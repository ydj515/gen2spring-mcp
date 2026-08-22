package io.gen2spring.mcp.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresMigrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.9-alpine");

    private Flyway flyway;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void resetDatabase() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        flyway = Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void createsTheHostedSchemaExactlyOnce() {
        assertEquals(7, flyway.migrate().migrationsExecuted);

        Set<String> tables = jdbc.queryForList(
                        """
                        select table_name
                          from information_schema.tables
                         where table_schema = 'public'
                           and table_name <> 'flyway_schema_history'
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "account",
                "specification",
                "generation_job",
                "generation_job_event",
                "artifact",
                "tool_catalog_family",
                "tool_catalog",
                "tool_catalog_entry",
                "managed_runtime_instance",
                "managed_runtime_catalog_transition",
                "managed_credential",
                "managed_runtime_credential_binding",
                "managed_runtime_grant",
                "managed_runtime_rate_window",
                "managed_tool_execution_audit",
                "worker_heartbeat"), tables);

        assertEquals(Set.of(
                        "tool_catalog_generation_owner_fk",
                        "tool_catalog_generation_unique",
                        "tool_catalog_family_owner_fk",
                        "tool_catalog_family_revision_unique",
                        "tool_catalog_predecessor_family_fk",
                        "tool_catalog_revision_shape_valid",
                        "tool_catalog_metadata_checksum_valid",
                        "tool_catalog_tool_count_valid"),
                jdbc.queryForList(
                                """
                                select constraint_name
                                  from information_schema.table_constraints
                                 where table_schema = 'public'
                                   and table_name = 'tool_catalog'
                                   and constraint_name in (
                                       'tool_catalog_generation_owner_fk',
                                       'tool_catalog_generation_unique',
                                       'tool_catalog_family_owner_fk',
                                       'tool_catalog_family_revision_unique',
                                       'tool_catalog_predecessor_family_fk',
                                       'tool_catalog_revision_shape_valid',
                                       'tool_catalog_metadata_checksum_valid',
                                       'tool_catalog_tool_count_valid')
                                """,
                                String.class)
                        .stream()
                        .collect(Collectors.toSet()));

        assertEquals(Set.of(
                        "managed_runtime_binding_runtime_owner_fk",
                        "managed_runtime_binding_credential_owner_fk"),
                jdbc.queryForList(
                                """
                                select constraint_name
                                  from information_schema.table_constraints
                                 where table_schema = 'public'
                                   and table_name = 'managed_runtime_credential_binding'
                                   and constraint_name in (
                                       'managed_runtime_binding_runtime_owner_fk',
                                       'managed_runtime_binding_credential_owner_fk')
                                """,
                                String.class)
                        .stream()
                        .collect(Collectors.toSet()));
        assertEquals(1, jdbc.queryForObject(
                """
                select count(*)
                  from pg_indexes
                 where schemaname = 'public'
                   and tablename = 'tool_catalog'
                   and indexname = 'tool_catalog_owner_created_idx'
                """, Integer.class));

        assertEquals(Set.of(
                        "managed_runtime_catalog_owner_fk",
                        "managed_runtime_digest_valid",
                        "managed_runtime_lifetime_valid"),
                jdbc.queryForList(
                                """
                                select constraint_name
                                  from information_schema.table_constraints
                                 where table_schema = 'public'
                                   and table_name = 'managed_runtime_instance'
                                   and constraint_name in (
                                       'managed_runtime_catalog_owner_fk',
                                       'managed_runtime_digest_valid',
                                       'managed_runtime_lifetime_valid')
                                """,
                                String.class)
                        .stream()
                        .collect(Collectors.toSet()));

        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void backfillsExistingCatalogsAsIndependentRevisionOneFamilies() {
        Flyway.configure()
                .dataSource(dataSource)
                .target(MigrationVersion.fromVersion("6"))
                .load()
                .migrate();

        UUID ownerId = UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9");
        UUID specificationId = UUID.fromString("80782e7c-337d-4d4d-bd4d-ad478359563c");
        UUID generationId = UUID.fromString("1a803410-a22a-4bc6-b951-7dbc301ae800");
        UUID catalogId = UUID.fromString("2c7fab42-1acd-4f90-bd3f-f7de5ec81edb");
        jdbc.update(
                """
                insert into account(id, issuer, subject, created_at, last_seen_at)
                values (?, 'https://issuer.example', 'subject-1', now(), now())
                """,
                ownerId);
        jdbc.update(
                """
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'UPLOAD', 'specifications/object', repeat('a', 64), 10,
                    'weather', 'READY', now(), now())
                """,
                specificationId,
                ownerId);
        jdbc.update(
                """
                insert into generation_job(
                    id, owner_account_id, specification_id, kind, operation,
                    idempotency_key, request_hash, request_snapshot, status,
                    created_at, updated_at)
                values (?, ?, ?, 'GENERATION', 'generation', 'key-1', repeat('b', 64),
                    '{}'::jsonb, 'SUCCEEDED', now(), now())
                """,
                generationId,
                ownerId,
                specificationId);
        jdbc.update(
                """
                insert into tool_catalog(
                    id, owner_account_id, generation_job_id, metadata_version,
                    specification_checksum, metadata_checksum, metadata_document,
                    tool_count, created_at)
                values (?, ?, ?, '1.0', repeat('a', 64), repeat('c', 64), '{}', 1, now())
                """,
                catalogId,
                ownerId,
                generationId);

        assertEquals(1, flyway.migrate().migrationsExecuted);

        assertEquals(catalogId, jdbc.queryForObject(
                "select family_id from tool_catalog where id = ?", UUID.class, catalogId));
        assertEquals(1L, jdbc.queryForObject(
                "select revision from tool_catalog where id = ?", Long.class, catalogId));
        assertNull(jdbc.queryForObject(
                "select predecessor_catalog_id from tool_catalog where id = ?", UUID.class, catalogId));
        assertEquals(catalogId, jdbc.queryForObject(
                "select head_catalog_id from tool_catalog_family where id = ?", UUID.class, catalogId));
        assertNull(jdbc.queryForObject(
                "select predecessor_catalog_id from generation_job where id = ?", UUID.class, generationId));
    }

    @Test
    void enforcesCatalogLineageOwnershipAndAppendOnlyRuntimeTransitions() {
        flyway.migrate();
        UUID owner = UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9");
        UUID otherOwner = UUID.fromString("da578a53-ece6-4718-b06d-5e243ef3aa7d");
        insertAccount(owner, "subject-1");
        insertAccount(otherOwner, "subject-2");

        UUID rootCatalog = createRootCatalog(owner, "root-a", "00000000-0000-0000-0000-000000000001");
        UUID otherFamilyRoot = createRootCatalog(owner, "root-b", "00000000-0000-0000-0000-000000000002");
        UUID foreignRoot = createRootCatalog(otherOwner, "foreign", "00000000-0000-0000-0000-000000000003");

        UUID childJob = createGeneration(owner, "child", rootCatalog);
        UUID childCatalog = UUID.fromString("00000000-0000-0000-0000-000000000011");
        insertCatalog(childCatalog, owner, childJob, rootCatalog, 2, rootCatalog);

        UUID duplicateRevisionJob = createGeneration(owner, "duplicate", rootCatalog);
        assertThrows(RuntimeException.class, () -> insertCatalog(
                UUID.fromString("00000000-0000-0000-0000-000000000012"),
                owner, duplicateRevisionJob, rootCatalog, 2, rootCatalog));

        UUID missingPredecessorJob = createGeneration(owner, "missing-predecessor", rootCatalog);
        assertThrows(RuntimeException.class, () -> insertCatalog(
                UUID.fromString("00000000-0000-0000-0000-000000000013"),
                owner, missingPredecessorJob, rootCatalog, 3, null));

        UUID crossFamilyJob = createGeneration(owner, "cross-family", otherFamilyRoot);
        assertThrows(RuntimeException.class, () -> insertCatalog(
                UUID.fromString("00000000-0000-0000-0000-000000000014"),
                owner, crossFamilyJob, rootCatalog, 3, otherFamilyRoot));

        UUID foreignOwnerJob = createGeneration(otherOwner, "foreign-owner", null);
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "update generation_job set predecessor_catalog_id = ? where id = ?",
                rootCatalog,
                foreignOwnerJob));
        assertEquals(foreignRoot, jdbc.queryForObject(
                "select head_catalog_id from tool_catalog_family where id = ?", UUID.class, foreignRoot));

        UUID runtimeId = UUID.fromString("00000000-0000-0000-0000-000000000021");
        jdbc.update(
                """
                insert into managed_runtime_instance(
                    id, owner_account_id, catalog_id, catalog_checksum, token_digest,
                    created_at, expires_at)
                values (?, ?, ?, repeat('c', 64), decode(repeat('de', 32), 'hex'),
                    now(), now() + interval '1 day')
                """,
                runtimeId,
                owner,
                rootCatalog);
        jdbc.update(
                """
                insert into managed_runtime_catalog_transition(
                    runtime_id, sequence, owner_account_id, source_catalog_id,
                    source_catalog_checksum, target_catalog_id, target_catalog_checksum,
                    diff_checksum, transition_kind, created_at)
                values (?, 1, ?, ?, repeat('c', 64), ?, repeat('d', 64),
                    repeat('e', 64), 'MIGRATION', now())
                """,
                runtimeId,
                owner,
                rootCatalog,
                childCatalog);

        assertThrows(RuntimeException.class, () -> jdbc.update(
                "update managed_runtime_catalog_transition set transition_kind = 'ROLLBACK' where runtime_id = ?",
                runtimeId));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "delete from managed_runtime_catalog_transition where runtime_id = ?", runtimeId));
    }

    @Test
    void rejectsInvalidOwnedRecordsAtTheDatabaseBoundary() {
        flyway.migrate();
        jdbc.update("""
                insert into account(id, issuer, subject, created_at, last_seen_at)
                values ('41dd3b69-589c-4466-a78e-d448407d17b9', 'https://issuer.example', 'subject-1', now(), now())
                """);

        assertThrows(RuntimeException.class, () -> jdbc.update("""
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (
                    '80782e7c-337d-4d4d-bd4d-ad478359563c',
                    '41dd3b69-589c-4466-a78e-d448407d17b9',
                    'PRIVATE', 'specifications/object', repeat('a', 64), 10,
                    'weather', 'READY', now(), now())
                """));

        assertThrows(RuntimeException.class, () -> jdbc.update("""
                insert into generation_job(
                    id, owner_account_id, specification_id, kind, operation,
                    idempotency_key, request_hash, request_snapshot, status,
                    created_at, updated_at)
                values (
                    '1a803410-a22a-4bc6-b951-7dbc301ae800',
                    '41dd3b69-589c-4466-a78e-d448407d17b9', null,
                    'GENERATION', 'generation', 'key-1', repeat('b', 64), '{}'::jsonb,
                    'QUEUED', now(), now())
                """));
    }

    private void insertAccount(UUID ownerId, String subject) {
        jdbc.update(
                """
                insert into account(id, issuer, subject, created_at, last_seen_at)
                values (?, 'https://issuer.example', ?, now(), now())
                """,
                ownerId,
                subject);
    }

    private UUID createRootCatalog(UUID ownerId, String key, String catalogId) {
        UUID generationId = createGeneration(ownerId, key, null);
        UUID id = UUID.fromString(catalogId);
        insertCatalog(id, ownerId, generationId, id, 1, null);
        jdbc.update("update tool_catalog_family set head_catalog_id = ? where id = ?", id, id);
        return id;
    }

    private UUID createGeneration(UUID ownerId, String key, UUID predecessorCatalogId) {
        UUID specificationId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        jdbc.update(
                """
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'UPLOAD', ?, repeat('a', 64), 10,
                    'weather', 'READY', now(), now())
                """,
                specificationId,
                ownerId,
                "specifications/" + specificationId);
        jdbc.update(
                """
                insert into generation_job(
                    id, owner_account_id, specification_id, predecessor_catalog_id,
                    kind, operation, idempotency_key, request_hash, request_snapshot,
                    status, created_at, updated_at)
                values (?, ?, ?, ?, 'GENERATION', 'generation', ?, repeat('b', 64),
                    '{}'::jsonb, 'SUCCEEDED', now(), now())
                """,
                generationId,
                ownerId,
                specificationId,
                predecessorCatalogId,
                key);
        return generationId;
    }

    private void insertCatalog(
            UUID catalogId,
            UUID ownerId,
            UUID generationId,
            UUID familyId,
            long revision,
            UUID predecessorCatalogId) {
        if (revision == 1) {
            jdbc.update(
                    """
                    insert into tool_catalog_family(
                        id, owner_account_id, head_catalog_id, created_at, updated_at)
                    values (?, ?, null, now(), now())
                    """,
                    familyId,
                    ownerId);
        }
        jdbc.update(
                """
                insert into tool_catalog(
                    id, owner_account_id, generation_job_id, family_id, revision,
                    predecessor_catalog_id, metadata_version, specification_checksum,
                    metadata_checksum, metadata_document, tool_count, created_at)
                values (?, ?, ?, ?, ?, ?, '1.0', repeat('a', 64), repeat('c', 64),
                    '{}', 1, now())
                """,
                catalogId,
                ownerId,
                generationId,
                familyId,
                revision,
                predecessorCatalogId);
    }
}
