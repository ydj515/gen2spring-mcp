package io.gen2spring.mcp.application.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ValidationJsonValue {
    private ValidationJsonValue() {}

    static Map<String, Object> immutableMap(Map<?, ?> source, boolean allowNull, boolean allowBlankKeys) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String name) || invalidObjectKey(name, allowBlankKeys)) {
                throw new IllegalArgumentException(allowBlankKeys
                        ? "Response object keys must be safe JSON strings"
                        : "Schema object keys must be non-blank strings");
            }
            copy.put(name, immutableJsonValue(value, allowNull, allowBlankKeys));
        });
        return Collections.unmodifiableMap(copy);
    }

    static Object immutableJsonValue(Object value, boolean allowNull, boolean allowBlankKeys) {
        if (value == null) {
            if (allowNull) {
                return null;
            }
            throw new IllegalArgumentException("Schema values must use JSON-compatible immutable types");
        }
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map, allowNull, allowBlankKeys);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableJsonValue(item, allowNull, allowBlankKeys)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) {
            return value;
        }
        if (value instanceof Float number && Float.isFinite(number)) {
            return value;
        }
        if (value instanceof Double number && Double.isFinite(number)) {
            return value;
        }
        throw new IllegalArgumentException("Schema values must use JSON-compatible immutable types");
    }

    private static boolean invalidObjectKey(String name, boolean allowBlankKeys) {
        return allowBlankKeys ? name.chars().anyMatch(Character::isISOControl) : name.isBlank();
    }
}
