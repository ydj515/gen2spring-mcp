package io.gen2spring.mcp.adapter.provideregress;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ProviderEgressCodec {
    public static final int MAX_WIRE_BYTES = 1_500_000;
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public byte[] encodeRequest(ProviderCallRequest request, Duration timeout) {
        if (request == null || !validTimeout(timeout)) throw invalid();
        ObjectNode root = json.createObjectNode();
        root.put("method", request.method().name());
        root.put("uri", request.uri().toASCIIString());
        root.set("headers", headers(request.headers()));
        root.put("body", Base64.getEncoder().encodeToString(request.body()));
        root.put("timeoutMillis", timeout.toMillis());
        return write(root);
    }

    public DecodedProviderCall decodeRequest(byte[] wire) {
        ObjectNode root = object(wire, Set.of("method", "uri", "headers", "body", "timeoutMillis"));
        try {
            HttpMethod method = HttpMethod.valueOf(text(root, "method"));
            URI uri = URI.create(text(root, "uri"));
            Map<String, List<String>> headers = headers(root.get("headers"));
            byte[] body = Base64.getDecoder().decode(text(root, "body"));
            JsonNode timeoutNode = root.get("timeoutMillis");
            if (timeoutNode == null || !timeoutNode.isIntegralNumber() || !timeoutNode.canConvertToLong()) throw invalid();
            Duration timeout = Duration.ofMillis(timeoutNode.longValue());
            if (!validTimeout(timeout)) throw invalid();
            return new DecodedProviderCall(new ProviderCallRequest(method, uri, headers, body), timeout);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public byte[] encodeResponse(ProviderCallResponse response) {
        if (response == null) throw invalid();
        ObjectNode root = json.createObjectNode();
        root.put("status", response.status());
        root.set("headers", headers(response.headers()));
        root.put("body", Base64.getEncoder().encodeToString(response.body()));
        return write(root);
    }

    public ProviderCallResponse decodeResponse(byte[] wire) {
        ObjectNode root = object(wire, Set.of("status", "headers", "body"));
        try {
            JsonNode status = root.get("status");
            if (status == null || !status.isIntegralNumber() || !status.canConvertToInt()) throw invalid();
            return new ProviderCallResponse(
                    status.intValue(), headers(root.get("headers")),
                    Base64.getDecoder().decode(text(root, "body")));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private ObjectNode headers(Map<String, List<String>> values) {
        ObjectNode node = json.createObjectNode();
        new TreeMap<>(values).forEach((name, headerValues) -> {
            var array = node.putArray(name);
            headerValues.forEach(array::add);
        });
        return node;
    }

    private Map<String, List<String>> headers(JsonNode value) {
        if (!(value instanceof ObjectNode object) || object.size() > 64) throw invalid();
        Map<String, List<String>> result = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isArray()) throw invalid();
            List<String> values = new ArrayList<>();
            entry.getValue().forEach(item -> {
                if (!item.isTextual()) throw invalid();
                values.add(item.textValue());
            });
            result.put(entry.getKey(), List.copyOf(values));
        });
        return Map.copyOf(result);
    }

    private ObjectNode object(byte[] wire, Set<String> fields) {
        if (wire == null || wire.length < 2 || wire.length > MAX_WIRE_BYTES) throw invalid();
        try {
            JsonNode value = json.readTree(wire);
            if (!(value instanceof ObjectNode object)
                    || !fieldNames(object).equals(fields)) throw invalid();
            return object;
        } catch (RuntimeException failure) {
            throw invalid();
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private Set<String> fieldNames(ObjectNode object) {
        java.util.HashSet<String> result = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(result::add);
        return Set.copyOf(result);
    }

    private String text(ObjectNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private byte[] write(ObjectNode value) {
        try {
            byte[] wire = json.writeValueAsBytes(value);
            if (wire.length > MAX_WIRE_BYTES) throw invalid();
            return wire;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private boolean validTimeout(Duration timeout) {
        return timeout != null && timeout.compareTo(Duration.ofMillis(10)) >= 0
                && timeout.compareTo(Duration.ofSeconds(60)) <= 0;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Provider egress message is invalid");
    }

    public record DecodedProviderCall(ProviderCallRequest request, Duration timeout) {}
}
