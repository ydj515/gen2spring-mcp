package io.gen2spring.mcp.domain.tool;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;

public record ToolInput(
        String name,
        String jsonName,
        String description,
        boolean required,
        ApiSchema schema) {}
