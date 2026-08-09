package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.VALIDATION_ARGUMENT_INVALID;

import io.gen2spring.mcp.domain.config.GenerationRequest.ToolCallValidation;
import io.gen2spring.mcp.domain.config.GenerationRequest.ValidationConfiguration;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedToolCall;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.McpInputDefinition;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ExpectedToolCallFactory {
    public ExpectedToolCall create(List<McpToolDefinition> tools, ValidationConfiguration configuration) {
        ToolCallValidation toolCall = configuration == null ? null : configuration.toolCall();
        if (toolCall == null) {
            throw invalid("operationId");
        }
        McpToolDefinition tool = findTool(tools, toolCall.operationId());
        if (tool == null) {
            throw invalid("operationId");
        }
        Map<String, McpInputDefinition> inputs = inputs(tool);
        Map<String, Object> arguments = toolCall.arguments();
        if (arguments == null) {
            throw invalid("arguments");
        }
        Map<String, Object> normalizedArguments = new LinkedHashMap<>();
        arguments.forEach((name, value) -> {
            McpInputDefinition input = inputs.get(name);
            if (input == null) {
                throw invalid(name);
            }
            normalizedArguments.put(name, normalize(input.schema(), value, input.name()));
        });
        inputs.forEach((name, input) -> {
            if (input.required() && !arguments.containsKey(name)) {
                throw invalid(name);
            }
        });
        return new ExpectedToolCall(tool, normalizedArguments);
    }

    private McpToolDefinition findTool(List<McpToolDefinition> tools, String operationId) {
        if (operationId == null || operationId.isBlank() || tools == null) {
            return null;
        }
        McpToolDefinition match = null;
        for (McpToolDefinition tool : tools) {
            if (tool != null && operationId.equals(tool.operationId())) {
                if (match != null) {
                    return null;
                }
                match = tool;
            }
        }
        return match;
    }

    private Map<String, McpInputDefinition> inputs(McpToolDefinition tool) {
        if (tool.inputs() == null) {
            throw invalid("operationId");
        }
        Map<String, McpInputDefinition> inputs = new HashMap<>();
        for (McpInputDefinition input : tool.inputs()) {
            if (input == null || input.name() == null || input.name().isBlank() || input.schema() == null
                    || inputs.put(input.name(), input) != null) {
                throw invalid("operationId");
            }
        }
        return inputs;
    }

    private Object normalize(ApiSchema schema, Object value, String inputName) {
        if (schema == null || schema.type() == null) {
            throw invalid(inputName);
        }
        return switch (schema.type()) {
            case STRING -> normalizeString(schema, requireType(value, String.class, inputName), inputName);
            case INTEGER -> normalizeInteger(schema, value, inputName);
            case NUMBER -> normalizeNumber(schema, value, inputName);
            case BOOLEAN -> requireType(value, Boolean.class, inputName);
            case ARRAY -> normalizeArray(schema, requireList(value, inputName), inputName);
            case OBJECT -> normalizeObject(schema, requireMap(value, inputName), inputName);
        };
    }

    private String normalizeString(ApiSchema schema, String value, String inputName) {
        if (schema.enumValues() != null && !schema.enumValues().isEmpty() && !schema.enumValues().contains(value)) {
            throw invalid(inputName);
        }
        if (schema.minLength() != null && value.length() < schema.minLength()) {
            throw invalid(inputName);
        }
        if (schema.maxLength() != null && value.length() > schema.maxLength()) {
            throw invalid(inputName);
        }
        if (schema.pattern() != null && !Pattern.compile(schema.pattern()).matcher(value).matches()) {
            throw invalid(inputName);
        }
        return value;
    }

    private Object normalizeInteger(ApiSchema schema, Object value, String inputName) {
        BigDecimal decimal = integral(value, inputName);
        validateNumber(schema, decimal, inputName);
        BigInteger integer = decimal.toBigIntegerExact();
        try {
            if ("int64".equals(schema.format())) {
                return integer.longValueExact();
            }
            return integer.intValueExact();
        } catch (ArithmeticException exception) {
            throw invalid(inputName);
        }
    }

    private BigDecimal normalizeNumber(ApiSchema schema, Object value, String inputName) {
        BigDecimal decimal = decimal(value, inputName);
        validateNumber(schema, decimal, inputName);
        return decimal;
    }

    private void validateNumber(ApiSchema schema, BigDecimal value, String inputName) {
        if (schema.minimum() != null && value.compareTo(schema.minimum()) < 0) {
            throw invalid(inputName);
        }
        if (schema.maximum() != null && value.compareTo(schema.maximum()) > 0) {
            throw invalid(inputName);
        }
    }

    private List<Object> normalizeArray(ApiSchema schema, List<?> value, String inputName) {
        if (schema.items() == null) {
            throw invalid(inputName);
        }
        List<Object> normalized = new ArrayList<>(value.size());
        value.forEach(item -> normalized.add(normalize(schema.items(), item, inputName)));
        return normalized;
    }

    private Map<String, Object> normalizeObject(ApiSchema schema, Map<String, Object> value, String inputName) {
        Map<String, ApiSchema> properties = schema.properties() == null ? Map.of() : schema.properties();
        Map<String, Object> normalized = new LinkedHashMap<>();
        value.forEach((name, propertyValue) -> {
            ApiSchema property = properties.get(name);
            if (property == null) {
                throw invalid(inputName);
            }
            normalized.put(name, normalize(property, propertyValue, inputName));
        });
        List<String> requiredProperties = schema.requiredProperties() == null ? List.of() : schema.requiredProperties();
        for (String property : requiredProperties) {
            if (property == null || !value.containsKey(property)) {
                throw invalid(inputName);
            }
        }
        return normalized;
    }

    private <T> T requireType(Object value, Class<T> type, String inputName) {
        if (!type.isInstance(value)) {
            throw invalid(inputName);
        }
        return type.cast(value);
    }

    private List<?> requireList(Object value, String inputName) {
        if (!(value instanceof List<?> list)) {
            throw invalid(inputName);
        }
        return list;
    }

    private Map<String, Object> requireMap(Object value, String inputName) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(inputName);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((name, propertyValue) -> {
            if (!(name instanceof String propertyName)) {
                throw invalid(inputName);
            }
            values.put(propertyName, propertyValue);
        });
        return values;
    }

    private BigDecimal integral(Object value, String inputName) {
        BigDecimal number = decimal(value, inputName);
        try {
            number.toBigIntegerExact();
            return number;
        } catch (ArithmeticException exception) {
            throw invalid(inputName);
        }
    }

    private BigDecimal decimal(Object value, String inputName) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        throw invalid(inputName);
    }

    private GeneratorException invalid(String safeInputName) {
        return GeneratorException.user(
                VALIDATION_ARGUMENT_INVALID,
                "TOOL_MODEL_VALIDATE",
                "Validation argument does not match Tool input: " + safeInputName);
    }
}
