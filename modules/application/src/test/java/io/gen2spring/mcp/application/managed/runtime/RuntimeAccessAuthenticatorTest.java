package io.gen2spring.mcp.application.managed.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore;
import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore;
import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore.AuditPage;
import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore.StoredGrant;
import io.gen2spring.mcp.application.managed.runtime.port.out.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.port.out.RuntimeTokenCodec;
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
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeAccessAuthenticatorTest {
    private static final Instant NOW = Instant.parse("2026-08-21T01:00:00Z");
    private static final RuntimeInstanceId ID = new RuntimeInstanceId(
            UUID.fromString("30000000-0000-0000-0000-000000000001"));

    @Test
    void exposesOnlyThePolicyAwareAuthenticationContract() {
        assertThrows(NoSuchMethodException.class,
                () -> RuntimeAccess.class.getConstructor(ManagedRuntimeInstance.class));
        assertThrows(NoSuchMethodException.class,
                () -> RuntimeAccessAuthenticator.class.getConstructor(
                        ManagedRuntimeStore.class, RuntimeTokenCodec.class, Clock.class));
    }

    @Test
    void authenticatesOneActiveRuntimeWithOneStoreLookup() {
        Store store = new Store(active());
        RuntimeAccessAuthenticator authenticator = authenticator(store, true);

        RuntimeAccess access = authenticator.authenticate(ID, "g2s_rt_valid");

        assertEquals(ID, access.instance().id());
        assertEquals(1, store.findCount);
    }

    @Test
    void rejectsMissingWrongExpiredAndRevokedTokensWithOneSafeFailure() {
        assertUnauthorized(authenticator(new Store(null), true), "g2s_rt_missing");
        assertUnauthorized(authenticator(new Store(active()), false), "g2s_rt_wrong-private-marker");
        assertUnauthorized(authenticator(new Store(instance(NOW)), true), "g2s_rt_expired");
        assertUnauthorized(authenticator(new Store(instance(NOW.plusSeconds(60)).revokeAt(NOW)), true),
                "g2s_rt_revoked");
        assertUnauthorized(authenticator(new Store(active()), true), "");
        assertUnauthorized(authenticator(new Store(active()), true), "x".repeat(513));
    }

    @Test
    void separatesStoreFailureFromInvalidCredentials() {
        Store store = new Store(active());
        store.failure = new IllegalStateException("private database marker");

        RuntimeAccessAuthenticator.RuntimeAccessUnavailable failure = assertThrows(
                RuntimeAccessAuthenticator.RuntimeAccessUnavailable.class,
                () -> authenticator(store, true).authenticate(ID, "g2s_rt_valid"));

        assertEquals("Managed runtime authentication is unavailable", failure.getMessage());
        assertFalse(failure.toString().contains("private database marker"));
    }

    @Test
    void identifiesInactiveRuntimesWithoutChangingThePublicFailureMessage() {
        RuntimeAccessAuthenticator authenticator = authenticator(new Store(instance(NOW)), true);

        RuntimeAccessAuthenticator.RuntimeInactive failure = assertThrows(
                RuntimeAccessAuthenticator.RuntimeInactive.class,
                () -> authenticator.authenticate(ID, "g2s_rt_valid"));

        assertEquals("Managed runtime authentication failed", failure.getMessage());
    }

    @Test
    void authenticatesOwnerAndScopedGrantWithDeterministicVisibilityPolicy() {
        ManagedRuntimeInstance runtime = active();
        RuntimeGrantId grantId = new RuntimeGrantId(
                UUID.fromString("40000000-0000-0000-0000-000000000001"));
        ManagedRuntimeGrant grant = new ManagedRuntimeGrant(
                grantId, ID, runtime.owner(), "client-a", Set.of("forecast_tool"), 7,
                NOW.minusSeconds(60), NOW.plusSeconds(30), Optional.empty());
        RuntimeAccessAuthenticator authenticator = new RuntimeAccessAuthenticator(
                new Store(runtime), tokenCodec(), Clock.fixed(NOW, ZoneOffset.UTC),
                catalog(runtime), new GrantPolicy(grant));

        RuntimeAccess owner = authenticator.authenticate(ID, "owner-token");
        RuntimeAccess scoped = authenticator.authenticate(ID, "grant-token");

        assertEquals(Set.of("forecast_tool", "status_tool"), owner.allowedTools());
        assertEquals(600, owner.requestsPerMinute());
        assertEquals(true, owner.ownerGrant());
        assertEquals(runtime.expiresAt(), owner.validUntil());
        assertEquals(Set.of("forecast_tool"), scoped.allowedTools());
        assertEquals(7, scoped.requestsPerMinute());
        assertEquals(Optional.of(grantId), scoped.grantId());
        assertEquals("client-a", scoped.principal());
        assertEquals(NOW.plusSeconds(30), scoped.validUntil());
        assertNotEquals(owner.policyChecksum(), scoped.policyChecksum());
        assertEquals(scoped.policyChecksum(), authenticator.authenticate(ID, "grant-token").policyChecksum());
    }

    private RuntimeAccessAuthenticator authenticator(Store store, boolean matches) {
        return new RuntimeAccessAuthenticator(
                store,
                new RuntimeTokenCodec() {
                    @Override
                    public IssuedRuntimeToken issue() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean matches(String token, RuntimeTokenDigest digest) {
                        return matches;
                    }

                    @Override
                    public RuntimeTokenDigest digest(String token) {
                        return new RuntimeTokenDigest(new byte[32]);
                    }
                },
                Clock.fixed(NOW, ZoneOffset.UTC), catalog(active()), new EmptyPolicy());
    }

    private RuntimeTokenCodec tokenCodec() {
        return new RuntimeTokenCodec() {
            @Override public IssuedRuntimeToken issue() { throw new UnsupportedOperationException(); }
            @Override public boolean matches(String token, RuntimeTokenDigest digest) {
                return "owner-token".equals(token);
            }
            @Override public RuntimeTokenDigest digest(String token) {
                byte[] value = new byte[32];
                java.util.Arrays.fill(value, (byte) 2);
                return new RuntimeTokenDigest(value);
            }
        };
    }

    private ToolCatalogService catalog(ManagedRuntimeInstance runtime) {
        RuntimeTool forecast = runtimeTool("forecast_tool");
        RuntimeTool status = runtimeTool("status_tool");
        var artifact = new CanonicalRuntimeMetadataCodec().encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "b".repeat(64), List.of(forecast, status)));
        var summary = new ToolCatalogStore.CatalogSummary(
                runtime.catalogId(), new JobId(UUID.fromString("50000000-0000-0000-0000-000000000001")),
                RuntimeMetadataDocument.VERSION, artifact.checksum(), 2, NOW.minusSeconds(60));
        var details = new ToolCatalogStore.CatalogDetails(summary, "b".repeat(64), artifact);
        return new ToolCatalogService(new ToolCatalogStore() {
            @Override public List<CatalogSummary> list(AccountId owner, int limit, Optional<CatalogCursor> cursor) {
                return List.of(summary);
            }
            @Override public Optional<CatalogDetails> find(AccountId owner, UUID catalogId) {
                return owner.equals(runtime.owner()) && catalogId.equals(runtime.catalogId())
                        ? Optional.of(details) : Optional.empty();
            }
            @Override public Optional<ToolDetails> findTool(AccountId owner, UUID catalogId, String toolName) {
                return Optional.empty();
            }
        });
    }

    private RuntimeTool runtimeTool(String name) {
        return new RuntimeTool(
                name, name, "Tool " + name,
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(), new RuntimeHttp(
                        HttpMethod.GET, "https://api.example", "/items", List.of(), false, false),
                null, null, null, List.of());
    }

    private static final class GrantPolicy implements RuntimePolicyStore {
        private final ManagedRuntimeGrant grant;
        private GrantPolicy(ManagedRuntimeGrant grant) { this.grant = grant; }
        @Override public boolean createGrant(
                ManagedRuntimeGrant value, RuntimeTokenDigest digest, UUID expectedCatalogId,
                String expectedCatalogChecksum, Instant observedAt) { return true; }
        @Override public Optional<StoredGrant> authenticateGrant(RuntimeInstanceId runtimeId, RuntimeTokenDigest digest) {
            return Optional.of(new StoredGrant(grant, digest));
        }
        @Override public List<ManagedRuntimeGrant> listGrants(AccountId owner, RuntimeInstanceId runtimeId) {
            return List.of(grant);
        }
        @Override public boolean revokeGrant(AccountId owner, RuntimeInstanceId runtimeId,
                RuntimeGrantId grantId, Instant revokedAt) { return false; }
        @Override public boolean acquireRate(RuntimeInstanceId runtimeId, Optional<RuntimeGrantId> grantId,
                int requestsPerMinute) { return true; }
        @Override public void startAudit(ToolExecutionAudit audit) {}
        @Override public boolean completeAudit(ToolExecutionAudit audit) { return true; }
        @Override public AuditPage listAudits(AccountId owner, RuntimeInstanceId runtimeId, int limit,
                Optional<RuntimePolicyStore.AuditCursor> cursor) { return new AuditPage(List.of(), Optional.empty()); }
    }

    private static final class EmptyPolicy implements RuntimePolicyStore {
        @Override public boolean createGrant(
                ManagedRuntimeGrant value, RuntimeTokenDigest digest, UUID expectedCatalogId,
                String expectedCatalogChecksum, Instant observedAt) { return true; }
        @Override public Optional<StoredGrant> authenticateGrant(
                RuntimeInstanceId runtimeId, RuntimeTokenDigest digest) { return Optional.empty(); }
        @Override public List<ManagedRuntimeGrant> listGrants(AccountId owner, RuntimeInstanceId runtimeId) {
            return List.of();
        }
        @Override public boolean revokeGrant(AccountId owner, RuntimeInstanceId runtimeId,
                RuntimeGrantId grantId, Instant revokedAt) { return false; }
        @Override public boolean acquireRate(RuntimeInstanceId runtimeId, Optional<RuntimeGrantId> grantId,
                int requestsPerMinute) { return true; }
        @Override public void startAudit(ToolExecutionAudit audit) {}
        @Override public boolean completeAudit(ToolExecutionAudit audit) { return true; }
        @Override public AuditPage listAudits(AccountId owner, RuntimeInstanceId runtimeId, int limit,
                Optional<RuntimePolicyStore.AuditCursor> cursor) {
            return new AuditPage(List.of(), Optional.empty());
        }
    }

    private ManagedRuntimeInstance active() {
        return instance(NOW.plusSeconds(60));
    }

    private ManagedRuntimeInstance instance(Instant expiresAt) {
        return new ManagedRuntimeInstance(
                ID,
                new AccountId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "a".repeat(64),
                Optional.empty(),
                NOW.minusSeconds(60),
                expiresAt,
                Optional.empty());
    }

    private void assertUnauthorized(RuntimeAccessAuthenticator authenticator, String token) {
        RuntimeAccessAuthenticator.RuntimeUnauthorized failure = assertThrows(
                RuntimeAccessAuthenticator.RuntimeUnauthorized.class,
                () -> authenticator.authenticate(ID, token));
        assertEquals("Managed runtime authentication failed", failure.getMessage());
        assertFalse(failure.toString().contains("private-marker"));
        assertFalse(failure.toString().contains(ID.value().toString()));
    }

    private static final class Store implements ManagedRuntimeStore {
        private final StoredRuntime stored;
        private int findCount;
        private RuntimeException failure;

        private Store(ManagedRuntimeInstance instance) {
            stored = instance == null ? null : new StoredRuntime(instance, new RuntimeTokenDigest(new byte[32]));
        }

        @Override
        public void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest,
                Map<String, io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId> credentialBindings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredRuntime> find(RuntimeInstanceId id) {
            findCount++;
            if (failure != null) throw failure;
            return Optional.ofNullable(stored);
        }

        @Override
        public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant revokedAt) {
            throw new UnsupportedOperationException();
        }
    }
}
