package io.gen2spring.mcp.springai2;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationContext;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.HttpExecutionDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.McpInputDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterBinding;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.SecretBinding;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class JavaSourceRendererTest {
    private final JavaSourceRenderer renderer = new JavaSourceRenderer();

    @Test
    void rendersAFlatMcpToolAndInputRecord() throws IOException {
        var files = renderer.render(contextWithWeatherTool());

        assertArrayEquals(golden("weather/WeatherMcpTools.java"),
                files.get("src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));
        assertArrayEquals(golden("weather/GetForecastInput.java"),
                files.get("src/main/java/com/example/weather/generated/model/GetForecastInput.java"));
        String tool = utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));
        assertTrue(tool.contains("public JsonNode getForecast("));
        assertTrue(tool.contains("var input = new GetForecastInput(nx, ny);"));
        assertTrue(tool.contains(
                "return executor.execute(WeatherOperations.GET_FORECAST, input.toArguments());"));
        assertFalse(tool.contains("OperationOutcome"));
    }

    @Test
    void emitsMetadataRuntimeApplicationAndNonUpstreamContextTest() {
        var files = renderer.render(contextWithWeatherTool());

        assertEquals(new TreeSet<>(List.of(
                "src/main/java/com/example/weather/application/WeatherMcpApplication.java",
                "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java",
                "src/main/java/com/example/weather/generated/model/GetForecastInput.java",
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java",
                "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java",
                "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java",
                "src/main/java/com/example/weather/runtime/NormalizedSuccess.java",
                "src/main/java/com/example/weather/runtime/OperationDefinition.java",
                "src/main/java/com/example/weather/runtime/OperationOutcome.java",
                "src/main/java/com/example/weather/runtime/ParameterBinding.java",
                "src/main/java/com/example/weather/runtime/ParameterLocation.java",
                "src/main/java/com/example/weather/runtime/ProviderError.java",
                "src/main/java/com/example/weather/runtime/ProviderErrorCategory.java",
                "src/main/java/com/example/weather/runtime/ProviderErrorException.java",
                "src/main/java/com/example/weather/runtime/ResponseNormalizationPolicy.java",
                "src/main/java/com/example/weather/runtime/ResponseNormalizer.java",
                "src/main/java/com/example/weather/runtime/SecretBinding.java",
                "src/test/java/com/example/weather/application/WeatherMcpApplicationTest.java")),
                new TreeSet<>(files.keySet()));

        String metadata = utf8(files.get(
                "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java"));
        assertTrue(metadata.indexOf("ParameterLocation.QUERY") < metadata.indexOf("new SecretBinding"));
        assertTrue(metadata.contains("\"provider.secrets.service-key\""));

        String runtime = utf8(files.get(
                "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java"));
        assertTrue(runtime.contains("readNBytes(responseMaxBytes + 1)"));
        assertTrue(runtime.contains("new ResponseTooLargeException(status)"));
        assertTrue(runtime.contains("responseNormalizer.normalize("));
        assertTrue(runtime.contains("new ProviderErrorException"));
        assertTrue(runtime.contains("ProviderErrorCategory.UPSTREAM_TIMEOUT"));
        assertTrue(runtime.contains("ProviderErrorCategory.UPSTREAM_UNAVAILABLE"));
        assertTrue(runtime.contains("target.setAccept(List.of(MediaType.APPLICATION_JSON))"));
        assertTrue(runtime.contains("request.contentType(MediaType.APPLICATION_JSON)"));
        assertTrue(runtime.contains("jsonMapper.writeValueAsBytes(requestBody)"));
        assertTrue(runtime.contains("tools.jackson.databind.JsonNode"));
        assertTrue(runtime.contains("tools.jackson.databind.json.JsonMapper"));

        String contextTest = utf8(files.get(
                "src/test/java/com/example/weather/application/WeatherMcpApplicationTest.java"));
        assertTrue(contextTest.contains("webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT"));
        assertTrue(contextTest.contains("void contextLoads()"));
    }

    @Test
    void emitsTypedNormalizationMetadataAndFocusedRuntimeSources() {
        var files = renderer.render(context(List.of(weatherTool(normalization()))));

        String metadata = utf8(files.get(
                "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java"));
        assertTrue(metadata.contains("new ResponseNormalizationPolicy("));
        assertTrue(metadata.contains("StringNode.valueOf(\"00\")"));
        assertTrue(metadata.contains("JsonNodeFactory.instance.numberNode(new BigDecimal(\"1.50\"))"));
        assertTrue(metadata.contains("BooleanNode.TRUE"));
        assertTrue(files.containsKey("src/main/java/com/example/weather/runtime/ResponseNormalizer.java"));
        assertTrue(files.containsKey("src/main/java/com/example/weather/runtime/ProviderErrorException.java"));
    }

    @Test
    void mapsSchemaConstraintsAndJsonNamesToSafeJavaSource() {
        ApiSchema schema = schema(SchemaType.STRING, null, BigDecimal.ONE, BigDecimal.TEN, 2, 12, "[A-Z]+", List.of());
        ApiSchema body = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null, null, null,
                null, null, Map.of("postal-code", schema), List.of("postal-code"), null, true, List.of());
        var bodyInput = new McpInputDefinition("body", "body", "Postal request", true, body);
        McpToolDefinition tool = weatherTool(List.of(bodyInput), List.of(
                new ParameterBinding("body", ParameterLocation.BODY, "body")));

        String input = utf8(renderer.render(context(List.of(tool))).get(
                "src/main/java/com/example/weather/generated/model/GetForecastBody.java"));

        assertTrue(input.contains("import com.fasterxml.jackson.annotation.JsonProperty;"));
        assertTrue(input.contains("@JsonProperty(\"postal-code\")"));
        assertTrue(input.contains("@NotNull"));
        assertTrue(input.contains("@Size(min = 2, max = 12)"));
        assertTrue(input.contains("@Pattern(regexp = \"[A-Z]+\")"));
        assertTrue(input.contains("String postalCode"));
    }

    @Test
    void rendersJavaSafeFlatMcpKeysWhilePreservingSpacedUpstreamJsonNames() {
        ApiSchema text = schema(SchemaType.STRING, null, null, null, null, null, null, List.of());
        McpToolDefinition tool = weatherTool(
                List.of(new McpInputDefinition("displayName", "display name", "Display name", true, text)),
                List.of(new ParameterBinding("displayName", ParameterLocation.BODY, "display name")));

        var files = assertDoesNotThrow(() -> renderer.render(context(List.of(tool))));
        String input = utf8(files.get(
                "src/main/java/com/example/weather/generated/model/GetForecastInput.java"));

        assertTrue(input.contains("@JsonProperty(\"display name\")"));
        assertTrue(input.contains("String displayName"));
        assertTrue(input.contains("arguments.put(\"displayName\", displayName)"));
    }

    @Test
    void rejectsBlankOversizedAndControlJsonPropertyNames() {
        ApiSchema text = schema(SchemaType.STRING, null, null, null, null, null, null, List.of());

        for (String jsonName : List.of(" ", "line\nbreak", "x".repeat(129))) {
            McpToolDefinition tool = weatherTool(
                    List.of(new McpInputDefinition("value", jsonName, "Value", true, text)),
                    List.of(new ParameterBinding("value", ParameterLocation.QUERY, "value")));

            assertThrows(GeneratorException.class, () -> renderer.render(context(List.of(tool))), jsonName);
        }
    }

    @Test
    void derivesAsciiSafeNestedPropertyNamesWhilePreservingUnicodeRawJsonNames() {
        ApiSchema body = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null, null, null,
                null, null,
                Map.of("café name", schema(SchemaType.STRING, null, null, null, null, null, null, List.of())),
                List.of("café name"), null, true, List.of());
        McpToolDefinition tool = weatherTool(
                List.of(new McpInputDefinition("body", "body", "Body", true, body)),
                List.of(new ParameterBinding("body", ParameterLocation.BODY, "body")));

        String nested = utf8(renderer.render(context(List.of(tool))).get(
                "src/main/java/com/example/weather/generated/model/GetForecastBody.java"));

        assertTrue(nested.contains("@JsonProperty(\"café name\")"));
        assertTrue(nested.contains("String cafName"));
        assertFalse(nested.contains("String caféName"));
    }

    @Test
    void rejectsNestedPropertyNamesThatCollideAfterJavaNormalization() {
        ApiSchema text = schema(SchemaType.STRING, null, null, null, null, null, null, List.of());
        ApiSchema body = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null, null, null,
                null, null,
                Map.of("display-name", text, "display.name", text),
                List.of(), null, true, List.of());
        McpToolDefinition tool = weatherTool(
                List.of(new McpInputDefinition("body", "body", "Body", true, body)),
                List.of(new ParameterBinding("body", ParameterLocation.BODY, "body")));

        assertThrows(GeneratorException.class, () -> renderer.render(context(List.of(tool))));
    }

    @Test
    void rendersExplicitToolSchemaForFormattedNumbers() {
        ApiSchema number = schema(SchemaType.NUMBER, "double", null, null, null, null, null, List.of());
        McpToolDefinition tool = weatherTool(
                List.of(new McpInputDefinition("amount", "amount", "Amount", true, number)),
                List.of(new ParameterBinding("amount", ParameterLocation.QUERY, "amount")));

        var files = renderer.render(context(List.of(tool)));

        assertTrue(files.containsKey(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java"));
    }

    @Test
    void registersLowLevelSpecificationsForEveryTool() {
        ApiSchema text = schema(SchemaType.STRING, null, null, null, null, null, null, List.of());
        McpToolDefinition tool = weatherTool(
                List.of(new McpInputDefinition("city", "city", "City", true, text)),
                List.of(new ParameterBinding("city", ParameterLocation.QUERY, "city")));

        var files = renderer.render(context(List.of(tool)));
        String callbacks = utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java"));
        String tools = utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));

        assertTrue(callbacks.contains("List<McpServerFeatures.SyncToolSpecification>"));
        assertTrue(callbacks.contains("McpToolUtils.toSyncToolSpecification(callback).tool()"));
        assertTrue(callbacks.contains("instanceof ProviderErrorException"));
        assertFalse(callbacks.contains("ToolCallbackProvider"));
        assertFalse(tools.contains("@McpTool("));
    }

    @Test
    void importsCollectionAndDecimalTypesUsedByGeneratedInputs() {
        ApiSchema number = schema(SchemaType.NUMBER, null, null, null, null, null, null, List.of());
        ApiSchema strings = new ApiSchema(
                SchemaType.ARRAY, null, false, List.of(), null, null, null, null,
                null, null, Map.of(), List.of(),
                schema(SchemaType.STRING, null, null, null, null, null, null, List.of()),
                true, List.of());
        McpToolDefinition tool = weatherTool(
                List.of(
                        new McpInputDefinition("amount", "amount", "Amount", true, number),
                        new McpInputDefinition("tags", "tags", "Tags", false, strings)),
                List.of(
                        new ParameterBinding("amount", ParameterLocation.QUERY, "amount"),
                        new ParameterBinding("tags", ParameterLocation.QUERY, "tags")));

        String input = utf8(renderer.render(context(List.of(tool))).get(
                "src/main/java/com/example/weather/generated/model/GetForecastInput.java"));

        assertTrue(input.contains("import java.math.BigDecimal;"));
        assertTrue(input.contains("import java.util.List;"));
        assertTrue(input.contains("BigDecimal amount"));
        assertTrue(input.contains("List<String> tags"));
    }

    @Test
    void emitsNestedRecordsWithCascadedValidation() {
        ApiSchema body = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null, null, null,
                null, null,
                Map.of("city", schema(SchemaType.STRING, null, null, null, 1, 80, null, List.of())),
                List.of("city"), null, true, List.of());
        McpToolDefinition tool = weatherTool(
                List.of(new McpInputDefinition("body", "body", "Request body", true, body)),
                List.of(new ParameterBinding("body", ParameterLocation.BODY, "body")));
        var files = renderer.render(context(List.of(tool)));

        String input = utf8(files.get(
                "src/main/java/com/example/weather/generated/model/GetForecastInput.java"));
        String nested = utf8(files.get(
                "src/main/java/com/example/weather/generated/model/GetForecastBody.java"));
        String toolSource = utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));

        assertTrue(input.contains("@Valid @NotNull GetForecastBody body"));
        assertTrue(nested.contains("@McpToolParam(description = \"city\", required = true)"));
        assertTrue(nested.contains("@NotNull @Size(min = 1, max = 80) String city"));
        assertTrue(toolSource.contains("@Valid @NotNull @McpToolParam"));
    }

    @Test
    void serializesEnumWireValuesForJsonAndNonBodyBindings() {
        ApiSchema conditions = schema(
                SchemaType.STRING, null, null, null, null, null, null,
                List.of("clear", "partly cloudy"));
        McpToolDefinition tool = weatherTool(
                List.of(new McpInputDefinition("condition", "condition", "Condition", true, conditions)),
                List.of(new ParameterBinding("condition", ParameterLocation.QUERY, "condition")));

        String enumSource = utf8(renderer.render(context(List.of(tool))).get(
                "src/main/java/com/example/weather/generated/model/GetForecastConditionValue.java"));

        assertTrue(enumSource.contains("import com.fasterxml.jackson.annotation.JsonProperty;"));
        assertTrue(enumSource.contains("import com.fasterxml.jackson.annotation.JsonValue;"));
        assertTrue(enumSource.contains("@JsonProperty(\"partly cloudy\")"));
        assertTrue(enumSource.contains("PARTLY_CLOUDY(\"partly cloudy\");"));
        assertTrue(enumSource.contains("private final String wireValue;"));
        assertTrue(enumSource.contains("public String toString()"));
        assertTrue(enumSource.contains("return wireValue;"));
        assertTrue(enumSource.contains("fromWireValue(String value)"));
        assertTrue(enumSource.contains("if (value == null) {"));
        assertTrue(enumSource.contains("return null;"));
    }

    @Test
    void keepsNestedEnumConstraintsInExplicitSchemaWithoutApplyingStringValidatorsToTheEnumType() {
        ApiSchema constrainedEnum = schema(
                SchemaType.STRING, null, null, null, 2, 16, "[a-z-]+",
                List.of("brief", "full-detail"));
        ApiSchema details = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null, null, null,
                null, null, Map.of("display name", constrainedEnum), List.of("display name"),
                null, true, List.of());
        McpToolDefinition tool = weatherTool(
                List.of(new McpInputDefinition("details", "details", "Details", true, details)),
                List.of(new ParameterBinding("details", ParameterLocation.BODY, "body")));

        var files = renderer.render(context(List.of(tool)));
        String nested = utf8(files.get(
                "src/main/java/com/example/weather/generated/model/GetForecastDetails.java"));
        String callbacks = utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java"));

        assertTrue(nested.contains("@JsonProperty(\"display name\")"));
        assertTrue(nested.contains("@NotNull GetForecastDetailsDisplayNameValue displayName"));
        assertFalse(nested.contains("@Size"));
        assertFalse(nested.contains("@Pattern"));
        assertTrue(callbacks.contains("\\\"enum\\\":[\\\"brief\\\",\\\"full-detail\\\"]"));
        assertTrue(callbacks.contains("\\\"minLength\\\":2"));
        assertTrue(callbacks.contains("\\\"maxLength\\\":16"));
        assertTrue(callbacks.contains("\\\"pattern\\\":\\\"[a-z-]+\\\""));
    }

    @Test
    void rendersExplicitSchemaCallbacksInASeparateConfigurationWithTheProxiedToolBean() {
        ApiSchema mode = schema(SchemaType.STRING, null, null, null, null, null, null,
                List.of("brief", "full-detail"));
        McpToolDefinition tool = weatherTool(
                List.of(new McpInputDefinition("mode", "mode", "Mode", false, mode)),
                List.of(new ParameterBinding("mode", ParameterLocation.QUERY, "mode")));

        var files = renderer.render(context(List.of(tool)));
        String tools = utf8(files.get("src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));
        String callbacks = utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java"));

        assertFalse(tools.contains("generatedToolCallbacks"));
        assertTrue(callbacks.contains("@Configuration"));
        assertTrue(callbacks.contains("WeatherMcpTools tools"));
        assertTrue(callbacks.contains(".toolObject(tools)"));
        assertFalse(callbacks.contains(".toolObject(this)"));
    }

    @Test
    void keepsNestedTypeNamesAlignedWhenOperationIdContainsInput() {
        ApiSchema body = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null, null, null,
                null, null, Map.of(), List.of(), null, true, List.of());
        McpToolDefinition tool = new McpToolDefinition(
                "inputForecast", "kma_weather_input_forecast", "Input forecast",
                List.of(new McpInputDefinition("body", "body", "Body", true, body)),
                new HttpExecutionDefinition(
                        HttpMethod.POST, URI.create("https://api.example.test"), "/forecast",
                        List.of(new ParameterBinding("body", ParameterLocation.BODY, "body"))),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);
        var files = renderer.render(context(List.of(tool)));

        assertTrue(files.containsKey(
                "src/main/java/com/example/weather/generated/model/InputForecastBody.java"));
        assertTrue(utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"))
                .contains("InputForecastBody body"));
    }

    @Test
    void rendersByteIdenticalSourcesWhenToolOrderChanges() {
        McpToolDefinition first = weatherTool();
        McpToolDefinition second = new McpToolDefinition(
                "getAlerts", "kma_weather_get_alerts", "Get alerts", List.of(),
                new HttpExecutionDefinition(HttpMethod.GET, URI.create("https://api.example.test"), "/alerts", List.of()),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);

        var firstOrder = renderer.render(context(List.of(first, second)));
        var secondOrder = renderer.render(context(List.of(second, first)));

        assertEquals(new TreeSet<>(firstOrder.keySet()), new TreeSet<>(secondOrder.keySet()));
        for (String path : firstOrder.keySet()) {
            assertArrayEquals(firstOrder.get(path), secondOrder.get(path), path);
        }
    }

    @Test
    void rejectsUnsafeOperationPathsBeforeRenderingMetadata() {
        McpToolDefinition unsafe = new McpToolDefinition(
                "getForecast", "kma_weather_get_forecast", "Get forecast", List.of(),
                new HttpExecutionDefinition(HttpMethod.GET, URI.create("https://api.example.test"),
                        "/forecast\nInjected: true", List.of()),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);

        assertThrows(GeneratorException.class, () -> renderer.render(context(List.of(unsafe))));
    }

    static GenerationContext contextWithWeatherTool() {
        return context(List.of(weatherTool()));
    }

    static GenerationContext context(List<McpToolDefinition> tools) {
        var coordinates = new GenerationRequest.ProjectCoordinates(
                "com.example", "weather-mcp-server", "com.example.weather");
        var request = new GenerationRequest(
                coordinates, "kma", "weather", CompatibilityProfile.p0().id(),
                GenerationRequest.ValidationLevel.MCP_PROTOCOL,
                new GenerationRequest.ValidationConfiguration(new GenerationRequest.ToolCallValidation(
                        "getForecast", Map.of("nx", 60, "ny", 127))),
                List.of());
        return new GenerationContext(null, tools, request, CompatibilityProfile.p0(), new byte[0]);
    }

    static McpToolDefinition weatherTool() {
        ApiSchema integer = schema(SchemaType.INTEGER, "int32", BigDecimal.ZERO,
                BigDecimal.valueOf(1000), null, null, null, List.of());
        return weatherTool(
                List.of(
                        new McpInputDefinition("nx", "nx", "Grid x coordinate", true, integer),
                        new McpInputDefinition("ny", "ny", "Grid y coordinate", true, integer)),
                List.of(
                        new ParameterBinding("nx", ParameterLocation.QUERY, "nx"),
                        new ParameterBinding("ny", ParameterLocation.QUERY, "ny")));
    }

    static McpToolDefinition weatherTool(ResponseNormalizationPolicy normalization) {
        McpToolDefinition tool = weatherTool();
        HttpExecutionDefinition execution = tool.execution();
        return new McpToolDefinition(
                tool.operationId(),
                tool.name(),
                tool.description(),
                tool.inputs(),
                new HttpExecutionDefinition(
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

    static ResponseNormalizationPolicy normalization() {
        return new ResponseNormalizationPolicy(
                "/response/body/items",
                "/response/header/code",
                List.of("00", new BigDecimal("1.50"), true),
                "/response/header/message",
                "/response/body/totalCount");
    }

    static McpToolDefinition weatherTool(
            List<McpInputDefinition> inputs,
            List<ParameterBinding> bindings) {
        return new McpToolDefinition(
                "getForecast",
                "kma_weather_get_forecast",
                "Get the public weather forecast for a grid location.",
                inputs,
                new HttpExecutionDefinition(
                        HttpMethod.GET,
                        URI.create("https://api.example.test"),
                        "/forecast",
                        bindings),
                List.of(new SecretBinding(
                        "KMA_SERVICE_KEY", "service-key", ParameterLocation.QUERY, "serviceKey", true)),
                McpToolDefinition.OutputKind.GENERIC_JSON);
    }

    static ApiSchema schema(
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

    private byte[] golden(String resource) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/golden/" + resource)) {
            if (input == null) {
                throw new IOException("Missing golden resource: " + resource);
            }
            return input.readAllBytes();
        }
    }

    private String utf8(byte[] value) {
        return new String(value, UTF_8);
    }
}
