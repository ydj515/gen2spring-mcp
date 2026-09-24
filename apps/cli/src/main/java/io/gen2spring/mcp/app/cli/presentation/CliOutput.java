package io.gen2spring.mcp.app.cli.presentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintWriter;
import java.util.Objects;

public final class CliOutput {
    private static final int MAX_SAFE_VALUE_LENGTH = 2_048;
    private static final String FALLBACK =
            "{\"code\":\"INTERNAL_ERROR\",\"stage\":\"CLI\",\"message\":\"The command failed safely\"}";

    private final ObjectMapper json;

    public CliOutput(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public void success(PrintWriter output, ObjectNode value) {
        try {
            output.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
            output.flush();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON serialization failed", exception);
        }
    }

    public void error(PrintWriter output, String code, String stage, String safeMessage) {
        ObjectNode error = json.createObjectNode();
        error.put("code", safe(code));
        error.put("stage", safe(stage));
        error.put("message", safe(safeMessage));
        try {
            output.println(json.writeValueAsString(error));
        } catch (JsonProcessingException exception) {
            output.println(FALLBACK);
        }
        output.flush();
    }

    private String safe(String value) {
        return value == null || value.isBlank()
                ? "Unavailable"
                : value.substring(0, Math.min(value.length(), MAX_SAFE_VALUE_LENGTH));
    }
}
