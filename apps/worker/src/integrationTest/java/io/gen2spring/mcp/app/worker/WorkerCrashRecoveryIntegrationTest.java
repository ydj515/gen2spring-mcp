package io.gen2spring.mcp.app.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.adapter.persistence.account.PostgresAccountStore;
import io.gen2spring.mcp.adapter.persistence.job.PostgresJobQueue;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogPublication;
import io.gen2spring.mcp.application.hosted.job.CreateJob;
import io.gen2spring.mcp.application.hosted.job.JobArtifact;
import io.gen2spring.mcp.application.hosted.job.JobCompletion;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.JobQuota;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    void publishesArtifactsAndOneCatalogAfterACompletionRollbackAndLeaseRecovery() {
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
                Optional.of(new SpecificationId(specification)),
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
        ToolCatalogPublication validCatalog = publication(List.of(
                tool("forecast", "getForecast"),
                tool("warnings", "listWarnings")));
        ToolCatalogPublication invalidCatalog = publication(List.of(
                tool("forecast", "getForecast"),
                tool("warnings", "x".repeat(129))));

        assertEquals(2, retry.fencingToken());
        assertFalse(recoveryQueue.complete(
                crashed,
                JobCompletion.success(),
                artifacts(crashed, recoveredAt.plus(Duration.ofDays(30))),
                Optional.of(validCatalog)));
        assertThrows(RuntimeException.class, () -> recoveryQueue.complete(
                retry,
                JobCompletion.success(),
                artifacts(retry, recoveredAt.plus(Duration.ofDays(30))),
                Optional.of(invalidCatalog)));
        assertEquals(JobStatus.RUNNING, recoveryQueue.find(owner, created.job().id()).orElseThrow().status());
        assertEquals(0, count("artifact", "job_id", created.job().id().value()));
        assertEquals(0, count("tool_catalog", "generation_job_id", created.job().id().value()));
        assertEquals(0, jdbc.queryForObject("select count(*) from tool_catalog_entry", Integer.class));
        assertEquals(0, terminalEventCount(created.job().id().value()));

        assertTrue(recoveryQueue.complete(
                retry,
                JobCompletion.success(),
                artifacts(retry, recoveredAt.plus(Duration.ofDays(30))),
                Optional.of(validCatalog)));
        assertFalse(recoveryQueue.complete(
                retry,
                JobCompletion.success(),
                artifacts(retry, recoveredAt.plus(Duration.ofDays(30))),
                Optional.of(validCatalog)));
        assertEquals(JobStatus.SUCCEEDED, recoveryQueue.find(owner, created.job().id()).orElseThrow().status());
        assertEquals(1, terminalEventCount(created.job().id().value()));
        assertEquals(3, count("artifact", "job_id", created.job().id().value()));
        assertEquals(1, count("tool_catalog", "generation_job_id", created.job().id().value()));
        assertEquals(2, jdbc.queryForObject("select count(*) from tool_catalog_entry", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "select tool_count from tool_catalog where generation_job_id = ?",
                Integer.class,
                created.job().id().value()));
        assertEquals(
                List.of("ARCHIVE", "MANIFEST", "VALIDATION_REPORT"),
                jdbc.queryForList(
                        "select type from artifact where job_id = ? order by type",
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

    private List<JobArtifact> artifacts(JobLease lease, Instant expiresAt) {
        return List.of("ARCHIVE", "MANIFEST", "VALIDATION_REPORT").stream()
                .map(type -> new JobArtifact(
                        type,
                        ObjectKey.parse("artifacts/" + lease.jobId().value() + "/"
                                + lease.fencingToken() + "-"
                                + type.toLowerCase(Locale.ROOT).replace('_', '-')),
                        "c".repeat(64),
                        10,
                        "ARCHIVE".equals(type) ? "application/zip" : "application/json",
                        expiresAt))
                .toList();
    }

    private ToolCatalogPublication publication(List<RuntimeTool> tools) {
        return ToolCatalogPublication.from(new CanonicalRuntimeMetadataCodec().encode(
                new RuntimeMetadataDocument(RuntimeMetadataDocument.VERSION, "a".repeat(64), tools)));
    }

    private RuntimeTool tool(String name, String operationId) {
        return new RuntimeTool(
                operationId,
                name,
                "Hosted Catalog Tool",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON",
                Map.of(),
                new RuntimeHttp(
                        HttpMethod.GET,
                        "https://api.example.test",
                        "/weather",
                        List.of(),
                        false,
                        false),
                null,
                null,
                null,
                List.of());
    }

    private int count(String table, String column, UUID value) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?",
                Integer.class,
                value);
    }

    private int terminalEventCount(UUID jobId) {
        return jdbc.queryForObject(
                """
                select count(*)
                  from generation_job_event
                 where job_id = ? and to_status in ('SUCCEEDED', 'FAILED', 'CANCELLED')
                """,
                Integer.class,
                jobId);
    }

    private PostgresJobQueue queueAt(Instant instant) {
        return new PostgresJobQueue(dataSource, Clock.fixed(instant, ZoneOffset.UTC));
    }
}
