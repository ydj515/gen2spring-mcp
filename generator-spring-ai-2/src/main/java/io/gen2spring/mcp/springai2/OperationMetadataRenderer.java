package io.gen2spring.mcp.springai2;

import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterBinding;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.SecretBinding;
import java.util.Comparator;
import java.util.List;

final class OperationMetadataRenderer {
    String render(String packageName, String domainClass, List<McpToolDefinition> tools) {
        StringBuilder source = new StringBuilder("package ").append(packageName).append(".generated.metadata;\n\n")
                .append("import ").append(packageName).append(".runtime.OperationDefinition;\n")
                .append("import ").append(packageName).append(".runtime.ParameterBinding;\n")
                .append("import ").append(packageName).append(".runtime.ParameterLocation;\n")
                .append("import ").append(packageName).append(".runtime.SecretBinding;\n")
                .append("import java.util.List;\n\n")
                .append("public final class ").append(domainClass).append("Operations {\n")
                .append("    private ").append(domainClass).append("Operations() {}\n");
        for (McpToolDefinition tool : tools) {
            appendOperation(source, tool);
        }
        return source.append("}\n").toString();
    }

    private void appendOperation(StringBuilder source, McpToolDefinition tool) {
        source.append("\n    public static final OperationDefinition ")
                .append(JavaSourceRenderer.constantName(tool.operationId())).append(" = new OperationDefinition(\n")
                .append("            ").append(JavaStringLiteral.quote(tool.execution().method().name())).append(",\n")
                .append("            ").append(JavaStringLiteral.quote(tool.execution().path())).append(",\n")
                .append("            ");
        appendParameterList(source, tool.execution().bindings());
        source.append(",\n            ");
        appendSecretList(source, tool.secretBindings());
        source.append(",\n            ").append(tool.execution().objectRequestBody())
                .append(",\n            ").append(tool.execution().requestBodyRequired()).append(");\n");
    }

    private void appendParameterList(StringBuilder source, List<ParameterBinding> bindings) {
        List<ParameterBinding> values = bindings == null ? List.of() : bindings.stream()
                .sorted(Comparator.comparing(ParameterBinding::sourceName)
                        .thenComparing(binding -> binding.targetLocation().name())
                        .thenComparing(ParameterBinding::targetName))
                .toList();
        if (values.isEmpty()) {
            source.append("List.of()");
            return;
        }
        source.append("List.of(\n");
        for (int index = 0; index < values.size(); index++) {
            ParameterBinding binding = values.get(index);
            source.append("                    new ParameterBinding(")
                    .append(JavaStringLiteral.quote(binding.sourceName())).append(", ParameterLocation.")
                    .append(binding.targetLocation().name()).append(", ")
                    .append(JavaStringLiteral.quote(binding.targetName())).append(')')
                    .append(index + 1 == values.size() ? ")" : ",\n");
        }
    }

    private void appendSecretList(StringBuilder source, List<SecretBinding> bindings) {
        List<SecretBinding> values = bindings == null ? List.of() : bindings.stream()
                .sorted(Comparator.comparing(SecretBinding::propertyName)
                        .thenComparing(binding -> binding.targetLocation().name())
                        .thenComparing(SecretBinding::targetName))
                .toList();
        if (values.isEmpty()) {
            source.append("List.of()");
            return;
        }
        source.append("List.of(\n");
        for (int index = 0; index < values.size(); index++) {
            SecretBinding binding = values.get(index);
            source.append("                    new SecretBinding(")
                    .append(JavaStringLiteral.quote("provider.secrets." + binding.propertyName()))
                    .append(", ParameterLocation.").append(binding.targetLocation().name()).append(", ")
                    .append(JavaStringLiteral.quote(binding.targetName())).append(", ")
                    .append(binding.required()).append(')')
                    .append(index + 1 == values.size() ? ")" : ",\n");
        }
    }
}
