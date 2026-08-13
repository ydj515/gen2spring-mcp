package io.gen2spring.mcp.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresArtifactRetentionStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.9-alpine");

    private JdbcTemplate jdbc;
    private PostgresArtifactRetentionStore store;

    @BeforeEach
    void resetDatabase() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new PostgresArtifactRetentionStore(dataSource);
        fixture();
    }

    @Test
    void returnsOneBoundedExpiryBatchAndPreservesReferencedSourceObjects() {
        var expired = store.findExpired(NOW, 10);

        assertEquals(2, expired.size());
        var archive = expired.stream().filter(value -> !value.referencedBySpecification()).findFirst().orElseThrow();
        var source = expired.stream().filter(value -> value.referencedBySpecification()).findFirst().orElseThrow();
        assertTrue(store.deleteExpired(archive.id(), archive.objectKey(), NOW, true));
        assertTrue(store.deleteExpired(source.id(), source.objectKey(), NOW, false));
        assertEquals(0, jdbc.queryForObject("select count(*) from artifact", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from specification", Integer.class));
        assertFalse(store.deleteExpired(source.id(), source.objectKey(), NOW, false));
    }

    private void fixture() {
        UUID owner = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        ObjectKey source = ObjectKey.parse("specifications/" + job + "/source");
        jdbc.update("insert into account(id, issuer, subject, created_at, last_seen_at) values (?, 'issuer', 'subject', ?, ?)",
                owner, Timestamp.from(NOW.minusSeconds(10)), Timestamp.from(NOW.minusSeconds(10)));
        jdbc.update("""
                insert into specification(id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'URL', ?, repeat('b',64), 10, 'source', 'READY', ?, ?)
                """, job, owner, source.value(), Timestamp.from(NOW.minusSeconds(10)), Timestamp.from(NOW.minusSeconds(10)));
        jdbc.update("""
                insert into generation_job(id, owner_account_id, specification_id, kind, operation, idempotency_key,
                    request_hash, request_snapshot, status, created_at, updated_at)
                values (?, ?, ?, 'SPEC_IMPORT', 'import', 'key', repeat('a',64), '{}'::jsonb, 'SUCCEEDED', ?, ?)
                """, job, owner, job, Timestamp.from(NOW.minusSeconds(10)), Timestamp.from(NOW.minusSeconds(10)));
        insertArtifact(UUID.randomUUID(), job, owner, source.value());
        insertArtifact(UUID.randomUUID(), job, owner, "artifacts/" + job + "/archive");
    }

    private void insertArtifact(UUID id, UUID job, UUID owner, String key) {
        jdbc.update("""
                insert into artifact(id, job_id, owner_account_id, type, object_key, sha256, byte_size,
                    content_type, created_at, expires_at)
                values (?, ?, ?, 'ZIP', ?, repeat('c',64), 10, 'application/zip', ?, ?)
                """, id, job, owner, key, Timestamp.from(NOW.minusSeconds(10)), Timestamp.from(NOW.minusSeconds(1)));
    }
}
