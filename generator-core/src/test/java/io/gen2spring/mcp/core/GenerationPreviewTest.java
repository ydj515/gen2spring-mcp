package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.VALIDATED;
import static io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterSource.SERVER_SECRET;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.config.GenerationRequest.OperationSelection;
import io.gen2spring.mcp.domain.config.GenerationRequest.ParameterOverride;
import io.gen2spring.mcp.domain.config.GenerationRequest.ProjectCoordinates;
import io.gen2spring.mcp.domain.config.GenerationRequest.ToolCallValidation;
import io.gen2spring.mcp.domain.config.GenerationRequest.ValidationConfiguration;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectFiles;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationReport;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.openapi.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.policy.ToolModelFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationPreviewTest {
    private static final String PRIVATE_ARGUMENT = "representative-private-value";

    @TempDir
    Path tempDir;

    @Test
    void rendersAnImmutableSortedPreviewWithoutWritingOrLeakingArguments() throws Exception {
        Path specification = Files.writeString(tempDir.resolve("weather.yaml"), specification(), UTF_8);
        Path absentOutput = tempDir.resolve("must-not-exist");
        AtomicReference<io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationContext> context =
                new AtomicReference<>();
        var generator = (io.gen2spring.mcp.domain.generation.GenerationContracts.ProjectGenerator) generation -> {
            context.set(generation);
            return new GeneratedProjectFiles(Map.of(
                    "z.txt", "z".getBytes(UTF_8),
                    "a.txt", "a".getBytes(UTF_8)));
        };
        var pipeline = new GenerationPipeline(
                new SwaggerOpenApiAnalyzer(), new ToolModelFactory(), CompatibilityProfile.p0(), generator,
                new SafeProjectWriter(), new SourceTreeChecksum(), new GenerationManifestWriter(new ObjectMapper()),
                request -> new ValidationReport(VALIDATED, List.of(), List.of()),
                new ValidationReportWriter(new ObjectMapper()), new DeterministicZipPackager());

        GenerationPreview preview = pipeline.preview(specification, request());

        assertSame(CompatibilityProfile.p0(), preview.profile());
        assertSame(preview.profile(), context.get().profile());
        assertEquals(List.of("weather_get_forecast"),
                preview.tools().stream().map(GenerationPreview.Tool::name).toList());
        assertEquals(List.of("KMA_SERVICE_KEY"), preview.secretEnvironmentVariables());
        assertEquals(List.of(
                "GENERATION_MANIFEST.json",
                "VALIDATION_REPORT.json",
                "a.txt",
                "openapi/source.yaml",
                "weather-mcp-server.zip",
                "z.txt"), preview.generatedFilePaths());
        assertFalse(Files.exists(absentOutput));
        assertFalse(preview.toString().contains(PRIVATE_ARGUMENT));
        assertFalse(preview.tools().getFirst().inputSchema().toString().contains(PRIVATE_ARGUMENT));
        assertThrows(UnsupportedOperationException.class,
                () -> preview.generatedFilePaths().add("late.txt"));
        assertThrows(UnsupportedOperationException.class,
                () -> preview.tools().getFirst().inputSchema().put("late", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>)
                preview.tools().getFirst().inputSchema().get("properties");
        assertThrows(UnsupportedOperationException.class,
                () -> properties.put("late", Map.of()));
    }

    private static String specification() {
        return """
                openapi: 3.0.3
                info:
                  title: Weather API
                  version: 1.0.0
                servers:
                  - url: https://weather.example.test
                paths:
                  /forecast:
                    get:
                      operationId: getForecast
                      summary: Get forecast
                      security:
                        - serviceKey: []
                      parameters:
                        - name: city
                          in: query
                          required: true
                          schema: {type: string}
                        - name: serviceKey
                          in: query
                          required: true
                          schema: {type: string}
                      responses:
                        '200': {description: Success}
                components:
                  securitySchemes:
                    serviceKey:
                      type: apiKey
                      in: query
                      name: serviceKey
                """;
    }

    private static GenerationRequest request() {
        return new GenerationRequest(
                new ProjectCoordinates("com.example", "weather-mcp-server", "com.example.weather"),
                "weather", "forecast", CompatibilityProfile.p0().id(),
                GenerationRequest.ValidationLevel.MCP_PROTOCOL,
                new ValidationConfiguration(new ToolCallValidation(
                        "getForecast", Map.of("city", PRIVATE_ARGUMENT))),
                List.of(new OperationSelection(
                        "getForecast", true, "weather_get_forecast", "Get forecast",
                        Map.of("serviceKey", new ParameterOverride(SERVER_SECRET, "KMA_SERVICE_KEY")))));
    }
}
