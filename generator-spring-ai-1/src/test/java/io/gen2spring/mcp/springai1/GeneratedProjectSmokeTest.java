package io.gen2spring.mcp.springai1;

import io.gen2spring.mcp.domain.tool.OutputKind;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.ToolOutput;
import java.math.BigDecimal;
import java.net.URI;
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

class GeneratedProjectSmokeTest {
    private static final List<String> PROJECT_FILES = List.of(
            ".dockerignore",
            ".gitignore",
            "Dockerfile",
            "README.md",
            "build.gradle.kts",
            "gradle.properties",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradlew",
            "gradlew.bat",
            "settings.gradle.kts",
            "src/main/resources/application.yml");

    @TempDir
    Path tempDir;

    @Test
    void emitsFixedProjectFilesBeforeSortedJavaSourcesAndReturnsAnUnmodifiableMap() {
        Map<String, byte[]> files = new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.contextWithWeatherTool(profile(21)))
                .files();
        List<String> paths = List.copyOf(files.keySet());

        assertEquals(PROJECT_FILES, paths.subList(0, PROJECT_FILES.size()));
        List<String> javaPaths = paths.subList(PROJECT_FILES.size(), paths.size());
        assertEquals(javaPaths.stream().sorted().toList(), javaPaths);
        assertThrows(UnsupportedOperationException.class,
                () -> files.put("unexpected", new byte[0]));
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedJava17ProjectCompilesAndTestsOnJava17() throws Exception {
        assertProjectBuilds(tempDir.resolve("java17"), 17);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedJava21ProjectCompilesBootsAndRegistersExactlyOneToolOnJava21() throws Exception {
        assertProjectBuilds(tempDir.resolve("java21"), 21);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedTypedOutputsRoundTripWholeAndNormalizedResponses() throws Exception {
        var generated = new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.context(typedOutputTools()))
                .files();
        Map<String, byte[]> files = new LinkedHashMap<>(generated);
        files.put(
                "src/test/java/com/example/weather/application/GeneratedTypedOutputContractTest.java",
                typedOutputContractTest().getBytes(UTF_8));

        assertProjectBuilds(tempDir.resolve("typed-output"), files, 21);
    }

    private void assertProjectBuilds(Path project, int javaVersion) throws Exception {
        var generated = new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.contextWithWeatherTool(profile(javaVersion)))
                .files();
        Map<String, byte[]> files = new LinkedHashMap<>(generated);
        files.put(
                "src/test/java/com/example/weather/application/GeneratedSpecificationRegistrationTest.java",
                exactSpecificationRegistrationTest().getBytes(UTF_8));
        files.put(
                "src/test/java/com/example/weather/application/GeneratedObservabilityContextTest.java",
                observabilityContextTest().getBytes(UTF_8));
        files.put(
                "src/test/java/com/example/weather/application/GeneratedObservabilityRuntimeTest.java",
                observabilityRuntimeTest(profile(javaVersion).id()).getBytes(UTF_8));
        addTelemetryTestDependency(files);
        assertProjectBuilds(project, files, javaVersion);
    }

    private void assertProjectBuilds(Path project, Map<String, byte[]> files, int javaVersion) throws Exception {
        writeProject(project, files);

        Path javaHome = requiredJavaHome(javaVersion);
        List<String> command = new ArrayList<>(List.of(
                gradleWrapper(project).toString(),
                "compileJava", "test", "--no-daemon", "--non-interactive"));
        command.add("-Dorg.gradle.java.installations.auto-detect=false");
        command.add("-Dorg.gradle.java.installations.auto-download=false");
        command.add("-Dorg.gradle.java.installations.paths=" + javaHome);
        Process process = new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        ManagedTestProcess.Result result = ManagedTestProcess.run(
                process, Duration.ofMinutes(4), Duration.ofSeconds(10));

        assertEquals(0, result.exitCode(), buildDiagnostics(project, result.output()));
        assertTrue(result.output().contains("BUILD SUCCESSFUL"), result.output());
    }

