package io.gen2spring.mcp.domain.tool;

import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import java.util.Objects;

public record OutputDefinition(
        McpToolDefinition.OutputKind kind,
        ApiSchema providerSchema,
        ApiSchema resultSchema) {
    public OutputDefinition {
        Objects.requireNonNull(kind, "kind");
        if (kind == McpToolDefinition.OutputKind.TYPED_DTO
                && (providerSchema == null || resultSchema == null)) {
            throw new IllegalArgumentException("Typed Tool output schema is incomplete");
        }
    }
}
