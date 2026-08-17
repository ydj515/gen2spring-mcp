package io.gen2spring.mcp.application.validation;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.ToolInput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ExpectedToolSchemaFactory {
    public Map<String, ExpectedTool> create(List<ToolDefinition> tools) {
        Map<String, ExpectedTool> expected = new TreeMap<>();
        for (ToolDefinition tool : tools == null ? List.<ToolDefinition>of() : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()
                    || tool.description() == null || tool.description().isBlank()) {
                throw new IllegalArgumentException("Generated Tool metadata is incomplete");
            }
            ExpectedTool prior = expected.put(
                    tool.name(), new ExpectedTool(tool.description(), inputSchema(tool.inputs())));
            if (prior != null) {
                throw new IllegalArgumentException("Generated Tool names must be unique");
            }
        }
        return Collections.unmodifiableMap(expected);
    }

    private Map<String, Object> inputSchema(List<ToolInput> inputs) {
        Map<String, Object> properties = new TreeMap<>();
        List<String> required = new ArrayList<>();
        for (ToolInput input : inputs == null ? List.<ToolInput>of() : inputs) {
            if (input == null || input.name() == null || input.name().isBlank()
                    || input.jsonName() == null || input.jsonName().isBlank() || input.schema() == null) {
                throw new IllegalArgumentException("Generated Tool input metadata is incomplete");
            }
            Map<String, Object> property = new LinkedHashMap<>(schema(input.schema()));
            property.put("description", description(input.description(), input.jsonName()));
            if (properties.put(input.name(), Collections.unmodifiableMap(property)) != null) {
                throw new IllegalArgumentException("Generated Tool input names must be unique");
            }
            if (input.required()) {
                required.add(input.name());
            }
        }
        required.sort(String::compareTo);
        return immutableObjectSchema(properties, required);
    }

    private Map<String, Object> schema(ApiSchema schema) {
        requireSupported(schema);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("type", schemaType(schema.type()));
        switch (schema.type()) {
            case STRING -> addStringConstraints(expected, schema);
            case INTEGER -> {
                expected.put("format", "int64".equals(schema.format()) ? "int64" : "int32");
                addNumericConstraints(expected, schema);
            }
            case NUMBER -> {
                if (schema.format() != null && !schema.format().isBlank()) {
                    expected.put("format", schema.format());
                }
                addNumericConstraints(expected, schema);
            }
            case ARRAY -> {
                expected.put("items", schema(schema.items()));
                if (schema.minItems() != null) {
                    expected.put("minItems", schema.minItems());
                }
            }
            case OBJECT -> expected.putAll(objectSchema(schema));
            case BOOLEAN -> {
                // Boolean schemas have no additional supported P0 constraints.
            }
        }
        Map<String, Object> nonNullSchema = Collections.unmodifiableMap(expected);
        if (!schema.nullable()) {
            return nonNullSchema;
        }
        Map<String, Object> nullableSchema = new LinkedHashMap<>();
        nullableSchema.put("anyOf", List.of(nonNullSchema, Map.of("type", "null")));
        return Collections.unmodifiableMap(nullableSchema);
    }

    private void addStringConstraints(Map<String, Object> expected, ApiSchema schema) {
        if (schema.format() != null && !schema.format().isBlank()) {
            expected.put("format", schema.format());
        }
        if (schema.enumValues() != null && !schema.enumValues().isEmpty()) {
            expected.put("enum", List.copyOf(schema.enumValues()));
        }
        if (schema.minLength() != null) {
            expected.put("minLength", schema.minLength());
        }
        if (schema.maxLength() != null) {
            expected.put("maxLength", schema.maxLength());
        }
        if (schema.pattern() != null) {
            expected.put("pattern", schema.pattern());
        }
    }

    private void addNumericConstraints(Map<String, Object> expected, ApiSchema schema) {
        if (schema.minimum() != null) {
            expected.put("minimum", schema.minimum());
        }
        if (schema.maximum() != null) {
            expected.put("maximum", schema.maximum());
        }
    }

    private Map<String, Object> objectSchema(ApiSchema schema) {
        Map<String, ApiSchema> sourceProperties = schema.properties() == null ? Map.of() : schema.properties();
        Map<String, Object> properties = new TreeMap<>();
        sourceProperties.forEach((name, propertySchema) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Generated nested property names must be non-blank");
            }
            Map<String, Object> property = new LinkedHashMap<>(schema(propertySchema));
            property.put("description", name);
            properties.put(name, Collections.unmodifiableMap(property));
        });
        List<String> sourceRequired = schema.requiredProperties() == null
                ? List.of() : schema.requiredProperties();
        if (sourceRequired.stream().anyMatch(name -> name == null || !properties.containsKey(name))) {
            throw new IllegalArgumentException("Generated nested required properties must exist");
        }
        List<String> required = sourceRequired.stream().distinct().sorted().toList();
        return immutableObjectSchema(properties, required);
    }

    private Map<String, Object> immutableObjectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(new TreeMap<>(properties)));
        schema.put("required", List.copyOf(required));
        return Collections.unmodifiableMap(schema);
    }

    private void requireSupported(ApiSchema schema) {
        if (schema == null || schema.type() == null || !schema.supported()) {
            throw new IllegalArgumentException("Generated Tool schema metadata is incomplete");
        }
        if (schema.type() != SchemaType.STRING
                && schema.enumValues() != null && !schema.enumValues().isEmpty()) {
            throw new IllegalArgumentException("Only string enum schemas are supported");
        }
        if (schema.minItems() != null && (schema.type() != SchemaType.ARRAY || schema.minItems() < 0)) {
            throw new IllegalArgumentException("Generated array schema metadata is invalid");
        }
    }

    private String description(String description, String fallback) {
        return description == null || description.isBlank() ? fallback : description;
    }

    public static String springAiEnumValue(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Generated enum values must be non-empty");
        }
        StringBuilder constant = new StringBuilder();
        boolean separator = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                if (separator && !constant.isEmpty()) {
                    constant.append('_');
                }
                constant.append(Character.toUpperCase(character));
                separator = false;
            } else {
                separator = true;
            }
        }
        if (constant.isEmpty()) {
            constant.append("VALUE");
        }
        if (Character.isDigit(constant.charAt(0))) {
            constant.insert(0, "VALUE_");
        }
        return constant.toString();
    }

    private String schemaType(SchemaType type) {
        return switch (type) {
            case STRING -> "string";
            case INTEGER -> "integer";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case ARRAY -> "array";
            case OBJECT -> "object";
        };
    }
}
