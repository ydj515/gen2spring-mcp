package io.gen2spring.mcp.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.GeneratorApplication;
import io.gen2spring.mcp.application.usecase.GenerationOutcome;
import io.gen2spring.mcp.application.validation.ValidationStatus;
import io.gen2spring.mcp.adapter.openapi.swagger.SwaggerOpenApiAnalyzer;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobHandlerTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesStrictConfigurationAndReturnsOnlySafeJobFields() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (SpecificationStore specifications = new SpecificationStore(tempDir, new SwaggerOpenApiAnalyzer());
                GenerationJobManager jobs = new GenerationJobManager(
                        tempDir, this::unverified, Clock.systemUTC(), Duration.ofHours(1), ignored -> {})) {
            String specificationId = specifications.store(
                    "weather.yaml", new ByteArrayInputStream(specification().getBytes(UTF_8))).id();
            JobHandler handler = new JobHandler(
                    GeneratorApplication.defaults(), specifications, jobs, json);

            JsonNode accepted = handler.start(
                    specificationId, new ByteArrayInputStream(configuration().getBytes(UTF_8)));
            assertEquals("QUEUED", accepted.path("state").textValue());
            String id = accepted.path("id").textValue();
            jobs.await(id, Duration.ofSeconds(5));

            JsonNode status = handler.status(id);
            assertEquals("UNVERIFIED", status.path("state").textValue());
            assertEquals(8, status.path("stages").size());
            assertEquals(2, status.path("downloads").size());
            assertFalse(status.toString().contains("representative-private-value"));
            assertFalse(status.has("request"));
            assertFalse(status.has("arguments"));

            handler.delete(id);
            org.junit.jupiter.api.Assertions.assertThrows(
                    GenerationJobManager.JobNotFoundException.class, () -> handler.status(id));
        }
    }

    private GenerationOutcome unverified(
            Path specification,
            io.gen2spring.mcp.application.command.GenerationCommand request,
            Path output,
            io.gen2spring.mcp.application.usecase.GenerationProgressListener progress)
            throws Exception {
        Files.createDirectory(output);
        Files.writeString(output.resolve("GENERATION_MANIFEST.json"), "{}");
        Files.writeString(output.resolve("VALIDATION_REPORT.json"), "{}");
        return new GenerationOutcome(output, null, ValidationStatus.UNVERIFIED, "checksum");
    }

    private String specification() {
        return """
                openapi: 3.0.3
                info: {title: Weather, version: 1.0.0}
                servers: [{url: https://weather.example.test}]
                paths:
                  /forecast:
                    get:
                      operationId: getForecast
                      parameters:
                        - name: city
                          in: query
                          required: true
                          schema: {type: string}
                      responses:
                        '200': {description: Success}
                """;
    }

    private String configuration() {
        return """
                {
                  "project":{"groupId":"com.example","artifactId":"weather-mcp",
                    "packageName":"com.example.weather"},
                  "provider":"weather","domain":"forecast",
                  "targetProfileId":"spring-ai-2.0-java21-mvc-streamable",
                  "validationLevel":"MCP_PROTOCOL",
                  "validation":{"toolCall":{"operationId":"getForecast",
                    "arguments":{"city":"representative-private-value"}}},
                  "operations":[{"operationId":"getForecast","enabled":true,
                    "toolName":"weather_get_forecast","toolDescription":"Get forecast","parameters":{}}]
                }
                """;
    }
}
