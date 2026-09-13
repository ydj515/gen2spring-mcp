package io.gen2spring.mcp.application.validation;

import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.OutputKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ExpectedToolResponseFactory {
    private static final String SAFE_MESSAGE = "Expected response fixture cannot be derived";
    private static final String CONTENT_TYPE = "application/json";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int SERIALIZED_NULL_WITH_SEPARATOR_BYTES = 5;
    private static final int MAX_SPARSE_ARRAY_INDEX =
            (MAX_RESPONSE_BYTES - 1) / SERIALIZED_NULL_WITH_SEPARATOR_BYTES;

    public ExpectedToolResponse create(ToolDefinition tool) {
        Objects.requireNonNull(tool, SAFE_MESSAGE);
        if (tool.operationId() == null || tool.operationId().isBlank()) {
            throw invalid();
        }
        Map<String, Object> marker = marker(tool.operationId());
        ResponseNormalizationPolicy policy = tool.execution() == null
                ? null
                : tool.execution().responseNormalization();
        PaginationPolicy pagination = tool.execution() == null
                ? null : tool.execution().paginationPolicy();
        if (pagination != null) {
            return pagination(tool, policy, pagination);
        }
        if (tool.outputKind() == OutputKind.TYPED_DTO) {
            return typed(tool, policy);
        }
        if (policy == null) {
            ExpectedUpstreamResponse upstream = new ExpectedUpstreamResponse(200, CONTENT_TYPE, marker);
            return new ExpectedToolResponse(upstream, upstream.body());
        }

        Object root = policy.dataPointer() == null
                ? mutableCopy(marker)
                : insert(null, policy.dataPointer(), mutableCopy(marker));
        if (policy.successCodePointer() != null) {
            if (policy.successValues().isEmpty()) {
                throw invalid();
            }
            root = insert(root, policy.successCodePointer(), policy.successValues().getFirst());
        }
        if (policy.errorMessagePointer() != null) {
            root = insert(root, policy.errorMessagePointer(), "NORMAL_SERVICE");
        }
        if (policy.totalCountPointer() != null) {
            root = insert(root, policy.totalCountPointer(), 1);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", policy.dataPointer() == null ? root : at(root, policy.dataPointer()));
        if (policy.totalCountPointer() != null) {
            result.put("page", Map.of("totalCount", at(root, policy.totalCountPointer())));
        }
        if (policy.successCodePointer() != null || policy.errorMessagePointer() != null) {
            Map<String, Object> provider = new LinkedHashMap<>();
            if (policy.successCodePointer() != null) {
                provider.put("code", at(root, policy.successCodePointer()));
            }
            if (policy.errorMessagePointer() != null) {
                provider.put("message", at(root, policy.errorMessagePointer()));
            }
            result.put("provider", provider);
        }

        ExpectedUpstreamResponse upstream = new ExpectedUpstreamResponse(200, CONTENT_TYPE, root);
        return new ExpectedToolResponse(upstream, immutableJson(result));
    }

    private ExpectedToolResponse typed(ToolDefinition tool, ResponseNormalizationPolicy policy) {
        ApiSchema providerSchema = tool.output().providerSchema();
        if (providerSchema == null) {
            throw invalid();
        }
        SchemaFixtureFactory fixtures = new SchemaFixtureFactory();
        Object root = mutableCopy(fixtures.create(providerSchema, 0));
        if (policy != null) {
            if (policy.dataPointer() != null) {
                root = insert(root, policy.dataPointer(),
                        fixtures.create(schemaAt(providerSchema, policy.dataPointer()), 0));
            }
            if (policy.successCodePointer() != null) {
                if (policy.successValues().isEmpty()) {
                    throw invalid();
                }
                root = insert(root, policy.successCodePointer(), policy.successValues().getFirst());
            }
            if (policy.errorMessagePointer() != null) {
                root = insert(root, policy.errorMessagePointer(),
                        fixtures.create(schemaAt(providerSchema, policy.errorMessagePointer()), 0));
            }
            if (policy.totalCountPointer() != null) {
                root = insert(root, policy.totalCountPointer(),
                        fixtures.create(schemaAt(providerSchema, policy.totalCountPointer()), 0));
            }
        }
        Object expectedResult = policy == null ? immutableJson(root) : normalizedResult(root, policy);
        ExpectedUpstreamResponse upstream = new ExpectedUpstreamResponse(200, CONTENT_TYPE, root);
        return new ExpectedToolResponse(upstream, expectedResult);
    }

    private ExpectedToolResponse pagination(
            ToolDefinition tool, ResponseNormalizationPolicy normalization, PaginationPolicy pagination) {
        ApiSchema providerSchema = tool.output() == null ? null : tool.output().providerSchema();
        if (providerSchema == null) {
            throw invalid();
        }
        SchemaFixtureFactory fixtures = new SchemaFixtureFactory();
        Object firstRoot = mutableCopy(fixtures.create(providerSchema, 0));
        Object secondRoot = mutableCopy(fixtures.create(providerSchema, 1));
        ApiSchema itemsSchema = schemaAt(providerSchema, pagination.itemsPointer());
        ApiSchema nextSchema = schemaAt(providerSchema, pagination.nextValuePointer());
        Object firstItem = fixtures.create(itemsSchema.items(), 0);
        Object secondItem = fixtures.create(itemsSchema.items(), 1);
        Object nextValue = fixtures.create(nextSchema, 1);
        firstRoot = insert(firstRoot, pagination.itemsPointer(), List.of(firstItem));
        firstRoot = insert(firstRoot, pagination.nextValuePointer(), nextValue);
        secondRoot = insert(secondRoot, pagination.itemsPointer(), List.of(secondItem));
        secondRoot = insert(secondRoot, pagination.nextValuePointer(), null);
        firstRoot = normalizationFields(firstRoot, normalization, 2);
        secondRoot = normalizationFields(secondRoot, normalization, 2);

        Object aggregate = mutableCopy(firstRoot);
        aggregate = insert(aggregate, pagination.itemsPointer(), List.of(firstItem, secondItem));
        aggregate = insert(aggregate, pagination.nextValuePointer(), null);
        Object expectedResult = normalization == null
                ? immutableJson(aggregate) : normalizedResult(aggregate, normalization);
        List<ExpectedUpstreamInteraction> interactions = List.of(
                interaction(pagination.requestParameter(), pagination.initialValue(), firstRoot),
                interaction(pagination.requestParameter(), nextValue, secondRoot));
        return new ExpectedToolResponse(interactions, expectedResult);
    }

    private ExpectedUpstreamInteraction interaction(String parameter, Object value, Object responseBody) {
        Map<String, Object> internal = value == null ? Map.of() : Map.of(parameter, value);
        return new ExpectedUpstreamInteraction(internal, ExpectedUpstreamOutcome.RESPONSE,
                new ExpectedUpstreamResponse(200, CONTENT_TYPE, responseBody));
    }

    private Object normalizationFields(Object root, ResponseNormalizationPolicy policy, int totalCount) {
        if (policy == null) {
            return root;
        }
        Object result = root;
        if (policy.successCodePointer() != null) {
            result = insert(result, policy.successCodePointer(), policy.successValues().getFirst());
        }
        if (policy.errorMessagePointer() != null) {
            result = insert(result, policy.errorMessagePointer(), "NORMAL_SERVICE");
        }
        if (policy.totalCountPointer() != null) {
            result = insert(result, policy.totalCountPointer(), totalCount);
        }
        return result;
    }

    private Object normalizedResult(Object root, ResponseNormalizationPolicy policy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", policy.dataPointer() == null ? root : at(root, policy.dataPointer()));
        if (policy.totalCountPointer() != null) {
            result.put("page", Map.of("totalCount", at(root, policy.totalCountPointer())));
        }
        if (policy.successCodePointer() != null || policy.errorMessagePointer() != null) {
            Map<String, Object> provider = new LinkedHashMap<>();
            if (policy.successCodePointer() != null) {
                provider.put("code", at(root, policy.successCodePointer()));
            }
            if (policy.errorMessagePointer() != null) {
                provider.put("message", at(root, policy.errorMessagePointer()));
            }
            result.put("provider", provider);
        }
        return immutableJson(result);
    }

    private ApiSchema schemaAt(ApiSchema root, String pointer) {
        ApiSchema current = root;
        for (String token : tokens(pointer)) {
            if (current.type() == io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.OBJECT) {
                current = current.properties() == null ? null : current.properties().get(token);
            } else if (current.type() == io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.ARRAY) {
                current = current.items();
            } else {
                current = null;
            }
            if (current == null) {
                throw invalid();
            }
        }
        return current;
    }

    private Object insert(Object root, String pointer, Object value) {
        List<String> tokens = tokens(pointer);
        if (tokens.isEmpty()) {
            return value;
        }
        Object actualRoot = root;
        if (actualRoot == null) {
            actualRoot = container(tokens.getFirst());
        }
        Object current = actualRoot;
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            boolean leaf = index == tokens.size() - 1;
            if (current instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) rawMap;
                if (leaf) {
                    map.put(token, value);
                    continue;
                }
                Object child = map.get(token);
                if (child == null) {
                    child = container(tokens.get(index + 1));
                    map.put(token, child);
                }
                current = child;
                continue;
            }
            if (current instanceof List<?> rawList) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) rawList;
                int arrayIndex = arrayIndex(token);
                grow(list, arrayIndex);
                if (leaf) {
                    list.set(arrayIndex, value);
                    continue;
                }
                Object child = list.get(arrayIndex);
                if (child == null) {
                    child = container(tokens.get(index + 1));
                    list.set(arrayIndex, child);
                }
                current = child;
                continue;
            }
            throw invalid();
        }
        return actualRoot;
    }

    private Object at(Object root, String pointer) {
        Object current = root;
        for (String token : tokens(pointer)) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    throw invalid();
                }
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                int index = arrayIndex(token);
                if (index >= list.size()) {
                    throw invalid();
                }
                current = list.get(index);
            } else {
                throw invalid();
            }
        }
        return current;
    }

    private List<String> tokens(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return List.of();
        }
        if (!pointer.startsWith("/")) {
            throw invalid();
        }
        List<String> result = new ArrayList<>();
        for (String encoded : pointer.substring(1).split("/", -1)) {
            StringBuilder token = new StringBuilder(encoded.length());
            for (int index = 0; index < encoded.length(); index++) {
                char character = encoded.charAt(index);
                if (character != '~') {
                    token.append(character);
                    continue;
                }
                if (++index == encoded.length()) {
                    throw invalid();
                }
                char escape = encoded.charAt(index);
                if (escape == '0') {
                    token.append('~');
                } else if (escape == '1') {
                    token.append('/');
                } else {
                    throw invalid();
                }
            }
            result.add(token.toString());
        }
        return List.copyOf(result);
    }

    private Object container(String token) {
        return numericIndex(token) ? new ArrayList<>() : new LinkedHashMap<String, Object>();
    }

    private boolean numericIndex(String token) {
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

    private int arrayIndex(String token) {
        if (!numericIndex(token)) {
            throw invalid();
        }
        try {
            int index = Integer.parseInt(token);
            if (index > MAX_SPARSE_ARRAY_INDEX) {
                throw invalid();
            }
            return index;
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private void grow(List<Object> list, int index) {
        while (list.size() <= index) {
            list.add(null);
        }
    }

    private Map<String, Object> marker(String operationId) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("validated", true);
        marker.put("operationId", operationId);
        return marker;
    }

    private Object mutableCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, mutableCopy(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(mutableCopy(item)));
            return copy;
        }
        return value;
    }

    private Object immutableJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, immutableJson(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableJson(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException(SAFE_MESSAGE);
    }

    public record ExpectedToolResponse(
            List<ExpectedUpstreamInteraction> upstreamInteractions, Object expectedResult) {
        public ExpectedToolResponse {
            if (upstreamInteractions == null || upstreamInteractions.isEmpty()) {
                throw new IllegalArgumentException(SAFE_MESSAGE);
            }
            upstreamInteractions = List.copyOf(upstreamInteractions);
        }

        public ExpectedToolResponse(ExpectedUpstreamResponse response, Object expectedResult) {
            this(List.of(new ExpectedUpstreamInteraction(
                    Map.of(), ExpectedUpstreamOutcome.RESPONSE, response)), expectedResult);
        }

        public ExpectedUpstreamResponse upstreamResponse() {
            return upstreamInteractions.getFirst().response();
        }
    }
}
