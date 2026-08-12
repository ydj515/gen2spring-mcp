package io.gen2spring.mcp.springai2;

import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import io.gen2spring.mcp.domain.tool.SecretBinding;
import java.util.Comparator;
import java.util.List;

final class OperationMetadataRenderer {
    String render(String packageName, String domainClass, List<ToolDefinition> tools) {
        boolean normalized = tools.stream().anyMatch(tool -> tool.execution().responseNormalization() != null);
        boolean retried = tools.stream().anyMatch(tool -> tool.execution().retryPolicy() != null);
        boolean paginated = tools.stream().anyMatch(tool -> tool.execution().paginationPolicy() != null);
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
        if (retried || paginated) {
            source.append("import ").append(packageName).append(".runtime.RetryPolicy;\n");
        }
        if (paginated) {
            source.append("import ").append(packageName).append(".runtime.PaginationPolicy;\n")
                    .append("import java.math.BigInteger;\n");
        }
        source.append("import java.util.List;\n\n")
                .append("public final class ").append(domainClass).append("Operations {\n")
                .append("    private ").append(domainClass).append("Operations() {}\n");
        for (ToolDefinition tool : tools) {
            appendOperation(source, tool, retried || paginated, paginated);
        }
        return source.append("}\n").toString();
    }

    private void appendOperation(
            StringBuilder source, ToolDefinition tool, boolean retried, boolean paginated) {
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
        if (retried) {
            source.append(",\n            ");
            appendRetryPolicy(source, tool.execution().retryPolicy());
        }
        if (paginated) {
            source.append(",\n            ");
            appendPaginationPolicy(source, tool.execution().paginationPolicy());
        }
        source.append(");\n");
    }

    private void appendPaginationPolicy(StringBuilder source, PaginationPolicy policy) {
        if (policy == null) {
            source.append("null");
            return;
        }
        source.append("new PaginationPolicy(")
                .append(JavaStringLiteral.quote(policy.requestParameter())).append(", ");
        if (policy.initialValue() == null) {
            source.append("null");
        } else if (policy.initialValue() instanceof String text) {
            source.append(JavaStringLiteral.quote(text));
        } else if (policy.initialValue() instanceof java.math.BigInteger integer) {
            source.append("new BigInteger(").append(JavaStringLiteral.quote(integer.toString())).append(")");
        } else {
            throw JavaSourceRenderer.invalid("Pagination initial value must be a string or integer");
        }
        source.append(", ").append(JavaStringLiteral.quote(policy.itemsPointer()))
                .append(", ").append(JavaStringLiteral.quote(policy.nextValuePointer()))
                .append(", ").append(policy.maxPages())
                .append(", ").append(policy.maxItems()).append(')');
    }

    private void appendRetryPolicy(StringBuilder source, RetryPolicy policy) {
        if (policy == null) {
            source.append("null");
            return;
        }
        source.append("new RetryPolicy(List.of(");
        for (int index = 0; index < policy.statusCodes().size(); index++) {
            if (index > 0) {
                source.append(", ");
            }
            source.append(policy.statusCodes().get(index));
        }
        source.append("), ").append(policy.networkErrors())
                .append(", ").append(policy.maxRetries())
                .append(", ").append(policy.initialBackoffMillis()).append('L')
                .append(", ").append(policy.maxBackoffMillis()).append('L')
                .append(", ").append(policy.respectRetryAfter()).append(')');
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
