package io.gen2spring.mcp.adapter.openapi.swagger;

import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_CONSTRAINT_UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
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
    void failsOpenApi31ArrayReferenceMinimumSiblingClosed() {
        ArraySchema referenced = new ArraySchema();
        referenced.setItems(new Schema<>().type("string"));
        Schema<Object> reference = new Schema<>();
        reference.set$ref("#/components/schemas/Values");
        reference.setMinItems(1);

        var normalized = normalizer.normalizeRequestBody(
                reference,
                Map.of("Values", referenced),
                "3.1.2");

        assertFalse(normalized.supported());
        assertEquals(
                java.util.List.of(SCHEMA_CONSTRAINT_UNSUPPORTED.message()),
                normalized.warnings());
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
