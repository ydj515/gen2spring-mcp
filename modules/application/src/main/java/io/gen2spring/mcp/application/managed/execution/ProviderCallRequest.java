package io.gen2spring.mcp.application.managed.execution;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ProviderCallRequest {
    private static final int MAX_URI = 8192;
    private static final int MAX_HEADERS = 64;
    private static final int MAX_HEADER_VALUE = 8192;
    private static final int MAX_BODY = 1_048_576;

    private final HttpMethod method;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    public ProviderCallRequest(HttpMethod method, URI uri, Map<String, List<String>> headers, byte[] body) {
        this.method = Objects.requireNonNull(method, "method");
        this.uri = requireUri(uri);
        this.headers = copyHeaders(headers);
        if (body == null || body.length > MAX_BODY) {
            throw invalid();
        }
        this.body = body.clone();
    }

    public HttpMethod method() {
        return method;
    }

    public URI uri() {
        return uri;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public byte[] body() {
        return body.clone();
    }

    private URI requireUri(URI value) {
        if (value == null || !value.isAbsolute() || value.getHost() == null
                || value.toASCIIString().length() > MAX_URI || value.getRawUserInfo() != null
                || value.getRawFragment() != null) {
            throw invalid();
        }
        return value;
    }

    private Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
        if (source == null || source.size() > MAX_HEADERS) {
            throw invalid();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            if (name == null || name.isBlank() || name.length() > 128
                    || name.chars().anyMatch(character -> Character.isISOControl(character) || character == ':')
                    || values == null || values.isEmpty()) {
                throw invalid();
            }
            List<String> copied = new ArrayList<>(values.size());
            for (String value : values) {
                if (value == null || value.length() > MAX_HEADER_VALUE || value.chars().anyMatch(Character::isISOControl)) {
                    throw invalid();
                }
                copied.add(value);
            }
            copy.put(name, List.copyOf(copied));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Provider call request is invalid");
    }
}
