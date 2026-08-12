package io.gen2spring.mcp.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.job.CreateJob;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.hosted.job.JobQueue;
import io.gen2spring.mcp.application.hosted.job.JobQuota;
import io.gen2spring.mcp.application.hosted.specification.SpecificationCatalog;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class PostgresJobQueueTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.9-alpine");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private PostgresAccountStore accounts;
    private PostgresSpecificationCatalog specifications;
    private PostgresJobQueue jobs;

    @BeforeEach
    void resetDatabase() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        accounts = new PostgresAccountStore(dataSource);
        specifications = new PostgresSpecificationCatalog(dataSource);
        jobs = new PostgresJobQueue(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void mapsExternalIdentityToOneInternalAccountWithoutUsingDisplayClaims() {
        AccountId first = accounts.findOrCreate(
                "https://issuer.example", "subject-1", NOW.minusSeconds(60));
        AccountId replay = accounts.findOrCreate(
                "https://issuer.example", "subject-1", NOW);
        AccountId other = accounts.findOrCreate(
                "https://issuer.example", "subject-2", NOW);

        assertEquals(first, replay);
        assertNotEquals(first, other);
        assertEquals(2, jdbc.queryForObject("select count(*) from account", Integer.class));
        assertEquals(NOW, jdbc.queryForObject(
                "select last_seen_at from account where id = ?",
                Timestamp.class,
                first.value()).toInstant());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> accounts.findOrCreate("private marker\n", "subject-3", NOW));
        assertEquals("External account identity is invalid", failure.getMessage());
    }

    @Test
    void checksSpecificationOwnershipWithTheOwnerInTheQuery() {
        AccountId owner = account("https://issuer.example", "subject-1");
        AccountId other = account("https://issuer.example", "subject-2");
        SpecificationId specification = insertSpecification(owner);

        assertTrue(specifications.belongsTo(owner, specification));
        assertFalse(specifications.belongsTo(other, specification));
    }

    @Test
    void registersUrlSpecificationsIdempotentlyAndRejectsConflictingReplay() {
        AccountId owner = account("https://issuer.example", "subject-1");
        SpecificationId id = new SpecificationId(UUID.randomUUID());
        ObjectKey key = ObjectKey.parse("specifications/" + id.value() + "/" + HASH_A);
        SpecificationCatalog.Registration registration = new SpecificationCatalog.Registration(
                id, owner, key, HASH_A, 10, "URL", "READY", NOW);

        assertEquals(SpecificationCatalog.RegistrationResult.CREATED, specifications.register(registration));
        assertEquals(SpecificationCatalog.RegistrationResult.REPLAYED, specifications.register(registration));

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from specification where id = ?", Integer.class, id.value()));
        assertTrue(specifications.belongsTo(owner, id));
        IllegalStateException conflict = assertThrows(
                IllegalStateException.class,
                () -> specifications.register(new SpecificationCatalog.Registration(
                        id, owner, key, HASH_B, 10, "URL", "READY", NOW)));
        assertEquals("Specification registration failed", conflict.getMessage());
    }

    @Test
    void replaysMatchingIdempotencyAndRejectsConflictsAndQueueOverflowAtomically() {
        AccountId owner = account("https://issuer.example", "subject-1");
        SpecificationId specification = insertSpecification(owner);
        JobQuota quota = new JobQuota(2, 2);
        CreateJob generation = generation(owner, specification, "same-key", HASH_A, quota);

        CreateJobResult created = jobs.create(generation);
        CreateJobResult replay = jobs.create(generation);

        assertFalse(created.replayed());
        assertTrue(replay.replayed());
        assertEquals(created.job().id(), replay.job().id());
        assertEquals(JobStatus.QUEUED, created.job().status());

        JobQueue.CreateRejected conflict = assertThrows(JobQueue.CreateRejected.class,
                () -> jobs.create(generation(owner, specification, "same-key", HASH_B, quota)));
        assertEquals(JobQueue.CreateRejection.IDEMPOTENCY_CONFLICT, conflict.rejection());

        jobs.create(importJob(owner, "second-key", HASH_B, quota));
        JobQueue.CreateRejected overflow = assertThrows(JobQueue.CreateRejected.class,
                () -> jobs.create(importJob(owner, "third-key", "c".repeat(64), quota)));
        assertEquals(JobQueue.CreateRejection.CAPACITY_EXCEEDED, overflow.rejection());

        assertTrue(jobs.find(owner, created.job().id()).isPresent());
        AccountId other = account("https://issuer.example", "subject-2");
        assertTrue(jobs.find(other, created.job().id()).isEmpty());
        assertFalse(jobs.requestCancellation(other, created.job().id()));
        assertTrue(jobs.requestCancellation(owner, created.job().id()));
        assertTrue(jobs.find(owner, created.job().id()).orElseThrow().cancellationRequested());
    }

    private AccountId account(String issuer, String subject) {
        return accounts.findOrCreate(issuer, subject, NOW);
    }

    private SpecificationId insertSpecification(AccountId owner) {
        SpecificationId id = new SpecificationId(UUID.randomUUID());
        jdbc.update("""
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'UPLOAD', ?, ?, 10, 'weather', 'READY', ?, ?)
                """,
                id.value(),
                owner.value(),
                "specifications/" + id.value(),
                HASH_A,
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        return id;
    }

    private CreateJob generation(
            AccountId owner,
            SpecificationId specification,
            String idempotencyKey,
            String hash,
            JobQuota quota) {
        return new CreateJob(
                owner,
                JobKind.GENERATION,
                "generation",
                idempotencyKey,
                hash,
                "{\"profile\":\"java21\"}",
                Optional.of(specification),
                quota);
    }

    private CreateJob importJob(
            AccountId owner,
            String idempotencyKey,
            String hash,
            JobQuota quota) {
        return new CreateJob(
                owner,
                JobKind.SPEC_IMPORT,
                "specification-import",
                idempotencyKey,
                hash,
                "{\"target\":\"encrypted\"}",
                Optional.empty(),
                quota);
    }
}
