package io.gen2spring.mcp.application.managed.audit.result;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditCursor(Instant startedAt, UUID executionId) {
    public AuditCursor {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(executionId, "executionId");
    }
}
