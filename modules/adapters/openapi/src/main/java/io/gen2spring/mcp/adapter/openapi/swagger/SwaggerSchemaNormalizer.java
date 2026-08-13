package io.gen2spring.mcp.adapter.openapi.swagger;

import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.RECURSIVE_SCHEMA_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_ADDITIONAL_PROPERTIES_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_COMPOSITION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_CONSTRAINT_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_MISSING;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_MULTI_TYPE_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_NESTED_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_NULLABILITY_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_TYPE_UNSUPPORTED;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
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
    public ApiSchema normalize(Schema<?> schema, Map<String, Schema> componentSchemas) {
        return normalize(schema, componentSchemas, false, false);
    }

    public ApiSchema normalizeResponse(Schema<?> schema, Map<String, Schema> componentSchemas) {
        return normalize(schema, componentSchemas, true, true);
    }

    private ApiSchema normalize(
            Schema<?> schema,
            Map<String, Schema> componentSchemas,
            boolean allowNullable,
            boolean responseSchema) {
        if (schema == null) {
            return unsupported(SCHEMA_MISSING.message());
        }
        return normalize(schema, componentSchemas == null ? Map.of() : componentSchemas,
                Collections.newSetFromMap(new IdentityHashMap<>()), new java.util.HashSet<>(),
                allowNullable, responseSchema);
    }

    private ApiSchema normalize(
            Schema<?> schema,
            Map<String, Schema> componentSchemas,
            Set<Schema<?>> ancestors,
            Set<String> ancestorReferences,
            boolean allowNullable,
            boolean responseSchema) {
        String reference = schema.get$ref();
        if (reference != null) {
            if (!reference.startsWith("#/components/schemas/")) {
                return unsupported(SCHEMA_TYPE_UNSUPPORTED.message());
            }
            if (!ancestorReferences.add(reference)) {
                return unsupported(RECURSIVE_SCHEMA_UNSUPPORTED.message());
            }
            try {
                Schema<?> referencedSchema = componentSchemas.get(reference.substring("#/components/schemas/".length()));
                return referencedSchema == null
                        ? unsupported(SCHEMA_MISSING.message())
                        : normalize(referencedSchema, componentSchemas, ancestors, ancestorReferences,
                                allowNullable, responseSchema);
            } finally {
                ancestorReferences.remove(reference);
            }
        }
        if (!ancestors.add(schema)) {
            return unsupported(RECURSIVE_SCHEMA_UNSUPPORTED.message());
        }
        try {
            TypeResolution typeResolution = resolveType(schema);
            List<String> warnings = unsupportedCompositionWarnings(schema);
            warnings.addAll(unsupportedSemanticWarnings(schema, allowNullable, typeResolution.nullable()));
            if (typeResolution.multipleTypesUnsupported()) {
                warnings.add(SCHEMA_MULTI_TYPE_UNSUPPORTED.message());
            }
            SchemaType type = typeResolution.type();
            List<String> enumValues = enumValues(schema.getEnum());
            if (type == null && !typeResolution.multipleTypesUnsupported()) {
                warnings.add(SCHEMA_TYPE_UNSUPPORTED.message());
            }
            if (!enumValues.isEmpty() && type != SchemaType.STRING) {
                warnings.add(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
            }

            Map<String, ApiSchema> properties = new LinkedHashMap<>();
            if (schema.getProperties() != null) {
                schema.getProperties().forEach((name, property) -> {
                    boolean excluded = property != null && (responseSchema
                            ? Boolean.TRUE.equals(property.getWriteOnly())
                            : Boolean.TRUE.equals(property.getReadOnly()));
                    if (!excluded) {
                        properties.put(name, normalize(
                                property, componentSchemas, ancestors, ancestorReferences,
                                allowNullable, responseSchema));
                    }
                });
            }
            ApiSchema items = schema.getItems() == null ? null
                    : normalize(schema.getItems(), componentSchemas, ancestors, ancestorReferences,
                            allowNullable, responseSchema);
            if (type == SchemaType.ARRAY && items == null) {
                warnings.add(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
            }
            if (properties.values().stream().anyMatch(property -> !property.supported())
                    || items != null && !items.supported()) {
                warnings.add(SCHEMA_NESTED_UNSUPPORTED.message());
            }

            return new ApiSchema(
                    type == null ? SchemaType.OBJECT : type,
                    schema.getFormat(),
                    typeResolution.nullable(),
                    enumValues,
                    schema.getMinimum(),
                    schema.getMaximum(),
                    schema.getMinLength(),
                    schema.getMaxLength(),
                    schema.getPattern(),
                    schema.getDefault(),
                    Collections.unmodifiableMap(new LinkedHashMap<>(properties)),
                    schema.getRequired() == null ? List.of() : schema.getRequired().stream()
                            .filter(properties::containsKey)
                            .toList(),
                    items,
                    warnings.isEmpty(),
                    List.copyOf(warnings));
        } finally {
            ancestors.remove(schema);
        }
    }

    private List<String> unsupportedCompositionWarnings(Schema<?> schema) {
        List<String> warnings = new ArrayList<>();
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            warnings.add(SCHEMA_COMPOSITION_UNSUPPORTED.message());
        }
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            warnings.add(SCHEMA_COMPOSITION_UNSUPPORTED.message());
        }
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            warnings.add(SCHEMA_COMPOSITION_UNSUPPORTED.message());
        }
        if (schema.getDiscriminator() != null) {
            warnings.add(SCHEMA_COMPOSITION_UNSUPPORTED.message());
        }
        return warnings;
    }

    private List<String> unsupportedSemanticWarnings(
            Schema<?> schema,
            boolean allowNullable,
            boolean nullable) {
        List<String> warnings = new ArrayList<>();
        Object additionalProperties = schema.getAdditionalProperties();
        if (additionalProperties != null && !Boolean.FALSE.equals(additionalProperties)) {
            warnings.add(SCHEMA_ADDITIONAL_PROPERTIES_UNSUPPORTED.message());
        }
        if (!allowNullable && nullable) {
            warnings.add(SCHEMA_NULLABILITY_UNSUPPORTED.message());
        }
        if (Boolean.TRUE.equals(schema.getExclusiveMinimum())
                || Boolean.TRUE.equals(schema.getExclusiveMaximum())
                || schema.getExclusiveMinimumValue() != null
                || schema.getExclusiveMaximumValue() != null
                || schema.getMultipleOf() != null
                || schema.getMinItems() != null
                || schema.getMaxItems() != null
                || Boolean.TRUE.equals(schema.getUniqueItems())
                || schema.getMinProperties() != null
                || schema.getMaxProperties() != null
                || schema.getIf() != null
                || schema.getThen() != null
                || schema.getElse() != null
                || schema.getNot() != null
                || schema.getContains() != null
                || schema.getMinContains() != null
                || schema.getMaxContains() != null
                || schema.getPrefixItems() != null && !schema.getPrefixItems().isEmpty()
                || schema.getPatternProperties() != null && !schema.getPatternProperties().isEmpty()
                || schema.getDependentSchemas() != null && !schema.getDependentSchemas().isEmpty()
                || schema.getDependentRequired() != null && !schema.getDependentRequired().isEmpty()
                || schema.getPropertyNames() != null
                || schema.getUnevaluatedProperties() != null
                || schema.getUnevaluatedItems() != null
                || schema.getContentEncoding() != null
                || schema.getContentMediaType() != null
                || schema.getConst() != null) {
            warnings.add(SCHEMA_CONSTRAINT_UNSUPPORTED.message());
        }
        return warnings;
    }

    private TypeResolution resolveType(Schema<?> schema) {
        Set<String> declaredTypes = schema.getTypes();
        if (declaredTypes == null || declaredTypes.isEmpty()) {
            return new TypeResolution(
                    mapType(schema.getType(), schema.getProperties()),
                    Boolean.TRUE.equals(schema.getNullable()),
                    false);
        }

        Set<String> normalizedTypes = new TreeSet<>();
        declaredTypes.stream()
                .filter(java.util.Objects::nonNull)
                .map(type -> type.toLowerCase(Locale.ROOT))
                .forEach(normalizedTypes::add);
        boolean nullable = normalizedTypes.remove("null") || Boolean.TRUE.equals(schema.getNullable());
        if (normalizedTypes.size() != 1) {
            return new TypeResolution(null, nullable, true);
        }
        String type = normalizedTypes.iterator().next();
        return new TypeResolution(mapType(type, schema.getProperties()), nullable, false);
    }

    private SchemaType mapType(String type, Map<String, Schema> properties) {
        if (type == null && properties != null && !properties.isEmpty()) {
            return SchemaType.OBJECT;
        }
        if (type == null) {
            return null;
        }
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

    private ApiSchema unsupported(String warning) {
        return new ApiSchema(SchemaType.OBJECT, null, false, List.of(), (BigDecimal) null, null,
                null, null, null, null, Map.of(), List.of(), null, false, List.of(warning));
    }

    private record TypeResolution(SchemaType type, boolean nullable, boolean multipleTypesUnsupported) {
    }
}
