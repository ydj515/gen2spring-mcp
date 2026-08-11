package io.gen2spring.mcp.domain.config;

import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record GenerationRequest(
        ProjectCoordinates project,
        String provider,
        String domain,
        String targetProfileId,
        ValidationLevel validationLevel,
        ValidationConfiguration validation,
        List<OperationSelection> operations) {
    public enum ValidationLevel { MCP_PROTOCOL }

    public record ValidationConfiguration(ToolCallValidation toolCall) {}

    public record ToolCallValidation(String operationId, Map<String, Object> arguments) {
        public ToolCallValidation {
            if (operationId == null || operationId.isBlank() || arguments == null) {
                throw new IllegalArgumentException("Tool call validation is incomplete");
            }
            arguments = immutableJsonMap(arguments);
        }
    }

    public record ProjectCoordinates(String groupId, String artifactId, String packageName) {}

    public record OperationSelection(
            String operationId,
            boolean enabled,
            String toolName,
            String toolDescription,
            Map<String, ParameterOverride> parameters,
            ResponseNormalizationPolicy responseNormalization,
            OutputSelection output,
            RetryPolicy retry) {
        public OperationSelection {
            Objects.requireNonNull(output, "output");
        }

        public OperationSelection(
                String operationId,
                boolean enabled,
                String toolName,
                String toolDescription,
                Map<String, ParameterOverride> parameters,
                ResponseNormalizationPolicy responseNormalization,
                OutputSelection output) {
            this(operationId, enabled, toolName, toolDescription, parameters, responseNormalization, output, null);
        }

        public OperationSelection(
                String operationId,
                boolean enabled,
                String toolName,
                String toolDescription,
                Map<String, ParameterOverride> parameters,
                ResponseNormalizationPolicy responseNormalization) {
            this(operationId, enabled, toolName, toolDescription, parameters, responseNormalization,
                    new OutputSelection(McpToolDefinition.OutputKind.GENERIC_JSON));
        }

        public OperationSelection(
                String operationId,
                boolean enabled,
                String toolName,
                String toolDescription,
                Map<String, ParameterOverride> parameters) {
            this(operationId, enabled, toolName, toolDescription, parameters, null);
        }
    }

    public record OutputSelection(McpToolDefinition.OutputKind mode) {
        public OutputSelection {
            Objects.requireNonNull(mode, "mode");
        }
    }

    public record ParameterOverride(
            McpToolDefinition.ParameterSource source,
            String environmentVariable) {}

    private static Map<String, Object> immutableJsonMap(Map<String, ?> values) {
        Map<String, Object> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Tool call arguments contain an invalid key");
            }
            copied.put(key, immutableJsonValue(value));
        });
        return Collections.unmodifiableMap(copied);
    }

    private static Object immutableJsonValue(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (!(key instanceof String stringKey) || stringKey.isBlank()) {
                    throw new IllegalArgumentException("Tool call arguments contain an invalid key");
                }
                copied.put(stringKey, immutableJsonValue(nestedValue));
            });
            return Collections.unmodifiableMap(copied);
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            for (Object item : list) {
                copied.add(immutableJsonValue(item));
            }
            return Collections.unmodifiableList(copied);
        }
        throw new IllegalArgumentException("Tool call arguments must contain JSON-compatible values");
    }
}