    private List<ToolDefinition> typedOutputTools() {
        ApiSchema text = new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema count = new ApiSchema(
                SchemaType.INTEGER, "int64", false, List.of(), BigDecimal.ZERO, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema city = objectSchema(Map.of("city-name", text), List.of("city-name"));
        ApiSchema provider = objectSchema(Map.of("payload", city, "total", count), List.of("payload", "total"));
        ApiSchema page = objectSchema(Map.of("totalCount", count), List.of("totalCount"));
        ApiSchema normalized = objectSchema(Map.of("data", city, "page", page), List.of("data", "page"));
        return List.of(
                typedTool("wholeResponse", "/whole", null, city, city),
                typedTool("normalizedResponse", "/normalized",
                        new ResponseNormalizationPolicy("/payload", null, List.of(), null, "/total"),
                        provider, normalized),
                typedTool("malformedResponse", "/malformed", null, city, city));
    }

    private ToolDefinition typedTool(
            String operationId,
            String path,
            ResponseNormalizationPolicy normalization,
            ApiSchema providerSchema,
            ApiSchema resultSchema) {
        return new ToolDefinition(
                operationId,
                "weather_" + operationId.replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT),
                "Get a typed weather response.",
                List.of(),
                new HttpExecution(
                        HttpMethod.GET, URI.create("https://api.example.test"), path, List.of(),
                        false, false, normalization),
                List.of(),
                new ToolOutput(OutputKind.TYPED_DTO, providerSchema, resultSchema));
    }

