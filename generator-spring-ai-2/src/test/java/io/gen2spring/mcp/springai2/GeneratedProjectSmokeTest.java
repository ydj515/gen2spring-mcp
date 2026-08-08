package io.gen2spring.mcp.springai2;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.HttpExecutionDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.McpInputDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterBinding;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.SecretBinding;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class GeneratedProjectSmokeTest {
    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedWeatherProjectResolvesCompilesAndStartsItsContext() throws Exception {
        var files = new SpringAi2ProjectGenerator()
                .generate(JavaSourceRendererTest.contextWithWeatherTool())
                .files();
        assertProjectBuilds(tempDir.resolve("weather"), files);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedProjectValidatesMixedExplicitAndAnnotationDerivedToolArgumentsBeforeUpstreamCalls() throws Exception {
        ApiSchema mode = new ApiSchema(
                SchemaType.STRING, null, false, List.of("brief", "full-detail"), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema integer = new ApiSchema(
                SchemaType.INTEGER, "int32", false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema city = new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null,
                1, 80, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema options = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, Map.of("city", city), List.of("city"), null, true, List.of());
        var enumTool = new McpToolDefinition(
                "getForecast",
                "kma_weather_get_forecast",
                "Get the public weather forecast for a grid location.",
                List.of(
                        new McpInputDefinition("nx", "nx", "Grid x coordinate", true, integer),
                        new McpInputDefinition("accept", "accept", "Requested response type", false,
                                new ApiSchema(
                                        SchemaType.STRING, null, false, List.of(), null, null,
                                        null, null, null, null, Map.of(), List.of(), null, true, List.of())),
                        new McpInputDefinition("mode", "mode", "Response mode", false, mode),
                        new McpInputDefinition("options", "options", "Forecast options", true, options)),
                new HttpExecutionDefinition(
                        HttpMethod.GET,
                        URI.create("https://api.example.test"),
                        "/forecast",
                        List.of(
                                new ParameterBinding("nx", ParameterLocation.QUERY, "nx"),
                                new ParameterBinding("accept", ParameterLocation.HEADER, "Accept"),
                                new ParameterBinding("mode", ParameterLocation.QUERY, "mode"))),
                List.of(),
                McpToolDefinition.OutputKind.GENERIC_JSON);
        var normalTool = new McpToolDefinition(
                "getAlerts",
                "kma_weather_get_alerts",
                "Get weather alerts.",
                List.of(new McpInputDefinition("region", "region", "Region", true, city)),
                new HttpExecutionDefinition(
                        HttpMethod.GET,
                        URI.create("https://api.example.test"),
                        "/alerts",
                        List.of(new ParameterBinding("region", ParameterLocation.QUERY, "region"))),
                List.of(),
                McpToolDefinition.OutputKind.GENERIC_JSON);
        var files = new SpringAi2ProjectGenerator()
                .generate(JavaSourceRendererTest.context(List.of(enumTool, normalTool)))
                .files();
        java.util.Map<String, byte[]> filesWithCallbackTest = new java.util.LinkedHashMap<>(files);
        filesWithCallbackTest.put(
                "src/test/java/com/example/weather/application/GeneratedEnumCallbackContractTest.java",
                enumCallbackContractTest().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertProjectBuilds(tempDir.resolve("enum"), filesWithCallbackTest);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedCallbackAcceptsNestedConstrainedEnumsWithSpacedJsonKeys() throws Exception {
        ApiSchema constrainedEnum = new ApiSchema(
                SchemaType.STRING, null, false, List.of("brief", "full-detail"), null, null,
                2, 16, "[a-z-]+", null, Map.of(), List.of(), null, true, List.of());
        ApiSchema details = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, Map.of("display name", constrainedEnum),
                List.of("display name"), null, true, List.of());
        var tool = new McpToolDefinition(
                "submitDetails", "kma_weather_submit_details", "Submit display details.",
                List.of(new McpInputDefinition("details", "details", "Display details", true, details)),
                new HttpExecutionDefinition(
                        HttpMethod.POST, URI.create("https://api.example.test"), "/details",
                        List.of(new ParameterBinding("details", ParameterLocation.BODY, "body"))),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var files = new SpringAi2ProjectGenerator()
                .generate(JavaSourceRendererTest.context(List.of(tool)))
                .files();
        java.util.Map<String, byte[]> filesWithCallbackTest = new java.util.LinkedHashMap<>(files);
        filesWithCallbackTest.put(
                "src/test/java/com/example/weather/application/GeneratedNestedEnumCallbackContractTest.java",
                nestedEnumCallbackContractTest().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertProjectBuilds(tempDir.resolve("nested-enum"), filesWithCallbackTest);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedProjectSerializesPrimitiveJsonBodiesBeforeCallingTheUpstream() throws Exception {
        ApiSchema text = new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema mode = new ApiSchema(
                SchemaType.STRING, null, false, List.of("brief", "full-detail"), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        var tool = new McpToolDefinition(
                "submitValue", "kma_weather_submit_value", "Submit a JSON value.",
                List.of(
                        new McpInputDefinition("body", "body", "Value", true, text),
                        new McpInputDefinition("mode", "mode", "Mode", false, mode)),
                new HttpExecutionDefinition(
                        HttpMethod.POST, URI.create("https://api.example.test"), "/value",
                        List.of(new ParameterBinding("body", ParameterLocation.BODY, "body"))),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var files = new SpringAi2ProjectGenerator()
                .generate(JavaSourceRendererTest.context(List.of(tool)))
                .files();
        java.util.Map<String, byte[]> filesWithBodyTest = new java.util.LinkedHashMap<>(files);
        filesWithBodyTest.put(
                "src/test/java/com/example/weather/application/GeneratedPrimitiveBodyContractTest.java",
                primitiveBodyContractTest().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertProjectBuilds(tempDir.resolve("primitive-body"), filesWithBodyTest);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedProjectEncodesReservedPathParameterCharactersBeforeExpansion() throws Exception {
        ApiSchema text = new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        var tool = new McpToolDefinition(
                "getResource", "kma_weather_get_resource", "Get a resource.",
                List.of(new McpInputDefinition("resourceId", "resourceId", "Resource identifier", true, text)),
                new HttpExecutionDefinition(
                        HttpMethod.GET, URI.create("https://api.example.test"), "/resources/{resourceId}",
                        List.of(new ParameterBinding("resourceId", ParameterLocation.PATH, "resourceId"))),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var files = new SpringAi2ProjectGenerator()
                .generate(JavaSourceRendererTest.context(List.of(tool)))
                .files();
        java.util.Map<String, byte[]> filesWithPathTest = new java.util.LinkedHashMap<>(files);
        filesWithPathTest.put(
                "src/test/java/com/example/weather/application/GeneratedPathEncodingContractTest.java",
                pathEncodingContractTest().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertProjectBuilds(tempDir.resolve("path-encoding"), filesWithPathTest);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedProjectMapsFlattenedJavaSafeInputsToOriginalObjectBodyProperties() throws Exception {
        ApiSchema postalCode = new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema deliveryMode = new ApiSchema(
                SchemaType.STRING, null, false, List.of("express", "standard"), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        var tool = new McpToolDefinition(
                "submitAddress", "kma_weather_submit_address", "Submit an address.",
                List.of(
                        new McpInputDefinition("postalCode", "postal-code", "Postal code", true, postalCode),
                        new McpInputDefinition("deliveryMode", "delivery-mode", "Delivery mode", true, deliveryMode)),
                new HttpExecutionDefinition(
                        HttpMethod.POST, URI.create("https://api.example.test"), "/address",
                        List.of(
                                new ParameterBinding("postalCode", ParameterLocation.BODY, "postal-code"),
                                new ParameterBinding("deliveryMode", ParameterLocation.BODY, "delivery-mode")),
                        true),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var files = new SpringAi2ProjectGenerator()
                .generate(JavaSourceRendererTest.context(List.of(tool)))
                .files();
        java.util.Map<String, byte[]> filesWithBodyTest = new java.util.LinkedHashMap<>(files);
        filesWithBodyTest.put(
                "src/test/java/com/example/weather/application/GeneratedFlattenedBodyContractTest.java",
                flattenedBodyContractTest().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertProjectBuilds(tempDir.resolve("flattened-body"), filesWithBodyTest);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedProjectOmitsOptionalEmptyObjectBodiesAndSendsRequiredEmptyObjectBodies() throws Exception {
        var optionalTool = new McpToolDefinition(
                "submitOptional", "kma_weather_submit_optional", "Submit an optional object.", List.of(),
                new HttpExecutionDefinition(
                        HttpMethod.POST, URI.create("https://api.example.test"), "/optional", List.of(), true, false),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var requiredTool = new McpToolDefinition(
                "submitRequired", "kma_weather_submit_required", "Submit a required object.", List.of(),
                new HttpExecutionDefinition(
                        HttpMethod.POST, URI.create("https://api.example.test"), "/required", List.of(), true, true),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var files = new SpringAi2ProjectGenerator()
                .generate(JavaSourceRendererTest.context(List.of(optionalTool, requiredTool)))
                .files();
        java.util.Map<String, byte[]> filesWithBodyTest = new java.util.LinkedHashMap<>(files);
        filesWithBodyTest.put(
                "src/test/java/com/example/weather/application/GeneratedEmptyObjectBodyContractTest.java",
                emptyObjectBodyContractTest().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertProjectBuilds(tempDir.resolve("empty-object-body"), filesWithBodyTest);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedProjectKeepsUnderscoreSecretPropertiesServerOnlyAndBuildable() throws Exception {
        var tool = new McpToolDefinition(
                "getCredential", "kma_weather_get_credential", "Get a credential.", List.of(),
                new HttpExecutionDefinition(
                        HttpMethod.GET, URI.create("https://api.example.test"), "/credential", List.of()),
                List.of(new SecretBinding("API_KEY", "api_key", ParameterLocation.QUERY, "api_key", true)),
                McpToolDefinition.OutputKind.GENERIC_JSON);
        var files = new SpringAi2ProjectGenerator()
                .generate(JavaSourceRendererTest.context(List.of(tool)))
                .files();

        assertTrue(new String(files.get("src/main/resources/application.yml"), java.nio.charset.StandardCharsets.UTF_8)
                .contains("api_key: \"${API_KEY:}\""));
        assertTrue(new String(files.get("src/main/java/com/example/weather/generated/metadata/WeatherOperations.java"),
                java.nio.charset.StandardCharsets.UTF_8).contains("provider.secrets.api_key"));
        assertFalse(new String(files.get("src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"),
                java.nio.charset.StandardCharsets.UTF_8).contains("api_key"));
        assertProjectBuilds(tempDir.resolve("underscore-secret"), files);
    }

    private void assertProjectBuilds(Path project, Map<String, byte[]> files) throws Exception {
        for (var entry : files.entrySet()) {
            Path target = project.resolve(entry.getKey()).normalize();
            assertTrue(target.startsWith(project), entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        assertTrue(project.resolve("gradlew").toFile().setExecutable(true));

        Process process = new ProcessBuilder(
                "./gradlew", "test", "--no-daemon", "--non-interactive")
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        ManagedTestProcess.Result result = ManagedTestProcess.run(
                process, Duration.ofMinutes(4), Duration.ofSeconds(10));
        String buildOutput = result.output();
        assertEquals(0, result.exitCode(), buildOutput);
        assertTrue(buildOutput.contains("BUILD SUCCESSFUL"), buildOutput);
    }

    private String enumCallbackContractTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.util.Arrays;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.atomic.AtomicInteger;
                import java.util.concurrent.atomic.AtomicReference;
                import com.example.weather.generated.metadata.WeatherOperations;
                import com.example.weather.runtime.OpenApiOperationExecutor;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.ai.tool.ToolCallback;
                import org.springframework.ai.tool.ToolCallbackProvider;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;

                @SpringBootTest(properties = {
                        "provider.response-max-bytes=1024",
                        "provider.connect-timeout-millis=1000",
                        "provider.read-timeout-millis=1000",
                        "provider.total-timeout-millis=1000"
                })
                class GeneratedEnumCallbackContractTest {
                    private static HttpServer server;
                    private static final AtomicReference<String> query = new AtomicReference<>();
                    private static final AtomicReference<List<String>> accepts = new AtomicReference<>();
                    private static final AtomicInteger requestCount = new AtomicInteger();

                    @Autowired
                    private ToolCallbackProvider toolCallbackProvider;

                    @Autowired
                    private OpenApiOperationExecutor executor;

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        try {
                            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            server.createContext("/forecast", exchange -> {
                                query.set(exchange.getRequestURI().getRawQuery());
                                accepts.set(List.copyOf(exchange.getRequestHeaders().get("Accept")));
                                requestCount.incrementAndGet();
                                if ("nx=99".equals(query.get())) {
                                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                                } else {
                                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                                }
                                exchange.sendResponseHeaders(200, 2);
                                exchange.getResponseBody().write("{}".getBytes());
                                exchange.close();
                            });
                            server.createContext("/alerts", exchange -> {
                                requestCount.incrementAndGet();
                                exchange.getResponseHeaders().set("Content-Type", "application/json");
                                exchange.sendResponseHeaders(200, 2);
                                exchange.getResponseBody().write("{}".getBytes());
                                exchange.close();
                            });
                            server.start();
                        } catch (java.io.IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                        registry.add("provider.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
                    }

                    @Test
                    void acceptsTheWireEnumValueAndForwardsItToTheUpstreamQuery() {
                        callback("kma_weather_get_forecast").call(
                                "{\\\"nx\\\":1,\\\"accept\\\":\\\"text/plain\\\",\\\"mode\\\":\\\"full-detail\\\",\\\"options\\\":{\\\"city\\\":\\\"Seoul\\\"}}");

                        assertTrue(query.get().contains("nx=1"), query.get());
                        assertTrue(query.get().contains("mode=full-detail"), query.get());
                        assertEquals(List.of("application/json"), accepts.get());
                    }

                    @Test
                    void rejectsAnUnknownEnumValueBeforeCallingTheUpstream() {
                        int before = requestCount.get();

                        assertThrows(RuntimeException.class,
                                () -> callback("kma_weather_get_forecast").call(
                                        "{\\\"nx\\\":1,\\\"mode\\\":\\\"unknown\\\",\\\"options\\\":{\\\"city\\\":\\\"Seoul\\\"}}"));

                        assertEquals(before, requestCount.get());
                    }

                    @Test
                    void rejectsRequiredAndNestedArgumentsBeforeCallingTheUpstream() {
                        int before = requestCount.get();

                        assertThrows(RuntimeException.class,
                                () -> callback("kma_weather_get_forecast").call(
                                        "{\\\"mode\\\":\\\"brief\\\",\\\"options\\\":{\\\"city\\\":\\\"Seoul\\\"}}"));
                        assertThrows(RuntimeException.class,
                                () -> callback("kma_weather_get_forecast").call(
                                        "{\\\"nx\\\":1,\\\"options\\\":{}}"));
                        assertThrows(RuntimeException.class,
                                () -> callback("kma_weather_get_alerts").call("{}"));

                        assertEquals(before, requestCount.get());
                    }

                    @Test
                    void acceptsTheOptionalEnumArgumentWhenItIsOmitted() {
                        int before = requestCount.get();

                        callback("kma_weather_get_forecast").call(
                                "{\\\"nx\\\":2,\\\"options\\\":{\\\"city\\\":\\\"Busan\\\"}}");
                        callback("kma_weather_get_alerts").call("{\\\"region\\\":\\\"Busan\\\"}");

                        assertEquals(before + 2, requestCount.get());
                        assertEquals("nx=2", query.get());
                    }

                    @Test
                    void rejectsJsonLookingTextResponsesBeforeDeserializingThem() {
                        var exception = assertThrows(IllegalStateException.class,
                                () -> executor.execute(WeatherOperations.GET_FORECAST, Map.of("nx", 99)));

                        assertEquals("UPSTREAM_RESPONSE_MEDIA_TYPE_UNSUPPORTED", exception.getMessage());
                    }

                    private ToolCallback callback(String name) {
                        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                                .filter(candidate -> candidate.getToolDefinition().name().equals(name))
                                .findFirst()
                                .orElseThrow();
                    }

                    @AfterAll
                    static void stopProvider() {
                        if (server != null) {
                            server.stop(0);
                        }
                    }
                }
                """;
    }

    private String nestedEnumCallbackContractTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertThrows;

                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.nio.charset.StandardCharsets;
                import java.util.Arrays;
                import java.util.concurrent.atomic.AtomicInteger;
                import java.util.concurrent.atomic.AtomicReference;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.ai.tool.ToolCallback;
                import org.springframework.ai.tool.ToolCallbackProvider;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;

                @SpringBootTest(properties = {
                        "provider.response-max-bytes=1024",
                        "provider.connect-timeout-millis=1000",
                        "provider.read-timeout-millis=1000",
                        "provider.total-timeout-millis=1000"
                })
                class GeneratedNestedEnumCallbackContractTest {
                    private static HttpServer server;
                    private static final AtomicReference<String> body = new AtomicReference<>();
                    private static final AtomicInteger requestCount = new AtomicInteger();

                    @Autowired
                    private ToolCallbackProvider toolCallbackProvider;

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        try {
                            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            server.createContext("/details", exchange -> {
                                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                                requestCount.incrementAndGet();
                                exchange.getResponseHeaders().set("Content-Type", "application/json");
                                exchange.sendResponseHeaders(200, 2);
                                exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                                exchange.close();
                            });
                            server.start();
                        } catch (java.io.IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                        registry.add("provider.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
                    }

                    @Test
                    void acceptsAValidNestedEnumAndPreservesItsSpacedJsonProperty() {
                        callback().call("{\\\"details\\\":{\\\"display name\\\":\\\"full-detail\\\"}}");

                        assertEquals("{\\\"display name\\\":\\\"full-detail\\\"}", body.get());
                    }

                    @Test
                    void rejectsAnUnknownNestedEnumBeforeCallingTheUpstream() {
                        int before = requestCount.get();

                        assertThrows(RuntimeException.class, () -> callback().call(
                                "{\\\"details\\\":{\\\"display name\\\":\\\"unknown\\\"}}"));

                        assertEquals(before, requestCount.get());
                    }

                    private ToolCallback callback() {
                        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                                .filter(candidate -> candidate.getToolDefinition().name()
                                        .equals("kma_weather_submit_details"))
                                .findFirst()
                                .orElseThrow();
                    }

                    @AfterAll
                    static void stopProvider() {
                        if (server != null) {
                            server.stop(0);
                        }
                    }
                }
                """;
    }

    private String primitiveBodyContractTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.nio.charset.StandardCharsets;
                import java.util.Arrays;
                import java.util.concurrent.atomic.AtomicReference;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.ai.tool.ToolCallbackProvider;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;

                @SpringBootTest(properties = {
                        "provider.response-max-bytes=1024",
                        "provider.connect-timeout-millis=1000",
                        "provider.read-timeout-millis=1000",
                        "provider.total-timeout-millis=1000"
                })
                class GeneratedPrimitiveBodyContractTest {
                    private static HttpServer server;
                    private static final AtomicReference<String> body = new AtomicReference<>();
                    private static final AtomicReference<String> contentType = new AtomicReference<>();

                    @Autowired
                    private ToolCallbackProvider toolCallbackProvider;

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        try {
                            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            server.createContext("/value", exchange -> {
                                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                                exchange.getResponseHeaders().set("Content-Type", "application/json");
                                exchange.sendResponseHeaders(200, 2);
                                exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                                exchange.close();
                            });
                            server.start();
                        } catch (java.io.IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                        registry.add("provider.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
                    }

                    @Test
                    void serializesAStringBodyAsQuotedJsonWithTheJsonContentType() {
                        Arrays.stream(toolCallbackProvider.getToolCallbacks())
                                .filter(callback -> callback.getToolDefinition().name().equals("kma_weather_submit_value"))
                                .findFirst()
                                .orElseThrow()
                                .call("{\\\"body\\\":\\\"hello\\\"}");

                        assertEquals("\\\"hello\\\"", body.get());
                        assertEquals("application/json", contentType.get());
                    }

                    @AfterAll
                    static void stopProvider() {
                        if (server != null) {
                            server.stop(0);
                        }
                    }
                }
                """;
    }

    private String pathEncodingContractTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import com.example.weather.generated.tool.WeatherMcpTools;
                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.util.concurrent.atomic.AtomicReference;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;

                @SpringBootTest(properties = {
                        "provider.response-max-bytes=1024",
                        "provider.connect-timeout-millis=1000",
                        "provider.read-timeout-millis=1000",
                        "provider.total-timeout-millis=1000"
                })
                class GeneratedPathEncodingContractTest {
                    private static HttpServer server;
                    private static final AtomicReference<String> rawPath = new AtomicReference<>();

                    @Autowired
                    private WeatherMcpTools tools;

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        try {
                            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            server.createContext("/resources/", exchange -> {
                                rawPath.set(exchange.getRequestURI().getRawPath());
                                exchange.getResponseHeaders().set("Content-Type", "application/json");
                                exchange.sendResponseHeaders(200, 2);
                                exchange.getResponseBody().write("{}".getBytes());
                                exchange.close();
                            });
                            server.start();
                        } catch (java.io.IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                        registry.add("provider.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
                    }

                    @Test
                    void keepsAPathParameterSlashInsideOneRouteSegment() {
                        tools.getResource("a/b");

                        assertEquals("/resources/a%2Fb", rawPath.get());
                    }

                    @AfterAll
                    static void stopProvider() {
                        if (server != null) {
                            server.stop(0);
                        }
                    }
                }
                """;
    }

    private String flattenedBodyContractTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.nio.charset.StandardCharsets;
                import java.util.Arrays;
                import java.util.concurrent.atomic.AtomicReference;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.ai.tool.ToolCallbackProvider;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;

                @SpringBootTest(properties = {
                        "provider.response-max-bytes=1024",
                        "provider.connect-timeout-millis=1000",
                        "provider.read-timeout-millis=1000",
                        "provider.total-timeout-millis=1000"
                })
                class GeneratedFlattenedBodyContractTest {
                    private static HttpServer server;
                    private static final AtomicReference<String> body = new AtomicReference<>();

                    @Autowired
                    private ToolCallbackProvider toolCallbackProvider;

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        try {
                            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            server.createContext("/address", exchange -> {
                                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                                exchange.getResponseHeaders().set("Content-Type", "application/json");
                                exchange.sendResponseHeaders(200, 2);
                                exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                                exchange.close();
                            });
                            server.start();
                        } catch (java.io.IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                        registry.add("provider.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
                    }

                    @Test
                    void invokesTheExplicitCallbackWithJavaSafeKeysAndPreservesUpstreamJsonProperties() {
                        Arrays.stream(toolCallbackProvider.getToolCallbacks())
                                .filter(callback -> callback.getToolDefinition().name().equals("kma_weather_submit_address"))
                                .findFirst()
                                .orElseThrow()
                                .call("{\\\"postalCode\\\":\\\"12345\\\",\\\"deliveryMode\\\":\\\"express\\\"}");

                        assertEquals("{\\\"delivery-mode\\\":\\\"express\\\",\\\"postal-code\\\":\\\"12345\\\"}", body.get());
                    }

                    @AfterAll
                    static void stopProvider() {
                        if (server != null) {
                            server.stop(0);
                        }
                    }
                }
                """;
    }

    private String emptyObjectBodyContractTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertNull;

                import com.example.weather.generated.tool.WeatherMcpTools;
                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.nio.charset.StandardCharsets;
                import java.util.concurrent.atomic.AtomicReference;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;

                @SpringBootTest(properties = {
                        "provider.response-max-bytes=1024",
                        "provider.connect-timeout-millis=1000",
                        "provider.read-timeout-millis=1000",
                        "provider.total-timeout-millis=1000"
                })
                class GeneratedEmptyObjectBodyContractTest {
                    private static HttpServer server;
                    private static final AtomicReference<String> optionalBody = new AtomicReference<>();
                    private static final AtomicReference<String> optionalContentType = new AtomicReference<>();
                    private static final AtomicReference<String> requiredBody = new AtomicReference<>();
                    private static final AtomicReference<String> requiredContentType = new AtomicReference<>();

                    @Autowired
                    private WeatherMcpTools tools;

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        try {
                            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            server.createContext("/optional", exchange -> respond(
                                    exchange, optionalBody, optionalContentType));
                            server.createContext("/required", exchange -> respond(
                                    exchange, requiredBody, requiredContentType));
                            server.start();
                        } catch (java.io.IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                        registry.add("provider.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
                    }

                    @Test
                    void sendsNoOptionalBodyButSerializesAnEmptyRequiredObject() {
                        tools.submitOptional();
                        tools.submitRequired();

                        assertEquals("", optionalBody.get());
                        assertNull(optionalContentType.get());
                        assertEquals("{}", requiredBody.get());
                        assertEquals("application/json", requiredContentType.get());
                    }

                    private static void respond(
                            com.sun.net.httpserver.HttpExchange exchange,
                            AtomicReference<String> body,
                            AtomicReference<String> contentType) throws java.io.IOException {
                        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, 2);
                        exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                        exchange.close();
                    }

                    @AfterAll
                    static void stopProvider() {
                        if (server != null) {
                            server.stop(0);
                        }
                    }
                }
                """;
    }
}
