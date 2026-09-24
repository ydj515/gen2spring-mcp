package io.gen2spring.mcp.app.web.application.local.exception;

import java.util.Objects;

public final class LocalJobFailure extends RuntimeException {
    public enum Kind { CAPACITY, NOT_FOUND, INVALID_STATE, ARTIFACT_UNAVAILABLE, WORKSPACE_CREATE }

    private final Kind kind;

    public LocalJobFailure(Kind kind) {
        this(kind, null);
    }

    public LocalJobFailure(Kind kind, Throwable cause) {
        super("Local job failed: " + Objects.requireNonNull(kind, "kind"), cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
