package io.gen2spring.mcp.domain.platform.runtime;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public record ManagedRuntimeInstance(
        RuntimeInstanceId id,
        AccountId owner,
        UUID catalogId,
        String catalogChecksum,
        Optional<ProviderTarget> providerBaseUrl,
        Instant createdAt,
        Instant expiresAt,
        Optional<Instant> revokedAt) {
    private static final String INVALID = "Managed runtime instance is invalid";
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
    private static final Duration MAX_LIFETIME = Duration.ofDays(30);

    public ManagedRuntimeInstance {
        try {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(catalogId, "catalogId");
            Objects.requireNonNull(providerBaseUrl, "providerBaseUrl");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(revokedAt, "revokedAt");
            if (catalogChecksum == null || !SHA_256.matcher(catalogChecksum).matches()
                    || !expiresAt.isAfter(createdAt)
                    || Duration.between(createdAt, expiresAt).compareTo(MAX_LIFETIME) > 0
                    || revokedAt.filter(value -> value.isBefore(createdAt)).isPresent()) {
                throw invalid();
            }
        } catch (IllegalArgumentException failure) {
            if (INVALID.equals(failure.getMessage())) {
                throw failure;
            }
            throw invalid();
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public RuntimeState stateAt(Instant now) {
        if (now == null) {
            throw invalid();
        }
        if (revokedAt.isPresent()) {
            return RuntimeState.REVOKED;
        }
        return !now.isBefore(expiresAt) ? RuntimeState.EXPIRED : RuntimeState.ACTIVE;
    }

    public ManagedRuntimeInstance revokeAt(Instant now) {
        if (revokedAt.isPresent()) {
            return this;
        }
        if (now == null || now.isBefore(createdAt)) {
            throw invalid();
        }
        return new ManagedRuntimeInstance(
                id, owner, catalogId, catalogChecksum, providerBaseUrl, createdAt, expiresAt, Optional.of(now));
    }

    @Override
    public String toString() {
        return "ManagedRuntimeInstance[id=" + id + ", owner=" + owner + ", catalogId=" + catalogId
                + ", catalogChecksum=" + catalogChecksum + ", providerBaseUrl=redacted, createdAt=" + createdAt
                + ", expiresAt=" + expiresAt + ", revokedAt=" + revokedAt + "]";
    }

    public enum RuntimeState {
        ACTIVE,
        EXPIRED,
        REVOKED
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
}
