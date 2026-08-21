package io.gen2spring.mcp.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
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

    @BeforeEach
    void resetDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
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
        assertEquals(6, flyway.migrate().migrationsExecuted);

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
                "tool_catalog",
                "tool_catalog_entry",
                "managed_runtime_instance",
                "managed_credential",
                "managed_runtime_credential_binding",
                "managed_runtime_grant",
                "managed_runtime_rate_window",
                "managed_tool_execution_audit",
                "worker_heartbeat"), tables);

        assertEquals(Set.of(
                        "tool_catalog_generation_owner_fk",
                        "tool_catalog_generation_unique",
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
}
