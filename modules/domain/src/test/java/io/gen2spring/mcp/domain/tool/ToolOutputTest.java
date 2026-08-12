package io.gen2spring.mcp.domain.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolOutputTest {
    @Test
    void preservesTypedProviderAndResultSchemas() {
        ApiSchema provider = schema(SchemaType.OBJECT);
        ApiSchema result = schema(SchemaType.STRING);

        ToolOutput output = new ToolOutput(
                OutputKind.TYPED_DTO, provider, result);

        assertEquals(OutputKind.TYPED_DTO, output.kind());
        assertSame(provider, output.providerSchema());
        assertSame(result, output.resultSchema());
    }

    @Test
    void permitsGenericOutputWithoutSchemasAndRejectsIncompleteTypedOutput() {
        ToolOutput generic = new ToolOutput(
                OutputKind.GENERIC_JSON, null, null);
        assertNull(generic.providerSchema());
        assertNull(generic.resultSchema());

        for (ToolOutput invalid : List.of(
                new ToolOutput(OutputKind.GENERIC_JSON, schema(SchemaType.OBJECT), null),
                new ToolOutput(OutputKind.GENERIC_JSON, null, schema(SchemaType.STRING)))) {
            assertEquals(OutputKind.GENERIC_JSON, invalid.kind());
        }
        IllegalArgumentException missingProvider = assertThrows(IllegalArgumentException.class,
                () -> new ToolOutput(
                        OutputKind.TYPED_DTO, null, schema(SchemaType.STRING)));
        IllegalArgumentException missingResult = assertThrows(IllegalArgumentException.class,
                () -> new ToolOutput(
                        OutputKind.TYPED_DTO, schema(SchemaType.OBJECT), null));
        assertEquals("Typed Tool output schema is incomplete", missingProvider.getMessage());
        assertEquals("Typed Tool output schema is incomplete", missingResult.getMessage());
    }

    private ApiSchema schema(SchemaType type) {
        return new ApiSchema(type, null, false, List.of(), null, null, null, null, null, null,
                Map.of(), List.of(), null, true, List.of());
    }
}
