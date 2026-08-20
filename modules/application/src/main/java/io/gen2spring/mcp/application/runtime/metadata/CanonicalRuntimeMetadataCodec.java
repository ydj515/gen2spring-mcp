package io.gen2spring.mcp.application.runtime.metadata;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.RUNTIME_METADATA_INVALID;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@SuppressWarnings("deprecation")
public final class CanonicalRuntimeMetadataCodec {
    public static final int MAX_BYTES = 1_048_576;
    private static final String STAGE = "RUNTIME_METADATA";
    private static final String SAFE_MESSAGE = "Runtime metadata could not be generated";
    private static final ObjectMapper MAPPER = objectMapper();
    private static final List<String> SCHEMA_FIELDS = List.of(
            "type", "format", "enum", "minimum", "maximum", "minLength", "maxLength",
            "pattern", "properties", "required", "items", "minItems", "anyOf", "description");

    public RuntimeMetadataArtifact encode(RuntimeMetadataDocument document) {
        try {
            ObjectNode payload = payloadNode(document);
            String checksum = sha256(MAPPER.writeValueAsBytes(payload));
            ObjectNode complete = completeNode(document, checksum);
            byte[] json = MAPPER.writeValueAsBytes(complete);
            if (json.length + 1 > MAX_BYTES) {
                throw new IllegalArgumentException("Runtime metadata is too large");
            }
            byte[] content = Arrays.copyOf(json, json.length + 1);
            content[content.length - 1] = '\n';
            return new RuntimeMetadataArtifact(document, checksum, content);
        } catch (GeneratorException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid(failure);
        }
    }

    public RuntimeMetadataArtifact decode(byte[] content) {
        try {
            if (content == null || content.length < 2 || content.length > MAX_BYTES
                    || content[content.length - 1] != '\n'
                    || content[content.length - 2] == '\n') {
                throw new IllegalArgumentException("Runtime metadata content is invalid");
            }
            byte[] json = Arrays.copyOf(content, content.length - 1);
            JsonNode root;
            try (JsonParser parser = MAPPER.createParser(json)) {
                root = MAPPER.readTree(parser);
                if (parser.nextToken() != null) {
                    throw new IllegalArgumentException("Runtime metadata has trailing content");
                }
            }
            ObjectNode object = object(root, "document");
            requireFields(object, Set.of("metadataVersion", "specificationChecksum", "checksum", "tools"));
            RuntimeMetadataDocument document = new RuntimeMetadataDocument(
                    text(object, "metadataVersion"),
                    text(object, "specificationChecksum"),
                    tools(object.get("tools")));
            String receivedChecksum = text(object, "checksum");
            RuntimeMetadataArtifact canonical = encode(document);
            if (!canonical.checksum().equals(receivedChecksum)
                    || !Arrays.equals(content, canonical.content())) {
                throw new IllegalArgumentException("Runtime metadata is not canonical");
            }
            return canonical;
        } catch (GeneratorException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid(failure);
        }
    }

    public String encodeTool(RuntimeTool tool) {
        try {
            return MAPPER.writeValueAsString(toolNode(tool));
        } catch (Exception failure) {
            throw invalid(failure);
        }
    }

    private ObjectNode payloadNode(RuntimeMetadataDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("Runtime metadata document is required");
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("metadataVersion", document.metadataVersion());
        payload.put("specificationChecksum", document.specificationChecksum());
        payload.set("tools", toolsNode(document.tools()));
        return payload;
    }

    private ObjectNode completeNode(RuntimeMetadataDocument document, String checksum) {
        ObjectNode complete = MAPPER.createObjectNode();
        complete.put("metadataVersion", document.metadataVersion());
        complete.put("specificationChecksum", document.specificationChecksum());
        complete.put("checksum", checksum);
        complete.set("tools", toolsNode(document.tools()));
        return complete;
    }

    private ArrayNode toolsNode(List<RuntimeTool> tools) {
        ArrayNode array = MAPPER.createArrayNode();
        tools.forEach(tool -> array.add(toolNode(tool)));
        return array;
    }

