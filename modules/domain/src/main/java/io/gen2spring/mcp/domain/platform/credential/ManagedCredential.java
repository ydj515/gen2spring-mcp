package io.gen2spring.mcp.domain.platform.credential;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ManagedCredential(
        ManagedCredentialId id,
        AccountId owner,
        String label,
        ManagedCredentialKind kind,
        long version,
        Instant createdAt,
        Instant rotatedAt,
        Optional<Instant> revokedAt) {
    private static final String INVALID = "Managed credential is invalid";
    private static final int MAX_LABEL_BYTES = 128;

    public ManagedCredential {
        try {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(rotatedAt, "rotatedAt");
            Objects.requireNonNull(revokedAt, "revokedAt");
            if (!safeLabel(label)
                    || version < 1
                    || rotatedAt.isBefore(createdAt)
                    || revokedAt.filter(value -> value.isBefore(createdAt)).isPresent()) {
                throw invalid();
            }
        } catch (IllegalArgumentException failure) {
            if (INVALID.equals(failure.getMessage())) throw failure;
            throw invalid();
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public CredentialState state() {
        return revokedAt.isPresent() ? CredentialState.REVOKED : CredentialState.ACTIVE;
    }

    public ManagedCredential revokeAt(Instant now) {
        if (revokedAt.isPresent()) return this;
        if (now == null || now.isBefore(createdAt)) throw invalid();
        return new ManagedCredential(id, owner, label, kind, version, createdAt, rotatedAt, Optional.of(now));
    }

    @Override
    public String toString() {
        return "ManagedCredential[id=" + id + ", owner=" + owner + ", label=redacted, kind=" + kind
                + ", version=" + version + ", createdAt=" + createdAt + ", rotatedAt=" + rotatedAt
                + ", revokedAt=" + revokedAt + "]";
    }

    private static boolean safeLabel(String value) {
        return value != null
                && !value.isBlank()
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_LABEL_BYTES
                && value.chars().noneMatch(Character::isISOControl);
    }

    public enum CredentialState {
        ACTIVE,
        REVOKED
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
}
