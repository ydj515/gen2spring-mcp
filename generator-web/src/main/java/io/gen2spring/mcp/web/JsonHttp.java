package io.gen2spring.mcp.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class JsonHttp {
    private final ObjectMapper json;

    JsonHttp(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json").copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    ObjectMapper mapper() {
        return json;
    }

    void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes;
        try {
            bytes = json.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw new IOException("JSON response could not be serialized", exception);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    ObjectNode error(WebErrorMapper.WebFailure failure) {
        ObjectNode root = json.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("code", failure.code());
        error.put("stage", failure.stage());
        error.put("message", failure.message());
        return root;
    }

    byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