    private ApiSchema objectSchema(Map<String, ApiSchema> properties, List<String> required) {
        return new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, properties, required, null, true, List.of());
    }

    private String buildDiagnostics(Path project, String output) throws Exception {
        Path results = project.resolve("build/test-results/test");
        if (!Files.isDirectory(results)) {
            return output;
        }
        StringBuilder diagnostics = new StringBuilder(output);
        try (var files = Files.list(results)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted().toList()) {
                diagnostics.append('\n').append(Files.readString(file));
            }
        }
        return diagnostics.toString();
    }

    private String typedOutputContractTest() {
        return """
                package com.example.weather.application;

                import static java.nio.charset.StandardCharsets.UTF_8;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertNull;
                import static org.junit.jupiter.api.Assertions.assertThrows;

                import com.example.weather.generated.model.NormalizedResponseResult;
                import com.example.weather.generated.model.WholeResponseResult;
                import com.example.weather.generated.tool.WeatherMcpTools;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.sun.net.httpserver.HttpExchange;
                import com.sun.net.httpserver.HttpServer;
                import java.io.IOException;
                import java.net.InetSocketAddress;
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
                class GeneratedTypedOutputContractTest {
                    private static HttpServer server;

                    @Autowired WeatherMcpTools tools;
                    @Autowired ObjectMapper objectMapper;

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        try {
                            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            server.createContext("/whole", exchange -> respond(
                                    exchange, "{\\\"city-name\\\":\\\"Seoul\\\"}"));
                            server.createContext("/normalized", exchange -> respond(
                                    exchange, "{\\\"payload\\\":{\\\"city-name\\\":\\\"Busan\\\"},\\\"total\\\":1}"));
                            server.createContext("/malformed", exchange -> respond(
                                    exchange, "{\\\"city-name\\\":{\\\"private\\\":\\\"raw-private-marker\\\"}}"));
                            server.start();
                        } catch (IOException failure) {
                            throw new IllegalStateException("Test provider failed to start", failure);
                        }
                        registry.add("provider.base-url",
                                () -> "http://127.0.0.1:" + server.getAddress().getPort());
                    }

                    @Test
                    void returnsTypedWholeAndNormalizedResultsWithoutChangingJsonShape() throws Exception {
                        WholeResponseResult whole = tools.wholeResponse();
                        NormalizedResponseResult normalized = tools.normalizedResponse();

                        assertEquals("Seoul", whole.cityName());
                        assertEquals("Busan", normalized.data().cityName());
                        assertEquals(1L, normalized.page().totalCount());
                        assertEquals("{\\\"city-name\\\":\\\"Seoul\\\"}", objectMapper.writeValueAsString(whole));
                        assertEquals("{\\\"data\\\":{\\\"city-name\\\":\\\"Busan\\\"},\\\"page\\\":{\\\"totalCount\\\":1}}",
                                objectMapper.writeValueAsString(normalized));

                        IllegalStateException failure = assertThrows(
                                IllegalStateException.class, () -> tools.malformedResponse());
                        assertEquals("Generated Tool result conversion failed", failure.getMessage());
                        assertNull(failure.getCause());
                    }

                    private static void respond(HttpExchange exchange, String json) throws IOException {
                        byte[] body = json.getBytes(UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        }
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

    private void writeProject(Path project, Map<String, byte[]> files) throws Exception {
        for (var entry : files.entrySet()) {
            Path target = project.resolve(entry.getKey()).normalize();
            assertTrue(target.startsWith(project), entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        if (!isWindows()) {
            assertTrue(project.resolve("gradlew").toFile().setExecutable(true));
        }
    }

    private CompatibilityProfile profile(int javaVersion) {
        return CompatibilityProfileRegistry.defaults()
                .find("spring-ai-1.1-java" + javaVersion + "-mvc-streamable")
                .orElseThrow();
    }

    private Path requiredJavaHome(int javaVersion) {
        String environmentVariable = "GEN2SPRING_JAVA_" + javaVersion + "_HOME";
        String configured = System.getenv(environmentVariable);
        if ((configured == null || configured.isBlank()) && javaVersion == Runtime.version().feature()) {
            configured = System.getProperty("java.home");
        }
        assertTrue(configured != null && !configured.isBlank(), environmentVariable + " must be configured");
        Path javaHome = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(javaExecutable(javaHome)), environmentVariable);
        return javaHome;
    }

    private Path gradleWrapper(Path project) {
        return project.resolve(isWindows() ? "gradlew.bat" : "gradlew");
    }

    private Path javaExecutable(Path javaHome) {
        return javaHome.resolve(isWindows() ? "bin/java.exe" : "bin/java");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    private String exactSpecificationRegistrationTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import io.modelcontextprotocol.server.McpServerFeatures;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                class GeneratedSpecificationRegistrationTest {
                    @Autowired
                    @Qualifier("generatedToolSpecifications")
                    private List<McpServerFeatures.SyncToolSpecification> specifications;

                    @Test
                    void registersExactlyOneGeneratedSpecification() {
                        assertEquals(1, specifications.size());
                        assertEquals("kma_weather_get_forecast", specifications.get(0).tool().name());
                    }
                }
                """;
    }

    private void addTelemetryTestDependency(Map<String, byte[]> files) {
        String build = new String(files.get("build.gradle.kts"), UTF_8);
        String anchor = "    testImplementation(\"org.springframework.boot:spring-boot-starter-test\")";
        assertTrue(build.contains(anchor), build);
        files.put("build.gradle.kts", build.replace(
                anchor,
                anchor + "\n    testImplementation(\"io.opentelemetry:opentelemetry-sdk-testing\")")
                .getBytes(UTF_8));
    }

    private String observabilityContextTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                import static org.junit.jupiter.api.Assertions.assertNull;

                import io.micrometer.core.instrument.MeterRegistry;
                import io.micrometer.observation.ObservationRegistry;
                import io.micrometer.tracing.Tracer;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.context.ApplicationContext;
                import org.springframework.core.env.Environment;

                @SpringBootTest
                class GeneratedObservabilityContextTest {
                    @Autowired ObservationRegistry observationRegistry;
                    @Autowired MeterRegistry meterRegistry;
                    @Autowired Tracer tracer;
                    @Autowired ApplicationContext applicationContext;
                    @Autowired WebEndpointsSupplier webEndpointsSupplier;
                    @Autowired Environment environment;

                    @Test
                    void providesLocalTelemetryWithoutDefaultExportersOrExtraEndpoints() throws Exception {
                        assertNotNull(observationRegistry);
                        assertNotNull(meterRegistry);
                        assertNotNull(tracer);
                        assertNull(environment.getProperty("management.metrics.tags.target.profile"));
                        assertEquals("false", environment.getProperty("management.otlp.metrics.export.enabled"));
                        assertEquals("false", environment.getProperty("management.otlp.tracing.export.enabled"));
                        assertEquals(List.of("health"), webEndpointsSupplier.getEndpoints().stream()
                                .map(endpoint -> endpoint.getEndpointId().toString())
                                .sorted()
                                .toList());
                        assertEquals(0, applicationContext.getBeanNamesForType(Class.forName(
                                "io.micrometer.registry.otlp.OtlpMeterRegistry")).length);
                        assertEquals(0, applicationContext.getBeanNamesForType(Class.forName(
                                "io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter")).length);
                    }
                }
                """;
    }

    private String observabilityRuntimeTest(String profileId) {
        return """
                package com.example.weather.application;

                import static java.nio.charset.StandardCharsets.UTF_8;
                import static java.util.concurrent.TimeUnit.SECONDS;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.json.JsonMapper;
                import com.sun.net.httpserver.HttpServer;
                import io.micrometer.core.instrument.MeterRegistry;
                import io.opentelemetry.api.common.AttributeKey;
                import io.opentelemetry.api.trace.SpanKind;
                import io.opentelemetry.api.trace.StatusCode;
                import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
                import io.opentelemetry.sdk.trace.SdkTracerProvider;
                import io.opentelemetry.sdk.trace.data.SpanData;
                import java.io.IOException;
                import java.net.InetSocketAddress;
                import java.net.URI;
                import java.net.http.HttpClient;
                import java.net.http.HttpRequest;
                import java.net.http.HttpResponse;
                import java.time.Duration;
                import java.util.List;
                import java.util.Set;
                import java.util.TreeSet;
                import java.util.concurrent.atomic.AtomicInteger;
                import java.util.concurrent.atomic.AtomicReference;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.boot.test.context.TestConfiguration;
                import org.springframework.boot.test.web.server.LocalServerPort;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Import;
                import org.springframework.test.context.DynamicPropertyRegistry;
                import org.springframework.test.context.DynamicPropertySource;

                @SpringBootTest(
                        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                        properties = {
                                "management.endpoints.web.exposure.include=health,prometheus",
                                "management.prometheus.metrics.export.enabled=true",
                                "management.tracing.sampling.probability=1.0",
                                "provider.response-max-bytes=1024",
                                "provider.connect-timeout-millis=1000",
                                "provider.read-timeout-millis=1000",
                                "provider.total-timeout-millis=1000",
                                "provider.secrets.service-key=configured-secret-marker"
                        })
                @Import(GeneratedObservabilityRuntimeTest.TraceConfiguration.class)
                class GeneratedObservabilityRuntimeTest {
                    private static final String PROFILE = "%s";
                    private static final String PRIVATE_BODY = "raw-private-body-marker";
                    private static final AtomicInteger PROVIDER_CALLS = new AtomicInteger();
                    private static final AtomicReference<List<String>> TRACEPARENT = new AtomicReference<>();
                    private static HttpServer provider;

                    @LocalServerPort int port;
                    @Autowired MeterRegistry meterRegistry;
                    @Autowired InMemorySpanExporter spanExporter;
                    @Autowired SdkTracerProvider tracerProvider;

                    private final HttpClient client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(2))
                            .build();
                    private final JsonMapper jsonMapper = JsonMapper.builder().build();

                    @DynamicPropertySource
                    static void provider(DynamicPropertyRegistry registry) {
                        try {
                            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                            provider.createContext("/forecast", exchange -> {
                                PROVIDER_CALLS.incrementAndGet();
                                TRACEPARENT.set(List.copyOf(exchange.getRequestHeaders()
                                        .getOrDefault("traceparent", List.of())));
                                byte[] body = ("{\\\"detail\\\":\\\"" + PRIVATE_BODY + "\\\"}").getBytes(UTF_8);
                                exchange.getResponseHeaders().set("Content-Type", "application/json");
                                exchange.sendResponseHeaders(500, body.length);
                                try (var output = exchange.getResponseBody()) {
                                    output.write(body);
                                }
                            });
                            provider.start();
                        } catch (IOException failure) {
                            throw new IllegalStateException("Test provider failed to start", failure);
                        }
                        registry.add("provider.base-url",
                                () -> "http://127.0.0.1:" + provider.getAddress().getPort());
                    }

                    @AfterAll
                    static void stopProvider() {
                        if (provider != null) {
                            provider.stop(0);
                        }
                    }

                    @Test
                    void exportsCanonicalSafeMetricsAndSpansForOneLiveMcpCall() throws Exception {
                        URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
                        HttpResponse<String> initialize = post(endpoint,
                                "{'jsonrpc':'2.0','id':1,'method':'initialize','params':"
                                        + "{'protocolVersion':'2025-03-26','capabilities':{},"
                                        + "'clientInfo':{'name':'telemetry-test','version':'1.0'}}}", null);
                        String sessionId = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
                        post(endpoint, "{'jsonrpc':'2.0','method':'notifications/initialized'}", sessionId);
                        JsonNode call = response(post(endpoint,
                                "{'jsonrpc':'2.0','id':2,'method':'tools/call','params':"
                                        + "{'name':'kma_weather_get_forecast','arguments':{'nx':60,'ny':127}}}",
                                sessionId));

                        JsonNode result = call.path("result");
                        assertTrue(result.path("isError").booleanValue(), call.toString());
                        JsonNode envelope = jsonMapper.readTree(result.path("content").get(0).path("text").textValue());
                        assertEquals("UPSTREAM_SERVER", envelope.at("/error/category").textValue());
                        assertEquals(500, envelope.at("/error/httpStatus").intValue());
                        assertEquals("getForecast", envelope.at("/error/operationId").textValue());
                        String traceId = envelope.at("/error/traceId").textValue();
                        assertTrue(traceId.matches("[0-9a-f]{32}"), traceId);
                        assertFalse(call.toString().contains(PRIVATE_BODY), call.toString());
                        assertEquals(1, PROVIDER_CALLS.get());

                        var flush = tracerProvider.forceFlush();
                        flush.join(5, SECONDS);
                        assertTrue(flush.isSuccess());
                        List<SpanData> spans = spanExporter.getFinishedSpanItems().stream()
                                .filter(span -> span.getName().startsWith("gen2spring.runtime."))
                                .toList();
                        assertEquals(2, spans.size(), spans.toString());
                        SpanData tool = span(spans, "gen2spring.runtime.mcp.tool.call");
                        SpanData request = span(spans, "gen2spring.runtime.provider.request");
                        assertEquals(SpanKind.INTERNAL, tool.getKind());
                        assertEquals(SpanKind.INTERNAL, request.getKind());
                        assertEquals(StatusCode.ERROR, tool.getStatus().getStatusCode());
                        assertEquals(StatusCode.ERROR, request.getStatus().getStatusCode());
                        assertEquals(tool.getTraceId(), request.getTraceId());
                        assertEquals(tool.getSpanId(), request.getParentSpanId());
                        assertEquals(request.getTraceId(), traceId);
                        assertEquals(List.of("00-" + traceId + "-" + request.getSpanId() + "-01"),
                                TRACEPARENT.get());
                        assertExactAttributes(tool, Set.of(
                                "target.profile", "outcome", "error.category",
                                "gen2spring.tool.name", "gen2spring.operation.id"));
                        assertExactAttributes(request, Set.of(
                                "target.profile", "outcome", "error.category",
                                "http.status.class", "gen2spring.operation.id",
                                "http.request.method", "http.response.status_code"));
                        assertEquals(PROFILE, attribute(tool, "target.profile"));
                        assertEquals("expected_error", attribute(tool, "outcome"));
                        assertEquals("upstream_server", attribute(tool, "error.category"));
                        assertEquals("kma_weather_get_forecast", attribute(tool, "gen2spring.tool.name"));
                        assertEquals("getForecast", attribute(request, "gen2spring.operation.id"));
                        assertEquals("GET", attribute(request, "http.request.method"));
                        assertEquals("500", attribute(request, "http.response.status_code"));

                        Set<String> meterNames = new TreeSet<>();
                        meterRegistry.getMeters().stream()
                                .map(meter -> meter.getId().getName())
                                .filter(name -> name.startsWith("gen2spring.runtime."))
                                .forEach(meterNames::add);
                        assertEquals(Set.of(
                                "gen2spring.runtime.mcp.tool.call",
                                "gen2spring.runtime.provider.request",
                                "gen2spring.runtime.provider.response.bytes",
                                "gen2spring.runtime.provider.executor.active",
                                "gen2spring.runtime.provider.executor.queued"), meterNames);
                        String scrape = get(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"));
                        assertTrue(scrape.contains("gen2spring_runtime_mcp_tool_call_seconds_count"), scrape);
                        assertTrue(scrape.contains("gen2spring_runtime_provider_request_seconds_count"), scrape);
                        assertTrue(scrape.contains("gen2spring_runtime_provider_response_bytes_count"), scrape);
                        assertTrue(scrape.contains("gen2spring_runtime_provider_executor_active"), scrape);
                        assertTrue(scrape.contains("gen2spring_runtime_provider_executor_queued"), scrape);

                        String telemetry = spans + "\\n" + customMetricLines(scrape);
                        assertFalse(telemetry.contains("configured-secret-marker"), telemetry);
                        assertFalse(telemetry.contains(PRIVATE_BODY), telemetry);
                        assertFalse(telemetry.contains("api.example.test"), telemetry);
                        assertFalse(telemetry.contains("127.0.0.1"), telemetry);
                        assertFalse(telemetry.contains("serviceKey"), telemetry);
                        assertFalse(telemetry.contains("nx"), telemetry);
                        assertFalse(telemetry.contains("ny"), telemetry);
                        assertFalse(telemetry.contains("Exception"), telemetry);
                        assertFalse(telemetry.contains("\tat "), telemetry);
                    }

                    private SpanData span(List<SpanData> spans, String name) {
                        return spans.stream().filter(value -> name.equals(value.getName())).findFirst().orElseThrow();
                    }

                    private void assertExactAttributes(SpanData span, Set<String> expected) {
                        Set<String> keys = span.getAttributes().asMap().keySet().stream()
                                .map(AttributeKey::getKey)
                                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                        assertEquals(expected, keys, span.toString());
                    }

                    private String attribute(SpanData span, String name) {
                        for (var entry : span.getAttributes().asMap().entrySet()) {
                            if (name.equals(entry.getKey().getKey())) {
                                return String.valueOf(entry.getValue());
                            }
                        }
                        return null;
                    }

                    private String customMetricLines(String scrape) {
                        return scrape.lines()
                                .filter(line -> line.contains("gen2spring_runtime_"))
                                .collect(java.util.stream.Collectors.joining("\\n"));
                    }

                    private String get(URI endpoint) throws Exception {
                        HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint)
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                                HttpResponse.BodyHandlers.ofString(UTF_8));
                        assertEquals(200, response.statusCode(), response.body());
                        return response.body();
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
                                request.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
                        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                                response.statusCode() + " " + response.body());
                        return response;
                    }

                    private JsonNode response(HttpResponse<String> response) throws Exception {
                        String body = response.body();
                        String data = body.lines()
                                .filter(line -> line.startsWith("data:"))
                                .map(line -> line.substring("data:".length()).stripLeading())
                                .findFirst()
                                .orElse(body);
                        return jsonMapper.readTree(data);
                    }

                    @TestConfiguration(proxyBeanMethods = false)
                    static class TraceConfiguration {
                        @Bean
                        InMemorySpanExporter inMemorySpanExporter() {
                            return InMemorySpanExporter.create();
                        }
                    }
                }
                """.formatted(profileId);
    }
}
