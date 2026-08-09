package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.ARRAY;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.INTEGER;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.NUMBER;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.OBJECT;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.STRING;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.VALIDATION_ARGUMENT_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.config.GenerationRequest.ToolCallValidation;
import io.gen2spring.mcp.domain.config.GenerationRequest.ValidationConfiguration;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedToolCall;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.McpInputDefinition;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpectedToolCallFactoryTest {
    private final ExpectedToolCallFactory factory = new ExpectedToolCallFactory();

    @Test
    void resolvesTheFinalToolAndValidatesRecursivelyCompatibleArguments() {
        ExpectedToolCall result = factory.create(List.of(weatherTool()), validation("getForecast", Map.of(
                "stationId", "STN01",
                "days", BigInteger.valueOf(3),
                "mode", "brief",
                "tags", List.of("public"),
                "location", Map.of(
                        "latitude", new BigDecimal("37.5"),
                        "longitude", new BigDecimal("127.0")))));

        assertEquals("kma_weather_get_forecast", result.tool().name());
        assertEquals(3, result.arguments().get("days"));
    }

    @Test
    void normalizesGeneratedIntegerTypesRecursivelyAtTheirExactBoundaries() {
        Map<String, Object> arguments = new LinkedHashMap<>(validArguments());
        arguments.put("defaultInteger", new BigDecimal(Integer.MIN_VALUE + ".0"));
        arguments.put("int32Integer", BigInteger.valueOf(Integer.MAX_VALUE));
        arguments.put("int64Integer", new BigDecimal(Long.MAX_VALUE + ".0"));
        arguments.put("integerArray", List.of(
                new BigDecimal(Long.MIN_VALUE + ".0"),
                BigInteger.valueOf(Long.MAX_VALUE)));
        arguments.put("integerObject", Map.of(
                "count", new BigDecimal("3.0"),
                "sequence", List.of(
                        new BigDecimal(Integer.MIN_VALUE + ".0"),
                        BigInteger.valueOf(Integer.MAX_VALUE))));

        ExpectedToolCall result = factory.create(
                List.of(weatherTool()), validation("getForecast", arguments));

        assertEquals(Integer.MIN_VALUE, result.arguments().get("defaultInteger"));
        assertEquals(Integer.MAX_VALUE, result.arguments().get("int32Integer"));
        assertEquals(Long.MAX_VALUE, result.arguments().get("int64Integer"));
        assertEquals(List.of(Long.MIN_VALUE, Long.MAX_VALUE), result.arguments().get("integerArray"));
        assertEquals(Map.of(
                "count", 3,
                "sequence", List.of(Integer.MIN_VALUE, Integer.MAX_VALUE)),
                result.arguments().get("integerObject"));
    }

    @Test
    void preservesDeclaredIntegerBoundsWhileNormalizingIntegralDecimals() {
        for (BigDecimal boundary : List.of(new BigDecimal("1.0"), new BigDecimal("5.0"))) {
            ExpectedToolCall result = factory.create(
                    List.of(weatherTool()),
                    validation("getForecast", arguments("days", boundary)));

            assertEquals(boundary.intValueExact(), result.arguments().get("days"));
        }
        assertInvalid("days", "0.0", validation(
                "getForecast", arguments("days", new BigDecimal("0.0"))));
        assertInvalid("days", "6.0", validation(
                "getForecast", arguments("days", new BigDecimal("6.0"))));
    }

    @Test
    void rejectsGeneratedJavaIntegerRangeOverflowRecursively() {
        assertInvalid("defaultInteger", "2147483648", validation(
                "getForecast", arguments("defaultInteger", BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE))));
        assertInvalid("int32Integer", "-2147483649", validation(
                "getForecast", arguments("int32Integer", BigInteger.valueOf(Integer.MIN_VALUE).subtract(BigInteger.ONE))));
        assertInvalid("int64Integer", "9223372036854775808", validation(
                "getForecast", arguments("int64Integer", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))));
        assertInvalid("integerArray", "-9223372036854775809", validation(
                "getForecast", arguments("integerArray", List.of(
                        BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)))));
        assertInvalid("integerObject", "2147483648", validation(
                "getForecast", arguments("integerObject", Map.of(
                        "count", BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE),
                        "sequence", List.of(BigInteger.ZERO)))));
        assertInvalid("integerObject", "2147483648", validation(
                "getForecast", arguments("integerObject", Map.of(
                        "count", BigInteger.ZERO,
                        "sequence", List.of(BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE))))));
    }

    @Test
    void defensivelyCopiesExpectedToolCallArguments() {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("latitude", new BigDecimal("37.5"));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("location", location);

        ExpectedToolCall expected = new ExpectedToolCall(weatherTool(), arguments);
        location.put("longitude", new BigDecimal("127.0"));
        arguments.put("days", BigInteger.ONE);

        assertEquals(Map.of("latitude", new BigDecimal("37.5")), expected.arguments().get("location"));
        assertFalse(expected.arguments().containsKey("days"));
        assertThrows(UnsupportedOperationException.class, () -> expected.arguments().put("days", BigInteger.ONE));
        assertTrue(expected.arguments().get("location") instanceof Map<?, ?>);
    }

    @Test
    void rejectsAnOperationThatIsNotInTheFinalToolModel() {
        assertInvalid("operationId", "disabledGetForecast", validation("disabledGetForecast", validArguments()));
    }

    @Test
    void rejectsAnUnknownArgumentWithoutLeakingItsValue() {
        assertInvalid("unknown", "confidential-value", validation("getForecast", Map.of(
                "stationId", "STN01",
                "days", BigInteger.valueOf(3),
                "mode", "brief",
                "tags", List.of("public"),
                "location", validLocation(),
                "unknown", "confidential-value")));
    }

    @Test
    void rejectsAMissingRequiredArgument() {
        assertInvalid("stationId", "missing-station", validation("getForecast", Map.of(
                "days", BigInteger.valueOf(3),
                "mode", "brief",
                "tags", List.of("public"),
                "location", validLocation())));
    }

    @Test
    void rejectsAWrongPrimitiveType() {
        assertInvalid("stationId", "123", validation("getForecast", arguments(
                "stationId", BigInteger.valueOf(123))));
    }

    @Test
    void rejectsANonIntegralInteger() {
        assertInvalid("days", "3.5", validation("getForecast", arguments(
                "days", new BigDecimal("3.5"))));
    }

    @Test
    void rejectsAnEnumMismatch() {
        assertInvalid("mode", "classified", validation("getForecast", arguments("mode", "classified")));
    }

    @Test
    void rejectsANumericBoundViolation() {
        assertInvalid("days", "6", validation("getForecast", arguments("days", BigInteger.valueOf(6))));
    }

    @Test
    void rejectsAStringLengthViolation() {
        assertInvalid("stationId", "S", validation("getForecast", arguments("stationId", "S")));
    }

    @Test
    void rejectsAStringPatternMismatch() {
        assertInvalid("stationId", "lowercase", validation("getForecast", arguments("stationId", "lowercase")));
    }

    @Test
    void rejectsAnInvalidArrayItem() {
        assertInvalid("tags", "7", validation("getForecast", arguments("tags", List.of(BigInteger.valueOf(7)))));
    }

    @Test
    void rejectsAnUnknownNestedProperty() {
        assertInvalid("location", "hidden-location", validation("getForecast", arguments(
                "location", Map.of(
                        "latitude", new BigDecimal("37.5"),
                        "longitude", new BigDecimal("127.0"),
                        "hidden", "hidden-location"))));
    }

    @Test
    void rejectsAMissingNestedRequiredProperty() {
        assertInvalid("location", "missing-longitude", validation("getForecast", arguments(
                "location", Map.of("latitude", new BigDecimal("37.5")))));
    }

    private void assertInvalid(String safeFieldName, String configuredValue, ValidationConfiguration configuration) {
        GeneratorException exception = assertThrows(GeneratorException.class,
                () -> factory.create(List.of(weatherTool()), configuration));

        assertEquals(VALIDATION_ARGUMENT_INVALID, exception.code());
        assertEquals("TOOL_MODEL_VALIDATE", exception.stage());
        assertEquals("Validation argument does not match Tool input: " + safeFieldName, exception.safeMessage());
        assertFalse(exception.safeMessage().contains(configuredValue));
    }

    private ValidationConfiguration validation(String operationId, Map<String, Object> arguments) {
        return new ValidationConfiguration(new ToolCallValidation(operationId, arguments));
    }

    private Map<String, Object> validArguments() {
        return Map.of(
                "stationId", "STN01",
                "days", BigInteger.valueOf(3),
                "mode", "brief",
                "tags", List.of("public"),
                "location", validLocation());
    }

    private Map<String, Object> arguments(String key, Object value) {
        Map<String, Object> arguments = new LinkedHashMap<>(validArguments());
        arguments.put(key, value);
        return Map.copyOf(arguments);
    }

    private Map<String, Object> validLocation() {
        return Map.of("latitude", new BigDecimal("37.5"), "longitude", new BigDecimal("127.0"));
    }

    private McpToolDefinition weatherTool() {
        ApiSchema stationId = schema(STRING, List.of(), null, null, 3, 8, "[A-Z0-9]+", Map.of(), List.of(), null);
        ApiSchema days = schema(INTEGER, List.of(), BigDecimal.ONE, BigDecimal.valueOf(5), null, null,
                null, Map.of(), List.of(), null);
        ApiSchema mode = schema(STRING, List.of("brief", "detailed"), null, null, null, null,
                null, Map.of(), List.of(), null);
        ApiSchema tag = schema(STRING, List.of(), null, null, null, null, null, Map.of(), List.of(), null);
        ApiSchema tags = schema(ARRAY, List.of(), null, null, null, null, null, Map.of(), List.of(), tag);
        ApiSchema coordinate = schema(NUMBER, List.of(), null, null, null, null, null, Map.of(), List.of(), null);
        ApiSchema location = schema(OBJECT, List.of(), null, null, null, null, null,
                Map.of("latitude", coordinate, "longitude", coordinate), List.of("latitude", "longitude"), null);
        ApiSchema defaultInteger = integerSchema(null);
        ApiSchema int32Integer = integerSchema("int32");
        ApiSchema int64Integer = integerSchema("int64");
        ApiSchema integerArray = schema(
                ARRAY, List.of(), null, null, null, null, null, Map.of(), List.of(), int64Integer);
        ApiSchema integerObject = schema(
                OBJECT, List.of(), null, null, null, null, null,
                Map.of(
                        "count", int32Integer,
                        "sequence", schema(
                                ARRAY, List.of(), null, null, null, null, null,
                                Map.of(), List.of(), defaultInteger)),
                List.of("count", "sequence"), null);
        return new McpToolDefinition(
                "getForecast",
                "kma_weather_get_forecast",
                "Get the public weather forecast.",
                List.of(
                        new McpInputDefinition("stationId", "stationId", "Station", true, stationId),
                        new McpInputDefinition("days", "days", "Days", true, days),
                        new McpInputDefinition("mode", "mode", "Mode", true, mode),
                        new McpInputDefinition("tags", "tags", "Tags", true, tags),
                        new McpInputDefinition("location", "location", "Location", true, location),
                        new McpInputDefinition(
                                "defaultInteger", "defaultInteger", "Default integer", false, defaultInteger),
                        new McpInputDefinition(
                                "int32Integer", "int32Integer", "Int32 integer", false, int32Integer),
                        new McpInputDefinition(
                                "int64Integer", "int64Integer", "Int64 integer", false, int64Integer),
                        new McpInputDefinition(
                                "integerArray", "integerArray", "Integer array", false, integerArray),
                        new McpInputDefinition(
                                "integerObject", "integerObject", "Integer object", false, integerObject)),
                null,
                List.of(),
                McpToolDefinition.OutputKind.GENERIC_JSON);
    }

    private ApiSchema integerSchema(String format) {
        return new ApiSchema(
                INTEGER, format, false, List.of(), null, null, null, null, null,
                null, Map.of(), List.of(), null, true, List.of());
    }

    private ApiSchema schema(
            io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType type,
            List<String> enumValues,
            BigDecimal minimum,
            BigDecimal maximum,
            Integer minLength,
            Integer maxLength,
            String pattern,
            Map<String, ApiSchema> properties,
            List<String> requiredProperties,
            ApiSchema items) {
        return new ApiSchema(
                type, null, false, enumValues, minimum, maximum, minLength, maxLength, pattern,
                null, properties, requiredProperties, items, true, List.of());
    }
}
