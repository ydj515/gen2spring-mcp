package io.gen2spring.mcp.adapter.filesystem;

import io.gen2spring.mcp.domain.tool.OutputKind;

import static io.gen2spring.mcp.application.validation.ValidationStatus.VALIDATED;
import static io.gen2spring.mcp.domain.tool.ParameterSource.SERVER_SECRET;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.command.GenerationCommand.OperationSelection;
import io.gen2spring.mcp.application.command.GenerationCommand.ParameterOverride;
import io.gen2spring.mcp.application.command.GenerationCommand.ProjectCoordinates;
import io.gen2spring.mcp.application.command.GenerationCommand.ToolCallValidation;
import io.gen2spring.mcp.application.command.GenerationCommand.ValidationConfiguration;
import io.gen2spring.mcp.application.port.outbound.GeneratedProjectFiles;
import io.gen2spring.mcp.application.usecase.GenerationPipeline;
import io.gen2spring.mcp.application.usecase.GenerationPreview;
import io.gen2spring.mcp.application.validation.ValidationReport;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.ToolOutput;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import io.gen2spring.mcp.adapter.openapi.swagger.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.application.toolmodel.ToolModelFactory;
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
        AtomicReference<io.gen2spring.mcp.application.usecase.GenerationContext> context =
                new AtomicReference<>();
        var generator = (io.gen2spring.mcp.application.port.outbound.ProjectGenerator) generation -> {
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
        assertEquals("GENERIC_JSON", preview.tools().getFirst().output().mode());
        assertEquals(null, preview.tools().getFirst().output().schemaChecksum());
        assertEquals(null, preview.tools().getFirst().retry());
        assertEquals(null, preview.tools().getFirst().pagination());
    }

    @Test
    void projectsTypedOutputRetryAndPaginationFromFinalToolIrDeterministically() throws Exception {
        ToolDefinition tool = policyTool();
        Map<String, Object> inputSchema = Map.of(
                "type", "object", "properties", Map.of(), "required", List.of());

        GenerationPreview.Tool first = GenerationPreview.Tool.from(tool, inputSchema);
        GenerationPreview.Tool second = GenerationPreview.Tool.from(tool, inputSchema);

        String canonicalSchema = """
                {"properties":{"items":{"items":{"properties":{"id":{"format":"int64","type":"integer"}},"required":["id"],"type":"object"},"minItems":1,"type":"array"},"next":{"nullable":true,"type":"string"}},"required":["items"],"type":"object"}
                """.strip();
        String checksum = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonicalSchema.getBytes(UTF_8)));
        assertEquals(new GenerationPreview.Output("TYPED", checksum), first.output());
        assertEquals(new GenerationPreview.Retry(
                List.of(429, 503), true, 2, 100, 1_000, true), first.retry());
        assertEquals(new GenerationPreview.Pagination(
                "cursor", "/items", "/next", 10, 1_000), first.pagination());
        assertEquals(first, second);
        assertFalse(first.toString().contains("initial-private-cursor"));
    }

    private ToolDefinition policyTool() {
        ApiSchema id = new ApiSchema(
                SchemaType.INTEGER, "int64", false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema item = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, Map.of("id", id), List.of("id"), null, true, List.of());
        ApiSchema items = new ApiSchema(
                SchemaType.ARRAY, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), item, 1, true, List.of());
        ApiSchema next = new ApiSchema(
                SchemaType.STRING, null, true, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema output = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, Map.of("next", next, "items", items),
                List.of("items"), null, true, List.of());
        return new ToolDefinition(
                "getForecast", "weather_get_forecast", "Get forecast", List.of(),
                new HttpExecution(
                        HttpMethod.GET, URI.create("https://weather.example.test"), "/forecast", List.of(),
                        false, false, null,
                        new RetryPolicy(List.of(503, 429), true, 2, 100, 1_000, true),
                        new PaginationPolicy(
                                "cursor", "initial-private-cursor", "/items", "/next", 10, 1_000)),
                List.of(), new ToolOutput(OutputKind.TYPED_DTO, output, output));
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

    private static GenerationCommand request() {
        return new GenerationCommand(
                new ProjectCoordinates("com.example", "weather-mcp-server", "com.example.weather"),
                "weather", "forecast", CompatibilityProfile.p0().id(),
                GenerationCommand.ValidationLevel.MCP_PROTOCOL,
                new ValidationConfiguration(new ToolCallValidation(
                        "getForecast", Map.of("city", PRIVATE_ARGUMENT))),
                List.of(new OperationSelection(
                        "getForecast", true, "weather_get_forecast", "Get forecast",
                        Map.of("serviceKey", new ParameterOverride(SERVER_SECRET, "KMA_SERVICE_KEY")))));
    }
}
