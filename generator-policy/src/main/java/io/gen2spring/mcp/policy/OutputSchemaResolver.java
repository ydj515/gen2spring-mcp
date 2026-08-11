package io.gen2spring.mcp.policy;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.OPERATION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.tool.McpToolDefinition.OutputKind.GENERIC_JSON;
import static io.gen2spring.mcp.domain.tool.McpToolDefinition.OutputKind.TYPED_DTO;

import io.gen2spring.mcp.domain.config.GenerationRequest.OutputSelection;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicyValidator;
import io.gen2spring.mcp.domain.tool.OutputDefinition;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class OutputSchemaResolver {
    private static final String UNSUPPORTED_MESSAGE = "Typed Tool output is unsupported";

    public OutputDefinition resolve(
            OutputSelection selection,
            ApiSchema providerSchema,
            ResponseNormalizationPolicy normalization) {
        if (selection == null || selection.mode() == null) {
            throw unsupported();
        }
        if (selection.mode() == GENERIC_JSON) {
            return new OutputDefinition(GENERIC_JSON, providerSchema, null);
        }
        if (selection.mode() != TYPED_DTO || providerSchema == null || !providerSchema.supported()) {
            throw unsupported();
        }
        if (normalization == null) {
            if (providerSchema.type() != SchemaType.OBJECT) {
                throw unsupported();
            }
            return new OutputDefinition(TYPED_DTO, providerSchema, providerSchema);
        }
        try {
            new ResponseNormalizationPolicyValidator().requireValid(normalization);
            ApiSchema data = normalization.dataPointer() == null
                    ? providerSchema : at(providerSchema, normalization.dataPointer());
            Map<String, ApiSchema> resultProperties = new TreeMap<>();
            List<String> required = new ArrayList<>();
            resultProperties.put("data", data);
            required.add("data");

            if (normalization.totalCountPointer() != null) {
                ApiSchema totalCount = at(providerSchema, normalization.totalCountPointer());
                if (totalCount.type() != SchemaType.INTEGER) {
                    throw unsupported();
                }
                resultProperties.put("page", object(
                        Map.of("totalCount", nonNegativeInteger()), List.of("totalCount")));
                required.add("page");
            }

            Map<String, ApiSchema> providerProperties = new TreeMap<>();
            List<String> providerRequired = new ArrayList<>();
            if (normalization.successCodePointer() != null) {
                ApiSchema code = at(providerSchema, normalization.successCodePointer());
                if (!isScalar(code) || !supportsValues(code, normalization.successValues())) {
                    throw unsupported();
                }
                providerProperties.put("code", code);
                providerRequired.add("code");
            }
            if (normalization.errorMessagePointer() != null) {
                ApiSchema message = at(providerSchema, normalization.errorMessagePointer());
                if (message.type() != SchemaType.STRING) {
                    throw unsupported();
                }
                providerProperties.put("message", string());
                providerRequired.add("message");
            }
            if (!providerProperties.isEmpty()) {
                resultProperties.put("provider", object(providerProperties, providerRequired));
                required.add("provider");
            }
            return new OutputDefinition(
                    TYPED_DTO, providerSchema, object(resultProperties, required));
        } catch (IllegalArgumentException failure) {
            throw unsupported();
        }
    }

    void requirePaginationSchema(ApiSchema providerSchema, PaginationPolicy pagination) {
        if (providerSchema == null || pagination == null || !providerSchema.supported()) {
            throw new IllegalArgumentException("Pagination schema is unsupported");
        }
        ApiSchema items = at(providerSchema, pagination.itemsPointer());
        ApiSchema next = at(providerSchema, pagination.nextValuePointer());
        if (items.type() != SchemaType.ARRAY || items.items() == null || !items.items().supported()
                || !next.nullable()
                || next.type() != SchemaType.STRING && next.type() != SchemaType.INTEGER) {
            throw new IllegalArgumentException("Pagination schema is unsupported");
        }
    }

    private ApiSchema at(ApiSchema schema, String pointer) {
        ApiSchema current = schema;
        for (String encoded : pointer.substring(1).split("/", -1)) {
            String token = decode(encoded);
            if (current.type() == SchemaType.OBJECT) {
                current = current.properties() == null ? null : current.properties().get(token);
            } else if (current.type() == SchemaType.ARRAY && validArrayIndex(token)) {
                current = current.items();
            } else {
                current = null;
            }
            if (current == null || !current.supported()) {
                throw unsupported();
            }
        }
        return current;
    }

    private String decode(String encoded) {
        StringBuilder decoded = new StringBuilder(encoded.length());
        for (int index = 0; index < encoded.length(); index++) {
            char character = encoded.charAt(index);
            if (character != '~') {
                decoded.append(character);
                continue;
            }
            if (++index == encoded.length()) {
                throw unsupported();
            }
            char escaped = encoded.charAt(index);
            if (escaped == '0') {
                decoded.append('~');
            } else if (escaped == '1') {
                decoded.append('/');
            } else {
                throw unsupported();
            }
        }
        return decoded.toString();
    }

    private boolean validArrayIndex(String token) {
        if (token.isEmpty() || token.length() > 1 && token.charAt(0) == '0') {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            if (token.charAt(index) < '0' || token.charAt(index) > '9') {
                return false;
            }
        }
        return true;
    }

    private boolean supportsValues(ApiSchema schema, List<Object> values) {
        return switch (schema.type()) {
            case STRING -> values.stream().allMatch(String.class::isInstance);
            case BOOLEAN -> values.stream().allMatch(Boolean.class::isInstance);
            case INTEGER -> values.stream().allMatch(value -> value instanceof BigInteger
                    || value instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0);
            case NUMBER -> values.stream().allMatch(value -> value instanceof BigInteger || value instanceof BigDecimal);
            case ARRAY, OBJECT -> false;
        };
    }

    private boolean isScalar(ApiSchema schema) {
        return switch (schema.type()) {
            case STRING, INTEGER, NUMBER, BOOLEAN -> true;
            case ARRAY, OBJECT -> false;
        };
    }

    private ApiSchema object(Map<String, ApiSchema> properties, List<String> required) {
        Map<String, ApiSchema> sorted = new TreeMap<>(properties);
        return new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null, null, null, null, null,
                Collections.unmodifiableMap(new LinkedHashMap<>(sorted)), List.copyOf(required), null, true, List.of());
    }

    private ApiSchema string() {
        return new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null, null, null, null, null,
                Map.of(), List.of(), null, true, List.of());
    }

    private ApiSchema nonNegativeInteger() {
        return new ApiSchema(
                SchemaType.INTEGER, "int64", false, List.of(), BigDecimal.ZERO, null, null, null, null, null,
                Map.of(), List.of(), null, true, List.of());
    }

    private GeneratorException unsupported() {
        return GeneratorException.user(OPERATION_UNSUPPORTED, "tool-policy", UNSUPPORTED_MESSAGE);
    }
}
