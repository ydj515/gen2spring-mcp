package io.gen2spring.mcp.adapter.openapi.swagger;

import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_CONSTRAINT_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_COMPOSITION_UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.CompositionKind;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SwaggerSchemaNormalizerTest {
    private final SwaggerSchemaNormalizer normalizer = new SwaggerSchemaNormalizer();

    @Test
    void appliesNullableOnlyToOpenApi30Schemas() {
        Schema<Object> schema = new Schema<>();
        schema.setTypes(Set.of("string"));
        schema.setNullable(true);

        assertTrue(normalizer.normalize(schema, Map.of(), "3.0.4").nullable());
        assertFalse(normalizer.normalize(schema, Map.of(), "3.1.2").nullable());
    }

    @Test
    void preservesNullableRequestBodyDescendantsAndArrayMinimums() {
        Schema<Object> nullableItem = new Schema<>();
        nullableItem.setTypes(Set.of("string", "null"));
        ArraySchema values = new ArraySchema();
        values.setItems(nullableItem);
        values.setMinItems(1);
        Schema<Object> body = new Schema<>();
        body.setType("object");
        body.addProperty("values", values);

        var normalized = normalizer.normalizeRequestBody(body, Map.of(), "3.1.2");

        assertTrue(normalized.supported(), normalized.warnings().toString());
        assertEquals(1, normalized.properties().get("values").minItems());
        assertTrue(normalized.properties().get("values").items().nullable());
    }

    @Test
    void intersectsOpenApi31ReferenceSiblingsWithoutDroppingReferencedConstraints() {
        ArraySchema referenced = new ArraySchema();
        referenced.setItems(new Schema<>().type("string"));
        referenced.setMinItems(1);
        Schema<Object> reference = new Schema<>();
        reference.set$ref("#/components/schemas/Values");
        reference.setMaxItems(3);

        var normalized = normalizer.normalizeRequestBody(
                reference,
                Map.of("Values", referenced),
                "3.1.2");

        assertTrue(normalized.supported(), normalized.warnings().toString());
        assertEquals(SchemaType.ARRAY, normalized.type());
        assertEquals(1, normalized.minItems());
        assertEquals(3, normalized.maxItems());
        assertEquals(SchemaType.STRING, normalized.items().type());
    }

    @Test
    void rejectsSemanticReferenceSiblingsForOpenApi30() {
        Schema<Object> referenced = new Schema<>().type("string");
        Schema<Object> reference = new Schema<>();
        reference.set$ref("#/components/schemas/Value");
        reference.setMaxLength(8);

        var normalized = normalizer.normalizeRequestBody(reference, Map.of("Value", referenced), "3.0.4");

        assertFalse(normalized.supported());
        assertEquals(List.of(SCHEMA_CONSTRAINT_UNSUPPORTED.message()), normalized.warnings());
    }

    @Test
    void preservesBoundedArrayMaximumAndUniqueness() {
        ArraySchema schema = new ArraySchema();
        schema.setItems(new Schema<>().type("integer"));
        schema.setMinItems(1);
        schema.setMaxItems(4);
        schema.setUniqueItems(true);

        var normalized = normalizer.normalizeRequestBody(schema, Map.of(), "3.1.2");

        assertTrue(normalized.supported(), normalized.warnings().toString());
        assertEquals(1, normalized.minItems());
        assertEquals(4, normalized.maxItems());
        assertTrue(normalized.uniqueItems());
    }

    @Test
    void rejectsUnboundedOrOversizedUniqueArrays() {
        ArraySchema unbounded = new ArraySchema();
        unbounded.setItems(new Schema<>().type("string"));
        unbounded.setUniqueItems(true);
        ArraySchema oversized = new ArraySchema();
        oversized.setItems(new Schema<>().type("string"));
        oversized.setMaxItems(257);
        oversized.setUniqueItems(true);

        assertFalse(normalizer.normalizeRequestBody(unbounded, Map.of(), "3.1.2").supported());
        assertFalse(normalizer.normalizeRequestBody(oversized, Map.of(), "3.1.2").supported());
    }

    @Test
    void intersectsCompatibleAllOfObjectsAndRejectsConflicts() {
        Schema<Object> identity = new Schema<>().type("object");
        identity.addProperty("id", new Schema<>().type("string").minLength(2));
        identity.setRequired(List.of("id"));
        Schema<Object> label = new Schema<>().type("object");
        label.addProperty("label", new Schema<>().type("string"));
        ComposedSchema compatible = new ComposedSchema();
        compatible.setAllOf(List.of(identity, label));
        ComposedSchema conflicting = new ComposedSchema();
        conflicting.setAllOf(List.of(new Schema<>().type("string"), new Schema<>().type("integer")));

        var merged = normalizer.normalizeRequestBody(compatible, Map.of(), "3.1.2");

        assertTrue(merged.supported(), merged.warnings().toString());
        assertEquals(SchemaType.OBJECT, merged.type());
        assertEquals(Set.of("id", "label"), merged.properties().keySet());
        assertEquals(List.of("id"), merged.requiredProperties());
        assertFalse(normalizer.normalizeRequestBody(conflicting, Map.of(), "3.1.2").supported());
    }

    @Test
    void preservesBoundedOneOfAnyOfAndMultiTypeUnions() {
        ComposedSchema oneOf = new ComposedSchema();
        oneOf.setOneOf(List.of(new Schema<>().type("string"), new Schema<>().type("integer")));
        ComposedSchema anyOf = new ComposedSchema();
        anyOf.setAnyOf(List.of(new Schema<>().type("boolean"), new Schema<>().type("number")));
        Schema<Object> multiType = new Schema<>();
        multiType.setTypes(Set.of("string", "integer", "null"));

        var normalizedOneOf = normalizer.normalizeRequestBody(oneOf, Map.of(), "3.1.2");
        var normalizedAnyOf = normalizer.normalizeRequestBody(anyOf, Map.of(), "3.1.2");
        var normalizedMultiType = normalizer.normalizeRequestBody(multiType, Map.of(), "3.1.2");

        assertEquals(CompositionKind.ONE_OF, normalizedOneOf.composition().kind());
        assertEquals(CompositionKind.ANY_OF, normalizedAnyOf.composition().kind());
        assertEquals(CompositionKind.ANY_OF, normalizedMultiType.composition().kind());
        assertTrue(normalizedMultiType.nullable());
        assertEquals(List.of(SchemaType.INTEGER, SchemaType.STRING), normalizedMultiType.composition().branches()
                .stream().map(schema -> schema.type()).toList());
    }

    @Test
    void rejectsCompositionBranchAndTotalBudgets() {
        List<Schema> nineBranches = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            nineBranches.add(new Schema<>().type("string"));
        }
        ComposedSchema oversized = new ComposedSchema();
        oversized.setOneOf(nineBranches);

        var normalized = normalizer.normalizeRequestBody(oversized, Map.of(), "3.1.2");

        assertFalse(normalized.supported());
        assertEquals(List.of(SCHEMA_COMPOSITION_UNSUPPORTED.message()), normalized.warnings());
    }

    @Test
    void rejectsArrayMinimumOnNonArraySchemas() {
        Schema<Object> schema = new Schema<>();
        schema.setType("string");
        schema.setMinItems(1);

        var normalized = normalizer.normalizeRequestBody(schema, Map.of(), "3.1.2");

        assertFalse(normalized.supported());
        assertEquals(
                java.util.List.of(SCHEMA_CONSTRAINT_UNSUPPORTED.message()),
                normalized.warnings());
    }
}
