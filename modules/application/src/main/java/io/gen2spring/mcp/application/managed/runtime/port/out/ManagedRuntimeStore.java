package io.gen2spring.mcp.application.managed.runtime.port.out;

import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public interface ManagedRuntimeStore {
    default void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest) {
        create(instance, digest, Map.of());
    }

    void create(
            ManagedRuntimeInstance instance,
            RuntimeTokenDigest digest,
            Map<String, ManagedCredentialId> credentialBindings);

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
