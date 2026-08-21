package io.gen2spring.mcp.domain.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolExecutionAuditTest {
    private static final Instant STARTED = Instant.parse("2026-08-21T00:00:00Z");
    private static final UUID EXECUTION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("20000000-0000-0000-0000-000000000002"));
    private static final RuntimeInstanceId RUNTIME = new RuntimeInstanceId(
            UUID.fromString("30000000-0000-0000-0000-000000000003"));
    private static final RuntimeGrantId GRANT = new RuntimeGrantId(
            UUID.fromString("40000000-0000-0000-0000-000000000004"));

    @Test
    void completesAStartedAuditOnceWithSafeBoundedMetadata() {
        ToolExecutionAudit started = started();

        ToolExecutionAudit completed = started.complete(
                ToolExecutionAudit.AuditStatus.SUCCEEDED,
                Optional.empty(), Optional.of(200), 25, 120, 512, STARTED.plusMillis(25));

        assertEquals(ToolExecutionAudit.AuditStatus.SUCCEEDED, completed.status());
        assertEquals(Optional.of(STARTED.plusMillis(25)), completed.completedAt());
        assertThrows(IllegalArgumentException.class, () -> completed.complete(
                ToolExecutionAudit.AuditStatus.INTERNAL_ERROR,
                Optional.of("INTERNAL_ERROR"), Optional.empty(), 26, 120, 0,
                STARTED.plusMillis(26)));
    }

    @Test
    void rejectsImpossibleStartedAndTerminalShapes() {
        assertInvalid(() -> new ToolExecutionAudit(
                EXECUTION, OWNER, RUNTIME, Optional.of(GRANT), "client-1", "a".repeat(64),
                "weather_get", ToolExecutionAudit.AuditStatus.STARTED,
                Optional.of("UPSTREAM_TIMEOUT"), Optional.empty(), 1, 0, 0,
                STARTED, Optional.empty()));
        assertInvalid(() -> started().complete(
                ToolExecutionAudit.AuditStatus.STARTED,
                Optional.empty(), Optional.empty(), 1, 0, 0, STARTED.plusMillis(1)));
        assertInvalid(() -> started().complete(
                ToolExecutionAudit.AuditStatus.SUCCEEDED,
                Optional.of("UPSTREAM_SERVER"), Optional.of(500), 1, 0, 0,
                STARTED.plusMillis(1)));
        assertInvalid(() -> started().complete(
                ToolExecutionAudit.AuditStatus.TOOL_ERROR,
                Optional.empty(), Optional.of(500), 1, 0, 0, STARTED.plusMillis(1)));
        assertInvalid(() -> started().complete(
                ToolExecutionAudit.AuditStatus.RATE_LIMITED,
                Optional.of("RATE_LIMITED"), Optional.of(429), 1, 0, 0,
                STARTED.plusMillis(1)));
        assertInvalid(() -> started().complete(
                ToolExecutionAudit.AuditStatus.INTERNAL_ERROR,
                Optional.of("INTERNAL_ERROR"), Optional.empty(), -1, 0, 0,
                STARTED.plusMillis(1)));
        assertInvalid(() -> started().complete(
                ToolExecutionAudit.AuditStatus.INTERNAL_ERROR,
                Optional.of("INTERNAL_ERROR"), Optional.empty(), 1, 1_048_577, 0,
                STARTED.plusMillis(1)));
    }

    @Test
    void excludesPrincipalAndPotentialPayloadDataFromDiagnosticText() {
        ToolExecutionAudit audit = started();

        assertFalse(audit.toString().contains("private-client"));
        assertFalse(audit.toString().contains("Authorization"));
    }

    private ToolExecutionAudit started() {
        return ToolExecutionAudit.start(
                EXECUTION, OWNER, RUNTIME, Optional.of(GRANT), "private-client",
                "a".repeat(64), "weather_get", STARTED);
    }

    private void assertInvalid(Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals("Tool execution audit is invalid", failure.getMessage());
    }
}
