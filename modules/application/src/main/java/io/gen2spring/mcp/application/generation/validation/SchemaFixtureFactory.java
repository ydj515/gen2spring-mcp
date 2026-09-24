package io.gen2spring.mcp.application.generation.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.toolmodel.schema.SchemaPatternMatcher;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SchemaFixtureFactory {
    private static final String SAFE_MESSAGE = "Schema fixture cannot be derived";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Object create(ApiSchema schema, int variant) {
        if (schema == null || !schema.supported() || variant < 0 || variant > 1) {
            throw invalid();
        }
        Object value = value(schema, variant, 0);
        try {
            if (MAPPER.writeValueAsBytes(value).length > MAX_BYTES) {
                throw invalid();
            }
        } catch (RuntimeException | java.io.IOException failure) {
            throw invalid();
        }
        return value;
    }

    private Object value(ApiSchema schema, int variant, int depth) {
        if (depth > 32 || schema.type() == null) {
            throw invalid();
        }
        return switch (schema.type()) {
            case STRING -> string(schema, variant);
            case INTEGER -> integer(schema, variant);
            case NUMBER -> number(schema, variant);
            case BOOLEAN -> variant == 0 ? Boolean.FALSE : Boolean.TRUE;
            case ARRAY -> List.of(value(requireItems(schema), variant, depth + 1));
            case OBJECT -> object(schema, variant, depth + 1);
            case COMPOSED -> composed(schema, variant, depth + 1);
        };
    }

    private Object composed(ApiSchema schema, int variant, int depth) {
        if (schema.composition() == null || schema.composition().branches().isEmpty()) {
            throw invalid();
        }
        List<ApiSchema> branches = schema.composition().branches();
        return value(branches.get(Math.min(variant, branches.size() - 1)), variant, depth);
    }

    private String string(ApiSchema schema, int variant) {
        if (schema.enumValues() != null && !schema.enumValues().isEmpty()) {
            if (variant >= schema.enumValues().size() && schema.enumValues().size() != 1) {
                throw invalid();
            }
            String value = schema.enumValues().get(Math.min(variant, schema.enumValues().size() - 1));
            return validString(schema, value) ? value : fail();
        }
        int minimum = schema.minLength() == null ? 1 : Math.max(1, schema.minLength());
        int maximum = schema.maxLength() == null ? 2_048 : Math.min(2_048, schema.maxLength());
        if (minimum > maximum) {
            throw invalid();
        }
        List<String> candidates = new ArrayList<>();
        candidates.add((variant == 0 ? "a" : "b").repeat(minimum));
        candidates.add(pad(variant == 0 ? "gen2spring-a" : "gen2spring-b", minimum, maximum));
        candidates.add(pad(variant == 0 ? "1" : "2", minimum, maximum));
        return candidates.stream().filter(candidate -> validString(schema, candidate)).findFirst().orElseThrow(this::invalid);
    }

    private String pad(String value, int minimum, int maximum) {
        String bounded = value.length() > maximum ? value.substring(0, maximum) : value;
        return bounded.length() >= minimum ? bounded : bounded + "a".repeat(minimum - bounded.length());
    }

    private boolean validString(ApiSchema schema, String value) {
        if (value == null || schema.minLength() != null && value.length() < schema.minLength()
                || schema.maxLength() != null && value.length() > schema.maxLength()) {
            return false;
        }
        if (schema.pattern() == null) {
            return true;
        }
        return SchemaPatternMatcher.matches(schema.pattern(), value);
    }

    private BigInteger integer(ApiSchema schema, int variant) {
        BigInteger minimum = integralBound(schema.minimum(), true);
        BigInteger maximum = integralBound(schema.maximum(), false);
        if ("int32".equals(schema.format())) {
            minimum = max(minimum, BigInteger.valueOf(Integer.MIN_VALUE));
            maximum = min(maximum, BigInteger.valueOf(Integer.MAX_VALUE));
        } else if ("int64".equals(schema.format())) {
            minimum = max(minimum, BigInteger.valueOf(Long.MIN_VALUE));
            maximum = min(maximum, BigInteger.valueOf(Long.MAX_VALUE));
        }
        BigInteger candidate = minimum == null ? BigInteger.valueOf(variant + 1L) : minimum.add(BigInteger.valueOf(variant));
        if (maximum != null && candidate.compareTo(maximum) > 0) {
            throw invalid();
        }
        return candidate;
    }

    private BigDecimal number(ApiSchema schema, int variant) {
        BigDecimal candidate = schema.minimum() == null
                ? BigDecimal.valueOf(variant + 1L) : schema.minimum().add(BigDecimal.valueOf(variant));
        if (schema.maximum() != null && candidate.compareTo(schema.maximum()) > 0) {
            throw invalid();
        }
        return candidate;
    }

    private Map<String, Object> object(ApiSchema schema, int variant, int depth) {
        Map<String, ApiSchema> properties = schema.properties() == null ? Map.of() : new TreeMap<>(schema.properties());
        List<String> required = schema.requiredProperties() == null ? List.of() : schema.requiredProperties();
        List<String> selected = required.isEmpty() && !properties.isEmpty()
                ? List.of(properties.keySet().iterator().next()) : required.stream().sorted().toList();
        if (selected.isEmpty() && variant > 0) {
            throw invalid();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : selected) {
            ApiSchema property = properties.get(name);
            if (property == null) {
                throw invalid();
            }
            result.put(name, value(property, variant, depth));
        }
        return result;
    }

    private ApiSchema requireItems(ApiSchema schema) {
        if (schema.items() == null) {
            throw invalid();
        }
        return schema.items();
    }

    private BigInteger integralBound(BigDecimal value, boolean ceiling) {
        if (value == null) {
            return null;
        }
        return value.setScale(0, ceiling ? java.math.RoundingMode.CEILING : java.math.RoundingMode.FLOOR).toBigIntegerExact();
    }

    private BigInteger max(BigInteger left, BigInteger right) {
        return left == null || left.compareTo(right) < 0 ? right : left;
    }

    private BigInteger min(BigInteger left, BigInteger right) {
        return left == null || left.compareTo(right) > 0 ? right : left;
    }

    private String fail() {
        throw invalid();
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException(SAFE_MESSAGE);
    }
}
