package io.gen2spring.mcp.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Request-bound MRTR state. Only explicit server configuration can bind client responses to tool arguments. */
public final class McpInteractions {
    private final ObjectMapper json;
    private final JsonNode interactions;
    private final byte[] key;
    private final Clock clock;

    public McpInteractions(ObjectMapper json, JsonNode interactions, byte[] key, Clock clock) {
        this.json = json; this.interactions = interactions.deepCopy(); this.key = key.clone(); this.clock = clock;
        if (!interactions.isObject()) throw new IllegalArgumentException("Interactions must be an object");
        interactions.properties().forEach(entry -> {
            JsonNode requests = entry.getValue().path("inputRequests");
            if (!requests.isObject() || requests.isEmpty()) throw new IllegalArgumentException("Interaction input requests are required");
            requests.properties().forEach(request -> {
                String method = request.getValue().path("method").asText();
                if (!List.of("elicitation/create", "sampling/createMessage", "roots/list").contains(method)) {
                    throw new IllegalArgumentException("Unsupported server input request");
                }
            });
        });
    }

    public ObjectNode prepare(String method, ObjectNode params, String owner) {
        String name = params.path("name").asText(params.path("uri").asText());
        JsonNode interaction = interactions.path(method + ":" + name);
        if (interaction.isMissingNode()) {
            if (params.has("requestState") || params.has("inputResponses")) throw new IllegalArgumentException("Unexpected request state");
            return null;
        }
        JsonNode capabilities = params.path("_meta").path(ModernMcpProtocol.CAPABILITIES_KEY);
        ObjectNode requests = (ObjectNode) interaction.path("inputRequests").deepCopy();
        for (var entry : requests.properties()) {
            String requestMethod = entry.getValue().path("method").asText();
            String capability = requestMethod.substring(0, requestMethod.indexOf('/'));
            if (!capabilities.path(capability).isObject()) throw new MissingCapability(capability);
            if (capability.equals("elicitation")) {
                String mode = entry.getValue().path("params").path("mode").asText("form");
                if (!capabilities.path(capability).path(mode).isObject()
                        && !(mode.equals("form") && capabilities.path(capability).isEmpty())) throw new MissingCapability("elicitation." + mode);
            }
        }
        if (!params.has("requestState")) {
            if (params.has("inputResponses")) throw new IllegalArgumentException("Input responses require request state");
            ObjectNode payload = json.createObjectNode().put("owner", owner).put("method", method)
                    .put("expires", clock.instant().plus(Duration.ofMinutes(10)).toEpochMilli());
            payload.set("request", identity(params));
            ObjectNode result = json.createObjectNode().put("resultType", "input_required").put("requestState", sign(payload));
            result.set("inputRequests", requests); return result;
        }
        ObjectNode state = verify(McpFeatureCatalog.text(params, "requestState"));
        if (!owner.equals(state.path("owner").asText()) || !method.equals(state.path("method").asText())
                || !identity(params).equals(state.path("request")) || clock.millis() >= state.path("expires").asLong()) {
            throw new IllegalArgumentException("Expired or mismatched request state");
        }
        if (!params.path("inputResponses").isObject()) throw new IllegalArgumentException("Input responses must be an object");
        ObjectNode responses = state.path("responses").isObject() ? (ObjectNode) state.get("responses").deepCopy() : json.createObjectNode();
        params.path("inputResponses").properties().forEach(entry -> {
            if (requests.has(entry.getKey()) && !responses.has(entry.getKey())) responses.set(entry.getKey(), entry.getValue().deepCopy());
        });
        for (String id : List.copyOf(requests.properties().stream().map(java.util.Map.Entry::getKey).toList())) {
            if (!responses.has(id)) continue;
            JsonNode response = responses.get(id);
            if (!response.isObject()) throw new IllegalArgumentException("Invalid input response");
            String requested = requests.path(id).path("method").asText();
            if (requested.equals("elicitation/create")) {
                String action = response.path("action").asText();
                if (!List.of("accept", "decline", "cancel").contains(action)) throw new IllegalArgumentException("Invalid elicitation action");
                if (!action.equals("accept")) throw new InputDeclined();
                JsonNode schema = requests.path(id).path("params").path("requestedSchema");
                if (!schema.isMissingNode()) validateForm(schema, response.path("content"));
                for (JsonNode required : schema.path("required")) {
                    if (!response.path("content").has(required.asText())) throw new IllegalArgumentException("Required elicitation value is missing");
                }
            } else if (requested.equals("roots/list") && !response.path("roots").isArray()) {
                throw new IllegalArgumentException("Invalid roots response");
            } else if (requested.equals("sampling/createMessage") && !response.path("model").isTextual()) {
                throw new IllegalArgumentException("Invalid sampling response");
            }
            requests.remove(id);
        }
        if (!requests.isEmpty()) {
            state.set("responses", responses);
            ObjectNode result = json.createObjectNode().put("resultType", "input_required")
                    .put("requestState", sign(state));
            result.set("inputRequests", requests); return result;
        }
        ObjectNode arguments = params.path("arguments").isObject() ? (ObjectNode) params.get("arguments") : params.putObject("arguments");
        interaction.path("argumentBindings").properties().forEach(binding -> {
            JsonNode value = responses.at(binding.getValue().asText());
            if (value.isMissingNode()) throw new IllegalArgumentException("Missing interaction binding value");
            arguments.set(binding.getKey(), value.deepCopy());
        });
        return null;
    }

