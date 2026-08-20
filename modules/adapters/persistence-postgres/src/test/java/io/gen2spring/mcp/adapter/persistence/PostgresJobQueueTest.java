package io.gen2spring.mcp.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.job.CreateJob;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogPublication;
import io.gen2spring.mcp.application.hosted.job.JobArtifact;
import io.gen2spring.mcp.application.hosted.job.JobCompletion;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.JobQueue;
import io.gen2spring.mcp.application.hosted.job.JobQuota;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.specification.SpecificationCatalog;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.Map;
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
                id, owner, key, HASH_A, 10, "URL", "Imported OpenAPI", "READY", NOW);

        assertEquals(SpecificationCatalog.RegistrationResult.CREATED, specifications.register(registration));
        assertEquals(SpecificationCatalog.RegistrationResult.REPLAYED, specifications.register(registration));

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from specification where id = ?", Integer.class, id.value()));
        assertTrue(specifications.belongsTo(owner, id));
        IllegalStateException conflict = assertThrows(
                IllegalStateException.class,
                () -> specifications.register(new SpecificationCatalog.Registration(
                        id, owner, key, HASH_B, 10, "URL", "Imported OpenAPI", "READY", NOW)));
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

    @Test
    void publishesArtifactsCatalogAndToolsInTheFencedCompletionTransaction() {
        AccountId owner = account("https://issuer.example", "subject-1");
        SpecificationId specification = insertSpecification(owner);
        var created = jobs.create(generation(
                owner, specification, "catalog-key", HASH_A, new JobQuota(2, 2)));
        JobLease lease = jobs.claim(new WorkerId("worker-1"), NOW, Duration.ofSeconds(30)).orElseThrow();
        ToolCatalogPublication publication = publication(List.of(tool("alpha", "alphaOperation"),
                tool("zeta", "zetaOperation")));

        assertTrue(jobs.complete(
                lease, JobCompletion.success(), artifacts(lease), Optional.of(publication)));

        assertEquals(JobStatus.SUCCEEDED, jobs.find(owner, created.job().id()).orElseThrow().status());
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from artifact where job_id = ?", Integer.class, lease.jobId().value()));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tool_catalog where generation_job_id = ?",
                Integer.class, lease.jobId().value()));
        assertEquals(List.of("alpha", "zeta"), jdbc.queryForList(
                """
                select tool_name
                  from tool_catalog_entry
                 where catalog_id = (select id from tool_catalog where generation_job_id = ?)
                 order by ordinal
                """, String.class, lease.jobId().value()));
    }

    @Test
    void rollsBackEveryCompletionRowWhenASecondToolViolatesTheDatabaseContract() {
        AccountId owner = account("https://issuer.example", "subject-1");
        SpecificationId specification = insertSpecification(owner);
        jobs.create(generation(owner, specification, "rollback-key", HASH_A, new JobQuota(2, 2)));
        JobLease lease = jobs.claim(new WorkerId("worker-1"), NOW, Duration.ofSeconds(30)).orElseThrow();
        ToolCatalogPublication publication = publication(List.of(
                tool("alpha", "alphaOperation"),
                tool("zeta", "x".repeat(129))));

        assertThrows(RuntimeException.class, () -> jobs.complete(
                lease, JobCompletion.success(), artifacts(lease), Optional.of(publication)));

        assertEquals(JobStatus.RUNNING, jobs.find(owner, lease.jobId()).orElseThrow().status());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from artifact where job_id = ?", Integer.class, lease.jobId().value()));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from tool_catalog where generation_job_id = ?",
                Integer.class, lease.jobId().value()));
        assertEquals(0, jdbc.queryForObject("select count(*) from tool_catalog_entry", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from generation_job_event where job_id = ?",
                Integer.class, lease.jobId().value()));
    }

    @Test
    void rejectsMissingGenerationCatalogAndImportCatalogBeforeWritingRows() {
        AccountId owner = account("https://issuer.example", "subject-1");
        SpecificationId specification = insertSpecification(owner);
        jobs.create(generation(owner, specification, "missing-key", HASH_A, new JobQuota(2, 3)));
        JobLease generation = jobs.claim(
                new WorkerId("worker-1"), NOW, Duration.ofSeconds(30)).orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> jobs.complete(
                generation, JobCompletion.success(), artifacts(generation), Optional.empty()));

        jobs.create(importJob(owner, "import-key", HASH_B, new JobQuota(2, 3)));
        JobLease imported = jobs.claim(
                new WorkerId("worker-2"), NOW, Duration.ofSeconds(30)).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> jobs.complete(
                imported, JobCompletion.success(), List.of(), Optional.of(publication(List.of(
                        tool("weather", "getWeather"))))));
        assertEquals(0, jdbc.queryForObject("select count(*) from tool_catalog", Integer.class));
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

    private List<JobArtifact> artifacts(JobLease lease) {
        return List.of("ARCHIVE", "MANIFEST", "VALIDATION_REPORT").stream()
                .map(type -> new JobArtifact(
                        type,
                        ObjectKey.parse("artifacts/" + lease.jobId().value() + "/"
                                + lease.fencingToken() + "-"
                                + type.toLowerCase(java.util.Locale.ROOT).replace('_', '-')),
                        "c".repeat(64),
                        10,
                        "application/json",
                        NOW.plus(Duration.ofDays(30))))
                .toList();
    }

    private ToolCatalogPublication publication(List<RuntimeTool> tools) {
        return ToolCatalogPublication.from(new CanonicalRuntimeMetadataCodec().encode(
                new RuntimeMetadataDocument(RuntimeMetadataDocument.VERSION, HASH_A, tools)));
    }

    private RuntimeTool tool(String name, String operationId) {
        return new RuntimeTool(
                operationId, name, "Catalog Tool",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(GET, "https://api.example.test", "/weather", List.of(), false, false),
                null, null, null, List.of());
    }
}
