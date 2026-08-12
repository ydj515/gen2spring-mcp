package io.gen2spring.mcp.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class WebErrorResponseWriter {
    private final ObjectMapper json;

    WebErrorResponseWriter(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    ObjectNode body(WebErrorMapper.WebFailure failure) {
        ObjectNode error = json.createObjectNode();
        error.put("code", failure.code());
        error.put("stage", failure.stage());
        error.put("message", failure.message());
        ObjectNode body = json.createObjectNode();
        body.set("error", error);
        return body;
    }

    void write(HttpServletResponse response, WebErrorMapper.WebFailure failure) throws IOException {
        response.setStatus(failure.status());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), body(failure));
    }
}
