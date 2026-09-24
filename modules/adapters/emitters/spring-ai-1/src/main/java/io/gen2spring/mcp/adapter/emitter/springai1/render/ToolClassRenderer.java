package io.gen2spring.mcp.adapter.emitter.springai1.render;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.tool.OutputKind;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.ToolInput;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class ToolClassRenderer {
    String render(
            String packageName,
            String domainClass,
            List<ToolDefinition> tools) {
        Set<String> imports = imports(packageName, domainClass, tools);
        StringBuilder source = new StringBuilder("package ").append(packageName).append(".generated.tool;\n\n");
        imports.forEach(value -> source.append("import ").append(value).append(";\n"));
        source.append("\n@Component\n@Validated\npublic class ").append(domainClass).append("McpTools {\n")
                .append("    private final OpenApiOperationExecutor executor;\n\n")
                .append("    public ").append(domainClass).append("McpTools(OpenApiOperationExecutor executor) {\n")
                .append("        this.executor = executor;\n")
                .append("    }\n");

        for (ToolDefinition tool : tools) {
            appendMethod(source, domainClass, tool);
        }
        return source.append("}\n").toString();
    }

    private void appendMethod(
            StringBuilder source,
            String domainClass,
            ToolDefinition tool) {
        List<ToolInput> inputs = InputRecordRenderer.inputs(tool);
        String operationClass = JavaSourceRenderer.upperCamel(tool.operationId());
        String resultType = tool.outputKind() == OutputKind.TYPED_DTO
                ? operationClass + "Result" : "JsonNode";
        source.append('\n');
        source.append("    public ").append(resultType).append(' ')
                .append(JavaSourceRenderer.lowerCamel(tool.operationId())).append('(');
        if (!inputs.isEmpty()) {
            source.append('\n');
        }
        for (int index = 0; index < inputs.size(); index++) {
            ToolInput input = inputs.get(index);
            source.append("            ");
            String constraints = InputRecordRenderer.validationAnnotations(input);
            if (!constraints.isEmpty()) {
                source.append(constraints).append(' ');
            }
            source.append(toolParameterType(
                            input.schema(), operationClass + JavaSourceRenderer.upperCamel(input.name())))
                    .append(' ').append(JavaSourceRenderer.lowerCamel(input.name()))
                    .append(index + 1 == inputs.size() ? ") {\n" : ",\n");
        }
        if (inputs.isEmpty()) {
            source.append(") {\n");
        }
        source.append("        var input = new ").append(operationClass).append("Input(");
        for (int index = 0; index < inputs.size(); index++) {
            if (index > 0) {
                source.append(", ");
            }
            ToolInput input = inputs.get(index);
            source.append(constructorArgument(input, operationClass));
        }
        source.append(");\n")
                .append("        return executor.execute(").append(domainClass).append("Operations.")
                .append(JavaSourceRenderer.constantName(tool.operationId())).append(", input.toArguments()")
                .append(tool.outputKind() == OutputKind.TYPED_DTO
                        ? ", " + operationClass + "Result.class" : "")
                .append(");\n")
                .append("    }\n");
    }

    private Set<String> imports(
            String packageName,
            String domainClass,
            List<ToolDefinition> tools) {
        Set<String> imports = new TreeSet<>();
        imports.add(packageName + ".generated.metadata." + domainClass + "Operations");
        imports.add(packageName + ".runtime.OpenApiOperationExecutor");
        imports.add("org.springframework.stereotype.Component");
        imports.add("org.springframework.validation.annotation.Validated");
        for (ToolDefinition tool : tools) {
            String operationClass = JavaSourceRenderer.upperCamel(tool.operationId());
            imports.add(packageName + ".generated.model." + operationClass + "Input");
            if (tool.outputKind() == OutputKind.TYPED_DTO) {
                imports.add(packageName + ".generated.model." + operationClass + "Result");
            } else {
                imports.add("com.fasterxml.jackson.databind.JsonNode");
            }
            for (ToolInput input : InputRecordRenderer.inputs(tool)) {
                addValidationImports(imports, input);
                String type = JavaSourceRenderer.javaType(
                        input.schema(), operationClass + JavaSourceRenderer.upperCamel(input.name()));
                addTypeImports(imports, packageName, type);
            }
        }
        return imports;
    }

    private void addValidationImports(Set<String> imports, ToolInput input) {
        ApiSchema schema = input.schema();
        if (input.required() && !schema.nullable()) {
            imports.add("jakarta.validation.constraints.NotNull");
        }
        if (InputRecordRenderer.requiresCascade(schema)) {
            imports.add("jakarta.validation.Valid");
        }
        if (schema.minimum() != null) {
            imports.add("jakarta.validation.constraints.DecimalMin");
        }
        if (schema.maximum() != null) {
            imports.add("jakarta.validation.constraints.DecimalMax");
        }
        if (schema.minLength() != null || schema.maxLength() != null
                || schema.minItems() != null || schema.maxItems() != null) {
            imports.add("jakarta.validation.constraints.Size");
        }
        if (schema.pattern() != null) {
            imports.add("jakarta.validation.constraints.Pattern");
        }
    }

    private void addTypeImports(Set<String> imports, String packageName, String type) {
        if (type.endsWith(".JsonNode")) {
            return;
        }
        if (type.contains("java.math.BigDecimal")) {
            imports.add("java.math.BigDecimal");
        }
        if (type.contains("java.util.List")) {
            imports.add("java.util.List");
        }
        String simple = type.replace("java.math.BigDecimal", "").replace("java.util.List", "")
                .replace("<", "").replace(">", "");
        if (!simple.isBlank() && !simple.contains(".") && !Set.of("String", "Integer", "Long", "Boolean").contains(simple)) {
            imports.add(packageName + ".generated.model." + simple);
        }
    }

    private String shortType(ApiSchema schema, String suggestedName) {
        return JavaSourceRenderer.javaType(schema, suggestedName)
                .replace("java.math.", "")
                .replace("java.util.", "");
    }

    private String toolParameterType(ApiSchema schema, String suggestedName) {
        if (schema.enumValues() != null && !schema.enumValues().isEmpty()) {
            return "String";
        }
        return shortType(schema, suggestedName);
    }

    private String constructorArgument(ToolInput input, String operationClass) {
        String variable = JavaSourceRenderer.lowerCamel(input.name());
        if (input.schema().enumValues() != null && !input.schema().enumValues().isEmpty()) {
            return JavaSourceRenderer.javaType(input.schema(), operationClass + JavaSourceRenderer.upperCamel(input.name()))
                    .replace("java.math.", "").replace("java.util.", "")
                    + ".fromWireValue(" + variable + ")";
        }
        return variable;
    }

}
