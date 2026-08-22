package io.gen2spring.mcp.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledCliTest {
    private static final String JAVA_17_IMAGE = "eclipse-temurin:17.0.19_10-jre-noble@sha256:"
            + "543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8";
    private static final String JAVA_21_IMAGE = "eclipse-temurin:21.0.11_10-jre-noble@sha256:"
            + "373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64";

    @TempDir
    Path tempDir;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void installedProfilesAndInspectKeepSuccessfulStderrEmpty() throws Exception {
        Path executable = Path.of(System.getProperty("openapiMcp.executable"));
        assertTrue(Files.isRegularFile(executable));

        Result profiles = run(executable, "profiles");
        Result repeatedProfiles = run(executable, "profiles");
        assertEquals(0, profiles.exitCode(), profiles.stderr());
        assertEquals(0, repeatedProfiles.exitCode(), repeatedProfiles.stderr());
        assertEquals("", profiles.stderr());
        assertEquals(profiles.stdout(), repeatedProfiles.stdout());
        assertEquals("", repeatedProfiles.stderr());
        var installedProfiles = JSON.readTree(profiles.stdout()).path("profiles");
        assertEquals(4, installedProfiles.size());
        assertInstalledProfile(installedProfiles.get(0),
                "spring-ai-1.1-java17-mvc-streamable", 17, "3.5.16", "1.1.8",
                "generator-spring-ai-1", "spring-ai-1-v2", JAVA_17_IMAGE);
        assertInstalledProfile(installedProfiles.get(1),
                "spring-ai-1.1-java21-mvc-streamable", 21, "3.5.16", "1.1.8",
                "generator-spring-ai-1", "spring-ai-1-v2", JAVA_21_IMAGE);
        assertInstalledProfile(installedProfiles.get(2),
                "spring-ai-2.0-java17-mvc-streamable", 17, "4.1.0", "2.0.0",
                "generator-spring-ai-2", "spring-ai-2-v3", JAVA_17_IMAGE);
        assertInstalledProfile(installedProfiles.get(3),
                "spring-ai-2.0-java21-mvc-streamable", 21, "4.1.0", "2.0.0",
                "generator-spring-ai-2", "spring-ai-2-v3", JAVA_21_IMAGE);

        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("weather.yaml"), """
                openapi: 3.0.3
                info:
                  title: Weather
                  version: 1.0.0
                paths:
                  /forecast:
                    get:
                      operationId: getForecast
                      responses:
                        '200':
                          description: Success
                """);
        Path output = safeTemp.resolve("analysis.json");
        Result inspect = run(executable, "inspect", "--spec", specification.toString(),
                "--output", output.toString());

        assertEquals(0, inspect.exitCode(), inspect.stderr());
        assertEquals("", inspect.stderr());
        assertEquals(output.toString(), JSON.readTree(inspect.stdout()).path("analysis").asText());
        assertTrue(Files.isRegularFile(output));
        assertFalse(Files.readString(output).isBlank());
    }

    @Test
    void installedInspectKeepsTheSuppliedOpenApiVersionsDecisionEquivalent() throws Exception {
        Path executable = Path.of(System.getProperty("openapiMcp.executable"));
        Path safeTemp = tempDir.toRealPath();
        Path output30 = safeTemp.resolve("openapi-30-analysis.json");
        Path output31 = safeTemp.resolve("openapi-31-analysis.json");

        Result inspect30 = run(executable, "inspect", "--spec", repositoryRoot().resolve("swagger-3.0.yml").toString(),
                "--output", output30.toString());
        Result inspect31 = run(executable, "inspect", "--spec", repositoryRoot().resolve("swagger-3.1.yml").toString(),
                "--output", output31.toString());

        assertEquals(0, inspect30.exitCode(), inspect30.stderr());
        assertEquals(0, inspect31.exitCode(), inspect31.stderr());
        assertEquals("", inspect30.stderr());
        assertEquals("", inspect31.stderr());
        assertFalse(inspect30.stdout().contains("swagger-3.0.yml"));
        assertFalse(inspect31.stdout().contains("swagger-3.1.yml"));

        var analysis30 = JSON.readTree(Files.readString(output30));
        var analysis31 = JSON.readTree(Files.readString(output31));
        assertEquals("3.0.4", analysis30.path("openApiVersion").asText());
        assertEquals("3.1.2", analysis31.path("openApiVersion").asText());
        assertExactPairedFixtureCounts(analysis30);
        assertExactPairedFixtureCounts(analysis31);
        assertEquals(analysis30.path("operations"), analysis31.path("operations"));
        assertEquals(analysis30.path("securitySchemes"), analysis31.path("securitySchemes"));
        assertEquals(analysis30.path("warnings"), analysis31.path("warnings"));
    }

    @Test
    void rootReadmeDocumentsTheCompletedRuntimeContractsAndRemainingGatewayWork() throws Exception {
        String readme = Files.readString(repositoryRoot().resolve("README.md"));
        String userGuide = Files.readString(repositoryRoot().resolve("docs/user-guide.md"));
        String prd = Files.readString(repositoryRoot().resolve("docs/prd.md"));
        String architecture = Files.readString(
                repositoryRoot().resolve("docs/architecture/hosted-generation-platform.html"));

        assertTrue(readme.lines().count() <= 200, "Root README should remain a concise landing page");
        assertTrue(readme.contains("[사용자 가이드](docs/user-guide.md)"));
        assertTrue(readme.contains("## 5분 안에 시작하기"));
        assertTrue(readme.contains("## 문서"));

        assertTrue(userGuide.contains("spring-ai-1.1-java17-mvc-streamable"));
        assertTrue(userGuide.contains("spring-ai-1.1-java21-mvc-streamable"));
        assertTrue(userGuide.contains("spring-ai-2.0-java17-mvc-streamable"));
        assertTrue(userGuide.contains("spring-ai-2.0-java21-mvc-streamable"));
        assertTrue(userGuide.contains("GEN2SPRING_JAVA_17_HOME"));
        assertFalse(userGuide.contains("생성 프로젝트 검증은 선택한 JDK 하나만 사용"));
        assertTrue(userGuide.contains("Gradle Wrapper JVM은 host의 Java 21로 시작될 수 있다"));
        assertTrue(userGuide.contains("generated compile/test toolchain 탐색만 verified target JDK로 제한"));
        assertTrue(userGuide.contains("org.gradle.java.installations.auto-detect=false"));
        assertTrue(userGuide.contains("org.gradle.java.installations.auto-download=false"));
        assertTrue(userGuide.contains("org.gradle.java.installations.paths=<verified target home>"));
        assertTrue(userGuide.contains("Spring Boot 3.5.16"));
        assertTrue(userGuide.contains("Spring AI 1.1.8"));
        assertTrue(userGuide.contains("Spring Boot 4.1.0"));
        assertTrue(userGuide.contains("Spring AI 2.0.0"));
        assertTrue(userGuide.contains("Gradle 9.6.1"));
        assertTrue(userGuide.contains("Jackson 2"));
        assertTrue(userGuide.contains("`McpToolParam` annotation을 생성하지 않는다"));
        assertTrue(userGuide.contains("Streamable HTTP"));
        assertTrue(userGuide.contains("`/mcp`"));
        assertTrue(userGuide.contains(JAVA_17_IMAGE));
        assertTrue(userGuide.contains(JAVA_21_IMAGE));
        assertTrue(userGuide.contains("USER 10001:10001"));
        assertTrue(userGuide.contains(".dockerignore"));
        assertTrue(userGuide.contains("Java 21 기본 profile"));
        assertTrue(userGuide.contains("`profiles`"));
        assertTrue(userGuide.contains("항상 같은 JSON"));
        assertFalse(userGuide.contains("Spring AI 1.x\n  compatibility profile은 후속 P1 범위다"));
        assertTrue(userGuide.contains("gen2spring.runtime.mcp.tool.call"));
        assertTrue(userGuide.contains("gen2spring.runtime.provider.request"));
        assertTrue(userGuide.contains("gen2spring.runtime.provider.executor.active"));
        assertTrue(userGuide.contains("`target.profile`"));
        assertTrue(userGuide.contains("`outcome`"));
        assertTrue(userGuide.contains("`error.category`"));
        assertTrue(userGuide.contains("`http.status.class`"));
        assertTrue(userGuide.contains("MANAGEMENT_SERVER_ADDRESS=127.0.0.1"));
        assertTrue(userGuide.contains("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED=true"));
        assertTrue(userGuide.contains("MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=true"));
        assertTrue(userGuide.contains("MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED=true"));
        assertTrue(userGuide.contains("active OpenTelemetry span의 trace ID"));
        assertFalse(userGuide.contains("metrics와 OpenTelemetry tracing은 후속 P1 범위다"));
        assertTrue(userGuide.contains("supported JSON object response에서 typed output DTO를 생성한다"));
        assertTrue(userGuide.contains("GET operation에 bounded retry를 실행"));
        assertTrue(userGuide.contains("GET operation에 bounded pagination을 실행"));
        assertTrue(userGuide.contains("`maxRetries` 1..3"));
        assertTrue(userGuide.contains("`maxPages` 2..20, `maxItems` 1..2000"));
        assertTrue(userGuide.contains("`gradlew.bat`"));
        assertTrue(userGuide.contains("`bin/java.exe`"));
        assertTrue(userGuide.contains("https://github.com/ydj515/gen2spring-mcp/issues/2"));
        assertFalse(userGuide.contains("UI operation editor complete"));
        assertTrue(readme.contains("OpenAPI 3.0.x와 3.1.x"));
        assertTrue(readme.contains("파일 업로드, API endpoint 선택, 생성 설정의 세 단계"));
        assertTrue(userGuide.contains("OpenAPI 3.0.x와 3.1.x"));
        assertTrue(userGuide.contains("API endpoint 선택, 생성 설정의 세 단계"));
        assertTrue(userGuide.contains("지원 불가 항목은 이유와 함께 비활성화"));
        assertTrue(userGuide.contains("https://spec.openapis.org/oas/3.1/dialect/base"));
        assertTrue(readme.contains("bounded `allOf`·`oneOf`·`anyOf`"));
        assertTrue(userGuide.contains("optional nullable query/header"));
        assertTrue(userGuide.contains("nullable root request body"));
        assertTrue(userGuide.contains("`maxItems` 256"));
        assertTrue(userGuide.contains("branch 8개, 깊이 16"));
        assertTrue(userGuide.contains("전체 branch 64"));
        assertTrue(userGuide.contains("OpenAPI 3.1 `$ref` sibling"));
        assertTrue(userGuide.contains("nullable path와 required nullable query/header"));
        assertTrue(prd.contains("bounded schema 구현 상태: 완료"));
        assertTrue(prd.contains("GitHub issue #12 처리 기준"));
        assertTrue(architecture.contains("id=\"schema-contract\""));
        assertTrue(architecture.contains("Bounded schema normalization contract"));
        assertTrue(architecture.contains("Required nullable query/header"));
        assertFalse(userGuide.contains("typed output DTO, retry 실행, pagination 실행은 후속 P1 범위다"));
        assertFalse(userGuide.contains("Windows validation host remains follow-up P1"));
        assertFalse(userGuide.contains(
                "Generator API와 UI operation editor, Windows validation host 지원은 후속 P1 범위다"));
        assertTrue(prd.contains("FR-4.6 구현 상태: 완료"));
        assertTrue(prd.contains("FR-5.3 구현 상태: 완료"));
        assertTrue(prd.contains("FR-5.4 구현 상태: 완료"));
        assertTrue(prd.contains("플랫폼 검증 상태: Linux와 Windows 완료"));
        assertTrue(readme.contains("단일 immutable Tool Catalog"));
        assertTrue(readme.contains("OPAQUE·Bearer·Basic credential"));
        assertTrue(readme.contains("Gateway가 아니다"));
        assertTrue(userGuide.contains("POST /api/tool-catalogs/{catalogId}/runtimes"));
        assertTrue(userGuide.contains("POST /api/runtimes/{runtimeId}/revocation"));
        assertTrue(userGuide.contains("POST /api/credentials"));
        assertTrue(userGuide.contains("POST /api/runtimes/{runtimeId}/grants"));
        assertTrue(userGuide.contains("GET  /api/runtimes/{runtimeId}/audit"));
        assertTrue(userGuide.contains("Authorization: Bearer <one-time-token>"));
        assertTrue(userGuide.contains("transport는 stateless"));
        assertTrue(userGuide.contains("PostgreSQL에서 원자적으로 수행"));
        assertTrue(userGuide.contains("provider-egress"));
        assertTrue(prd.contains("dynamic Managed Runtime 실행: 완료 (P2 v1 지원 범위)"));
        assertTrue(prd.contains("owner token과 scoped Tool grant"));
        assertTrue(prd.contains("stateless multi-replica Streamable HTTP"));
        assertTrue(prd.contains("cross-Catalog public Gateway, sharing, OAuth2 credential acquisition, billing"));
    }

    @Test
    void installedGenerateRejectsAnImplicitTimestampBeforeCreatingOutput() throws Exception {
        Path executable = Path.of(System.getProperty("openapiMcp.executable"));
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("generate-weather.yaml"), """
                openapi: 3.0.3
                info:
                  title: Weather
                  version: 1.0.0
                paths: {}
                """);
        Path configuration = Files.writeString(safeTemp.resolve("implicit-timestamp.yaml"), """
                project:
                  groupId: com.example
                  artifactId: weather-mcp-server
                  packageName: com.example.weather
                provider: kma
                domain: weather
                targetProfileId: spring-ai-2.0-java21-mvc-streamable
                validationLevel: MCP_PROTOCOL
                operations:
                  - operationId: getForecast
                    enabled: true
                    toolDescription: 2026-08-07
                """);
        Path output = safeTemp.resolve("generated-project");

        Result result = run(executable, "generate", "--spec", specification.toString(),
                "--config", configuration.toString(), "--output", output.toString());

        assertEquals(2, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("CLI_CONFIGURATION_ERROR"));
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(output.resolveSibling(output.getFileName() + ".zip")));
    }

    @Test
    void installedGenerateRejectsAnExplicitStandardTagBeforeCreatingOutput() throws Exception {
        Path executable = Path.of(System.getProperty("openapiMcp.executable"));
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("tagged-weather.yaml"), """
                openapi: 3.0.3
                info:
                  title: Weather
                  version: 1.0.0
                paths: {}
                """);
        Path configuration = Files.writeString(safeTemp.resolve("explicit-standard-tag.yaml"), """
                project:
                  groupId: com.example
                  artifactId: weather-mcp-server
                  packageName: com.example.weather
                provider: !!str kma
                domain: weather
                targetProfileId: spring-ai-2.0-java21-mvc-streamable
                validationLevel: MCP_PROTOCOL
                operations:
                  - operationId: getForecast
                    enabled: true
                """);
        Path output = safeTemp.resolve("tagged-project");

        Result result = run(executable, "generate", "--spec", specification.toString(),
                "--config", configuration.toString(), "--output", output.toString());

        assertEquals(2, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("CLI_CONFIGURATION_ERROR"));
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(output.resolveSibling(output.getFileName() + ".zip")));
    }

    @Test
    void installedGeneratePreservesRawJsonAndEmptySuccessCompatibility() throws Exception {
        Path executable = Path.of(System.getProperty("openapiMcp.executable"));
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("raw-weather.yaml"), rawWeatherSpecification());
        Path configuration = copyResource("/config/weather-generation.yaml", safeTemp.resolve("raw-generation.yaml"));
        Path output = safeTemp.resolve("raw-weather-mcp-server");

        Result generation = run(executable, "generate", "--spec", specification.toString(),
                "--config", configuration.toString(), "--output", output.toString());

        assertEquals(0, generation.exitCode(), () -> generationFailureMessage(generation, output));
        assertEquals("", generation.stderr());
        assertFalse(JSON.readTree(Files.readString(output.resolve("GENERATION_MANIFEST.json")))
                .path("operationMappings").get(0).has("responseNormalization"));
        assertFalse(generation.stdout().contains("STN01"));

        Path regressionTest = output.resolve(
                "src/test/java/com/example/weather/runtime/RawResponseCompatibilityTest.java");
        Files.createDirectories(regressionTest.getParent());
        Files.writeString(regressionTest, rawResponseCompatibilityTest());

        Result generatedTests = runGeneratedTests(output);

        assertEquals(0, generatedTests.exitCode(), generatedTests.stderr() + generatedTests.stdout());
    }

    @Test
    void installedGenerateUsesTheSpringAi1Emitter() throws Exception {
        Path executable = Path.of(System.getProperty("openapiMcp.executable"));
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("spring-ai-1-weather.yaml"), rawWeatherSpecification());
        Path configuration = copyResource(
                "/config/weather-generation-spring-ai1-java21.yaml",
                safeTemp.resolve("spring-ai-1-generation.yaml"));
        Path output = safeTemp.resolve("spring-ai-1-weather-mcp-server");

        Result generation = run(executable, "generate", "--spec", specification.toString(),
                "--config", configuration.toString(), "--output", output.toString());

        assertEquals(0, generation.exitCode(), () -> generationFailureMessage(generation, output));
        assertEquals("", generation.stderr());
        assertTrue(Files.readString(output.resolve("build.gradle.kts"))
                .contains("spring-ai-starter-mcp-server-webmvc"));
        assertEquals("spring-ai-1.1-java21-mvc-streamable",
                JSON.readTree(Files.readString(output.resolve("GENERATION_MANIFEST.json")))
                        .path("targetProfileId").asText());
    }

    @Test
    void failedGenerationDiagnosticsIncludeSafeValidationStages() throws Exception {
        Path output = Files.createDirectories(tempDir.resolve("failed-project"));
        Files.writeString(output.resolve("VALIDATION_REPORT.json"), """
                {
                  "status": "UNVERIFIED",
                  "stages": [
                    {
                      "stage": "COMPILE",
                      "status": "FAILED",
                      "durationMillis": 19,
                      "warningCount": 0,
                      "errorCount": 1,
                      "summary": "build process failed safely",
                      "private": "must-not-leak"
                    }
                  ],
                  "private": "must-not-leak"
                }
                """);

        String diagnostic = generationFailureMessage(new Result(5, "stdout", "stderr"), output);

        assertTrue(diagnostic.contains("\"status\" : \"UNVERIFIED\""));
        assertTrue(diagnostic.contains("\"stage\" : \"COMPILE\""));
        assertTrue(diagnostic.contains("\"errorCount\" : 1"));
        assertTrue(diagnostic.contains("build process failed safely"));
        assertFalse(diagnostic.contains("durationMillis"));
        assertFalse(diagnostic.contains("must-not-leak"));
    }

    private String generationFailureMessage(Result generation, Path output) {
        StringBuilder diagnostic = new StringBuilder()
                .append(generation.stderr())
                .append(generation.stdout());
        Path report = output.resolve("VALIDATION_REPORT.json");
        if (!Files.isRegularFile(report)) {
            return diagnostic.append("\nvalidationReport=missing").toString();
        }
        try {
            var source = JSON.readTree(Files.readString(report, StandardCharsets.UTF_8));
            var safe = JSON.createObjectNode();
            safe.put("status", source.path("status").asText("UNKNOWN"));
            var safeStages = safe.putArray("stages");
            for (var stage : source.path("stages")) {
                var safeStage = safeStages.addObject();
                safeStage.put("stage", stage.path("stage").asText("UNKNOWN"));
                safeStage.put("status", stage.path("status").asText("UNKNOWN"));
                safeStage.put("warningCount", stage.path("warningCount").asInt(-1));
                safeStage.put("errorCount", stage.path("errorCount").asInt(-1));
                safeStage.put("summary", stage.path("summary").asText(""));
            }
            return diagnostic.append("\nvalidationReport=")
                    .append(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(safe))
                    .toString();
        } catch (java.io.IOException | RuntimeException failure) {
            return diagnostic.append("\nvalidationReport=unreadable").toString();
        }
    }

    private Result run(Path executable, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).start();
        boolean finished = process.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("installed CLI timed out");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.exitValue(), stdout, stderr);
    }

    private Result runGeneratedTests(Path projectRoot) throws Exception {
        Path stdoutPath = tempDir.resolve("generated-tests.stdout");
        Path stderrPath = tempDir.resolve("generated-tests.stderr");
        Process process = new ProcessBuilder(
                gradleWrapper(projectRoot).toString(),
                "test",
                "--tests", "com.example.weather.runtime.RawResponseCompatibilityTest",
                "--no-daemon",
                "--non-interactive",
                "--rerun-tasks")
                .directory(projectRoot.toFile())
                .redirectOutput(stdoutPath.toFile())
                .redirectError(stderrPath.toFile())
                .start();
        boolean finished = process.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
            throw new AssertionError("generated runtime tests timed out");
        }
        return new Result(
                process.exitValue(),
                Files.readString(stdoutPath, StandardCharsets.UTF_8),
                Files.readString(stderrPath, StandardCharsets.UTF_8));
    }

    private Path copyResource(String name, Path target) throws Exception {
        try (var input = getClass().getResourceAsStream(name)) {
            if (input == null) {
                throw new AssertionError("Missing test resource: " + name);
            }
            return Files.write(target, input.readAllBytes());
        }
    }

    private Path gradleWrapper(Path projectRoot) {
        return projectRoot.resolve(isWindows() ? "gradlew.bat" : "gradlew");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    private void assertInstalledProfile(
            com.fasterxml.jackson.databind.JsonNode profile,
            String id,
            int javaVersion,
            String springBootVersion,
            String springAiVersion,
            String generatorModule,
            String templateVersion,
            String containerImage) {
        assertEquals(id, profile.path("id").asText());
        assertEquals(generatorModule, profile.path("generatorModule").asText());
        assertEquals(templateVersion, profile.path("templateVersion").asText());
        assertEquals("0.3.0", profile.path("runtimeVersion").asText());
        assertEquals("9.6.1", profile.path("gradleVersion").asText());
        assertEquals(containerImage, profile.path("containerImage").asText());
        assertEquals(javaVersion, profile.path("target").path("javaVersion").asInt());
        assertEquals(springBootVersion, profile.path("target").path("springBootVersion").asText());
        assertEquals(springAiVersion, profile.path("target").path("springAiVersion").asText());
        assertEquals("GRADLE_KOTLIN", profile.path("target").path("buildTool").asText());
        assertEquals("MVC", profile.path("target").path("webStack").asText());
        assertEquals("SYNC", profile.path("target").path("programmingModel").asText());
        assertEquals("STREAMABLE_HTTP", profile.path("target").path("transport").asText());
    }

    private void assertExactPairedFixtureCounts(com.fasterxml.jackson.databind.JsonNode analysis) {
        assertEquals(38, analysis.path("counts").path("total").asInt());
        assertEquals(34, analysis.path("counts").path("supported").asInt());
        assertEquals(0, analysis.path("counts").path("supportedWithWarning").asInt());
        assertEquals(4, analysis.path("counts").path("unsupported").asInt());
        var customers = java.util.stream.StreamSupport.stream(analysis.path("operations").spliterator(), false)
                .filter(operation -> operation.path("operationId").asText().equals("getCustomers"))
                .findFirst().orElseThrow();
        assertEquals("SUPPORTED", customers.path("status").asText());
        assertTrue(customers.path("issues").isEmpty());
        assertOperationDecision(analysis, "listSchemaFixtures", "SUPPORTED", null);
        assertOperationDecision(analysis, "submitRequiredNullablePayload", "SUPPORTED", null);
        assertOperationDecision(analysis, "submitBoundedUniqueItems", "SUPPORTED", null);
        assertOperationDecision(analysis, "getNullablePathFixture", "UNSUPPORTED",
                "PARAMETER_NULLABLE_PATH_UNSUPPORTED");
        assertOperationDecision(analysis, "inspectRequiredNullableFilter", "UNSUPPORTED",
                "PARAMETER_REQUIRED_NULLABLE_UNSUPPORTED");
        assertOperationDecision(analysis, "submitConflictingAllOf", "UNSUPPORTED",
                "SCHEMA_CONSTRAINT_UNSUPPORTED");
        assertOperationDecision(analysis, "submitCompositionBudgetOverflow", "UNSUPPORTED",
                "SCHEMA_COMPOSITION_UNSUPPORTED");
    }

    private void assertOperationDecision(
            com.fasterxml.jackson.databind.JsonNode analysis,
            String operationId,
            String status,
            String issueCode) {
        var operation = java.util.stream.StreamSupport.stream(analysis.path("operations").spliterator(), false)
                .filter(candidate -> candidate.path("operationId").asText().equals(operationId))
                .findFirst().orElseThrow();
        assertEquals(status, operation.path("status").asText());
        if (issueCode == null) {
            assertTrue(operation.path("issues").isEmpty());
        } else {
            assertEquals(issueCode, operation.path("issues").get(0).path("code").asText());
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Unable to locate the repository root");
        }
        return current;
    }

    private String rawWeatherSpecification() {
        return """
                openapi: 3.0.3
                info:
                  title: Weather Forecast API
                  version: 1.0.0
                servers:
                  - url: https://weather.example.test
                paths:
                  /stations/{stationId}/forecast:
                    post:
                      operationId: getForecast
                      parameters:
                        - name: stationId
                          in: path
                          required: true
                          schema:
                            type: string
                        - name: days
                          in: query
                          required: true
                          schema:
                            type: integer
                            minimum: 1
                            maximum: 7
                        - name: tags
                          in: query
                          required: false
                          schema:
                            type: array
                            items:
                              type: string
                        - name: serviceKey
                          in: query
                          required: true
                          schema:
                            type: string
                      security:
                        - WeatherQueryKey: []
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              required: [location]
                              properties:
                                location:
                                  type: object
                                  required: [latitude, longitude]
                                  properties:
                                    latitude:
                                      type: number
                                    longitude:
                                      type: number
                      responses:
                        '200':
                          description: Forecast response
                components:
                  securitySchemes:
                    WeatherQueryKey:
                      type: apiKey
                      in: query
                      name: serviceKey
                """;
    }

    private String rawResponseCompatibilityTest() {
        return """
                package com.example.weather.runtime;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertInstanceOf;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import java.nio.charset.StandardCharsets;
                import java.util.ArrayList;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import org.springframework.http.MediaType;
                import tools.jackson.databind.JsonNode;
                import tools.jackson.databind.json.JsonMapper;
                import tools.jackson.databind.node.StringNode;

                class RawResponseCompatibilityTest {
                    private final JsonMapper mapper = JsonMapper.builder().build();
                    private final ResponseNormalizer normalizer = new ResponseNormalizer();
                    private final OperationDefinition operation = new OperationDefinition(
                            "getForecast", "GET", "/forecast", List.of(), List.of(), false, false, null);
                    private final OperationDefinition normalizedOperation = new OperationDefinition(
                            "getForecast", "GET", "/forecast", List.of(), List.of(), false, false,
                            new ResponseNormalizationPolicy(
                                    "/response/body/items/item",
                                    "/response/header/resultCode",
                                    List.of(StringNode.valueOf("00")),
                                    "/response/header/resultMsg",
                                    "/response/body/totalCount"));

                    @Test
                    void independentlyNormalizesTheFixedProviderEnvelope() throws Exception {
                        byte[] upstream = ("{\\\"response\\\":{"
                                + "\\\"header\\\":{\\\"resultCode\\\":\\\"00\\\","
                                + "\\\"resultMsg\\\":\\\"NORMAL_SERVICE\\\",\\\"rawHeader\\\":true},"
                                + "\\\"body\\\":{\\\"items\\\":{\\\"item\\\":[{\\\"forecast\\\":\\\"sunny\\\"}]},"
                                + "\\\"totalCount\\\":1,\\\"rawBody\\\":true}},\\\"rawRoot\\\":true}")
                                .getBytes(StandardCharsets.UTF_8);
                        JsonNode expected = mapper.readTree(
                                "{\\\"data\\\":[{\\\"forecast\\\":\\\"sunny\\\"}],"
                                        + "\\\"page\\\":{\\\"totalCount\\\":1},"
                                        + "\\\"provider\\\":{\\\"code\\\":\\\"00\\\","
                                        + "\\\"message\\\":\\\"NORMAL_SERVICE\\\"}}");

                        NormalizedSuccess result = assertInstanceOf(NormalizedSuccess.class,
                                normalizer.normalize(normalizedOperation, 200, MediaType.APPLICATION_JSON,
                                        upstream, List.of(), List.of()));

                        assertEquals(expected, result.payload());
                        assertEquals(List.of("data", "page", "provider"), fieldNames(result.payload()));
                        assertEquals(List.of("code", "message"), fieldNames(result.payload().path("provider")));
                        assertFalse(result.payload().has("response"));
                        assertFalse(result.payload().has("rawRoot"));
                        assertTrue(result.payload().findValues("rawHeader").isEmpty());
                        assertTrue(result.payload().findValues("rawBody").isEmpty());
                    }

                    @Test
                    void returnsTheRawProviderJsonBodyWithoutNormalization() throws Exception {
                        byte[] body = "{\\\"data\\\":[{\\\"value\\\":7}],\\\"raw\\\":true}"
                                .getBytes(StandardCharsets.UTF_8);

                        NormalizedSuccess result = assertInstanceOf(NormalizedSuccess.class,
                                normalizer.normalize(operation, 200, MediaType.APPLICATION_JSON,
                                        body, List.of(), List.of()));

                        assertEquals(mapper.readTree(body), result.payload());
                    }

                    @Test
                    void returnsJsonNullForAnEmptySuccessfulBodyWithoutNormalization() {
                        NormalizedSuccess result = assertInstanceOf(NormalizedSuccess.class,
                                normalizer.normalize(operation, 204, null, new byte[0], List.of(), List.of()));

                        assertTrue(result.payload().isNull());
                    }

                    private List<String> fieldNames(JsonNode value) {
                        return new ArrayList<>(value.propertyNames());
                    }
                }
                """;
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
