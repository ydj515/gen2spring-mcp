package io.gen2spring.mcp.application.planning;

import static io.gen2spring.mcp.domain.tool.ParameterSource.SERVER_SECRET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.command.GenerationCommand.OperationSelection;
import io.gen2spring.mcp.application.command.GenerationCommand.ParameterOverride;
import io.gen2spring.mcp.application.command.GenerationCommand.ProjectCoordinates;
import io.gen2spring.mcp.application.command.GenerationCommand.ToolCallValidation;
import io.gen2spring.mcp.application.command.GenerationCommand.ValidationConfiguration;
import io.gen2spring.mcp.application.port.outbound.GeneratedProjectFiles;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiOperation;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiParameter;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSecurityScheme;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.specification.OperationSupport;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.application.toolmodel.ToolModelFactory;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenerationPlannerTest {
    @Test
    void plansCanonicalProfileEmitterToolSchemaAndRepresentativeCall() {
        CompatibilityProfileRegistry profiles = CompatibilityProfileRegistry.defaults();
        var emitter = (io.gen2spring.mcp.application.port.outbound.ProjectGenerator) context ->
                new GeneratedProjectFiles(Map.of());
        GenerationPlanner planner = new GenerationPlanner(
                new ToolModelFactory(),
                profiles,
                ProjectGeneratorRegistry.of(Map.of("generator-spring-ai-2", emitter)));

        GenerationPlanner.PlannedGeneration plan = planner.plan(document(), request());

        assertSame(profiles.find(CompatibilityProfile.p0().id()).orElseThrow(), plan.profile());
        assertSame(emitter, plan.projectGenerator());
        assertEquals(List.of("weather_get_forecast"),
                plan.tools().stream().map(tool -> tool.name()).toList());
        assertEquals(List.of("city"), plan.tools().getFirst().inputs().stream().map(input -> input.name()).toList());
        assertEquals(List.of("KMA_SERVICE_KEY"), plan.secretEnvironmentVariables());
        assertEquals(Map.of("city", "Seoul"), plan.expectedToolCall().arguments());
        assertEquals(plan.tools().getFirst().description(),
                plan.expectedTools().get("weather_get_forecast").description());
    }

    private static OpenApiDocument document() {
        ApiSchema string = new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null, null, null, null,
                null, Map.of(), List.of(), null, true, List.of());
        ApiOperation operation = new ApiOperation(
                "getForecast", HttpMethod.GET, "/forecast", "Get forecast", null,
                List.of(
                        new ApiParameter("city", ParameterLocation.QUERY, true, "City", string),
                        new ApiParameter("serviceKey", ParameterLocation.QUERY, true, "API key", string)),
                null, false, List.of("serviceKey"), OperationSupport.fromIssues(List.of()));
        return new OpenApiDocument(
                "3.0.3", "a".repeat(64), "yaml", URI.create("https://weather.example.test"),
                List.of(operation),
                Map.of("serviceKey", new ApiSecurityScheme(
                        "serviceKey", "apiKey", ParameterLocation.QUERY, "serviceKey")),
                List.of());
    }

    private static GenerationCommand request() {
        return new GenerationCommand(
                new ProjectCoordinates("com.example", "weather-mcp-server", "com.example.weather"),
                "weather", "forecast", CompatibilityProfile.p0().id(),
                GenerationCommand.ValidationLevel.MCP_PROTOCOL,
                new ValidationConfiguration(new ToolCallValidation(
                        "getForecast", Map.of("city", "Seoul"))),
                List.of(new OperationSelection(
                        "getForecast", true, "weather_get_forecast", "Get forecast",
                        Map.of("serviceKey", new ParameterOverride(SERVER_SECRET, "KMA_SERVICE_KEY")))));
    }
}
