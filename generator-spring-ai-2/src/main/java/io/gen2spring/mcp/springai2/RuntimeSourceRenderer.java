package io.gen2spring.mcp.springai2;

import java.util.LinkedHashMap;
import java.util.Map;

final class RuntimeSourceRenderer {
    Map<String, String> render(String packageName, String packagePath, String domainClass) {
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
                contextTest(packageName, domainClass));
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
                import java.net.URI;
                import java.net.http.HttpClient;
                import java.time.Duration;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.ArrayBlockingQueue;
                import java.util.concurrent.ExecutionException;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Future;
                import java.util.concurrent.RejectedExecutionException;
                import java.util.concurrent.TimeUnit;
                import java.util.concurrent.TimeoutException;
                import java.util.concurrent.ThreadPoolExecutor;
                import jakarta.annotation.PreDestroy;
                import org.springframework.core.env.Environment;
                import org.springframework.http.HttpHeaders;
                import org.springframework.http.HttpMethod;
                import org.springframework.http.MediaType;
                import org.springframework.http.client.JdkClientHttpRequestFactory;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.util.UriComponentsBuilder;
                import tools.jackson.core.JacksonException;
                import tools.jackson.databind.JsonNode;
                import tools.jackson.databind.node.NullNode;
                import tools.jackson.databind.node.ObjectNode;
                import tools.jackson.databind.json.JsonMapper;

                @Component
                public final class OpenApiOperationExecutor {
                    private static final String REQUEST_FAILED = "UPSTREAM_REQUEST_FAILED";
                    private static final String RESPONSE_TOO_LARGE = "UPSTREAM_RESPONSE_TOO_LARGE";
                    private static final String REQUEST_BODY_SERIALIZATION_FAILED =
                            "UPSTREAM_REQUEST_BODY_SERIALIZATION_FAILED";

                    private final RestClient restClient;
                    private final Environment environment;
                    private final JsonMapper jsonMapper;
                    private final URI baseUrl;
                    private final int responseMaxBytes;
                    private final long totalTimeoutMillis;
                    private final ExecutorService requestExecutor;

