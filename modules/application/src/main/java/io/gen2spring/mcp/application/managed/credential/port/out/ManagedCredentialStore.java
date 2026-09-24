package io.gen2spring.mcp.application.managed.credential.port.out;

import io.gen2spring.mcp.application.managed.credential.ProtectedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface ManagedCredentialStore {
    int MAX_ACTIVE_PER_OWNER = 100;

    void create(ManagedCredential credential, ProtectedCredential protectedCredential);

    boolean rotate(
            AccountId owner,
            ManagedCredentialId id,
            long expectedVersion,
            ManagedCredential credential,
            ProtectedCredential protectedCredential);

    boolean revoke(AccountId owner, ManagedCredentialId id, Instant revokedAt);

    Optional<StoredCredential> find(AccountId owner, ManagedCredentialId id);

    List<ManagedCredential> list(AccountId owner);

    long countActive(AccountId owner);

    final class ManagedCredentialQuotaExceeded extends RuntimeException {
        public ManagedCredentialQuotaExceeded() {
            super("Managed credential quota is exhausted", null, false, false);
        }
    }

    record StoredCredential(ManagedCredential credential, ProtectedCredential protectedValue) {
        public StoredCredential {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(protectedValue, "protectedValue");
            if (credential.version() != protectedValue.credentialVersion()) {
                throw new IllegalArgumentException("Stored managed credential is invalid");
            }
        }
    }
}
