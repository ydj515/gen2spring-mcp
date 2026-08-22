package io.gen2spring.mcp.application.toolmodel.schema;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class CanonicalJsonValue {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 65_536;

    private CanonicalJsonValue() {}

    public static Value of(Object value) {
        return value(value, 0, new Budget());
    }

    private static Value value(Object source, int depth, Budget budget) {
        if (depth > MAX_DEPTH || !budget.reserve()) throw invalid();
        if (source == null) return new Value(Kind.NULL, null);
        if (source instanceof String string) return new Value(Kind.STRING, string);
        if (source instanceof Boolean bool) return new Value(Kind.BOOLEAN, bool);
        if (source instanceof Number number) return new Value(Kind.NUMBER, number(number));
        if (source instanceof Map<?, ?> map) {
            Map<String, Value> values = new TreeMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String name) || values.put(name, value(nested, depth + 1, budget)) != null) {
                    throw invalid();
                }
            });
            return new Value(Kind.OBJECT, Collections.unmodifiableMap(new LinkedHashMap<>(values)));
        }
        if (source instanceof Iterable<?> iterable) {
            List<Value> values = new ArrayList<>();
            iterable.forEach(nested -> values.add(value(nested, depth + 1, budget)));
            return new Value(Kind.ARRAY, List.copyOf(values));
        }
        if (source.getClass().isArray()) {
            List<Value> values = new ArrayList<>(Array.getLength(source));
            for (int index = 0; index < Array.getLength(source); index++) {
                values.add(value(Array.get(source, index), depth + 1, budget));
            }
            return new Value(Kind.ARRAY, List.copyOf(values));
        }
        throw invalid();
    }

    private static BigDecimal number(Number value) {
        try {
            BigDecimal decimal;
            if (value instanceof BigDecimal bigDecimal) {
                decimal = bigDecimal;
            } else if (value instanceof BigInteger bigInteger) {
                decimal = new BigDecimal(bigInteger);
            } else if (value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                decimal = BigDecimal.valueOf(value.longValue());
            } else if (value instanceof Float || value instanceof Double) {
                double number = value.doubleValue();
                if (!Double.isFinite(number)) throw invalid();
                decimal = BigDecimal.valueOf(number);
            } else {
                decimal = new BigDecimal(value.toString());
            }
            BigDecimal normalized = decimal.stripTrailingZeros();
            return normalized.signum() == 0 ? BigDecimal.ZERO : normalized;
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("JSON value is invalid");
    }

    public enum Kind { NULL, STRING, BOOLEAN, NUMBER, ARRAY, OBJECT }

    public record Value(Kind kind, Object value) {
        public Value {
            if (kind == null) throw invalid();
        }
    }

    private static final class Budget {
        private int nodes;

        private boolean reserve() {
            return ++nodes <= MAX_NODES;
        }
    }
}
