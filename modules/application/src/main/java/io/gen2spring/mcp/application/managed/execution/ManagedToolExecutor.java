package io.gen2spring.mcp.application.managed.execution;

import io.gen2spring.mcp.application.managed.credential.RuntimeCredentialResolver.ResolvedCredentials;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit.AuditStatus;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class ManagedToolExecutor implements AutoCloseable {
    private final ProviderCallClient client;
    private final ManagedExecutionLimits limits;
    private final RuntimeHttpRequestFactory requests;
    private final RuntimeResponseNormalizer responses;
    private final ThreadPoolExecutor executor;
    private final Clock clock;
    private final RuntimePolicyStore policies;
    private final Supplier<UUID> executionIds;

    public ManagedToolExecutor(ProviderCallClient client, ManagedExecutionLimits limits) {
        this(client, limits, Clock.systemUTC(), null, UUID::randomUUID);
    }

    public ManagedToolExecutor(ProviderCallClient client, ManagedExecutionLimits limits, Clock clock) {
        this(client, limits, clock, null, UUID::randomUUID);
    }

    public ManagedToolExecutor(
            ProviderCallClient client,
            ManagedExecutionLimits limits,
            RuntimePolicyStore policies,
            Clock clock,
            Supplier<UUID> executionIds) {
        this(client, limits, clock, Objects.requireNonNull(policies, "policies"), executionIds);
    }

    private ManagedToolExecutor(
            ProviderCallClient client,
            ManagedExecutionLimits limits,
            Clock clock,
            RuntimePolicyStore policies,
            Supplier<UUID> executionIds) {
        this.client = Objects.requireNonNull(client, "client");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policies = policies;
        this.executionIds = Objects.requireNonNull(executionIds, "executionIds");
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
        return submit(tool, request);
    }

    public ManagedToolResult call(
            ManagedExecutionContext context,
            String toolName,
            Map<String, Object> arguments) {
        if (policies == null || context == null || toolName == null || arguments == null
                || context.access().instance().stateAt(clock.instant())
                        != io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance.RuntimeState.ACTIVE
                || !context.access().allowedTools().contains(toolName)) {
            throw new ManagedToolRequestInvalid();
        }
        ManagedRuntimeBinding runtime = context.binding();
        RuntimeTool tool = runtime.metadata().document().tools().stream()
                .filter(candidate -> candidate.name().equals(toolName))
                .findFirst()
                .orElseThrow(ManagedToolRequestInvalid::new);
        ProviderCallRequest baseRequest;
        try {
            baseRequest = requests.create(tool, runtime.instance().providerBaseUrl(), arguments);
        } catch (RuntimeHttpRequestFactory.RuntimeRequestInvalid failure) {
            throw new ManagedToolRequestInvalid();
        }

        InstantPair timing = new InstantPair(clock.instant(), System.nanoTime());
        ToolExecutionAudit started = ToolExecutionAudit.start(
                Objects.requireNonNull(executionIds.get(), "executionId"), runtime.instance().owner(),
                runtime.instance().id(), context.access().grantId(), context.access().principal(),
                runtime.instance().catalogChecksum(), tool.name(), timing.startedAt());
        try {
            policies.startAudit(started);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new ManagedToolInternalFailure(failure.getClass().getSimpleName());
        }

        boolean acquired;
        try {
            acquired = policies.acquireRate(
                    runtime.instance().id(), context.access().grantId(), context.access().requestsPerMinute());
        } catch (Error fatal) {
            completeFatal(started, timing, baseRequest, fatal);
            throw fatal;
        } catch (RuntimeException failure) {
            ManagedToolInternalFailure primary = internal(failure);
            completeInternalPreserving(started, timing, baseRequest, primary);
            throw primary;
        }
        if (!acquired) {
            ManagedToolResult result = responses.error(
                    tool, ManagedToolResult.ErrorCategory.RATE_LIMITED, null, null, null);
            complete(started, AuditStatus.RATE_LIMITED, Optional.of("RATE_LIMITED"),
                    Optional.empty(), timing, baseRequest, result);
            return result;
        }

        ManagedToolResult result;
        ProviderCallRequest request = baseRequest;
        try (ResolvedCredentials resolved = context.credentials().resolve(runtime.instance(), tool)) {
            request = requests.create(tool, runtime.instance().providerBaseUrl(), arguments, resolved);
            result = submit(tool, request);
        } catch (Error fatal) {
            completeFatal(started, timing, request, fatal);
            throw fatal;
        } catch (ManagedToolInternalFailure failure) {
            completeInternalPreserving(started, timing, request, failure);
            throw failure;
        } catch (RuntimeException failure) {
            ManagedToolInternalFailure primary = internal(failure);
            completeInternalPreserving(started, timing, request, primary);
            throw primary;
        }

        AuditStatus terminal = result.error() ? AuditStatus.TOOL_ERROR : AuditStatus.SUCCEEDED;
        Optional<String> category = result.error()
                ? Optional.of(result.category().name()) : Optional.empty();
        complete(started, terminal, category, Optional.ofNullable(result.httpStatus()),
                timing, request, result);
        return result;
    }

    private ManagedToolResult submit(RuntimeTool tool, ProviderCallRequest request) {
        Future<ManagedToolResult> future;
        long deadline = System.nanoTime() + limits.timeout().toNanos();
        try {
            future = executor.submit(() -> execute(tool, request, deadline));
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            return responses.error(tool, ManagedToolResult.ErrorCategory.LOCAL_RESOURCE, null, null, null);
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return future.get(remaining, TimeUnit.NANOSECONDS);
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

    private void completeInternal(
            ToolExecutionAudit started,
            InstantPair timing,
            ProviderCallRequest request) {
        complete(started, AuditStatus.INTERNAL_ERROR, Optional.of("INTERNAL_ERROR"), Optional.empty(),
                timing, request, null);
    }

    private void completeInternalPreserving(
            ToolExecutionAudit started,
            InstantPair timing,
            ProviderCallRequest request,
            ManagedToolInternalFailure primary) {
        try {
            completeInternal(started, timing, request);
        } catch (Throwable completionFailure) {
            if (completionFailure != primary) primary.addSuppressed(completionFailure);
        }
    }

    private ManagedToolInternalFailure internal(RuntimeException failure) {
        return failure instanceof ManagedToolInternalFailure internal
                ? internal : new ManagedToolInternalFailure(failure.getClass().getSimpleName());
    }

    private void completeFatal(
            ToolExecutionAudit started,
            InstantPair timing,
            ProviderCallRequest request,
            Error fatal) {
        try {
            completeInternal(started, timing, request);
        } catch (Throwable completionFailure) {
            if (completionFailure != fatal) fatal.addSuppressed(completionFailure);
        }
    }

    private void complete(
            ToolExecutionAudit started,
            AuditStatus status,
            Optional<String> category,
            Optional<Integer> providerStatus,
            InstantPair timing,
            ProviderCallRequest request,
            ManagedToolResult result) {
        long duration = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timing.startedNanos()));
        long responseBytes = result == null ? 0 : result.json().length;
        ToolExecutionAudit terminal = started.complete(
                status, category, providerStatus, duration, request.body().length,
                responseBytes, clock.instant());
        try {
            if (!policies.completeAudit(terminal)) {
                throw new ManagedToolInternalFailure("AuditCompletionFailed");
            }
        } catch (ManagedToolInternalFailure failure) {
            throw failure;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new ManagedToolInternalFailure(failure.getClass().getSimpleName());
        }
    }

    private ManagedToolResult execute(RuntimeTool tool, ProviderCallRequest request, long deadline) {
        if (tool.pagination() != null) {
            return executePaginated(tool, request, deadline);
        }
        Attempt attempt = providerAttempt(tool, request, deadline);
        return attempt.result() == null ? responses.normalize(tool, attempt.response()) : attempt.result();
    }

    private Attempt providerAttempt(RuntimeTool tool, ProviderCallRequest request, long deadline) {
        RetryPolicy retry = tool.retry();
        int retryCount = 0;
        while (true) {
            try {
                Duration remaining = remaining(deadline);
                if (remaining == null) {
                    return new Attempt(null, responses.error(
                            tool, ManagedToolResult.ErrorCategory.UPSTREAM_TIMEOUT, null, null, null));
                }
                ProviderCallResponse response = client.execute(request, remaining);
                if (retry != null && retryCount < retry.maxRetries()
                        && retry.statusCodes().contains(response.status())) {
                    ManagedToolResult waitFailure = backoffResult(
                            tool, backoff(retry, retryCount++, response.firstHeader("Retry-After"), deadline));
                    if (waitFailure != null) return new Attempt(null, waitFailure);
                    continue;
                }
                return new Attempt(response, null);
            } catch (Error fatal) {
                throw fatal;
            } catch (ProviderCallClient.ProviderCallFailure failure) {
                if (retry != null && retry.networkErrors() && retryCount < retry.maxRetries()
                        && failure.kind() != ProviderCallClient.ProviderCallFailure.Kind.PROTOCOL) {
                    ManagedToolResult waitFailure = backoffResult(
                            tool, backoff(retry, retryCount++, null, deadline));
                    if (waitFailure != null) return new Attempt(null, waitFailure);
                    continue;
                }
                return new Attempt(null, responses.error(tool, category(failure), null, null, null));
            } catch (RuntimeException failure) {
                throw new ManagedToolInternalFailure(failure.getClass().getSimpleName());
            }
        }
    }

    private ManagedToolResult executePaginated(
            RuntimeTool tool, ProviderCallRequest baseRequest, long deadline) {
        PaginationPolicy policy = tool.pagination();
        PaginationAccumulator accumulator = new PaginationAccumulator(policy);
        Object cursor = policy.initialValue();
        ProviderCallResponse last = null;
        while (true) {
            ProviderCallRequest pageRequest = cursor == null
                    ? baseRequest : withQuery(baseRequest, policy.requestParameter(), cursor);
            Attempt attempt = providerAttempt(tool, pageRequest, deadline);
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

    private WaitResult backoff(RetryPolicy retry, int retryCount, String retryAfter, long deadline) {
        long multiplier = 1L << retryCount;
        long exponential = Math.min(retry.maxBackoffMillis(), retry.initialBackoffMillis() * multiplier);
        long providerDelay = retry.respectRetryAfter() ? retryAfterMillis(retryAfter) : 0;
        long delay = Math.min(retry.maxBackoffMillis(), Math.max(exponential, providerDelay));
        long delayNanos = TimeUnit.MILLISECONDS.toNanos(delay);
        if (deadline - System.nanoTime() <= delayNanos) return WaitResult.DEADLINE;
        try {
            TimeUnit.NANOSECONDS.sleep(delayNanos);
            return WaitResult.READY;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return WaitResult.INTERRUPTED;
        }
    }

    private ManagedToolResult backoffResult(RuntimeTool tool, WaitResult wait) {
        return switch (wait) {
            case READY -> null;
            case DEADLINE -> responses.error(
                    tool, ManagedToolResult.ErrorCategory.UPSTREAM_TIMEOUT, null, null, null);
            case INTERRUPTED -> responses.error(
                    tool, ManagedToolResult.ErrorCategory.LOCAL_RESOURCE, null, null, null);
        };
    }

    private Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos < TimeUnit.MILLISECONDS.toNanos(10) ? null : Duration.ofNanos(nanos);
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
            super("Managed Tool execution failed", null, true, false);
            this.failureType = failureType == null || failureType.isBlank() ? "Unknown" : failureType;
        }

        public String failureType() {
            return failureType;
        }
    }

    private record Attempt(ProviderCallResponse response, ManagedToolResult result) {}

    private record InstantPair(java.time.Instant startedAt, long startedNanos) {}

    private enum WaitResult {
        READY,
        DEADLINE,
        INTERRUPTED
    }
}
