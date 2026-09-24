package io.gen2spring.mcp.adapter.emitter.springai1.fixture;

import io.gen2spring.mcp.application.generation.command.GenerationCommand;
import io.gen2spring.mcp.application.generation.usecase.GenerationContext;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.OutputKind;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import io.gen2spring.mcp.domain.tool.SecretBinding;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.ToolInput;
import io.gen2spring.mcp.domain.tool.ToolOutput;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

public final class RendererFixtures {
    public static GenerationContext contextWithWeatherTool() {
        return context(List.of(weatherTool()));
    }

    public static GenerationContext contextWithWeatherTool(CompatibilityProfile profile) {
        return context(profile, List.of(weatherTool()));
    }

    public static GenerationContext context(List<ToolDefinition> tools) {
        return context(profile(21), tools);
    }

    public static GenerationContext context(CompatibilityProfile profile, List<ToolDefinition> tools) {
        var coordinates = new GenerationCommand.ProjectCoordinates(
                "com.example", "weather-mcp-server", "com.example.weather");
        var request = new GenerationCommand(
                coordinates, "kma", "weather", profile.id(),
                GenerationCommand.ValidationLevel.MCP_PROTOCOL,
                new GenerationCommand.ValidationConfiguration(new GenerationCommand.ToolCallValidation(
                        "getForecast", Map.of("nx", 60, "ny", 127))),
                List.of());
        return new GenerationContext(null, tools, request, profile, new byte[0]);
    }

    public static CompatibilityProfile profile(int javaVersion) {
        return io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry.defaults()
                .find("spring-ai-1.1-java" + javaVersion + "-mvc-streamable")
                .orElseThrow();
    }

    public static ToolDefinition weatherTool() {
        ApiSchema integer = schema(SchemaType.INTEGER, "int32", BigDecimal.ZERO,
                BigDecimal.valueOf(1000), null, null, null, List.of());
        return weatherTool(
                List.of(
                        new ToolInput("nx", "nx", "Grid x coordinate", true, integer),
                        new ToolInput("ny", "ny", "Grid y coordinate", true, integer)),
                List.of(
                        new ParameterBinding("nx", ParameterLocation.QUERY, "nx"),
                        new ParameterBinding("ny", ParameterLocation.QUERY, "ny")));
    }

    public static ToolDefinition typedWeatherTool() {
        ToolDefinition base = weatherTool();
        ApiSchema result = typedResultSchema();
        return new ToolDefinition(
                base.operationId(), base.name(), base.description(), base.inputs(), base.execution(),
                base.secretBindings(), new ToolOutput(
                        OutputKind.TYPED_DTO, result, result));
    }

    public static ApiSchema typedResultSchema() {
        ApiSchema condition = new ApiSchema(
                SchemaType.STRING, null, false, List.of("sunny", "partly-cloudy"), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema conditions = new ApiSchema(
                SchemaType.ARRAY, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), condition, true, List.of());
        ApiSchema temperature = new ApiSchema(
                SchemaType.NUMBER, "double", false, List.of(), BigDecimal.valueOf(-50), BigDecimal.valueOf(60),
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema data = objectSchema(Map.of(
                "city-name", textSchema(),
                "conditions", conditions,
                "display name", textSchema(),
                "temperature", temperature), List.of("city-name", "conditions"));
        ApiSchema page = objectSchema(Map.of("totalCount", schema(SchemaType.INTEGER, "int64",
                BigDecimal.ZERO, null, null, null, null, List.of())), List.of("totalCount"));
        ApiSchema provider = objectSchema(Map.of("code", textSchema(), "message", textSchema()),
                List.of("code", "message"));
        return objectSchema(Map.of("data", data, "page", page, "provider", provider),
                List.of("data", "page", "provider"));
    }

    public static ApiSchema objectSchema(Map<String, ApiSchema> properties, List<String> required) {
        return new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, properties, required, null, true, List.of());
    }

    public static ApiSchema textSchema() {
        return schema(SchemaType.STRING, null, null, null, null, null, null, List.of());
    }

    public static ToolDefinition weatherTool(ResponseNormalizationPolicy normalization) {
        ToolDefinition tool = weatherTool();
        HttpExecution execution = tool.execution();
        return new ToolDefinition(
                tool.operationId(),
                tool.name(),
                tool.description(),
                tool.inputs(),
                new HttpExecution(
                        execution.method(),
                        execution.baseUrl(),
                        execution.path(),
                        execution.bindings(),
                        execution.objectRequestBody(),
                        execution.requestBodyRequired(),
                        normalization),
                tool.secretBindings(),
                tool.outputKind());
    }

    public static ResponseNormalizationPolicy normalization() {
        return new ResponseNormalizationPolicy(
                "/response/body/items",
                "/response/header/code",
                List.of("00", new BigDecimal("1.50"), new BigDecimal("1E+3"), true),
                "/response/header/message",
                "/response/body/totalCount");
    }

    public static ToolDefinition weatherTool(
            List<ToolInput> inputs,
            List<ParameterBinding> bindings) {
        return new ToolDefinition(
                "getForecast",
                "kma_weather_get_forecast",
                "Get the public weather forecast for a grid location.",
                inputs,
                new HttpExecution(
                        HttpMethod.GET,
                        URI.create("https://api.example.test"),
                        "/forecast",
                        bindings),
                List.of(new SecretBinding(
                        "KMA_SERVICE_KEY", "service-key", ParameterLocation.QUERY, "serviceKey", true)),
                OutputKind.GENERIC_JSON);
    }

    public static ApiSchema schema(
            SchemaType type,
            String format,
            BigDecimal minimum,
            BigDecimal maximum,
            Integer minLength,
            Integer maxLength,
            String pattern,
            List<String> enumValues) {
        return new OpenApiDocument.ApiSchema(
                type, format, false, enumValues, minimum, maximum, minLength, maxLength,
                pattern, null, Map.of(), List.of(), null, true, List.of());
    }

    private RendererFixtures() {}
}
