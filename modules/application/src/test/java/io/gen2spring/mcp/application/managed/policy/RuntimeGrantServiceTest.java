package io.gen2spring.mcp.application.managed.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore.AuditPage;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore.StoredGrant;
import io.gen2spring.mcp.application.managed.runtime.IssuedRuntimeToken;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeService;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenCodec;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeGrantServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T02:00:00Z");
    private static final AccountId OWNER = new AccountId(UUID.randomUUID());
    private static final AccountId FOREIGN = new AccountId(UUID.randomUUID());
    private static final RuntimeInstanceId RUNTIME = new RuntimeInstanceId(UUID.randomUUID());
    private static final UUID CATALOG = UUID.randomUUID();

    @Test
    void createsOneTimeScopedGrantAndListsOnlyRedactedMetadata() {
        Fixture fixture = fixture();

        IssuedRuntimeGrant issued = fixture.service.create(
                OWNER, RUNTIME, "mobile-client", Set.of("weather"), 20, Duration.ofHours(1));

        assertEquals("g2s_rt_grant-token", issued.plaintextToken());
        assertEquals(Set.of("weather"), issued.grant().allowedTools());
        assertEquals(List.of(issued.grant()), fixture.service.list(OWNER, RUNTIME));
        assertFalse(fixture.service.list(OWNER, RUNTIME).toString().contains("grant-token"));
        assertFalse(issued.toString().contains("grant-token"));
    }

    @Test
    void rejectsForeignRuntimeUnknownToolsAndLifetimeBeyondRuntime() {
        Fixture fixture = fixture();

        assertNotFound(() -> fixture.service.create(
                FOREIGN, RUNTIME, "client", Set.of("weather"), 10, Duration.ofMinutes(1)));
        assertInvalid(() -> fixture.service.create(
                OWNER, RUNTIME, "client", Set.of("missing"), 10, Duration.ofMinutes(1)));
        assertInvalid(() -> fixture.service.create(
                OWNER, RUNTIME, "client", Set.of("weather"), 10, Duration.ofHours(3)));
        assertEquals(0, fixture.store.created.size());
    }

    @Test
    void rejectsGrantWhenTheRuntimeCatalogChangesBeforePersistence() {
        Fixture fixture = fixture();
        fixture.store.createResult = false;

        assertInvalid(() -> fixture.service.create(
                OWNER, RUNTIME, "client", Set.of("weather"), 10, Duration.ofMinutes(5)));
        assertEquals(0, fixture.store.created.size());
    }

    @Test
    void revokesAnOwnedGrantIdempotentlyAndHidesForeignGrants() {
        Fixture fixture = fixture();
        IssuedRuntimeGrant issued = fixture.service.create(
                OWNER, RUNTIME, "client", Set.of("weather"), 10, Duration.ofMinutes(5));

        fixture.service.revoke(OWNER, RUNTIME, issued.grant().id());
        fixture.service.revoke(OWNER, RUNTIME, issued.grant().id());
        assertEquals(1, fixture.store.revokeCount);
        assertNotFound(() -> fixture.service.revoke(FOREIGN, RUNTIME, issued.grant().id()));
    }

    private Fixture fixture() {
        ManagedRuntimeInstance runtime = new ManagedRuntimeInstance(
                RUNTIME, OWNER, CATALOG, "a".repeat(64), Optional.empty(),
                NOW.minusSeconds(60), NOW.plusSeconds(7200), Optional.empty());
        ManagedRuntimeService runtimes = new ManagedRuntimeService(
                new ToolCatalogService(new CatalogStore()), new RuntimeStore(runtime), new TokenCodec(),
                Clock.fixed(NOW, ZoneOffset.UTC), java.net.URI.create("https://runtime.example"));
        PolicyStore store = new PolicyStore();
        TokenCodec tokens = new TokenCodec();
        RuntimeGrantService service = new RuntimeGrantService(
                runtimes, new ToolCatalogService(new CatalogStore()), store, tokens,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> UUID.fromString("55555555-5555-5555-5555-555555555555"));
        return new Fixture(service, store);
    }

    private void assertInvalid(Runnable action) {
        assertEquals("Managed runtime grant request is invalid",
                assertThrows(RuntimeGrantService.RuntimeGrantRequestInvalid.class, action::run).getMessage());
    }

    private void assertNotFound(Runnable action) {
        assertEquals("Managed runtime grant was not found",
                assertThrows(RuntimeGrantService.RuntimeGrantNotFound.class, action::run).getMessage());
    }

    private record Fixture(RuntimeGrantService service, PolicyStore store) {}

    private static final class TokenCodec implements RuntimeTokenCodec {
        @Override public IssuedRuntimeToken issue() {
            return new IssuedRuntimeToken("g2s_rt_grant-token", new RuntimeTokenDigest(new byte[32]));
        }
        @Override public boolean matches(String value, RuntimeTokenDigest digest) { return false; }
    }

    private static final class PolicyStore implements RuntimePolicyStore {
        private final List<ManagedRuntimeGrant> created = new ArrayList<>();
        private int revokeCount;
        private boolean createResult = true;
        @Override public boolean createGrant(
                ManagedRuntimeGrant grant, RuntimeTokenDigest digest, UUID expectedCatalogId,
                String expectedCatalogChecksum, Instant observedAt) {
            if (createResult) created.add(grant);
            return createResult;
        }
        @Override public Optional<StoredGrant> authenticateGrant(RuntimeInstanceId runtimeId, RuntimeTokenDigest digest) { return Optional.empty(); }
        @Override public List<ManagedRuntimeGrant> listGrants(AccountId owner, RuntimeInstanceId runtimeId) {
            return created.stream().filter(grant -> grant.owner().equals(owner) && grant.runtimeId().equals(runtimeId)).toList();
        }
        @Override public boolean revokeGrant(AccountId owner, RuntimeInstanceId runtimeId, RuntimeGrantId grantId, Instant revokedAt) {
            int index = -1;
            for (int i = 0; i < created.size(); i++) if (created.get(i).id().equals(grantId)
                    && created.get(i).owner().equals(owner) && created.get(i).runtimeId().equals(runtimeId)) index = i;
            if (index < 0) return false;
            ManagedRuntimeGrant current = created.get(index);
            if (current.revokedAt().isEmpty()) {
                created.set(index, current.revokeAt(revokedAt));
                revokeCount++;
            }
            return true;
        }
        @Override public boolean acquireRate(RuntimeInstanceId runtimeId, Optional<RuntimeGrantId> grantId, int limit) { return true; }
        @Override public void startAudit(ToolExecutionAudit audit) {}
        @Override public boolean completeAudit(ToolExecutionAudit audit) { return true; }
        @Override public AuditPage listAudits(AccountId owner, RuntimeInstanceId runtimeId, int limit,
                Optional<AuditCursor> cursor) { return new AuditPage(List.of(), Optional.empty()); }
    }

    private static final class RuntimeStore implements ManagedRuntimeStore {
        private final StoredRuntime runtime;
        private RuntimeStore(ManagedRuntimeInstance instance) {
            runtime = new StoredRuntime(instance, new RuntimeTokenDigest(new byte[32]));
        }
        @Override public void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest,
                Map<String, io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId> credentialBindings) {}
        @Override public Optional<StoredRuntime> find(RuntimeInstanceId id) {
            return runtime.instance().id().equals(id) ? Optional.of(runtime) : Optional.empty();
        }
        @Override public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant revokedAt) { return false; }
    }

    private static final class CatalogStore implements ToolCatalogStore {
        private final CatalogDetails catalog;
        private CatalogStore() {
            RuntimeTool tool = new RuntimeTool(
                    "getWeather", "weather", "Weather",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                    "GENERIC_JSON", Map.of(),
                    new RuntimeHttp(io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET,
                            "https://api.example", "/weather", List.of(), false, false),
                    null, null, null, List.of());
            var metadata = new CanonicalRuntimeMetadataCodec().encode(new RuntimeMetadataDocument(
                    RuntimeMetadataDocument.VERSION, "b".repeat(64), List.of(tool)));
            var summary = new CatalogSummary(
                    CATALOG, new JobId(UUID.randomUUID()), RuntimeMetadataDocument.VERSION,
                    metadata.checksum(), 1, NOW.minusSeconds(60));
            catalog = new CatalogDetails(summary, "b".repeat(64), metadata);
        }
        @Override public List<CatalogSummary> list(AccountId owner, int limit, Optional<CatalogCursor> cursor) { return List.of(); }
        @Override public Optional<CatalogDetails> find(AccountId owner, UUID id) {
            return owner.equals(OWNER) && id.equals(CATALOG) ? Optional.of(catalog) : Optional.empty();
        }
        @Override public Optional<ToolDetails> findTool(AccountId owner, UUID id, String name) { return Optional.empty(); }
    }
}
