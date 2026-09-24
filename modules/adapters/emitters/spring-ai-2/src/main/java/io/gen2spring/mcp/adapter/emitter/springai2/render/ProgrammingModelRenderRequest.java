package io.gen2spring.mcp.adapter.emitter.springai2.render;

import io.gen2spring.mcp.application.generation.usecase.GenerationContext;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record ProgrammingModelRenderRequest(
        GenerationContext context,
        String packageName,
        String packagePath,
        String domainClass,
        List<ToolDefinition> tools,
        Map<String, String> toolSchemas,
        boolean hasTypedOutputs,
        boolean hasRetryPolicies,
        boolean hasPaginationPolicies) {
    ProgrammingModelRenderRequest {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(packagePath, "packagePath");
        Objects.requireNonNull(domainClass, "domainClass");
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        toolSchemas = Map.copyOf(Objects.requireNonNull(toolSchemas, "toolSchemas"));
    }
}
