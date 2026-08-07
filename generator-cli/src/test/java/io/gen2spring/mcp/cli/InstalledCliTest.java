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

    private Result run(Path executable, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).start();
        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("installed CLI timed out");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.exitValue(), stdout, stderr);
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
