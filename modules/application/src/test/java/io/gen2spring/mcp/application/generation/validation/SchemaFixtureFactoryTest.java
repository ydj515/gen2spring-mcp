package io.gen2spring.mcp.application.generation.validation;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.ARRAY;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.INTEGER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.NUMBER;
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
    void reusesSingletonEnumsAndSupportsBudgetedPatternFixtures() {
        ApiSchema single = new ApiSchema(
                STRING, null, false, List.of("only"), null, null, null, null, null,
                null, Map.of(), List.of(), null, true, List.of());
        ApiSchema budgetedPattern = new ApiSchema(
                STRING, null, false, List.of(), null, null, 1, 10, "(a+)+$",
                null, Map.of(), List.of(), null, true, List.of());

        assertEquals("only", factory.create(single, 1));
        assertEquals("a", factory.create(budgetedPattern, 0));
    }

    @Test
    void derivesIntegerVariantsBelowAnUpperBoundWithoutAnExplicitMinimum() {
        ApiSchema bounded = schema(INTEGER, Map.of(), List.of(), null, null, BigDecimal.ZERO);
        ApiSchema int32 = new ApiSchema(INTEGER, "int32", false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());

        assertEquals(BigInteger.ZERO, factory.create(bounded, 0));
        assertEquals(BigInteger.ONE.negate(), factory.create(bounded, 1));
        assertEquals(BigInteger.ONE, factory.create(int32, 0));
        assertEquals(BigInteger.TWO, factory.create(int32, 1));
    }

    @Test
    void derivesDecimalVariantsBelowAnUpperBoundWithoutAnExplicitMinimum() {
        ApiSchema bounded = schema(NUMBER, Map.of(), List.of(), null, null, new BigDecimal("-0.5"));

        assertEquals(new BigDecimal("-0.5"), factory.create(bounded, 0));
        assertEquals(new BigDecimal("-1.5"), factory.create(bounded, 1));
    }

    @Test
    void preservesMinimumBasedVariantsAndRejectsAnImpossibleSecondValue() {
        ApiSchema bounded = schema(INTEGER, Map.of(), List.of(), null, BigDecimal.ONE, BigDecimal.ONE);

        assertEquals(BigInteger.ONE, factory.create(bounded, 0));
        assertEquals("Schema fixture cannot be derived",
                assertThrows(IllegalArgumentException.class, () -> factory.create(bounded, 1)).getMessage());
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
