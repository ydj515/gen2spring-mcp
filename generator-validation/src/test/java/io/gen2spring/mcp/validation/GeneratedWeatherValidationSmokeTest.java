package io.gen2spring.mcp.validation;

import static io.gen2spring.mcp.domain.config.GenerationRequest.ValidationLevel.MCP_PROTOCOL;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.SUCCESS;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.VALIDATED;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation.HEADER;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation.QUERY;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.ARRAY;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.INTEGER;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.OBJECT;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.STRING;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.config.GenerationRequest.ProjectCoordinates;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationContext;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedToolCall;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationRequest;
import io.gen2spring.mcp.domain.generation.ExpectedToolSchemaFactory;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.HttpExecutionDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.McpInputDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterBinding;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.SecretBinding;
import io.gen2spring.mcp.springai2.SpringAi2ProjectGenerator;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class GeneratedWeatherValidationSmokeTest {
    private static final String TOOL_NAME = "kma_weather_get_forecast";
    private static final String TOOL_DESCRIPTION = "Get the public weather forecast for a grid location.";

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void validatesTheActualSpringAiToolsListSchemaForAGeneratedWeatherProject() throws Exception {
        Path root = tempDir.toRealPath();
        McpToolDefinition tool = weatherTool();
        var coordinates = new ProjectCoordinates(
                "com.example", "weather-mcp-server", "com.example.weather");
        var generationRequest = new GenerationRequest(
                coordinates, "kma", "weather", CompatibilityProfile.p0().id(), MCP_PROTOCOL,
                new GenerationRequest.ValidationConfiguration(new GenerationRequest.ToolCallValidation(
                        "getForecast", weatherArguments())),
                List.of());
        var files = new SpringAi2ProjectGenerator().generate(new GenerationContext(
                null, List.of(tool), generationRequest, CompatibilityProfile.p0(), new byte[0]));
        assertTrue(files.files().containsKey(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java"));
        for (var entry : files.files().entrySet()) {
            Path target = root.resolve(entry.getKey()).normalize();
            assertTrue(target.startsWith(root));
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        assertTrue(root.resolve("gradlew").toFile().setExecutable(true));
        var expectedTools = new ExpectedToolSchemaFactory().create(List.of(tool));
        Map<String, Object> expectedSchema = expectedTools.get(TOOL_NAME).inputSchema();
        Map<?, ?> inputs = assertInstanceOf(Map.class, expectedSchema.get("properties"));
        Map<?, ?> nx = assertInstanceOf(Map.class, inputs.get("nx"));
        Map<?, ?> options = assertInstanceOf(Map.class, inputs.get("options"));
        Map<?, ?> optionProperties = assertInstanceOf(Map.class, options.get("properties"));
        Map<?, ?> region = assertInstanceOf(Map.class, optionProperties.get("region"));
        assertEquals(java.util.Set.of("nx", "ny", "options", "mode", "tags"), inputs.keySet());
        assertEquals(List.of("region"), options.get("required"));
        assertEquals(java.util.Set.of("region", "filter"), optionProperties.keySet());
        assertEquals(BigDecimal.ZERO, nx.get("minimum"));
        assertEquals(BigDecimal.valueOf(1000), nx.get("maximum"));
        assertEquals(3, region.get("minLength"));
        assertEquals(8, region.get("maxLength"));
        assertEquals("[a-z]+", region.get("pattern"));

        var report = new GradleMcpProjectValidator().validate(new ValidationRequest(
                root, coordinates.artifactId(), MCP_PROTOCOL, expectedTools,
                new ExpectedToolCall(tool, weatherArguments()), CompatibilityProfile.p0()));

        assertEquals(VALIDATED, report.status(), report.toString());
        assertEquals(List.of("COMPILE", "APPLICATION_CONTEXT", "MCP_INITIALIZE", "MCP_TOOLS_LIST", "MCP_TOOL_CALL"),
                report.stages().stream().map(stage -> stage.stage()).toList());
        assertTrue(report.stages().stream().allMatch(stage -> stage.status() == SUCCESS));
        assertTrue(report.stages().get(4).summary().contains("mock upstream contract"));
        assertTrue(report.stages().get(4).summary().chars().noneMatch(Character::isDigit));
        assertTrue(report.stages().stream().noneMatch(stage -> stage.summary().contains("seoul")));
        assertTrue(report.stages().stream().noneMatch(stage -> stage.summary().contains("mcp-validation-secret")));
        assertEquals(List.of(TOOL_NAME), report.tools().stream().map(toolResult -> toolResult.name()).toList());
    }

    private McpToolDefinition weatherTool() {
        ApiSchema integer = new ApiSchema(
                INTEGER, "int32", false, List.of(), BigDecimal.ZERO, BigDecimal.valueOf(1000),
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema constrainedString = new ApiSchema(
                STRING, null, false, List.of(), null, null, 3, 8, "[a-z]+",
                null, Map.of(), List.of(), null, true, List.of());
        ApiSchema options = new ApiSchema(
                OBJECT, null, false, List.of(), null, null, null, null, null,
                null,
                Map.of("region", constrainedString, "filter", constrainedString),
                List.of("region"), null, true, List.of());
        ApiSchema mode = new ApiSchema(
                STRING, null, false, List.of(), null, null, null, null, null,
                null, Map.of(), List.of(), null, true, List.of());
        ApiSchema tags = new ApiSchema(
                ARRAY, null, false, List.of(), null, null, null, null, null,
                null, Map.of(), List.of(), constrainedString, true, List.of());
        List<McpInputDefinition> inputs = List.of(
                new McpInputDefinition("nx", "nx", "Grid x coordinate", true, integer),
                new McpInputDefinition("ny", "ny", "Grid y coordinate", true, integer),
                new McpInputDefinition("options", "options", "Forecast options", true, options),
                new McpInputDefinition("mode", "mode", "Forecast mode", false, mode),
                new McpInputDefinition("tags", "tags", "Forecast tags", false, tags));
        return new McpToolDefinition(
                "getForecast", TOOL_NAME, TOOL_DESCRIPTION, inputs,
                new HttpExecutionDefinition(
                        GET, URI.create("https://api.example.test"), "/forecast",
                        List.of(
                                new ParameterBinding("nx", QUERY, "nx"),
                                new ParameterBinding("ny", QUERY, "ny"),
                                new ParameterBinding("mode", QUERY, "mode"),
                                new ParameterBinding("tags", QUERY, "tags"))),
                List.of(
                        new SecretBinding("KMA_SERVICE_KEY", "service-key", QUERY, "serviceKey", true),
                        new SecretBinding("KMA_HEADER_KEY", "header-key", HEADER, "X-Weather-Key", true)),
                McpToolDefinition.OutputKind.GENERIC_JSON);
    }

    private Map<String, Object> weatherArguments() {
        return Map.of(
                "nx", 60,
                "ny", 127,
                "options", Map.of("region", "seoul"),
                "mode", "brief",
                "tags", List.of("public", "forecast"));
    }

}
