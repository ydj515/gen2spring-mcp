package io.gen2spring.mcp.domain.tool;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import java.util.Objects;

public record ToolOutput(
        OutputKind kind,
        ApiSchema providerSchema,
        ApiSchema resultSchema) {
    public ToolOutput {
        Objects.requireNonNull(kind, "kind");
        if (kind == OutputKind.TYPED_DTO && (providerSchema == null || resultSchema == null)) {
            throw new IllegalArgumentException("Typed Tool output schema is incomplete");
        }
    }
}
