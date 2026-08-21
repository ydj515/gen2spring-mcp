package io.gen2spring.mcp.application.managed.execution;

import java.util.Objects;

public final class ManagedToolResult {
    private final byte[] json;
    private final boolean error;
    private final ErrorCategory category;
    private final Integer httpStatus;

    private ManagedToolResult(byte[] json, boolean error, ErrorCategory category, Integer httpStatus) {
        if (json == null || json.length == 0 || json.length > 1_048_576
                || error != (category != null)
                || httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("Managed Tool result is invalid");
        }
        this.json = json.clone();
        this.error = error;
        this.category = category;
        this.httpStatus = httpStatus;
    }

    public static ManagedToolResult success(byte[] json) {
        return new ManagedToolResult(json, false, null, null);
    }

    public static ManagedToolResult providerError(byte[] json, ErrorCategory category, Integer httpStatus) {
        return new ManagedToolResult(json, true, Objects.requireNonNull(category, "category"), httpStatus);
    }

    public byte[] json() {
        return json.clone();
    }

    public boolean error() {
        return error;
    }

    public ErrorCategory category() {
        return category;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    @Override
    public String toString() {
        return "ManagedToolResult[json=redacted, error=" + error + ", category=" + category
                + ", httpStatus=" + httpStatus + "]";
    }

    public enum ErrorCategory {
        PROVIDER_BUSINESS,
        UPSTREAM_CLIENT,
        UPSTREAM_SERVER,
        UPSTREAM_TIMEOUT,
        UPSTREAM_UNAVAILABLE,
        UPSTREAM_PROTOCOL,
        LOCAL_RESOURCE
    }
}
