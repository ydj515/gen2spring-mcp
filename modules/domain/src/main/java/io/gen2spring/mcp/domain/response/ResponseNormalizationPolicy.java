package io.gen2spring.mcp.domain.response;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

public record ResponseNormalizationPolicy(
        String dataPointer,
        String successCodePointer,
        List<Object> successValues,
        String errorMessagePointer,
        String totalCountPointer) {
    public ResponseNormalizationPolicy {
        successValues = successValues == null ? List.of()
                : successValues.stream().map(ResponseNormalizationPolicy::immutableScalar).toList();
    }

    private static Object immutableScalar(Object value) {
        if (value instanceof String || value instanceof Boolean
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float number && Float.isFinite(number)) {
            return new BigDecimal(Float.toString(number));
        }
        if (value instanceof Double number && Double.isFinite(number)) {
            return BigDecimal.valueOf(number);
        }
        throw new IllegalArgumentException("Response normalization success value is invalid");
    }
}
