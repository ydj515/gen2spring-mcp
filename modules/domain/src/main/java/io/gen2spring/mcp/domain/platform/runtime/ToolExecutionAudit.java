package io.gen2spring.mcp.domain.platform.runtime;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public record ToolExecutionAudit(
        UUID executionId,
        AccountId owner,
        RuntimeInstanceId runtimeId,
        Optional<RuntimeGrantId> grantId,
        String principal,
        String catalogChecksum,
        String toolName,
        AuditStatus status,
        Optional<String> errorCategory,
        Optional<Integer> providerStatus,
        long durationMillis,
        long requestBytes,
        long responseBytes,
        Instant startedAt,
        Optional<Instant> completedAt) {
    private static final String INVALID = "Tool execution audit is invalid";
    private static final long MAX_BYTES = 1_048_576;
    private static final long MAX_DURATION_MILLIS = 86_400_000;
    private static final Pattern PRINCIPAL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}");
    private static final Pattern TOOL = Pattern.compile("[a-z][a-z0-9_]{0,127}");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern CATEGORY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public ToolExecutionAudit {
        try {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(runtimeId, "runtimeId");
            Objects.requireNonNull(grantId, "grantId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(errorCategory, "errorCategory");
            Objects.requireNonNull(providerStatus, "providerStatus");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(completedAt, "completedAt");
            if (!PRINCIPAL.matcher(principal == null ? "" : principal).matches()
                    || !HASH.matcher(catalogChecksum == null ? "" : catalogChecksum).matches()
                    || !TOOL.matcher(toolName == null ? "" : toolName).matches()
                    || errorCategory.filter(value -> !CATEGORY.matcher(value).matches()).isPresent()
                    || providerStatus.filter(value -> value < 100 || value > 599).isPresent()
                    || durationMillis < 0 || durationMillis > MAX_DURATION_MILLIS
                    || requestBytes < 0 || requestBytes > MAX_BYTES
                    || responseBytes < 0 || responseBytes > MAX_BYTES
                    || completedAt.filter(value -> value.isBefore(startedAt)).isPresent()
                    || !validShape(status, errorCategory, providerStatus, durationMillis,
                            requestBytes, responseBytes, completedAt)) {
                throw invalid();
            }
        } catch (IllegalArgumentException failure) {
            if (INVALID.equals(failure.getMessage())) throw failure;
            throw invalid();
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public static ToolExecutionAudit start(
            UUID executionId,
            AccountId owner,
            RuntimeInstanceId runtimeId,
            Optional<RuntimeGrantId> grantId,
            String principal,
            String catalogChecksum,
            String toolName,
            Instant startedAt) {
        return new ToolExecutionAudit(
                executionId, owner, runtimeId, grantId, principal, catalogChecksum, toolName,
                AuditStatus.STARTED, Optional.empty(), Optional.empty(), 0, 0, 0,
                startedAt, Optional.empty());
    }

    public ToolExecutionAudit complete(
            AuditStatus terminalStatus,
            Optional<String> terminalCategory,
            Optional<Integer> httpStatus,
            long duration,
            long requestSize,
            long responseSize,
            Instant completed) {
        if (status != AuditStatus.STARTED || terminalStatus == AuditStatus.STARTED) throw invalid();
        return new ToolExecutionAudit(
                executionId, owner, runtimeId, grantId, principal, catalogChecksum, toolName,
                terminalStatus, terminalCategory, httpStatus, duration, requestSize, responseSize,
                startedAt, Optional.ofNullable(completed));
    }

    @Override
    public String toString() {
        return "ToolExecutionAudit[executionId=" + executionId + ", owner=" + owner
                + ", runtimeId=" + runtimeId + ", grantId=" + grantId + ", principal=redacted"
                + ", catalogChecksum=" + catalogChecksum + ", toolName=" + toolName + ", status=" + status
                + ", errorCategory=" + errorCategory + ", providerStatus=" + providerStatus
                + ", durationMillis=" + durationMillis + ", requestBytes=" + requestBytes
                + ", responseBytes=" + responseBytes + ", startedAt=" + startedAt
                + ", completedAt=" + completedAt + "]";
    }

    private static boolean validShape(
            AuditStatus status,
            Optional<String> category,
            Optional<Integer> providerStatus,
            long duration,
            long requestBytes,
            long responseBytes,
            Optional<Instant> completedAt) {
        if (status == AuditStatus.STARTED) {
            return category.isEmpty() && providerStatus.isEmpty() && completedAt.isEmpty()
                    && duration == 0 && requestBytes == 0 && responseBytes == 0;
        }
        if (completedAt.isEmpty()) return false;
        return switch (status) {
            case SUCCEEDED -> category.isEmpty()
                    && providerStatus.filter(value -> value >= 200 && value < 300).isPresent();
            case TOOL_ERROR -> category.isPresent();
            case RATE_LIMITED -> category.equals(Optional.of("RATE_LIMITED")) && providerStatus.isEmpty();
            case INTERNAL_ERROR -> category.equals(Optional.of("INTERNAL_ERROR")) && providerStatus.isEmpty();
            case STARTED -> false;
        };
    }

    public enum AuditStatus {
        STARTED,
        SUCCEEDED,
        TOOL_ERROR,
        RATE_LIMITED,
        INTERNAL_ERROR
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
}
