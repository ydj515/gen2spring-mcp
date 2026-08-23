package io.gen2spring.mcp.adapter.emitter.springai2;

final class ReactiveExecutorSourceRenderer {
    private final ReactiveRuntimeConfigurationRenderer configurationRenderer =
            new ReactiveRuntimeConfigurationRenderer();
    private final ReactiveHttpClientSourceRenderer httpClientRenderer = new ReactiveHttpClientSourceRenderer();
    private final ReactiveResponseSourceRenderer responseRenderer = new ReactiveResponseSourceRenderer();
    private final ReactiveRetrySourceRenderer retryRenderer = new ReactiveRetrySourceRenderer();
    private final ReactivePaginationSourceRenderer paginationRenderer = new ReactivePaginationSourceRenderer();

    String render(
            String packageName,
            boolean hasTypedOutputs,
            boolean hasRetryPolicies,
            boolean hasPaginationPolicies) {
        String typedExecute = hasTypedOutputs ? typedExecute() : "";
        boolean hasPolicies = hasRetryPolicies || hasPaginationPolicies;
        String retry = hasPolicies ? retryRenderer.render() : "";
        String pagination = hasPaginationPolicies ? paginationRenderer.render() : "";
        return """
                package %s.runtime;

                import com.fasterxml.jackson.annotation.JsonInclude;
                import io.netty.channel.ChannelOption;
                import io.netty.channel.ConnectTimeoutException;
                import io.netty.handler.timeout.ReadTimeoutException;
                import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
                import io.micrometer.observation.ObservationRegistry;
                import io.micrometer.tracing.Tracer;
                import jakarta.annotation.PreDestroy;
                import java.io.IOException;
                import java.lang.reflect.Array;
                import java.net.URI;
                import java.time.Duration;
                import java.util.ArrayList;
                import java.util.Collections;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Locale;
                import java.util.Map;
                import java.util.Set;
                import java.util.concurrent.TimeoutException;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.core.env.Environment;
                import org.springframework.core.io.buffer.DataBufferLimitException;
                import org.springframework.http.HttpHeaders;
                import org.springframework.http.HttpMethod;
                import org.springframework.http.InvalidMediaTypeException;
                import org.springframework.http.MediaType;
                import org.springframework.http.client.reactive.ReactorClientHttpConnector;
                import org.springframework.stereotype.Component;
                import org.springframework.web.reactive.function.client.WebClient;
                import org.springframework.web.reactive.function.client.WebClientRequestException;
                import org.springframework.web.util.UriComponentsBuilder;
                import reactor.core.publisher.Mono;
                import reactor.netty.http.client.HttpClient;
                import reactor.netty.resources.ConnectionProvider;
                import tools.jackson.core.JacksonException;
                import tools.jackson.databind.JsonNode;
                import tools.jackson.databind.json.JsonMapper;
                import tools.jackson.databind.node.ArrayNode;
                import tools.jackson.databind.node.ObjectNode;

                @Component
                public final class OpenApiOperationExecutor {
                    private final WebClient webClient;
                    private final Environment environment;
                    private final JsonMapper jsonMapper;
                    private final ResponseNormalizer responseNormalizer;
                    private final RuntimeTelemetry runtimeTelemetry;
                    private final URI baseUrl;
                    private final int responseMaxBytes;
                    private final long totalTimeoutMillis;
                    private final ConnectionProvider connectionProvider;

                    public OpenApiOperationExecutor(WebClient.Builder builder, Environment environment) {
                        this(builder, environment, new RuntimeTelemetry(
                                ObservationRegistry.NOOP, new SimpleMeterRegistry(), Tracer.NOOP));
                    }

                    @Autowired
                    public OpenApiOperationExecutor(
                            WebClient.Builder builder,
                            Environment environment,
                            RuntimeTelemetry runtimeTelemetry) {
                %s
                    }

                %s

                %s
                    private Map<String, Object> immutableArguments(Map<String, Object> arguments) {
                        if (arguments == null || arguments.isEmpty()) {
                            return Map.of();
                        }
                        return Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
                    }

                %s

                %s

                %s

                %s

                    private URI requireHttpUri(String value) {
                        URI uri;
                        try {
                            uri = URI.create(value);
                        } catch (IllegalArgumentException exception) {
                            throw new IllegalStateException("PROVIDER_BASE_URL_INVALID");
                        }
                        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                                || uri.getHost() == null || uri.getUserInfo() != null) {
                            throw new IllegalStateException("PROVIDER_BASE_URL_INVALID");
                        }
                        return uri;
                    }

                    private int requireResponseLimit(Integer value) {
                        if (value == null || value <= 0 || value == Integer.MAX_VALUE) {
                            throw new IllegalStateException("PROVIDER_RESPONSE_LIMIT_INVALID");
                        }
                        return value;
                    }

                    private long requireTimeout(Long value) {
                        if (value == null || value <= 0 || value > 300_000) {
                            throw new IllegalStateException("PROVIDER_TIMEOUT_INVALID");
                        }
                        return value;
                    }

                    private int requireRequestCapacity(Integer value) {
                        if (value == null || value <= 0 || value > 256) {
                            throw new IllegalStateException("PROVIDER_REQUEST_CAPACITY_INVALID");
                        }
                        return value;
                    }

                    @PreDestroy
                    void shutdown() {
                        connectionProvider.dispose();
                    }

                    private record RequestBodyValue(boolean present, Object value) {
                        private static RequestBodyValue absent() {
                            return new RequestBodyValue(false, null);
                        }
                    }

                    private record ProviderAttempt(
                            OperationOutcome outcome,
                            Integer httpStatus,
                            Integer responseBytes,
                            String retryAfter,
                            byte[] responseBody,
                            String contentType) {
                        private ProviderAttempt(
                                OperationOutcome outcome,
                                Integer httpStatus,
                                Integer responseBytes) {
                            this(outcome, httpStatus, responseBytes, null, null, null);
                        }
                    }

                    private static final class ResponseTooLargeException extends RuntimeException {
                        private final int status;

                        private ResponseTooLargeException(int status) {
                            super("Generated provider response exceeded its configured limit");
                            this.status = status;
                        }

                        private int status() {
                            return status;
                        }
                    }

                    private static final class RequiredSecretException extends RuntimeException {}

                    private static final class RequestSerializationException extends RuntimeException {}
                }
                """.formatted(
                packageName,
                configurationRenderer.render(),
                executeMethod(hasPolicies, hasPaginationPolicies),
                typedExecute,
                httpClientRenderer.render(hasPolicies, hasPaginationPolicies),
                responseRenderer.render(),
                retry,
                pagination);
    }

