package io.gen2spring.mcp.springai1;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class GeneratedRuntimeRegressionTest {
    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedMcpAdapterSeparatesExpectedAndInternalFailures() throws Exception {
        ApiSchema city = new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema code = new ApiSchema(
                SchemaType.INTEGER, "int32", false, List.of(), BigDecimal.ZERO, BigDecimal.TEN,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ResponseNormalizationPolicy normalization = new ResponseNormalizationPolicy(
                "/data", "/code", List.of("00"), "/message", null);
        var success = new McpToolDefinition(
                "getSuccess", "weather_get_success", "Get a successful weather response.",
                List.of(new McpInputDefinition("city", "city", "City", true, city)),
                new HttpExecutionDefinition(
                        HttpMethod.GET, URI.create("https://api.example.test"), "/success",
                        List.of(new ParameterBinding("city", ParameterLocation.QUERY, "city")),
                        false, false, normalization),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var providerFailure = new McpToolDefinition(
                "getProviderFailure", "weather_get_provider_failure", "Get a provider failure.",
                List.of(new McpInputDefinition("code", "code", "Code", true, code)),
                new HttpExecutionDefinition(
                        HttpMethod.GET, URI.create("https://api.example.test"), "/provider-failure",
                        List.of(new ParameterBinding("code", ParameterLocation.QUERY, "code")),
                        false, false, normalization),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var internalFailure = new McpToolDefinition(
                "internalFailure", "weather_internal_failure", "Trigger an internal failure.", List.of(),
                new HttpExecutionDefinition(
                        HttpMethod.GET, URI.create("https://api.example.test"), "/internal-failure", List.of(),
                        false, false, normalization),
                List.of(new SecretBinding(
                        "PROVIDER_SECRET", "service-secret", ParameterLocation.HEADER,
                        "X-Service-Secret", true)), McpToolDefinition.OutputKind.GENERIC_JSON);
        var fatalFailure = new McpToolDefinition(
                "fatalFailure", "weather_fatal_failure", "Trigger a fatal failure.", List.of(),
                new HttpExecutionDefinition(
                        HttpMethod.GET, URI.create("https://api.example.test"), "/fatal-failure", List.of(),
                        false, false, normalization),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var files = new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.context(
                        List.of(success, providerFailure, internalFailure, fatalFailure)))
                .files();
        java.util.Map<String, byte[]> filesWithMcpTest = new java.util.LinkedHashMap<>(files);
        String toolPath = "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java";
        String tools = new String(files.get(toolPath), java.nio.charset.StandardCharsets.UTF_8)
                .replace(
                        "return executor.execute(WeatherOperations.INTERNAL_FAILURE, input.toArguments());",
                        "return executor.execute(new com.example.weather.runtime.OperationDefinition("
                                + "\"internalFailure\", \"GET\", \"/missing/{private-internal-marker}\", "
                                + "java.util.List.of(), java.util.List.of(), false, false, null), "
                                + "input.toArguments());")
                .replace(
                        "return executor.execute(WeatherOperations.FATAL_FAILURE, input.toArguments());",
                        "throw com.example.weather.application.FatalFailureProbe.ERROR;");
        filesWithMcpTest.put(toolPath, tools.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        filesWithMcpTest.put(
                "src/main/java/com/example/weather/application/FatalFailureProbe.java",
                fatalFailureProbe().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        filesWithMcpTest.put(
                "src/test/java/com/example/weather/application/GeneratedMcpAdapterContractTest.java",
                mcpAdapterContractTest().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertProjectBuilds(tempDir.resolve("mcp-adapter"), filesWithMcpTest);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedResponseNormalizerEnforcesTheContract() throws Exception {
        var files = new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.context(List.of(
                        JavaSourceRendererTest.weatherTool(JavaSourceRendererTest.normalization()))))
                .files();
        java.util.Map<String, byte[]> filesWithNormalizerTest = new java.util.LinkedHashMap<>(files);
        filesWithNormalizerTest.put(
                "src/test/java/com/example/weather/runtime/GeneratedResponseNormalizerContractTest.java",
                responseNormalizerContractTest().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertProjectBuilds(tempDir.resolve("response-normalizer"), filesWithNormalizerTest);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedExponentSuccessValueRemainsBoundedAndCompiles() throws Exception {
        ResponseNormalizationPolicy exponent = new ResponseNormalizationPolicy(
                null, "/code", List.of(new BigDecimal("1e1000000")), null, null);
        var files = new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.context(List.of(
                        JavaSourceRendererTest.weatherTool(exponent))))
                .files();
        String metadataPath = "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java";
        String metadata = new String(files.get(metadataPath), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(metadata.length() < 20_000, "expanded metadata length=" + metadata.length());
        assertTrue(metadata.contains("new BigDecimal(\"1E+1000000\")"), metadata);
        assertProjectBuilds(tempDir.resolve("bounded-exponent"), files);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedExecutorMapsUpstreamFailures() throws Exception {
        var files = new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.context(List.of(
                        JavaSourceRendererTest.weatherTool(JavaSourceRendererTest.normalization()))))
                .files();
        java.util.Map<String, byte[]> filesWithExecutorTest = new java.util.LinkedHashMap<>(files);
        filesWithExecutorTest.put(
                "src/test/java/com/example/weather/runtime/GeneratedExecutorFailureContractTest.java",
                executorFailureContractTest().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        filesWithExecutorTest.put(
                "src/test/java/com/example/weather/runtime/GeneratedTelemetryExecutionContractTest.java",
                telemetryExecutionContractTest().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertProjectBuilds(tempDir.resolve("executor-failures"), filesWithExecutorTest);
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
        var files = new SpringAi1ProjectGenerator()
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
        var files = new SpringAi1ProjectGenerator()
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
    void generatedProjectOmitsAbsentNestedRecordPropertiesWithoutChangingArrayBodiesOrResponses() throws Exception {
        ApiSchema text = new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema details = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, Map.of("city", text, "label", text, "unit", text),
                List.of("city"), null, true, List.of());
        ApiSchema tags = new ApiSchema(
                SchemaType.ARRAY, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), text, true, List.of());
        var detailsTool = new McpToolDefinition(
                "submitDetails", "kma_weather_submit_details", "Submit weather details.",
                List.of(new McpInputDefinition("details", "details", "Weather details", true, details)),
                new HttpExecutionDefinition(
                        HttpMethod.POST, URI.create("https://api.example.test"), "/details",
                        List.of(new ParameterBinding("details", ParameterLocation.BODY, "body"))),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var tagsTool = new McpToolDefinition(
                "submitTags", "kma_weather_submit_tags", "Submit weather tags.",
                List.of(new McpInputDefinition("body", "body", "Weather tags", true, tags)),
                new HttpExecutionDefinition(
                        HttpMethod.POST, URI.create("https://api.example.test"), "/tags",
                        List.of(new ParameterBinding("body", ParameterLocation.BODY, "body"))),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var files = new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.context(List.of(detailsTool, tagsTool)))
                .files();
        java.util.Map<String, byte[]> filesWithBodyTest = new java.util.LinkedHashMap<>(files);
        filesWithBodyTest.put(
                "src/test/java/com/example/weather/application/GeneratedNullBodyPropertyContractTest.java",
                nullBodyPropertyContractTest().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertProjectBuilds(tempDir.resolve("null-body-property"), filesWithBodyTest);
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
        var files = new SpringAi1ProjectGenerator()
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
        var files = new SpringAi1ProjectGenerator()
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
        var files = new SpringAi1ProjectGenerator()
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
        var files = new SpringAi1ProjectGenerator()
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
        var files = new SpringAi1ProjectGenerator()
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
        Path targetJavaHome = requiredJavaHome(21);
        for (var entry : files.entrySet()) {
            Path target = project.resolve(entry.getKey()).normalize();
            assertTrue(target.startsWith(project), entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        assertTrue(project.resolve("gradlew").toFile().setExecutable(true));

        List<String> command = new java.util.ArrayList<>(List.of(
                "./gradlew", "test", "--no-daemon", "--non-interactive"));
        command.add("-Dorg.gradle.java.installations.auto-detect=false");
        command.add("-Dorg.gradle.java.installations.auto-download=false");
        command.add("-Dorg.gradle.java.installations.paths=" + targetJavaHome);
        Process process = new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        ManagedTestProcess.Result result = ManagedTestProcess.run(
                process, Duration.ofMinutes(4), Duration.ofSeconds(10));
        String buildOutput = result.output();
        assertEquals(0, result.exitCode(), buildOutput);
        assertTrue(buildOutput.contains("BUILD SUCCESSFUL"), buildOutput);
    }

    private Path requiredJavaHome(int javaVersion) {
        String environmentVariable = "GEN2SPRING_JAVA_" + javaVersion + "_HOME";
        String configured = System.getenv(environmentVariable);
        if ((configured == null || configured.isBlank()) && javaVersion == Runtime.version().feature()) {
            configured = System.getProperty("java.home");
        }
        assertTrue(configured != null && !configured.isBlank(), environmentVariable + " must be configured");
        Path javaHome = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(javaHome.resolve("bin/java")), environmentVariable);
        return javaHome;
    }

    private String telemetryExecutionContractTest() {
        return """
                package com.example.weather.runtime;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                import static org.junit.jupiter.api.Assertions.assertNull;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import io.micrometer.core.instrument.DistributionSummary;
                import io.micrometer.core.instrument.Gauge;
                import io.micrometer.core.instrument.Timer;
                import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
                import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
                import io.micrometer.observation.ObservationRegistry;
                import io.micrometer.tracing.Tracer;
                import java.util.concurrent.ArrayBlockingQueue;
                import java.util.concurrent.ThreadPoolExecutor;
                import java.util.concurrent.TimeUnit;
                import org.junit.jupiter.api.Test;

                class GeneratedTelemetryExecutionContractTest {
                    private static final String PROFILE =
                            "spring-ai-1.1-java21-mvc-streamable";

                    @Test
                    void recordsCanonicalMetersAndStopsEachCallOnlyOnce() {
                        SimpleMeterRegistry meters = new SimpleMeterRegistry();
                        ObservationRegistry observations = ObservationRegistry.create();
                        observations.observationConfig()
                                .observationHandler(new DefaultMeterObservationHandler(meters));
                        RuntimeTelemetry telemetry = new RuntimeTelemetry(observations, meters, Tracer.NOOP);
                        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
                        try {
                            telemetry.registerExecutor(executor);
                            telemetry.registerExecutor(executor);
                            RuntimeTelemetry.Call tool = telemetry.startToolCall(
                                    "kma_weather_get_forecast", "getForecast");
                            assertTrue(tool.complete(
                                    RuntimeTelemetry.Outcome.SUCCESS,
                                    RuntimeTelemetry.ErrorCategory.NONE,
                                    RuntimeTelemetry.HttpStatusClass.NONE));
                            assertFalse(tool.complete(
                                    RuntimeTelemetry.Outcome.SUCCESS,
                                    RuntimeTelemetry.ErrorCategory.NONE,
                                    RuntimeTelemetry.HttpStatusClass.NONE));

                            RuntimeTelemetry.Call provider = telemetry.startProviderCall("getForecast", "GET");
                            provider.responseStatus(200);
                            telemetry.recordResponseBytes(RuntimeTelemetry.HttpStatusClass.SUCCESS, 17);
                            assertTrue(provider.complete(
                                    RuntimeTelemetry.Outcome.SUCCESS,
                                    RuntimeTelemetry.ErrorCategory.NONE,
                                    RuntimeTelemetry.HttpStatusClass.SUCCESS));
                            assertFalse(provider.complete(
                                    RuntimeTelemetry.Outcome.EXPECTED_ERROR,
                                    RuntimeTelemetry.ErrorCategory.UPSTREAM_TIMEOUT,
                                    RuntimeTelemetry.HttpStatusClass.NONE));

                            Timer toolTimer = meters.find("gen2spring.runtime.mcp.tool.call")
                                    .tags("target.profile", PROFILE,
                                            "outcome", "success", "error.category", "none")
                                    .timer();
                            Timer providerTimer = meters.find("gen2spring.runtime.provider.request")
                                    .tags("target.profile", PROFILE, "outcome", "success",
                                            "error.category", "none", "http.status.class", "2xx")
                                    .timer();
                            DistributionSummary bytes = meters.find(
                                            "gen2spring.runtime.provider.response.bytes")
                                    .tags("target.profile", PROFILE, "http.status.class", "2xx")
                                    .summary();
                            Gauge active = meters.find("gen2spring.runtime.provider.executor.active")
                                    .tag("target.profile", PROFILE).gauge();
                            Gauge queued = meters.find("gen2spring.runtime.provider.executor.queued")
                                    .tag("target.profile", PROFILE).gauge();

                            assertNotNull(toolTimer);
                            assertNotNull(providerTimer);
                            assertNotNull(bytes);
                            assertNotNull(active);
                            assertNotNull(queued);
                            assertEquals(1L, toolTimer.count());
                            assertEquals(1L, providerTimer.count());
                            assertEquals(1L, bytes.count());
                            assertEquals(17.0, bytes.totalAmount());
                            assertEquals(0.0, active.value());
                            assertEquals(0.0, queued.value());
                            assertNull(telemetry.currentTraceparent());
                            assertTrue(telemetry.currentTraceIdOrFallback().matches("[0-9a-f]{32}"));
                        } finally {
                            executor.shutdownNow();
                            meters.close();
                        }
                    }
                }
                """;
    }

    private String responseNormalizerContractTest() {
        return """
                package com.example.weather.runtime;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertInstanceOf;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import com.example.weather.generated.metadata.WeatherOperations;
                import java.math.BigDecimal;
                import java.nio.charset.StandardCharsets;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import org.springframework.http.MediaType;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.json.JsonMapper;
                import com.fasterxml.jackson.databind.node.BooleanNode;
                import com.fasterxml.jackson.databind.node.JsonNodeFactory;
                import com.fasterxml.jackson.databind.node.TextNode;

                class GeneratedResponseNormalizerContractTest {
                    private final ResponseNormalizer normalizer = new ResponseNormalizer();
                    private final JsonMapper mapper = JsonMapper.builder().build();

                    @Test
                    void preservesExactAndLegacyOperationIdentity() {
                        assertEquals("getForecast", operation().operationId());
                        assertEquals("unknown",
                                new OperationDefinition("GET", "/legacy", List.of(), List.of()).operationId());
                        assertEquals("customOperation",
                                operation("customOperation", null).operationId());
                    }

                    @Test
                    void normalizesSuccessAndPreservesTypedMetadata() throws Exception {
                        OperationOutcome outcome = normalizer.normalize(operation(), 200,
                                MediaType.parseMediaType("application/problem+json; charset=UTF-8"),
                                json("{'response':{'header':{'code':'00','message':'NORMAL_SERVICE'},"
                                        + "'body':{'items':[{'id':1}],'totalCount':1}}}"),
                                List.of(), List.of());

                        assertEquals(jsonNode("{'data':[{'id':1}],'page':{'totalCount':1},"
                                + "'provider':{'code':'00','message':'NORMAL_SERVICE'}}"),
                                assertInstanceOf(NormalizedSuccess.class, outcome).payload());
                    }

                    @Test
                    void failsClosedForBusinessAndProtocolFailures() {
                        ProviderError business = assertInstanceOf(ProviderError.class,
                                normalizer.normalize(operation(), 200, MediaType.APPLICATION_JSON,
                                        json("{'response':{'header':{'code':30,'message':'INVALID'}}}"),
                                        List.of(), List.of()));
                        assertEquals("PROVIDER_BUSINESS", business.payload().at("/error/category").textValue());
                        assertTrue(business.payload().at("/error/providerCode").isIntegralNumber());
                        assertEquals("INVALID", business.payload().at("/error/providerMessage").textValue());
                        assertErrorShape(business, 200, false, "getForecast");

                        for (byte[] body : List.of(
                                json("{}"),
                                json("{'response':{'header':{'code':'00'}}}"))) {
                            ProviderError protocol = assertInstanceOf(ProviderError.class,
                                    normalizer.normalize(operation(), 200, MediaType.APPLICATION_JSON,
                                            body, List.of(), List.of()));
                            assertEquals("UPSTREAM_PROTOCOL",
                                    protocol.payload().at("/error/category").textValue());
                        }
                    }

                    @Test
                    void preservesRawNoPolicyJsonAndEmptyBodyCompatibility() throws Exception {
                        OperationDefinition raw = operation("rawOperation", null);
                        NormalizedSuccess json = assertInstanceOf(NormalizedSuccess.class,
                                normalizer.normalize(raw, 204, MediaType.APPLICATION_JSON,
                                        json("{'raw':true}"), List.of(), List.of()));
                        NormalizedSuccess empty = assertInstanceOf(NormalizedSuccess.class,
                                normalizer.normalize(raw, 204, null, new byte[0], List.of(), List.of()));

                        assertEquals(jsonNode("{'raw':true}"), json.payload());
                        assertTrue(empty.payload().isNull());
                    }

                    @Test
                    void normalizesEmptyBodyWhenThePolicyHasNoPointers() throws Exception {
                        ResponseNormalizationPolicy emptyPolicy = policy(
                                null, null, List.of(), null, null);
                        NormalizedSuccess success = assertInstanceOf(NormalizedSuccess.class,
                                normalizer.normalize(operation("emptyPolicy", emptyPolicy), 204, null,
                                        new byte[0], List.of(), List.of()));

                        assertEquals(jsonNode("{'data':null}"), success.payload());
                        assertCategory("UPSTREAM_PROTOCOL", normalizer.normalize(
                                operation("pointerPolicy", policy("/data", null, List.of(), null, null)),
                                204, null, new byte[0], List.of(), List.of()));
                    }

                    @Test
                    void evaluatesEscapedObjectAndArrayPointerTokens() throws Exception {
                        assertEquals(jsonNode("{'id':1}"), successData(
                                policy("/a~1b", null, List.of(), null, null),
                                "{'a/b':{'id':1}}"));
                        assertEquals(TextNode.valueOf("object-property"), successData(
                                policy("/items/01", null, List.of(), null, null),
                                "{'items':{'01':'object-property'}}"));
                        assertEquals(TextNode.valueOf("first"), successData(
                                policy("/items/0", null, List.of(), null, null),
                                "{'items':['first']}"));

                        ProviderError leadingZero = assertInstanceOf(ProviderError.class,
                                normalizer.normalize(
                                        operation("leadingZeroArray", policy(
                                                "/items/01", null, List.of(), null, null)),
                                        200, MediaType.APPLICATION_JSON,
                                        json("{'items':['first','second']}"), List.of(), List.of()));
                        assertEquals("UPSTREAM_PROTOCOL",
                                leadingZero.payload().at("/error/category").textValue());
                    }

                    @Test
                    void comparesDecimalSuccessCodesExactlyByNumericValue() {
                        ResponseNormalizationPolicy policy = policy(
                                null,
                                "/code",
                                List.of(JsonNodeFactory.instance.numberNode(new BigDecimal("90"))),
                                null,
                                null);

                        assertInstanceOf(NormalizedSuccess.class,
                                normalizer.normalize(operation("decimal", policy), 200,
                                        MediaType.APPLICATION_JSON, json("{'code':9E+1}"),
                                        List.of(), List.of()));
                    }

                    @Test
                    void preservesHighPrecisionBeforeComparingDecimalSuccessCodes() {
                        ResponseNormalizationPolicy exact = policy(
                                null,
                                "/code",
                                List.of(JsonNodeFactory.instance.numberNode(
                                        new BigDecimal("1.0000000000000000001"))),
                                null,
                                null);
                        ResponseNormalizationPolicy rounded = policy(
                                null,
                                "/code",
                                List.of(JsonNodeFactory.instance.numberNode(new BigDecimal("1.0"))),
                                null,
                                null);

                        assertInstanceOf(NormalizedSuccess.class,
                                normalizer.normalize(operation("exactDecimal", exact), 200,
                                        MediaType.APPLICATION_JSON,
                                        json("{'code':1.0000000000000000001}"), List.of(), List.of()));
                        assertCategory("PROVIDER_BUSINESS",
                                normalizer.normalize(operation("roundedDecimal", rounded), 200,
                                        MediaType.APPLICATION_JSON,
                                        json("{'code':1.0000000000000000001}"), List.of(), List.of()));
                    }

                    @Test
                    void rejectsInvalidJsonTrailingTokensAndUnsupportedMediaTypes() {
                        for (byte[] invalid : List.of(json("{"), json("{} {}"))) {
                            assertCategory("UPSTREAM_PROTOCOL",
                                    normalizer.normalize(operation(), 200, MediaType.APPLICATION_JSON,
                                            invalid, List.of(), List.of()));
                        }
                        assertCategory("UPSTREAM_PROTOCOL",
                                normalizer.normalize(operation(), 200, null,
                                        json("{}"), List.of(), List.of()));
                        assertCategory("UPSTREAM_PROTOCOL",
                                normalizer.normalize(operation(), 200, MediaType.TEXT_PLAIN,
                                        json("{}"), List.of(), List.of()));
                    }

                    @Test
                    void rejectsInvalidTotalCounts() {
                        ResponseNormalizationPolicy policy = policy(null, null, List.of(), null, "/count");
                        for (String count : List.of("-1", "1.5", "9223372036854775808", "'1'")) {
                            assertCategory("UPSTREAM_PROTOCOL", normalizer.normalize(
                                    operation("count", policy), 200, MediaType.APPLICATION_JSON,
                                    json("{'count':" + count + "}"), List.of(), List.of()));
                        }
                    }

                    @Test
                    void classifiesHttpStatusBeforeParsingProviderMetadata() {
                        assertHttp(400, "UPSTREAM_CLIENT", false);
                        assertHttp(408, "UPSTREAM_CLIENT", true);
                        assertHttp(425, "UPSTREAM_CLIENT", true);
                        assertHttp(429, "UPSTREAM_CLIENT", true);
                        assertHttp(500, "UPSTREAM_SERVER", true);
                        assertHttp(302, "UPSTREAM_PROTOCOL", false);
                    }

                    private void assertHttp(int status, String category, boolean retryable) {
                        ProviderError error = assertInstanceOf(ProviderError.class,
                                normalizer.normalize(operation(), status, MediaType.TEXT_PLAIN,
                                        json("private invalid body"), List.of(), List.of()));
                        assertEquals(category, error.payload().at("/error/category").textValue());
                        assertErrorShape(error, status, retryable, "getForecast");
                    }

                    private JsonNode successData(ResponseNormalizationPolicy policy, String body) {
                        NormalizedSuccess success = assertInstanceOf(NormalizedSuccess.class,
                                normalizer.normalize(operation("pointer", policy), 200,
                                        MediaType.APPLICATION_JSON, json(body), List.of(), List.of()));
                        return success.payload().get("data");
                    }

                    private void assertCategory(String category, OperationOutcome outcome) {
                        ProviderError error = assertInstanceOf(ProviderError.class, outcome);
                        assertEquals(category, error.payload().at("/error/category").textValue());
                    }

                    private void assertErrorShape(
                            ProviderError outcome, int status, boolean retryable, String operationId) {
                        JsonNode error = outcome.payload().get("error");
                        assertEquals(retryable, error.get("retryable").booleanValue());
                        assertEquals(status, error.get("httpStatus").intValue());
                        assertEquals(operationId, error.get("operationId").textValue());
                        assertTrue(error.get("traceId").textValue().matches("[0-9a-f]{32}"));
                        String serialized = error.toString();
                        assertTrue(serialized.indexOf("category") < serialized.indexOf("providerCode"));
                        assertTrue(serialized.indexOf("providerCode") < serialized.indexOf("providerMessage"));
                        assertTrue(serialized.indexOf("providerMessage") < serialized.indexOf("retryable"));
                        assertTrue(serialized.indexOf("retryable") < serialized.indexOf("httpStatus"));
                        assertTrue(serialized.indexOf("httpStatus") < serialized.indexOf("operationId"));
                        assertTrue(serialized.indexOf("operationId") < serialized.indexOf("traceId"));
                    }

                    private OperationDefinition operation() {
                        return WeatherOperations.GET_FORECAST;
                    }

                    private OperationDefinition operation(String operationId, ResponseNormalizationPolicy policy) {
                        return new OperationDefinition(
                                operationId, "GET", "/test", List.of(), List.of(), false, false, policy);
                    }

                    private ResponseNormalizationPolicy policy(
                            String dataPointer,
                            String successCodePointer,
                            List<JsonNode> successValues,
                            String errorMessagePointer,
                            String totalCountPointer) {
                        return new ResponseNormalizationPolicy(
                                dataPointer, successCodePointer, successValues,
                                errorMessagePointer, totalCountPointer);
                    }

                    private byte[] json(String value) {
                        return value.replace('\\'', '"').getBytes(StandardCharsets.UTF_8);
                    }

                    private JsonNode jsonNode(String value) throws Exception {
                        return mapper.readTree(value.replace('\\'', '"'));
                    }
                }
                """;
    }

    private String mcpAdapterContractTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                import static org.junit.jupiter.api.Assertions.assertSame;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import com.sun.net.httpserver.HttpExchange;
                import com.sun.net.httpserver.HttpServer;
                import io.modelcontextprotocol.server.McpServerFeatures;
                import io.modelcontextprotocol.spec.McpSchema;
                import java.io.IOException;
                import java.net.InetSocketAddress;
                import java.net.URI;
                import java.net.http.HttpClient;
                import java.net.http.HttpRequest;
                import java.net.http.HttpResponse;
                import java.nio.charset.StandardCharsets;
                import java.time.Duration;
                import java.util.HashSet;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.atomic.AtomicInteger;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.boot.test.web.server.LocalServerPort;
                import org.springframework.boot.test.system.CapturedOutput;
                import org.springframework.boot.test.system.OutputCaptureExtension;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.json.JsonMapper;

                @SpringBootTest(
                        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                        properties = {
                                "provider.response-max-bytes=1024",
                                "provider.connect-timeout-millis=1000",
                                "provider.read-timeout-millis=1000",
                                "provider.total-timeout-millis=1000",
                                "provider.secrets.service-secret=configured-secret-marker"
                        })
                @ExtendWith(OutputCaptureExtension.class)
                class GeneratedMcpAdapterContractTest {
                    private static HttpServer provider;
                    private static final AtomicInteger providerFailureCalls = new AtomicInteger();

                    @Autowired
                    @org.springframework.beans.factory.annotation.Qualifier("generatedToolSpecifications")
                    private List<McpServerFeatures.SyncToolSpecification> specifications;

                    @LocalServerPort
                    private int port;

                    private final HttpClient client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(2))
                            .build();
                    private final JsonMapper jsonMapper = JsonMapper.builder().build();

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        try {
                            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            provider.createContext("/success", exchange -> respond(
                                    exchange, "{'code':'00','message':'OK','data':{'forecast':'sunny'}}"));
                            provider.createContext("/provider-failure", exchange -> {
                                providerFailureCalls.incrementAndGet();
                                respond(exchange, "{'code':'BUSINESS_42','message':'Safe provider failure'}");
                            });
                            provider.start();
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                        registry.add("provider.base-url",
                                () -> "http://127.0.0.1:" + provider.getAddress().getPort());
                    }

                    @Test
                    void separatesSuccessfulProviderAndInternalToolResultsOverStreamableHttp(
                            CapturedOutput output) throws Exception {
                        URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
                        HttpResponse<String> initialize = post(endpoint,
                                "{'jsonrpc':'2.0','id':1,'method':'initialize','params':"
                                        + "{'protocolVersion':'2025-03-26','capabilities':{},"
                                        + "'clientInfo':{'name':'adapter-test','version':'1.0'}}}", null);
                        String sessionId = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
                        post(endpoint, "{'jsonrpc':'2.0','method':'notifications/initialized'}", sessionId);

                        JsonNode toolsResponse = response(post(endpoint,
                                "{'jsonrpc':'2.0','id':2,'method':'tools/list','params':{}}", sessionId));
                        JsonNode tools = toolsResponse.path("result").path("tools");
                        assertEquals(4, tools.size(), toolsResponse.toString());
                        var names = new HashSet<String>();
                        tools.forEach(tool -> names.add(tool.path("name").textValue()));
                        assertEquals(4, names.size(), toolsResponse.toString());
                        assertEquals(json("{'type':'object','properties':{'city':"
                                        + "{'type':'string','description':'City'}},'required':['city']}"),
                                tool(tools, "weather_get_success").path("inputSchema"));
                        assertEquals(json("{'type':'object','properties':{'code':"
                                        + "{'type':'integer','format':'int32','minimum':0,'maximum':10,"
                                        + "'description':'Code'}},'required':['code']}"),
                                tool(tools, "weather_get_provider_failure").path("inputSchema"));
                        assertEquals(json("{'type':'object','properties':{},'required':[]}"),
                                tool(tools, "weather_internal_failure").path("inputSchema"));
                        assertEquals(json("{'type':'object','properties':{},'required':[]}"),
                                tool(tools, "weather_fatal_failure").path("inputSchema"));

                        JsonNode success = response(call(endpoint, sessionId, 3,
                                "weather_get_success", "{'city':'Seoul'}"));
                        JsonNode successResult = success.path("result");
                        assertFalse(successResult.path("isError").booleanValue(), success.toString());
                        assertEquals(json("{'data':{'forecast':'sunny'},"
                                + "'provider':{'code':'00','message':'OK'}}"), parseOnlyText(successResult));

                        JsonNode providerFailure = response(call(endpoint, sessionId, 4,
                                "weather_get_provider_failure", "{'code':5}"));
                        JsonNode providerResult = providerFailure.path("result");
                        assertTrue(providerResult.path("isError").booleanValue(), providerFailure.toString());
                        assertEquals("PROVIDER_BUSINESS",
                                parseOnlyText(providerResult).at("/error/category").textValue());

                        assertSafeValidationFailure(response(call(endpoint, sessionId, 5,
                                "weather_get_provider_failure", "{}")));
                        assertSafeValidationFailure(response(call(endpoint, sessionId, 6,
                                "weather_get_provider_failure", "{'code':'raw-body-marker'}")));
                        assertSafeValidationFailure(response(call(endpoint, sessionId, 7,
                                "weather_get_provider_failure", "{'code':11}")));
                        assertEquals(1, providerFailureCalls.get(), "schema-invalid calls reached provider");

                        JsonNode internalFailure = response(call(endpoint, sessionId, 8,
                                "weather_internal_failure", "{}"));
                        assertTrue(internalFailure.has("error"), internalFailure.toString());
                        assertFalse(internalFailure.has("result"), internalFailure.toString());
                        assertEquals(-32603, internalFailure.at("/error/code").intValue(),
                                internalFailure.toString());
                        assertEquals("Generated Tool execution failed",
                                internalFailure.at("/error/message").textValue());
                        assertNoPrivateFailureDetail(internalFailure.toString());
                        assertFalse(internalFailure.toString().contains("ToolExecutionException"),
                                internalFailure.toString());

                        String captured = output.getAll();
                        String diagnostic = captured.lines()
                                .filter(line -> line.contains("generated_tool_adapter_failure"))
                                .filter(line -> line.contains("tool=weather_internal_failure"))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("Missing safe adapter diagnostic:\\n" + captured));
                        assertTrue(diagnostic.contains("tool=weather_internal_failure"), diagnostic);
                        assertTrue(diagnostic.contains(
                                "exception=org.springframework.ai.tool.execution.ToolExecutionException"), diagnostic);
                        assertTrue(diagnostic.contains("cause=java.lang.IllegalArgumentException"), diagnostic);
                        assertNoPrivateValues(diagnostic);
                        assertFalse(diagnostic.contains("arguments="), diagnostic);
                        assertFalse(diagnostic.contains("ToolExecutionException:"), diagnostic);
                        assertNoPrivateValues(captured);
                        assertFalse(captured.contains("arguments="), captured);
                    }

                    @Test
                    void rethrowsTheOriginalFatalErrorInstance() {
                        McpServerFeatures.SyncToolSpecification fatal = specifications.stream()
                                .filter(specification -> "weather_fatal_failure"
                                        .equals(specification.tool().name()))
                                .findFirst()
                                .orElseThrow();

                        Error thrown = assertThrows(Error.class, () -> fatal.callHandler().apply(
                                null, new McpSchema.CallToolRequest(
                                        "weather_fatal_failure", Map.of())));

                        assertSame(FatalFailureProbe.ERROR, thrown);
                    }

                    private void assertSafeValidationFailure(JsonNode response) throws Exception {
                        assertTrue(response.has("error"), response.toString());
                        assertFalse(response.has("result"), response.toString());
                        assertEquals(-32603, response.at("/error/code").intValue(), response.toString());
                        assertEquals("Generated Tool execution failed",
                                response.at("/error/message").textValue(), response.toString());
                        assertEquals("IllegalStateException: Generated Tool execution failed",
                                response.at("/error/data").textValue(), response.toString());
                        assertNoPrivateFailureDetail(response.toString());
                        assertFalse(response.toString().contains("PROVIDER_BUSINESS"), response.toString());
                    }

                    private void assertNoPrivateFailureDetail(String value) {
                        assertNoPrivateValues(value);
                        assertFalse(value.contains("IllegalArgumentException"), value);
                    }

                    private void assertNoPrivateValues(String value) {
                        assertNoSensitiveValues(value);
                        assertFalse(value.contains("at com.example"), value);
                        assertFalse(value.contains("\\tat "), value);
                    }

                    private void assertNoSensitiveValues(String value) {
                        assertFalse(value.contains("private-internal-marker"), value);
                        assertFalse(value.contains("private-cause-marker"), value);
                        assertFalse(value.contains("raw-private-body-marker"), value);
                        assertFalse(value.contains("raw-body-marker"), value);
                        assertFalse(value.contains("configured-secret-marker"), value);
                    }

                    private HttpResponse<String> call(
                            URI endpoint, String sessionId, int id, String name, String arguments) throws Exception {
                        return post(endpoint,
                                "{'jsonrpc':'2.0','id':" + id + ",'method':'tools/call','params':{'name':'"
                                        + name + "','arguments':" + arguments + "}}",
                                sessionId);
                    }

                    private HttpResponse<String> post(URI endpoint, String body, String sessionId) throws Exception {
                        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                                .timeout(Duration.ofSeconds(5))
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json, text/event-stream")
                                .POST(HttpRequest.BodyPublishers.ofString(body.replace('\\'', '"')));
                        if (sessionId != null) {
                            request.header("Mcp-Session-Id", sessionId);
                        }
                        HttpResponse<String> response = client.send(
                                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                                response.statusCode() + " " + response.body());
                        return response;
                    }

                    private JsonNode response(HttpResponse<String> response) throws Exception {
                        String body = response.body();
                        java.util.Optional<String> data = body.lines()
                                    .filter(line -> line.startsWith("data:"))
                                    .map(line -> line.substring("data:".length()).stripLeading())
                                    .findFirst();
                        if (data.isPresent()) {
                            body = data.get();
                        }
                        return jsonMapper.readTree(body);
                    }

                    private JsonNode parseOnlyText(JsonNode result) throws Exception {
                        JsonNode content = result.path("content");
                        assertEquals(1, content.size(), result.toString());
                        assertEquals("text", content.get(0).path("type").textValue(), result.toString());
                        String text = content.get(0).path("text").textValue();
                        assertNotNull(text, result.toString());
                        assertTrue(text.stripLeading().startsWith("{"), text);
                        return jsonMapper.readTree(text);
                    }

                    private JsonNode tool(JsonNode tools, String name) {
                        for (JsonNode tool : tools) {
                            if (name.equals(tool.path("name").textValue())) {
                                return tool;
                            }
                        }
                        throw new AssertionError("Missing Tool " + name + ": " + tools);
                    }

                    private JsonNode json(String value) throws Exception {
                        return jsonMapper.readTree(value.replace('\\'', '"'));
                    }

                    private static void respond(HttpExchange exchange, String body) throws IOException {
                        byte[] bytes = body.replace('\\'', '"').getBytes(StandardCharsets.UTF_8);
                        try (exchange) {
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, bytes.length);
                            exchange.getResponseBody().write(bytes);
                        }
                    }

                    @AfterAll
                    static void stopProvider() {
                        if (provider != null) {
                            provider.stop(0);
                        }
                    }
                }
                """;
    }

    private String fatalFailureProbe() {
        return """
                package com.example.weather.application;

                public final class FatalFailureProbe {
                    public static final AssertionError ERROR = new AssertionError("private-fatal-marker");

                    private FatalFailureProbe() {
                    }
                }
                """;
    }

    private String executorFailureContractTest() {
        return """
                package com.example.weather.runtime;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                import static org.junit.jupiter.api.Assertions.assertNull;
                import static org.junit.jupiter.api.Assertions.assertSame;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                import static org.junit.jupiter.api.Assertions.fail;

                import com.sun.net.httpserver.HttpExchange;
                import com.sun.net.httpserver.HttpServer;
                import java.io.IOException;
                import java.net.InetSocketAddress;
                import java.net.ServerSocket;
                import java.nio.charset.StandardCharsets;
                import java.time.Duration;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.CountDownLatch;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                import java.util.concurrent.TimeUnit;
                import java.util.concurrent.atomic.AtomicBoolean;
                import java.util.concurrent.atomic.AtomicReference;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.BeforeAll;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.ValueSource;
                import org.springframework.http.MediaType;
                import org.springframework.mock.env.MockEnvironment;
                import org.springframework.web.client.RestClient;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.node.TextNode;

                class GeneratedExecutorFailureContractTest {
                    private static final String OPERATION_ID = "getForecast";
                    private static final String PRIVATE_BODY_MARKER = "private-body-marker";
                    private static final String HEADER_MARKER = "header-secret-marker";
                    private static final String QUERY_MARKER = "query-secret-marker";
                    private static final String SECRET_VALUE = "secret-value";
                    private static final CountDownLatch BLOCKED_REQUEST = new CountDownLatch(1);
                    private static final CountDownLatch RELEASE_BLOCKED = new CountDownLatch(1);
                    private static final CountDownLatch INTERRUPT_REQUEST = new CountDownLatch(1);
                    private static final AtomicReference<Map<String, List<String>>> PROPAGATION_HEADERS =
                            new AtomicReference<>();

                    private static HttpServer server;
                    private static ExecutorService serverExecutor;
                    private static String baseUrl;

                    @BeforeAll
                    static void startProvider() throws Exception {
                        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                        serverExecutor = Executors.newCachedThreadPool(runnable -> {
                            Thread thread = new Thread(runnable, "generated-test-provider");
                            thread.setDaemon(true);
                            return thread;
                        });
                        server.setExecutor(serverExecutor);
                        server.createContext("/business", exchange -> json(exchange, 200,
                                "{'code':'30','message':'Authorization provider.secrets.service-key "
                                        + "serviceKey " + SECRET_VALUE + " " + HEADER_MARKER + " "
                                        + QUERY_MARKER + " cookie','private':'"
                                        + PRIVATE_BODY_MARKER + "','data':null}"));
                        for (int status : List.of(400, 408, 425, 429, 500)) {
                            server.createContext("/status/" + status, exchange -> text(exchange, status,
                                    PRIVATE_BODY_MARKER + " " + HEADER_MARKER + " " + QUERY_MARKER));
                        }
                        server.createContext("/redirect", exchange -> text(exchange, 302, PRIVATE_BODY_MARKER));
                        server.createContext("/invalid-json", exchange -> json(exchange, 200,
                                "{'private':'" + PRIVATE_BODY_MARKER + "'"));
                        server.createContext("/suffix-json", exchange -> problemJson(exchange, 200,
                                "{'code':'00','message':'ok','data':{'accepted':true}}"));
                        server.createContext("/oversize", exchange -> json(exchange, 200,
                                "{'value':'" + "x".repeat(2048) + "'}"));
                        server.createContext("/status-oversize", exchange -> json(exchange, 500,
                                "{'value':'" + PRIVATE_BODY_MARKER.repeat(128) + "'}"));
                        server.createContext("/malformed-type-400", exchange -> malformedType(
                                exchange, 400, "{'code':'30','message':'" + PRIVATE_BODY_MARKER + "'}"));
                        server.createContext("/malformed-type-500", exchange -> malformedType(
                                exchange, 500, "{'code':'50','message':'" + PRIVATE_BODY_MARKER + "'}"));
                        server.createContext("/malformed-type-200", exchange -> malformedType(
                                exchange, 200, "{'code':'00','message':'ok','data':{}}"));
                        server.createContext("/empty", GeneratedExecutorFailureContractTest::noContent);
                        server.createContext("/propagation-defense", exchange -> {
                            Map<String, List<String>> captured = new java.util.TreeMap<>(
                                    String.CASE_INSENSITIVE_ORDER);
                            exchange.getRequestHeaders().forEach(
                                    (name, values) -> captured.put(name, List.copyOf(values)));
                            PROPAGATION_HEADERS.set(Map.copyOf(captured));
                            json(exchange, 200, "{'accepted':true}");
                        });
                        server.createContext("/read-timeout", exchange -> {
                            sleep(Duration.ofMillis(500));
                            json(exchange, 200, "{'code':'00','message':'ok','data':{}}");
                        });
                        server.createContext("/total-timeout", exchange -> {
                            sleep(Duration.ofMillis(500));
                            json(exchange, 200, "{'code':'00','message':'ok','data':{}}");
                        });
                        server.createContext("/blocked", exchange -> {
                            BLOCKED_REQUEST.countDown();
                            await(RELEASE_BLOCKED);
                            json(exchange, 200, "{'code':'00','message':'ok','data':{}}");
                        });
                        server.createContext("/interrupt", exchange -> {
                            INTERRUPT_REQUEST.countDown();
                            sleep(Duration.ofSeconds(2));
                            json(exchange, 200, "{'code':'00','message':'ok','data':{}}");
                        });
                        server.start();
                        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
                    }

                    @Test
                    void mapsHttpBusinessProtocolBoundAndTransportFailures() throws Exception {
                        OpenApiOperationExecutor executor = executor(baseUrl, 512, 100, 1000, 4, 4);
                        try {
                            assertError(call(executor, "/business"), "PROVIDER_BUSINESS", false, 200);
                            assertError(call(executor, "/status/400"), "UPSTREAM_CLIENT", false, 400);
                            assertError(call(executor, "/status/408"), "UPSTREAM_CLIENT", true, 408);
                            assertError(call(executor, "/status/425"), "UPSTREAM_CLIENT", true, 425);
                            assertError(call(executor, "/status/429"), "UPSTREAM_CLIENT", true, 429);
                            assertError(call(executor, "/status/500"), "UPSTREAM_SERVER", true, 500);
                            assertError(call(executor, "/redirect"), "UPSTREAM_PROTOCOL", false, 302);
                            assertError(call(executor, "/invalid-json"), "UPSTREAM_PROTOCOL", false, 200);
                            assertError(call(executor, "/oversize"), "UPSTREAM_PROTOCOL", false, 200);
                            assertError(call(executor, "/status-oversize"),
                                    "UPSTREAM_SERVER", true, 500);
                            assertNullProviderMetadata(call(executor, "/malformed-type-400"),
                                    "UPSTREAM_CLIENT", false, 400);
                            assertNullProviderMetadata(call(executor, "/malformed-type-500"),
                                    "UPSTREAM_SERVER", true, 500);
                            assertError(call(executor, "/malformed-type-200"),
                                    "UPSTREAM_PROTOCOL", false, 200);
                            JsonNode empty = executor.execute(
                                    operation("/empty", new ResponseNormalizationPolicy(
                                            null, null, List.of(), null, null)), Map.of());
                            assertEquals(1, empty.size(), empty.toString());
                            assertTrue(empty.get("data").isNull(), empty.toString());
                            assertError(call(executor, operation("/empty")),
                                    "UPSTREAM_PROTOCOL", false, 204);
                            assertEquals(true, executor.execute(operation("/suffix-json"), Map.of())
                                    .at("/data/accepted").booleanValue());
                        } finally {
                            executor.shutdown();
                        }

                        OpenApiOperationExecutor readTimeout = executor(baseUrl, 512, 50, 1000, 1, 1);
                        try {
                            assertError(call(readTimeout, "/read-timeout"),
                                    "UPSTREAM_TIMEOUT", true, null);
                        } finally {
                            readTimeout.shutdown();
                        }

                        OpenApiOperationExecutor totalTimeout = executor(baseUrl, 512, 1000, 50, 1, 1);
                        try {
                            assertError(call(totalTimeout, "/total-timeout"),
                                    "UPSTREAM_TIMEOUT", true, null);
                        } finally {
                            totalTimeout.shutdown();
                        }

                        try (ServerSocket refused = new ServerSocket(0)) {
                            int port = refused.getLocalPort();
                            refused.close();
                            OpenApiOperationExecutor unavailable = executor(
                                    "http://127.0.0.1:" + port, 512, 100, 1000, 1, 1);
                            try {
                                assertError(call(unavailable, "/refused"),
                                        "UPSTREAM_UNAVAILABLE", true, null);
                            } finally {
                                unavailable.shutdown();
                            }
                        }
                    }

                    @Test
                    void mapsQueueSaturationAndInterruptionWithoutLosingInterruptState() throws Exception {
                        OpenApiOperationExecutor saturated = executor(baseUrl, 512, 2000, 3000, 1, 1);
                        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
                        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
                        Thread first = caller(saturated, "/blocked", firstFailure);
                        Thread second = caller(saturated, "/blocked", secondFailure);
                        try {
                            first.start();
                            assertTrue(BLOCKED_REQUEST.await(1, TimeUnit.SECONDS));
                            second.start();
                            awaitTimedWaiting(second);

                            assertError(call(saturated, "/blocked"), "LOCAL_RESOURCE", false, null);
                        } finally {
                            RELEASE_BLOCKED.countDown();
                            first.join(2000);
                            second.join(2000);
                            saturated.shutdown();
                        }
                        assertNull(firstFailure.get());
                        assertNull(secondFailure.get());

                        OpenApiOperationExecutor interrupted = executor(baseUrl, 512, 2000, 3000, 1, 1);
                        AtomicReference<ProviderErrorException> interruption = new AtomicReference<>();
                        AtomicBoolean interruptPreserved = new AtomicBoolean();
                        Thread caller = new Thread(() -> {
                            try {
                                interrupted.execute(operation("/interrupt"), Map.of());
                            } catch (ProviderErrorException failure) {
                                interruption.set(failure);
                                interruptPreserved.set(Thread.currentThread().isInterrupted());
                            }
                        }, "interrupted-executor-caller");
                        try {
                            caller.start();
                            assertTrue(INTERRUPT_REQUEST.await(1, TimeUnit.SECONDS));
                            caller.interrupt();
                            caller.join(1000);
                        } finally {
                            interrupted.shutdown();
                        }
                        assertFalse(caller.isAlive());
                        assertTrue(interruptPreserved.get());
                        assertError(interruption.get(), "LOCAL_RESOURCE", false, null);
                    }

                    @Test
                    void removesUserControlledPropagationHeadersAfterAllBindings() {
                        OpenApiOperationExecutor executor = executor(baseUrl, 512, 1000, 1000, 1, 1);
                        OperationDefinition operation = new OperationDefinition(
                                OPERATION_ID,
                                "GET",
                                "/propagation-defense",
                                List.of(
                                        new ParameterBinding("trace", ParameterLocation.HEADER, "TraceParent"),
                                        new ParameterBinding("state", ParameterLocation.HEADER, "tracestate"),
                                        new ParameterBinding("baggage", ParameterLocation.HEADER, "Baggage"),
                                        new ParameterBinding("b3", ParameterLocation.HEADER, "b3"),
                                        new ParameterBinding("xB3", ParameterLocation.HEADER, "X-B3-TraceId")),
                                List.of(new SecretBinding(
                                        "provider.secrets.authorization",
                                        ParameterLocation.HEADER,
                                        "X-B3-SpanId",
                                        true)),
                                false,
                                false,
                                null);
                        try {
                            assertTrue(executor.execute(operation, Map.of(
                                    "trace", "00-11111111111111111111111111111111-2222222222222222-01",
                                    "state", "private-state",
                                    "baggage", "private-baggage",
                                    "b3", "private-b3",
                                    "xB3", "private-x-b3")).get("accepted").booleanValue());
                        } finally {
                            executor.shutdown();
                        }

                        Map<String, List<String>> captured = PROPAGATION_HEADERS.get();
                        assertNotNull(captured);
                        for (String forbidden : List.of(
                                "traceparent", "tracestate", "baggage", "b3",
                                "x-b3-traceid", "x-b3-spanid")) {
                            assertFalse(captured.keySet().stream().anyMatch(forbidden::equalsIgnoreCase),
                                    captured.toString());
                        }
                    }

                    @Test
                    void propagatesUnexpectedWorkerFailuresForAdapterRedaction() {
                        OpenApiOperationExecutor executor = executor(baseUrl, 512, 1000, 1000, 1, 1);
                        OperationDefinition invalid = new OperationDefinition(
                                OPERATION_ID,
                                "GET",
                                "/missing/{" + PRIVATE_BODY_MARKER + "}",
                                List.of(),
                                List.of(),
                                false,
                                false,
                                null);
                        try {
                            IllegalArgumentException failure = assertThrows(
                                    IllegalArgumentException.class,
                                    () -> executor.execute(invalid, Map.of()));

                            assertTrue(failure.getMessage().contains(PRIVATE_BODY_MARKER), failure.getMessage());
                            for (RuntimeException expected : List.of(
                                    new NullPointerException("private-null-marker"),
                                    new RuntimeException("private-runtime-marker"))) {
                                Object argument = new Object() {
                                    @Override
                                    public String toString() {
                                        throw expected;
                                    }
                                };

                                RuntimeException actual = assertThrows(RuntimeException.class,
                                        () -> executor.execute(
                                                queryOperation("/runtime"), Map.of("value", argument)));

                                assertSame(expected, actual);
                            }
                        } finally {
                            executor.shutdown();
                        }
                    }

                    @Test
                    void rethrowsErrorsBeforeClassifyingTheirCauseChains() {
                        OpenApiOperationExecutor executor = executor(baseUrl, 512, 1000, 1000, 1, 1);
                        Error ioError = new java.io.IOError(new IOException("private-io-marker"));
                        Error resourceError = new AssertionError(new org.springframework.web.client.ResourceAccessException(
                                "private-resource-marker", new IOException("private-resource-cause")));
                        Error timeoutError = new AssertionError(
                                new java.net.SocketTimeoutException("private-timeout-marker"));
                        try {
                            for (Error expected : List.of(ioError, resourceError, timeoutError)) {
                                Object argument = new Object() {
                                    @Override
                                    public String toString() {
                                        throw expected;
                                    }
                                };

                                Error actual = assertThrows(Error.class, () -> executor.execute(
                                        queryOperation("/error"), Map.of("value", argument)));

                                assertSame(expected, actual);
                            }
                        } finally {
                            executor.shutdown();
                        }
                    }

                    @ParameterizedTest
                    @ValueSource(strings = {
                            "secret-value", "Authorization failed", "serviceKey invalid",
                            "clientSecret invalid", "cookie invalid"
                    })
                    void masksSecretValuesAndNames(String message) {
                        ProviderError error = new ResponseNormalizer().error(
                                operation("/business"), ProviderErrorCategory.PROVIDER_BUSINESS, 200,
                                TextNode.valueOf("30"), message,
                                List.of("Authorization", "serviceKey", "clientSecret", "cookie"),
                                List.of("secret-value"));

                        String serialized = error.payload().toString();
                        assertFalse(serialized.contains(message));
                        assertTrue(serialized.contains("***"));
                    }

                    @Test
                    void masksLongestSecretsFirstAndIgnoresBlankSanitizerValues() {
                        ResponseNormalizer normalizer = new ResponseNormalizer();
                        ProviderError longest = normalizer.error(
                                operation("/business"), ProviderErrorCategory.PROVIDER_BUSINESS, 200,
                                null, "secret-value clientSecret",
                                List.of("client", "clientSecret"),
                                List.of("secret", "secret-value"));
                        ProviderError blank = normalizer.error(
                                operation("/business"), ProviderErrorCategory.PROVIDER_BUSINESS, 200,
                                null, "safe message", List.of("\t"), List.of(" "));

                        assertEquals("*** ***", longest.payload().at("/error/providerMessage").textValue());
                        assertEquals("safe message", blank.payload().at("/error/providerMessage").textValue());
                    }

                    @Test
                    void replacesControlMessagesAndBoundsByUnicodeCodePoint() {
                        ResponseNormalizer normalizer = new ResponseNormalizer();
                        ProviderError unsafe = normalizer.error(
                                operation("/business"), ProviderErrorCategory.PROVIDER_BUSINESS, 200,
                                null, "unsafe\u0000value", List.of(), List.of());
                        ProviderError bounded = normalizer.error(
                                operation("/business"), ProviderErrorCategory.PROVIDER_BUSINESS, 200,
                                null, "가".repeat(600), List.of(), List.of());
                        String providerMessage = bounded.payload().at("/error/providerMessage").textValue();

                        assertEquals("Provider returned an unsafe error message",
                                unsafe.payload().at("/error/providerMessage").textValue());
                        assertEquals(512, providerMessage.codePointCount(0, providerMessage.length()));
                    }

                    private static Thread caller(
                            OpenApiOperationExecutor executor,
                            String path,
                            AtomicReference<Throwable> failure) {
                        return new Thread(() -> {
                            try {
                                executor.execute(operation(path), Map.of());
                            } catch (Throwable thrown) {
                                failure.set(thrown);
                            }
                        }, "executor-contract-caller");
                    }

                    private static void awaitTimedWaiting(Thread thread) throws InterruptedException {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                        while (System.nanoTime() < deadline) {
                            if (thread.getState() == Thread.State.TIMED_WAITING) {
                                return;
                            }
                            Thread.sleep(5);
                        }
                        fail("queued caller did not enter timed wait: " + thread.getState());
                    }

                    private static ProviderErrorException call(
                            OpenApiOperationExecutor executor, String path) {
                        return call(executor, operation(path));
                    }

                    private static ProviderErrorException call(
                            OpenApiOperationExecutor executor, OperationDefinition operation) {
                        return assertThrows(ProviderErrorException.class,
                                () -> executor.execute(operation, Map.of()));
                    }

                    private static void assertError(
                            ProviderErrorException failure,
                            String category,
                            boolean retryable,
                            Integer status) {
                        assertEquals("Generated provider request failed", failure.getMessage());
                        JsonNode error = failure.error().payload().get("error");
                        assertEquals(category, error.get("category").textValue());
                        assertEquals(retryable, error.get("retryable").booleanValue());
                        if (status == null) {
                            assertTrue(error.get("httpStatus").isNull());
                        } else {
                            assertEquals(status.intValue(), error.get("httpStatus").intValue());
                        }
                        assertEquals(OPERATION_ID, error.get("operationId").textValue());
                        assertTrue(error.get("traceId").textValue().matches("[0-9a-f]{32}"));
                        String serialized = failure.error().payload().toString();
                        for (String forbidden : List.of(
                                PRIVATE_BODY_MARKER, HEADER_MARKER, QUERY_MARKER, SECRET_VALUE,
                                "provider.secrets.service-key", "serviceKey", "Authorization",
                                "Exception", "java.", "at ", "Connection refused")) {
                            assertFalse(serialized.contains(forbidden), serialized);
                        }
                    }

                    private static void assertNullProviderMetadata(
                            ProviderErrorException failure,
                            String category,
                            boolean retryable,
                            Integer status) {
                        assertError(failure, category, retryable, status);
                        JsonNode error = failure.error().payload().get("error");
                        assertTrue(error.get("providerCode").isNull(), error.toString());
                        assertTrue(error.get("providerMessage").isNull(), error.toString());
                    }

                    private static OperationDefinition operation(String path) {
                        return operation(path, new ResponseNormalizationPolicy(
                                "/data", "/code", List.of(TextNode.valueOf("00")),
                                "/message", null));
                    }

                    private static OperationDefinition operation(
                            String path, ResponseNormalizationPolicy normalization) {
                        return new OperationDefinition(
                                OPERATION_ID,
                                "GET",
                                path,
                                List.of(),
                                List.of(
                                        new SecretBinding(
                                                "provider.secrets.service-key",
                                                ParameterLocation.QUERY,
                                                "serviceKey",
                                                true),
                                        new SecretBinding(
                                                "provider.secrets.authorization",
                                                ParameterLocation.HEADER,
                                                "Authorization",
                                                true),
                                        new SecretBinding(
                                                "provider.secrets.query-token",
                                                ParameterLocation.QUERY,
                                                "queryToken",
                                                true)),
                                false,
                                false,
                                normalization);
                    }

                    private static OperationDefinition queryOperation(String path) {
                        return new OperationDefinition(
                                OPERATION_ID,
                                "GET",
                                path,
                                List.of(new ParameterBinding("value", ParameterLocation.QUERY, "value")),
                                List.of(),
                                false,
                                false,
                                null);
                    }

                    private static OpenApiOperationExecutor executor(
                            String providerBaseUrl,
                            int responseMaxBytes,
                            long readTimeoutMillis,
                            long totalTimeoutMillis,
                            int maxConcurrentRequests,
                            int maxQueuedRequests) {
                        MockEnvironment environment = new MockEnvironment()
                                .withProperty("provider.base-url", providerBaseUrl)
                                .withProperty("provider.response-max-bytes", String.valueOf(responseMaxBytes))
                                .withProperty("provider.connect-timeout-millis", "100")
                                .withProperty("provider.read-timeout-millis", String.valueOf(readTimeoutMillis))
                                .withProperty("provider.total-timeout-millis", String.valueOf(totalTimeoutMillis))
                                .withProperty("provider.max-concurrent-requests", String.valueOf(maxConcurrentRequests))
                                .withProperty("provider.max-queued-requests", String.valueOf(maxQueuedRequests))
                                .withProperty("provider.secrets.service-key", SECRET_VALUE)
                                .withProperty("provider.secrets.authorization", HEADER_MARKER)
                                .withProperty("provider.secrets.query-token", QUERY_MARKER);
                        return new OpenApiOperationExecutor(RestClient.builder(), environment);
                    }

                    private static void json(HttpExchange exchange, int status, String body) throws IOException {
                        respond(exchange, status, "application/json", body);
                    }

                    private static void problemJson(HttpExchange exchange, int status, String body) throws IOException {
                        respond(exchange, status, "application/problem+json; charset=UTF-8", body);
                    }

                    private static void text(HttpExchange exchange, int status, String body) throws IOException {
                        respond(exchange, status, "text/plain", body);
                    }

                    private static void malformedType(
                            HttpExchange exchange, int status, String body) throws IOException {
                        respond(exchange, status, "application/json; charset=\\\"", body);
                    }

                    private static void noContent(HttpExchange exchange) throws IOException {
                        try (exchange) {
                            exchange.sendResponseHeaders(204, -1);
                        }
                    }

                    private static void respond(
                            HttpExchange exchange, int status, String contentType, String body) throws IOException {
                        byte[] bytes = body.replace('\\'', '"').getBytes(StandardCharsets.UTF_8);
                        try (exchange) {
                            exchange.getResponseHeaders().set("Content-Type", contentType);
                            exchange.sendResponseHeaders(status, bytes.length);
                            exchange.getResponseBody().write(bytes);
                        }
                    }

                    private static void sleep(Duration duration) {
                        try {
                            Thread.sleep(duration.toMillis());
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    private static void await(CountDownLatch latch) {
                        try {
                            latch.await();
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @AfterAll
                    static void stopProvider() {
                        RELEASE_BLOCKED.countDown();
                        if (server != null) {
                            server.stop(0);
                        }
                        if (serverExecutor != null) {
                            serverExecutor.shutdownNow();
                        }
                    }
                }
                """;
    }

    private String enumCallbackContractTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.atomic.AtomicInteger;
                import java.util.concurrent.atomic.AtomicReference;
                import com.example.weather.generated.metadata.WeatherOperations;
                import com.example.weather.runtime.OpenApiOperationExecutor;
                import com.example.weather.runtime.ProviderErrorException;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import io.modelcontextprotocol.server.McpServerFeatures;
                import io.modelcontextprotocol.spec.McpSchema;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;
                import com.fasterxml.jackson.databind.json.JsonMapper;

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
                    @org.springframework.beans.factory.annotation.Qualifier("generatedToolSpecifications")
                    private List<McpServerFeatures.SyncToolSpecification> specifications;

                    private final JsonMapper jsonMapper = JsonMapper.builder().build();

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
                    void acceptsTheWireEnumValueAndForwardsItToTheUpstreamQuery() throws Exception {
                        callTool("kma_weather_get_forecast",
                                "{\\\"nx\\\":1,\\\"accept\\\":\\\"text/plain\\\",\\\"mode\\\":\\\"full-detail\\\",\\\"options\\\":{\\\"city\\\":\\\"Seoul\\\"}}");

                        assertTrue(query.get().contains("nx=1"), query.get());
                        assertTrue(query.get().contains("mode=full-detail"), query.get());
                        assertEquals(List.of("application/json"), accepts.get());
                    }

                    @Test
                    void rejectsAnUnknownEnumValueBeforeCallingTheUpstream() {
                        int before = requestCount.get();

                        assertThrows(RuntimeException.class,
                                () -> callTool("kma_weather_get_forecast",
                                        "{\\\"nx\\\":1,\\\"mode\\\":\\\"unknown\\\",\\\"options\\\":{\\\"city\\\":\\\"Seoul\\\"}}"));

                        assertEquals(before, requestCount.get());
                    }

                    @Test
                    void rejectsRequiredAndNestedArgumentsBeforeCallingTheUpstream() {
                        int before = requestCount.get();

                        assertThrows(RuntimeException.class,
                                () -> callTool("kma_weather_get_forecast",
                                        "{\\\"mode\\\":\\\"brief\\\",\\\"options\\\":{\\\"city\\\":\\\"Seoul\\\"}}"));
                        assertThrows(RuntimeException.class,
                                () -> callTool("kma_weather_get_forecast",
                                        "{\\\"nx\\\":1,\\\"options\\\":{}}"));
                        assertThrows(RuntimeException.class,
                                () -> callTool("kma_weather_get_alerts", "{}"));

                        assertEquals(before, requestCount.get());
                    }

                    @Test
                    void acceptsTheOptionalEnumArgumentWhenItIsOmitted() throws Exception {
                        int before = requestCount.get();

                        callTool("kma_weather_get_forecast",
                                "{\\\"nx\\\":2,\\\"options\\\":{\\\"city\\\":\\\"Busan\\\"}}");
                        callTool("kma_weather_get_alerts", "{\\\"region\\\":\\\"Busan\\\"}");

                        assertEquals(before + 2, requestCount.get());
                        assertEquals("nx=2", query.get());
                    }

                    @Test
                    void rejectsJsonLookingTextResponsesBeforeDeserializingThem() {
                        var exception = assertThrows(ProviderErrorException.class,
                                () -> executor.execute(WeatherOperations.GET_FORECAST, Map.of("nx", 99)));

                        assertEquals("UPSTREAM_PROTOCOL",
                                exception.error().payload().at("/error/category").textValue());
                    }

                    private void callTool(String name, String arguments) throws Exception {
                        var specification = specifications.stream()
                                .filter(candidate -> candidate.tool().name().equals(name))
                                .findFirst()
                                .orElseThrow();
                        specification.callHandler().apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        name, jsonMapper.readValue(arguments, Map.class)));
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
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.atomic.AtomicInteger;
                import java.util.concurrent.atomic.AtomicReference;
                import io.modelcontextprotocol.server.McpServerFeatures;
                import io.modelcontextprotocol.spec.McpSchema;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;
                import com.fasterxml.jackson.databind.json.JsonMapper;

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
                    @org.springframework.beans.factory.annotation.Qualifier("generatedToolSpecifications")
                    private List<McpServerFeatures.SyncToolSpecification> specifications;

                    private final JsonMapper jsonMapper = JsonMapper.builder().build();

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
                    void acceptsAValidNestedEnumAndPreservesItsSpacedJsonProperty() throws Exception {
                        callTool("{\\\"details\\\":{\\\"display name\\\":\\\"full-detail\\\"}}");

                        assertEquals("{\\\"display name\\\":\\\"full-detail\\\"}", body.get());
                    }

                    @Test
                    void rejectsAnUnknownNestedEnumBeforeCallingTheUpstream() {
                        int before = requestCount.get();

                        assertThrows(RuntimeException.class, () -> callTool(
                                "{\\\"details\\\":{\\\"display name\\\":\\\"unknown\\\"}}"));

                        assertEquals(before, requestCount.get());
                    }

                    private void callTool(String arguments) throws Exception {
                        var specification = specifications.stream()
                                .filter(candidate -> candidate.tool().name()
                                        .equals("kma_weather_submit_details"))
                                .findFirst()
                                .orElseThrow();
                        specification.callHandler().apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "kma_weather_submit_details",
                                        jsonMapper.readValue(arguments, Map.class)));
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
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.atomic.AtomicReference;
                import io.modelcontextprotocol.server.McpServerFeatures;
                import io.modelcontextprotocol.spec.McpSchema;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;
                import com.fasterxml.jackson.databind.json.JsonMapper;

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
                    @org.springframework.beans.factory.annotation.Qualifier("generatedToolSpecifications")
                    private List<McpServerFeatures.SyncToolSpecification> specifications;

                    private final JsonMapper jsonMapper = JsonMapper.builder().build();

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
                    void serializesAStringBodyAsQuotedJsonWithTheJsonContentType() throws Exception {
                        var specification = specifications.stream()
                                .filter(candidate -> candidate.tool().name().equals("kma_weather_submit_value"))
                                .findFirst()
                                .orElseThrow();
                        specification.callHandler().apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "kma_weather_submit_value",
                                        jsonMapper.readValue("{\\\"body\\\":\\\"hello\\\"}", Map.class)));

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

    private String nullBodyPropertyContractTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import com.example.weather.generated.model.SubmitDetailsDetails;
                import com.example.weather.generated.tool.WeatherMcpTools;
                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.nio.charset.StandardCharsets;
                import java.util.List;
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
                class GeneratedNullBodyPropertyContractTest {
                    private static HttpServer server;
                    private static final AtomicReference<String> detailsBody = new AtomicReference<>();
                    private static final AtomicReference<String> tagsBody = new AtomicReference<>();

                    @Autowired
                    private WeatherMcpTools tools;

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        try {
                            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            server.createContext("/details", exchange -> respond(exchange, detailsBody));
                            server.createContext("/tags", exchange -> respond(exchange, tagsBody));
                            server.start();
                        } catch (java.io.IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                        registry.add("provider.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
                    }

                    @Test
                    void omitsAnAbsentOptionalNestedRecordComponentAndKeepsItsRequiredSibling() {
                        var response = tools.submitDetails(new SubmitDetailsDetails("Seoul", null, "metric"));

                        assertEquals("{\\\"city\\\":\\\"Seoul\\\",\\\"unit\\\":\\\"metric\\\"}", detailsBody.get());
                        assertEquals("{\\\"validated\\\":true}", response.toString());
                    }

                    @Test
                    void keepsRootArrayBodySerializationAndResponseParsing() {
                        var response = tools.submitTags(List.of("spring", "ai"));

                        assertEquals("[\\\"spring\\\",\\\"ai\\\"]", tagsBody.get());
                        assertEquals("{\\\"validated\\\":true}", response.toString());
                    }

                    private static void respond(
                            com.sun.net.httpserver.HttpExchange exchange,
                            AtomicReference<String> body) throws java.io.IOException {
                        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        byte[] response = "{\\\"validated\\\":true}".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
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
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.atomic.AtomicReference;
                import io.modelcontextprotocol.server.McpServerFeatures;
                import io.modelcontextprotocol.spec.McpSchema;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;
                import com.fasterxml.jackson.databind.json.JsonMapper;

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
                    @org.springframework.beans.factory.annotation.Qualifier("generatedToolSpecifications")
                    private List<McpServerFeatures.SyncToolSpecification> specifications;

                    private final JsonMapper jsonMapper = JsonMapper.builder().build();

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
                    void invokesTheExplicitCallbackWithJavaSafeKeysAndPreservesUpstreamJsonProperties()
                            throws Exception {
                        var specification = specifications.stream()
                                .filter(candidate -> candidate.tool().name().equals("kma_weather_submit_address"))
                                .findFirst()
                                .orElseThrow();
                        specification.callHandler().apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "kma_weather_submit_address",
                                        jsonMapper.readValue(
                                                "{\\\"postalCode\\\":\\\"12345\\\",\\\"deliveryMode\\\":\\\"express\\\"}",
                                                Map.class)));

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
