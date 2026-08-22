package io.gen2spring.mcp.application.toolmodel.schema;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.CompositionKind;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SchemaValueValidator {
    private static final String SAFE_MESSAGE = "Schema value is invalid";
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 65_536;

    public void validate(ApiSchema schema, Object value) {
        validate(schema, value, 0, new Budget());
    }

    public void validate(Map<String, Object> jsonSchema, Object value) {
        validate(jsonSchema, value, 0, new Budget());
    }

    private void validate(ApiSchema schema, Object value, int depth, Budget budget) {
        reserve(depth, budget);
        if (schema == null || !schema.supported() || schema.type() == null) throw invalid();
        if (value == null) {
            if (!schema.nullable()) throw invalid();
            return;
        }
        switch (schema.type()) {
            case STRING -> string(schema, value);
            case INTEGER -> integer(schema, value);
            case NUMBER -> number(schema, value);
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) throw invalid();
            }
            case ARRAY -> array(schema, value, depth, budget);
            case OBJECT -> object(schema, value, depth, budget);
            case COMPOSED -> composed(schema, value, depth, budget);
        }
    }

    private void string(ApiSchema schema, Object value) {
        if (!(value instanceof String string)
                || schema.enumValues() != null && !schema.enumValues().isEmpty()
                        && !schema.enumValues().contains(string)
                || schema.minLength() != null && string.length() < schema.minLength()
                || schema.maxLength() != null && string.length() > schema.maxLength()
                || schema.pattern() != null && !matches(schema.pattern(), string)) {
            throw invalid();
        }
    }

    private void integer(ApiSchema schema, Object value) {
        BigDecimal decimal = decimal(value);
        try {
            BigInteger integer = decimal.toBigIntegerExact();
            if ("int32".equals(schema.format())) integer.intValueExact();
            if ("int64".equals(schema.format())) integer.longValueExact();
        } catch (ArithmeticException failure) {
            throw invalid();
        }
        bounds(schema, decimal);
    }

    private void number(ApiSchema schema, Object value) {
        bounds(schema, decimal(value));
    }

    private void bounds(ApiSchema schema, BigDecimal value) {
        if (schema.minimum() != null && value.compareTo(schema.minimum()) < 0
                || schema.maximum() != null && value.compareTo(schema.maximum()) > 0) {
            throw invalid();
        }
    }

    private void array(ApiSchema schema, Object value, int depth, Budget budget) {
        List<?> values = list(value);
        if (schema.items() == null
                || schema.minItems() != null && values.size() < schema.minItems()
                || schema.maxItems() != null && values.size() > schema.maxItems()) {
            throw invalid();
        }
        Set<CanonicalJsonValue.Value> unique = schema.uniqueItems() ? new HashSet<>() : null;
        for (Object item : values) {
            validate(schema.items(), item, depth + 1, budget);
            if (unique != null && !unique.add(CanonicalJsonValue.of(item))) throw invalid();
        }
    }

    private void object(ApiSchema schema, Object value, int depth, Budget budget) {
        Map<String, Object> values = object(value);
        Map<String, ApiSchema> properties = schema.properties() == null ? Map.of() : schema.properties();
        if (!properties.keySet().containsAll(values.keySet())) throw invalid();
        List<String> required = schema.requiredProperties() == null ? List.of() : schema.requiredProperties();
        if (!values.keySet().containsAll(required)) throw invalid();
        values.forEach((name, nested) -> validate(properties.get(name), nested, depth + 1, budget));
    }

    private void composed(ApiSchema schema, Object value, int depth, Budget budget) {
        if (schema.composition() == null) throw invalid();
        int matches = 0;
        for (ApiSchema branch : schema.composition().branches()) {
            try {
                validate(branch, value, depth + 1, budget.fork());
                matches++;
            } catch (IllegalArgumentException ignored) {
                // A non-matching branch is part of normal composition evaluation.
            }
        }
        if (schema.composition().kind() == CompositionKind.ONE_OF ? matches != 1 : matches < 1) throw invalid();
    }

    private void validate(Map<String, Object> schema, Object value, int depth, Budget budget) {
        reserve(depth, budget);
        if (schema == null) throw invalid();
        if (schema.isEmpty()) return;
        Object oneOf = schema.get("oneOf");
        Object anyOf = schema.get("anyOf");
        if (oneOf != null || anyOf != null) {
            if (oneOf != null && anyOf != null) throw invalid();
            List<?> branches = list(oneOf != null ? oneOf : anyOf);
            if (branches.isEmpty() || branches.size() > 8) throw invalid();
            int matches = 0;
            for (Object branch : branches) {
                try {
                    validate(stringMap(branch), value, depth + 1, budget.fork());
                    matches++;
                } catch (IllegalArgumentException ignored) {
                    // A non-matching branch is part of normal composition evaluation.
                }
            }
            if (oneOf != null ? matches != 1 : matches < 1) throw invalid();
            return;
        }
        String type = schema.get("type") instanceof String string ? string : null;
        if (type == null) throw invalid();
        switch (type) {
            case "null" -> {
                if (value != null) throw invalid();
            }
            case "string" -> mapString(schema, value);
            case "integer" -> mapNumber(schema, value, true);
            case "number" -> mapNumber(schema, value, false);
            case "boolean" -> {
                if (!(value instanceof Boolean)) throw invalid();
            }
            case "array" -> mapArray(schema, value, depth, budget);
            case "object" -> mapObject(schema, value, depth, budget);
            default -> throw invalid();
        }
    }

    private void mapString(Map<String, Object> schema, Object value) {
        if (!(value instanceof String string)) throw invalid();
        Object enums = schema.get("enum");
        if (enums != null && !list(enums).contains(string)) throw invalid();
        Integer minimum = integer(schema.get("minLength"));
        Integer maximum = integer(schema.get("maxLength"));
        if (minimum != null && string.length() < minimum || maximum != null && string.length() > maximum
                || schema.get("pattern") instanceof String pattern && !matches(pattern, string)) throw invalid();
    }

    private void mapNumber(Map<String, Object> schema, Object value, boolean integral) {
        BigDecimal decimal = decimal(value);
        if (integral) {
            try {
                BigInteger integer = decimal.toBigIntegerExact();
                if ("int32".equals(schema.get("format"))) integer.intValueExact();
                if ("int64".equals(schema.get("format"))) integer.longValueExact();
            } catch (ArithmeticException failure) {
                throw invalid();
            }
        }
        BigDecimal minimum = constraint(schema.get("minimum"));
        BigDecimal maximum = constraint(schema.get("maximum"));
        if (minimum != null && decimal.compareTo(minimum) < 0
                || maximum != null && decimal.compareTo(maximum) > 0) throw invalid();
    }

    private void mapArray(Map<String, Object> schema, Object value, int depth, Budget budget) {
        List<?> values = list(value);
        Integer minimum = integer(schema.get("minItems"));
        Integer maximum = integer(schema.get("maxItems"));
        if (minimum != null && values.size() < minimum || maximum != null && values.size() > maximum) throw invalid();
        Map<String, Object> items = stringMap(schema.get("items"));
        Set<CanonicalJsonValue.Value> unique = Boolean.TRUE.equals(schema.get("uniqueItems"))
                ? new HashSet<>() : null;
        for (Object item : values) {
            validate(items, item, depth + 1, budget);
            if (unique != null && !unique.add(CanonicalJsonValue.of(item))) throw invalid();
        }
    }

    private void mapObject(Map<String, Object> schema, Object value, int depth, Budget budget) {
        Map<String, Object> values = object(value);
        Map<String, Object> properties = stringMap(schema.get("properties"));
        if (!properties.keySet().containsAll(values.keySet())) throw invalid();
        List<?> required = list(schema.get("required"));
        if (required.stream().anyMatch(name -> !(name instanceof String string) || !values.containsKey(string))) {
            throw invalid();
        }
        values.forEach((name, nested) -> validate(stringMap(properties.get(name)), nested, depth + 1, budget));
    }

    private Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, nested) -> {
            if (!(key instanceof String name) || result.put(name, nested) != null) throw invalid();
        });
        return result;
    }

    private Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, nested) -> {
            if (!(key instanceof String name) || result.put(name, nested) != null) throw invalid();
        });
        return result;
    }

    private List<?> list(Object value) {
        if (value instanceof List<?> list) return list;
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(result::add);
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<>(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) result.add(Array.get(value, index));
            return result;
        }
        throw invalid();
    }

    private BigDecimal decimal(Object value) {
        try {
            if (value instanceof BigDecimal decimal) return decimal;
            if (value instanceof BigInteger integer) return new BigDecimal(integer);
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return BigDecimal.valueOf(((Number) value).longValue());
            }
            if (value instanceof Float || value instanceof Double) {
                double number = ((Number) value).doubleValue();
                if (!Double.isFinite(number)) throw invalid();
                return BigDecimal.valueOf(number);
            }
            throw invalid();
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private BigDecimal constraint(Object value) {
        return value == null ? null : decimal(value);
    }

    private Integer integer(Object value) {
        if (value == null) return null;
        if (!(value instanceof Integer integer) || integer < 0) throw invalid();
        return integer;
    }

    private boolean matches(String expression, String value) {
        return SchemaPatternMatcher.matches(expression, value);
    }

    private void reserve(int depth, Budget budget) {
        if (depth > MAX_DEPTH || !budget.reserve()) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(SAFE_MESSAGE);
    }

    private static final class Budget {
        private int nodes;

        private boolean reserve() {
            return ++nodes <= MAX_NODES;
        }

        private Budget fork() {
            Budget fork = new Budget();
            fork.nodes = nodes;
            return fork;
        }
    }
}
