package io.gen2spring.mcp.application.managed.credential;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.HEADER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.managed.credential.ManagedCredentialStore.StoredCredential;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialKind;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeCredentialResolverTest {
    private static final Instant NOW = Instant.parse("2026-08-21T04:00:00Z");
    private static final AccountId OWNER = new AccountId(UUID.randomUUID());
    private static final ManagedCredentialId OPAQUE = new ManagedCredentialId(UUID.randomUUID());
    private static final ManagedCredentialId BEARER = new ManagedCredentialId(UUID.randomUUID());

    @Test
    void resolvesOnlySelectedToolSlotsInCanonicalOrderAndFormatsWireValues() {
        RuntimeFixture fixture = fixture(Map.of("query-key", OPAQUE, "authorization", BEARER));
        RuntimeCredentialResolver resolver = fixture.resolver();
        RuntimeTool tool = tool(List.of(
                new RuntimeCredential("query-key", QUERY, "api_key", true),
                new RuntimeCredential("authorization", HEADER, "Authorization", true)));

        try (var resolved = resolver.resolve(fixture.instance(), tool)) {
            assertEquals(List.of("authorization", "query-key"),
                    resolved.values().stream().map(RuntimeCredentialResolver.WireCredential::slot).toList());
            assertEquals("Bearer bearer-secret", new String(
                    resolved.values().getFirst().wireValue(), StandardCharsets.UTF_8));
            assertEquals("opaque-secret", new String(
                    resolved.values().get(1).wireValue(), StandardCharsets.UTF_8));
        }
        assertEquals(2, fixture.reveals().get());
    }

    @Test
    void permitsUnboundOptionalSlotsAndRejectsMissingOrRevokedCredentialsSafely() {
        RuntimeFixture optional = fixture(Map.of());
        try (var resolved = optional.resolver().resolve(optional.instance(), tool(List.of(
                new RuntimeCredential("optional-key", HEADER, "X-Optional", false))))) {
            assertEquals(List.of(), resolved.values());
        }

        RuntimeFixture missing = fixture(Map.of());
        assertUnavailable(() -> missing.resolver().resolve(missing.instance(), tool(List.of(
                new RuntimeCredential("required-key", HEADER, "X-Key", true)))));

        RuntimeFixture revoked = fixture(Map.of("query-key", OPAQUE));
        revoked.credentials().values.put(OPAQUE, stored(
                OPAQUE, ManagedCredentialKind.OPAQUE, Optional.of(NOW.minusSeconds(1))));
        assertUnavailable(() -> revoked.resolver().resolve(revoked.instance(), tool(List.of(
                new RuntimeCredential("query-key", QUERY, "api_key", true)))));
    }

    private void assertUnavailable(Runnable action) {
        RuntimeCredentialResolver.RuntimeCredentialUnavailable failure = assertThrows(
                RuntimeCredentialResolver.RuntimeCredentialUnavailable.class, action::run);
        assertEquals("Managed runtime credential is unavailable", failure.getMessage());
        assertFalse(failure.toString().contains("secret"));
        assertFalse(failure.toString().contains(OPAQUE.value().toString()));
    }

    private RuntimeFixture fixture(Map<String, ManagedCredentialId> bindings) {
        ManagedRuntimeInstance instance = new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.randomUUID()), OWNER, UUID.randomUUID(), "a".repeat(64),
                Optional.empty(), NOW.minusSeconds(60), NOW.plusSeconds(3600), Optional.empty());
        CredentialStore credentials = new CredentialStore();
        credentials.values.put(OPAQUE, stored(OPAQUE, ManagedCredentialKind.OPAQUE, Optional.empty()));
        credentials.values.put(BEARER, stored(BEARER, ManagedCredentialKind.BEARER, Optional.empty()));
        RuntimeStore runtimes = new RuntimeStore(instance, bindings);
        AtomicInteger reveals = new AtomicInteger();
        CredentialProtector protector = new CredentialProtector() {
            @Override public ProtectedCredential protect(AccountId owner, ManagedCredentialId id, long version,
                    CredentialSecret secret) { throw new UnsupportedOperationException(); }
            @Override public CredentialSecret reveal(AccountId owner, ManagedCredentialId id, long version,
                    ProtectedCredential value) {
                reveals.incrementAndGet();
                return id.equals(OPAQUE) ? CredentialSecret.opaque("opaque-secret")
                        : CredentialSecret.bearer("bearer-secret");
            }
        };
        return new RuntimeFixture(instance, credentials, reveals,
                new RuntimeCredentialResolver(runtimes, credentials, protector));
    }

    private StoredCredential stored(
            ManagedCredentialId id,
            ManagedCredentialKind kind,
            Optional<Instant> revokedAt) {
        ManagedCredential credential = new ManagedCredential(
                id, OWNER, "credential", kind, 1, NOW.minusSeconds(60), NOW.minusSeconds(60), revokedAt);
        return new StoredCredential(credential, new ProtectedCredential(
                1, 1, "test-key", bytes(12), bytes(48), bytes(12), bytes(32)));
    }

    private RuntimeTool tool(List<RuntimeCredential> credentials) {
        return new RuntimeTool(
                "operation", "managed_tool", "Managed Tool",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(GET, "https://api.example", "/items", List.of(), false, false),
                null, null, null, credentials);
    }

    private byte[] bytes(int size) {
        byte[] value = new byte[size];
        java.util.Arrays.fill(value, (byte) 1);
        return value;
    }

    private record RuntimeFixture(
            ManagedRuntimeInstance instance,
            CredentialStore credentials,
            AtomicInteger reveals,
            RuntimeCredentialResolver resolver) {}

    private static final class RuntimeStore implements ManagedRuntimeStore {
        private final StoredRuntime stored;
        private RuntimeStore(ManagedRuntimeInstance instance, Map<String, ManagedCredentialId> bindings) {
            stored = new StoredRuntime(instance, new RuntimeTokenDigest(new byte[32]), bindings);
        }
        @Override public void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest) {}
        @Override public Optional<StoredRuntime> find(RuntimeInstanceId id) {
            return stored.instance().id().equals(id) ? Optional.of(stored) : Optional.empty();
        }
        @Override public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant revokedAt) { return false; }
    }

    private static final class CredentialStore implements ManagedCredentialStore {
        private final Map<ManagedCredentialId, StoredCredential> values = new java.util.HashMap<>();
        @Override public void create(ManagedCredential credential, ProtectedCredential protectedCredential) {}
        @Override public boolean rotate(AccountId owner, ManagedCredentialId id, long expectedVersion,
                ManagedCredential credential, ProtectedCredential protectedCredential) { return false; }
        @Override public boolean revoke(AccountId owner, ManagedCredentialId id, Instant revokedAt) { return false; }
        @Override public Optional<StoredCredential> find(AccountId owner, ManagedCredentialId id) {
            StoredCredential value = values.get(id);
            return value != null && value.credential().owner().equals(owner) ? Optional.of(value) : Optional.empty();
        }
        @Override public List<ManagedCredential> list(AccountId owner) { return List.of(); }
        @Override public long countActive(AccountId owner) { return 0; }
    }
}