    private String executeMethod(boolean hasPolicies, boolean hasPaginationPolicies) {
        if (hasPolicies) {
            String operation = hasPaginationPolicies
                    ? """
                                Mono<OperationOutcome> outcome = operation.paginationPolicy() == null
                                        ? executeWithRetry(
                                                operation,
                                                safeArguments,
                                                secretNames,
                                                secretValues,
                                                null,
                                                deadlineNanos,
                                                0).map(ProviderAttempt::outcome)
                                        : executePaginated(
                                                operation,
                                                safeArguments,
                                                secretNames,
                                                secretValues,
                                                deadlineNanos);
                    """
                    : """
                                Mono<OperationOutcome> outcome = executeWithRetry(
                                        operation,
                                        safeArguments,
                                        secretNames,
                                        secretValues,
                                        null,
                                        deadlineNanos,
                                        0).map(ProviderAttempt::outcome);
                    """;
            return """
                    public Mono<JsonNode> execute(OperationDefinition operation, Map<String, Object> arguments) {
                        Map<String, Object> safeArguments = immutableArguments(arguments);
                        return Mono.defer(() -> {
                            List<String> secretNames = new ArrayList<>();
                            List<String> secretValues = new ArrayList<>();
                            long deadlineNanos = System.nanoTime()
                                    + Duration.ofMillis(totalTimeoutMillis).toNanos();
                %s
                            return outcome.flatMap(result -> {
                                if (result instanceof NormalizedSuccess success) {
                                    return Mono.just(success.payload());
                                }
                                return Mono.error(new ProviderErrorException((ProviderError) result));
                            });
                        });
                    }
                    """.formatted(operation);
        }
        return """
                    public Mono<JsonNode> execute(OperationDefinition operation, Map<String, Object> arguments) {
                        Map<String, Object> safeArguments = immutableArguments(arguments);
                        return Mono.defer(() -> {
                            List<String> secretNames = new ArrayList<>();
                            List<String> secretValues = new ArrayList<>();
                            RuntimeTelemetry.Call providerCall = runtimeTelemetry.startProviderCall(
                                    operation.operationId(), operation.method());
                            Mono<OperationOutcome> outcome;
                            try (var ignored = providerCall.openScope()) {
                                outcome = executeOnce(operation, safeArguments, secretNames, secretValues)
                                        .timeout(Duration.ofMillis(totalTimeoutMillis))
                                        .map(attempt -> completeProviderCall(providerCall, attempt))
                                        .onErrorResume(failure -> Mono.just(completeFailure(
                                                providerCall,
                                                operation,
                                                failure,
                                                secretNames,
                                                secretValues)));
                            }
                            return outcome
                                    .doOnCancel(() -> providerCall.complete(
                                            RuntimeTelemetry.Outcome.CANCELLED,
                                            RuntimeTelemetry.ErrorCategory.NONE,
                                            RuntimeTelemetry.HttpStatusClass.NONE))
                                    .flatMap(result -> {
                                        if (result instanceof NormalizedSuccess success) {
                                            return Mono.just(success.payload());
                                        }
                                        return Mono.error(new ProviderErrorException((ProviderError) result));
                                    });
                        });
                    }
                """;
    }

    private String typedExecute() {
        return """
                    public <T> Mono<T> execute(
                            OperationDefinition operation,
                            Map<String, Object> arguments,
                            Class<T> resultType) {
                        return execute(operation, arguments).flatMap(result -> {
                            try {
                                return Mono.just(jsonMapper.treeToValue(result, resultType));
                            } catch (JacksonException failure) {
                                return Mono.error(new IllegalStateException(
                                        "Generated Tool result conversion failed"));
                            }
                        });
                    }

                """;
    }
}
