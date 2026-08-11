package io.gen2spring.mcp.web;

import com.sun.net.httpserver.Headers;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

final class RequestGuard {
    private final String host;
    private final String origin;
    private final byte[] token;

    RequestGuard(int port, String token) {
        if (port < 1 || port > 65535 || token == null || token.isBlank()) {
            throw new IllegalArgumentException("Request guard configuration is invalid");
        }
        this.host = "127.0.0.1:" + port;
        this.origin = "http://" + host;
        this.token = token.getBytes(StandardCharsets.US_ASCII);
    }

    void require(
            Headers headers,
            InetAddress remote,
            String method,
            boolean apiRequest) {
        Objects.requireNonNull(headers, "headers");
        if (remote == null || !"127.0.0.1".equals(remote.getHostAddress())) {
            throw rejected();
        }
        requireSingle(headers, "Host", host);
        if (!apiRequest) {
            return;
        }
        List<String> suppliedTokens = headers.get("X-Gen2Spring-Token");
        if (suppliedTokens == null || suppliedTokens.size() != 1
                || !MessageDigest.isEqual(
                        token,
                        suppliedTokens.getFirst().getBytes(StandardCharsets.US_ASCII))) {
            throw rejected();
        }
        if (isChanging(method)) {
            requireSingle(headers, "Origin", origin);
        }
    }

    private void requireSingle(Headers headers, String name, String expected) {
        List<String> values = headers.get(name);
        if (values == null || values.size() != 1 || !expected.equals(values.getFirst())) {
            throw rejected();
        }
    }

    private boolean isChanging(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private RequestRejectedException rejected() {
        return new RequestRejectedException();
    }

    static final class RequestRejectedException extends RuntimeException {
        int status() {
            return 403;
        }
    }
}
