package io.gen2spring.mcp.application.managed.execution;

import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ManagedToolExecutor implements AutoCloseable {
    private final ProviderCallClient client;
    private final ManagedExecutionLimits limits;
    private final RuntimeHttpRequestFactory requests;
    private final RuntimeResponseNormalizer responses;
    private final ThreadPoolExecutor executor;
    private final Clock clock;

    public ManagedToolExecutor(ProviderCallClient client, ManagedExecutionLimits limits) {
        this(client, limits, Clock.systemUTC());
    }

    ManagedToolExecutor(ProviderCallClient client, ManagedExecutionLimits limits, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requests = new RuntimeHttpRequestFactory();
        this.responses = new RuntimeResponseNormalizer();
        this.executor = new ThreadPoolExecutor(
                limits.workers(), limits.workers(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "managed-provider-call");
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public ManagedToolResult call(
            ManagedRuntimeBinding runtime,
            String toolName,
            Map<String, Object> arguments) {
        if (runtime == null || toolName == null || arguments == null
                || runtime.instance().stateAt(clock.instant())
                        != io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance.RuntimeState.ACTIVE) {
            throw new ManagedToolRequestInvalid();
        }
        RuntimeTool tool = runtime.metadata().document().tools().stream()
                .filter(candidate -> candidate.name().equals(toolName))
                .findFirst()
                .orElseThrow(ManagedToolRequestInvalid::new);
        ProviderCallRequest request;
        try {
            request = requests.create(tool, runtime.instance().providerBaseUrl(), arguments);
        } catch (RuntimeHttpRequestFactory.RuntimeRequestInvalid failure) {
            throw new ManagedToolRequestInvalid();
        }
        Future<ManagedToolResult> future;
        try {
            future = executor.submit(() -> execute(tool, request));
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            return responses.error(tool, ManagedToolResult.ErrorCategory.LOCAL_RESOURCE, null, null, null);
        }
        try {
            return future.get(limits.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            return responses.error(tool, ManagedToolResult.ErrorCategory.UPSTREAM_TIMEOUT, null, null, null);
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return responses.error(tool, ManagedToolResult.ErrorCategory.LOCAL_RESOURCE, null, null, null);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            if (cause instanceof ProviderCallClient.ProviderCallFailure provider) {
                return responses.error(tool, category(provider), null, null, null);
            }
            if (cause instanceof ManagedToolInternalFailure internal) {
                throw internal;
            }
            throw new ManagedToolInternalFailure(cause == null
                    ? "Unknown" : cause.getClass().getSimpleName());
        }
    }

    private ManagedToolResult execute(RuntimeTool tool, ProviderCallRequest request) {
        if (tool.pagination() != null) {
            return executePaginated(tool, request);
        }
        Attempt attempt = providerAttempt(tool, request);
        return attempt.result() == null ? responses.normalize(tool, attempt.response()) : attempt.result();
    }

    private Attempt providerAttempt(RuntimeTool tool, ProviderCallRequest request) {
        RetryPolicy retry = tool.retry();
        int retryCount = 0;
        while (true) {
            try {
                ProviderCallResponse response = client.execute(request, limits.timeout());
                if (retry != null && retryCount < retry.maxRetries()
                        && retry.statusCodes().contains(response.status())) {
                    backoff(retry, retryCount++, response.firstHeader("Retry-After"));
                    continue;
                }
                return new Attempt(response, null);
            } catch (Error fatal) {
                throw fatal;
            } catch (ProviderCallClient.ProviderCallFailure failure) {
                if (retry != null && retry.networkErrors() && retryCount < retry.maxRetries()
                        && failure.kind() != ProviderCallClient.ProviderCallFailure.Kind.PROTOCOL) {
                    backoff(retry, retryCount++, null);
                    continue;
                }
                return new Attempt(null, responses.error(tool, category(failure), null, null, null));
            } catch (RuntimeException failure) {
                throw new ManagedToolInternalFailure(failure.getClass().getSimpleName());
            }
        }
    }

    private ManagedToolResult executePaginated(RuntimeTool tool, ProviderCallRequest baseRequest) {
        PaginationPolicy policy = tool.pagination();
        PaginationAccumulator accumulator = new PaginationAccumulator(policy);
        Object cursor = policy.initialValue();
        ProviderCallResponse last = null;
        while (true) {
            ProviderCallRequest pageRequest = cursor == null
                    ? baseRequest : withQuery(baseRequest, policy.requestParameter(), cursor);
            Attempt attempt = providerAttempt(tool, pageRequest);
            if (attempt.result() != null) {
                return attempt.result();
            }
            last = attempt.response();
            if (last.status() < 200 || last.status() >= 300) {
                return responses.normalize(tool, last);
            }
            try {
                PaginationAccumulator.PageStep step = accumulator.append(last);
                if (step.terminal()) {
                    return responses.normalize(tool, new ProviderCallResponse(
                            last.status(), last.headers(), accumulator.bytes()));
                }
                cursor = step.nextValue();
            } catch (PaginationAccumulator.PageProtocolFailure failure) {
                return responses.error(tool, ManagedToolResult.ErrorCategory.UPSTREAM_PROTOCOL,
                        last.status(), null, null);
            } catch (PaginationAccumulator.PageResourceFailure failure) {
                return responses.error(tool, ManagedToolResult.ErrorCategory.LOCAL_RESOURCE,
                        last.status(), null, null);
            }
        }
    }

    private ProviderCallRequest withQuery(ProviderCallRequest request, String name, Object value) {
        String encoded = encode(name) + "=" + encode(String.valueOf(value));
        String ascii = request.uri().toASCIIString();
        String uri = ascii + (request.uri().getRawQuery() == null ? "?" : "&") + encoded;
        return new ProviderCallRequest(request.method(), URI.create(uri), request.headers(), request.body());
    }

    private String encode(String value) {
        StringBuilder result = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = current & 0xff;
            if (unsigned >= 'a' && unsigned <= 'z' || unsigned >= 'A' && unsigned <= 'Z'
                    || unsigned >= '0' && unsigned <= '9' || unsigned == '-' || unsigned == '.'
                    || unsigned == '_' || unsigned == '~') {
                result.append((char) unsigned);
            } else {
                result.append('%').append(String.format(Locale.ROOT, "%02X", unsigned));
            }
        }
        return result.toString();
    }

    private void backoff(RetryPolicy retry, int retryCount, String retryAfter) {
        long multiplier = 1L << retryCount;
        long exponential = Math.min(retry.maxBackoffMillis(), retry.initialBackoffMillis() * multiplier);
        long providerDelay = retry.respectRetryAfter() ? retryAfterMillis(retryAfter) : 0;
        long delay = Math.min(retry.maxBackoffMillis(), Math.max(exponential, providerDelay));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ProviderCallClient.ProviderCallFailure.timeout();
        }
    }

    private long retryAfterMillis(String value) {
        if (value == null || value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            return 0;
        }
        try {
            return Math.multiplyExact(Long.parseLong(value), 1_000L);
        } catch (ArithmeticException | NumberFormatException failure) {
            return 0;
        }
    }

    private ManagedToolResult.ErrorCategory category(ProviderCallClient.ProviderCallFailure failure) {
        return switch (failure.kind()) {
            case TIMEOUT -> ManagedToolResult.ErrorCategory.UPSTREAM_TIMEOUT;
            case UNAVAILABLE -> ManagedToolResult.ErrorCategory.UPSTREAM_UNAVAILABLE;
            case PROTOCOL -> ManagedToolResult.ErrorCategory.UPSTREAM_PROTOCOL;
        };
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Managed Tool executor did not stop");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Managed Tool executor did not stop");
        }
    }

    public static final class ManagedToolRequestInvalid extends RuntimeException {
        public ManagedToolRequestInvalid() {
            super("Managed Tool request is invalid", null, false, false);
        }
    }

    public static final class ManagedToolInternalFailure extends RuntimeException {
        private final String failureType;

        private ManagedToolInternalFailure(String failureType) {
            super("Managed Tool execution failed", null, false, false);
            this.failureType = failureType == null || failureType.isBlank() ? "Unknown" : failureType;
        }

        public String failureType() {
            return failureType;
        }
    }

    private record Attempt(ProviderCallResponse response, ManagedToolResult result) {}
}
