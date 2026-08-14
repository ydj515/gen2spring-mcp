package io.gen2spring.mcp.adapter.openapi.swagger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
