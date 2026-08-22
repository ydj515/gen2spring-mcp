package io.gen2spring.mcp.application.toolmodel.schema;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.ARRAY;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.INTEGER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.OBJECT;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.STRING;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.COMPOSED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.CompositionKind;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaComposition;
import io.gen2spring.mcp.domain.tool.OutputKind;
import io.gen2spring.mcp.domain.tool.ToolInput;
import io.gen2spring.mcp.domain.tool.ToolOutput;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolJsonSchemaFactoryTest {
    private final ToolJsonSchemaFactory factory = new ToolJsonSchemaFactory();

    @Test
    void projectsSortedInputPropertiesRequiredNamesAndNestedConstraints() {
        ApiSchema nullableTag = schema(
                STRING, null, true, List.of("public", "private"), null, null,
                2, 16, "[a-z]+", Map.of(), List.of(), null, null);
        ApiSchema tags = schema(
                ARRAY, null, false, List.of(), null, null,
                null, null, null, Map.of(), List.of(), nullableTag, 1);
        ApiSchema request = schema(
                OBJECT, null, false, List.of(), null, null,
                null, null, null,
                Map.of(
                        "count", schema(INTEGER, "int64", false, List.of(), BigDecimal.ONE,
                                BigDecimal.TEN, null, null, null, Map.of(), List.of(), null, null),
                        "tags", tags),
                List.of("count"), null, null);

        Map<String, Object> actual = factory.inputSchema(List.of(
                new ToolInput("zeta", "zeta", "", false, tags),
                new ToolInput("request", "request", "Create request", true, request)));

        assertEquals(Map.of(
                "type", "object",
                "properties", Map.of(
                        "request", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "count", Map.of(
                                                "type", "integer",
                                                "format", "int64",
                                                "minimum", BigDecimal.ONE,
                                                "maximum", BigDecimal.TEN,
                                                "description", "count"),
                                        "tags", Map.of(
                                                "type", "array",
                                                "items", Map.of(
                                                        "anyOf", List.of(
                                                                Map.of(
                                                                        "type", "string",
                                                                        "enum", List.of("public", "private"),
                                                                        "minLength", 2,
                                                                        "maxLength", 16,
                                                                        "pattern", "[a-z]+"),
                                                                Map.of("type", "null"))),
                                                "minItems", 1,
                                                "description", "tags")),
                                "required", List.of("count"),
                                "description", "Create request"),
                        "zeta", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "anyOf", List.of(
                                                Map.of(
                                                        "type", "string",
                                                        "enum", List.of("public", "private"),
                                                        "minLength", 2,
                                                        "maxLength", 16,
                                                        "pattern", "[a-z]+"),
                                                Map.of("type", "null"))),
                                "minItems", 1,
                                "description", "zeta")),
                "required", List.of("request")), actual);
        assertEquals(List.of("request", "zeta"),
                new ArrayList<>(((Map<?, ?>) actual.get("properties")).keySet()));
    }

    @Test
    void projectsTypedOutputAndLeavesGenericJsonUnconstrained() {
        ApiSchema result = schema(
                OBJECT, null, false, List.of(), null, null,
                null, null, null,
                Map.of("id", schema(STRING, "uuid", false, List.of(), null, null,
                        null, null, null, Map.of(), List.of(), null, null)),
                List.of("id"), null, null);

        assertEquals(Map.of(
                "type", "object",
                "properties", Map.of("id", Map.of(
                        "type", "string",
                        "format", "uuid",
                        "description", "id")),
                "required", List.of("id")),
                factory.outputSchema(new ToolOutput(OutputKind.TYPED_DTO, result, result)));
        assertEquals(Map.of(),
                factory.outputSchema(new ToolOutput(OutputKind.GENERIC_JSON, result, null)));
    }

    @Test
    void projectsBoundedUniqueArraysAndCompositionsExactly() {
        ApiSchema string = schema(STRING, null, false, List.of(), null, null,
                null, null, null, Map.of(), List.of(), null, null);
        ApiSchema integer = schema(INTEGER, null, false, List.of(), null, null,
                null, null, null, Map.of(), List.of(), null, null);
        ApiSchema composed = new ApiSchema(
                COMPOSED, null, true, List.of(), null, null, null, null, null, null,
                Map.of(), List.of(), null, null, null, false,
                new SchemaComposition(CompositionKind.ONE_OF, List.of(string, integer)), true, List.of());
        ApiSchema array = new ApiSchema(
                ARRAY, null, false, List.of(), null, null, null, null, null, null,
                Map.of(), List.of(), composed, 1, 4, true, null, true, List.of());

        assertEquals(Map.of(
                "type", "array",
                "items", Map.of("anyOf", List.of(
                        Map.of("oneOf", List.of(
                                Map.of("type", "string"),
                                Map.of("type", "integer", "format", "int32"))),
                        Map.of("type", "null"))),
                "minItems", 1,
                "maxItems", 4,
                "uniqueItems", true), factory.schema(array));
    }

    private ApiSchema schema(
            io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType type,
            String format,
            boolean nullable,
            List<String> enumValues,
            BigDecimal minimum,
            BigDecimal maximum,
            Integer minLength,
            Integer maxLength,
            String pattern,
            Map<String, ApiSchema> properties,
            List<String> required,
            ApiSchema items,
            Integer minItems) {
        return new ApiSchema(
                type, format, nullable, enumValues, minimum, maximum, minLength, maxLength,
                pattern, null, properties, required, items, minItems, true, List.of());
    }
}
