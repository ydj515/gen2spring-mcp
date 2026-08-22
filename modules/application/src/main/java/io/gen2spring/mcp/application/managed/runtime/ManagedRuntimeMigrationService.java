package io.gen2spring.mcp.application.managed.runtime;

import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.CREDENTIAL_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.Compatibility.BREAKING;
import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionKind.MIGRATION;
import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionKind.ROLLBACK;
import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionOutcome.APPLIED;

import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiffService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogDetails;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore.StoredRuntime;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.MigrationCommand;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.RuntimeCatalogTransition;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionKind;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionPage;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionResult;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance.RuntimeState;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ManagedRuntimeMigrationService {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    private final ToolCatalogStore catalogs;
    private final ManagedRuntimeStore runtimes;
    private final RuntimeCatalogTransitionStore transitions;
    private final CatalogDiffService diffs;
    private final Clock clock;

    public ManagedRuntimeMigrationService(
            ToolCatalogStore catalogs,
            ManagedRuntimeStore runtimes,
            RuntimeCatalogTransitionStore transitions,
            Clock clock) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.diffs = new CatalogDiffService(catalogs);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MigrationResult migrate(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            UUID expectedCurrentCatalogId,
            UUID targetCatalogId,
            String targetChecksum) {
        validateMigration(owner, runtimeId, expectedCurrentCatalogId, targetCatalogId, targetChecksum);
        ManagedRuntimeInstance runtime = requireActive(owner, runtimeId);
        requireExpected(runtime, expectedCurrentCatalogId);
        if (runtime.catalogId().equals(targetCatalogId)) {
            throw invalid();
        }
        CatalogDetails target = requireCatalog(owner, targetCatalogId);
        if (!target.summary().metadataChecksum().equals(targetChecksum)) {
            throw conflict();
        }
        CatalogDiff diff = compare(owner, runtime.catalogId(), targetCatalogId);
        if (!diff.source().metadataChecksum().equals(runtime.catalogChecksum())) {
            throw conflict();
        }
        rejectForwardIncompatibility(diff);
        return apply(runtime, target, diff, MIGRATION);
    }

    public TransitionPage history(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            int limit,
            Optional<Long> before) {
        if (owner == null || runtimeId == null || before == null
                || limit < 1 || limit > 100 || before.filter(value -> value < 1).isPresent()) {
            throw invalid();
        }
        requireRuntime(owner, runtimeId);
        try {
            return Objects.requireNonNull(transitions.history(owner, runtimeId, limit, before));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeMigrationNotFound | RuntimeMigrationRequestInvalid known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    public MigrationResult rollback(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            UUID expectedCurrentCatalogId) {
        if (owner == null || runtimeId == null || expectedCurrentCatalogId == null) {
            throw invalid();
        }
        ManagedRuntimeInstance runtime = requireActive(owner, runtimeId);
        requireExpected(runtime, expectedCurrentCatalogId);
        RuntimeCatalogTransition candidate = rollbackCandidate(owner, runtimeId, runtime.catalogId())
                .orElseThrow(ManagedRuntimeMigrationService::notFound);
        if (!candidate.targetCatalogId().equals(runtime.catalogId())
                || !candidate.targetChecksum().equals(runtime.catalogChecksum())) {
            throw conflict();
        }
        CatalogDetails target = requireCatalog(owner, candidate.sourceCatalogId());
        if (!target.summary().metadataChecksum().equals(candidate.sourceChecksum())) {
            throw conflict();
        }
        CatalogDiff diff = compare(owner, runtime.catalogId(), target.summary().catalogId());
        if (!diff.source().metadataChecksum().equals(runtime.catalogChecksum())) {
            throw conflict();
        }
        if (hasCredentialChange(diff)) {
            throw blocked();
        }
        return apply(runtime, target, diff, ROLLBACK);
    }

    private MigrationResult apply(
            ManagedRuntimeInstance runtime,
            CatalogDetails target,
            CatalogDiff diff,
            TransitionKind kind) {
        Set<String> targetTools = target.metadata().document().tools().stream()
                .map(tool -> tool.name())
                .collect(Collectors.toUnmodifiableSet());
        MigrationCommand command = new MigrationCommand(
                runtime.owner(), runtime.id(), runtime.catalogId(), runtime.catalogChecksum(),
                target.summary().catalogId(), target.summary().metadataChecksum(), diff.checksum(),
                targetTools, clock.instant());
        TransitionResult result;
        try {
            result = Objects.requireNonNull(transitions.apply(command, kind));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        if (result.outcome() == RuntimeCatalogTransitionStore.TransitionOutcome.CONFLICT) {
            throw conflict();
        }
        if (result.outcome() == RuntimeCatalogTransitionStore.TransitionOutcome.BLOCKED) {
            throw blocked();
        }
        if (result.outcome() != APPLIED || result.instance().isEmpty() || result.transition().isEmpty()) {
            throw unavailable();
        }
        return new MigrationResult(result.instance().orElseThrow(), result.transition().orElseThrow(), diff.checksum());
    }

    private ManagedRuntimeInstance requireActive(AccountId owner, RuntimeInstanceId runtimeId) {
        ManagedRuntimeInstance instance = requireRuntime(owner, runtimeId);
        if (instance.stateAt(clock.instant()) != RuntimeState.ACTIVE) {
            throw blocked();
        }
        return instance;
    }

    private ManagedRuntimeInstance requireRuntime(AccountId owner, RuntimeInstanceId runtimeId) {
        if (owner == null || runtimeId == null) {
            throw invalid();
        }
        try {
            StoredRuntime stored = Objects.requireNonNull(runtimes.find(runtimeId)).orElseThrow(
                    ManagedRuntimeMigrationService::notFound);
            if (!stored.instance().owner().equals(owner)) {
                throw notFound();
            }
            return stored.instance();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeMigrationNotFound known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private CatalogDetails requireCatalog(AccountId owner, UUID catalogId) {
        try {
            return Objects.requireNonNull(catalogs.find(owner, catalogId))
                    .orElseThrow(ManagedRuntimeMigrationService::notFound);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeMigrationNotFound known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private CatalogDiff compare(AccountId owner, UUID sourceCatalogId, UUID targetCatalogId) {
        try {
            return diffs.compare(owner, sourceCatalogId, targetCatalogId);
        } catch (CatalogDiffService.CatalogDiffNotFound failure) {
            throw notFound();
        } catch (CatalogDiffService.CatalogDiffQueryInvalid failure) {
            throw invalid();
        } catch (CatalogDiffService.CatalogDiffUnavailable failure) {
            throw unavailable();
        }
    }

    private Optional<RuntimeCatalogTransition> rollbackCandidate(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            UUID currentCatalogId) {
        try {
            return Objects.requireNonNull(
                    transitions.findRollbackCandidate(owner, runtimeId, currentCatalogId));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private void rejectForwardIncompatibility(CatalogDiff diff) {
        if (hasCredentialChange(diff)) {
            throw blocked();
        }
        if (diff.compatibility() == BREAKING) {
            throw breaking();
        }
    }

    private boolean hasCredentialChange(CatalogDiff diff) {
        return diff.changes().stream().anyMatch(change -> change.kind() == CREDENTIAL_CHANGED);
    }

    private void validateMigration(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            UUID expectedCurrentCatalogId,
            UUID targetCatalogId,
            String targetChecksum) {
        if (owner == null || runtimeId == null || expectedCurrentCatalogId == null
                || targetCatalogId == null
                || targetChecksum == null || !SHA_256.matcher(targetChecksum).matches()) {
            throw invalid();
        }
    }

    private void requireExpected(ManagedRuntimeInstance runtime, UUID expectedCurrentCatalogId) {
        if (!runtime.catalogId().equals(expectedCurrentCatalogId)) {
            throw conflict();
        }
    }

    public record MigrationResult(
            ManagedRuntimeInstance instance,
            RuntimeCatalogTransition transition,
            String diffChecksum) {
        public MigrationResult {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(transition, "transition");
            if (diffChecksum == null || !SHA_256.matcher(diffChecksum).matches()
                    || !instance.id().equals(transition.runtimeId())
                    || !instance.catalogId().equals(transition.targetCatalogId())
                    || !instance.catalogChecksum().equals(transition.targetChecksum())
                    || !diffChecksum.equals(transition.diffChecksum())) {
                throw new IllegalArgumentException("Runtime Catalog migration result is invalid");
            }
        }
    }

    private static RuntimeMigrationRequestInvalid invalid() {
        return new RuntimeMigrationRequestInvalid();
    }

    private static RuntimeMigrationNotFound notFound() {
        return new RuntimeMigrationNotFound();
    }

    private static CatalogVersionConflict conflict() {
        return new CatalogVersionConflict();
    }

    private static CatalogMigrationBreaking breaking() {
        return new CatalogMigrationBreaking();
    }

    private static CatalogMigrationBlocked blocked() {
        return new CatalogMigrationBlocked();
    }

    private static RuntimeMigrationUnavailable unavailable() {
        return new RuntimeMigrationUnavailable();
    }

    public static final class RuntimeMigrationRequestInvalid extends RuntimeException {
        public RuntimeMigrationRequestInvalid() {
            super("Runtime migration request is invalid", null, false, false);
        }
    }

    public static final class RuntimeMigrationNotFound extends RuntimeException {
        public RuntimeMigrationNotFound() {
            super("Runtime migration resource was not found", null, false, false);
        }
    }

    public static final class CatalogVersionConflict extends RuntimeException {
        public CatalogVersionConflict() {
            super("Catalog version conflict", null, false, false);
        }
    }

    public static final class CatalogMigrationBreaking extends RuntimeException {
        public CatalogMigrationBreaking() {
            super("Catalog migration is breaking", null, false, false);
        }
    }

    public static final class CatalogMigrationBlocked extends RuntimeException {
        public CatalogMigrationBlocked() {
            super("Catalog migration is blocked", null, false, false);
        }
    }

    public static final class RuntimeMigrationUnavailable extends RuntimeException {
        public RuntimeMigrationUnavailable() {
            super("Runtime migration is unavailable", null, false, false);
        }
    }
}
