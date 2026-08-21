package io.gen2spring.mcp.domain.platform.runtime;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public record ManagedRuntimeGrant(
        RuntimeGrantId id,
        RuntimeInstanceId runtimeId,
        AccountId owner,
        String principal,
        Set<String> allowedTools,
        int requestsPerMinute,
        Instant createdAt,
        Instant expiresAt,
        Optional<Instant> revokedAt) {
    private static final String INVALID = "Managed runtime grant is invalid";
    private static final Duration MAX_LIFETIME = Duration.ofDays(30);
    private static final Pattern PRINCIPAL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}");
    private static final Pattern TOOL = Pattern.compile("[a-z][a-z0-9_]{0,127}");

    public ManagedRuntimeGrant {
        try {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(runtimeId, "runtimeId");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(allowedTools, "allowedTools");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(revokedAt, "revokedAt");
            TreeSet<String> copied = new TreeSet<>();
            for (String tool : allowedTools) {
                if (tool == null || !TOOL.matcher(tool).matches()) throw invalid();
                copied.add(tool);
            }
            if (!PRINCIPAL.matcher(principal == null ? "" : principal).matches()
                    || copied.isEmpty()
                    || requestsPerMinute < 1 || requestsPerMinute > 6000
                    || !expiresAt.isAfter(createdAt)
                    || Duration.between(createdAt, expiresAt).compareTo(MAX_LIFETIME) > 0
                    || revokedAt.filter(value -> value.isBefore(createdAt)).isPresent()) {
                throw invalid();
            }
            allowedTools = Collections.unmodifiableSet(copied);
        } catch (IllegalArgumentException failure) {
            if (INVALID.equals(failure.getMessage())) throw failure;
            throw invalid();
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public GrantState stateAt(Instant now) {
        if (now == null) throw invalid();
        if (revokedAt.isPresent()) return GrantState.REVOKED;
        return !now.isBefore(expiresAt) ? GrantState.EXPIRED : GrantState.ACTIVE;
    }

    public ManagedRuntimeGrant revokeAt(Instant now) {
        if (revokedAt.isPresent()) return this;
        if (now == null || now.isBefore(createdAt)) throw invalid();
        return new ManagedRuntimeGrant(
                id, runtimeId, owner, principal, allowedTools, requestsPerMinute,
                createdAt, expiresAt, Optional.of(now));
    }

    @Override
    public String toString() {
        return "ManagedRuntimeGrant[id=" + id + ", runtimeId=" + runtimeId + ", owner=" + owner
                + ", principal=redacted, allowedTools=" + allowedTools + ", requestsPerMinute="
                + requestsPerMinute + ", createdAt=" + createdAt + ", expiresAt=" + expiresAt
                + ", revokedAt=" + revokedAt + "]";
    }

    public enum GrantState {
        ACTIVE,
        EXPIRED,
        REVOKED
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
}
