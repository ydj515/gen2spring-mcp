package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.VALIDATION_ARGUMENT_INVALID;

import io.gen2spring.mcp.domain.config.GenerationRequest.ToolCallValidation;
import io.gen2spring.mcp.domain.config.GenerationRequest.ValidationConfiguration;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedToolCall;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedUpstreamInteraction;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedUpstreamOutcome;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedUpstreamResponse;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.McpInputDefinition;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class ExpectedToolCallFactory {
    private static final int MAX_VALIDATION_PATTERN_CHARACTERS = 512;
    private static final int MAX_VALIDATION_PATTERN_GROUP_DEPTH = 32;
    private static final int MAX_PATTERN_CHARACTER_ACCESSES = 100_000;

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
        var response = new ExpectedToolResponseFactory().create(tool);
        List<ExpectedUpstreamInteraction> interactions = retryInteractions(tool, response.upstreamInteractions());
        return new ExpectedToolCall(
                tool,
                normalizedArguments,
                interactions,
                response.expectedResult());
    }

    private List<ExpectedUpstreamInteraction> retryInteractions(
            McpToolDefinition tool, List<ExpectedUpstreamInteraction> successful) {
        var retry = tool.execution() == null ? null : tool.execution().retryPolicy();
        if (retry == null) {
            return successful;
        }
        ExpectedUpstreamInteraction first = successful.getFirst();
        ExpectedUpstreamInteraction failure;
        if (retry.statusCodes() != null && !retry.statusCodes().isEmpty()) {
            failure = new ExpectedUpstreamInteraction(
                    first.internalParameters(), ExpectedUpstreamOutcome.RESPONSE,
                    new ExpectedUpstreamResponse(
                            retry.statusCodes().getFirst(), "application/json", Map.of("retryable", true)));
        } else {
            failure = new ExpectedUpstreamInteraction(
                    first.internalParameters(), ExpectedUpstreamOutcome.DISCONNECT, null);
        }
        List<ExpectedUpstreamInteraction> result = new ArrayList<>(successful.size() + 1);
        result.add(failure);
        result.addAll(successful);
        return List.copyOf(result);
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
        if (schema.pattern() != null && !matchesPatternSafely(schema.pattern(), value)) {
            throw invalid(inputName);
        }
        return value;
    }

    private boolean matchesPatternSafely(String expression, String value) {
        if (!hasSafePatternShape(expression)) {
            return false;
        }
        try {
            Pattern pattern = Pattern.compile(expression);
            return pattern.matcher(new BudgetedCharSequence(value, MAX_PATTERN_CHARACTER_ACCESSES)).matches();
        } catch (PatternSyntaxException | PatternBudgetExceededException | StackOverflowError failure) {
            return false;
        }
    }

    private boolean hasSafePatternShape(String expression) {
        if (expression.length() > MAX_VALIDATION_PATTERN_CHARACTERS) {
            return false;
        }
        Deque<PatternGroup> groups = new ArrayDeque<>();
        boolean escaped = false;
        boolean characterClass = false;
        boolean quoted = false;
        boolean lastClosedGroupRisky = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (quoted) {
                if (current == '\\' && index + 1 < expression.length() && expression.charAt(index + 1) == 'E') {
                    quoted = false;
                    index++;
                }
                continue;
            }
            if (escaped) {
                if (current == 'Q') {
                    quoted = true;
                }
                escaped = false;
                lastClosedGroupRisky = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                lastClosedGroupRisky = false;
                continue;
            }
            if (characterClass) {
                if (current == ']') {
                    characterClass = false;
                }
                continue;
            }
            if (current == '[') {
                characterClass = true;
                lastClosedGroupRisky = false;
                continue;
            }
            if (current == '(') {
                groups.push(new PatternGroup());
                if (groups.size() > MAX_VALIDATION_PATTERN_GROUP_DEPTH) {
                    return false;
                }
                lastClosedGroupRisky = false;
                continue;
            }
            if (current == ')') {
                if (groups.isEmpty()) {
                    lastClosedGroupRisky = false;
                    continue;
                }
                PatternGroup completed = groups.pop();
                lastClosedGroupRisky = completed.risky;
                if (!groups.isEmpty() && completed.risky) {
                    groups.peek().risky = true;
                }
                continue;
            }
            boolean groupPrefix = current == '?' && !groups.isEmpty() && groups.peek().empty;
            boolean quantifier = current == '*' || current == '+' || current == '{' || current == '?' && !groupPrefix;
            if (quantifier) {
                if (lastClosedGroupRisky) {
                    return false;
                }
                if (!groups.isEmpty()) {
                    groups.peek().risky = true;
                }
            } else if (current == '|' && !groups.isEmpty()) {
                groups.peek().risky = true;
            }
            if (!groups.isEmpty() && current != ':' && !groupPrefix) {
                groups.peek().empty = false;
            }
            if (!quantifier) {
                lastClosedGroupRisky = false;
            }
        }
        return true;
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

    private static final class PatternGroup {
        private boolean empty = true;
        private boolean risky;
    }

    private static final class BudgetedCharSequence implements CharSequence {
        private final String value;
        private final PatternBudget budget;
        private final int start;
        private final int end;

        private BudgetedCharSequence(String value, int maximumAccesses) {
            this(value, new PatternBudget(maximumAccesses), 0, value.length());
        }

        private BudgetedCharSequence(String value, PatternBudget budget, int start, int end) {
            this.value = value;
            this.budget = budget;
            this.start = start;
            this.end = end;
        }

        @Override
        public int length() {
            return end - start;
        }

        @Override
        public char charAt(int index) {
            if (index < 0 || index >= length()) {
                throw new IndexOutOfBoundsException(index);
            }
            budget.consume();
            return value.charAt(start + index);
        }

        @Override
        public CharSequence subSequence(int subsequenceStart, int subsequenceEnd) {
            if (subsequenceStart < 0 || subsequenceEnd < subsequenceStart || subsequenceEnd > length()) {
                throw new IndexOutOfBoundsException();
            }
            return new BudgetedCharSequence(
                    value, budget, start + subsequenceStart, start + subsequenceEnd);
        }

        @Override
        public String toString() {
            return value.substring(start, end);
        }
    }

    private static final class PatternBudget {
        private int remaining;

        private PatternBudget(int remaining) {
            this.remaining = remaining;
        }

        private void consume() {
            if (remaining-- <= 0) {
                throw new PatternBudgetExceededException();
            }
        }
    }

    private static final class PatternBudgetExceededException extends RuntimeException {
        private PatternBudgetExceededException() {
            super(null, null, false, false);
        }
    }
}
