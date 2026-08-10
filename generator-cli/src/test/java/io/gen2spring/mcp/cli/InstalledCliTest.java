package io.gen2spring.mcp.cli;

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
    @TempDir
    Path tempDir;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void installedProfilesAndInspectKeepSuccessfulStderrEmpty() throws Exception {
        Path executable = Path.of(System.getProperty("openapiMcp.executable"));
        assertTrue(Files.isExecutable(executable));

        Result profiles = run(executable, "profiles");
        assertEquals(0, profiles.exitCode(), profiles.stderr());
        assertEquals("", profiles.stderr());
        assertEquals(1, JSON.readTree(profiles.stdout()).path("profiles").size());

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

        assertEquals(0, generation.exitCode(), generation.stderr() + generation.stdout());
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
                projectRoot.resolve("gradlew").toString(),
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
                import static org.junit.jupiter.api.Assertions.assertInstanceOf;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import java.nio.charset.StandardCharsets;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import org.springframework.http.MediaType;
                import tools.jackson.databind.json.JsonMapper;

                class RawResponseCompatibilityTest {
                    private final JsonMapper mapper = JsonMapper.builder().build();
                    private final ResponseNormalizer normalizer = new ResponseNormalizer();
                    private final OperationDefinition operation = new OperationDefinition(
                            "getForecast", "GET", "/forecast", List.of(), List.of(), false, false, null);

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
                }
                """;
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
