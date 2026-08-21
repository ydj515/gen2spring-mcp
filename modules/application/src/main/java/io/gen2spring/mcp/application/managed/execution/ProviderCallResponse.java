package io.gen2spring.mcp.application.managed.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProviderCallResponse {
    private static final int MAX_BODY = 1_048_576;
    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    public ProviderCallResponse(int status, Map<String, List<String>> headers, byte[] body) {
        if (status < 100 || status > 599 || headers == null || headers.size() > 64
                || body == null || body.length > MAX_BODY) {
            throw invalid();
        }
        Map<String, List<String>> copied = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name == null || name.isBlank() || values == null) {
                throw invalid();
            }
            List<String> valueCopy = new ArrayList<>(values);
            if (valueCopy.stream().anyMatch(value -> value == null || value.length() > 8192)) {
                throw invalid();
            }
            copied.put(name, List.copyOf(valueCopy));
        });
        this.status = status;
        this.headers = Collections.unmodifiableMap(copied);
        this.body = body.clone();
    }

    public int status() {
        return status;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public byte[] body() {
        return body.clone();
    }

    public String firstHeader(String name) {
        if (name == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Provider call response is invalid");
    }
}
