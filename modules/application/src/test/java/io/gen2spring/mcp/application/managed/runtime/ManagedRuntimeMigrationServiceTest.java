package io.gen2spring.mcp.application.managed.runtime;

import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionKind.MIGRATION;
import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionOutcome.APPLIED;
import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionOutcome.BLOCKED;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogDetails;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogSummary;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogVersion;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.MigrationCommand;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.RuntimeCatalogTransition;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionKind;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionOutcome;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionPage;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionResult;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedRuntimeMigrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9"));
    private static final AccountId OTHER = new AccountId(
            UUID.fromString("5d0c27ac-1c18-487d-b922-28653572fc4a"));
    private static final UUID SOURCE = UUID.fromString("6d65bd83-547b-4965-82f0-eb31af0dcd21");
    private static final UUID TARGET = UUID.fromString("8f5a48fd-f34b-4ba5-b749-eb79008370d5");
    private static final RuntimeInstanceId RUNTIME = new RuntimeInstanceId(
            UUID.fromString("aec70688-4d4c-4baf-bf87-76054bc95e6b"));

    private CatalogStore catalogs;
    private RuntimeStore runtimes;
    private TransitionStore transitions;
    private ManagedRuntimeMigrationService service;

    @BeforeEach
    void setUp() {
        catalogs = new CatalogStore();
        catalogs.put(details(SOURCE, new CatalogVersion(SOURCE, 1, Optional.empty()), List.of(tool("alpha"))));
        catalogs.put(details(TARGET, new CatalogVersion(SOURCE, 2, Optional.of(SOURCE)),
                List.of(tool("alpha"), tool("beta"))));
        runtimes = new RuntimeStore(instance(SOURCE, catalogs.get(SOURCE).summary().metadataChecksum(), Optional.empty()));
        transitions = new TransitionStore(runtimes);
        service = new ManagedRuntimeMigrationService(
                catalogs, runtimes, transitions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void migratesOneActiveRuntimeToACompatibleRevisionWithoutReplacingItsIdentity() {
        var result = service.migrate(
                OWNER, RUNTIME, SOURCE, TARGET, catalogs.get(TARGET).summary().metadataChecksum());

        assertEquals(RUNTIME, result.instance().id());
        assertEquals(TARGET, result.instance().catalogId());
        assertEquals(MIGRATION, result.transition().kind());
        assertEquals(result.diffChecksum(), result.transition().diffChecksum());
        assertEquals(Set.of("alpha", "beta"), transitions.command.targetTools());
        assertEquals(NOW, transitions.command.observedAt());
    }

    @Test
    void rejectsBreakingTargetsChecksumDriftAndHiddenCatalogsBeforeTheStore() {
        catalogs.put(details(TARGET, new CatalogVersion(SOURCE, 2, Optional.of(SOURCE)), List.of(tool("beta"))));
        assertThrows(ManagedRuntimeMigrationService.CatalogMigrationBreaking.class,
                () -> service.migrate(
                        OWNER, RUNTIME, SOURCE, TARGET, catalogs.get(TARGET).summary().metadataChecksum()));

        catalogs.put(details(TARGET, new CatalogVersion(SOURCE, 2, Optional.of(SOURCE)),
                List.of(tool("alpha"), tool("beta"))));
        assertThrows(ManagedRuntimeMigrationService.CatalogVersionConflict.class,
                () -> service.migrate(OWNER, RUNTIME, SOURCE, TARGET, "f".repeat(64)));

        CatalogDetails target = catalogs.get(TARGET);
        catalogs.put(new CatalogDetails(
                summary(TARGET, new CatalogVersion(TARGET, 1, Optional.empty()), target.metadata()),
                target.specificationChecksum(), target.metadata()));
        assertThrows(ManagedRuntimeMigrationService.RuntimeMigrationNotFound.class,
                () -> service.migrate(
                        OWNER, RUNTIME, SOURCE, TARGET, target.summary().metadataChecksum()));
        assertThrows(ManagedRuntimeMigrationService.RuntimeMigrationNotFound.class,
                () -> service.migrate(
                        OTHER, RUNTIME, SOURCE, TARGET, target.summary().metadataChecksum()));
        assertEquals(0, transitions.calls);
    }

    @Test
    void rejectsInactiveStaleAndCredentialIncompatibleMigrations() {
        runtimes.stored = instance(SOURCE, catalogs.get(SOURCE).summary().metadataChecksum(), Optional.of(NOW));
        assertThrows(ManagedRuntimeMigrationService.CatalogMigrationBlocked.class,
                () -> service.migrate(
                        OWNER, RUNTIME, SOURCE, TARGET, catalogs.get(TARGET).summary().metadataChecksum()));

        runtimes.stored = instance(SOURCE, catalogs.get(SOURCE).summary().metadataChecksum(), Optional.empty());
        assertThrows(ManagedRuntimeMigrationService.CatalogVersionConflict.class,
                () -> service.migrate(
                        OWNER, RUNTIME, TARGET, TARGET, catalogs.get(TARGET).summary().metadataChecksum()));

        runtimes.stored = instance(SOURCE, "e".repeat(64), Optional.empty());
        assertThrows(ManagedRuntimeMigrationService.CatalogVersionConflict.class,
                () -> service.migrate(
                        OWNER, RUNTIME, SOURCE, TARGET, catalogs.get(TARGET).summary().metadataChecksum()));

        RuntimeTool credentialTarget = withCredentials(
                tool("alpha"), List.of(new RuntimeCredential("service_key", HEADER, "Authorization", true)));
        runtimes.stored = instance(SOURCE, catalogs.get(SOURCE).summary().metadataChecksum(), Optional.empty());
        catalogs.put(details(TARGET, new CatalogVersion(SOURCE, 2, Optional.of(SOURCE)),
                List.of(credentialTarget, tool("beta"))));
        assertThrows(ManagedRuntimeMigrationService.CatalogMigrationBlocked.class,
                () -> service.migrate(
                        OWNER, RUNTIME, SOURCE, TARGET, catalogs.get(TARGET).summary().metadataChecksum()));
    }

    @Test
    void mapsAtomicStoreConflictsAndGrantBlocksWithoutReturningPartialResults() {
        transitions.outcome = RuntimeCatalogTransitionStore.TransitionOutcome.CONFLICT;
        assertThrows(ManagedRuntimeMigrationService.CatalogVersionConflict.class,
                () -> service.migrate(
                        OWNER, RUNTIME, SOURCE, TARGET, catalogs.get(TARGET).summary().metadataChecksum()));

        transitions.outcome = BLOCKED;
        assertThrows(ManagedRuntimeMigrationService.CatalogMigrationBlocked.class,
                () -> service.migrate(
                        OWNER, RUNTIME, SOURCE, TARGET, catalogs.get(TARGET).summary().metadataChecksum()));
    }

    @Test
    void returnsBoundedHistoryAndRollsBackTheLatestNonRevertedMigration() {
        RuntimeCatalogTransition forward = transition(1, SOURCE, TARGET, MIGRATION);
        transitions.page = new TransitionPage(List.of(forward), Optional.empty());
        assertEquals(List.of(forward), service.history(OWNER, RUNTIME, 50, Optional.empty()).items());
        assertThrows(ManagedRuntimeMigrationService.RuntimeMigrationRequestInvalid.class,
                () -> service.history(OWNER, RUNTIME, 0, Optional.empty()));

        runtimes.stored = instance(TARGET, catalogs.get(TARGET).summary().metadataChecksum(), Optional.empty());
        transitions.rollbackCandidate = Optional.of(forward);
        var rollback = service.rollback(OWNER, RUNTIME, TARGET);
        assertEquals(SOURCE, rollback.instance().catalogId());
        assertEquals(TransitionKind.ROLLBACK, rollback.transition().kind());
        assertEquals(Set.of("alpha"), transitions.command.targetTools());
    }

    @Test
    void blocksRollbackWhenTheAtomicGrantSubsetCheckFails() {
        RuntimeCatalogTransition forward = transition(1, SOURCE, TARGET, MIGRATION);
        runtimes.stored = instance(TARGET, catalogs.get(TARGET).summary().metadataChecksum(), Optional.empty());
        transitions.rollbackCandidate = Optional.of(forward);
        transitions.outcome = BLOCKED;

        assertThrows(ManagedRuntimeMigrationService.CatalogMigrationBlocked.class,
                () -> service.rollback(OWNER, RUNTIME, TARGET));
    }

    private ManagedRuntimeInstance instance(UUID catalogId, String checksum, Optional<Instant> revokedAt) {
        return new ManagedRuntimeInstance(
                RUNTIME, OWNER, catalogId, checksum, Optional.empty(),
                NOW.minusSeconds(60), NOW.plusSeconds(3600), revokedAt);
    }

    private RuntimeCatalogTransition transition(
            long sequence,
            UUID source,
            UUID target,
            TransitionKind kind) {
        return new RuntimeCatalogTransition(
                sequence, RUNTIME, source, catalogs.get(source).summary().metadataChecksum(),
                target, catalogs.get(target).summary().metadataChecksum(), "d".repeat(64), kind, NOW);
    }

    private CatalogDetails details(UUID id, CatalogVersion version, List<RuntimeTool> tools) {
        RuntimeMetadataArtifact metadata = new CanonicalRuntimeMetadataCodec().encode(
                new RuntimeMetadataDocument(RuntimeMetadataDocument.VERSION, "a".repeat(64), tools));
        return new CatalogDetails(summary(id, version, metadata), "a".repeat(64), metadata);
    }

    private CatalogSummary summary(UUID id, CatalogVersion version, RuntimeMetadataArtifact metadata) {
        return new CatalogSummary(
                id, new JobId(UUID.nameUUIDFromBytes(id.toString().getBytes())),
                RuntimeMetadataDocument.VERSION, metadata.checksum(), metadata.document().tools().size(), NOW, version);
    }

    private RuntimeTool tool(String name) {
        return new RuntimeTool(
                name + "Operation", name, "Description",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(GET, "https://api.example.test", "/items", List.of(), false, false),
                null, null, null, List.of());
    }

    private RuntimeTool withCredentials(RuntimeTool source, List<RuntimeCredential> credentials) {
        return new RuntimeTool(
                source.operationId(), source.name(), source.description(), source.inputSchema(),
                source.outputKind(), source.outputSchema(), source.http(),
                source.responseNormalization(), source.retry(), source.pagination(), credentials);
    }

    private static final class CatalogStore implements ToolCatalogStore {
        private final Map<UUID, CatalogDetails> catalogs = new LinkedHashMap<>();

        void put(CatalogDetails details) {
            catalogs.put(details.summary().catalogId(), details);
        }

        CatalogDetails get(UUID id) {
            return catalogs.get(id);
        }

        @Override
        public List<CatalogSummary> list(AccountId owner, int fetchLimit, Optional<CatalogCursor> cursor) {
            return new ArrayList<>();
        }

        @Override
        public Optional<CatalogDetails> find(AccountId owner, UUID catalogId) {
            return OWNER.equals(owner) ? Optional.ofNullable(catalogs.get(catalogId)) : Optional.empty();
        }

        @Override
        public Optional<ToolDetails> findTool(AccountId owner, UUID catalogId, String toolName) {
            return Optional.empty();
        }
    }

    private static final class RuntimeStore implements ManagedRuntimeStore {
        private ManagedRuntimeInstance stored;

        RuntimeStore(ManagedRuntimeInstance stored) {
            this.stored = stored;
        }

        @Override
        public void create(
                ManagedRuntimeInstance instance,
                RuntimeTokenDigest digest,
                Map<String, io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId> credentialBindings) {}

        @Override
        public Optional<StoredRuntime> find(RuntimeInstanceId id) {
            return RUNTIME.equals(id)
                    ? Optional.of(new StoredRuntime(stored, new RuntimeTokenDigest(new byte[32])))
                    : Optional.empty();
        }

        @Override
        public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant revokedAt) {
            return false;
        }
    }

    private static final class TransitionStore implements RuntimeCatalogTransitionStore {
        private final RuntimeStore runtimes;
        private TransitionOutcome outcome = APPLIED;
        private MigrationCommand command;
        private TransitionPage page = new TransitionPage(List.of(), Optional.empty());
        private Optional<RuntimeCatalogTransition> rollbackCandidate = Optional.empty();
        private int calls;

        TransitionStore(RuntimeStore runtimes) {
            this.runtimes = runtimes;
        }

        @Override
        public TransitionResult apply(MigrationCommand command, TransitionKind kind) {
            calls++;
            this.command = command;
            if (outcome != APPLIED) {
                return new TransitionResult(outcome, Optional.empty(), Optional.empty());
            }
            ManagedRuntimeInstance current = runtimes.stored;
            ManagedRuntimeInstance migrated = new ManagedRuntimeInstance(
                    current.id(), current.owner(), command.targetCatalogId(), command.targetChecksum(),
                    current.providerBaseUrl(), current.createdAt(), current.expiresAt(), current.revokedAt());
            RuntimeCatalogTransition transition = new RuntimeCatalogTransition(
                    1, current.id(), current.catalogId(), current.catalogChecksum(),
                    command.targetCatalogId(), command.targetChecksum(), command.diffChecksum(), kind,
                    command.observedAt());
            runtimes.stored = migrated;
            return new TransitionResult(APPLIED, Optional.of(migrated), Optional.of(transition));
        }

        @Override
        public TransitionPage history(
                AccountId owner,
                RuntimeInstanceId runtimeId,
                int limit,
                Optional<Long> before) {
            return page;
        }

        @Override
        public Optional<RuntimeCatalogTransition> findRollbackCandidate(
                AccountId owner,
                RuntimeInstanceId runtimeId,
                UUID currentCatalogId) {
            return rollbackCandidate;
        }
    }
}
