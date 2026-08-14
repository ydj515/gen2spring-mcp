package io.gen2spring.mcp.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
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
class PostgresHostedResourceStoreTest {
    private static final AccountId OWNER = new AccountId(UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9"));
    private static final AccountId OTHER = new AccountId(UUID.fromString("51dd3b69-589c-4466-a78e-d448407d17b9"));
    private static final SpecificationId SPECIFICATION =
            new SpecificationId(UUID.fromString("80782e7c-337d-4d4d-bd4d-ad478359563c"));
    private static final SpecificationId PENDING_SPECIFICATION =
            new SpecificationId(UUID.fromString("90782e7c-337d-4d4d-bd4d-ad478359563c"));
    private static final JobId JOB = new JobId(UUID.fromString("1a803410-a22a-4bc6-b951-7dbc301ae800"));
    private static final UUID ARTIFACT = UUID.fromString("6a803410-a22a-4bc6-b951-7dbc301ae800");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.9-alpine");

    private JdbcTemplate jdbc;
    private PostgresHostedResourceStore store;

    @BeforeEach
    void resetDatabase() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new PostgresHostedResourceStore(dataSource);
        fixture();
    }

    @Test
    void returnsOwnedSpecificationsJobsEventsAndArtifactsOnly() {
        assertEquals(1, store.specifications(OWNER, 10).size());
        assertEquals(SPECIFICATION, store.specification(OWNER, SPECIFICATION).orElseThrow().id());
        assertEquals(1, store.jobs(OWNER, 10).size());
        assertEquals(JOB, store.job(OWNER, JOB).orElseThrow().id());
        assertEquals(1, store.events(OWNER, JOB, 10).size());
        assertEquals(1, store.artifacts(OWNER, JOB).size());
        assertEquals(ARTIFACT, store.artifact(OWNER, ARTIFACT).orElseThrow().id());

        var specification = store.specification(OWNER, SPECIFICATION).orElseThrow();
        assertTrue(store.specifications(OWNER, 10, java.util.Optional.of(
                new io.gen2spring.mcp.application.hosted.query.HostedResourceStore.ResourceCursor(
                        specification.createdAt(), specification.id().value()))).isEmpty());
        var job = store.job(OWNER, JOB).orElseThrow();
        assertTrue(store.jobs(OWNER, 10, java.util.Optional.of(
                new io.gen2spring.mcp.application.hosted.query.HostedResourceStore.ResourceCursor(
                        job.createdAt(), job.id().value()))).isEmpty());

        assertTrue(store.specifications(OTHER, 10).isEmpty());
        assertTrue(store.specification(OTHER, SPECIFICATION).isEmpty());
        assertTrue(store.specification(OWNER, PENDING_SPECIFICATION).isEmpty());
        assertTrue(store.jobs(OTHER, 10).isEmpty());
        assertTrue(store.job(OTHER, JOB).isEmpty());
        assertTrue(store.events(OTHER, JOB, 10).isEmpty());
        assertTrue(store.artifacts(OTHER, JOB).isEmpty());
        assertTrue(store.artifact(OTHER, ARTIFACT).isEmpty());
    }

    private void fixture() {
        jdbc.update("""
                insert into account(id, issuer, subject, created_at, last_seen_at) values
                (?, 'https://issuer.example', 'owner', now(), now()),
                (?, 'https://issuer.example', 'other', now(), now())
                """, OWNER.value(), OTHER.value());
        jdbc.update("""
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'UPLOAD', 'specifications/80782e7c-337d-4d4d-bd4d-ad478359563c/source', repeat('a', 64), 10,
                        'weather.yaml', 'READY', now(), now())
                """, SPECIFICATION.value(), OWNER.value());
        jdbc.update("""
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'URL', 'specifications/90782e7c-337d-4d4d-bd4d-ad478359563c/source', repeat('d', 64), 10,
                        'pending import', 'PENDING', now() - interval '1 minute', now())
                """, PENDING_SPECIFICATION.value(), OWNER.value());
        jdbc.update("""
                insert into generation_job(
                    id, owner_account_id, specification_id, kind, operation,
                    idempotency_key, request_hash, request_snapshot, status,
                    stage, attempt, created_at, updated_at)
                values (?, ?, ?, 'GENERATION', 'generation', 'owner-key', repeat('b', 64),
                        '{}'::jsonb, 'SUCCEEDED', 'COMPLETE', 1, now(), now())
                """, JOB.value(), OWNER.value(), SPECIFICATION.value());
        jdbc.update("""
                insert into generation_job_event(
                    job_id, sequence, from_status, to_status, stage, safe_code, safe_summary, created_at)
                values (?, 1, 'RUNNING', 'SUCCEEDED', 'COMPLETE', null, 'completed', now())
                """, JOB.value());
        jdbc.update("""
                insert into artifact(
                    id, job_id, owner_account_id, type, object_key, sha256,
                    byte_size, content_type, created_at, expires_at)
                values (?, ?, ?, 'ZIP', 'artifacts/1a803410-a22a-4bc6-b951-7dbc301ae800/result', repeat('c', 64),
                        20, 'application/zip', now(), now() + interval '1 day')
                """, ARTIFACT, JOB.value(), OWNER.value());
    }
}
