package io.gen2spring.mcp.application.toolmodel.schema;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.CompositionKind.ANY_OF;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.CompositionKind.ONE_OF;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.ARRAY;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.INTEGER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.NUMBER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.OBJECT;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.STRING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaComposition;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaValueValidatorTest {
    private final SchemaValueValidator validator = new SchemaValueValidator();

    @Test
    void enforcesArrayBoundsAndStructuralUniqueness() {
        ApiSchema unique = schema(ARRAY, false, null, null, schema(OBJECT, false, null, null, null,
                Map.of("amount", schema(NUMBER, false, null, null, null, Map.of(), List.of(), null)),
                List.of("amount"), null), Map.of(), List.of(), null, 1, 3, true, null);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("amount", BigDecimal.ONE);
        Map<String, Object> duplicate = new LinkedHashMap<>();
        duplicate.put("amount", new BigDecimal("1.0"));

        assertDoesNotThrow(() -> validator.validate(unique, List.of(first)));
        assertInvalid(() -> validator.validate(unique, List.of()));
        assertInvalid(() -> validator.validate(unique, List.of(first, duplicate)));
        assertInvalid(() -> validator.validate(unique, List.of(first, Map.of("amount", 2), Map.of("amount", 3),
                Map.of("amount", 4))));
    }

    @Test
    void treatsObjectOrderAsIrrelevantAndArrayOrderAsSignificant() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "a");
        first.put("values", List.of(1, 2));
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("values", List.of(new BigDecimal("1.0"), 2));
        reordered.put("name", "a");
        Map<String, Object> reversed = Map.of("name", "a", "values", List.of(2, 1));

        assertEquals(CanonicalJsonValue.of(first), CanonicalJsonValue.of(reordered));
        assertNotEquals(CanonicalJsonValue.of(first), CanonicalJsonValue.of(reversed));
    }

    @Test
    void enforcesExactlyOneOneOfAndAtLeastOneAnyOf() {
        ApiSchema integer = schema(INTEGER, false, BigDecimal.ZERO, BigDecimal.TEN,
                null, Map.of(), List.of(), null);
        ApiSchema number = schema(NUMBER, false, BigDecimal.ZERO, BigDecimal.TEN,
                null, Map.of(), List.of(), null);
        ApiSchema string = schema(STRING, false, null, null, null, Map.of(), List.of(), null);
        ApiSchema oneOf = composed(ONE_OF, List.of(integer, number), false);
        ApiSchema anyOf = composed(ANY_OF, List.of(integer, number), false);
        ApiSchema disjoint = composed(ONE_OF, List.of(string, integer), true);

        assertInvalid(() -> validator.validate(oneOf, 1));
        assertDoesNotThrow(() -> validator.validate(anyOf, 1));
        assertDoesNotThrow(() -> validator.validate(disjoint, "one"));
        assertDoesNotThrow(() -> validator.validate(disjoint, null));
        assertInvalid(() -> validator.validate(disjoint, true));
    }

    @Test
    void validatesProjectedJsonSchemasWithTheSameRules() {
        Map<String, Object> schema = Map.of(
                "type", "array",
                "items", Map.of("oneOf", List.of(Map.of("type", "string"), Map.of("type", "integer"))),
                "minItems", 1,
                "maxItems", 2,
                "uniqueItems", true);

        assertDoesNotThrow(() -> validator.validate(schema, List.of("one", 2)));
        assertInvalid(() -> validator.validate(schema, List.of(1, new BigDecimal("1.0"))));
        assertInvalid(() -> validator.validate(schema, List.of()));
    }

    private void assertInvalid(Runnable action) {
        assertEquals("Schema value is invalid", assertThrows(IllegalArgumentException.class, action::run).getMessage());
    }

    private ApiSchema composed(
            io.gen2spring.mcp.domain.specification.OpenApiDocument.CompositionKind kind,
            List<ApiSchema> branches,
            boolean nullable) {
        return schema(SchemaType.COMPOSED, nullable, null, null, null, Map.of(), List.of(), null,
                null, null, false, new SchemaComposition(kind, branches));
    }

    private ApiSchema schema(
            SchemaType type,
            boolean nullable,
            BigDecimal minimum,
            BigDecimal maximum,
            ApiSchema items,
            Map<String, ApiSchema> properties,
            List<String> required,
            SchemaComposition composition) {
        return schema(type, nullable, minimum, maximum, items, properties, required, composition,
                null, null, false, composition);
    }

    private ApiSchema schema(
            SchemaType type,
            boolean nullable,
            BigDecimal minimum,
            BigDecimal maximum,
            ApiSchema items,
            Map<String, ApiSchema> properties,
            List<String> required,
            SchemaComposition ignored,
            Integer minItems,
            Integer maxItems,
            boolean uniqueItems,
            SchemaComposition composition) {
        return new ApiSchema(
                type, null, nullable, List.of(), minimum, maximum, null, null, null, null,
                properties, required, items, minItems, maxItems, uniqueItems, composition, true, List.of());
    }
}
