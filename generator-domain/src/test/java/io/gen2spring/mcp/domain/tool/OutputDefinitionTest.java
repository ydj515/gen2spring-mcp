package io.gen2spring.mcp.domain.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutputDefinitionTest {
    @Test
    void preservesTypedProviderAndResultSchemas() {
        ApiSchema provider = schema(SchemaType.OBJECT);
        ApiSchema result = schema(SchemaType.STRING);

        OutputDefinition output = new OutputDefinition(
                McpToolDefinition.OutputKind.TYPED_DTO, provider, result);

        assertEquals(McpToolDefinition.OutputKind.TYPED_DTO, output.kind());
        assertSame(provider, output.providerSchema());
        assertSame(result, output.resultSchema());
    }

    @Test
    void permitsGenericOutputWithoutSchemasAndRejectsIncompleteTypedOutput() {
        OutputDefinition generic = new OutputDefinition(
                McpToolDefinition.OutputKind.GENERIC_JSON, null, null);
        assertNull(generic.providerSchema());
        assertNull(generic.resultSchema());

        for (OutputDefinition invalid : List.of(
                new OutputDefinition(McpToolDefinition.OutputKind.GENERIC_JSON, schema(SchemaType.OBJECT), null),
                new OutputDefinition(McpToolDefinition.OutputKind.GENERIC_JSON, null, schema(SchemaType.STRING)))) {
            assertEquals(McpToolDefinition.OutputKind.GENERIC_JSON, invalid.kind());
        }
        IllegalArgumentException missingProvider = assertThrows(IllegalArgumentException.class,
                () -> new OutputDefinition(
                        McpToolDefinition.OutputKind.TYPED_DTO, null, schema(SchemaType.STRING)));
        IllegalArgumentException missingResult = assertThrows(IllegalArgumentException.class,
                () -> new OutputDefinition(
                        McpToolDefinition.OutputKind.TYPED_DTO, schema(SchemaType.OBJECT), null));
        assertEquals("Typed Tool output schema is incomplete", missingProvider.getMessage());
        assertEquals("Typed Tool output schema is incomplete", missingResult.getMessage());
    }

    private ApiSchema schema(SchemaType type) {
        return new ApiSchema(type, null, false, List.of(), null, null, null, null, null, null,
                Map.of(), List.of(), null, true, List.of());
    }
}
