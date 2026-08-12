package io.gen2spring.mcp.domain.tool;

import java.util.List;
import java.util.Objects;

public record ToolDefinition(
        String operationId,
        String name,
        String description,
        List<ToolInput> inputs,
        HttpExecution execution,
        List<SecretBinding> secretBindings,
        ToolOutput output) {
    public ToolDefinition {
        Objects.requireNonNull(output, "output");
    }

    public ToolDefinition(
            String operationId,
            String name,
            String description,
            List<ToolInput> inputs,
            HttpExecution execution,
            List<SecretBinding> secretBindings,
            OutputKind outputKind) {
        this(operationId, name, description, inputs, execution, secretBindings,
                new ToolOutput(outputKind, null, null));
    }

    public OutputKind outputKind() {
        return output.kind();
    }
}
