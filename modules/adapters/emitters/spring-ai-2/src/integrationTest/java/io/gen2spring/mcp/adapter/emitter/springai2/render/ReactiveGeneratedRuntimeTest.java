package io.gen2spring.mcp.adapter.emitter.springai2.render;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.adapter.emitter.springai2.SpringAi2ProjectGenerator;
import io.gen2spring.mcp.adapter.emitter.springai2.fixture.RendererFixtures;
import io.gen2spring.mcp.application.generation.model.GenerationContext;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ReactiveGeneratedRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedReactiveRuntimeCompilesAndPassesItsProviderContract() throws Exception {
        var profile = CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java21-mvc-streamable")
                .orElseThrow();
        var context = RendererFixtures.contextWithWeatherTool(profile);
        Map<String, byte[]> files = reactiveProjectFiles(context, profile, false, false);
        files.put(
                "src/test/java/com/example/weather/runtime/GeneratedReactiveProviderContractTest.java",
                generatedContractTest().getBytes(UTF_8));

        assertProjectBuilds(tempDir.resolve("reactive-runtime"), files);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedReactivePoliciesRetryPaginateAndCancelSequentially() throws Exception {
        var profile = CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java21-mvc-streamable")
                .orElseThrow();
        ToolDefinition base = RendererFixtures.weatherTool();
        HttpExecution execution = base.execution();
        ToolDefinition policies = new ToolDefinition(
                base.operationId(),
                base.name(),
                base.description(),
                base.inputs(),
                new HttpExecution(
                        execution.method(),
                        execution.baseUrl(),
                        execution.path(),
                        execution.bindings(),
                        execution.objectRequestBody(),
                        execution.requestBodyRequired(),
                        execution.responseNormalization(),
                        new RetryPolicy(List.of(503), true, 2, 10, 1_000, true),
                        new PaginationPolicy("cursor", "first", "/items", "/next", 4, 10)),
                base.secretBindings(),
                base.output());
        GenerationContext context = RendererFixtures.context(profile, List.of(policies));
        Map<String, byte[]> files = reactiveProjectFiles(context, profile, true, true);
        files.put(
                "src/test/java/com/example/weather/runtime/GeneratedReactivePolicyContractTest.java",
                generatedPolicyContractTest().getBytes(UTF_8));

        assertProjectBuilds(tempDir.resolve("reactive-policies"), files);
    }

    private Map<String, byte[]> reactiveProjectFiles(
            GenerationContext context,
            CompatibilityProfile profile,
            boolean hasRetryPolicies,
            boolean hasPaginationPolicies) {
        Map<String, byte[]> files = new LinkedHashMap<>(
                new SpringAi2ProjectGenerator().generate(context).files());
        files.keySet().removeIf(path -> path.contains("/generated/tool/")
                || path.contains("/generated/model/")
                || path.endsWith("/runtime/ToolArgumentContext.java")
                || path.endsWith("/application/WeatherMcpApplicationTest.java"));

        var request = new ProgrammingModelRenderRequest(
                context,
                "com.example.weather",
                "com/example/weather",
                "Weather",
                context.tools(),
                Map.of("kma_weather_get_forecast", "{}"),
                false,
                hasRetryPolicies,
                hasPaginationPolicies);
        new ReactiveRuntimeSourceRenderer().render(request).forEach(
                (path, source) -> files.put(path, source.getBytes(UTF_8)));
        files.put(
                "src/main/java/com/example/weather/runtime/RuntimeTelemetry.java",
                new RuntimeTelemetryRenderer(profile)
                        .renderReactive("com.example.weather", context.tools())
                        .getBytes(UTF_8));
        String build = new String(files.get("build.gradle.kts"), UTF_8)
                .replace("spring-ai-starter-mcp-server-webmvc", "spring-ai-starter-mcp-server-webflux")
                .replace("spring-boot-restclient", "spring-boot-starter-webflux")
                .replace(
                        "testImplementation(\"org.springframework.boot:spring-boot-starter-test\")",
                        "testImplementation(\"org.springframework.boot:spring-boot-starter-test\")\n"
                                + "    testImplementation(\"io.projectreactor:reactor-test\")");
        files.put("build.gradle.kts", build.getBytes(UTF_8));
        return files;
    }

    private void assertProjectBuilds(Path project, Map<String, byte[]> files) throws Exception {
        for (var entry : files.entrySet()) {
            Path target = project.resolve(entry.getKey()).normalize();
            assertTrue(target.startsWith(project), entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        if (!isWindows()) {
            assertTrue(project.resolve("gradlew").toFile().setExecutable(true));
        }
        Path wrapper = project.resolve(isWindows() ? "gradlew.bat" : "gradlew");
        Process process = new ProcessBuilder(
                wrapper.toString(), "test", "--no-daemon", "--non-interactive")
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        ManagedTestProcess.Result result = ManagedTestProcess.run(
                process, Duration.ofMinutes(4), Duration.ofSeconds(10));
        assertEquals(0, result.exitCode(), buildDiagnostics(project, result.output()));
        assertTrue(result.output().contains("BUILD SUCCESSFUL"), result.output());
    }

    private String buildDiagnostics(Path project, String output) throws Exception {
        Path results = project.resolve("build/test-results/test");
        if (!Files.isDirectory(results)) {
            return output;
        }
        StringBuilder diagnostics = new StringBuilder(output);
        try (var resultFiles = Files.list(results)) {
            for (Path file : resultFiles.filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted().toList()) {
                diagnostics.append('\n').append(Files.readString(file));
            }
        }
        return diagnostics.toString();
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    private String generatedPolicyContractTest() {
        return """
                package com.example.weather.runtime;

                import static java.nio.charset.StandardCharsets.UTF_8;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertInstanceOf;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import com.sun.net.httpserver.HttpExchange;
                import com.sun.net.httpserver.HttpServer;
                import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
                import io.micrometer.observation.ObservationRegistry;
                import io.micrometer.tracing.Tracer;
                import java.io.IOException;
                import java.net.InetSocketAddress;
                import java.time.Duration;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                import java.util.concurrent.atomic.AtomicInteger;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.AfterEach;
                import org.junit.jupiter.api.BeforeAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.core.env.MapPropertySource;
                import org.springframework.core.env.StandardEnvironment;
                import org.springframework.http.MediaType;
                import org.springframework.web.reactive.function.client.WebClient;
                import reactor.core.publisher.Mono;
                import reactor.test.StepVerifier;

                class GeneratedReactivePolicyContractTest {
                    private static final AtomicInteger RETRY_REQUESTS = new AtomicInteger();
                    private static final AtomicInteger POST_REQUESTS = new AtomicInteger();
                    private static final AtomicInteger PAGE_REQUESTS = new AtomicInteger();
                    private static HttpServer server;
                    private static ExecutorService serverExecutor;

                    private OpenApiOperationExecutor executor;
                    private SimpleMeterRegistry meterRegistry;

                    @BeforeAll
                    static void startServer() throws Exception {
                        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                        server.createContext("/retry", exchange -> {
                            int attempt = RETRY_REQUESTS.incrementAndGet();
                            if (attempt < 3) {
                                exchange.getResponseHeaders().set("Retry-After", "1");
                                respond(exchange, 503, "{\\\"retry\\\":true}");
                            } else {
                                respond(exchange, 200, "{\\\"ok\\\":true}");
                            }
                        });
                        server.createContext("/post", exchange -> {
                            POST_REQUESTS.incrementAndGet();
                            respond(exchange, 503, "{\\\"retry\\\":true}");
                        });
                        server.createContext("/pages", exchange -> {
                            PAGE_REQUESTS.incrementAndGet();
                            String query = exchange.getRequestURI().getRawQuery();
                            if (query != null && query.contains("cursor=first")) {
                                respond(exchange, 200,
                                        "{\\\"items\\\":[{\\\"id\\\":1}],\\\"next\\\":\\\"second\\\"}");
                            } else {
                                respond(exchange, 200,
                                        "{\\\"items\\\":[{\\\"id\\\":2}],\\\"next\\\":null}");
                            }
                        });
                        server.createContext("/repeat", exchange -> respond(exchange, 200,
                                "{\\\"items\\\":[{\\\"id\\\":1}],\\\"next\\\":\\\"first\\\"}"));
                        server.createContext("/slow", exchange -> {
                            try {
                                Thread.sleep(2_000);
                                respond(exchange, 200, "{\\\"ok\\\":true}");
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                            }
                        });
                        serverExecutor = Executors.newCachedThreadPool();
                        server.setExecutor(serverExecutor);
                        server.start();
                    }

                    @AfterAll
                    static void stopServer() {
                        server.stop(0);
                        serverExecutor.shutdownNow();
                    }

                    @AfterEach
                    void disposeExecutor() {
                        if (executor != null) {
                            executor.shutdown();
                        }
                    }

                    @Test
                    void retriesIdempotentRequestsSequentiallyAndHonorsRetryAfterCap() {
                        RETRY_REQUESTS.set(0);
                        executor = executor();
                        RetryPolicy retry = new RetryPolicy(List.of(503), false, 2, 1, 50, true);
                        long started = System.nanoTime();

                        StepVerifier.create(executor.execute(operation("GET", "/retry", retry, null), Map.of()))
                                .assertNext(result -> assertTrue(result.get("ok").booleanValue()))
                                .verifyComplete();

                        assertEquals(3, RETRY_REQUESTS.get());
                        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 80);
                    }

                    @Test
                    void doesNotRetryNonIdempotentRequests() {
                        POST_REQUESTS.set(0);
                        executor = executor();
                        RetryPolicy retry = new RetryPolicy(List.of(503), true, 3, 1, 10, false);

                        assertProviderError(
                                executor.execute(operation("POST", "/post", retry, null), Map.of()),
                                ProviderErrorCategory.UPSTREAM_SERVER);
                        assertEquals(1, POST_REQUESTS.get());
                    }

                    @Test
                    void aggregatesPagesAndRejectsRepeatedTokensAndLimits() {
                        PAGE_REQUESTS.set(0);
                        executor = executor();
                        PaginationPolicy pagination = new PaginationPolicy(
                                "cursor", "first", "/items", "/next", 3, 10);

                        StepVerifier.create(executor.execute(
                                        operation("GET", "/pages", null, pagination), Map.of()))
                                .assertNext(result -> {
                                    assertEquals(2, result.get("items").size());
                                    assertTrue(result.get("next").isNull());
                                })
                                .verifyComplete();
                        assertEquals(2, PAGE_REQUESTS.get());

                        PaginationPolicy repeated = new PaginationPolicy(
                                "cursor", "first", "/items", "/next", 3, 10);
                        assertProviderError(
                                executor.execute(operation("GET", "/repeat", null, repeated), Map.of()),
                                ProviderErrorCategory.UPSTREAM_PROTOCOL);

                        PaginationPolicy limited = new PaginationPolicy(
                                "cursor", "first", "/items", "/next", 1, 1);
                        assertProviderError(
                                executor.execute(operation("GET", "/pages", null, limited), Map.of()),
                                ProviderErrorCategory.LOCAL_RESOURCE);
                    }

                    @Test
                    void propagatesCancellationDuringResponseAndRetryDelay() throws Exception {
                        executor = executor();
                        StepVerifier.create(executor.execute(operation("GET", "/slow", null, null), Map.of()))
                                .thenAwait(Duration.ofMillis(50))
                                .thenCancel()
                                .verify(Duration.ofSeconds(2));
                        var cancelled = meterRegistry.find("gen2spring.runtime.provider.request")
                                .tag("outcome", "cancelled")
                                .timer();
                        assertNotNull(cancelled);
                        assertEquals(1.0, cancelled.count());

                        RETRY_REQUESTS.set(0);
                        RetryPolicy delayed = new RetryPolicy(List.of(503), false, 3, 1_000, 1_000, false);
                        StepVerifier.create(executor.execute(operation("GET", "/retry", delayed, null), Map.of()))
                                .thenAwait(Duration.ofMillis(100))
                                .thenCancel()
                                .verify(Duration.ofSeconds(2));
                        Thread.sleep(100);
                        assertEquals(1, RETRY_REQUESTS.get());
                    }

                    private OpenApiOperationExecutor executor() {
                        Map<String, Object> properties = new LinkedHashMap<>();
                        properties.put("provider.base-url",
                                "http://127.0.0.1:" + server.getAddress().getPort());
                        properties.put("provider.response-max-bytes", 4096);
                        properties.put("provider.connect-timeout-millis", 1000L);
                        properties.put("provider.read-timeout-millis", 3000L);
                        properties.put("provider.total-timeout-millis", 3000L);
                        properties.put("provider.max-concurrent-requests", 2);
                        properties.put("provider.max-queued-requests", 2);
                        StandardEnvironment environment = new StandardEnvironment();
                        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
                        meterRegistry = new SimpleMeterRegistry();
                        RuntimeTelemetry telemetry = new RuntimeTelemetry(
                                ObservationRegistry.NOOP, meterRegistry, Tracer.NOOP);
                        return new OpenApiOperationExecutor(WebClient.builder(), environment, telemetry);
                    }

                    private OperationDefinition operation(
                            String method,
                            String path,
                            RetryPolicy retry,
                            PaginationPolicy pagination) {
                        return new OperationDefinition(
                                "getForecast",
                                method,
                                path,
                                List.of(),
                                List.of(),
                                false,
                                false,
                                null,
                                retry,
                                pagination);
                    }

                    private void assertProviderError(Mono<?> execution, ProviderErrorCategory expected) {
                        StepVerifier.create(execution)
                                .expectErrorSatisfies(failure -> {
                                    ProviderErrorException provider =
                                            assertInstanceOf(ProviderErrorException.class, failure);
                                    assertEquals(expected, provider.error().category());
                                })
                                .verify();
                    }

                    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
                        byte[] bytes = body.getBytes(UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    }
                }
                """;
    }

    private String generatedContractTest() {
        return """
                package com.example.weather.runtime;

                import static java.nio.charset.StandardCharsets.UTF_8;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertInstanceOf;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import com.sun.net.httpserver.HttpExchange;
                import com.sun.net.httpserver.HttpServer;
                import java.io.IOException;
                import java.net.InetSocketAddress;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.AfterEach;
                import org.junit.jupiter.api.BeforeAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.context.annotation.AnnotationConfigApplicationContext;
                import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
                import io.micrometer.observation.ObservationRegistry;
                import io.micrometer.tracing.Tracer;
                import org.springframework.web.reactive.function.client.ClientRequest;
                import org.springframework.core.env.MapPropertySource;
                import org.springframework.core.env.StandardEnvironment;
                import org.springframework.http.MediaType;
                import org.springframework.web.reactive.function.client.WebClient;
                import reactor.test.StepVerifier;

                class GeneratedReactiveProviderContractTest {
                    private static HttpServer server;
                    private OpenApiOperationExecutor executor;

                    @BeforeAll
                    static void startServer() throws Exception {
                        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                        server.createContext("/get", exchange -> respond(exchange, 200,
                                "{\\\"method\\\":\\\"" + exchange.getRequestMethod()
                                        + "\\\",\\\"query\\\":\\\"" + exchange.getRequestURI().getRawQuery()
                                        + "\\\",\\\"header\\\":\\\"" + exchange.getRequestHeaders().getFirst("X-Mode")
                                        + "\\\"}"));
                        server.createContext("/body", exchange -> respond(exchange, 200,
                                new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
                        server.createContext("/client", exchange -> respond(exchange, 400, "{\\\"error\\\":true}"));
                        server.createContext("/server", exchange -> respond(exchange, 503, "{\\\"error\\\":true}"));
                        server.createContext("/malformed", exchange -> respond(exchange, 200, "{not-json"));
                        server.createContext("/large", exchange -> respond(exchange, 200,
                                "\\\"" + "x".repeat(256) + "\\\""));
                        server.createContext("/slow", exchange -> {
                            try {
                                Thread.sleep(250);
                                respond(exchange, 200, "{\\\"ok\\\":true}");
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                            }
                        });
                        server.createContext("/secret", exchange -> respond(exchange, 500,
                                "{\\\"message\\\":\\\"private-token\\\"}"));
                        server.start();
                    }

                    @AfterAll
                    static void stopServer() {
                        server.stop(0);
                    }

                    @AfterEach
                    void disposeExecutor() {
                        if (executor != null) {
                            executor.shutdown();
                        }
                    }

                    @Test
                    void springInjectionPreservesTheManagedBuilderFilters() {
                        WebClient.Builder builder = WebClient.builder().filter((request, next) ->
                                next.exchange(ClientRequest.from(request)
                                        .header("X-Mode", "managed-builder").build()));
                        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                            context.setEnvironment(environment(Map.of()));
                            context.registerBean(WebClient.Builder.class, () -> builder);
                            context.registerBean(RuntimeTelemetry.class, () -> new RuntimeTelemetry(
                                    ObservationRegistry.NOOP, new SimpleMeterRegistry(), Tracer.NOOP));
                            context.register(OpenApiOperationExecutor.class);
                            context.refresh();
                            OpenApiOperationExecutor managed = context.getBean(OpenApiOperationExecutor.class);
                            StepVerifier.create(managed.execute(operation("GET", "/get"), Map.of()))
                                    .assertNext(result -> assertEquals("managed-builder", result.get("header").stringValue()))
                                    .verifyComplete();
                        }
                    }

                    @Test
                    void mapsGetQueryHeaderAndBodyWithoutBlocking() {
                        executor = executor(Map.of());
                        OperationDefinition get = operation("GET", "/get", List.of(
                                new ParameterBinding("query", ParameterLocation.QUERY, "q"),
                                new ParameterBinding("mode", ParameterLocation.HEADER, "X-Mode")),
                                false, false, null, List.of());
                        StepVerifier.create(executor.execute(get, Map.of("query", "seoul", "mode", "full")))
                                .assertNext(result -> {
                                    assertEquals("GET", result.get("method").stringValue());
                                    assertEquals("q=seoul", result.get("query").stringValue());
                                    assertEquals("full", result.get("header").stringValue());
                                })
                                .verifyComplete();

                        OperationDefinition post = operation("POST", "/body", List.of(
                                new ParameterBinding("payload", ParameterLocation.BODY, "payload")),
                                false, true, null, List.of());
                        StepVerifier.create(executor.execute(post, Map.of("payload", Map.of("city", "Seoul"))))
                                .assertNext(result -> assertEquals("Seoul", result.get("city").stringValue()))
                                .verifyComplete();
                    }

                    @Test
                    void mapsProviderStatusesAndMalformedJson() {
                        executor = executor(Map.of());
                        assertProviderError(executor.execute(operation("GET", "/client"), Map.of()),
                                ProviderErrorCategory.UPSTREAM_CLIENT);
                        assertProviderError(executor.execute(operation("GET", "/server"), Map.of()),
                                ProviderErrorCategory.UPSTREAM_SERVER);
                        assertProviderError(executor.execute(operation("GET", "/malformed"), Map.of()),
                                ProviderErrorCategory.UPSTREAM_PROTOCOL);
                    }

                    @Test
                    void boundsResponseMemoryAndReadAndTotalTimeouts() {
                        executor = executor(Map.of("provider.response-max-bytes", 32));
                        assertProviderError(executor.execute(operation("GET", "/large"), Map.of()),
                                ProviderErrorCategory.UPSTREAM_PROTOCOL);
                        executor.shutdown();

                        executor = executor(Map.of(
                                "provider.read-timeout-millis", 25L,
                                "provider.total-timeout-millis", 500L));
                        assertProviderError(executor.execute(operation("GET", "/slow"), Map.of()),
                                ProviderErrorCategory.UPSTREAM_TIMEOUT);
                        executor.shutdown();

                        executor = executor(Map.of(
                                "provider.read-timeout-millis", 500L,
                                "provider.total-timeout-millis", 25L));
                        assertProviderError(executor.execute(operation("GET", "/slow"), Map.of()),
                                ProviderErrorCategory.UPSTREAM_TIMEOUT);
                    }

                    @Test
                    void redactsConfiguredSecretsFromProviderErrors() {
                        executor = executor(Map.of("provider.secrets.token", "private-token"));
                        ResponseNormalizationPolicy policy = new ResponseNormalizationPolicy(
                                null, null, List.of(), "/message", null);
                        OperationDefinition secret = operation("GET", "/secret", List.of(), false, false,
                                policy,
                                List.of(new SecretBinding(
                                        "provider.secrets.token", ParameterLocation.HEADER, "X-Token", true)));

                        StepVerifier.create(executor.execute(secret, Map.of()))
                                .expectErrorSatisfies(failure -> {
                                    ProviderErrorException provider =
                                            assertInstanceOf(ProviderErrorException.class, failure);
                                    String payload = provider.error().payload().toString();
                                    assertFalse(payload.contains("private-token"), payload);
                                    assertTrue(payload.contains("***"), payload);
                                })
                                .verify();
                    }

                    private OpenApiOperationExecutor executor(Map<String, Object> overrides) {
                        return new OpenApiOperationExecutor(WebClient.builder(), environment(overrides));
                    }

                    private StandardEnvironment environment(Map<String, Object> overrides) {
                        Map<String, Object> properties = new LinkedHashMap<>();
                        properties.put("provider.base-url",
                                "http://127.0.0.1:" + server.getAddress().getPort());
                        properties.put("provider.response-max-bytes", 1024);
                        properties.put("provider.connect-timeout-millis", 1000L);
                        properties.put("provider.read-timeout-millis", 1000L);
                        properties.put("provider.total-timeout-millis", 1000L);
                        properties.put("provider.max-concurrent-requests", 2);
                        properties.put("provider.max-queued-requests", 2);
                        properties.putAll(overrides);
                        StandardEnvironment environment = new StandardEnvironment();
                        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
                        return environment;
                    }

                    private OperationDefinition operation(String method, String path) {
                        return operation(method, path, List.of(), false, false, null, List.of());
                    }

                    private OperationDefinition operation(
                            String method,
                            String path,
                            List<ParameterBinding> bindings,
                            boolean objectBody,
                            boolean bodyRequired,
                            ResponseNormalizationPolicy policy,
                            List<SecretBinding> secrets) {
                        return new OperationDefinition(
                                "getForecast", method, path, bindings, secrets,
                                objectBody, bodyRequired, policy);
                    }

                    private void assertProviderError(
                            reactor.core.publisher.Mono<?> execution,
                            ProviderErrorCategory expected) {
                        StepVerifier.create(execution)
                                .expectErrorSatisfies(failure -> {
                                    ProviderErrorException provider =
                                            assertInstanceOf(ProviderErrorException.class, failure);
                                    assertEquals(expected, provider.error().category());
                                })
                                .verify();
                    }

                    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
                        byte[] bytes = body.getBytes(UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    }
                }
                """;
    }
}
