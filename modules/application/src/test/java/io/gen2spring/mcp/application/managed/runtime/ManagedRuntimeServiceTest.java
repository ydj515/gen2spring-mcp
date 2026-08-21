package io.gen2spring.mcp.application.managed.runtime;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.HEADER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.managed.credential.ManagedCredentialService;
import io.gen2spring.mcp.application.managed.credential.ManagedCredentialStore;
import io.gen2spring.mcp.application.managed.credential.CredentialProtector;
import io.gen2spring.mcp.application.managed.credential.CredentialSecret;
import io.gen2spring.mcp.application.managed.credential.ProtectedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialKind;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagedRuntimeServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T01:00:00Z");
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private static final AccountId OTHER = new AccountId(
            UUID.fromString("10000000-0000-0000-0000-000000000002"));
    private static final UUID CATALOG = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RUNTIME = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Test
    void activatesAnOwnedCredentialFreeCatalogAndReturnsTheTokenOnce() {
        Fixture fixture = fixture(tool("https://api.example.com", List.of()));

        RuntimeActivation activation = fixture.service.activate(
                OWNER, CATALOG, Optional.empty(), Optional.empty());

        assertEquals(RUNTIME, activation.instance().id().value());
        assertEquals(NOW.plus(Duration.ofHours(24)), activation.instance().expiresAt());
        assertEquals("g2s_rt_test-token", activation.plaintextToken());
        assertEquals(URI.create("https://runtime.example/mcp/" + RUNTIME), activation.endpoint());
        assertSame(activation.instance(), fixture.runtimeStore.created.instance());
        assertEquals(fixture.tokenCodec.digest, fixture.runtimeStore.created.tokenDigest());
        assertFalse(activation.toString().contains("test-token"));
    }

    @Test
    void requiresAnExplicitProviderBaseOnlyForRelativeMetadata() {
        Fixture relative = fixture(tool("/", List.of()));

        assertInvalid(() -> relative.service.activate(OWNER, CATALOG, Optional.empty(), Optional.empty()));

        RuntimeActivation activation = relative.service.activate(
                OWNER,
                CATALOG,
                Optional.of("https://provider.example/v1"),
                Optional.of(Duration.ofMinutes(30)));
        assertEquals("https://provider.example/v1", activation.instance().providerBaseUrl()
                .orElseThrow().uri().toString());
        assertEquals(NOW.plus(Duration.ofMinutes(30)), activation.instance().expiresAt());

        Fixture absolute = fixture(tool("https://api.example.com", List.of()));
        assertInvalid(() -> absolute.service.activate(
                OWNER, CATALOG, Optional.of("https://ignored.example"), Optional.empty()));

        Fixture mixed = fixture(List.of(
                tool("https://api.example.com", List.of()),
                tool("/v2", "forecast", List.of())));
        assertInvalid(() -> mixed.service.activate(
                OWNER, CATALOG, Optional.of("https://provider.example"), Optional.empty()));
    }

    @Test
    void rejectsCredentialSlotsAndInvalidLifetimesBeforePersistence() {
        Fixture credentialed = fixture(tool(
                "https://api.example.com",
                List.of(new RuntimeCredential("api-key", HEADER, "X-Api-Key", true))));

        assertUnavailable(() -> credentialed.service.activate(OWNER, CATALOG, Optional.empty(), Optional.empty()));
        assertInvalid(() -> fixture(tool("https://api.example.com", List.of())).service.activate(
                OWNER, CATALOG, Optional.empty(), Optional.of(Duration.ZERO)));
        assertInvalid(() -> fixture(tool("https://api.example.com", List.of())).service.activate(
                OWNER, CATALOG, Optional.empty(), Optional.of(Duration.ofDays(31))));
        assertEquals(0, credentialed.runtimeStore.createCount);
    }

    @Test
    void bindsOwnedActiveCredentialsWithExactSlotAndKindCompatibility() {
        CredentialStore credentialStore = new CredentialStore();
        ManagedCredentialService credentials = credentialService(credentialStore);
        ManagedCredentialId opaqueId = credentials.create(OWNER, "opaque", CredentialSecret.opaque("private")).id();
        ManagedCredentialId bearerId = credentials.create(OWNER, "bearer", CredentialSecret.bearer("private")).id();
        Fixture fixture = fixture(
                List.of(tool("https://api.example.com", "weather", List.of(
                        new RuntimeCredential("service-key", QUERY, "api_key", true),
                        new RuntimeCredential("authorization", HEADER, "Authorization", false)))),
                credentials);

        fixture.service.activate(
                OWNER, CATALOG, Optional.empty(), Optional.empty(),
                Map.of("service-key", opaqueId, "authorization", bearerId));

        assertEquals(Map.of("service-key", opaqueId, "authorization", bearerId),
                fixture.runtimeStore.created.credentialBindings());
    }

    @Test
    void rejectsMissingUnknownRevokedAndIncompatibleCredentialBindingsBeforePersistence() {
        CredentialStore credentialStore = new CredentialStore();
        ManagedCredentialService credentials = credentialService(credentialStore);
        ManagedCredentialId id = credentials.create(OWNER, "bearer", CredentialSecret.bearer("private")).id();
        Fixture fixture = fixture(
                List.of(tool("https://api.example.com", "weather", List.of(
                        new RuntimeCredential("service-key", QUERY, "api_key", true)))),
                credentials);

        assertInvalid(() -> fixture.service.activate(
                OWNER, CATALOG, Optional.empty(), Optional.empty(), Map.of()));
        assertInvalid(() -> fixture.service.activate(
                OWNER, CATALOG, Optional.empty(), Optional.empty(), Map.of("unknown", id)));
        credentialStore.values.put(id, new ManagedCredentialStore.StoredCredential(
                credential(id, ManagedCredentialKind.OPAQUE, Optional.of(NOW.minusSeconds(1))), protectedValue(1)));
        assertInvalid(() -> fixture.service.activate(
                OWNER, CATALOG, Optional.empty(), Optional.empty(), Map.of("service-key", id)));
        credentialStore.values.put(id, new ManagedCredentialStore.StoredCredential(
                credential(id, ManagedCredentialKind.BEARER, Optional.empty()), protectedValue(1)));
        assertInvalid(() -> fixture.service.activate(
                OWNER, CATALOG, Optional.empty(), Optional.empty(), Map.of("service-key", id)));
        assertEquals(0, fixture.runtimeStore.createCount);
    }

    @Test
    void preservesOwnerIsolationAndProvidesIdempotentRevocation() {
        Fixture fixture = fixture(tool("https://api.example.com", List.of()));
        ManagedRuntimeInstance runtime = fixture.service.activate(
                OWNER, CATALOG, Optional.empty(), Optional.empty()).instance();

        assertSame(runtime, fixture.service.require(OWNER, runtime.id()));
        assertNotFound(() -> fixture.service.require(OTHER, runtime.id()));
        assertNotFound(() -> fixture.service.revoke(OTHER, runtime.id()));

        fixture.service.revoke(OWNER, runtime.id());
        fixture.service.revoke(OWNER, runtime.id());
        assertEquals(1, fixture.runtimeStore.revokeCount);
        assertTrue(fixture.runtimeStore.stored.instance().revokedAt().isPresent());
    }

    @Test
    void masksCatalogAndStoreFailuresButPropagatesFatalErrors() {
        Fixture missing = fixture(tool("https://api.example.com", List.of()));
        missing.catalogStore.catalog = Optional.empty();
        assertNotFound(() -> missing.service.activate(OWNER, CATALOG, Optional.empty(), Optional.empty()));

        Fixture failed = fixture(tool("https://api.example.com", List.of()));
        failed.runtimeStore.failure = new IllegalStateException("private database marker");
        ManagedRuntimeService.ManagedRuntimeUnavailable unavailable = assertThrows(
                ManagedRuntimeService.ManagedRuntimeUnavailable.class,
                () -> failed.service.activate(OWNER, CATALOG, Optional.empty(), Optional.empty()));
        assertEquals("Managed runtime is unavailable", unavailable.getMessage());
        assertFalse(unavailable.toString().contains("private database marker"));

        AssertionError fatal = new AssertionError("fatal-marker");
        failed.runtimeStore.failure = null;
        failed.runtimeStore.fatal = fatal;
        assertSame(fatal, assertThrows(AssertionError.class,
                () -> failed.service.activate(OWNER, CATALOG, Optional.empty(), Optional.empty())));
    }

    private Fixture fixture(RuntimeTool tool) {
        return fixture(List.of(tool));
    }

    private Fixture fixture(List<RuntimeTool> tools) {
        return fixture(tools, null);
    }

    private Fixture fixture(List<RuntimeTool> tools, ManagedCredentialService credentials) {
        CatalogStore catalogStore = new CatalogStore(metadata(tools));
        RuntimeStore runtimeStore = new RuntimeStore();
        TokenCodec tokenCodec = new TokenCodec();
        ManagedRuntimeService service = credentials == null
                ? new ManagedRuntimeService(
                        new ToolCatalogService(catalogStore), runtimeStore, tokenCodec,
                        Clock.fixed(NOW, ZoneOffset.UTC), URI.create("https://runtime.example"), () -> RUNTIME)
                : new ManagedRuntimeService(
                        new ToolCatalogService(catalogStore), runtimeStore, credentials, tokenCodec,
                        Clock.fixed(NOW, ZoneOffset.UTC), URI.create("https://runtime.example"), () -> RUNTIME);
        return new Fixture(service, catalogStore, runtimeStore, tokenCodec);
    }

    private ManagedCredential credential(
            ManagedCredentialId id,
            ManagedCredentialKind kind,
            Optional<Instant> revokedAt) {
        return new ManagedCredential(id, OWNER, "provider", kind, 1, NOW.minusSeconds(10), NOW.minusSeconds(10), revokedAt);
    }

    private ManagedCredentialService credentialService(CredentialStore store) {
        CredentialProtector protector = new CredentialProtector() {
            @Override public ProtectedCredential protect(
                    AccountId owner, ManagedCredentialId id, long version, CredentialSecret secret) {
                return protectedValue(version);
            }
            @Override public CredentialSecret reveal(
                    AccountId owner, ManagedCredentialId id, long version, ProtectedCredential value) {
                return CredentialSecret.opaque("private");
            }
        };
        return new ManagedCredentialService(store, protector, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ProtectedCredential protectedValue(long version) {
        return new ProtectedCredential(1, version, "test-key", bytes(12), bytes(48), bytes(12), bytes(32));
    }

    private byte[] bytes(int size) {
        byte[] value = new byte[size];
        java.util.Arrays.fill(value, (byte) 1);
        return value;
    }

    private RuntimeTool tool(String baseUrl, List<RuntimeCredential> credentials) {
        return tool(baseUrl, "weather", credentials);
    }

    private RuntimeTool tool(String baseUrl, String name, List<RuntimeCredential> credentials) {
        return new RuntimeTool(
                "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1),
                name,
                "Get weather",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON",
                Map.of(),
                new RuntimeHttp(GET, baseUrl, "/weather", List.of(), false, false),
                null,
                null,
                null,
                credentials);
    }

    private RuntimeMetadataArtifact metadata(List<RuntimeTool> tools) {
        return new CanonicalRuntimeMetadataCodec().encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "a".repeat(64), tools));
    }

    private void assertInvalid(Runnable action) {
        ManagedRuntimeService.ManagedRuntimeRequestInvalid failure = assertThrows(
                ManagedRuntimeService.ManagedRuntimeRequestInvalid.class, action::run);
        assertEquals("Managed runtime request is invalid", failure.getMessage());
    }

    private void assertNotFound(Runnable action) {
        ManagedRuntimeService.ManagedRuntimeNotFound failure = assertThrows(
                ManagedRuntimeService.ManagedRuntimeNotFound.class, action::run);
        assertEquals("Managed runtime was not found", failure.getMessage());
        assertFalse(failure.toString().contains(CATALOG.toString()));
        assertFalse(failure.toString().contains(RUNTIME.toString()));
    }

    private void assertUnavailable(Runnable action) {
        ManagedRuntimeService.ManagedRuntimeUnavailable failure = assertThrows(
                ManagedRuntimeService.ManagedRuntimeUnavailable.class, action::run);
        assertEquals("Managed runtime is unavailable", failure.getMessage());
    }

    private record Fixture(
            ManagedRuntimeService service,
            CatalogStore catalogStore,
            RuntimeStore runtimeStore,
            TokenCodec tokenCodec) {}

    private static final class CatalogStore implements ToolCatalogStore {
        private Optional<CatalogDetails> catalog;

        private CatalogStore(RuntimeMetadataArtifact metadata) {
            CatalogSummary summary = new CatalogSummary(
                    CATALOG,
                    new JobId(UUID.fromString("40000000-0000-0000-0000-000000000001")),
                    RuntimeMetadataDocument.VERSION,
                    metadata.checksum(),
                    metadata.document().tools().size(),
                    NOW);
            catalog = Optional.of(new CatalogDetails(
                    summary, metadata.document().specificationChecksum(), metadata));
        }

        @Override
        public List<CatalogSummary> list(AccountId owner, int fetchLimit, Optional<CatalogCursor> cursor) {
            return List.of();
        }

        @Override
        public Optional<CatalogDetails> find(AccountId owner, UUID catalogId) {
            return catalog;
        }

        @Override
        public Optional<ToolDetails> findTool(AccountId owner, UUID catalogId, String toolName) {
            return Optional.empty();
        }
    }

    private static final class RuntimeStore implements ManagedRuntimeStore {
        private StoredRuntime created;
        private StoredRuntime stored;
        private RuntimeException failure;
        private Error fatal;
        private int createCount;
        private int revokeCount;

        @Override
        public void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest) {
            create(instance, digest, Map.of());
        }

        @Override
        public void create(
                ManagedRuntimeInstance instance,
                RuntimeTokenDigest digest,
                Map<String, ManagedCredentialId> bindings) {
            throwFailure();
            createCount++;
            created = new StoredRuntime(instance, digest, bindings);
            stored = created;
        }

        @Override
        public Optional<StoredRuntime> find(RuntimeInstanceId id) {
            throwFailure();
            return Optional.ofNullable(stored);
        }

        @Override
        public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant revokedAt) {
            throwFailure();
            if (stored == null || !stored.instance().owner().equals(owner)
                    || stored.instance().revokedAt().isPresent()) {
                return false;
            }
            revokeCount++;
            stored = new StoredRuntime(
                    stored.instance().revokeAt(revokedAt), stored.tokenDigest(), stored.credentialBindings());
            return true;
        }

        private void throwFailure() {
            if (fatal != null) {
                throw fatal;
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class CredentialStore implements ManagedCredentialStore {
        private final Map<ManagedCredentialId, StoredCredential> values = new java.util.HashMap<>();
        @Override public void create(ManagedCredential credential, ProtectedCredential protectedCredential) {
            values.put(credential.id(), new StoredCredential(credential, protectedCredential));
        }
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

    private static final class TokenCodec implements RuntimeTokenCodec {
        private final RuntimeTokenDigest digest = new RuntimeTokenDigest(new byte[32]);

        @Override
        public IssuedRuntimeToken issue() {
            return new IssuedRuntimeToken("g2s_rt_test-token", digest);
        }

        @Override
        public boolean matches(String presentedToken, RuntimeTokenDigest persistedDigest) {
            return "g2s_rt_test-token".equals(presentedToken) && digest.equals(persistedDigest);
        }
    }
}
