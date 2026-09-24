package io.gen2spring.mcp.application.managed.execution.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@SuppressWarnings("deprecation")
final class PaginationAccumulator {
    private static final int MAX_BYTES = 1_048_576;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private final PaginationPolicy policy;
    private final Set<String> seen = new HashSet<>();
    private JsonNode aggregate;
    private ArrayNode items;
    private int pages;

    PaginationAccumulator(PaginationPolicy policy) {
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        if (policy.initialValue() != null) {
            seen.add(wire(policy.initialValue()));
        }
    }

    PageStep append(ProviderCallResponse response) {
        if (!jsonMedia(response.firstHeader("Content-Type")) || response.body().length > MAX_BYTES) {
            throw new PageProtocolFailure();
        }
        try (JsonParser parser = json.createParser(response.body())) {
            JsonNode page = json.readTree(parser);
            if (page == null || parser.nextToken() != null) {
                throw new PageProtocolFailure();
            }
            JsonNode pageItems = page.at(policy.itemsPointer());
            if (!pageItems.isArray()) {
                throw new PageProtocolFailure();
            }
            if (aggregate == null) {
                aggregate = page.deepCopy();
                JsonNode selected = aggregate.at(policy.itemsPointer());
                if (!(selected instanceof ArrayNode array)) {
                    throw new PageProtocolFailure();
                }
                items = array;
            } else {
                pageItems.forEach(item -> items.add(item.deepCopy()));
            }
            pages++;
            JsonNode next = page.at(policy.nextValuePointer());
            boolean terminal = next.isMissingNode() || next.isNull() || next.isTextual() && next.textValue().isEmpty();
            String wire = null;
            if (!terminal) {
                wire = wire(next);
                if (!seen.add(wire)) {
                    throw new PageProtocolFailure();
                }
            }
            if (!next.isMissingNode() || !terminal || !aggregate.at(policy.nextValuePointer()).isMissingNode()) {
                setAt(aggregate, policy.nextValuePointer(), terminal ? json.nullNode() : next.deepCopy());
            }
            if (items.size() > policy.maxItems()
                    || !terminal && (pages >= policy.maxPages() || items.size() >= policy.maxItems())) {
                throw new PageResourceFailure();
            }
            bytes();
            return new PageStep(wire, terminal);
        } catch (PageProtocolFailure | PageResourceFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PageProtocolFailure();
        }
    }

    byte[] bytes() {
        if (aggregate == null) {
            throw new PageProtocolFailure();
        }
        try {
            byte[] value = json.writeValueAsBytes(aggregate);
            if (value.length > MAX_BYTES) {
                throw new PageResourceFailure();
            }
            return value;
        } catch (PageResourceFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PageProtocolFailure();
        }
    }

    private void setAt(JsonNode root, String pointer, JsonNode value) {
        List<String> tokens = tokens(pointer);
        JsonNode current = root;
        for (int index = 0; index < tokens.size() - 1; index++) {
            current = child(current, tokens.get(index));
        }
        String leaf = tokens.getLast();
        if (current instanceof ObjectNode object) {
            object.set(leaf, value);
        } else if (current instanceof ArrayNode array) {
            int index = arrayIndex(leaf);
            if (index >= array.size()) throw new PageProtocolFailure();
            array.set(index, value);
        } else {
            throw new PageProtocolFailure();
        }
    }

    private JsonNode child(JsonNode current, String token) {
        JsonNode child;
        if (current instanceof ObjectNode object) {
            child = object.get(token);
        } else if (current instanceof ArrayNode array) {
            int index = arrayIndex(token);
            child = index < array.size() ? array.get(index) : null;
        } else {
            child = null;
        }
        if (child == null || child.isMissingNode()) throw new PageProtocolFailure();
        return child;
    }

    private List<String> tokens(String pointer) {
        List<String> result = new ArrayList<>();
        for (String token : pointer.substring(1).split("/", -1)) {
            result.add(token.replace("~1", "/").replace("~0", "~"));
        }
        return result;
    }

    private int arrayIndex(String value) {
        try {
            if (value.isEmpty() || value.length() > 1 && value.startsWith("0")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new PageProtocolFailure();
        }
    }

    private String wire(JsonNode value) {
        if (value.isTextual() && !value.textValue().isEmpty()) return value.textValue();
        if (value.isIntegralNumber()) return value.bigIntegerValue().toString();
        throw new PageProtocolFailure();
    }

    private String wire(Object value) {
        if (value instanceof String text && !text.isEmpty()) return text;
        if (value instanceof BigInteger integer) return integer.toString();
        throw new PageProtocolFailure();
    }

    private boolean jsonMedia(String contentType) {
        if (contentType == null) return false;
        String media = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return "application/json".equals(media) || media.startsWith("application/") && media.endsWith("+json");
    }

    record PageStep(String nextValue, boolean terminal) {}

    static final class PageProtocolFailure extends RuntimeException {
        private PageProtocolFailure() { super(null, null, false, false); }
    }

    static final class PageResourceFailure extends RuntimeException {
        private PageResourceFailure() { super(null, null, false, false); }
    }
}
