package io.gen2spring.mcp.springai1;

import java.util.LinkedHashMap;
import java.util.Map;

final class RuntimeSourceRenderer {
    Map<String, String> render(
            String packageName, String packagePath, String domainClass, String contextOperationId) {
        Map<String, String> sources = new LinkedHashMap<>();
        String runtimePath = "src/main/java/" + packagePath + "/runtime/";
        sources.put(runtimePath + "ParameterLocation.java", parameterLocation(packageName));
        sources.put(runtimePath + "ParameterBinding.java", parameterBinding(packageName));
        sources.put(runtimePath + "SecretBinding.java", secretBinding(packageName));
        sources.put(runtimePath + "OperationDefinition.java", operationDefinition(packageName));
        sources.put(runtimePath + "OpenApiOperationExecutor.java", executor(packageName));
        sources.put("src/main/java/" + packagePath + "/application/" + domainClass + "McpApplication.java",
                application(packageName, domainClass));
        sources.put("src/test/java/" + packagePath + "/application/" + domainClass + "McpApplicationTest.java",
                contextTest(packageName, domainClass, contextOperationId));
        return sources;
    }

    private String parameterLocation(String packageName) {
        return """
                package %s.runtime;

                public enum ParameterLocation {
                    PATH,
                    QUERY,
                    HEADER,
                    BODY
                }
                """.formatted(packageName);
    }

    private String parameterBinding(String packageName) {
        return """
                package %s.runtime;

                import java.util.Objects;

                public record ParameterBinding(
                        String sourceName,
                        ParameterLocation targetLocation,
                        String targetName) {
                    public ParameterBinding {
                        Objects.requireNonNull(sourceName, "sourceName");
                        Objects.requireNonNull(targetLocation, "targetLocation");
                        Objects.requireNonNull(targetName, "targetName");
                    }
                }
                """.formatted(packageName);
    }

    private String secretBinding(String packageName) {
        return """
                package %s.runtime;

                import java.util.Objects;

                public record SecretBinding(
                        String propertyName,
                        ParameterLocation targetLocation,
                        String targetName,
                        boolean required) {
                    public SecretBinding {
                        Objects.requireNonNull(propertyName, "propertyName");
                        Objects.requireNonNull(targetLocation, "targetLocation");
                        Objects.requireNonNull(targetName, "targetName");
                    }
                }
                """.formatted(packageName);
    }

    private String operationDefinition(String packageName) {
        return """
                package %s.runtime;

                import java.util.List;
                import java.util.Objects;

                public record OperationDefinition(
                        String operationId,
                        String method,
                        String path,
                        List<ParameterBinding> parameterBindings,
                        List<SecretBinding> secretBindings,
                        boolean objectRequestBody,
                        boolean requestBodyRequired,
                        ResponseNormalizationPolicy responseNormalization) {
                    public OperationDefinition(
                            String method,
                            String path,
                            List<ParameterBinding> parameterBindings,
                            List<SecretBinding> secretBindings) {
                        this("unknown", method, path, parameterBindings, secretBindings, false, false, null);
                    }

                    public OperationDefinition(
                            String method,
                            String path,
                            List<ParameterBinding> parameterBindings,
                            List<SecretBinding> secretBindings,
                            boolean objectRequestBody) {
                        this("unknown", method, path, parameterBindings, secretBindings,
                                objectRequestBody, objectRequestBody, null);
                    }

                    public OperationDefinition(
                            String method,
                            String path,
                            List<ParameterBinding> parameterBindings,
                            List<SecretBinding> secretBindings,
                            boolean objectRequestBody,
                            boolean requestBodyRequired) {
                        this("unknown", method, path, parameterBindings, secretBindings,
                                objectRequestBody, requestBodyRequired, null);
                    }

                    public OperationDefinition {
                        Objects.requireNonNull(operationId, "operationId");
                        Objects.requireNonNull(method, "method");
                        Objects.requireNonNull(path, "path");
                        parameterBindings = List.copyOf(parameterBindings);
                        secretBindings = List.copyOf(secretBindings);
                    }
                }
                """.formatted(packageName);
    }

