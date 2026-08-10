package io.gen2spring.mcp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationFactoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void compositionRootListsFourProfilesAndGeneratesTheSpringAi1ProjectSources() throws Exception {
        CliApplication application = ApplicationFactory.create();
        Result profiles = run(application, "profiles");

        assertEquals(0, profiles.exitCode());
        assertEquals(4, JSON.readTree(profiles.stdout()).path("profiles").size());

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
        Path configuration = Files.writeString(safeTemp.resolve("spring-ai-1.yaml"), """
                project:
                  groupId: com.example
                  artifactId: weather-mcp-server
                  packageName: com.example.weather
                provider: kma
                domain: weather
                targetProfileId: spring-ai-1.1-java21-mvc-streamable
                validationLevel: MCP_PROTOCOL
                validation:
                  toolCall:
                    operationId: getForecast
                    arguments: {}
                operations:
                  - operationId: getForecast
                    enabled: true
                    toolName: kma_weather_get_forecast
                    toolDescription: Get the public weather forecast.
                """);
        Path output = safeTemp.resolve("weather-mcp-server");

        Result generation = run(application, "generate", "--spec", specification.toString(),
                "--config", configuration.toString(), "--output", output.toString());

        assertFalse(generation.stderr().contains("TARGET_COMBINATION_UNSUPPORTED"));
        assertTrue(Files.isRegularFile(output.resolve("build.gradle.kts")), generation.stderr());
        String build = Files.readString(output.resolve("build.gradle.kts"));
        assertTrue(build.contains("spring-ai-starter-mcp-server-webmvc"));
        assertTrue(build.contains("1.1.8"));
    }

    private Result run(CliApplication application, String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = application.run(
                arguments,
                new PrintWriter(stdout, true, StandardCharsets.UTF_8),
                new PrintWriter(stderr, true, StandardCharsets.UTF_8));
        return new Result(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