    private void validateForm(JsonNode schema, JsonNode value) {
        String type = schema.path("type").asText();
        boolean valid = switch (type) {
            case "object" -> value.isObject();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            default -> true;
        };
        if (!valid) throw new IllegalArgumentException("Elicitation value has the wrong type");
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : schema.get("enum")) if (allowed.equals(value)) found = true;
            if (!found) throw new IllegalArgumentException("Elicitation value is outside the allowed enum");
        }
        if (schema.has("oneOf")) {
            boolean found = false;
            for (JsonNode option : schema.get("oneOf")) if (option.path("const").equals(value)) found = true;
            if (!found) throw new IllegalArgumentException("Elicitation value is outside the allowed choices");
        }
        if (value.isTextual() && (value.asText().codePointCount(0, value.asText().length()) < schema.path("minLength").asInt(0)
                || value.asText().codePointCount(0, value.asText().length()) > schema.path("maxLength").asInt(Integer.MAX_VALUE))) {
            throw new IllegalArgumentException("Elicitation text length is invalid");
        }
        if (value.isNumber() && ((schema.has("minimum") && value.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0)
                || (schema.has("maximum") && value.decimalValue().compareTo(schema.get("maximum").decimalValue()) > 0))) {
            throw new IllegalArgumentException("Elicitation number is outside bounds");
        }
        if (value.isArray()) {
            if (value.size() < schema.path("minItems").asInt(0) || value.size() > schema.path("maxItems").asInt(Integer.MAX_VALUE)) {
                throw new IllegalArgumentException("Elicitation array size is invalid");
            }
            java.util.Set<JsonNode> unique = new java.util.HashSet<>();
            for (JsonNode item : value) {
                validateForm(schema.path("items"), item);
                if (!unique.add(item) && schema.path("uniqueItems").asBoolean()) throw new IllegalArgumentException("Duplicate elicitation choice");
            }
        }
        if (value.isObject()) {
            schema.path("properties").properties().forEach(property -> {
                if (value.has(property.getKey())) validateForm(property.getValue(), value.get(property.getKey()));
            });
        }
    }

    private ObjectNode identity(ObjectNode params) {
        ObjectNode identity = params.deepCopy(); identity.remove(List.of("_meta", "inputResponses", "requestState")); return identity;
    }
    private String sign(ObjectNode payload) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(encoded));
    }
    private ObjectNode verify(String token) {
        try {
            if (token.length() > 2 * 1024 * 1024) throw new IllegalArgumentException("Request state exceeds limit");
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(mac(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) {
                throw new IllegalArgumentException("Invalid request state");
            }
            JsonNode decoded = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!decoded.isObject()) throw new IllegalArgumentException("Invalid request state");
            return (ObjectNode) decoded;
        } catch (java.io.IOException | IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid request state"); }
    }
    private byte[] mac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("Cannot protect MCP request state", failure); }
    }
    public static final class MissingCapability extends RuntimeException {
        private final String capability;
        MissingCapability(String capability) { super("Missing required client capability"); this.capability = capability; }
        public String capability() { return capability; }
    }
    public static final class InputDeclined extends RuntimeException {
        InputDeclined() { super("Client declined the input request"); }
    }
}