    private String executor(String packageName) {
        return """
                package %s.runtime;

                import com.fasterxml.jackson.annotation.JsonInclude;
                import java.io.IOException;
                import java.io.InputStream;
                import java.lang.reflect.Array;
                import java.net.SocketTimeoutException;
                import java.net.URI;
                import java.net.http.HttpClient;
                import java.net.http.HttpTimeoutException;
                import java.time.Duration;
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Locale;
                import java.util.Map;
                import java.util.Set;
                import java.util.concurrent.ArrayBlockingQueue;
                import java.util.concurrent.ExecutionException;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Future;
                import java.util.concurrent.RejectedExecutionException;
                import java.util.concurrent.TimeUnit;
                import java.util.concurrent.TimeoutException;
                import java.util.concurrent.ThreadPoolExecutor;
                import io.micrometer.context.ContextExecutorService;
                import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
                import io.micrometer.observation.ObservationRegistry;
                import io.micrometer.tracing.Tracer;
                import jakarta.annotation.PreDestroy;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.core.env.Environment;
                import org.springframework.http.HttpHeaders;
                import org.springframework.http.HttpMethod;
                import org.springframework.http.InvalidMediaTypeException;
                import org.springframework.http.MediaType;
                import org.springframework.http.client.JdkClientHttpRequestFactory;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.ResourceAccessException;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.util.UriComponentsBuilder;
                import com.fasterxml.jackson.core.JsonProcessingException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.json.JsonMapper;

                @Component
                public final class OpenApiOperationExecutor {
                    private final RestClient restClient;
                    private final Environment environment;
                    private final JsonMapper jsonMapper;
                    private final ResponseNormalizer responseNormalizer;
                    private final RuntimeTelemetry runtimeTelemetry;
                    private final URI baseUrl;
                    private final int responseMaxBytes;
                    private final long totalTimeoutMillis;
                    private final ThreadPoolExecutor rawRequestExecutor;
                    private final ExecutorService requestExecutor;

                    public OpenApiOperationExecutor(RestClient.Builder builder, Environment environment) {
                        this(builder, environment, new RuntimeTelemetry(
                                ObservationRegistry.NOOP, new SimpleMeterRegistry(), Tracer.NOOP));
                    }

                    @Autowired
                    public OpenApiOperationExecutor(
                            RestClient.Builder builder,
                            Environment environment,
                            RuntimeTelemetry runtimeTelemetry) {
                        this.environment = environment;
                        this.runtimeTelemetry = java.util.Objects.requireNonNull(runtimeTelemetry);
                        this.jsonMapper = JsonMapper.builder()
                                .serializationInclusion(JsonInclude.Include.NON_NULL)
                                .build();
                        this.responseNormalizer = new ResponseNormalizer(runtimeTelemetry);
                        this.baseUrl = requireHttpUri(environment.getRequiredProperty("provider.base-url"));
                        this.responseMaxBytes = requireResponseLimit(
                                environment.getRequiredProperty("provider.response-max-bytes", Integer.class));
                        long connectTimeoutMillis = requireTimeout(
                                environment.getRequiredProperty("provider.connect-timeout-millis", Long.class));
                        long readTimeoutMillis = requireTimeout(
                                environment.getRequiredProperty("provider.read-timeout-millis", Long.class));
                        this.totalTimeoutMillis = requireTimeout(
                                environment.getRequiredProperty("provider.total-timeout-millis", Long.class));
                        int maxConcurrentRequests = requireRequestCapacity(
                                environment.getRequiredProperty("provider.max-concurrent-requests", Integer.class));
                        int maxQueuedRequests = requireRequestCapacity(
                                environment.getRequiredProperty("provider.max-queued-requests", Integer.class));
                        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                                .build());
                        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
                        this.restClient = builder.requestFactory(requestFactory)
                                .observationRegistry(ObservationRegistry.NOOP)
                                .build();
                        this.rawRequestExecutor = new ThreadPoolExecutor(
                                maxConcurrentRequests,
                                maxConcurrentRequests,
                                0L,
                                TimeUnit.MILLISECONDS,
                                new ArrayBlockingQueue<>(maxQueuedRequests),
                                runnable -> {
                            Thread thread = new Thread(runnable, "openapi-upstream-request");
                            thread.setDaemon(true);
                            return thread;
                                },
                                new ThreadPoolExecutor.AbortPolicy());
                        runtimeTelemetry.registerExecutor(rawRequestExecutor);
                        this.requestExecutor = ContextExecutorService.wrap(rawRequestExecutor);
                    }

                    public JsonNode execute(OperationDefinition operation, Map<String, Object> arguments) {
                        List<String> secretNames = new ArrayList<>();
                        List<String> secretValues = new ArrayList<>();
                        try {
                            OperationOutcome outcome = await(
                                    operation,
                                    arguments == null ? Map.of() : arguments,
                                    secretNames,
                                    secretValues);
                            if (outcome instanceof NormalizedSuccess success) {
                                return success.payload();
                            }
                            throw new ProviderErrorException((ProviderError) outcome);
                        } catch (ProviderErrorException failure) {
                            throw failure;
                        } catch (RejectedExecutionException failure) {
                            throw providerFailure(
                                    operation,
                                    ProviderErrorCategory.LOCAL_RESOURCE,
                                    null,
                                    secretNames,
                                    secretValues);
                        }
                    }

                    private OperationOutcome await(
                            OperationDefinition operation,
                            Map<String, Object> arguments,
                            List<String> secretNames,
                            List<String> secretValues) {
                        RuntimeTelemetry.Call providerCall = runtimeTelemetry.startProviderCall(operation.operationId(), operation.method());
                        Future<ProviderAttempt> request;
                        try (var ignored = providerCall.openScope()) {
                            try {
                                request = requestExecutor.submit(
                                        () -> executeSafely(operation, arguments, secretNames, secretValues));
                            } catch (RejectedExecutionException failure) {
                                providerCall.complete(
                                        RuntimeTelemetry.Outcome.EXPECTED_ERROR,
                                        RuntimeTelemetry.ErrorCategory.LOCAL_RESOURCE,
                                        RuntimeTelemetry.HttpStatusClass.NONE);
                                return providerError(
                                        operation,
                                        ProviderErrorCategory.LOCAL_RESOURCE,
                                        null,
                                        secretNames,
                                        secretValues);
                            }
                        }
                        try {
                            return completeProviderCall(providerCall,
                                    request.get(totalTimeoutMillis, TimeUnit.MILLISECONDS));
                        } catch (TimeoutException failure) {
                            request.cancel(true);
                            providerCall.complete(
                                    RuntimeTelemetry.Outcome.EXPECTED_ERROR,
                                    RuntimeTelemetry.ErrorCategory.UPSTREAM_TIMEOUT,
                                    RuntimeTelemetry.HttpStatusClass.NONE);
                            return providerError(
                                    operation,
                                    ProviderErrorCategory.UPSTREAM_TIMEOUT,
                                    null,
                                    secretNames,
                                    secretValues);
                        } catch (InterruptedException failure) {
                            request.cancel(true);
                            Thread.currentThread().interrupt();
                            providerCall.complete(
                                    RuntimeTelemetry.Outcome.EXPECTED_ERROR,
                                    RuntimeTelemetry.ErrorCategory.LOCAL_RESOURCE,
                                    RuntimeTelemetry.HttpStatusClass.NONE);
                            return providerError(
                                    operation,
                                    ProviderErrorCategory.LOCAL_RESOURCE,
                                    null,
                                    secretNames,
                                    secretValues);
                        } catch (ExecutionException failure) {
                            try {
                                OperationOutcome outcome = mapFailure(
                                        operation, failure.getCause(), secretNames, secretValues);
                                Integer status = outcome instanceof ProviderError providerError
                                        ? providerError.httpStatus() : null;
                                return completeProviderCall(
                                        providerCall, new ProviderAttempt(outcome, status, null));
                            } catch (Error fatal) {
                                providerCall.complete(
                                        RuntimeTelemetry.Outcome.FATAL,
                                        RuntimeTelemetry.ErrorCategory.FATAL,
                                        RuntimeTelemetry.HttpStatusClass.NONE);
                                throw fatal;
                            } catch (RuntimeException runtimeFailure) {
                                providerCall.complete(
                                        RuntimeTelemetry.Outcome.INTERNAL_ERROR,
                                        RuntimeTelemetry.ErrorCategory.UNEXPECTED_RUNTIME,
                                        RuntimeTelemetry.HttpStatusClass.NONE);
                                throw runtimeFailure;
                            }
                        }
                    }

                    private ProviderAttempt executeSafely(
                            OperationDefinition operation,
                            Map<String, Object> arguments,
                            List<String> secretNames,
                            List<String> secretValues) {
                        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUri(baseUrl).path(operation.path());
                        Map<String, Object> pathVariables = new LinkedHashMap<>();
                        HttpHeaders headers = new HttpHeaders();
                        Object requestBody = operation.objectRequestBody() && operation.requestBodyRequired()
                                ? new LinkedHashMap<String, Object>() : null;

                        for (ParameterBinding binding : operation.parameterBindings()) {
                            Object value = arguments.get(binding.sourceName());
                            if (value == null) {
                                continue;
                            }
                            requestBody = bind(uriBuilder, pathVariables, headers, binding.targetLocation(),
                                    binding.targetName(), value, requestBody, operation.objectRequestBody());
                        }
                        for (SecretBinding binding : operation.secretBindings()) {
                            secretNames.add(binding.propertyName());
                            secretNames.add(binding.targetName());
                            String value = environment.getProperty(binding.propertyName());
                            if (value == null || value.isBlank()) {
                                if (binding.required()) {
                                    throw new RequiredSecretException();
                                }
                                continue;
                            }
                            secretValues.add(value);
                            requestBody = bind(uriBuilder, pathVariables, headers, binding.targetLocation(),
                                    binding.targetName(), value, requestBody, operation.objectRequestBody());
                        }

                        removePropagationHeaders(headers);
                        String traceparent = runtimeTelemetry.currentTraceparent();
                        if (traceparent != null) {
                            headers.set("traceparent", traceparent);
                        }

                        URI uri = uriBuilder.encode().buildAndExpand(pathVariables).toUri();
                        RestClient.RequestBodySpec request = restClient
                                .method(HttpMethod.valueOf(operation.method()))
                                .uri(uri);
                        request.headers(target -> {
                            target.addAll(headers);
                            target.setAccept(List.of(MediaType.APPLICATION_JSON));
                        });
                        if (requestBody != null && allowsBody(operation.method())) {
                            try {
                                request.contentType(MediaType.APPLICATION_JSON);
                                request.body(jsonMapper.writeValueAsBytes(requestBody));
                            } catch (JsonProcessingException exception) {
                                throw new RequestSerializationException();
                            }
                        }

                        RawResponse response = request.exchange((ignoredRequest, upstreamResponse) -> {
                            int status = upstreamResponse.getStatusCode().value();
                            return new RawResponse(
                                    status,
                                    upstreamResponse.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                                    readBounded(upstreamResponse.getBody(), status));
                        });
                        if (response == null) {
                            return new ProviderAttempt(providerError(
                                    operation,
                                    ProviderErrorCategory.UPSTREAM_PROTOCOL,
                                    null,
                                    secretNames,
                                    secretValues), null, null);
                        }
                        return new ProviderAttempt(
                                responseNormalizer.normalize(
                                        operation,
                                        response.status(),
                                        parseContentType(response.contentType()),
                                        response.body(),
                                        secretNames,
                                        secretValues),
                                response.status(),
                                response.body().length);
                    }

                    private OperationOutcome completeProviderCall(
                            RuntimeTelemetry.Call providerCall,
                            ProviderAttempt attempt) {
                        RuntimeTelemetry.HttpStatusClass statusClass = statusClass(attempt.httpStatus());
                        if (attempt.httpStatus() != null) {
                            providerCall.responseStatus(attempt.httpStatus());
                        }
                        if (attempt.responseBytes() != null) {
                            runtimeTelemetry.recordResponseBytes(statusClass, attempt.responseBytes());
                        }
                        if (attempt.outcome() instanceof NormalizedSuccess) {
                            providerCall.complete(
                                    RuntimeTelemetry.Outcome.SUCCESS,
                                    RuntimeTelemetry.ErrorCategory.NONE,
                                    statusClass);
                        } else {
                            ProviderError providerError = (ProviderError) attempt.outcome();
                            providerCall.complete(
                                    RuntimeTelemetry.Outcome.EXPECTED_ERROR,
                                    RuntimeTelemetry.ErrorCategory.valueOf(providerError.category().name()),
                                    statusClass);
                        }
                        return attempt.outcome();
                    }

                    private RuntimeTelemetry.HttpStatusClass statusClass(Integer status) {
                        if (status == null) {
                            return RuntimeTelemetry.HttpStatusClass.NONE;
                        }
                        if (status >= 200 && status < 300) {
                            return RuntimeTelemetry.HttpStatusClass.SUCCESS;
                        }
                        if (status >= 400 && status < 500) {
                            return RuntimeTelemetry.HttpStatusClass.CLIENT_ERROR;
                        }
                        if (status >= 500 && status < 600) {
                            return RuntimeTelemetry.HttpStatusClass.SERVER_ERROR;
                        }
                        return RuntimeTelemetry.HttpStatusClass.OTHER;
                    }

                    private void removePropagationHeaders(HttpHeaders headers) {
                        List<String> namesToRemove = new ArrayList<>();
                        headers.forEach((name, ignored) -> {
                            String normalized = name.toLowerCase(Locale.ROOT);
                            if (Set.of("traceparent", "tracestate", "baggage", "b3").contains(normalized)
                                    || normalized.startsWith("x-b3-")) {
                                namesToRemove.add(name);
                            }
                        });
                        namesToRemove.forEach(headers::remove);
                    }

                    private MediaType parseContentType(String contentType) {
                        if (contentType == null) {
                            return null;
                        }
                        try {
                            return MediaType.parseMediaType(contentType);
                        } catch (InvalidMediaTypeException failure) {
                            return null;
                        }
                    }

                    private Object bind(
                            UriComponentsBuilder uriBuilder,
                            Map<String, Object> pathVariables,
                            HttpHeaders headers,
                            ParameterLocation location,
                            String targetName,
                            Object value,
                            Object requestBody,
                            boolean objectRequestBody) {
                        return switch (location) {
                            case PATH -> {
                                pathVariables.put(targetName, value);
                                yield requestBody;
                            }
                            case QUERY -> {
                                addQueryValues(uriBuilder, targetName, value);
                                yield requestBody;
                            }
                            case HEADER -> {
                                addHeaderValues(headers, targetName, value);
                                yield requestBody;
                            }
                            case BODY -> {
                                if (!objectRequestBody) {
                                    yield value;
                                }
                                Map<String, Object> objectBody = new LinkedHashMap<>();
                                if (requestBody instanceof Map<?, ?> existing) {
                                    for (Map.Entry<?, ?> entry : existing.entrySet()) {
                                        if (entry.getKey() instanceof String property) {
                                            objectBody.put(property, entry.getValue());
                                        }
                                    }
                                }
                                objectBody.put(targetName, value);
                                yield objectBody;
                            }
                        };
                    }

                    private void addQueryValues(UriComponentsBuilder builder, String name, Object value) {
                        if (value instanceof Iterable<?> values) {
                            values.forEach(item -> builder.queryParam(name, item));
                        } else if (value.getClass().isArray()) {
                            for (int index = 0; index < Array.getLength(value); index++) {
                                builder.queryParam(name, Array.get(value, index));
                            }
                        } else {
                            builder.queryParam(name, value);
                        }
                    }

                    private void addHeaderValues(HttpHeaders headers, String name, Object value) {
                        if (value instanceof Iterable<?> values) {
                            values.forEach(item -> headers.add(name, String.valueOf(item)));
                        } else if (value.getClass().isArray()) {
                            for (int index = 0; index < Array.getLength(value); index++) {
                                headers.add(name, String.valueOf(Array.get(value, index)));
                            }
                        } else {
                            headers.add(name, String.valueOf(value));
                        }
                    }

                    private byte[] readBounded(InputStream input, int status) throws IOException {
                        byte[] bytes = input.readNBytes(responseMaxBytes + 1);
                        if (bytes.length > responseMaxBytes) {
                            throw new ResponseTooLargeException(status);
                        }
                        return bytes;
                    }

                    private boolean allowsBody(String method) {
                        return "POST".equals(method) || "PUT".equals(method)
                                || "PATCH".equals(method) || "DELETE".equals(method);
                    }

                    private OperationOutcome mapFailure(
                            OperationDefinition operation,
                            Throwable failure,
                            List<String> secretNames,
                            List<String> secretValues) {
                        Error error = findCause(failure, Error.class);
                        if (error != null) {
                            throw error;
                        }
                        ResponseTooLargeException tooLarge = findCause(
                                failure, ResponseTooLargeException.class);
                        if (tooLarge != null) {
                            if (tooLarge.status() < 200 || tooLarge.status() >= 300) {
                                return responseNormalizer.normalize(
                                        operation,
                                        tooLarge.status(),
                                        null,
                                        new byte[0],
                                        secretNames,
                                        secretValues);
                            }
                            return providerError(
                                    operation,
                                    ProviderErrorCategory.UPSTREAM_PROTOCOL,
                                    tooLarge.status(),
                                    secretNames,
                                    secretValues);
                        }
                        if (hasCause(failure, SocketTimeoutException.class)
                                || hasCause(failure, HttpTimeoutException.class)) {
                            return providerError(
                                    operation,
                                    ProviderErrorCategory.UPSTREAM_TIMEOUT,
                                    null,
                                    secretNames,
                                    secretValues);
                        }
                        if (hasCause(failure, ResourceAccessException.class)
                                || hasCause(failure, IOException.class)) {
                            return providerError(
                                    operation,
                                    ProviderErrorCategory.UPSTREAM_UNAVAILABLE,
                                    null,
                                    secretNames,
                                    secretValues);
                        }
                        if (hasCause(failure, RequiredSecretException.class)) {
                            return providerError(
                                    operation,
                                    ProviderErrorCategory.LOCAL_RESOURCE,
                                    null,
                                    secretNames,
                                    secretValues);
                        }
                        if (hasCause(failure, RequestSerializationException.class)) {
                            return providerError(
                                    operation,
                                    ProviderErrorCategory.UPSTREAM_PROTOCOL,
                                    null,
                                    secretNames,
                                    secretValues);
                        }
                        if (failure instanceof RuntimeException runtimeFailure) {
                            throw runtimeFailure;
                        }
                        throw new IllegalStateException("Generated upstream execution failed");
                    }

                    private OperationOutcome providerError(
                            OperationDefinition operation,
                            ProviderErrorCategory category,
                            Integer status,
                            List<String> secretNames,
                            List<String> secretValues) {
                        return responseNormalizer.error(
                                operation,
                                category,
                                status,
                                null,
                                null,
                                secretNames,
                                secretValues);
                    }

                    private ProviderErrorException providerFailure(
                            OperationDefinition operation,
                            ProviderErrorCategory category,
                            Integer status,
                            List<String> secretNames,
                            List<String> secretValues) {
                        return new ProviderErrorException((ProviderError) providerError(
                                operation, category, status, secretNames, secretValues));
                    }

                    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
                        return findCause(failure, type) != null;
                    }

                    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
                        Throwable current = failure;
                        while (current != null) {
                            if (type.isInstance(current)) {
                                return type.cast(current);
                            }
                            Throwable cause = current.getCause();
                            if (cause == current) {
                                break;
                            }
                            current = cause;
                        }
                        return null;
                    }

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
                        requestExecutor.shutdownNow();
                    }

                    private record RawResponse(int status, String contentType, byte[] body) {}

                    private record ProviderAttempt(
                            OperationOutcome outcome,
                            Integer httpStatus,
                            Integer responseBytes) {}

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
                """.formatted(packageName);
    }

