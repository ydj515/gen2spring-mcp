package io.gen2spring.mcp.application.managed.runtime;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public interface ManagedRuntimeStore {
    void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest);

    Optional<StoredRuntime> find(RuntimeInstanceId id);

    boolean revoke(AccountId owner, RuntimeInstanceId id, Instant revokedAt);

    record StoredRuntime(ManagedRuntimeInstance instance, RuntimeTokenDigest tokenDigest) {
        public StoredRuntime {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(tokenDigest, "tokenDigest");
        }
    }
}
