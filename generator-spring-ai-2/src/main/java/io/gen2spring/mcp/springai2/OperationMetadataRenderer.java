package io.gen2spring.mcp.springai2;

import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterBinding;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.SecretBinding;
import java.util.Comparator;
import java.util.List;

final class OperationMetadataRenderer {
    String render(String packageName, String domainClass, List<McpToolDefinition> tools) {
        boolean normalized = tools.stream().anyMatch(tool -> tool.execution().responseNormalization() != null);
        StringBuilder source = new StringBuilder("package ").append(packageName).append(".generated.metadata;\n\n")
                .append("import ").append(packageName).append(".runtime.OperationDefinition;\n")
                .append("import ").append(packageName).append(".runtime.ParameterBinding;\n")
                .append("import ").append(packageName).append(".runtime.ParameterLocation;\n")
                .append("import ").append(packageName).append(".runtime.SecretBinding;\n");
        if (normalized) {
            source.append("import ").append(packageName).append(".runtime.ResponseNormalizationPolicy;\n")
                    .append("import java.math.BigDecimal;\n")
                    .append("import tools.jackson.databind.node.BooleanNode;\n")
                    .append("import tools.jackson.databind.node.JsonNodeFactory;\n")
                    .append("import tools.jackson.databind.node.StringNode;\n");
        }
        source.append("import java.util.List;\n\n")
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
                .append("            ").append(JavaStringLiteral.quote(tool.operationId())).append(",\n")
                .append("            ").append(JavaStringLiteral.quote(tool.execution().method().name())).append(",\n")
                .append("            ").append(JavaStringLiteral.quote(tool.execution().path())).append(",\n")
                .append("            ");
        appendParameterList(source, tool.execution().bindings());
        source.append(",\n            ");
        appendSecretList(source, tool.secretBindings());
        source.append(",\n            ").append(tool.execution().objectRequestBody())
                .append(",\n            ").append(tool.execution().requestBodyRequired())
                .append(",\n            ");
        appendResponseNormalization(source, tool.execution().responseNormalization());
        source.append(");\n");
    }

    private void appendResponseNormalization(StringBuilder source, ResponseNormalizationPolicy policy) {
        if (policy == null) {
            source.append("null");
            return;
        }
        source.append("new ResponseNormalizationPolicy(\n")
                .append("                    ");
        appendNullablePointer(source, policy.dataPointer());
        source.append(",\n                    ");
        appendNullablePointer(source, policy.successCodePointer());
        source.append(",\n                    ");
        appendSuccessValues(source, policy.successValues());
        source.append(",\n                    ");
        appendNullablePointer(source, policy.errorMessagePointer());
        source.append(",\n                    ");
        appendNullablePointer(source, policy.totalCountPointer());
        source.append(')');
    }

    private void appendNullablePointer(StringBuilder source, String pointer) {
        source.append(pointer == null ? "null" : JavaStringLiteral.quote(pointer));
    }

    private void appendSuccessValues(StringBuilder source, List<Object> values) {
        if (values.isEmpty()) {
            source.append("List.of()");
            return;
        }
        source.append("List.of(");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                source.append(", ");
            }
            appendSuccessValue(source, values.get(index));
        }
        source.append(')');
    }

    private void appendSuccessValue(StringBuilder source, Object value) {
        if (value instanceof String text) {
            source.append("StringNode.valueOf(").append(JavaStringLiteral.quote(text)).append(')');
        } else if (value instanceof Boolean bool) {
            source.append(bool ? "BooleanNode.TRUE" : "BooleanNode.FALSE");
        } else if (value instanceof Number number) {
            source.append("JsonNodeFactory.instance.numberNode(new BigDecimal(")
                    .append(JavaStringLiteral.quote(new java.math.BigDecimal(number.toString()).toString()))
                    .append("))");
        } else {
            throw JavaSourceRenderer.invalid("Response success values must be JSON scalars");
        }
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
