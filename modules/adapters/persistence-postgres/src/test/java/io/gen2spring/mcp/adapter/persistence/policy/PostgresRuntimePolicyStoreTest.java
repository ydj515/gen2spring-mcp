package io.gen2spring.mcp.adapter.persistence.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore.AuditCursor;
import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore.StoredGrant;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit.AuditStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
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
class PostgresRuntimePolicyStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final String CHECKSUM = "a".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.9-alpine");

    private JdbcTemplate jdbc;
    private PostgresRuntimePolicyStore store;
    private AccountId owner;
    private RuntimeInstanceId runtimeId;

    @BeforeEach
    void resetDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new PostgresRuntimePolicyStore(dataSource);
        owner = account();
        runtimeId = runtime(owner);
    }

    @Test
    void storesAuthenticatesListsAndRevokesOwnerScopedGrants() {
        ManagedRuntimeGrant grant = grant(owner, runtimeId);
        RuntimeTokenDigest digest = digest((byte) 4);

        assertTrue(store.createGrant(grant, digest, currentCatalog(), CHECKSUM, NOW));

        StoredGrant stored = store.authenticateGrant(runtimeId, digest).orElseThrow();
        assertEquals(grant, stored.grant());
        assertEquals(digest, stored.tokenDigest());
        assertEquals(java.util.List.of(grant), store.listGrants(owner, runtimeId));
        assertTrue(store.listGrants(account(), runtimeId).isEmpty());
        assertTrue(store.revokeGrant(owner, runtimeId, grant.id(), NOW.plusSeconds(30)));
        assertTrue(store.revokeGrant(owner, runtimeId, grant.id(), NOW.plusSeconds(40)));
        assertEquals(Optional.of(NOW.plusSeconds(30)),
                store.listGrants(owner, runtimeId).getFirst().revokedAt());
    }

    @Test
    void rejectsGrantCreationWhenTheExpectedRuntimeCatalogIsStale() {
        ManagedRuntimeGrant grant = grant(owner, runtimeId);

        assertFalse(store.createGrant(
                grant, digest((byte) 5), UUID.randomUUID(), "b".repeat(64), NOW));
        assertTrue(store.listGrants(owner, runtimeId).isEmpty());
    }

    @Test
    void sharesOneAtomicDatabaseTimeRateWindowAcrossStoreInstances() throws Exception {
        PostgresRuntimePolicyStore second = new PostgresRuntimePolicyStore(jdbc.getDataSource());
        for (int attempt = 0; attempt < 3; attempt++) {
            Instant before = databaseMinute();
            RuntimeGrantId grantId = new RuntimeGrantId(UUID.randomUUID());
            CountDownLatch start = new CountDownLatch(1);
            int accepted = 0;
            try (var executor = Executors.newFixedThreadPool(12)) {
                java.util.List<Future<Boolean>> calls = new java.util.ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    PostgresRuntimePolicyStore target = i % 2 == 0 ? store : second;
                    calls.add(executor.submit(() -> {
                        start.await();
                        return target.acquireRate(runtimeId, Optional.of(grantId), 5);
                    }));
                }
                start.countDown();
                for (Future<Boolean> call : calls) if (call.get()) accepted++;
            }
            if (before.equals(databaseMinute())) {
                assertEquals(5, accepted);
                return;
            }
        }
        fail("Database minute changed during every bounded rate-limit attempt");
    }

    private Instant databaseMinute() {
        return jdbc.queryForObject(
                "select date_trunc('minute', clock_timestamp())", Timestamp.class).toInstant();
    }

    private UUID currentCatalog() {
        return jdbc.queryForObject(
                "select catalog_id from managed_runtime_instance where id = ?", UUID.class, runtimeId.value());
    }

    @Test
    void completesAuditExactlyOnceAndListsByOwnerWithCursor() {
        ManagedRuntimeGrant grant = grant(owner, runtimeId);
        assertTrue(store.createGrant(grant, digest((byte) 3), currentCatalog(), CHECKSUM, NOW));
        RuntimeGrantId grantId = grant.id();
        ToolExecutionAudit first = ToolExecutionAudit.start(
                UUID.randomUUID(), owner, runtimeId, Optional.of(grantId), "client-a",
                CHECKSUM, "get_forecast", NOW);
        ToolExecutionAudit second = ToolExecutionAudit.start(
                UUID.randomUUID(), owner, runtimeId, Optional.empty(), "owner",
                CHECKSUM, "get_forecast", NOW.plusSeconds(1));
        store.startAudit(first);
        store.startAudit(second);
        ToolExecutionAudit completed = first.complete(
                AuditStatus.SUCCEEDED, Optional.empty(), Optional.of(200),
                12, 34, 56, NOW.plusSeconds(2));

        PostgresRuntimePolicyStore secondStore = new PostgresRuntimePolicyStore(jdbc.getDataSource());
        CountDownLatch completionStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> left = executor.submit(() -> {
                completionStart.await();
                return store.completeAudit(completed);
            });
            Future<Boolean> right = executor.submit(() -> {
                completionStart.await();
                return secondStore.completeAudit(completed);
            });
            completionStart.countDown();
            assertEquals(1, java.util.stream.Stream.of(left.get(), right.get()).filter(Boolean::booleanValue).count());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertFalse(store.completeAudit(completed));
        var page = store.listAudits(owner, runtimeId, 1, Optional.empty());
        assertEquals(java.util.List.of(second), page.items());
        assertTrue(page.nextCursor().isPresent());
        AuditCursor cursor = page.nextCursor().orElseThrow();
        assertEquals(java.util.List.of(completed),
                store.listAudits(owner, runtimeId, 10, Optional.of(cursor)).items());
        assertTrue(store.listAudits(account(), runtimeId, 10, Optional.empty()).items().isEmpty());
    }

    private ManagedRuntimeGrant grant(AccountId account, RuntimeInstanceId runtime) {
        return new ManagedRuntimeGrant(
                new RuntimeGrantId(UUID.randomUUID()), runtime, account, "client-a",
                Set.of("get_forecast"), 5, NOW, NOW.plusSeconds(3600), Optional.empty());
    }

    private AccountId account() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into account(id, issuer, subject, created_at, last_seen_at)
                values (?, 'https://issuer.example', ?, ?, ?)
                """, id, id.toString(), Timestamp.from(NOW), Timestamp.from(NOW));
        return new AccountId(id);
    }

    private RuntimeInstanceId runtime(AccountId account) {
        UUID specification = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID catalog = UUID.randomUUID();
        jdbc.update("""
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'UPLOAD', ?, ?, 10, 'weather', 'READY', ?, ?)
                """, specification, account.value(), "specifications/" + specification, CHECKSUM,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                insert into generation_job(
                    id, owner_account_id, specification_id, kind, operation, idempotency_key,
                    request_hash, request_snapshot, status, created_at, updated_at)
                values (?, ?, ?, 'GENERATION', 'generation', ?, ?, '{}'::jsonb, 'SUCCEEDED', ?, ?)
                """, job, account.value(), specification, job.toString(), CHECKSUM,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                insert into tool_catalog(
                    id, owner_account_id, generation_job_id, metadata_version,
                    specification_checksum, metadata_checksum, metadata_document,
                    tool_count, created_at)
                values (?, ?, ?, '1.0', ?, ?, '{}', 1, ?)
                """, catalog, account.value(), job, CHECKSUM, CHECKSUM, Timestamp.from(NOW));
        RuntimeInstanceId id = new RuntimeInstanceId(UUID.randomUUID());
        jdbc.update("""
                insert into managed_runtime_instance(
                    id, owner_account_id, catalog_id, catalog_checksum, token_digest, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id.value(), account.value(), catalog, CHECKSUM, digest((byte) 9).value(),
                Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(7200)));
        return id;
    }

    private RuntimeTokenDigest digest(byte fill) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, fill);
        return new RuntimeTokenDigest(value);
    }
}
