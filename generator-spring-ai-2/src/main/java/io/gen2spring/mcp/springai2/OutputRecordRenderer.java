package io.gen2spring.mcp.springai2;

import io.gen2spring.mcp.application.validation.ExpectedToolSchemaFactory;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class OutputRecordRenderer {
    Map<String, String> render(
            String packageName,
            String packagePath,
            String operationClass,
            ApiSchema resultSchema) {
        if (resultSchema == null || !resultSchema.supported() || resultSchema.type() != SchemaType.OBJECT) {
            throw JavaSourceRenderer.invalid("Typed Tool output requires a supported object result schema");
        }
        Map<String, String> sources = new TreeMap<>();
        renderSchema(sources, packageName, packagePath, operationClass + "Result", resultSchema,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        return Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }

    private void renderSchema(
            Map<String, String> sources,
            String packageName,
            String packagePath,
            String suggestedName,
            ApiSchema schema,
            Set<ApiSchema> visiting) {
        if (schema == null || !schema.supported() || !visiting.add(schema)) {
            throw JavaSourceRenderer.invalid("Typed Tool output contains an unsupported recursive schema");
        }
        try {
            if (schema.enumValues() != null && !schema.enumValues().isEmpty()) {
                renderEnum(sources, packageName, packagePath,
                        JavaSourceRenderer.upperCamel(suggestedName) + "Value", schema.enumValues());
                return;
            }
            if (schema.type() == SchemaType.ARRAY) {
                renderSchema(sources, packageName, packagePath, suggestedName + "Item", schema.items(), visiting);
                return;
            }
            if (schema.type() != SchemaType.OBJECT) {
                return;
            }
            String className = JavaSourceRenderer.upperCamel(suggestedName);
            List<Property> properties = properties(schema);
            for (Property property : properties) {
                renderSchema(sources, packageName, packagePath,
                        className + JavaSourceRenderer.upperCamel(property.javaName()), property.schema(), visiting);
            }
            renderRecord(sources, packageName, packagePath, className, properties);
        } finally {
            visiting.remove(schema);
        }
    }

    private List<Property> properties(ApiSchema schema) {
        List<Property> properties = new ArrayList<>();
        Set<String> javaNames = new HashSet<>();
        Map<String, ApiSchema> values = schema.properties() == null ? Map.of() : schema.properties();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String jsonName = JavaSourceRenderer.requireJsonPropertyName(entry.getKey());
            String javaName = JavaSourceRenderer.nestedInputName(jsonName);
            if (!javaNames.add(javaName)) {
                throw JavaSourceRenderer.invalid("Generated output Java property names must be unique");
            }
            properties.add(new Property(jsonName, javaName, entry.getValue()));
        });
        return List.copyOf(properties);
    }

    private void renderRecord(
            Map<String, String> sources,
            String packageName,
            String packagePath,
            String className,
            List<Property> properties) {
        Set<String> imports = new TreeSet<>();
        for (Property property : properties) {
            if (!property.jsonName().equals(property.javaName())) {
                imports.add("com.fasterxml.jackson.annotation.JsonProperty");
            }
            String type = type(property.schema(), className + JavaSourceRenderer.upperCamel(property.javaName()));
            if (type.contains("java.math.BigDecimal")) {
                imports.add("java.math.BigDecimal");
            }
            if (type.contains("java.util.List")) {
                imports.add("java.util.List");
            }
        }
        StringBuilder source = new StringBuilder("package ").append(packageName).append(".generated.model;\n\n");
        imports.forEach(value -> source.append("import ").append(value).append(";\n"));
        if (!imports.isEmpty()) {
            source.append('\n');
        }
        source.append("public record ").append(className).append("(\n");
        for (int index = 0; index < properties.size(); index++) {
            Property property = properties.get(index);
            source.append("        ");
            if (!property.jsonName().equals(property.javaName())) {
                source.append("@JsonProperty(").append(JavaStringLiteral.quote(property.jsonName())).append(") ");
            }
            source.append(shortType(property.schema(), className + JavaSourceRenderer.upperCamel(property.javaName())))
                    .append(' ').append(property.javaName())
                    .append(index + 1 == properties.size() ? ") {}\n" : ",\n");
        }
        if (properties.isEmpty()) {
            source.append(") {}\n");
        }
        putUnique(sources, "src/main/java/" + packagePath + "/generated/model/" + className + ".java",
                source.toString());
    }

    private void renderEnum(
            Map<String, String> sources,
            String packageName,
            String packagePath,
            String className,
            List<String> values) {
        Set<String> constants = new HashSet<>();
        StringBuilder source = new StringBuilder("package ").append(packageName).append(".generated.model;\n\n")
                .append("import com.fasterxml.jackson.annotation.JsonCreator;\n")
                .append("import com.fasterxml.jackson.annotation.JsonProperty;\n")
                .append("import com.fasterxml.jackson.annotation.JsonValue;\n\n")
                .append("public enum ").append(className).append(" {\n");
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value == null || value.isEmpty() || value.length() > 256
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw JavaSourceRenderer.invalid("Generated enum wire values must be safe bounded strings");
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
                .append("    @JsonCreator\n")
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
                .append("    }\n")
                .append("}\n");
        putUnique(sources, "src/main/java/" + packagePath + "/generated/model/" + className + ".java",
                source.toString());
    }

    private String shortType(ApiSchema schema, String suggestedName) {
        return type(schema, suggestedName).replace("java.math.", "").replace("java.util.", "");
    }

    private String type(ApiSchema schema, String suggestedName) {
        if (schema == null || schema.type() == null || !schema.supported()) {
            throw JavaSourceRenderer.invalid("Typed Tool output requires supported schemas");
        }
        if (schema.enumValues() != null && !schema.enumValues().isEmpty()) {
            return JavaSourceRenderer.upperCamel(suggestedName) + "Value";
        }
        return switch (schema.type()) {
            case STRING -> "String";
            case INTEGER -> "int64".equals(schema.format()) ? "Long" : "Integer";
            case NUMBER -> "java.math.BigDecimal";
            case BOOLEAN -> "Boolean";
            case ARRAY -> "java.util.List<" + type(schema.items(), suggestedName + "Item") + ">";
            case OBJECT -> JavaSourceRenderer.upperCamel(suggestedName);
        };
    }

    private void putUnique(Map<String, String> sources, String path, String source) {
        if (sources.putIfAbsent(path, source) != null) {
            throw JavaSourceRenderer.invalid("Generated output source paths must be unique");
        }
    }

    private record Property(String jsonName, String javaName, ApiSchema schema) {}
}
