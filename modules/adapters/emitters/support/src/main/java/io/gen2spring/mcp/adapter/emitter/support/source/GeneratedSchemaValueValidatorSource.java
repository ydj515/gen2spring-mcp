package io.gen2spring.mcp.adapter.emitter.support.source;

public final class GeneratedSchemaValueValidatorSource {
    private GeneratedSchemaValueValidatorSource() {}

    public static String render(String packageName) {
        return """
                package %s.runtime;

                import java.lang.reflect.Array;
                import java.math.BigDecimal;
                import java.math.BigInteger;
                import java.util.ArrayList;
                import java.util.HashSet;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Set;
                import java.util.TreeMap;
                import java.util.regex.Pattern;
                import java.util.regex.PatternSyntaxException;

                public final class SchemaValueValidator {
                    private static final int MAX_DEPTH = 32;
                    private static final int MAX_NODES = 65_536;
                    private static final int MAX_PATTERN_CHARACTERS = 512;
                    private static final int MAX_PATTERN_CHARACTER_ACCESSES = 100_000;
                    private static final int MAX_PATTERN_VALUE_CHARACTERS = 8_192;

                    public void validate(Map<String, Object> schema, Object value) {
                        validate(schema, value, 0, new Budget());
                    }

                    private void validate(Map<String, Object> schema, Object value, int depth, Budget budget) {
                        reserve(depth, budget);
                        if (schema == null || schema.isEmpty()) {
                            return;
                        }
                        Object oneOf = schema.get("oneOf");
                        Object anyOf = schema.get("anyOf");
                        if (oneOf != null || anyOf != null) {
                            if (oneOf != null && anyOf != null) {
                                throw invalid();
                            }
                            List<?> branches = list(oneOf != null ? oneOf : anyOf);
                            if (branches.isEmpty() || branches.size() > 8) {
                                throw invalid();
                            }
                            int matches = 0;
                            for (Object branch : branches) {
                                try {
                                    validate(stringMap(branch), value, depth + 1, budget.fork());
                                    matches++;
                                } catch (SchemaValueInvalid ignored) {
                                    // Non-matching branches are expected during composition evaluation.
                                }
                            }
                            if (oneOf != null ? matches != 1 : matches < 1) {
                                throw invalid();
                            }
                            return;
                        }
                        Object typeValue = schema.get("type");
                        if (!(typeValue instanceof String type)) {
                            throw invalid();
                        }
                        switch (type) {
                            case "null" -> {
                                if (value != null) throw invalid();
                            }
                            case "string" -> string(schema, value);
                            case "integer" -> number(schema, value, true);
                            case "number" -> number(schema, value, false);
                            case "boolean" -> {
                                if (!(value instanceof Boolean)) throw invalid();
                            }
                            case "array" -> array(schema, value, depth, budget);
                            case "object" -> object(schema, value, depth, budget);
                            default -> throw invalid();
                        }
                    }

                    private void string(Map<String, Object> schema, Object value) {
                        if (!(value instanceof String string)) throw invalid();
                        Object enums = schema.get("enum");
                        if (enums != null && !list(enums).contains(string)) throw invalid();
                        Integer minimum = integer(schema.get("minLength"));
                        Integer maximum = integer(schema.get("maxLength"));
                        if (minimum != null && string.length() < minimum
                                || maximum != null && string.length() > maximum
                                || schema.get("pattern") instanceof String pattern && !matches(pattern, string)) {
                            throw invalid();
                        }
                    }

                    private void number(Map<String, Object> schema, Object value, boolean integral) {
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
                                || maximum != null && decimal.compareTo(maximum) > 0) {
                            throw invalid();
                        }
                    }

                    private void array(Map<String, Object> schema, Object value, int depth, Budget budget) {
                        List<?> values = list(value);
                        Integer minimum = integer(schema.get("minItems"));
                        Integer maximum = integer(schema.get("maxItems"));
                        if (minimum != null && values.size() < minimum
                                || maximum != null && values.size() > maximum) {
                            throw invalid();
                        }
                        Map<String, Object> items = stringMap(schema.get("items"));
                        Set<CanonicalValue> unique = Boolean.TRUE.equals(schema.get("uniqueItems"))
                                ? new HashSet<>() : null;
                        for (Object item : values) {
                            validate(items, item, depth + 1, budget);
                            if (unique != null && !unique.add(canonical(item, 0, new Budget()))) {
                                throw invalid();
                            }
                        }
                    }

                    private void object(Map<String, Object> schema, Object value, int depth, Budget budget) {
                        Map<String, Object> values = stringMap(value);
                        Map<String, Object> properties = stringMap(schema.get("properties"));
                        if (!properties.keySet().containsAll(values.keySet())) throw invalid();
                        List<?> required = list(schema.get("required"));
                        for (Object name : required) {
                            if (!(name instanceof String string) || !values.containsKey(string)) throw invalid();
                        }
                        for (Map.Entry<String, Object> entry : values.entrySet()) {
                            validate(stringMap(properties.get(entry.getKey())), entry.getValue(), depth + 1, budget);
                        }
                    }

                    private CanonicalValue canonical(Object source, int depth, Budget budget) {
                        reserve(depth, budget);
                        if (source == null) return new CanonicalValue("null", null);
                        if (source instanceof String string) return new CanonicalValue("string", string);
                        if (source instanceof Boolean bool) return new CanonicalValue("boolean", bool);
                        if (source instanceof Number number) {
                            BigDecimal normalized = decimal(number).stripTrailingZeros();
                            return new CanonicalValue("number",
                                    normalized.signum() == 0 ? BigDecimal.ZERO : normalized);
                        }
                        if (source instanceof Map<?, ?>) {
                            Map<String, CanonicalValue> values = new TreeMap<>();
                            for (Map.Entry<String, Object> entry : stringMap(source).entrySet()) {
                                values.put(entry.getKey(), canonical(entry.getValue(), depth + 1, budget));
                            }
                            return new CanonicalValue("object", Map.copyOf(values));
                        }
                        List<?> sourceValues = list(source);
                        List<CanonicalValue> values = new ArrayList<>();
                        for (Object item : sourceValues) {
                            values.add(canonical(item, depth + 1, budget));
                        }
                        return new CanonicalValue("array", List.copyOf(values));
                    }

                    private Map<String, Object> stringMap(Object value) {
                        if (!(value instanceof Map<?, ?> map)) throw invalid();
                        Map<String, Object> result = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (!(entry.getKey() instanceof String name)) throw invalid();
                            result.put(name, entry.getValue());
                        }
                        return result;
                    }

                    private List<?> list(Object value) {
                        if (value instanceof List<?> values) return values;
                        if (value instanceof Iterable<?> iterable) {
                            List<Object> result = new ArrayList<>();
                            iterable.forEach(result::add);
                            return result;
                        }
                        if (value != null && value.getClass().isArray()) {
                            List<Object> result = new ArrayList<>(Array.getLength(value));
                            for (int index = 0; index < Array.getLength(value); index++) {
                                result.add(Array.get(value, index));
                            }
                            return result;
                        }
                        throw invalid();
                    }

                    private BigDecimal decimal(Object value) {
                        if (value instanceof BigDecimal decimal) return decimal;
                        if (value instanceof BigInteger integer) return new BigDecimal(integer);
                        if (value instanceof Byte || value instanceof Short
                                || value instanceof Integer || value instanceof Long) {
                            return BigDecimal.valueOf(((Number) value).longValue());
                        }
                        if (value instanceof Float || value instanceof Double) {
                            double number = ((Number) value).doubleValue();
                            if (!Double.isFinite(number)) throw invalid();
                            return BigDecimal.valueOf(number);
                        }
                        throw invalid();
                    }

                    private BigDecimal constraint(Object value) {
                        return value == null ? null : decimal(value);
                    }

                    private Integer integer(Object value) {
                        if (value == null) return null;
                        if (!(value instanceof Number number)) throw invalid();
                        try {
                            int integer = new BigDecimal(number.toString()).intValueExact();
                            if (integer < 0) throw invalid();
                            return integer;
                        } catch (ArithmeticException | NumberFormatException failure) {
                            throw invalid();
                        }
                    }

                    private boolean matches(String expression, String value) {
                        if (expression.length() > MAX_PATTERN_CHARACTERS
                                || value.length() > MAX_PATTERN_VALUE_CHARACTERS) {
                            return false;
                        }
                        try {
                            return Pattern.compile(expression)
                                    .matcher(new BudgetedCharSequence(value, MAX_PATTERN_CHARACTER_ACCESSES))
                                    .matches();
                        } catch (PatternSyntaxException | PatternBudgetExceeded | StackOverflowError failure) {
                            return false;
                        }
                    }

                    private void reserve(int depth, Budget budget) {
                        if (depth > MAX_DEPTH || !budget.reserve()) throw invalid();
                    }

                    private static SchemaValueInvalid invalid() {
                        return new SchemaValueInvalid();
                    }

                    public static final class SchemaValueInvalid extends RuntimeException {
                        private SchemaValueInvalid() {
                            super("Generated Tool argument is invalid", null, false, false);
                        }
                    }

                    private record CanonicalValue(String kind, Object value) {}

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
                            if (index < 0 || index >= length()) throw new IndexOutOfBoundsException(index);
                            budget.consume();
                            return value.charAt(start + index);
                        }

                        @Override
                        public CharSequence subSequence(int subsequenceStart, int subsequenceEnd) {
                            if (subsequenceStart < 0 || subsequenceEnd < subsequenceStart
                                    || subsequenceEnd > length()) {
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
                            if (remaining-- <= 0) throw new PatternBudgetExceeded();
                        }
                    }

                    private static final class PatternBudgetExceeded extends RuntimeException {
                        private PatternBudgetExceeded() {
                            super(null, null, false, false);
                        }
                    }
                }
                """.formatted(packageName);
    }
}