    private ObjectNode toolNode(RuntimeTool tool) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("operationId", tool.operationId());
        node.put("name", tool.name());
        node.put("description", tool.description());
        node.set("inputSchema", schemaNode(tool.inputSchema()));
        node.put("outputKind", tool.outputKind());
        node.set("outputSchema", schemaNode(tool.outputSchema()));
        node.set("http", httpNode(tool.http()));
        node.set("responseNormalization", responseNode(tool.responseNormalization()));
        node.set("retry", retryNode(tool.retry()));
        node.set("pagination", paginationNode(tool.pagination()));
        node.set("credentials", credentialsNode(tool.credentials()));
        return node;
    }

    private ObjectNode httpNode(RuntimeHttp http) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("method", http.method().name());
        node.put("baseUrl", http.baseUrl());
        node.put("path", http.path());
        ArrayNode bindings = MAPPER.createArrayNode();
        for (ParameterBinding binding : http.bindings()) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("sourceName", binding.sourceName());
            item.put("targetLocation", binding.targetLocation().name());
            item.put("targetName", binding.targetName());
            bindings.add(item);
        }
        node.set("bindings", bindings);
        node.put("objectRequestBody", http.objectRequestBody());
        node.put("requestBodyRequired", http.requestBodyRequired());
        return node;
    }

    private JsonNode responseNode(ResponseNormalizationPolicy policy) {
        if (policy == null) {
            return MAPPER.nullNode();
        }
        ObjectNode node = MAPPER.createObjectNode();
        putNullable(node, "dataPointer", policy.dataPointer());
        putNullable(node, "successCodePointer", policy.successCodePointer());
        ArrayNode values = MAPPER.createArrayNode();
        policy.successValues().forEach(value -> values.add(scalarNode(value)));
        node.set("successValues", values);
        putNullable(node, "errorMessagePointer", policy.errorMessagePointer());
        putNullable(node, "totalCountPointer", policy.totalCountPointer());
        return node;
    }

    private JsonNode retryNode(RetryPolicy policy) {
        if (policy == null) {
            return MAPPER.nullNode();
        }
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode statuses = MAPPER.createArrayNode();
        policy.statusCodes().forEach(statuses::add);
        node.set("statusCodes", statuses);
        node.put("networkErrors", policy.networkErrors());
        node.put("maxRetries", policy.maxRetries());
        node.put("initialBackoffMillis", policy.initialBackoffMillis());
        node.put("maxBackoffMillis", policy.maxBackoffMillis());
        node.put("respectRetryAfter", policy.respectRetryAfter());
        return node;
    }

    private JsonNode paginationNode(PaginationPolicy policy) {
        if (policy == null) {
            return MAPPER.nullNode();
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("requestParameter", policy.requestParameter());
        node.set("initialValue", scalarNode(policy.initialValue()));
        node.put("itemsPointer", policy.itemsPointer());
        node.put("nextValuePointer", policy.nextValuePointer());
        node.put("maxPages", policy.maxPages());
        node.put("maxItems", policy.maxItems());
        return node;
    }

    private ArrayNode credentialsNode(List<RuntimeCredential> credentials) {
        ArrayNode array = MAPPER.createArrayNode();
        for (RuntimeCredential credential : credentials) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("credentialSlot", credential.credentialSlot());
            item.put("targetLocation", credential.targetLocation().name());
            item.put("targetName", credential.targetName());
            item.put("required", credential.required());
            array.add(item);
        }
        return array;
    }

    private ObjectNode schemaNode(Map<String, Object> schema) {
        ObjectNode node = MAPPER.createObjectNode();
        if (!SCHEMA_FIELDS.containsAll(schema.keySet())) {
            throw new IllegalArgumentException("Runtime JSON schema field is invalid");
        }
        validateSchema(schema);
        for (String field : SCHEMA_FIELDS) {
            if (!schema.containsKey(field)) {
                continue;
            }
            Object value = schema.get(field);
            switch (field) {
                case "properties" -> {
                    ObjectNode properties = MAPPER.createObjectNode();
                    Map<?, ?> source = requireMap(value);
                    source.keySet().stream().map(String.class::cast).sorted()
                            .forEach(name -> properties.set(name, schemaNode(requireStringMap(source.get(name)))));
                    node.set(field, properties);
                }
                case "items" -> node.set(field, schemaNode(requireStringMap(value)));
                case "anyOf" -> {
                    ArrayNode alternatives = MAPPER.createArrayNode();
                    requireList(value).forEach(item -> alternatives.add(schemaNode(requireStringMap(item))));
                    node.set(field, alternatives);
                }
                case "required", "enum" -> {
                    ArrayNode values = MAPPER.createArrayNode();
                    requireList(value).stream().map(this::requireStringValue).sorted().forEach(values::add);
                    node.set(field, values);
                }
                case "minimum", "maximum" -> {
                    if (!(value instanceof BigDecimal)) {
                        throw new IllegalArgumentException("Runtime JSON schema number is invalid");
                    }
                    node.set(field, scalarNode(value));
                }
                case "minLength", "maxLength", "minItems" -> {
                    if (!(value instanceof Integer)) {
                        throw new IllegalArgumentException("Runtime JSON schema integer is invalid");
                    }
                    node.set(field, scalarNode(value));
                }
                case "type", "format", "pattern", "description" ->
                    node.put(field, requireStringValue(value));
                default -> throw new IllegalArgumentException("Runtime JSON schema field is invalid");
            }
        }
        return node;
    }

    private void validateSchema(Map<String, Object> schema) {
        if (schema.isEmpty()) {
            return;
        }
        Object typeValue = schema.get("type");
        Object alternativesValue = schema.get("anyOf");
        if ((typeValue == null) == (alternativesValue == null)) {
            throw new IllegalArgumentException("Runtime JSON schema type is invalid");
        }
        String type = null;
        if (typeValue != null) {
            type = requireStringValue(typeValue);
            if (!Set.of("string", "integer", "number", "boolean", "array", "object", "null")
                    .contains(type)) {
                throw new IllegalArgumentException("Runtime JSON schema type is invalid");
            }
        }
        if (alternativesValue != null) {
            List<?> alternatives = requireList(alternativesValue);
            if (alternatives.size() != 2) {
                throw new IllegalArgumentException("Runtime nullable schema is invalid");
            }
            alternatives.forEach(alternative -> validateSchema(requireStringMap(alternative)));
            Map<String, Object> nullAlternative = requireStringMap(alternatives.get(1));
            if (!Map.of("type", "null").equals(nullAlternative)) {
                throw new IllegalArgumentException("Runtime nullable schema is invalid");
            }
        }
        validateLength(schema, "minLength");
        validateLength(schema, "maxLength");
        validateLength(schema, "minItems");
        Integer minLength = (Integer) schema.get("minLength");
        Integer maxLength = (Integer) schema.get("maxLength");
        if (minLength != null && maxLength != null && minLength > maxLength) {
            throw new IllegalArgumentException("Runtime JSON schema length is invalid");
        }
        if (schema.containsKey("minItems") && !"array".equals(type)
                || "array".equals(type) != schema.containsKey("items")) {
            throw new IllegalArgumentException("Runtime array schema is invalid");
        }
        if ("object".equals(type)) {
            Map<String, Object> properties = requireStringMap(schema.get("properties"));
            List<?> required = requireList(schema.get("required"));
            if (required.stream().anyMatch(name -> !(name instanceof String) || !properties.containsKey(name))) {
                throw new IllegalArgumentException("Runtime object schema is invalid");
            }
        } else if (schema.containsKey("properties") || schema.containsKey("required")) {
            throw new IllegalArgumentException("Runtime object schema is invalid");
        }
        if (schema.containsKey("enum") && !"string".equals(type)) {
            throw new IllegalArgumentException("Runtime enum schema is invalid");
        }
        BigDecimal minimum = decimalConstraint(schema, "minimum");
        BigDecimal maximum = decimalConstraint(schema, "maximum");
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Runtime numeric schema is invalid");
        }
    }

    private void validateLength(Map<String, Object> schema, String field) {
        Object value = schema.get(field);
        if (value != null && (!(value instanceof Integer integer) || integer < 0)) {
            throw new IllegalArgumentException("Runtime JSON schema length is invalid");
        }
    }

    private BigDecimal decimalConstraint(Map<String, Object> schema, String field) {
        Object value = schema.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof BigDecimal decimal)) {
            throw new IllegalArgumentException("Runtime JSON schema number is invalid");
        }
        return decimal;
    }

    private List<RuntimeTool> tools(JsonNode value) {
        ArrayNode array = array(value, "tools");
        List<RuntimeTool> tools = new ArrayList<>();
        array.forEach(item -> tools.add(tool(object(item, "tool"))));
        return tools;
    }

    private RuntimeTool tool(ObjectNode node) {
        requireFields(node, Set.of(
                "operationId", "name", "description", "inputSchema", "outputKind", "outputSchema",
                "http", "responseNormalization", "retry", "pagination", "credentials"));
        return new RuntimeTool(
                text(node, "operationId"),
                text(node, "name"),
                text(node, "description"),
                schemaMap(object(node.get("inputSchema"), "inputSchema")),
                text(node, "outputKind"),
                schemaMap(object(node.get("outputSchema"), "outputSchema")),
                http(object(node.get("http"), "http")),
                response(node.get("responseNormalization")),
                retry(node.get("retry")),
                pagination(node.get("pagination")),
                credentials(node.get("credentials")));
    }

    private RuntimeHttp http(ObjectNode node) {
        requireFields(node, Set.of(
                "method", "baseUrl", "path", "bindings", "objectRequestBody", "requestBodyRequired"));
        List<ParameterBinding> bindings = new ArrayList<>();
        array(node.get("bindings"), "bindings").forEach(item -> {
            ObjectNode binding = object(item, "binding");
            requireFields(binding, Set.of("sourceName", "targetLocation", "targetName"));
            bindings.add(new ParameterBinding(
                    text(binding, "sourceName"),
                    enumValue(ParameterLocation.class, text(binding, "targetLocation")),
                    text(binding, "targetName")));
        });
        return new RuntimeHttp(
                enumValue(HttpMethod.class, text(node, "method")),
                text(node, "baseUrl"), text(node, "path"), bindings,
                bool(node, "objectRequestBody"), bool(node, "requestBodyRequired"));
    }

    private ResponseNormalizationPolicy response(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        ObjectNode node = object(value, "responseNormalization");
        requireFields(node, Set.of(
                "dataPointer", "successCodePointer", "successValues", "errorMessagePointer", "totalCountPointer"));
        List<Object> values = new ArrayList<>();
        array(node.get("successValues"), "successValues").forEach(item -> values.add(scalar(item)));
        return new ResponseNormalizationPolicy(
                nullableText(node, "dataPointer"), nullableText(node, "successCodePointer"), values,
                nullableText(node, "errorMessagePointer"), nullableText(node, "totalCountPointer"));
    }

    private RetryPolicy retry(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        ObjectNode node = object(value, "retry");
        requireFields(node, Set.of(
                "statusCodes", "networkErrors", "maxRetries", "initialBackoffMillis",
                "maxBackoffMillis", "respectRetryAfter"));
        List<Integer> statuses = new ArrayList<>();
        array(node.get("statusCodes"), "statusCodes").forEach(status -> statuses.add(exactInt(status)));
        return new RetryPolicy(
                statuses, bool(node, "networkErrors"), integer(node, "maxRetries"),
                longValue(node, "initialBackoffMillis"), longValue(node, "maxBackoffMillis"),
                bool(node, "respectRetryAfter"));
    }

    private PaginationPolicy pagination(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        ObjectNode node = object(value, "pagination");
        requireFields(node, Set.of(
                "requestParameter", "initialValue", "itemsPointer", "nextValuePointer", "maxPages", "maxItems"));
        return new PaginationPolicy(
                text(node, "requestParameter"), scalar(node.get("initialValue")),
                text(node, "itemsPointer"), text(node, "nextValuePointer"),
                integer(node, "maxPages"), integer(node, "maxItems"));
    }

    private List<RuntimeCredential> credentials(JsonNode value) {
        List<RuntimeCredential> credentials = new ArrayList<>();
        array(value, "credentials").forEach(item -> {
            ObjectNode credential = object(item, "credential");
            requireFields(credential, Set.of("credentialSlot", "targetLocation", "targetName", "required"));
            credentials.add(new RuntimeCredential(
                    text(credential, "credentialSlot"),
                    enumValue(ParameterLocation.class, text(credential, "targetLocation")),
                    text(credential, "targetName"), bool(credential, "required")));
        });
        return credentials;
    }

    private Map<String, Object> schemaMap(ObjectNode node) {
        if (!SCHEMA_FIELDS.containsAll(iterableFieldNames(node))) {
            throw new IllegalArgumentException("Runtime JSON schema field is invalid");
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        for (String field : SCHEMA_FIELDS) {
            JsonNode value = node.get(field);
            if (value == null) {
                continue;
            }
            switch (field) {
                case "properties" -> {
                    ObjectNode properties = object(value, "properties");
                    Map<String, Object> parsed = new TreeMap<>();
                    properties.fields().forEachRemaining(entry ->
                            parsed.put(entry.getKey(), schemaMap(object(entry.getValue(), "property"))));
                    schema.put(field, parsed);
                }
                case "items" -> schema.put(field, schemaMap(object(value, "items")));
                case "anyOf" -> {
                    List<Object> alternatives = new ArrayList<>();
                    array(value, "anyOf").forEach(item ->
                            alternatives.add(schemaMap(object(item, "alternative"))));
                    schema.put(field, alternatives);
                }
                case "required", "enum" -> {
                    List<String> strings = new ArrayList<>();
                    array(value, field).forEach(item -> strings.add(requireText(item, field)));
                    strings.sort(String::compareTo);
                    schema.put(field, strings);
                }
                case "minLength", "maxLength", "minItems" -> schema.put(field, exactInt(value));
                case "minimum", "maximum" -> {
                    if (!value.isNumber()) {
                        throw new IllegalArgumentException("Runtime JSON schema number is invalid");
                    }
                    schema.put(field, value.decimalValue());
                }
                case "type", "format", "pattern", "description" -> schema.put(field, requireText(value, field));
                default -> throw new IllegalArgumentException("Runtime JSON schema field is invalid");
            }
        }
        return schema;
    }

    private JsonNode scalarNode(Object value) {
        if (value == null) {
            return MAPPER.nullNode();
        }
        if (value instanceof String string) {
            return MAPPER.getNodeFactory().textNode(string);
        }
        if (value instanceof Boolean bool) {
            return MAPPER.getNodeFactory().booleanNode(bool);
        }
        if (value instanceof BigInteger integer) {
            return MAPPER.getNodeFactory().numberNode(integer);
        }
        if (value instanceof BigDecimal decimal) {
            return MAPPER.getNodeFactory().numberNode(decimal);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return MAPPER.getNodeFactory().numberNode(((Number) value).intValue());
        }
        if (value instanceof Long number) {
            return MAPPER.getNodeFactory().numberNode(number);
        }
        throw new IllegalArgumentException("Runtime JSON scalar is invalid");
    }

    private Object scalar(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            return value.bigIntegerValue();
        }
        if (value.isBigDecimal() || value.isFloatingPointNumber()) {
            return value.decimalValue();
        }
        throw new IllegalArgumentException("Runtime JSON scalar is invalid");
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private String nullableText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNull() ? null : requireText(value, field);
    }

    private String text(ObjectNode node, String field) {
        return requireText(node.get(field), field);
    }

    private String requireText(JsonNode value, String field) {
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Runtime metadata text field is invalid");
        }
        return value.textValue();
    }

    private boolean bool(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("Runtime metadata boolean field is invalid");
        }
        return value.booleanValue();
    }

    private int integer(ObjectNode node, String field) {
        return exactInt(node.get(field));
    }

    private int exactInt(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("Runtime metadata integer field is invalid");
        }
        return value.intValue();
    }

    private long longValue(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Runtime metadata long field is invalid");
        }
        return value.longValue();
    }

    private ObjectNode object(JsonNode value, String field) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException("Runtime metadata object field is invalid");
        }
        return object;
    }

    private ArrayNode array(JsonNode value, String field) {
        if (!(value instanceof ArrayNode array)) {
            throw new IllegalArgumentException("Runtime metadata array field is invalid");
        }
        return array;
    }

    private void requireFields(ObjectNode node, Set<String> expected) {
        if (!iterableFieldNames(node).equals(expected)) {
            throw new IllegalArgumentException("Runtime metadata fields are invalid");
        }
    }

    private Set<String> iterableFieldNames(ObjectNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private Map<?, ?> requireMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Runtime JSON schema object is invalid");
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireStringMap(Object value) {
        Map<?, ?> map = requireMap(value);
        if (map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException("Runtime JSON schema object is invalid");
        }
        return (Map<String, Object>) map;
    }

    private List<?> requireList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Runtime JSON schema array is invalid");
        }
        return list;
    }

    private String requireStringValue(Object value) {
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("Runtime JSON schema text is invalid");
        }
        return string;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Runtime metadata enum field is invalid", failure);
        }
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private GeneratorException invalid(Throwable cause) {
        return GeneratorException.user(RUNTIME_METADATA_INVALID, STAGE, SAFE_MESSAGE, cause);
    }

    private static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }
}
