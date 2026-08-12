package io.gen2spring.mcp.application.validation;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.ARRAY;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.INTEGER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.OBJECT;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaFixtureFactoryTest {
    private final SchemaFixtureFactory factory = new SchemaFixtureFactory();

    @Test
    void createsTwoDistinctRecursiveFixturesWithinSchemaConstraints() {
        ApiSchema item = schema(OBJECT, Map.of(
                "id", schema(INTEGER, Map.of(), List.of(), null, BigDecimal.ONE, BigDecimal.TEN),
                "label", new ApiSchema(
                        STRING, null, false, List.of("first", "second"), null, null, 3, 8, null,
                        null, Map.of(), List.of(), null, true, List.of())), List.of("id", "label"), null, null, null);
        ApiSchema array = schema(ARRAY, Map.of(), List.of(), item, null, null);

        assertEquals(List.of(Map.of("id", BigInteger.ONE, "label", "first")), factory.create(array, 0));
        assertEquals(List.of(Map.of("id", BigInteger.TWO, "label", "second")), factory.create(array, 1));
    }

    @Test
    void reusesSingletonEnumsAndRejectsUnsupportedPatternFixtures() {
        ApiSchema single = new ApiSchema(
                STRING, null, false, List.of("only"), null, null, null, null, null,
                null, Map.of(), List.of(), null, true, List.of());
        ApiSchema unsupportedPattern = new ApiSchema(
                STRING, null, false, List.of(), null, null, 1, 10, "(a+)+$",
                null, Map.of(), List.of(), null, true, List.of());

        assertEquals("only", factory.create(single, 1));
        assertThrows(IllegalArgumentException.class, () -> factory.create(unsupportedPattern, 0));
    }

    private ApiSchema schema(
            io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType type,
            Map<String, ApiSchema> properties,
            List<String> required,
            ApiSchema items,
            BigDecimal minimum,
            BigDecimal maximum) {
        return new ApiSchema(
                type, null, false, List.of(), minimum, maximum, null, null, null,
                null, properties, required, items, true, List.of());
    }
}
