package io.gen2spring.mcp.adapter.openapi.swagger;

import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_CONSTRAINT_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_NESTED_UNSUPPORTED;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class SwaggerSchemaIntersection {
    ApiSchema intersect(List<ApiSchema> branches) {
        if (branches == null || branches.isEmpty()) {
            return unsupported(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
        }
        ApiSchema result = branches.getFirst();
        for (int index = 1; index < branches.size(); index++) {
            result = intersect(result, branches.get(index));
        }
        return result;
    }

    ApiSchema intersect(ApiSchema left, ApiSchema right) {
        if (left == null || right == null || !left.supported() || !right.supported()) {
            List<String> warnings = new ArrayList<>();
            if (left != null) warnings.addAll(left.warnings());
            if (right != null) warnings.addAll(right.warnings());
            warnings.add(SCHEMA_NESTED_UNSUPPORTED.message());
            return unsupported(warnings);
        }
        if (left.type() != right.type() || left.type() == SchemaType.COMPOSED
                || incompatible(left.format(), right.format())) {
            return unsupported(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
        }
        SchemaType type = left.type();
        List<String> enums = intersectEnums(left.enumValues(), right.enumValues());
        if (enums == null) return unsupported(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
        BigDecimal minimum = maximum(left.minimum(), right.minimum());
        BigDecimal maximum = minimum(left.maximum(), right.maximum());
        Integer minLength = maximum(left.minLength(), right.minLength());
        Integer maxLength = minimum(left.maxLength(), right.maxLength());
        Integer minItems = maximum(left.minItems(), right.minItems());
        Integer maxItems = minimum(left.maxItems(), right.maxItems());
        if (invalidRange(minimum, maximum) || invalidRange(minLength, maxLength)
                || invalidRange(minItems, maxItems) || incompatible(left.pattern(), right.pattern())) {
            return unsupported(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
        }
        boolean uniqueItems = left.uniqueItems() || right.uniqueItems();
        if (uniqueItems && (maxItems == null || maxItems > 256)) {
            return unsupported(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
        }
        Map<String, ApiSchema> properties = Map.of();
        List<String> required = List.of();
        ApiSchema items = null;
        if (type == SchemaType.OBJECT) {
            LinkedHashMap<String, ApiSchema> merged = new LinkedHashMap<>(left.properties());
            right.properties().forEach((name, schema) -> merged.merge(name, schema, this::intersect));
            if (merged.values().stream().anyMatch(schema -> !schema.supported())) {
                return unsupported(SCHEMA_NESTED_UNSUPPORTED.message());
            }
            properties = Map.copyOf(merged);
            TreeSet<String> names = new TreeSet<>(left.requiredProperties());
            names.addAll(right.requiredProperties());
            required = List.copyOf(names);
        } else if (type == SchemaType.ARRAY) {
            items = intersect(left.items(), right.items());
            if (!items.supported()) return unsupported(SCHEMA_NESTED_UNSUPPORTED.message());
        }
        return new ApiSchema(
                type, left.format() != null ? left.format() : right.format(),
                left.nullable() && right.nullable(), enums, minimum, maximum, minLength, maxLength,
                left.pattern() != null ? left.pattern() : right.pattern(),
                right.defaultValue() != null ? right.defaultValue() : left.defaultValue(),
                properties, required, items, minItems, maxItems,
                uniqueItems, null, true, List.of());
    }

    private List<String> intersectEnums(List<String> left, List<String> right) {
        List<String> first = left == null ? List.of() : left;
        List<String> second = right == null ? List.of() : right;
        if (first.isEmpty()) return List.copyOf(second);
        if (second.isEmpty()) return List.copyOf(first);
        LinkedHashSet<String> intersection = new LinkedHashSet<>(first);
        intersection.retainAll(second);
        return intersection.isEmpty() ? null : List.copyOf(intersection);
    }

    private boolean incompatible(String left, String right) {
        return left != null && right != null && !left.equals(right);
    }

    private boolean invalidRange(BigDecimal minimum, BigDecimal maximum) {
        return minimum != null && maximum != null && minimum.compareTo(maximum) > 0;
    }

    private boolean invalidRange(Integer minimum, Integer maximum) {
        return minimum != null && maximum != null && minimum > maximum;
    }

    private BigDecimal maximum(BigDecimal left, BigDecimal right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.max(right);
    }

    private BigDecimal minimum(BigDecimal left, BigDecimal right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.min(right);
    }

    private Integer maximum(Integer left, Integer right) {
        if (left == null) return right;
        if (right == null) return left;
        return Math.max(left, right);
    }

    private Integer minimum(Integer left, Integer right) {
        if (left == null) return right;
        if (right == null) return left;
        return Math.min(left, right);
    }

    private ApiSchema unsupported(String warning) {
        return unsupported(List.of(warning));
    }

    private ApiSchema unsupported(List<String> warnings) {
        return new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null, null, null,
                null, null, Map.of(), List.of(), null, null, null, false, null,
                false, warnings.stream().distinct().toList());
    }
}