                    public OpenApiOperationExecutor(RestClient.Builder builder, Environment environment) {
                        this.environment = environment;
                        this.jsonMapper = JsonMapper.builder()
                                .changeDefaultPropertyInclusion(inclusion ->
                                        inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
                                .build();
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
                        this.restClient = builder.requestFactory(requestFactory).build();
                        this.requestExecutor = new ThreadPoolExecutor(
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
                    }

                    public JsonNode execute(OperationDefinition operation, Map<String, Object> arguments) {
                        Future<JsonNode> request;
                        try {
                            request = requestExecutor.submit(
                                    () -> executeSafely(operation, arguments == null ? Map.of() : arguments));
                        } catch (RejectedExecutionException exception) {
                            throw new SafeExecutionException("UPSTREAM_REQUEST_SATURATED");
                        }
                        try {
                            return request.get(totalTimeoutMillis, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException exception) {
                            request.cancel(true);
                            throw new SafeExecutionException("UPSTREAM_REQUEST_TIMEOUT");
                        } catch (InterruptedException exception) {
                            request.cancel(true);
                            Thread.currentThread().interrupt();
                            throw new SafeExecutionException(REQUEST_FAILED);
                        } catch (ExecutionException exception) {
                            if (exception.getCause() instanceof SafeExecutionException safeException) {
                                throw safeException;
                            }
                            throw new SafeExecutionException(REQUEST_FAILED);
                        } catch (SafeExecutionException exception) {
                            throw exception;
                        } catch (RuntimeException exception) {
                            throw new SafeExecutionException(REQUEST_FAILED);
                        }
                    }

                    private JsonNode executeSafely(OperationDefinition operation, Map<String, Object> arguments) {
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
                            String value = environment.getProperty(binding.propertyName());
                            if (value == null || value.isBlank()) {
                                if (binding.required()) {
                                    throw new SafeExecutionException("REQUIRED_PROVIDER_SECRET_MISSING");
                                }
                                continue;
                            }
                            requestBody = bind(uriBuilder, pathVariables, headers, binding.targetLocation(),
                                    binding.targetName(), value, requestBody, operation.objectRequestBody());
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
                            } catch (JacksonException exception) {
                                throw new SafeExecutionException(REQUEST_BODY_SERIALIZATION_FAILED);
                            }
                        }

                        RawResponse response = request.exchange((ignoredRequest, upstreamResponse) ->
                                new RawResponse(
                                        upstreamResponse.getStatusCode().value(),
                                        upstreamResponse.getHeaders().getContentType(),
                                        readBounded(upstreamResponse.getBody())));
                        if (response == null) {
                            throw new SafeExecutionException(REQUEST_FAILED);
                        }
                        if (response.status() < 200 || response.status() >= 300) {
                            return httpError(response.status());
                        }
                        if (response.body().length == 0) {
                            return NullNode.getInstance();
                        }
                        if (!isJsonResponse(response.contentType())) {
                            throw new SafeExecutionException("UPSTREAM_RESPONSE_MEDIA_TYPE_UNSUPPORTED");
                        }
                        try {
                            return jsonMapper.readTree(response.body());
                        } catch (JacksonException exception) {
                            throw new SafeExecutionException("UPSTREAM_RESPONSE_INVALID_JSON");
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

                    private byte[] readBounded(InputStream input) throws IOException {
                        byte[] bytes = input.readNBytes(responseMaxBytes + 1);
                        if (bytes.length > responseMaxBytes) {
                            throw new SafeExecutionException(RESPONSE_TOO_LARGE);
                        }
                        return bytes;
                    }

                    private JsonNode httpError(int status) {
                        ObjectNode error = jsonMapper.createObjectNode();
                        error.put("error", "UPSTREAM_HTTP_ERROR");
                        error.put("status", status);
                        return error;
                    }

                    private boolean allowsBody(String method) {
                        return "POST".equals(method) || "PUT".equals(method)
                                || "PATCH".equals(method) || "DELETE".equals(method);
                    }

                    private boolean isJsonResponse(MediaType contentType) {
                        return contentType != null
                                && "application".equalsIgnoreCase(contentType.getType())
                                && "json".equalsIgnoreCase(contentType.getSubtype());
                    }

                    private URI requireHttpUri(String value) {
                        URI uri;
                        try {
                            uri = URI.create(value);
                        } catch (IllegalArgumentException exception) {
                            throw new SafeExecutionException("PROVIDER_BASE_URL_INVALID");
                        }
                        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                                || uri.getHost() == null || uri.getUserInfo() != null) {
                            throw new SafeExecutionException("PROVIDER_BASE_URL_INVALID");
                        }
                        return uri;
                    }

                    private int requireResponseLimit(Integer value) {
                        if (value == null || value <= 0 || value == Integer.MAX_VALUE) {
                            throw new SafeExecutionException("PROVIDER_RESPONSE_LIMIT_INVALID");
                        }
                        return value;
                    }

                    private long requireTimeout(Long value) {
                        if (value == null || value <= 0 || value > 300_000) {
                            throw new SafeExecutionException("PROVIDER_TIMEOUT_INVALID");
                        }
                        return value;
                    }

                    private int requireRequestCapacity(Integer value) {
                        if (value == null || value <= 0 || value > 256) {
                            throw new SafeExecutionException("PROVIDER_REQUEST_CAPACITY_INVALID");
                        }
                        return value;
                    }

                    @PreDestroy
                    void shutdown() {
                        requestExecutor.shutdownNow();
                    }

                    private record RawResponse(int status, MediaType contentType, byte[] body) {}

                    private static final class SafeExecutionException extends IllegalStateException {
                        private SafeExecutionException(String message) {
                            super(message);
                        }
                    }
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

    private String contextTest(String packageName, String domainClass) {
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
                        var exception = assertThrows(IllegalStateException.class, () -> executor.execute(
                                new OperationDefinition("GET", "/slow", List.of(), List.of()), Map.of()));

                        assertEquals("UPSTREAM_REQUEST_TIMEOUT", exception.getMessage());
                    }

                    @AfterAll
                    static void stopProvider() {
                        if (server != null) {
                            server.stop(0);
                        }
                    }
                }
                """.formatted(packageName, packageName, packageName, domainClass);
    }
}
