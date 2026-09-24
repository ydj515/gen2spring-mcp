package io.gen2spring.mcp.application.managed.runtime.port.out;

import io.gen2spring.mcp.application.managed.runtime.result.RuntimeCatalogTransition;
import io.gen2spring.mcp.application.managed.runtime.result.TransitionKind;
import io.gen2spring.mcp.application.managed.runtime.result.TransitionPage;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

public interface RuntimeCatalogTransitionStore {
    TransitionResult apply(MigrationCommand command, TransitionKind kind);

    TransitionPage history(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            int limit,
            Optional<Long> before);

    Optional<RuntimeCatalogTransition> findRollbackCandidate(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            UUID currentCatalogId);

    record MigrationCommand(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            UUID expectedCurrentCatalogId,
            String expectedCurrentChecksum,
            UUID targetCatalogId,
            String targetChecksum,
            String diffChecksum,
            Set<String> targetTools,
            Instant observedAt) {
        private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
        private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,127}");

        public MigrationCommand {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(runtimeId, "runtimeId");
            Objects.requireNonNull(expectedCurrentCatalogId, "expectedCurrentCatalogId");
            Objects.requireNonNull(targetCatalogId, "targetCatalogId");
            Objects.requireNonNull(observedAt, "observedAt");
            targetTools = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(targetTools, "targetTools")));
            if (expectedCurrentCatalogId.equals(targetCatalogId)
                    || !checksum(expectedCurrentChecksum)
                    || !checksum(targetChecksum)
                    || !checksum(diffChecksum)
                    || targetTools.isEmpty()
                    || targetTools.size() > 1_000
                    || targetTools.stream().anyMatch(name -> name == null || !TOOL_NAME.matcher(name).matches())) {
                throw new IllegalArgumentException("Runtime Catalog migration command is invalid");
            }
        }

        private static boolean checksum(String value) {
            return value != null && SHA_256.matcher(value).matches();
        }
    }

    record TransitionResult(
            TransitionOutcome outcome,
            Optional<ManagedRuntimeInstance> instance,
            Optional<RuntimeCatalogTransition> transition) {
        public TransitionResult {
            Objects.requireNonNull(outcome, "outcome");
            instance = Objects.requireNonNull(instance, "instance");
            transition = Objects.requireNonNull(transition, "transition");
            boolean applied = outcome == TransitionOutcome.APPLIED;
            if (instance.isPresent() != applied || transition.isPresent() != applied) {
                throw new IllegalArgumentException("Runtime Catalog transition result is invalid");
            }
        }
    }

    enum TransitionOutcome {
        APPLIED,
        CONFLICT,
        BLOCKED
    }

}
