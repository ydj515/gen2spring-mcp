package io.gen2spring.mcp.adapter.openapi.swagger;

import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.RECURSIVE_SCHEMA_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_ADDITIONAL_PROPERTIES_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_COMPOSITION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_CONSTRAINT_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_MISSING;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_NESTED_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_NULLABILITY_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_TYPE_UNSUPPORTED;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.CompositionKind;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaComposition;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.swagger.v3.oas.models.media.Schema;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class SwaggerSchemaNormalizer {
    private final SwaggerSchemaIntersection intersections = new SwaggerSchemaIntersection();

    public ApiSchema normalize(Schema<?> schema, Map<String, Schema> componentSchemas) {
        return normalize(schema, componentSchemas, "3.0.0");
    }

    ApiSchema normalize(Schema<?> schema, Map<String, Schema> componentSchemas, String openApiVersion) {
        return normalizeRoot(schema, componentSchemas, false, false, honorsNullable(openApiVersion));
    }

    ApiSchema normalizeParameter(Schema<?> schema, Map<String, Schema> componentSchemas, String openApiVersion) {
        return normalizeRoot(schema, componentSchemas, true, false, honorsNullable(openApiVersion));
    }

    public ApiSchema normalizeResponse(Schema<?> schema, Map<String, Schema> componentSchemas) {
        return normalizeResponse(schema, componentSchemas, "3.0.0");
    }

    ApiSchema normalizeResponse(Schema<?> schema, Map<String, Schema> componentSchemas, String openApiVersion) {
        return normalizeRoot(schema, componentSchemas, true, true, honorsNullable(openApiVersion));
    }

    ApiSchema normalizeRequestBody(Schema<?> schema, Map<String, Schema> componentSchemas, String openApiVersion) {
        return normalizeRoot(schema, componentSchemas, true, false, honorsNullable(openApiVersion));
    }

    private ApiSchema normalizeRoot(
            Schema<?> schema,
            Map<String, Schema> componentSchemas,
            boolean allowNullable,
            boolean responseSchema,
            boolean honorNullableKeyword) {
        if (schema == null) return unsupported(SCHEMA_MISSING.message());
        return normalize(schema, componentSchemas == null ? Map.of() : componentSchemas,
                Collections.newSetFromMap(new IdentityHashMap<>()), new java.util.HashSet<>(),
                new SwaggerSchemaBudget(), 1, allowNullable, responseSchema, honorNullableKeyword);
    }

    private ApiSchema normalize(
            Schema<?> schema,
            Map<String, Schema> components,
            Set<Schema<?>> ancestors,
            Set<String> ancestorReferences,
            SwaggerSchemaBudget budget,
            int depth,
            boolean allowNullable,
            boolean responseSchema,
            boolean honorNullableKeyword) {
        if (schema == null) return unsupported(SCHEMA_MISSING.message());
        if (!budget.acceptDepth(depth)) return unsupported(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
        String reference = schema.get$ref();
        if (reference != null) {
            if (!reference.startsWith("#/components/schemas/")) {
                return unsupported(SCHEMA_TYPE_UNSUPPORTED.message());
            }
            if (!ancestorReferences.add(reference)) {
                return unsupported(RECURSIVE_SCHEMA_UNSUPPORTED.message());
            }
            try {
                Schema<?> target = components.get(reference.substring("#/components/schemas/".length()));
                if (target == null) return unsupported(SCHEMA_MISSING.message());
                ApiSchema referenced = normalize(target, components, ancestors, ancestorReferences,
                        budget, depth + 1, allowNullable, responseSchema, honorNullableKeyword);
                if (!hasReferenceSibling(schema)) return referenced;
                if (honorNullableKeyword) return unsupported(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
                ApiSchema sibling = normalizeReferenceSibling(schema, referenced, components, ancestors,
                        ancestorReferences, budget, depth, allowNullable, responseSchema, honorNullableKeyword);
                return intersections.intersect(referenced, sibling);
            } finally {
                ancestorReferences.remove(reference);
            }
        }
        return normalizeInline(schema, components, ancestors, ancestorReferences,
                budget, depth, allowNullable, responseSchema, honorNullableKeyword, false);
    }

    private ApiSchema normalizeReferenceSibling(
            Schema<?> schema,
            ApiSchema referenced,
            Map<String, Schema> components,
            Set<Schema<?>> ancestors,
            Set<String> ancestorReferences,
            SwaggerSchemaBudget budget,
            int depth,
            boolean allowNullable,
            boolean responseSchema,
            boolean honorNullableKeyword) {
        if (nonEmpty(schema.getAllOf()) == 1 || nonEmpty(schema.getOneOf()) == 1
                || nonEmpty(schema.getAnyOf()) == 1) {
            return normalizeInline(schema, components, ancestors, ancestorReferences,
                    budget, depth, allowNullable, responseSchema, honorNullableKeyword, true);
        }
        ApiSchema sibling = standard(schema, components, ancestors, ancestorReferences,
                budget, depth, allowNullable, responseSchema, honorNullableKeyword, referenced.type(), true);
        if (referenced.type() != SchemaType.ARRAY || sibling.items() != null) {
            return sibling;
        }
        return new ApiSchema(
                sibling.type(), sibling.format(), sibling.nullable(), sibling.enumValues(), sibling.minimum(),
                sibling.maximum(), sibling.minLength(), sibling.maxLength(), sibling.pattern(), sibling.defaultValue(),
                sibling.properties(), sibling.requiredProperties(), referenced.items(), sibling.minItems(),
                sibling.maxItems(), sibling.uniqueItems(), sibling.composition(), sibling.supported(),
                sibling.warnings());
    }

    private ApiSchema normalizeInline(
            Schema<?> schema,
            Map<String, Schema> components,
            Set<Schema<?>> ancestors,
            Set<String> ancestorReferences,
            SwaggerSchemaBudget budget,
            int depth,
            boolean allowNullable,
            boolean responseSchema,
            boolean honorNullableKeyword,
            boolean ignoreReference) {
        if (!ancestors.add(schema)) return unsupported(RECURSIVE_SCHEMA_UNSUPPORTED.message());
        try {
            if (schema.getDiscriminator() != null) return unsupported(SCHEMA_COMPOSITION_UNSUPPORTED.message());
            int compositionCount = nonEmpty(schema.getAllOf())
                    + nonEmpty(schema.getOneOf()) + nonEmpty(schema.getAnyOf());
            if (compositionCount > 1) return unsupported(SCHEMA_COMPOSITION_UNSUPPORTED.message());
            if (nonEmpty(schema.getAllOf()) == 1) {
                if (!budget.reserveBranches(schema.getAllOf().size())) {
                    return unsupported(SCHEMA_COMPOSITION_UNSUPPORTED.message());
                }
                List<ApiSchema> branches = new ArrayList<>();
                for (Schema<?> branch : schema.getAllOf()) {
                    branches.add(normalize(branch, components, ancestors, ancestorReferences,
                            budget, depth + 1, allowNullable, responseSchema, honorNullableKeyword));
                }
                if (hasDirectShape(schema)) {
                    branches.add(standard(schema, components, ancestors, ancestorReferences,
                            budget, depth, allowNullable, responseSchema, honorNullableKeyword, null, false));
                }
                return intersections.intersect(branches);
            }
            if (nonEmpty(schema.getOneOf()) == 1 || nonEmpty(schema.getAnyOf()) == 1) {
                List<Schema> source = nonEmpty(schema.getOneOf()) == 1 ? schema.getOneOf() : schema.getAnyOf();
                CompositionKind kind = nonEmpty(schema.getOneOf()) == 1
                        ? CompositionKind.ONE_OF : CompositionKind.ANY_OF;
                return composition(schema, source, kind, components, ancestors, ancestorReferences,
                        budget, depth, allowNullable, responseSchema, honorNullableKeyword);
            }
            TypeResolution types = resolveTypes(schema, honorNullableKeyword);
            if (types.types().size() > 1) {
                if (!budget.reserveBranches(types.types().size())) {
                    return unsupported(SCHEMA_COMPOSITION_UNSUPPORTED.message());
                }
                List<ApiSchema> branches = types.types().stream()
                        .map(type -> standard(schema, components, ancestors, ancestorReferences,
                                budget, depth, true, responseSchema, honorNullableKeyword, type, false))
                        .toList();
                if (branches.stream().anyMatch(branch -> !branch.supported())) {
                    return unsupported(SCHEMA_NESTED_UNSUPPORTED.message());
                }
                return composed(CompositionKind.ANY_OF, branches, types.nullable(), schema.getDefault());
            }
            return standard(schema, components, ancestors, ancestorReferences,
                    budget, depth, allowNullable, responseSchema, honorNullableKeyword,
                    types.types().isEmpty() ? null : types.types().getFirst(), false);
        } finally {
            ancestors.remove(schema);
        }
    }

    private ApiSchema composition(
            Schema<?> schema,
            List<Schema> source,
            CompositionKind kind,
            Map<String, Schema> components,
            Set<Schema<?>> ancestors,
            Set<String> ancestorReferences,
            SwaggerSchemaBudget budget,
            int depth,
            boolean allowNullable,
            boolean responseSchema,
            boolean honorNullableKeyword) {
        if (!budget.reserveBranches(source.size()) || hasDirectShape(schema)) {
            return unsupported(SCHEMA_COMPOSITION_UNSUPPORTED.message());
        }
        List<ApiSchema> branches = source.stream()
                .map(branch -> normalize(branch, components, ancestors, ancestorReferences,
                        budget, depth + 1, true, responseSchema, honorNullableKeyword))
                .toList();
        if (branches.stream().anyMatch(branch -> !branch.supported())) {
            return unsupported(SCHEMA_NESTED_UNSUPPORTED.message());
        }
        boolean nullable = nullable(schema, honorNullableKeyword);
        if (nullable && !allowNullable) return unsupported(SCHEMA_NULLABILITY_UNSUPPORTED.message());
        return composed(kind, branches, nullable, schema.getDefault());
    }

    private ApiSchema standard(
            Schema<?> schema,
            Map<String, Schema> components,
            Set<Schema<?>> ancestors,
            Set<String> ancestorReferences,
            SwaggerSchemaBudget budget,
            int depth,
            boolean allowNullable,
            boolean responseSchema,
            boolean honorNullableKeyword,
            SchemaType forcedType,
            boolean inheritedShape) {
        TypeResolution resolution = resolveTypes(schema, honorNullableKeyword);
        SchemaType type = forcedType != null ? forcedType
                : resolution.types().size() == 1 ? resolution.types().getFirst()
                : mapType(schema.getType(), schema.getProperties());
        boolean nullable = resolution.nullable();
        List<String> warnings = new ArrayList<>();
        if (type == null) warnings.add(SCHEMA_TYPE_UNSUPPORTED.message());
        if (nullable && !allowNullable) warnings.add(SCHEMA_NULLABILITY_UNSUPPORTED.message());
        Object additional = schema.getAdditionalProperties();
        if (additional != null && !Boolean.FALSE.equals(additional)) {
            warnings.add(SCHEMA_ADDITIONAL_PROPERTIES_UNSUPPORTED.message());
        }
        warnings.addAll(unsupportedConstraints(schema, type));
        List<String> enums = enumValues(schema.getEnum());
        if (!enums.isEmpty() && type != SchemaType.STRING) warnings.add(SCHEMA_CONSTRAINT_UNSUPPORTED.message());

        Map<String, ApiSchema> properties = new LinkedHashMap<>();
        if (schema.getProperties() != null) {
            schema.getProperties().forEach((name, property) -> {
                boolean excluded = property != null && (responseSchema
                        ? Boolean.TRUE.equals(property.getWriteOnly())
                        : Boolean.TRUE.equals(property.getReadOnly()));
                if (!excluded) {
                    properties.put(name, normalize(property, components, ancestors, ancestorReferences,
                            budget, depth + 1, true, responseSchema, honorNullableKeyword));
                }
            });
        }
        ApiSchema items = schema.getItems() == null ? null
                : normalize(schema.getItems(), components, ancestors, ancestorReferences,
                        budget, depth + 1, true, responseSchema, honorNullableKeyword);
        if (type == SchemaType.ARRAY && items == null && !inheritedShape) {
            warnings.add(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
        }
        if (properties.values().stream().anyMatch(property -> !property.supported())
                || items != null && !items.supported()) warnings.add(SCHEMA_NESTED_UNSUPPORTED.message());

        SchemaType safeType = type == null ? SchemaType.OBJECT : type;
        return new ApiSchema(
                safeType, schema.getFormat(), nullable, enums,
                schema.getMinimum(), schema.getMaximum(), schema.getMinLength(), schema.getMaxLength(),
                schema.getPattern(), schema.getDefault(), Map.copyOf(properties),
                schema.getRequired() == null ? List.of() : schema.getRequired().stream()
                        .filter(properties::containsKey).distinct().sorted().toList(),
                items, schema.getMinItems(), schema.getMaxItems(), Boolean.TRUE.equals(schema.getUniqueItems()),
                null, warnings.isEmpty(), warnings.stream().distinct().toList());
    }

    private List<String> unsupportedConstraints(Schema<?> schema, SchemaType type) {
        List<String> warnings = new ArrayList<>();
        Integer minItems = schema.getMinItems();
        Integer maxItems = schema.getMaxItems();
        boolean unique = Boolean.TRUE.equals(schema.getUniqueItems());
        if (minItems != null && (type != SchemaType.ARRAY || minItems < 0)
                || maxItems != null && (type != SchemaType.ARRAY || maxItems < 0)
                || minItems != null && maxItems != null && minItems > maxItems
                || unique && (type != SchemaType.ARRAY || maxItems == null || maxItems > 256)) {
            warnings.add(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
        }
        if (Boolean.TRUE.equals(schema.getExclusiveMinimum())
                || Boolean.TRUE.equals(schema.getExclusiveMaximum())
                || schema.getExclusiveMinimumValue() != null || schema.getExclusiveMaximumValue() != null
                || schema.getMultipleOf() != null || schema.getMinProperties() != null
                || schema.getMaxProperties() != null || schema.getIf() != null || schema.getThen() != null
                || schema.getElse() != null || schema.getNot() != null || schema.getContains() != null
                || schema.getMinContains() != null || schema.getMaxContains() != null
                || schema.getPrefixItems() != null && !schema.getPrefixItems().isEmpty()
                || schema.getPatternProperties() != null && !schema.getPatternProperties().isEmpty()
                || schema.getDependentSchemas() != null && !schema.getDependentSchemas().isEmpty()
                || schema.getDependentRequired() != null && !schema.getDependentRequired().isEmpty()
                || schema.getPropertyNames() != null || schema.getUnevaluatedProperties() != null
                || schema.getUnevaluatedItems() != null || schema.getContentEncoding() != null
                || schema.getContentMediaType() != null || schema.getConst() != null) {
            warnings.add(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
        }
        return warnings;
    }

    private ApiSchema composed(CompositionKind kind, List<ApiSchema> branches, boolean nullable, Object defaultValue) {
        return new ApiSchema(
                SchemaType.COMPOSED, null, nullable, List.of(), null, null, null, null,
                null, defaultValue, Map.of(), List.of(), null, null, null, false,
                new SchemaComposition(kind, branches), true, List.of());
    }

    private boolean hasReferenceSibling(Schema<?> schema) {
        return hasDirectShape(schema) || nonEmpty(schema.getAllOf()) == 1
                || nonEmpty(schema.getOneOf()) == 1 || nonEmpty(schema.getAnyOf()) == 1
                || schema.getDefault() != null || nullable(schema, false) || hasUnsupportedKeyword(schema);
    }

    private boolean hasUnsupportedKeyword(Schema<?> schema) {
        return Boolean.TRUE.equals(schema.getExclusiveMinimum()) || Boolean.TRUE.equals(schema.getExclusiveMaximum())
                || schema.getExclusiveMinimumValue() != null || schema.getExclusiveMaximumValue() != null
                || schema.getMultipleOf() != null || schema.getMinProperties() != null
                || schema.getMaxProperties() != null || schema.getIf() != null || schema.getThen() != null
                || schema.getElse() != null || schema.getNot() != null || schema.getContains() != null
                || schema.getMinContains() != null || schema.getMaxContains() != null
                || schema.getPrefixItems() != null && !schema.getPrefixItems().isEmpty()
                || schema.getPatternProperties() != null && !schema.getPatternProperties().isEmpty()
                || schema.getDependentSchemas() != null && !schema.getDependentSchemas().isEmpty()
                || schema.getDependentRequired() != null && !schema.getDependentRequired().isEmpty()
                || schema.getPropertyNames() != null || schema.getUnevaluatedProperties() != null
                || schema.getUnevaluatedItems() != null || schema.getContentEncoding() != null
                || schema.getContentMediaType() != null || schema.getConst() != null;
    }

    private boolean hasDirectShape(Schema<?> schema) {
        return schema.getType() != null || schema.getTypes() != null && !schema.getTypes().isEmpty()
                || schema.getFormat() != null || schema.getEnum() != null && !schema.getEnum().isEmpty()
                || schema.getMinimum() != null || schema.getMaximum() != null
                || schema.getMinLength() != null || schema.getMaxLength() != null || schema.getPattern() != null
                || schema.getProperties() != null && !schema.getProperties().isEmpty()
                || schema.getRequired() != null && !schema.getRequired().isEmpty()
                || schema.getItems() != null || schema.getMinItems() != null || schema.getMaxItems() != null
                || Boolean.TRUE.equals(schema.getUniqueItems()) || schema.getAdditionalProperties() != null;
    }

    private TypeResolution resolveTypes(Schema<?> schema, boolean honorNullableKeyword) {
        Set<String> declared = schema.getTypes();
        if (declared == null || declared.isEmpty()) {
            SchemaType type = mapType(schema.getType(), schema.getProperties());
            return new TypeResolution(type == null ? List.of() : List.of(type),
                    honorNullableKeyword && Boolean.TRUE.equals(schema.getNullable()));
        }
        TreeSet<String> normalized = new TreeSet<>();
        declared.stream().filter(java.util.Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT)).forEach(normalized::add);
        boolean nullable = normalized.remove("null")
                || honorNullableKeyword && Boolean.TRUE.equals(schema.getNullable());
        List<SchemaType> types = normalized.stream().map(value -> mapType(value, null)).toList();
        if (types.stream().anyMatch(java.util.Objects::isNull)) return new TypeResolution(List.of(), nullable);
        return new TypeResolution(types, nullable);
    }

    private SchemaType mapType(String type, Map<String, Schema> properties) {
        if (type == null && properties != null && !properties.isEmpty()) return SchemaType.OBJECT;
        if (type == null) return null;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "string" -> SchemaType.STRING;
            case "integer" -> SchemaType.INTEGER;
            case "number" -> SchemaType.NUMBER;
            case "boolean" -> SchemaType.BOOLEAN;
            case "array" -> SchemaType.ARRAY;
            case "object" -> SchemaType.OBJECT;
            default -> null;
        };
    }

    private List<String> enumValues(List<?> values) {
        return values == null ? List.of() : values.stream().map(String::valueOf).toList();
    }

    private int nonEmpty(List<?> values) {
        return values == null || values.isEmpty() ? 0 : 1;
    }

    private boolean nullable(Schema<?> schema, boolean honorNullableKeyword) {
        return schema.getTypes() != null && schema.getTypes().stream().anyMatch("null"::equalsIgnoreCase)
                || honorNullableKeyword && Boolean.TRUE.equals(schema.getNullable());
    }

    private boolean honorsNullable(String openApiVersion) {
        return openApiVersion != null && openApiVersion.startsWith("3.0.");
    }

    private ApiSchema unsupported(String warning) {
        return new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), (BigDecimal) null, null,
                null, null, null, null, Map.of(), List.of(), null, null, null,
                false, null, false, List.of(warning));
    }

    private record TypeResolution(List<SchemaType> types, boolean nullable) {}
}
