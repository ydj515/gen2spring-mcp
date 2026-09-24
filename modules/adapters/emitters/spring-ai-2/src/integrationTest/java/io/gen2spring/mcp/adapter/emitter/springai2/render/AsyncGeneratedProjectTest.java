package io.gen2spring.mcp.adapter.emitter.springai2.render;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.adapter.emitter.springai2.SpringAi2ProjectGenerator;
import io.gen2spring.mcp.adapter.emitter.springai2.fixture.RendererFixtures;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class AsyncGeneratedProjectTest {
    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void buildsAndRunsTheGradleWebFluxAsyncProject() throws Exception {
        assertAsyncProject(tempDir.resolve("gradle-async"), asyncProfile("GRADLE_KOTLIN"));
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void buildsAndRunsTheMavenWebFluxAsyncProject() throws Exception {
        assertAsyncProject(tempDir.resolve("maven-async"), asyncProfile("MAVEN"));
    }

    private void assertAsyncProject(Path project, CompatibilityProfile profile) throws Exception {
        var context = RendererFixtures.contextWithWeatherTool(profile);
        Map<String, byte[]> files = new LinkedHashMap<>(
                new SpringAi2ProjectGenerator().generate(context).files());
        files.put(
                "src/test/java/com/example/weather/application/GeneratedAsyncMcpContractTest.java",
                generatedContractTest().getBytes(UTF_8));
        writeProject(project, files);

        List<String> command = new ArrayList<>();
        if ("MAVEN".equals(profile.target().buildTool())) {
            command.add(project.resolve(isWindows() ? "mvnw.cmd" : "mvnw").toString());
            command.addAll(List.of("--batch-mode", "--no-transfer-progress", "test"));
        } else {
            command.add(project.resolve(isWindows() ? "gradlew.bat" : "gradlew").toString());
            command.addAll(List.of("test", "--no-daemon", "--non-interactive"));
        }
        Process process = new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        ManagedTestProcess.Result result = ManagedTestProcess.run(
                process, Duration.ofMinutes(4), Duration.ofSeconds(10));
        assertEquals(0, result.exitCode(), buildDiagnostics(project, result.output(), profile));
        assertTrue(
                result.output().contains("BUILD SUCCESSFUL") || result.output().contains("BUILD SUCCESS"),
                result.output());
    }

    private void writeProject(Path project, Map<String, byte[]> files) throws Exception {
        for (var entry : files.entrySet()) {
            Path target = project.resolve(entry.getKey()).normalize();
            assertTrue(target.startsWith(project), entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        if (!isWindows()) {
            Path wrapper = Files.exists(project.resolve("mvnw"))
                    ? project.resolve("mvnw") : project.resolve("gradlew");
            assertTrue(wrapper.toFile().setExecutable(true));
        }
    }

    private String buildDiagnostics(
            Path project,
            String output,
            CompatibilityProfile profile) throws Exception {
        Path results = "MAVEN".equals(profile.target().buildTool())
                ? project.resolve("target/surefire-reports")
                : project.resolve("build/test-results/test");
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

    private CompatibilityProfile asyncProfile(String buildTool) {
        return AsyncProgrammingModelSourceRendererTest.asyncProfile(buildTool);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    private String generatedContractTest() {
        return """
                package com.example.weather.application;

                import static java.nio.charset.StandardCharsets.UTF_8;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertInstanceOf;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import com.sun.net.httpserver.HttpExchange;
                import com.sun.net.httpserver.HttpServer;
                import io.modelcontextprotocol.server.McpServerFeatures;
                import io.modelcontextprotocol.spec.McpSchema;
                import java.io.IOException;
                import java.net.InetSocketAddress;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.atomic.AtomicInteger;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.BeforeAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.boot.test.web.server.LocalServerPort;
                import org.springframework.context.ApplicationContext;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;
                import reactor.test.StepVerifier;

                @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
                class GeneratedAsyncMcpContractTest {
                    private static final AtomicInteger REQUESTS = new AtomicInteger();
                    private static HttpServer upstream;

                    @LocalServerPort int serverPort;
                    @Autowired ApplicationContext applicationContext;

                    @BeforeAll
                    static void startUpstream() throws Exception {
                        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                        upstream.createContext("/forecast", exchange -> {
                            REQUESTS.incrementAndGet();
                            assertEquals("60", query(exchange, "nx"));
                            assertEquals("127", query(exchange, "ny"));
                            assertEquals("test-key", query(exchange, "serviceKey"));
                            String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
                            assertNotNull(traceparent);
                            assertTrue(traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-(00|01)"));
                            respond(exchange, 200, "{\\\"forecast\\\":\\\"sunny\\\"}");
                        });
                        upstream.start();
                    }

                    @AfterAll
                    static void stopUpstream() {
                        upstream.stop(0);
                    }

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        registry.add("provider.base-url", () ->
                                "http://127.0.0.1:" + upstream.getAddress().getPort());
                        registry.add("provider.secrets.service-key", () -> "test-key");
                    }

                    @Test
                    @SuppressWarnings("unchecked")
                    void startsNettyListsTheExactSchemaAndCallsTheUpstream() {
                        assertTrue(serverPort > 0);
                        assertTrue(applicationContext.getClass().getName().contains("Reactive"),
                                applicationContext.getClass().getName());
                        List<McpServerFeatures.AsyncToolSpecification> specifications =
                                (List<McpServerFeatures.AsyncToolSpecification>) applicationContext
                                        .getBean("generatedToolSpecifications", List.class);
                        assertEquals(1, specifications.size());
                        McpServerFeatures.AsyncToolSpecification specification = specifications.getFirst();
                        assertEquals("kma_weather_get_forecast", specification.tool().name());
                        assertEquals(List.of("nx", "ny"), specification.tool().inputSchema().get("required"));

                        McpSchema.CallToolRequest request = McpSchema.CallToolRequest
                                .builder("kma_weather_get_forecast")
                                .arguments(Map.of("nx", 60, "ny", 127))
                                .build();
                        StepVerifier.create(specification.callHandler().apply(null, request))
                                .assertNext(result -> {
                                    assertFalse(Boolean.TRUE.equals(result.isError()));
                                    McpSchema.TextContent content = assertInstanceOf(
                                            McpSchema.TextContent.class, result.content().getFirst());
                                    assertTrue(content.text().contains("sunny"), content.text());
                                })
                                .verifyComplete();
                        assertEquals(1, REQUESTS.get());
                    }

                    private static String query(HttpExchange exchange, String name) {
                        String query = exchange.getRequestURI().getRawQuery();
                        for (String pair : query.split("&")) {
                            String[] parts = pair.split("=", 2);
                            if (parts[0].equals(name)) {
                                return parts.length == 2 ? parts[1] : "";
                            }
                        }
                        return null;
                    }

                    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
                        byte[] bytes = body.getBytes(UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    }
                }
                """;
    }
}