    private String application(String packageName, String domainClass) {
        return """
                package %s.application;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication(scanBasePackages = "%s")
                public class %sMcpApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(%sMcpApplication.class, args);
                    }
                }
                """.formatted(packageName, packageName, domainClass, domainClass);
    }

    private String contextTest(String packageName, String domainClass, String operationId) {
        return """
                package %s.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertThrows;

                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.util.List;
                import java.util.Map;
                import %s.runtime.OpenApiOperationExecutor;
                import %s.runtime.OperationDefinition;
                import %s.runtime.ProviderErrorException;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;

                @SpringBootTest(
                        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                        properties = {
                            "provider.response-max-bytes=1024",
                            "provider.connect-timeout-millis=1000",
                            "provider.read-timeout-millis=1000",
                            "provider.total-timeout-millis=100"
                        })
                class %sMcpApplicationTest {
                    private static HttpServer server;

                    @Autowired
                    private OpenApiOperationExecutor executor;

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        try {
                            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            server.createContext("/slow", exchange -> {
                                try {
                                    Thread.sleep(500);
                                    exchange.sendResponseHeaders(200, 2);
                                    exchange.getResponseBody().write("{}".getBytes());
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    exchange.close();
                                }
                            });
                            server.start();
                        } catch (java.io.IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                        registry.add("provider.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
                    }

                    @Test
                    void contextLoads() {}

                    @Test
                    void slowUpstreamBodyTimesOutAndCancelsTheRequest() {
                        var exception = assertThrows(ProviderErrorException.class, () -> executor.execute(
                                new OperationDefinition(
                                        %s, "GET", "/slow", List.of(), List.of(), false, false, null), Map.of()));

                        assertEquals("UPSTREAM_TIMEOUT",
                                exception.error().payload().at("/error/category").textValue());
                    }

                    @AfterAll
                    static void stopProvider() {
                        if (server != null) {
                            server.stop(0);
                        }
                    }
                }
                """.formatted(
                        packageName, packageName, packageName, packageName, domainClass,
                        JavaStringLiteral.quote(operationId));
    }
}
