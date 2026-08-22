package io.gen2spring.mcp.adapter.persistence;

import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionKind.MIGRATION;
import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionKind.ROLLBACK;
import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionOutcome.APPLIED;
import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionOutcome.BLOCKED;
import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionOutcome.CONFLICT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.MigrationCommand;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionOutcome;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class PostgresRuntimeCatalogTransitionStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
    private static final String SOURCE_CHECKSUM = "a".repeat(64);
    private static final String TARGET_CHECKSUM = "b".repeat(64);
    private static final String SECOND_TARGET_CHECKSUM = "c".repeat(64);
    private static final String DIFF_CHECKSUM = "d".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.9-alpine");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private PostgresRuntimeCatalogTransitionStore store;

    @BeforeEach
    void resetDatabase() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new PostgresRuntimeCatalogTransitionStore(dataSource);
    }

    @Test
    void appliesMigrationAndRollbackWhilePreservingAllOtherRuntimeState() {
        Fixture fixture = fixture();
        insertGrant(fixture, Set.of("alpha"));
        insertCredentialBinding(fixture);
        insertRateWindow(fixture);
        insertAudit(fixture);
        Map<String, String> before = protectedState(fixture);

        var migrated = store.apply(command(
                fixture, fixture.source(), SOURCE_CHECKSUM, fixture.target(), TARGET_CHECKSUM,
                Set.of("alpha", "beta")), MIGRATION);

        assertEquals(APPLIED, migrated.outcome());
        assertEquals(fixture.runtimeId(), migrated.instance().orElseThrow().id());
        assertEquals(fixture.target(), migrated.instance().orElseThrow().catalogId());
        assertEquals(before, protectedState(fixture));
        assertEquals(List.of(1L), store.history(
                fixture.owner(), fixture.runtimeId(), 10, Optional.empty()).items().stream()
                .map(transition -> transition.sequence()).toList());
        assertEquals(1L, store.findRollbackCandidate(
                fixture.owner(), fixture.runtimeId(), fixture.target()).orElseThrow().sequence());

        var rolledBack = store.apply(command(
                fixture, fixture.target(), TARGET_CHECKSUM, fixture.source(), SOURCE_CHECKSUM,
                Set.of("alpha")), ROLLBACK);

        assertEquals(APPLIED, rolledBack.outcome());
        assertEquals(fixture.source(), rolledBack.instance().orElseThrow().catalogId());
        assertEquals(before, protectedState(fixture));
        assertEquals(List.of(2L, 1L), store.history(
                fixture.owner(), fixture.runtimeId(), 10, Optional.empty()).items().stream()
                .map(transition -> transition.sequence()).toList());
        assertTrue(store.findRollbackCandidate(
                fixture.owner(), fixture.runtimeId(), fixture.source()).isEmpty());
    }

    @Test
    void blocksTransitionWhenAnActiveGrantNamesAToolAbsentFromTheTarget() {
        Fixture fixture = fixture();
        assertEquals(APPLIED, store.apply(command(
                fixture, fixture.source(), SOURCE_CHECKSUM, fixture.target(), TARGET_CHECKSUM,
                Set.of("alpha", "beta")), MIGRATION).outcome());
        insertGrant(fixture, Set.of("beta"));

        assertEquals(BLOCKED, store.apply(command(
                fixture, fixture.target(), TARGET_CHECKSUM, fixture.source(), SOURCE_CHECKSUM,
                Set.of("alpha")), ROLLBACK).outcome());

        assertEquals(fixture.target(), currentCatalog(fixture));
        assertEquals(1, transitionCount(fixture));
    }

    @Test
    void letsOnlyOneConcurrentCompareAndSetMigrationWin() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch start = new CountDownLatch(1);
        PostgresRuntimeCatalogTransitionStore other = new PostgresRuntimeCatalogTransitionStore(dataSource);
        MigrationCommand first = command(
                fixture, fixture.source(), SOURCE_CHECKSUM, fixture.target(), TARGET_CHECKSUM,
                Set.of("alpha", "beta"));
        MigrationCommand second = command(
                fixture, fixture.source(), SOURCE_CHECKSUM, fixture.secondTarget(), SECOND_TARGET_CHECKSUM,
                Set.of("alpha", "gamma"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<TransitionOutcome> left = executor.submit(() -> {
                start.await();
                return store.apply(first, MIGRATION).outcome();
            });
            Future<TransitionOutcome> right = executor.submit(() -> {
                start.await();
                return other.apply(second, MIGRATION).outcome();
            });
            start.countDown();
            assertEquals(Set.of(APPLIED, CONFLICT), Set.of(left.get(), right.get()));
        }

        assertEquals(1, transitionCount(fixture));
        assertTrue(Set.of(fixture.target(), fixture.secondTarget()).contains(currentCatalog(fixture)));
    }

    @Test
    void serializesGrantCreationWithATransitionThatRemovesTheGrantedTool() throws Exception {
        Fixture fixture = fixture();
        PostgresRuntimePolicyStore policies = new PostgresRuntimePolicyStore(dataSource);
        ManagedRuntimeGrant grant = new ManagedRuntimeGrant(
                new RuntimeGrantId(UUID.randomUUID()), fixture.runtimeId(), fixture.owner(), "client-beta",
                Set.of("beta"), 60, NOW.minusSeconds(10), NOW.plusSeconds(1800), Optional.empty());
        MigrationCommand migration = command(
                fixture, fixture.source(), SOURCE_CHECKSUM, fixture.target(), TARGET_CHECKSUM, Set.of("alpha"));
        CountDownLatch start = new CountDownLatch(1);

        boolean created;
        TransitionOutcome outcome;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> grantResult = executor.submit(() -> {
                start.await();
                return policies.createGrant(
                        grant, new RuntimeTokenDigest(bytes(32, 0x31)),
                        fixture.source(), SOURCE_CHECKSUM, NOW);
            });
            Future<TransitionOutcome> migrationResult = executor.submit(() -> {
                start.await();
                return store.apply(migration, MIGRATION).outcome();
            });
            start.countDown();
            created = grantResult.get();
            outcome = migrationResult.get();
        }

        if (created) {
            assertEquals(BLOCKED, outcome);
            assertEquals(fixture.source(), currentCatalog(fixture));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from managed_runtime_grant where runtime_id = ?",
                    Integer.class, fixture.runtimeId().value()));
        } else {
            assertEquals(APPLIED, outcome);
            assertEquals(fixture.target(), currentCatalog(fixture));
            assertFalse(jdbc.queryForObject(
                    "select exists(select 1 from managed_runtime_grant where runtime_id = ?)",
                    Boolean.class, fixture.runtimeId().value()));
        }
    }

    private Fixture fixture() {
        UUID ownerId = UUID.randomUUID();
        AccountId owner = new AccountId(ownerId);
        UUID specification = UUID.randomUUID();
        jdbc.update("""
                insert into account(id, issuer, subject, created_at, last_seen_at)
                values (?, 'https://issuer.example', ?, ?, ?)
                """, ownerId, ownerId.toString(), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'UPLOAD', ?, ?, 10, 'migration', 'READY', ?, ?)
                """, specification, ownerId, "specifications/" + specification, SOURCE_CHECKSUM,
                Timestamp.from(NOW), Timestamp.from(NOW));

        UUID source = insertRootCatalog(owner, specification, SOURCE_CHECKSUM);
        UUID target = insertChildCatalog(owner, specification, source, source, 2, TARGET_CHECKSUM);
        UUID secondTarget = insertChildCatalog(owner, specification, source, target, 3, SECOND_TARGET_CHECKSUM);
        RuntimeInstanceId runtimeId = new RuntimeInstanceId(UUID.randomUUID());
        jdbc.update("""
                insert into managed_runtime_instance(
                    id, owner_account_id, catalog_id, catalog_checksum, token_digest,
                    created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, runtimeId.value(), ownerId, source, SOURCE_CHECKSUM, bytes(32, 0x11),
                Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.plusSeconds(3600)));
        return new Fixture(owner, runtimeId, specification, source, target, secondTarget);
    }

    private UUID insertRootCatalog(AccountId owner, UUID specification, String checksum) {
        UUID catalog = UUID.randomUUID();
        UUID job = insertJob(owner, specification, Optional.empty());
        jdbc.update("""
                insert into tool_catalog(
                    id, owner_account_id, generation_job_id, metadata_version,
                    specification_checksum, metadata_checksum, metadata_document,
                    tool_count, created_at)
                values (?, ?, ?, '1.0', ?, ?, '{}', 1, ?)
                """, catalog, owner.value(), job, SOURCE_CHECKSUM, checksum, Timestamp.from(NOW));
        return catalog;
    }

    private UUID insertChildCatalog(
            AccountId owner,
            UUID specification,
            UUID family,
            UUID predecessor,
            long revision,
            String checksum) {
        UUID catalog = UUID.randomUUID();
        UUID job = insertJob(owner, specification, Optional.of(predecessor));
        jdbc.update("""
                insert into tool_catalog(
                    id, owner_account_id, generation_job_id, metadata_version,
                    specification_checksum, metadata_checksum, metadata_document,
                    tool_count, created_at, family_id, revision, predecessor_catalog_id)
                values (?, ?, ?, '1.0', ?, ?, '{}', 1, ?, ?, ?, ?)
                """, catalog, owner.value(), job, SOURCE_CHECKSUM, checksum,
                Timestamp.from(NOW.plusSeconds(revision)), family, revision, predecessor);
        jdbc.update("""
                update tool_catalog_family
                   set head_catalog_id = ?, updated_at = ?
                 where id = ? and owner_account_id = ?
                """, catalog, Timestamp.from(NOW.plusSeconds(revision)), family, owner.value());
        return catalog;
    }

    private UUID insertJob(AccountId owner, UUID specification, Optional<UUID> predecessor) {
        UUID job = UUID.randomUUID();
        jdbc.update("""
                insert into generation_job(
                    id, owner_account_id, specification_id, kind, operation, idempotency_key,
                    request_hash, request_snapshot, status, created_at, updated_at, predecessor_catalog_id)
                values (?, ?, ?, 'GENERATION', 'generation', ?, ?, '{}'::jsonb, 'SUCCEEDED', ?, ?, ?)
                """, job, owner.value(), specification, job.toString(), SOURCE_CHECKSUM,
                Timestamp.from(NOW), Timestamp.from(NOW), predecessor.orElse(null));
        return job;
    }

    private MigrationCommand command(
            Fixture fixture,
            UUID expected,
            String expectedChecksum,
            UUID target,
            String targetChecksum,
            Set<String> targetTools) {
        return new MigrationCommand(
                fixture.owner(), fixture.runtimeId(), expected, expectedChecksum, target,
                targetChecksum, DIFF_CHECKSUM, targetTools, NOW);
    }

    private void insertGrant(Fixture fixture, Set<String> tools) {
        UUID grant = UUID.randomUUID();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into managed_runtime_grant(
                        id, runtime_id, owner_account_id, principal, allowed_tools,
                        requests_per_minute, token_digest, created_at, expires_at)
                    values (?, ?, ?, 'client', ?, 60, ?, ?, ?)
                    """);
            statement.setObject(1, grant);
            statement.setObject(2, fixture.runtimeId().value());
            statement.setObject(3, fixture.owner().value());
            statement.setArray(4, connection.createArrayOf("text", tools.toArray(String[]::new)));
            statement.setBytes(5, bytes(32, 0x22));
            statement.setTimestamp(6, Timestamp.from(NOW.minusSeconds(30)));
            statement.setTimestamp(7, Timestamp.from(NOW.plusSeconds(1800)));
            return statement;
        });
    }

    private void insertCredentialBinding(Fixture fixture) {
        UUID credential = UUID.randomUUID();
        jdbc.update("""
                insert into managed_credential(
                    id, owner_account_id, label, kind, credential_version, envelope_version, key_id,
                    wrapped_key_nonce, wrapped_key, payload_nonce, ciphertext, created_at, rotated_at)
                values (?, ?, 'provider', 'OPAQUE', 7, 1, 'key-1', ?, ?, ?, ?, ?, ?)
                """, credential, fixture.owner().value(), bytes(12, 1), bytes(48, 2), bytes(12, 3), bytes(32, 4),
                Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.minusSeconds(60)));
        jdbc.update("""
                insert into managed_runtime_credential_binding(
                    runtime_id, owner_account_id, credential_slot, credential_id, credential_version)
                values (?, ?, 'service_key', ?, 7)
                """, fixture.runtimeId().value(), fixture.owner().value(), credential);
    }

    private void insertRateWindow(Fixture fixture) {
        jdbc.update("""
                insert into managed_runtime_rate_window(runtime_id, access_key, window_start, request_count)
                values (?, 'owner', ?, 11)
                """, fixture.runtimeId().value(), Timestamp.from(NOW.minusSeconds(10)));
    }

    private void insertAudit(Fixture fixture) {
        jdbc.update("""
                insert into managed_tool_execution_audit(
                    execution_id, owner_account_id, runtime_id, principal, catalog_checksum,
                    tool_name, status, provider_status, duration_millis, request_bytes,
                    response_bytes, started_at, completed_at)
                values (?, ?, ?, 'owner', ?, 'alpha', 'SUCCEEDED', 200, 12, 4, 8, ?, ?)
                """, UUID.randomUUID(), fixture.owner().value(), fixture.runtimeId().value(), SOURCE_CHECKSUM,
                Timestamp.from(NOW.minusSeconds(20)), Timestamp.from(NOW.minusSeconds(19)));
    }

    private Map<String, String> protectedState(Fixture fixture) {
        return Map.of(
                "runtime", scalar("""
                        select jsonb_build_object(
                            'token', encode(token_digest, 'hex'), 'provider', provider_base_url,
                            'created', created_at, 'expires', expires_at, 'revoked', revoked_at)::text
                          from managed_runtime_instance where id = ?
                        """, fixture.runtimeId().value()),
                "binding", rows("""
                        select jsonb_build_object('slot', credential_slot, 'id', credential_id,
                            'version', credential_version)::text
                          from managed_runtime_credential_binding where runtime_id = ? order by credential_slot
                        """, fixture.runtimeId().value()),
                "grant", rows("""
                        select jsonb_build_object('id', id, 'tools', allowed_tools, 'rate', requests_per_minute,
                            'token', encode(token_digest, 'hex'), 'created', created_at,
                            'expires', expires_at, 'revoked', revoked_at)::text
                          from managed_runtime_grant where runtime_id = ? order by id
                        """, fixture.runtimeId().value()),
                "rate", rows("""
                        select jsonb_build_object('key', access_key, 'window', window_start,
                            'count', request_count)::text
                          from managed_runtime_rate_window where runtime_id = ? order by access_key
                        """, fixture.runtimeId().value()),
                "audit", rows("""
                        select jsonb_build_object('id', execution_id, 'checksum', catalog_checksum,
                            'tool', tool_name, 'status', status, 'started', started_at,
                            'completed', completed_at)::text
                          from managed_tool_execution_audit where runtime_id = ? order by execution_id
                        """, fixture.runtimeId().value()));
    }

    private String scalar(String sql, Object argument) {
        return jdbc.queryForObject(sql, String.class, argument);
    }

    private String rows(String sql, Object argument) {
        return jdbc.queryForList(sql, String.class, argument).toString();
    }

    private UUID currentCatalog(Fixture fixture) {
        return jdbc.queryForObject(
                "select catalog_id from managed_runtime_instance where id = ?",
                UUID.class, fixture.runtimeId().value());
    }

    private int transitionCount(Fixture fixture) {
        return jdbc.queryForObject(
                "select count(*) from managed_runtime_catalog_transition where runtime_id = ?",
                Integer.class, fixture.runtimeId().value());
    }

    private byte[] bytes(int size, int value) {
        byte[] result = new byte[size];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private record Fixture(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            UUID specification,
            UUID source,
            UUID target,
            UUID secondTarget) {}
}
