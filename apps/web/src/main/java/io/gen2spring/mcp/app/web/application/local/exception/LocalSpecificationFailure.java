package io.gen2spring.mcp.app.web.application.local.exception;

import java.util.Objects;

public final class LocalSpecificationFailure extends RuntimeException {
    public enum Kind { WORKSPACE_CREATE, INVALID_NAME, STORE_FAILED, NOT_FOUND, CAPACITY }

    private final Kind kind;

    public LocalSpecificationFailure(Kind kind) {
        this(kind, null);
    }

    public LocalSpecificationFailure(Kind kind, Throwable cause) {
        super("Specification storage failed: " + Objects.requireNonNull(kind, "kind"), cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
