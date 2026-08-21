package io.gen2spring.mcp.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore.StoredRuntime;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialKind;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.ProviderTarget;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresManagedRuntimeStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final String CHECKSUM = "a".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.9-alpine");

    private JdbcTemplate jdbc;
    private PostgresManagedRuntimeStore store;

    @BeforeEach
    void resetDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new PostgresManagedRuntimeStore(dataSource);
    }

    @Test
    void persistsExactRuntimeStateAndRevokesIdempotently() {
        AccountId owner = account();
        UUID catalog = catalog(owner);
        ManagedRuntimeInstance instance = instance(owner, catalog, Optional.of(ProviderTarget.parse("https://api.example/")));
        byte[] digest = digest();

        store.create(instance, new RuntimeTokenDigest(digest));

        StoredRuntime stored = store.find(instance.id()).orElseThrow();
        assertEquals(instance, stored.instance());
        assertArrayEquals(digest, stored.tokenDigest().value());

        Instant first = NOW.plusSeconds(60);
        Instant later = NOW.plusSeconds(120);
        assertTrue(store.revoke(owner, instance.id(), first));
        assertTrue(store.revoke(owner, instance.id(), later));
        assertEquals(Optional.of(first), store.find(instance.id()).orElseThrow().instance().revokedAt());
        assertFalse(store.revoke(account(), instance.id(), later));
        assertFalse(store.revoke(owner, new RuntimeInstanceId(UUID.randomUUID()), later));
    }

    @Test
    void rejectsForeignCatalogsDuplicateIdsAndInvalidDigestRows() {
        AccountId owner = account();
        AccountId foreign = account();
        UUID catalog = catalog(owner);
        ManagedRuntimeInstance foreignInstance = instance(foreign, catalog, Optional.empty());

        RuntimeException foreignFailure = assertThrows(
                RuntimeException.class, () -> store.create(foreignInstance, new RuntimeTokenDigest(digest())));
        assertEquals("Managed runtime storage write failed", foreignFailure.getMessage());
        assertFalse(foreignFailure.toString().contains(catalog.toString()));

        ManagedRuntimeInstance valid = instance(owner, catalog, Optional.empty());
        store.create(valid, new RuntimeTokenDigest(digest()));
        RuntimeException duplicate = assertThrows(
                RuntimeException.class, () -> store.create(valid, new RuntimeTokenDigest(digest())));
        assertEquals("Managed runtime storage write failed", duplicate.getMessage());

        assertThrows(RuntimeException.class, () -> jdbc.update(
                "update managed_runtime_instance set token_digest = ? where id = ?", new byte[31], valid.id().value()));
    }

    @Test
    void concurrentRevocationIsOneWayAndCatalogDeletionCascades() throws Exception {
        AccountId owner = account();
        UUID catalog = catalog(owner);
        ManagedRuntimeInstance instance = instance(owner, catalog, Optional.empty());
        store.create(instance, new RuntimeTokenDigest(digest()));
        Instant first = NOW.plusSeconds(10);
        Instant second = NOW.plusSeconds(20);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> left = executor.submit(() -> {
                start.await();
                return store.revoke(owner, instance.id(), first);
            });
            Future<Boolean> right = executor.submit(() -> {
                start.await();
                return store.revoke(owner, instance.id(), second);
            });
            start.countDown();
            assertTrue(left.get());
            assertTrue(right.get());
        }
        Instant revokedAt = store.find(instance.id()).orElseThrow().instance().revokedAt().orElseThrow();
        assertTrue(revokedAt.equals(first) || revokedAt.equals(second));

        jdbc.update("delete from tool_catalog where id = ?", catalog);
        assertTrue(store.find(instance.id()).isEmpty());
    }

    @Test
    void insertsRuntimeAndCredentialBindingsAtomicallyAndRejectsForeignCredentials() {
        AccountId owner = account();
        UUID catalog = catalog(owner);
        ManagedRuntimeInstance instance = instance(owner, catalog, Optional.empty());
        ManagedCredential credential = credential(owner);
        new PostgresManagedCredentialStore(jdbc.getDataSource()).create(
                credential,
                new io.gen2spring.mcp.application.managed.credential.ProtectedCredential(
                        1, 1, "operator-key", bytes(12), bytes(48), bytes(12), bytes(32)));

        store.create(instance, new RuntimeTokenDigest(digest()), Map.of("service-key", credential.id()));

        assertEquals(Map.of("service-key", credential.id()), store.find(instance.id()).orElseThrow().credentialBindings());

        AccountId foreign = account();
        ManagedCredential foreignCredential = credential(foreign);
        new PostgresManagedCredentialStore(jdbc.getDataSource()).create(
                foreignCredential,
                new io.gen2spring.mcp.application.managed.credential.ProtectedCredential(
                        1, 1, "operator-key", bytes(12), bytes(48), bytes(12), bytes(32)));
        ManagedRuntimeInstance rejected = instance(owner, catalog, Optional.empty());
        RuntimeException failure = assertThrows(RuntimeException.class, () -> store.create(
                rejected, new RuntimeTokenDigest(digest()), Map.of("service-key", foreignCredential.id())));
        assertEquals("Managed runtime storage write failed", failure.getMessage());
        assertTrue(store.find(rejected.id()).isEmpty());
    }

    private ManagedRuntimeInstance instance(AccountId owner, UUID catalog, Optional<ProviderTarget> provider) {
        return new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.randomUUID()), owner, catalog, CHECKSUM, provider,
                NOW, NOW.plus(1, ChronoUnit.DAYS), Optional.empty());
    }

    private AccountId account() {
        UUID value = UUID.randomUUID();
        jdbc.update("""
                insert into account(id, issuer, subject, created_at, last_seen_at)
                values (?, 'https://issuer.example', ?, ?, ?)
                """, value, value.toString(), Timestamp.from(NOW), Timestamp.from(NOW));
        return new AccountId(value);
    }

    private UUID catalog(AccountId owner) {
        UUID specification = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID catalog = UUID.randomUUID();
        jdbc.update("""
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'UPLOAD', ?, ?, 10, 'weather', 'READY', ?, ?)
                """, specification, owner.value(), "specifications/" + specification, CHECKSUM,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                insert into generation_job(
                    id, owner_account_id, specification_id, kind, operation, idempotency_key,
                    request_hash, request_snapshot, status, created_at, updated_at)
                values (?, ?, ?, 'GENERATION', 'generation', ?, ?, '{}'::jsonb, 'SUCCEEDED', ?, ?)
                """, job, owner.value(), specification, job.toString(), CHECKSUM,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                insert into tool_catalog(
                    id, owner_account_id, generation_job_id, metadata_version,
                    specification_checksum, metadata_checksum, metadata_document,
                    tool_count, created_at)
                values (?, ?, ?, '1.0', ?, ?, '{}', 1, ?)
                """, catalog, owner.value(), job, CHECKSUM, CHECKSUM, Timestamp.from(NOW));
        return catalog;
    }

    private byte[] digest() {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) 0x5a);
        return value;
    }

    private ManagedCredential credential(AccountId owner) {
        return new ManagedCredential(
                new ManagedCredentialId(UUID.randomUUID()), owner, "provider-key",
                ManagedCredentialKind.OPAQUE, 1, NOW, NOW, Optional.empty());
    }

    private byte[] bytes(int size) {
        byte[] value = new byte[size];
        java.util.Arrays.fill(value, (byte) 7);
        return value;
    }
}
