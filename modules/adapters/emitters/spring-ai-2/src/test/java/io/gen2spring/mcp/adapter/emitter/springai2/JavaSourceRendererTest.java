package io.gen2spring.mcp.adapter.emitter.springai2;

import io.gen2spring.mcp.domain.tool.OutputKind;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.application.usecase.GenerationContext;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.CompositionKind;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaComposition;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.ToolInput;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import io.gen2spring.mcp.domain.tool.SecretBinding;
import io.gen2spring.mcp.domain.tool.ToolOutput;
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
        assertFalse(files.keySet().stream().anyMatch(path -> path.contains("/GetForecastResult")));
    }

    @Test
    void rendersTypedOutputRecordsAndToolReturnType() {
        var files = renderer.render(context(List.of(typedWeatherTool())));

        String result = utf8(files.get(
                "src/main/java/com/example/weather/generated/model/GetForecastResult.java"));
        String data = utf8(files.get(
                "src/main/java/com/example/weather/generated/model/GetForecastResultData.java"));
        String condition = utf8(files.get(
                "src/main/java/com/example/weather/generated/model/GetForecastResultDataConditionsItemValue.java"));
        String tool = utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));

        assertTrue(result.contains("public record GetForecastResult("), result);
        assertTrue(result.contains("GetForecastResultData data"), result);
        assertTrue(result.contains("GetForecastResultPage page"), result);
        assertTrue(result.contains("GetForecastResultProvider provider"), result);
        assertTrue(data.contains("import com.fasterxml.jackson.annotation.JsonProperty;"), data);
        assertTrue(data.contains("@JsonProperty(\"city-name\") String cityName"), data);
        assertTrue(data.contains("@JsonProperty(\"display name\") String displayName"), data);
        assertTrue(data.contains("List<GetForecastResultDataConditionsItemValue> conditions"), data);
        assertTrue(data.contains("BigDecimal temperature"), data);
        assertFalse(data.contains("jakarta.validation"), data);
        assertFalse(data.contains("@DecimalMin"), data);
        assertTrue(condition.contains("@JsonProperty(\"partly-cloudy\")"), condition);
        assertTrue(condition.contains("import com.fasterxml.jackson.annotation.JsonCreator;"), condition);
        assertTrue(condition.contains("import com.fasterxml.jackson.annotation.JsonValue;"), condition);
        assertTrue(condition.contains("fromWireValue(String value)"), condition);
        assertTrue(tool.contains("public GetForecastResult getForecast("), tool);
        assertTrue(tool.contains("input.toArguments(), GetForecastResult.class"), tool);
    }

    @Test
    void rejectsTypedOutputPropertyNamesThatCollideAfterJavaNormalization() {
        ApiSchema collision = objectSchema(Map.of(
                "postal-code", textSchema(), "postal_code", textSchema()), List.of());
        ToolDefinition base = weatherTool();
        ToolDefinition tool = new ToolDefinition(
                base.operationId(), base.name(), base.description(), base.inputs(), base.execution(),
                base.secretBindings(), new ToolOutput(
                        OutputKind.TYPED_DTO, collision, collision));

        GeneratorException failure = assertThrows(
                GeneratorException.class, () -> renderer.render(context(List.of(tool))));

        assertEquals(io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED, failure.code());
    }

    @Test
    void rendersRetryPolicyMetadataAndBoundedRuntimeSeams() {
        ToolDefinition base = weatherTool();
        var execution = base.execution();
        RetryPolicy retry = new RetryPolicy(List.of(503, 429), true, 2, 100, 1_000, true);
        ToolDefinition retried = new ToolDefinition(
                base.operationId(), base.name(), base.description(), base.inputs(),
                new HttpExecution(
                        execution.method(), execution.baseUrl(), execution.path(), execution.bindings(),
                        execution.objectRequestBody(), execution.requestBodyRequired(),
                        execution.responseNormalization(), retry),
                base.secretBindings(), base.output());

        var files = renderer.render(context(List.of(retried)));
        String metadata = utf8(files.get(
                "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java"));
        String operation = utf8(files.get(
                "src/main/java/com/example/weather/runtime/OperationDefinition.java"));
        String retrySource = utf8(files.get(
                "src/main/java/com/example/weather/runtime/RetryPolicy.java"));
        String executor = utf8(files.get(
                "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java"));

        assertTrue(metadata.contains("new RetryPolicy(List.of(429, 503), true, 2, 100L, 1000L, true)"), metadata);
        assertTrue(operation.contains("RetryPolicy retryPolicy"), operation);
        assertTrue(retrySource.contains("interface RetryClock"), retrySource);
        assertTrue(retrySource.contains("interface RetrySleeper"), retrySource);
        assertTrue(executor.contains("sleepBeforeRetry"), executor);
        assertTrue(executor.contains("policy.respectRetryAfter()"), executor);
    }

    @Test
    void rendersPaginationMetadataAndAccumulatorWithoutAVisibleCursorBinding() {
        ToolDefinition base = weatherTool();
        ApiSchema text = textSchema();
        ApiSchema nullableText = new ApiSchema(
                SchemaType.STRING, null, true, List.of(), null, null, null, null, null,
                null, Map.of(), List.of(), null, true, List.of());
        ApiSchema response = objectSchema(Map.of(
                "items", new ApiSchema(
                        SchemaType.ARRAY, null, false, List.of(), null, null, null, null, null,
                        null, Map.of(), List.of(), text, true, List.of()),
                "next", nullableText), List.of("items"));
        PaginationPolicy pagination = new PaginationPolicy("cursor", "first", "/items", "/next", 4, 100);
        ToolDefinition paginated = new ToolDefinition(
                base.operationId(), base.name(), base.description(), base.inputs(),
                new HttpExecution(
                        base.execution().method(), base.execution().baseUrl(), base.execution().path(),
                        base.execution().bindings(), false, false, null, null, pagination),
                base.secretBindings(), new ToolOutput(
                        OutputKind.GENERIC_JSON, response, null));

        var files = renderer.render(context(List.of(paginated)));
        String metadata = utf8(files.get(
                "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java"));
        String operation = utf8(files.get(
                "src/main/java/com/example/weather/runtime/OperationDefinition.java"));
        String executor = utf8(files.get(
                "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java"));

        assertTrue(files.containsKey("src/main/java/com/example/weather/runtime/PaginationPolicy.java"));
        assertTrue(files.containsKey("src/main/java/com/example/weather/runtime/PageAccumulator.java"));
        String accumulator = utf8(files.get(
                "src/main/java/com/example/weather/runtime/PageAccumulator.java"));
        assertTrue(accumulator.contains("tokens.get(tokens.size() - 1)"));
        assertFalse(accumulator.contains("tokens.getLast()"));
        assertTrue(metadata.contains("new PaginationPolicy(\"cursor\", \"first\", \"/items\", \"/next\", 4, 100)"));
        assertTrue(operation.contains("PaginationPolicy paginationPolicy"), operation);
        assertTrue(executor.contains("awaitPaginated"), executor);
        assertFalse(metadata.contains("ParameterLocation.QUERY, \"cursor\""), metadata);
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
                "src/main/java/com/example/weather/runtime/RuntimeTelemetry.java",
                "src/main/java/com/example/weather/runtime/SchemaValueValidator.java",
                "src/main/java/com/example/weather/runtime/SecretBinding.java",
                "src/main/java/com/example/weather/runtime/ToolArgumentContext.java",
                "src/test/java/com/example/weather/application/GeneratedJavaRuntimeTest.java",
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
        assertTrue(runtime.contains("jsonMapper.writeValueAsBytes(requestBody.value())"));
        assertTrue(runtime.contains("RequestBodyValue(boolean present, Object value)"));
        assertTrue(runtime.contains("tools.jackson.databind.JsonNode"));
        assertTrue(runtime.contains("tools.jackson.databind.json.JsonMapper"));

        assertTelemetryExecutionContract(files, runtime);

        String contextTest = utf8(files.get(
                "src/test/java/com/example/weather/application/WeatherMcpApplicationTest.java"));
        assertTrue(contextTest.contains("webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT"));
        assertTrue(contextTest.contains("void contextLoads()"));
        assertTrue(contextTest.contains(
                "new OperationDefinition(\n                        \"getForecast\", \"GET\", \"/slow\""),
                contextTest);
    }

    private void assertTelemetryExecutionContract(Map<String, byte[]> files, String runtime) {
        String callbacks = utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java"));
        assertTrue(callbacks.contains("RuntimeTelemetry runtimeTelemetry"));
        assertTrue(callbacks.contains("runtimeTelemetry.startToolCall(tool.name(), operationId)"));
        assertTrue(callbacks.contains("RuntimeTelemetry.Outcome.SUCCESS"));
        assertTrue(callbacks.contains("RuntimeTelemetry.ErrorCategory.ARGUMENT_CONVERSION"));
        assertTrue(callbacks.contains("RuntimeTelemetry.ErrorCategory.RESULT_CONVERSION"));
        assertTrue(callbacks.contains("RuntimeTelemetry.ErrorCategory.TOOL_EXECUTION"));
        assertTrue(callbacks.contains("RuntimeTelemetry.ErrorCategory.UNEXPECTED_RUNTIME"));
        assertTrue(callbacks.contains("RuntimeTelemetry.Outcome.FATAL"));

        assertTrue(runtime.contains("ContextExecutorService.wrap(rawRequestExecutor)"));
        assertTrue(runtime.contains("builder.requestFactory(requestFactory)"));
        assertTrue(runtime.contains(".observationRegistry(ObservationRegistry.NOOP)"));
        assertTrue(runtime.contains("runtimeTelemetry.registerExecutor(rawRequestExecutor)"));
        assertTrue(runtime.contains("runtimeTelemetry.startProviderCall(operation.operationId(), operation.method())"));
        assertTrue(runtime.contains("new ProviderAttempt("));
        assertTrue(runtime.contains("completeProviderCall(providerCall"));
        assertTrue(runtime.contains("removePropagationHeaders(headers)"));
        assertTrue(runtime.contains("runtimeTelemetry.currentTraceparent()"));
        assertTrue(runtime.contains("headers.set(\"traceparent\", traceparent)"));
        assertTrue(runtime.contains("headers.forEach((name, ignored) ->"));
        assertTrue(runtime.contains("namesToRemove.forEach(headers::remove)"));

        String response = utf8(files.get(
                "src/main/java/com/example/weather/runtime/ResponseNormalizer.java"));
        assertTrue(response.contains("RuntimeTelemetry runtimeTelemetry"));
        assertTrue(response.contains("runtimeTelemetry.currentTraceIdOrFallback()"));
        assertTrue(response.contains("new ProviderError(envelope, category, status)"));
    }

    @Test
    void emitsTheCanonicalRuntimeTelemetryContract() {
        String telemetry = utf8(renderer.render(contextWithWeatherTool()).get(
                "src/main/java/com/example/weather/runtime/RuntimeTelemetry.java"));

        for (String literal : List.of(
                "gen2spring.runtime.mcp.tool.call",
                "gen2spring.runtime.provider.request",
                "gen2spring.runtime.provider.response.bytes",
                "gen2spring.runtime.provider.executor.active",
                "gen2spring.runtime.provider.executor.queued",
                "target.profile", "outcome", "error.category", "http.status.class",
                "gen2spring.tool.name", "gen2spring.operation.id",
                "http.request.method", "http.response.status_code",
                "success", "expected_error", "internal_error", "fatal",
                "provider_business", "upstream_client", "upstream_server",
                "upstream_timeout", "upstream_unavailable", "upstream_protocol",
                "local_resource", "argument_conversion", "result_conversion",
                "tool_execution", "unexpected_runtime",
                "2xx", "4xx", "5xx", "other", "none",
                "spring-ai-2.0-java21-mvc-streamable",
                "Set.of(\"getForecast\")", "Set.of(\"kma_weather_get_forecast\")",
                "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}",
                "[a-z][a-z0-9_]{0,63}",
                "currentTraceIdOrFallback()", "currentTraceparent()",
                "new SecureRandom()", "HexFormat.of().formatHex")) {
            assertTrue(telemetry.contains(literal), literal);
        }
        assertFalse(telemetry.contains("tool.name\", targetProfileId"));
        assertFalse(telemetry.contains("operation.id\", targetProfileId"));
        assertFalse(telemetry.contains("Throwable failure"));
        assertFalse(telemetry.contains("failure.getMessage()"));
        assertFalse(telemetry.contains("application"));
    }

    @Test
    void emitsTheTargetJavaRuntimeFeatureAssertionForBothSupportedProfiles() {
        assertRuntimeFeature(profile(17), 17);
        assertRuntimeFeature(profile(21), 21);
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
        var bodyInput = new ToolInput("body", "body", "Postal request", true, body);
        ToolDefinition tool = weatherTool(List.of(bodyInput), List.of(
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
    void mapsNullableAndArrayMinimumsToCompatibleValidation() {
        ApiSchema nullableLabel = new ApiSchema(
                SchemaType.STRING, null, true, List.of(), null, null, null, null,
                null, null, Map.of(), List.of(), null, null, true, List.of());
        ApiSchema values = new ApiSchema(
                SchemaType.ARRAY, null, false, List.of(), null, null, null, null,
                null, null, Map.of(), List.of(), nullableLabel, 1, true, List.of());
        ToolDefinition tool = weatherTool(
                List.of(
                        new ToolInput("label", "label", "Nullable label", true, nullableLabel),
                        new ToolInput("values", "values", "Values", true, values)),
                List.of(
                        new ParameterBinding("label", ParameterLocation.BODY, "label"),
                        new ParameterBinding("values", ParameterLocation.BODY, "values")));

        var files = renderer.render(context(List.of(tool)));
        String input = utf8(files.get(
                "src/main/java/com/example/weather/generated/model/GetForecastInput.java"));
        String callbacks = utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java"));
        String argumentContext = utf8(files.get(
                "src/main/java/com/example/weather/runtime/ToolArgumentContext.java"));

        assertFalse(input.contains("@NotNull String label"));
        assertTrue(input.contains("@NotNull @Size(min = 1, max = 2147483647) List<String> values"));
        assertTrue(input.contains("ToolArgumentContext.active()"), input);
        assertTrue(input.contains("ToolArgumentContext.contains(\"label\")"), input);
        assertTrue(input.contains("ToolArgumentContext.value(\"label\")"), input);
        assertTrue(callbacks.contains(
                "Map<String, Object> rawArguments ="),
                callbacks);
        assertTrue(callbacks.contains(
                "request.arguments() == null ? Map.of() : request.arguments()"), callbacks);
        assertTrue(callbacks.contains("ToolArgumentContext.open(rawArguments)"), callbacks);
        assertTrue(callbacks.contains("schemaValues.validate(parsedInputSchema, rawArguments)"), callbacks);
        assertTrue(files.containsKey("src/main/java/com/example/weather/runtime/SchemaValueValidator.java"));
        assertTrue(argumentContext.contains("ThreadLocal<Map<String, Object>>"), argumentContext);
        assertTrue(argumentContext.contains("Collections.unmodifiableMap(new LinkedHashMap<>(arguments))"),
                argumentContext);
    }

    @Test
    void rendersComposedJsonNodesAndBoundedUniqueValidation() {
        ApiSchema string = textSchema();
        ApiSchema integer = schema(SchemaType.INTEGER, "int32", null, null, null, null, null, List.of());
        ApiSchema choice = new ApiSchema(
                SchemaType.COMPOSED, null, false, List.of(), null, null, null, null, null, null,
                Map.of(), List.of(), null, null, null, false,
                new SchemaComposition(CompositionKind.ONE_OF, List.of(string, integer)), true, List.of());
        ApiSchema values = new ApiSchema(
                SchemaType.ARRAY, null, false, List.of(), null, null, null, null, null, null,
                Map.of(), List.of(), choice, 1, 4, true, null, true, List.of());
        ToolDefinition tool = weatherTool(
                List.of(
                        new ToolInput("choice", "choice", "Choice", true, choice),
                        new ToolInput("values", "values", "Values", true, values)),
                List.of(
                        new ParameterBinding("choice", ParameterLocation.BODY, "choice"),
                        new ParameterBinding("values", ParameterLocation.BODY, "values")));

        var files = renderer.render(context(List.of(tool)));
        String input = utf8(files.get(
                "src/main/java/com/example/weather/generated/model/GetForecastInput.java"));
        String callbacks = utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java"));
        String validator = utf8(files.get(
                "src/main/java/com/example/weather/runtime/SchemaValueValidator.java"));

        assertTrue(input.contains("tools.jackson.databind.JsonNode choice"), input);
        assertTrue(input.contains("@Size(min = 1, max = 4)"), input);
        assertTrue(callbacks.contains("\\\"oneOf\\\""), callbacks);
        assertTrue(callbacks.contains("\\\"maxItems\\\":4,\\\"uniqueItems\\\":true"), callbacks);
        assertTrue(callbacks.contains("tools.jackson.databind.JsonNode.class"), callbacks);
        assertTrue(validator.contains("matches != 1"), validator);
        assertTrue(validator.contains("CanonicalValue"), validator);
        assertTrue(validator.contains("BudgetedCharSequence"), validator);
        assertTrue(validator.contains("MAX_PATTERN_CHARACTER_ACCESSES"), validator);
        assertFalse(validator.contains("expression.contains(\"){\")"), validator);
        assertFalse(validator.contains("hasSafePatternShape"), validator);
    }

    @Test
    void rendersJavaSafeFlatMcpKeysWhilePreservingSpacedUpstreamJsonNames() {
        ApiSchema text = schema(SchemaType.STRING, null, null, null, null, null, null, List.of());
        ToolDefinition tool = weatherTool(
                List.of(new ToolInput("displayName", "display name", "Display name", true, text)),
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
            ToolDefinition tool = weatherTool(
                    List.of(new ToolInput("value", jsonName, "Value", true, text)),
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
        ToolDefinition tool = weatherTool(
                List.of(new ToolInput("body", "body", "Body", true, body)),
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
        ToolDefinition tool = weatherTool(
                List.of(new ToolInput("body", "body", "Body", true, body)),
                List.of(new ParameterBinding("body", ParameterLocation.BODY, "body")));

        assertThrows(GeneratorException.class, () -> renderer.render(context(List.of(tool))));
    }

    @Test
    void rendersExplicitToolSchemaForFormattedNumbers() {
        ApiSchema number = schema(SchemaType.NUMBER, "double", null, null, null, null, null, List.of());
        ToolDefinition tool = weatherTool(
                List.of(new ToolInput("amount", "amount", "Amount", true, number)),
                List.of(new ParameterBinding("amount", ParameterLocation.QUERY, "amount")));

        var files = renderer.render(context(List.of(tool)));

        assertTrue(files.containsKey(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java"));
    }

    @Test
    void registersLowLevelSpecificationsForEveryTool() {
        ApiSchema text = schema(SchemaType.STRING, null, null, null, null, null, null, List.of());
        ToolDefinition tool = weatherTool(
                List.of(new ToolInput("city", "city", "City", true, text)),
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
    void rendersSafeDiagnosticsForUnexpectedFailuresAndRethrowsFatalErrors() {
        String callbacks = utf8(renderer.render(contextWithWeatherTool()).get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java"));

        assertTrue(callbacks.contains("LoggerFactory.getLogger(WeatherMcpToolCallbacks.class)"));
        assertTrue(callbacks.contains(
                "generated_tool_adapter_failure tool={} exception={} cause={}"));
        assertTrue(callbacks.contains("failure.getClass().getName()"));
        assertTrue(callbacks.contains("cause.getClass().getName()"));
        assertTrue(callbacks.contains("failure.getCause() instanceof Error fatal"));
        assertTrue(callbacks.contains("throw fatal;"));
        assertFalse(callbacks.contains("logger.error(\"generated_tool_adapter_failure\", failure)"));
        assertFalse(callbacks.contains("failure.getMessage()"));
        assertFalse(callbacks.contains("failure.toString()"));
    }

    @Test
    void importsCollectionAndDecimalTypesUsedByGeneratedInputs() {
        ApiSchema number = schema(SchemaType.NUMBER, null, null, null, null, null, null, List.of());
        ApiSchema strings = new ApiSchema(
                SchemaType.ARRAY, null, false, List.of(), null, null, null, null,
                null, null, Map.of(), List.of(),
                schema(SchemaType.STRING, null, null, null, null, null, null, List.of()),
                true, List.of());
        ToolDefinition tool = weatherTool(
                List.of(
                        new ToolInput("amount", "amount", "Amount", true, number),
                        new ToolInput("tags", "tags", "Tags", false, strings)),
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
        ToolDefinition tool = weatherTool(
                List.of(new ToolInput("body", "body", "Request body", true, body)),
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
        ToolDefinition tool = weatherTool(
                List.of(new ToolInput("condition", "condition", "Condition", true, conditions)),
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
        ToolDefinition tool = weatherTool(
                List.of(new ToolInput("details", "details", "Details", true, details)),
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
        ToolDefinition tool = weatherTool(
                List.of(new ToolInput("mode", "mode", "Mode", false, mode)),
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
        ToolDefinition tool = new ToolDefinition(
                "inputForecast", "kma_weather_input_forecast", "Input forecast",
                List.of(new ToolInput("body", "body", "Body", true, body)),
                new HttpExecution(
                        HttpMethod.POST, URI.create("https://api.example.test"), "/forecast",
                        List.of(new ParameterBinding("body", ParameterLocation.BODY, "body"))),
                List.of(), OutputKind.GENERIC_JSON);
        var files = renderer.render(context(List.of(tool)));

        assertTrue(files.containsKey(
                "src/main/java/com/example/weather/generated/model/InputForecastBody.java"));
        assertTrue(utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"))
                .contains("InputForecastBody body"));
    }

    @Test
    void rendersByteIdenticalSourcesWhenToolOrderChanges() {
        ToolDefinition first = weatherTool();
        ToolDefinition second = new ToolDefinition(
                "getAlerts", "kma_weather_get_alerts", "Get alerts", List.of(),
                new HttpExecution(HttpMethod.GET, URI.create("https://api.example.test"), "/alerts", List.of()),
                List.of(), OutputKind.GENERIC_JSON);

        var firstOrder = renderer.render(context(List.of(first, second)));
        var secondOrder = renderer.render(context(List.of(second, first)));

        assertEquals(new TreeSet<>(firstOrder.keySet()), new TreeSet<>(secondOrder.keySet()));
        for (String path : firstOrder.keySet()) {
            assertArrayEquals(firstOrder.get(path), secondOrder.get(path), path);
        }
    }

    @Test
    void rejectsUnsafeOperationPathsBeforeRenderingMetadata() {
        ToolDefinition unsafe = new ToolDefinition(
                "getForecast", "kma_weather_get_forecast", "Get forecast", List.of(),
                new HttpExecution(HttpMethod.GET, URI.create("https://api.example.test"),
                        "/forecast\nInjected: true", List.of()),
                List.of(), OutputKind.GENERIC_JSON);

        assertThrows(GeneratorException.class, () -> renderer.render(context(List.of(unsafe))));
    }

    static GenerationContext contextWithWeatherTool() {
        return context(List.of(weatherTool()));
    }

    static GenerationContext contextWithWeatherTool(CompatibilityProfile profile) {
        return context(profile, List.of(weatherTool()));
    }

    static GenerationContext context(List<ToolDefinition> tools) {
        return context(CompatibilityProfile.p0(), tools);
    }

    static GenerationContext context(CompatibilityProfile profile, List<ToolDefinition> tools) {
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

    private void assertRuntimeFeature(CompatibilityProfile profile, int expectedFeature) {
        var files = new JavaSourceRenderer(profile).render(contextWithWeatherTool(profile));
        String path = "src/test/java/com/example/weather/application/GeneratedJavaRuntimeTest.java";

        assertTrue(files.containsKey(path), profile.id());
        String source = utf8(files.get(path));
        assertTrue(source.contains("assertEquals(" + expectedFeature + ", Runtime.version().feature())"), source);
        String telemetry = utf8(files.get(
                "src/main/java/com/example/weather/runtime/RuntimeTelemetry.java"));
        assertTrue(telemetry.contains("TARGET_PROFILE_ID = \"" + profile.id() + "\""), telemetry);
    }

    private static CompatibilityProfile profile(int javaVersion) {
        return io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java" + javaVersion + "-mvc-streamable")
                .orElseThrow();
    }

    static ToolDefinition weatherTool() {
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

    private static ToolDefinition typedWeatherTool() {
        ToolDefinition base = weatherTool();
        ApiSchema result = typedResultSchema();
        return new ToolDefinition(
                base.operationId(), base.name(), base.description(), base.inputs(), base.execution(),
                base.secretBindings(), new ToolOutput(
                        OutputKind.TYPED_DTO, result, result));
    }

    private static ApiSchema typedResultSchema() {
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

    private static ApiSchema objectSchema(Map<String, ApiSchema> properties, List<String> required) {
        return new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, properties, required, null, true, List.of());
    }

    private static ApiSchema textSchema() {
        return schema(SchemaType.STRING, null, null, null, null, null, null, List.of());
    }

    static ToolDefinition weatherTool(ResponseNormalizationPolicy normalization) {
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

    static ResponseNormalizationPolicy normalization() {
        return new ResponseNormalizationPolicy(
                "/response/body/items",
                "/response/header/code",
                List.of("00", new BigDecimal("1.50"), true),
                "/response/header/message",
                "/response/body/totalCount");
    }

    static ToolDefinition weatherTool(
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
