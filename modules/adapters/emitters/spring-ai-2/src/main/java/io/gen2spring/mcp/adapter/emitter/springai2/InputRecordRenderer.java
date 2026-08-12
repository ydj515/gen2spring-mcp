package io.gen2spring.mcp.adapter.emitter.springai2;

import io.gen2spring.mcp.adapter.emitter.support.JavaStringLiteral;

import io.gen2spring.mcp.application.validation.ExpectedToolSchemaFactory;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.ToolInput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class InputRecordRenderer {
    Map<String, String> render(String packageName, String packagePath, List<ToolDefinition> tools) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) {
            String className = JavaSourceRenderer.upperCamel(tool.operationId()) + "Input";
            renderInputRecord(sources, packageName, packagePath, className, inputs(tool));
        }
        return sources;
    }

    static List<ToolInput> inputs(ToolDefinition tool) {
        return tool.inputs() == null ? List.of() : tool.inputs();
    }

    static String annotations(ToolInput input) {
        return annotations(input.schema(), input.required(), input.jsonName(), JavaSourceRenderer.lowerCamel(input.name()));
    }

    static String validationAnnotations(ToolInput input) {
        return validationAnnotations(input.schema(), input.required());
    }

    private void renderInputRecord(
            Map<String, String> sources,
            String packageName,
            String packagePath,
            String className,
            List<ToolInput> inputs) {
        String operationClass = className.substring(0, className.length() - "Input".length());
        for (ToolInput input : inputs) {
            renderNestedTypes(sources, packageName, packagePath,
                    JavaSourceRenderer.upperCamel(operationClass + JavaSourceRenderer.upperCamel(input.name())),
                    input.schema(), new HashSet<>());
        }

        Set<String> imports = imports(inputs);
        StringBuilder source = new StringBuilder("package ").append(packageName).append(".generated.model;\n\n");
        imports.forEach(value -> source.append("import ").append(value).append(";\n"));
        if (!imports.isEmpty()) {
            source.append('\n');
        }
        source.append("public record ").append(className).append("(\n");
        for (int index = 0; index < inputs.size(); index++) {
            ToolInput input = inputs.get(index);
            source.append("        ").append(annotations(input));
            if (!annotations(input).isEmpty()) {
                source.append(' ');
            }
            source.append(shortType(input.schema(), operationClass
                            + JavaSourceRenderer.upperCamel(input.name())))
                    .append(' ').append(JavaSourceRenderer.lowerCamel(input.name()))
                    .append(index + 1 == inputs.size() ? ") {\n" : ",\n");
        }
        if (inputs.isEmpty()) {
            source.append(") {\n");
        }
        source.append("    public Map<String, Object> toArguments() {\n")
                .append("        Map<String, Object> arguments = new LinkedHashMap<>();\n");
        for (ToolInput input : inputs) {
            String name = JavaSourceRenderer.lowerCamel(input.name());
            if (input.required()) {
                source.append("        arguments.put(").append(JavaStringLiteral.quote(input.name()))
                        .append(", ").append(name).append(");\n");
            } else {
                source.append("        if (").append(name).append(" != null) {\n")
                        .append("            arguments.put(").append(JavaStringLiteral.quote(input.name()))
                        .append(", ").append(name).append(");\n")
                        .append("        }\n");
            }
        }
        source.append("        return Collections.unmodifiableMap(arguments);\n")
                .append("    }\n")
                .append("}\n");
        putUnique(sources,
                "src/main/java/" + packagePath + "/generated/model/" + className + ".java",
                source.toString());
    }

    private void renderNestedTypes(
            Map<String, String> sources,
            String packageName,
            String packagePath,
            String suggestedName,
            ApiSchema schema,
            Set<ApiSchema> visiting) {
        if (schema == null || !visiting.add(schema)) {
            if (schema != null) {
                throw JavaSourceRenderer.invalid("Recursive schemas are not supported by the P0 renderer");
            }
            return;
        }
        try {
            if (schema.enumValues() != null && !schema.enumValues().isEmpty()) {
                renderEnum(sources, packageName, packagePath,
                        JavaSourceRenderer.upperCamel(suggestedName) + "Value", schema.enumValues());
            } else if (schema.type() == SchemaType.OBJECT) {
                String className = JavaSourceRenderer.upperCamel(suggestedName);
                List<ToolInput> properties = new ArrayList<>();
                Set<String> javaNames = new HashSet<>();
                List<String> required = schema.requiredProperties() == null ? List.of() : schema.requiredProperties();
                schema.properties().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    String jsonName = JavaSourceRenderer.requireJsonPropertyName(entry.getKey());
                    String javaName = JavaSourceRenderer.nestedInputName(jsonName);
                    if (!javaNames.add(javaName)) {
                        throw JavaSourceRenderer.invalid("Generated nested Java property names must be unique");
                    }
                    properties.add(new ToolInput(
                            javaName, jsonName, null, required.contains(jsonName), entry.getValue()));
                });
                for (ToolInput property : properties) {
                    renderNestedTypes(sources, packageName, packagePath,
                            className + JavaSourceRenderer.upperCamel(property.name()), property.schema(), visiting);
                }
                renderValueRecord(sources, packageName, packagePath, className, properties);
            } else if (schema.type() == SchemaType.ARRAY) {
                renderNestedTypes(sources, packageName, packagePath, suggestedName + "Item", schema.items(), visiting);
            }
        } finally {
            visiting.remove(schema);
        }
    }

    private void renderValueRecord(
            Map<String, String> sources,
            String packageName,
            String packagePath,
            String className,
            List<ToolInput> properties) {
        Set<String> imports = validationImports(properties);
        if (!properties.isEmpty()) {
            imports.add("org.springframework.ai.mcp.annotation.McpToolParam");
        }
        StringBuilder source = new StringBuilder("package ").append(packageName).append(".generated.model;\n\n");
        imports.forEach(value -> source.append("import ").append(value).append(";\n"));
        if (!imports.isEmpty()) {
            source.append('\n');
        }
        source.append("public record ").append(className).append("(\n");
        for (int index = 0; index < properties.size(); index++) {
            ToolInput property = properties.get(index);
            String annotations = nestedAnnotations(property);
            source.append("        ").append(annotations);
            if (!annotations.isEmpty()) {
                source.append(' ');
            }
            source.append(shortType(property.schema(), className + JavaSourceRenderer.upperCamel(property.name())))
                    .append(' ').append(JavaSourceRenderer.lowerCamel(property.name()))
                    .append(index + 1 == properties.size() ? ") {}\n" : ",\n");
        }
        if (properties.isEmpty()) {
            source.append(") {}\n");
        }
        putUnique(sources, "src/main/java/" + packagePath + "/generated/model/" + className + ".java", source.toString());
    }

    private String nestedAnnotations(ToolInput input) {
        String description = input.description() == null || input.description().isBlank()
                ? input.jsonName() : input.description();
        String schemaAnnotation = "@McpToolParam(description = " + JavaStringLiteral.quote(description)
                + ", required = " + input.required() + ")";
        String validation = annotations(input);
        return validation.isEmpty() ? schemaAnnotation : schemaAnnotation + " " + validation;
    }

    private void renderEnum(
            Map<String, String> sources,
            String packageName,
            String packagePath,
            String className,
            List<String> values) {
        Set<String> constants = new HashSet<>();
        StringBuilder source = new StringBuilder("package ").append(packageName).append(".generated.model;\n\n")
                .append("import com.fasterxml.jackson.annotation.JsonProperty;\n")
                .append("import com.fasterxml.jackson.annotation.JsonValue;\n\n")
                .append("public enum ").append(className).append(" {\n");
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value == null || value.isEmpty() || value.length() > 256) {
                throw JavaSourceRenderer.invalid("Generated enum wire values must be non-empty and at most 256 characters");
            }
            String constant = JavaIdentifier.requireIdentifier(ExpectedToolSchemaFactory.springAiEnumValue(value));
            if (!constants.add(constant)) {
                throw JavaSourceRenderer.invalid("Generated enum constants must be unique");
            }
            source.append("    @JsonProperty(").append(JavaStringLiteral.quote(value)).append(")\n")
                    .append("    ").append(constant).append("(").append(JavaStringLiteral.quote(value)).append(")")
                    .append(index + 1 == values.size() ? ";\n" : ",\n");
        }
        source.append("\n    private final String wireValue;\n\n")
                .append("    ").append(className).append("(String wireValue) {\n")
                .append("        this.wireValue = wireValue;\n")
                .append("    }\n\n")
                .append("    @JsonValue\n")
                .append("    public String wireValue() {\n")
                .append("        return wireValue;\n")
                .append("    }\n\n")
                .append("    public static ").append(className).append(" fromWireValue(String value) {\n")
                .append("        if (value == null) {\n")
                .append("            return null;\n")
                .append("        }\n")
                .append("        for (").append(className).append(" candidate : values()) {\n")
                .append("            if (candidate.wireValue.equals(value)) {\n")
                .append("                return candidate;\n")
                .append("            }\n")
                .append("        }\n")
                .append("        throw new IllegalArgumentException(\"Unsupported enum wire value\");\n")
                .append("    }\n\n")
                .append("    @Override\n")
                .append("    public String toString() {\n")
                .append("        return wireValue;\n")
                .append("    }\n")
                .append("}\n");
        putUnique(sources, "src/main/java/" + packagePath + "/generated/model/" + className + ".java", source.toString());
    }

    private Set<String> imports(List<ToolInput> inputs) {
        Set<String> imports = validationImports(inputs);
        imports.add("java.util.Collections");
        imports.add("java.util.LinkedHashMap");
        imports.add("java.util.Map");
        return imports;
    }

    private Set<String> validationImports(List<ToolInput> inputs) {
        Set<String> imports = new java.util.TreeSet<>();
        for (ToolInput input : inputs) {
            if (!input.jsonName().equals(JavaSourceRenderer.lowerCamel(input.name()))) {
                imports.add("com.fasterxml.jackson.annotation.JsonProperty");
            }
            if (requiresCascade(input.schema())) {
                imports.add("jakarta.validation.Valid");
            }
            if (input.required()) {
                imports.add("jakarta.validation.constraints.NotNull");
            }
            ApiSchema schema = input.schema();
            if (!isEnumSchema(schema) && schema.minimum() != null) {
                imports.add("jakarta.validation.constraints.DecimalMin");
            }
            if (!isEnumSchema(schema) && schema.maximum() != null) {
                imports.add("jakarta.validation.constraints.DecimalMax");
            }
            if (!isEnumSchema(schema) && (schema.minLength() != null || schema.maxLength() != null)) {
                imports.add("jakarta.validation.constraints.Size");
            }
            if (!isEnumSchema(schema) && schema.pattern() != null) {
                imports.add("jakarta.validation.constraints.Pattern");
            }
            addTypeImports(imports, schema);
        }
        return imports;
    }

    private void addTypeImports(Set<String> imports, ApiSchema schema) {
        if (schema.type() == SchemaType.NUMBER) {
            imports.add("java.math.BigDecimal");
        } else if (schema.type() == SchemaType.ARRAY) {
            imports.add("java.util.List");
            addTypeImports(imports, schema.items());
        }
    }

    private static String annotations(ApiSchema schema, boolean required, String jsonName, String javaName) {
        List<String> annotations = new ArrayList<>();
        if (!jsonName.equals(javaName)) {
            annotations.add("@JsonProperty(" + JavaStringLiteral.quote(jsonName) + ")");
        }
        String validation = validationAnnotations(schema, required);
        if (!validation.isEmpty()) {
            annotations.add(validation);
        }
        return String.join(" ", annotations);
    }

    private static String validationAnnotations(ApiSchema schema, boolean required) {
        List<String> annotations = new ArrayList<>();
        if (requiresCascade(schema)) {
            annotations.add("@Valid");
        }
        if (required) {
            annotations.add("@NotNull");
        }
        if (isEnumSchema(schema)) {
            return String.join(" ", annotations);
        }
        if (schema.minimum() != null) {
            annotations.add("@DecimalMin(" + JavaStringLiteral.quote(schema.minimum().toPlainString()) + ")");
        }
        if (schema.maximum() != null) {
            annotations.add("@DecimalMax(" + JavaStringLiteral.quote(schema.maximum().toPlainString()) + ")");
        }
        if (schema.minLength() != null || schema.maxLength() != null) {
            int minimum = schema.minLength() == null ? 0 : schema.minLength();
            int maximum = schema.maxLength() == null ? Integer.MAX_VALUE : schema.maxLength();
            annotations.add("@Size(min = " + minimum + ", max = " + maximum + ")");
        }
        if (schema.pattern() != null) {
            annotations.add("@Pattern(regexp = " + JavaStringLiteral.quote(schema.pattern()) + ")");
        }
        return String.join(" ", annotations);
    }

    private static boolean isEnumSchema(ApiSchema schema) {
        return schema.enumValues() != null && !schema.enumValues().isEmpty();
    }

    static boolean requiresCascade(ApiSchema schema) {
        if (schema.type() == SchemaType.OBJECT) {
            return true;
        }
        return schema.type() == SchemaType.ARRAY && schema.items() != null && requiresCascade(schema.items());
    }

    private String shortType(ApiSchema schema, String suggestedName) {
        return JavaSourceRenderer.javaType(schema, suggestedName)
                .replace("java.math.", "")
                .replace("java.util.", "");
    }

    private void putUnique(Map<String, String> sources, String path, String source) {
        if (sources.putIfAbsent(path, source) != null) {
            throw JavaSourceRenderer.invalid("Generated model source paths must be unique");
        }
    }
}
