package io.gen2spring.mcp.application.managed.audit.result;

import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AuditPage(List<ToolExecutionAudit> items, Optional<AuditCursor> nextCursor) {
    public AuditPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Runtime audit page is invalid");
        }
        Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
