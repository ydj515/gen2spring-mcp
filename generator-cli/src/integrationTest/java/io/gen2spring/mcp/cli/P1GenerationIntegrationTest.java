package io.gen2spring.mcp.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedTool;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedToolCall;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedUpstreamResponse;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.validation.LoopbackPortAllocator;
import io.gen2spring.mcp.validation.McpStreamableHttpClient;
import io.gen2spring.mcp.validation.MockUpstreamServer;
import io.gen2spring.mcp.validation.UpstreamCallExpectation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P1GenerationIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JAVA_17_HOME = "GEN2SPRING_JAVA_17_HOME";
    private static final String TOOL_NAME = "kma_weather_get_forecast";
    private static final String TOOL_DESCRIPTION = "Get the public weather forecast for a grid location.";
    private static final String REPRESENTATIVE_STATION_ID = "STN01";
    private static final String LIVE_QUERY_SECRET = "independent-query-secret";
    private static final String LIVE_HEADER_SECRET = "independent-header-secret";
    private static final ProfileCase JAVA_17 = new ProfileCase(
            "spring-ai-2.0-java17-mvc-streamable",
            17,
            "config/weather-generation-java17.yaml",
            "eclipse-temurin:17.0.19_10-jre-noble@sha256:"
                    + "543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8");
    private static final ProfileCase JAVA_21 = new ProfileCase(
            "spring-ai-2.0-java21-mvc-streamable",
            21,
            "config/weather-generation.yaml",
            "eclipse-temurin:21.0.11_10-jre-noble@sha256:"
                    + "373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64");
    private static final List<ProfileCase> PROFILE_CASES = List.of(JAVA_17, JAVA_21);
    private static final Set<String> REQUIRED_OUTPUTS = Set.of(
            ".dockerignore",
            ".gitignore",
            "Dockerfile",
            "GENERATION_MANIFEST.json",
            "README.md",
            "VALIDATION_REPORT.json",
            "build.gradle.kts",
            "gradle.properties",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradlew",
            "gradlew.bat",
            "openapi/source.yaml",
            "settings.gradle.kts",
            "src/main/java/com/example/weather/application/WeatherMcpApplication.java",
            "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java",
            "src/main/java/com/example/weather/generated/model/GetForecastInput.java",
            "src/main/java/com/example/weather/generated/model/GetForecastLocation.java",
            "src/main/java/com/example/weather/generated/model/GetForecastModeValue.java",
            "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java",
            "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java",
            "src/main/java/com/example/weather/runtime/NormalizedSuccess.java",
            "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java",
            "src/main/java/com/example/weather/runtime/OperationDefinition.java",
            "src/main/java/com/example/weather/runtime/OperationOutcome.java",
            "src/main/java/com/example/weather/runtime/ParameterBinding.java",
            "src/main/java/com/example/weather/runtime/ParameterLocation.java",
            "src/main/java/com/example/weather/runtime/ProviderError.java",
            "src/main/java/com/example/weather/runtime/ProviderErrorCategory.java",
            "src/main/java/com/example/weather/runtime/ProviderErrorException.java",
            "src/main/java/com/example/weather/runtime/ResponseNormalizationPolicy.java",
            "src/main/java/com/example/weather/runtime/ResponseNormalizer.java",
            "src/main/java/com/example/weather/runtime/SecretBinding.java",
            "src/main/resources/application.yml",
            "src/test/java/com/example/weather/application/GeneratedJavaRuntimeTest.java",
            "src/test/java/com/example/weather/application/WeatherMcpApplicationTest.java");

    @TempDir
    Path tempDir;

    @Test
    void installedCliValidatesTheJavaProfileMatrixDeterministically() throws Exception {
        Path specification = resource("openapi/weather-api.yaml");
        Path java17Home = requiredJava17Home();
        assertFixturesDifferOnlyByProfile();
        Map<String, GenerationResult> firstByProfile = new LinkedHashMap<>();

        for (ProfileCase profile : PROFILE_CASES) {
            Path configuration = resource(profile.configurationResource());
            String outputName = "weather-mcp-server-java" + profile.javaFeature();
            GenerationResult first = generate(specification, configuration, tempDir.resolve(outputName));
            assertReleaseContract(first, specification, profile);

            GenerationResult second = generate(
                    specification, configuration, tempDir.resolve(outputName + "-second"));
            assertReleaseContract(second, specification, profile);
            assertEquals(first.sourceChecksum(), second.sourceChecksum(), profile.id());
            assertEquals(first.manifest(), second.manifest(), profile.id());
            assertCanonicalArchiveEntriesEqual(first.archiveEntries(), second.archiveEntries());
            assertSensitiveValuesAbsent(first, REPRESENTATIVE_STATION_ID,
                    "mcp-validation-secret-1", "mcp-validation-secret-2", java17Home.toString());
            assertSensitiveValuesAbsent(second, REPRESENTATIVE_STATION_ID,
                    "mcp-validation-secret-1", "mcp-validation-secret-2", java17Home.toString());
            assertOperationalDetailsAbsent(first);
            assertOperationalDetailsAbsent(second);
            firstByProfile.put(profile.id(), first);
        }

        assertNotEquals(
                firstByProfile.get(JAVA_17.id()).sourceChecksum(),
                firstByProfile.get(JAVA_21.id()).sourceChecksum());
        for (ProfileCase profile : PROFILE_CASES) {
            assertIndependentLiveMcpContract(firstByProfile.get(profile.id()), profile, java17Home);
        }
    }

    @Test
    void installedCliRejectsInvalidRepresentativeArgumentWithoutPublishingOrLeakingIt() throws Exception {
        Path specification = resource("openapi/weather-api.yaml");
        String configuredValue = "configured-invalid-representative-value";
        Path configuration = Files.writeString(tempDir.resolve("invalid-weather-generation.yaml"),
                Files.readString(resource("config/weather-generation.yaml"), UTF_8)
                        .replace("days: 3", "days: " + configuredValue), UTF_8);
        Path output = tempDir.resolve("invalid-weather-mcp-server");

        InstalledCliResult result = runInstalledCli(specification, configuration, output);

        assertEquals(3, result.exitCode(), result.stderr() + result.stdout());
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(output.resolveSibling(output.getFileName() + ".zip")));
        assertFalse(result.stdout().contains(configuredValue));
        assertFalse(result.stderr().contains(configuredValue));
    }

    private GenerationResult generate(Path specification, Path configuration, Path output) throws Exception {
        InstalledCliResult result = runInstalledCli(specification, configuration, output);

        String validationReport = Files.exists(output.resolve("VALIDATION_REPORT.json"))
                ? Files.readString(output.resolve("VALIDATION_REPORT.json"), UTF_8)
                : "";
        assertEquals(0, result.exitCode(), result.stderr() + result.stdout() + validationReport);
        assertEquals("", result.stderr());
        JsonNode response = JSON.readTree(result.stdout());
        assertNotNull(response);
        assertEquals("VALIDATED", response.path("status").asText());
        assertEquals(output.toAbsolutePath().normalize().toString(), response.path("project").asText());
        assertEquals(output.resolve("VALIDATION_REPORT.json").toAbsolutePath().normalize().toString(),
                response.path("report").asText());
        Path archive = output.resolveSibling(output.getFileName() + ".zip");
        assertEquals(archive.toAbsolutePath().normalize().toString(), response.path("archive").asText());
        return new GenerationResult(
                output,
                archive,
                response.path("sourceChecksum").asText(),
                readJson(output.resolve("GENERATION_MANIFEST.json")),
                readJson(output.resolve("VALIDATION_REPORT.json")),
                readArchive(archive),
                result.stdout(),
                result.stderr());
    }

    private InstalledCliResult runInstalledCli(Path specification, Path configuration, Path output) throws Exception {
        Path executable = Path.of(System.getProperty("openapiMcp.executable"));
        assertTrue(Files.isExecutable(executable));
        ProcessBuilder processBuilder = new ProcessBuilder(
                executable.toString(),
                "generate",
                "--spec", specification.toString(),
                "--config", configuration.toString(),
                "--output", output.toString());
        String java17Home = System.getenv(JAVA_17_HOME);
        if (java17Home != null && !java17Home.isBlank()) {
            processBuilder.environment().put(JAVA_17_HOME, java17Home);
        }
        Process process = processBuilder.start();
        boolean finished = process.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
            throw new AssertionError("installed CLI timed out");
        }
        return new InstalledCliResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), UTF_8),
                new String(process.getErrorStream().readAllBytes(), UTF_8));
    }

    private void assertReleaseContract(
            GenerationResult result,
            Path specification,
            ProfileCase profile) throws Exception {
        assertTrue(Files.isDirectory(result.projectRoot()));
        assertTrue(Files.isRegularFile(result.archive()));
        assertTrue(Files.isExecutable(result.projectRoot().resolve("gradlew")));
        if (Files.getFileStore(result.projectRoot()).supportsFileAttributeView("posix")) {
            assertTrue(Files.getPosixFilePermissions(result.projectRoot().resolve("gradlew"))
                    .contains(PosixFilePermission.OWNER_EXECUTE));
        }
        assertTransientBuildOutputsAbsent(result.projectRoot());

        Set<String> outputPaths = regularFiles(result.projectRoot());
        assertEquals(REQUIRED_OUTPUTS, outputPaths);
        assertEquals(outputPaths, result.archiveEntries().keySet());
        assertEquals(new ArrayList<>(new TreeSet<>(result.archiveEntries().keySet())),
                new ArrayList<>(result.archiveEntries().keySet()));
        assertEquals(0755, centralDirectoryMode(Files.readAllBytes(result.archive()), "gradlew"));
        assertArrayEquals(Files.readAllBytes(specification),
                Files.readAllBytes(result.projectRoot().resolve("openapi/source.yaml")));

        JsonNode manifest = result.manifest();
        assertEquals("0.1.0", manifest.path("generatorVersion").asText());
        assertEquals("spring-ai-2-v2", manifest.path("templateVersion").asText());
        assertEquals("0.2.0", manifest.path("runtimeVersion").asText());
        assertEquals(profile.id(), manifest.path("targetProfileId").asText());
        assertEquals("4.1.0", manifest.path("springBootVersion").asText());
        assertEquals("2.0.0", manifest.path("springAiVersion").asText());
        assertEquals(profile.javaFeature(), manifest.path("javaVersion").asInt());
        assertEquals("9.6.1", manifest.path("gradleVersion").asText());
        assertEquals(profile.containerImage(), manifest.path("containerImage").asText());
        assertEquals(sha256(Files.readAllBytes(specification)),
                manifest.path("originalSpecificationChecksum").asText());
        assertEquals(result.sourceChecksum(), manifest.path("sourceChecksum").asText());
        assertEquals(independentSourceChecksum(result.projectRoot()), result.sourceChecksum());
        assertEquals(List.of("getForecast"), manifest.path("operationMappings").findValuesAsText("operationId"));
        assertEquals(List.of(TOOL_NAME), manifest.path("operationMappings").findValuesAsText("toolName"));
        JsonNode normalization = manifest.path("operationMappings").get(0).path("responseNormalization");
        assertEquals(List.of("dataPath", "successCodePath", "successValues", "errorMessagePath", "totalCountPath"),
                iterable(normalization.fieldNames()));
        assertEquals("/response/body/items/item", normalization.path("dataPath").textValue());
        assertEquals("/response/header/resultCode", normalization.path("successCodePath").textValue());
        assertEquals(List.of("00"), JSON.convertValue(normalization.path("successValues"), List.class));
        assertEquals("/response/header/resultMsg", normalization.path("errorMessagePath").textValue());
        assertEquals("/response/body/totalCount", normalization.path("totalCountPath").textValue());

        JsonNode report = result.report();
        assertEquals("VALIDATED", report.path("status").asText());
        assertEquals(List.of(
                "COMPILE",
                "APPLICATION_CONTEXT",
                "MCP_INITIALIZE",
                "MCP_TOOLS_LIST",
                "MCP_TOOL_CALL"), stageNames(report));
        assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"),
                report.path("stages").findValuesAsText("status"));
        assertEquals("SUCCESS", stage(report, "MCP_TOOL_CALL").path("status").textValue());
        assertEquals("MCP initialize contract matched", stage(report, "MCP_INITIALIZE").path("summary").textValue());
        assertEquals("MCP Tool metadata matched the generated contract",
                stage(report, "MCP_TOOLS_LIST").path("summary").textValue());
        assertEquals("Representative MCP Tool call matched the mock upstream contract",
                stage(report, "MCP_TOOL_CALL").path("summary").textValue());
        assertEquals(List.of(TOOL_NAME), report.path("tools").findValuesAsText("name"));
        assertEquals(List.of(TOOL_DESCRIPTION), report.path("tools").findValuesAsText("description"));
        assertTrue(report.path("tools").get(0).path("inputSchemaPresent").asBoolean());

        String generatedReadme = Files.readString(result.projectRoot().resolve("README.md"), UTF_8);
        assertTrue(generatedReadme.contains("Requirements: Java " + profile.javaFeature() + "."));
        assertTrue(generatedReadme.contains("- Compatibility profile: `" + profile.id() + "`"));
        assertTrue(generatedReadme.contains("- Template: `spring-ai-2-v2`"));
        assertTrue(generatedReadme.contains("- Runtime version: `0.2.0`"));
        assertTrue(generatedReadme.contains("- Gradle 9.6.1"));
        assertTrue(generatedReadme.contains("- Container image: `" + profile.containerImage() + "`"));
        assertTrue(generatedReadme.contains("- Java " + profile.javaFeature()));
        assertTrue(generatedReadme.contains("## Response handling"));
        assertTrue(generatedReadme.contains("`getForecast`"));
        assertTrue(generatedReadme.contains("`successValues`: `[\"00\"]`"));
        assertFalse(generatedReadme.contains("Known P0 limits"));
        assertFalse(generatedReadme.contains("\"response\": {"));

        String build = Files.readString(result.projectRoot().resolve("build.gradle.kts"), UTF_8);
        assertTrue(build.contains("languageVersion = JavaLanguageVersion.of(" + profile.javaFeature() + ")"));
        assertEquals("""
                FROM %s
                WORKDIR /app
                COPY build/libs/weather-mcp-server.jar /app/app.jar
                USER 10001:10001
                ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                """.formatted(profile.containerImage()),
                Files.readString(result.projectRoot().resolve("Dockerfile"), UTF_8));
        assertEquals("""
                **
                !Dockerfile
                !build/
                !build/libs/
                !build/libs/weather-mcp-server.jar
                """, Files.readString(result.projectRoot().resolve(".dockerignore"), UTF_8));
        String runtimeTest = Files.readString(result.projectRoot().resolve(
                "src/test/java/com/example/weather/application/GeneratedJavaRuntimeTest.java"), UTF_8);
        assertTrue(runtimeTest.contains(
                "assertEquals(" + profile.javaFeature() + ", Runtime.version().feature());"));

        String applicationYaml = Files.readString(
                result.projectRoot().resolve("src/main/resources/application.yml"), UTF_8);
        assertTrue(applicationYaml.contains("${KMA_SERVICE_KEY:}"));
        assertTrue(applicationYaml.contains("${WEATHER_HEADER_KEY:}"));
        assertFalse(applicationYaml.contains("secretValue"));
        result.archiveEntries().forEach((path, bytes) -> {
            assertFalse(path.equals("build") || path.startsWith("build/"));
            assertFalse(path.equals(".gradle") || path.startsWith(".gradle/"));
            assertFalse(path.equals("process-logs") || path.startsWith("process-logs/"));
        });
    }

    private List<String> stageNames(JsonNode report) {
        return report.path("stages").findValuesAsText("stage");
    }

    private List<String> iterable(java.util.Iterator<String> values) {
        List<String> result = new ArrayList<>();
        values.forEachRemaining(result::add);
        return result;
    }

    private JsonNode stage(JsonNode report, String stageName) {
        for (JsonNode stage : report.path("stages")) {
            if (stageName.equals(stage.path("stage").textValue())) {
                return stage;
            }
        }
        throw new AssertionError("Missing validation stage: " + stageName);
    }

    private void assertSensitiveValuesAbsent(GenerationResult result, String... sensitiveValues) throws IOException {
        for (String sensitiveValue : sensitiveValues) {
            assertFalse(result.stdout().contains(sensitiveValue), sensitiveValue);
            assertFalse(result.stderr().contains(sensitiveValue), sensitiveValue);
            assertFalse(result.manifest().toString().contains(sensitiveValue), sensitiveValue);
            assertFalse(result.report().toString().contains(sensitiveValue), sensitiveValue);
            try (var paths = Files.walk(result.projectRoot())) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    assertFalse(new String(Files.readAllBytes(path), UTF_8).contains(sensitiveValue),
                            result.projectRoot().relativize(path) + ": " + sensitiveValue);
                }
            }
            result.archiveEntries().forEach((path, bytes) ->
                    assertFalse((path + new String(bytes, UTF_8)).contains(sensitiveValue),
                            path + ": " + sensitiveValue));
        }
    }

    private void assertOperationalDetailsAbsent(GenerationResult result) throws IOException {
        String userName = System.getProperty("user.name", "");
        assertArtifactValuesAbsent(result, userName);
        assertSensitiveValuesAbsent(result,
                System.getProperty("java.home", ""),
                "BUILD SUCCESSFUL",
                "> Task :",
                "java.specification.version =",
                "Tomcat started on port",
                "-Dorg.gradle.java.installations.paths=",
                "classes test bootJar",
                "--no-daemon");
    }

    private void assertIndependentLiveMcpContract(
            GenerationResult result,
            ProfileCase profile,
            Path java17Home) throws Exception {
        Path targetJavaHome = profile.javaFeature() == 17
                ? java17Home
                : Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        Path targetJava = targetJavaHome.resolve("bin/java");
        assertTrue(Files.isRegularFile(targetJava), "target Java executable is unavailable");
        buildBootJar(result.projectRoot(), targetJavaHome);

        Object rawProviderResponse = rawProviderResponse();
        Object normalizedResult = normalizedResult();
        UpstreamCallExpectation expectation = new UpstreamCallExpectation(
                "getForecast",
                "POST",
                "/stations/STN01/forecast",
                Map.of(
                        "days", List.of("3"),
                        "serviceKey", List.of(LIVE_QUERY_SECRET)),
                Map.of("x-weather-key", List.of(LIVE_HEADER_SECRET)),
                Map.of("location", Map.of(
                        "latitude", new BigDecimal("37.5"),
                        "longitude", new BigDecimal("127.0"))),
                Map.of(
                        "KMA_SERVICE_KEY", LIVE_QUERY_SECRET,
                        "WEATHER_HEADER_KEY", LIVE_HEADER_SECRET),
                200,
                "application/json",
                rawProviderResponse);
        LoopbackPortAllocator ports = new LoopbackPortAllocator();
        Process application = null;
        try (MockUpstreamServer mock = MockUpstreamServer.start(expectation);
                LoopbackPortAllocator.Reservation reservation = ports.reserve()) {
            int applicationPort = reservation.port();
            reservation.releaseForLaunch();
            ProcessBuilder launch = new ProcessBuilder(
                    targetJava.toString(),
                    "-jar",
                    result.projectRoot().resolve("build/libs/weather-mcp-server.jar").toString(),
                    "--server.address=127.0.0.1",
                    "--server.port=" + applicationPort);
            launch.environment().put("PROVIDER_BASE_URL", mock.baseUri().toString());
            launch.environment().putAll(expectation.environmentOverrides());
            launch.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            launch.redirectError(ProcessBuilder.Redirect.DISCARD);
            application = launch.start();
            awaitApplication(application, applicationPort);

            ExpectedToolCall expectedCall = new ExpectedToolCall(
                    liveTool(),
                    liveArguments(),
                    new ExpectedUpstreamResponse(200, "application/json", rawProviderResponse),
                    normalizedResult);
            var validation = new McpStreamableHttpClient().validate(
                    ports.mcpUri(applicationPort),
                    Map.of(TOOL_NAME, new ExpectedTool(TOOL_DESCRIPTION, expectedInputSchema())),
                    expectedCall);

            assertEquals(Set.of(TOOL_NAME), validation.toolNames());
            assertEquals(List.of(TOOL_DESCRIPTION),
                    validation.tools().stream().map(tool -> tool.description()).toList());
            mock.sealAndAwaitVerified(Duration.ofSeconds(10));
        } finally {
            stopApplication(application);
        }
    }

    private void buildBootJar(Path projectRoot, Path targetJavaHome) throws Exception {
        Path gradle = projectRoot.resolve("gradlew");
        assertTrue(Files.isExecutable(gradle), "generated Gradle wrapper is unavailable");
        Process build = new ProcessBuilder(
                gradle.toString(),
                "-Dorg.gradle.java.installations.auto-detect=false",
                "-Dorg.gradle.java.installations.auto-download=false",
                "-Dorg.gradle.java.installations.paths=" + targetJavaHome,
                "bootJar",
                "--no-daemon",
                "--non-interactive")
                .directory(projectRoot.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!build.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS)) {
            build.descendants().forEach(ProcessHandle::destroyForcibly);
            build.destroyForcibly();
            if (!build.waitFor(10, TimeUnit.SECONDS)) {
                throw new AssertionError("generated project build cleanup failed safely");
            }
            throw new AssertionError("generated project build timed out safely");
        }
        assertEquals(0, build.exitValue(), "generated project build failed safely");
        assertTrue(Files.isRegularFile(projectRoot.resolve("build/libs/weather-mcp-server.jar")),
                "generated boot JAR is unavailable");
    }

    private void awaitApplication(Process application, int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (!application.isAlive()) {
                throw new AssertionError("generated application exited before readiness");
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                return;
            } catch (IOException unavailable) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("generated application readiness timed out safely");
    }

    private void stopApplication(Process application) throws InterruptedException {
        if (application == null) {
            return;
        }
        application.destroy();
        if (!application.waitFor(10, TimeUnit.SECONDS)) {
            application.destroyForcibly();
            if (!application.waitFor(10, TimeUnit.SECONDS)) {
                throw new AssertionError("generated application cleanup failed safely");
            }
        }
    }

    private Map<String, Object> expectedInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("clientVersion", Map.of(
                "type", "string",
                "description", "Calling client version."));
        properties.put("days", Map.of(
                "type", "integer",
                "format", "int32",
                "minimum", BigDecimal.ONE,
                "maximum", BigDecimal.valueOf(7),
                "description", "Number of forecast days."));
        properties.put("includeAlerts", Map.of(
                "type", "boolean",
                "description", "includeAlerts"));
        properties.put("location", Map.of(
                "type", "object",
                "properties", Map.of(
                        "label", Map.of("type", "string", "description", "label"),
                        "latitude", Map.of(
                                "type", "number",
                                "minimum", BigDecimal.valueOf(-90),
                                "maximum", BigDecimal.valueOf(90),
                                "description", "latitude"),
                        "longitude", Map.of(
                                "type", "number",
                                "minimum", BigDecimal.valueOf(-180),
                                "maximum", BigDecimal.valueOf(180),
                                "description", "longitude")),
                "required", List.of("latitude", "longitude"),
                "description", "location"));
        properties.put("mode", Map.of(
                "type", "string",
                "enum", List.of("brief", "full-detail"),
                "description", "Forecast detail mode."));
        properties.put("note", Map.of(
                "type", "string",
                "minLength", 1,
                "maxLength", 80,
                "description", "note"));
        properties.put("stationId", Map.of(
                "type", "string",
                "minLength", 2,
                "maxLength", 12,
                "pattern", "^[A-Z0-9]+$",
                "description", "Station identifier."));
        properties.put("tags", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Optional forecast tags."));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("days", "location", "stationId"));
    }

    private McpToolDefinition liveTool() {
        return new McpToolDefinition(
                "getForecast",
                TOOL_NAME,
                TOOL_DESCRIPTION,
                List.of(),
                null,
                List.of(),
                McpToolDefinition.OutputKind.GENERIC_JSON);
    }

    private Map<String, Object> liveArguments() {
        return Map.of(
                "stationId", REPRESENTATIVE_STATION_ID,
                "days", 3,
                "location", Map.of(
                        "latitude", new BigDecimal("37.5"),
                        "longitude", new BigDecimal("127.0")));
    }

    private Object rawProviderResponse() {
        return Map.of(
                "response", Map.of(
                        "header", Map.of(
                                "resultCode", "00",
                                "resultMsg", "NORMAL_SERVICE",
                                "rawHeader", true),
                        "body", Map.of(
                                "items", Map.of("item", List.of(Map.of("forecast", "sunny"))),
                                "totalCount", 1,
                                "rawBody", true)),
                "rawRoot", true);
    }

    private Object normalizedResult() {
        return Map.of(
                "data", List.of(Map.of("forecast", "sunny")),
                "page", Map.of("totalCount", 1),
                "provider", Map.of("code", "00", "message", "NORMAL_SERVICE"));
    }

    private void assertArtifactValuesAbsent(GenerationResult result, String... sensitiveValues) throws IOException {
        for (String sensitiveValue : sensitiveValues) {
            if (sensitiveValue == null || sensitiveValue.isBlank()) {
                continue;
            }
            assertFalse(result.manifest().toString().contains(sensitiveValue), sensitiveValue);
            assertFalse(result.report().toString().contains(sensitiveValue), sensitiveValue);
            try (var paths = Files.walk(result.projectRoot())) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    assertFalse(new String(Files.readAllBytes(path), UTF_8).contains(sensitiveValue),
                            result.projectRoot().relativize(path) + ": " + sensitiveValue);
                }
            }
            result.archiveEntries().forEach((path, bytes) ->
                    assertFalse((path + new String(bytes, UTF_8)).contains(sensitiveValue),
                            path + ": " + sensitiveValue));
        }
    }

    private void assertFixturesDifferOnlyByProfile() throws Exception {
        String java21 = Files.readString(resource(JAVA_21.configurationResource()), UTF_8);
        String java17 = Files.readString(resource(JAVA_17.configurationResource()), UTF_8);
        assertEquals(java21.replace(JAVA_21.id(), JAVA_17.id()), java17);
    }

    private Path requiredJava17Home() {
        String configured = System.getenv(JAVA_17_HOME);
        assertNotNull(configured, JAVA_17_HOME + " must be forwarded to the integration test");
        assertFalse(configured.isBlank(), JAVA_17_HOME + " must not be blank");
        Path home = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(home.resolve("bin/java")), JAVA_17_HOME);
        return home;
    }

    private void assertCanonicalArchiveEntriesEqual(
            Map<String, byte[]> first,
            Map<String, byte[]> second) {
        assertEquals(first.keySet(), second.keySet());
        first.forEach((path, bytes) -> {
            if (!"VALIDATION_REPORT.json".equals(path)) {
                assertArrayEquals(bytes, second.get(path), path);
            }
        });
    }

    private void assertTransientBuildOutputsAbsent(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                assertFalse(relative.equals("build") || relative.startsWith("build/"), relative);
                assertFalse(relative.equals(".gradle") || relative.startsWith(".gradle/"), relative);
                assertFalse(relative.equals("process-logs") || relative.startsWith("process-logs/"), relative);
                assertFalse(relative.startsWith(".gradlew-validated-"), relative);
            }
        }
    }

    private Set<String> regularFiles(Path root) throws IOException {
        Set<String> files = new TreeSet<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> files.add(root.relativize(path).toString().replace('\\', '/')));
        }
        return files;
    }

    private Map<String, byte[]> readArchive(Path archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(archive)))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertFalse(entry.isDirectory());
                assertFalse(entry.getName().startsWith("/") || entry.getName().contains("../"));
                assertEquals(LocalDateTime.of(1980, 1, 1, 0, 0), entry.getTimeLocal());
                assertEquals(null, entries.put(entry.getName(), zip.readAllBytes()), entry.getName());
            }
        }
        return entries;
    }

    private String independentSourceChecksum(Path root) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.equals("GENERATION_MANIFEST.json")
                        || relative.equals("VALIDATION_REPORT.json")
                        || relative.equals("process-logs")
                        || relative.startsWith("process-logs/")) {
                    continue;
                }
                byte[] name = relative.getBytes(UTF_8);
                byte[] content = normalizeLineEndings(Files.readAllBytes(path));
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(name.length).array());
                digest.update(name);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
                digest.update(content);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private byte[] normalizeLineEndings(byte[] input) {
        byte[] normalized = new byte[input.length];
        int write = 0;
        for (int read = 0; read < input.length; read++) {
            if (input[read] == '\r') {
                if (read + 1 < input.length && input[read + 1] == '\n') {
                    read++;
                }
                normalized[write++] = '\n';
            } else {
                normalized[write++] = input[read];
            }
        }
        return Arrays.copyOf(normalized, write);
    }

    private int centralDirectoryMode(byte[] archive, String expectedName) {
        int end = findSignatureFromEnd(archive, 0x06054b50);
        int count = unsignedShort(archive, end + 10);
        int offset = littleEndianInt(archive, end + 16);
        for (int index = 0; index < count; index++) {
            assertEquals(0x02014b50, littleEndianInt(archive, offset));
            int nameLength = unsignedShort(archive, offset + 28);
            int extraLength = unsignedShort(archive, offset + 30);
            int commentLength = unsignedShort(archive, offset + 32);
            String name = new String(archive, offset + 46, nameLength, UTF_8);
            if (name.equals(expectedName)) {
                return littleEndianInt(archive, offset + 38) >>> 16 & 0777;
            }
            offset += 46 + nameLength + extraLength + commentLength;
        }
        throw new AssertionError("Missing central directory entry: " + expectedName);
    }

    private int findSignatureFromEnd(byte[] bytes, int signature) {
        for (int index = bytes.length - 4; index >= 0; index--) {
            if (littleEndianInt(bytes, index) == signature) {
                return index;
            }
        }
        throw new AssertionError("ZIP signature is missing");
    }

    private int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private int littleEndianInt(byte[] bytes, int offset) {
        return unsignedShort(bytes, offset) | unsignedShort(bytes, offset + 2) << 16;
    }

    private String sha256(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private Path resource(String name) throws URISyntaxException {
        var resource = getClass().getClassLoader().getResource(name);
        assertNotNull(resource, name);
        return Path.of(resource.toURI());
    }

    private JsonNode readJson(Path path) throws IOException {
        return JSON.readTree(Files.readAllBytes(path));
    }

    private record GenerationResult(
            Path projectRoot,
            Path archive,
            String sourceChecksum,
            JsonNode manifest,
            JsonNode report,
            Map<String, byte[]> archiveEntries,
            String stdout,
            String stderr) {}

    private record InstalledCliResult(int exitCode, String stdout, String stderr) {}

    private record ProfileCase(
            String id,
            int javaFeature,
            String configurationResource,
            String containerImage) {}
}
