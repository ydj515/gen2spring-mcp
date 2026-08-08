package io.gen2spring.mcp.openapi;

import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType;
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

public final class SwaggerSchemaNormalizer {
    public ApiSchema normalize(Schema<?> schema, Map<String, Schema> componentSchemas) {
        if (schema == null) {
            return unsupported("Schema is missing");
        }
        return normalize(schema, componentSchemas == null ? Map.of() : componentSchemas,
                Collections.newSetFromMap(new IdentityHashMap<>()), new java.util.HashSet<>());
    }

    private ApiSchema normalize(
            Schema<?> schema,
            Map<String, Schema> componentSchemas,
            Set<Schema<?>> ancestors,
            Set<String> ancestorReferences) {
        String reference = schema.get$ref();
        if (reference != null) {
            if (!reference.startsWith("#/components/schemas/")) {
                return unsupported("Local schema reference " + reference + " is not supported");
            }
            if (!ancestorReferences.add(reference)) {
                return unsupported("Recursive schemas are not supported");
            }
            try {
                Schema<?> referencedSchema = componentSchemas.get(reference.substring("#/components/schemas/".length()));
                return referencedSchema == null
                        ? unsupported("Local schema reference " + reference + " could not be resolved")
                        : normalize(referencedSchema, componentSchemas, ancestors, ancestorReferences);
            } finally {
                ancestorReferences.remove(reference);
            }
        }
        if (!ancestors.add(schema)) {
            return unsupported("Recursive schemas are not supported");
        }
        try {
            List<String> warnings = unsupportedCompositionWarnings(schema);
            warnings.addAll(unsupportedSemanticWarnings(schema));
            SchemaType type = mapType(schema.getType(), schema.getProperties());
            List<String> enumValues = enumValues(schema.getEnum());
            if (type == null) {
                warnings.add("Schema type " + schema.getType() + " is not supported");
            }
            if (!enumValues.isEmpty() && type != SchemaType.STRING) {
                warnings.add("Only string enum schemas are supported");
            }

            Map<String, ApiSchema> properties = new LinkedHashMap<>();
            if (schema.getProperties() != null) {
                schema.getProperties().forEach((name, property) -> {
                    if (property == null || !Boolean.TRUE.equals(property.getReadOnly())) {
                        properties.put(name, normalize(property, componentSchemas, ancestors, ancestorReferences));
                    }
                });
            }
            ApiSchema items = schema.getItems() == null ? null
                    : normalize(schema.getItems(), componentSchemas, ancestors, ancestorReferences);
            if (type == SchemaType.ARRAY && items == null) {
                warnings.add("Array schemas must declare items");
            }
            if (properties.values().stream().anyMatch(property -> !property.supported())
                    || items != null && !items.supported()) {
                warnings.add("Schema contains an unsupported nested schema");
            }

            return new ApiSchema(
                    type == null ? SchemaType.OBJECT : type,
                    schema.getFormat(),
                    Boolean.TRUE.equals(schema.getNullable()),
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
            warnings.add("oneOf schemas are not supported");
        }
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            warnings.add("anyOf schemas are not supported");
        }
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            warnings.add("allOf schemas are not supported");
        }
        if (schema.getDiscriminator() != null) {
            warnings.add("Discriminator schemas are not supported");
        }
        return warnings;
    }

    private List<String> unsupportedSemanticWarnings(Schema<?> schema) {
        List<String> warnings = new ArrayList<>();
        Object additionalProperties = schema.getAdditionalProperties();
        if (additionalProperties != null && !Boolean.FALSE.equals(additionalProperties)) {
            warnings.add("Schemas with additionalProperties are not supported");
        }
        if (Boolean.TRUE.equals(schema.getNullable())) {
            warnings.add("Nullable schemas are not supported");
        }
        if (Boolean.TRUE.equals(schema.getExclusiveMinimum()) || Boolean.TRUE.equals(schema.getExclusiveMaximum())) {
            warnings.add("Schemas with exclusive numeric bounds are not supported");
        }
        return warnings;
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
}
