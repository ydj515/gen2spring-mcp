package io.gen2spring.mcp.adapter.emitter.springai2;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
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
        var context = JavaSourceRendererTest.contextWithWeatherTool(profile);
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
                false,
                false);
        new ReactiveRuntimeSourceRenderer().render(request).forEach(
                (path, source) -> files.put(path, source.getBytes(UTF_8)));
        String build = new String(files.get("build.gradle.kts"), UTF_8)
                .replace("spring-ai-starter-mcp-server-webmvc", "spring-ai-starter-mcp-server-webflux")
                .replace("spring-boot-restclient", "spring-boot-starter-webflux")
                .replace(
                        "testImplementation(\"org.springframework.boot:spring-boot-starter-test\")",
                        "testImplementation(\"org.springframework.boot:spring-boot-starter-test\")\n"
                                + "    testImplementation(\"io.projectreactor:reactor-test\")");
        files.put("build.gradle.kts", build.getBytes(UTF_8));
        files.put(
                "src/test/java/com/example/weather/runtime/GeneratedReactiveProviderContractTest.java",
                generatedContractTest().getBytes(UTF_8));

        assertProjectBuilds(tempDir.resolve("reactive-runtime"), files);
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
                        return new OpenApiOperationExecutor(WebClient.builder(), environment);
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
