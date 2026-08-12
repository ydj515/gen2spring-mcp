package io.gen2spring.mcp.domain.tool;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;

public record SecretBinding(
        String environmentVariable,
        String propertyName,
        ParameterLocation targetLocation,
        String targetName,
        boolean required) {}
