package io.gen2spring.mcp.application.usecase;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.util.List;

public record GenerationContext(
        OpenApiDocument document,
        List<ToolDefinition> tools,
        GenerationCommand request,
        CompatibilityProfile profile,
        byte[] originalSpecification) {}
