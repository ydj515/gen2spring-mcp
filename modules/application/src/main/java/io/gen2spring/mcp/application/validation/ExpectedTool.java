package io.gen2spring.mcp.application.validation;

import java.util.Map;

public record ExpectedTool(String description, Map<String, Object> inputSchema) {
    public ExpectedTool {
        if (description == null || inputSchema == null) {
            throw new IllegalArgumentException("Expected Tool metadata is incomplete");
        }
        inputSchema = ValidationJsonValue.immutableMap(inputSchema, false, false);
    }
}
