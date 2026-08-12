package io.gen2spring.mcp.app.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.adapter.persistence.PostgresAccountStore;
import io.gen2spring.mcp.adapter.persistence.PostgresJobQueue;
import io.gen2spring.mcp.application.hosted.job.CreateJob;
import io.gen2spring.mcp.application.hosted.job.JobArtifact;
import io.gen2spring.mcp.application.hosted.job.JobCompletion;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.JobQuota;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
class WorkerCrashRecoveryIntegrationTest {
    private static final Instant START = Instant.parse("2026-08-13T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.9-alpine");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void expiresTheCrashedLeaseRejectsItsLateWriteAndCompletesOnceWithANewFence() {
        PostgresJobQueue initialQueue = queueAt(START);
        AccountId owner = new PostgresAccountStore(dataSource).findOrCreate(
                "https://issuer.example", "subject-1", START);
        UUID specification = UUID.randomUUID();
        jdbc.update("""
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'UPLOAD', ?, ?, 10, 'weather', 'READY', ?, ?)
                """,
                specification,
                owner.value(),
                "specifications/" + specification,
                "a".repeat(64),
                Timestamp.from(START),
                Timestamp.from(START));
        var created = initialQueue.create(new CreateJob(
                owner,
                JobKind.GENERATION,
                "generation",
                "crash-recovery",
                "b".repeat(64),
                "{\"configuration\":{\"targetProfileId\":\"spring-ai-2.0-java21-mvc-streamable\"},"
                        + "\"specificationObjectKey\":\"specifications/" + specification + "\"}",
                Optional.of(new io.gen2spring.mcp.domain.platform.specification.SpecificationId(specification)),
                new JobQuota(2, 10)));

        JobLease crashed = initialQueue.claim(
                        new WorkerId("worker-crashed"), START, Duration.ofSeconds(1))
                .orElseThrow();
        Instant recoveredAt = START.plusSeconds(2);
        PostgresJobQueue recoveryQueue = queueAt(recoveredAt);

        assertEquals(1, recoveryQueue.recoverExpired(recoveredAt, 3));
        JobLease retry = recoveryQueue.claim(
                        new WorkerId("worker-retry"), recoveredAt, Duration.ofSeconds(30))
                .orElseThrow();

        assertEquals(2, retry.fencingToken());
        assertFalse(recoveryQueue.complete(
                crashed,
                JobCompletion.success(),
                List.of(artifact(crashed, recoveredAt.plus(Duration.ofDays(30))))));
        assertTrue(recoveryQueue.complete(
                retry,
                JobCompletion.success(),
                List.of(artifact(retry, recoveredAt.plus(Duration.ofDays(30))))));
        assertFalse(recoveryQueue.complete(retry, JobCompletion.success()));
        assertEquals(JobStatus.SUCCEEDED, recoveryQueue.find(owner, created.job().id()).orElseThrow().status());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from generation_job_event where job_id = ? and to_status = 'SUCCEEDED'",
                Integer.class,
                created.job().id().value()));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from artifact where job_id = ?",
                Integer.class,
                created.job().id().value()));
        assertEquals(
                "artifacts/" + created.job().id().value() + "/2-archive",
                jdbc.queryForObject(
                        "select object_key from artifact where job_id = ?",
                        String.class,
                        created.job().id().value()));
    }

    @Test
    void publishesSuccessfulImportsAsOwnedSpecificationsInTheCompletionTransaction() {
        PostgresJobQueue jobs = queueAt(START);
        AccountId owner = new PostgresAccountStore(dataSource).findOrCreate(
                "https://issuer.example", "subject-import", START);
        var created = jobs.create(new CreateJob(
                owner,
                JobKind.SPEC_IMPORT,
                "specification-import",
                "import-once",
                "d".repeat(64),
                "{}",
                Optional.empty(),
                new JobQuota(2, 10)));
        JobLease lease = jobs.claim(new WorkerId("worker-import"), START, Duration.ofSeconds(30)).orElseThrow();
        ObjectKey key = ObjectKey.parse(
                "specifications/" + lease.jobId().value() + "/" + lease.fencingToken() + "-source");

        assertTrue(jobs.complete(
                lease,
                JobCompletion.success(),
                List.of(new JobArtifact(
                        "SOURCE",
                        key,
                        "e".repeat(64),
                        42,
                        "application/yaml",
                        START.plus(Duration.ofDays(30))))));

        var completed = jobs.find(owner, created.job().id()).orElseThrow();
        assertEquals(JobStatus.SUCCEEDED, completed.status());
        assertEquals(created.job().id().value(), completed.specificationId().orElseThrow().value());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from specification where id = ? and owner_account_id = ? and object_key = ?",
                Integer.class,
                created.job().id().value(),
                owner.value(),
                key.value()));
    }

    private JobArtifact artifact(JobLease lease, Instant expiresAt) {
        return new JobArtifact(
                "ARCHIVE",
                ObjectKey.parse("artifacts/" + lease.jobId().value() + "/" + lease.fencingToken() + "-archive"),
                "c".repeat(64),
                10,
                "application/zip",
                expiresAt);
    }

    private PostgresJobQueue queueAt(Instant instant) {
        return new PostgresJobQueue(dataSource, Clock.fixed(instant, ZoneOffset.UTC));
    }
}
