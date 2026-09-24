package io.gen2spring.mcp.application.generation.validation;

import io.gen2spring.mcp.application.toolmodel.schema.ToolJsonSchemaFactory;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class ExpectedToolSchemaFactory {
    private final ToolJsonSchemaFactory schemaFactory;

    public ExpectedToolSchemaFactory() {
        this(new ToolJsonSchemaFactory());
    }

    ExpectedToolSchemaFactory(ToolJsonSchemaFactory schemaFactory) {
        this.schemaFactory = Objects.requireNonNull(schemaFactory, "schemaFactory");
    }

    public Map<String, ExpectedTool> create(List<ToolDefinition> tools) {
        Map<String, ExpectedTool> expected = new TreeMap<>();
        for (ToolDefinition tool : tools == null ? List.<ToolDefinition>of() : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()
                    || tool.description() == null || tool.description().isBlank()) {
                throw new IllegalArgumentException("Generated Tool metadata is incomplete");
            }
            ExpectedTool prior = expected.put(
                    tool.name(), new ExpectedTool(tool.description(), schemaFactory.inputSchema(tool.inputs())));
            if (prior != null) {
                throw new IllegalArgumentException("Generated Tool names must be unique");
            }
        }
        return Collections.unmodifiableMap(expected);
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

}
