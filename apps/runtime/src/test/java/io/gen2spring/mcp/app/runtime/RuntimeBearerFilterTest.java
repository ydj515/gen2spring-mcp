package io.gen2spring.mcp.app.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccessAuthenticator;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenCodec;
import io.gen2spring.mcp.application.managed.runtime.IssuedRuntimeToken;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore.AuditCursor;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore.AuditPage;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore.StoredGrant;
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
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RuntimeBearerFilterTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void authenticatesBeforeDelegationAndPublishesOnlyRuntimeAccess() throws Exception {
        ManagedRuntimeInstance instance = instance();
        AtomicInteger lookups = new AtomicInteger();
        ManagedRuntimeStore store = store(instance, lookups);
        RuntimeAccessAuthenticator authenticator = authenticator(
                store, tokens(presented -> "valid-token".equals(presented)), instance);
        RuntimeBearerFilter filter = new RuntimeBearerFilter(authenticator);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/mcp/" + instance.id().value());
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger delegated = new AtomicInteger();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            delegated.incrementAndGet();
            RuntimeAccess access = (RuntimeAccess) servletRequest.getAttribute(RuntimeBearerFilter.RUNTIME_ACCESS);
            assertSame(instance, access.instance());
        });

        assertEquals(1, lookups.get());
        assertEquals(1, delegated.get());
        assertNull(request.getSession(false));
    }

    @Test
    void returnsEquivalentFixedUnauthorizedResponsesWithoutCacheDelegation() throws Exception {
        ManagedRuntimeInstance instance = instance();
        RuntimeAccessAuthenticator authenticator = authenticator(
                store(instance, new AtomicInteger()), tokens(presented -> false), instance);
        RuntimeBearerFilter filter = new RuntimeBearerFilter(authenticator);

        for (String header : new String[] {null, "Basic private", "Bearer wrong"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "POST", "/mcp/" + instance.id().value());
            if (header != null) request.addHeader("Authorization", header);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicInteger delegated = new AtomicInteger();
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> delegated.incrementAndGet());

            assertEquals(401, response.getStatus());
            assertEquals("{\"code\":\"UNAUTHORIZED\",\"message\":\"Managed runtime authentication failed\"}",
                    response.getContentAsString());
            assertEquals(0, delegated.get());
        }
    }

    @Test
    void preservesDownstreamRuntimeFailuresAfterAuthentication() {
        ManagedRuntimeInstance instance = instance();
        RuntimeAccessAuthenticator authenticator = authenticator(
                store(instance, new AtomicInteger()),
                tokens(presented -> "valid-token".equals(presented)), instance);
        RuntimeBearerFilter filter = new RuntimeBearerFilter(authenticator);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/mcp/" + instance.id().value());
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalStateException failure = new IllegalStateException("private-marker");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                    throw failure;
                }));

        assertSame(failure, thrown);
        assertEquals(200, response.getStatus());
    }

    @Test
    void returnsFixedUnavailableForAuthenticationStoreFailures() throws Exception {
        ManagedRuntimeInstance instance = instance();
        ManagedRuntimeStore store = new ManagedRuntimeStore() {
            @Override public void create(ManagedRuntimeInstance ignored, RuntimeTokenDigest digest,
                    java.util.Map<String, io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId>
                            credentialBindings) {}
            @Override public Optional<StoredRuntime> find(RuntimeInstanceId id) {
                throw new IllegalStateException("private database marker");
            }
            @Override public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant at) { return false; }
        };
        RuntimeBearerFilter filter = new RuntimeBearerFilter(authenticator(
                store, tokens(presented -> true), instance));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/mcp/" + instance.id().value());
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("must not delegate");
        });

        assertEquals(503, response.getStatus());
        assertEquals("{\"code\":\"RUNTIME_UNAVAILABLE\",\"message\":\"Managed runtime is unavailable\"}",
                response.getContentAsString());
    }

    @Test
    void invalidatesOnlyInactiveRuntimeHandlesBeforeReturningEquivalentUnauthorized() throws Exception {
        ManagedRuntimeInstance revoked = instance().revokeAt(NOW);
        java.util.concurrent.atomic.AtomicReference<RuntimeInstanceId> invalidated =
                new java.util.concurrent.atomic.AtomicReference<>();
        RuntimeBearerFilter filter = new RuntimeBearerFilter(authenticator(
                store(revoked, new AtomicInteger()), tokens(presented -> true), revoked), invalidated::set);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/mcp/" + revoked.id().value());
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("must not delegate");
        });

        assertEquals(revoked.id(), invalidated.get());
        assertEquals(401, response.getStatus());
        assertEquals("{\"code\":\"UNAUTHORIZED\",\"message\":\"Managed runtime authentication failed\"}",
                response.getContentAsString());
    }

    private ManagedRuntimeStore store(ManagedRuntimeInstance instance, AtomicInteger lookups) {
        return new ManagedRuntimeStore() {
            @Override public void create(ManagedRuntimeInstance ignored, RuntimeTokenDigest digest,
                    java.util.Map<String, io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId>
                            credentialBindings) {}
            @Override public Optional<StoredRuntime> find(RuntimeInstanceId id) {
                lookups.incrementAndGet();
                return id.equals(instance.id())
                        ? Optional.of(new StoredRuntime(instance, new RuntimeTokenDigest(new byte[32])))
                        : Optional.empty();
            }
            @Override public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant at) { return false; }
        };
    }

    private RuntimeTokenCodec tokens(java.util.function.Predicate<String> matches) {
        return new RuntimeTokenCodec() {
            @Override public IssuedRuntimeToken issue() { throw new UnsupportedOperationException(); }
            @Override public boolean matches(String presented, RuntimeTokenDigest digest) {
                return matches.test(presented);
            }
            @Override public RuntimeTokenDigest digest(String presented) {
                return new RuntimeTokenDigest(new byte[32]);
            }
        };
    }

    private RuntimeAccessAuthenticator authenticator(
            ManagedRuntimeStore store,
            RuntimeTokenCodec tokens,
            ManagedRuntimeInstance instance) {
        RuntimeTool tool = new RuntimeTool(
                "operation", "managed_tool", "Managed Tool",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(HttpMethod.GET, "https://api.example", "/items", List.of(), false, false),
                null, null, null, List.of());
        var metadata = new CanonicalRuntimeMetadataCodec().encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "b".repeat(64), List.of(tool)));
        var summary = new ToolCatalogStore.CatalogSummary(
                instance.catalogId(), new JobId(UUID.fromString("40000000-0000-0000-0000-000000000001")),
                RuntimeMetadataDocument.VERSION, metadata.checksum(), 1, NOW.minusSeconds(30));
        var details = new ToolCatalogStore.CatalogDetails(summary, "b".repeat(64), metadata);
        ToolCatalogService catalogs = new ToolCatalogService(new ToolCatalogStore() {
            @Override public List<CatalogSummary> list(AccountId owner, int limit, Optional<CatalogCursor> cursor) {
                return List.of(summary);
            }
            @Override public Optional<CatalogDetails> find(AccountId owner, UUID catalogId) {
                return owner.equals(instance.owner()) && catalogId.equals(instance.catalogId())
                        ? Optional.of(details) : Optional.empty();
            }
            @Override public Optional<ToolDetails> findTool(
                    AccountId owner, UUID catalogId, String toolName) { return Optional.empty(); }
        });
        return new RuntimeAccessAuthenticator(
                store, tokens, Clock.fixed(NOW, ZoneOffset.UTC), catalogs, new EmptyPolicy());
    }

    private static final class EmptyPolicy implements RuntimePolicyStore {
        @Override public void createGrant(ManagedRuntimeGrant grant, RuntimeTokenDigest digest) {}
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
                Optional<AuditCursor> cursor) { return new AuditPage(List.of(), Optional.empty()); }
    }

    private ManagedRuntimeInstance instance() {
        return new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                new AccountId(UUID.fromString("20000000-0000-0000-0000-000000000001")),
                UUID.fromString("30000000-0000-0000-0000-000000000001"), "a".repeat(64),
                Optional.empty(), NOW.minusSeconds(1), NOW.plusSeconds(3600), Optional.empty());
    }
}
