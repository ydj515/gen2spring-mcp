package io.gen2spring.mcp.domain.tool;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;

public record ParameterBinding(
        String sourceName,
        ParameterLocation targetLocation,
        String targetName) {}
