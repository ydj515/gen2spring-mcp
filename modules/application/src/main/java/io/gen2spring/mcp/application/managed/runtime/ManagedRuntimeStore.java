package io.gen2spring.mcp.application.managed.runtime;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;

public interface ManagedRuntimeStore {
    void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest);

    default void create(
            ManagedRuntimeInstance instance,
            RuntimeTokenDigest digest,
            Map<String, ManagedCredentialId> credentialBindings) {
        if (credentialBindings == null || !credentialBindings.isEmpty()) {
            throw new IllegalArgumentException("Managed runtime storage request is invalid");
        }
        create(instance, digest);
    }

    Optional<StoredRuntime> find(RuntimeInstanceId id);

    boolean revoke(AccountId owner, RuntimeInstanceId id, Instant revokedAt);

    record StoredRuntime(
            ManagedRuntimeInstance instance,
            RuntimeTokenDigest tokenDigest,
            Map<String, ManagedCredentialId> credentialBindings) {
        public StoredRuntime {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(tokenDigest, "tokenDigest");
            Objects.requireNonNull(credentialBindings, "credentialBindings");
            credentialBindings = java.util.Collections.unmodifiableMap(new TreeMap<>(credentialBindings));
        }

        public StoredRuntime(ManagedRuntimeInstance instance, RuntimeTokenDigest tokenDigest) {
            this(instance, tokenDigest, Map.of());
        }
    }
}
