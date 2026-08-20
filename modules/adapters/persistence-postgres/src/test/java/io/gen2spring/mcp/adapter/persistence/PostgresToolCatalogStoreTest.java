package io.gen2spring.mcp.adapter.persistence;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogPublication;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogCursor;
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
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
class PostgresToolCatalogStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.9-alpine");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private PostgresAccountStore accounts;
    private PostgresJobQueue jobs;
    private PostgresToolCatalogStore catalogs;

    @BeforeEach
    void resetDatabase() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        accounts = new PostgresAccountStore(dataSource);
        jobs = new PostgresJobQueue(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
        catalogs = new PostgresToolCatalogStore(dataSource);
    }

    @Test
    void readsCanonicalCatalogsAndToolsOnlyForTheOwner() {
        AccountId owner = accounts.findOrCreate("https://issuer.example", "subject-1", NOW);
        AccountId other = accounts.findOrCreate("https://issuer.example", "subject-2", NOW);
        SpecificationId specification = specification(owner);
        jobs.create(new CreateJob(
                owner, JobKind.GENERATION, "generation", "catalog-key", HASH,
                "{\"profile\":\"java21\"}", Optional.of(specification), new JobQuota(2, 2)));
        JobLease lease = jobs.claim(new WorkerId("worker-1"), NOW, Duration.ofSeconds(30)).orElseThrow();
        ToolCatalogPublication publication = publication();
        assertTrue(jobs.complete(
                lease, JobCompletion.success(), artifacts(lease), Optional.of(publication)));

        var summaries = catalogs.list(owner, 2, Optional.empty());
        assertEquals(1, summaries.size());
        var summary = summaries.getFirst();
        assertEquals(lease.jobId(), summary.generationId());
        assertEquals(2, summary.toolCount());
        assertEquals(publication.metadata().checksum(), summary.metadataChecksum());
        assertTrue(catalogs.list(
                owner, 2, Optional.of(new CatalogCursor(summary.createdAt(), summary.catalogId()))).isEmpty());
        assertTrue(catalogs.list(other, 2, Optional.empty()).isEmpty());

        var details = catalogs.find(owner, summary.catalogId()).orElseThrow();
        assertArrayEquals(publication.metadata().content(), details.metadata().content());
        assertEquals(HASH, details.specificationChecksum());
        assertEquals("alpha", catalogs.findTool(owner, summary.catalogId(), "alpha")
                .orElseThrow().tool().name());
        assertTrue(catalogs.find(other, summary.catalogId()).isEmpty());
        assertTrue(catalogs.findTool(other, summary.catalogId(), "alpha").isEmpty());
        assertTrue(catalogs.findTool(owner, summary.catalogId(), "missing").isEmpty());
    }

    @Test
    void rejectsInvalidBoundsAndNeverReturnsCorruptedRawMetadata() {
        AccountId owner = accounts.findOrCreate("https://issuer.example", "subject-1", NOW);
        IllegalArgumentException lower = assertThrows(
                IllegalArgumentException.class,
                () -> catalogs.list(owner, 1, Optional.empty()));
        assertEquals("Tool Catalog storage query is invalid", lower.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> catalogs.list(owner, 102, Optional.empty()));

        SpecificationId specification = specification(owner);
        jobs.create(new CreateJob(
                owner, JobKind.GENERATION, "generation", "corrupt-key", HASH,
                "{\"profile\":\"java21\"}", Optional.of(specification), new JobQuota(2, 2)));
        JobLease lease = jobs.claim(new WorkerId("worker-1"), NOW, Duration.ofSeconds(30)).orElseThrow();
        assertTrue(jobs.complete(
                lease, JobCompletion.success(), artifacts(lease), Optional.of(publication())));
        UUID catalogId = catalogs.list(owner, 2, Optional.empty()).getFirst().catalogId();
        jdbc.update("update tool_catalog set metadata_document = '{}' where id = ?", catalogId);

        RuntimeException failure = assertThrows(
                RuntimeException.class, () -> catalogs.find(owner, catalogId));
        assertFalse(failure.toString().contains("metadataVersion"));
        assertFalse(failure.toString().contains(catalogId.toString()));
    }

    private SpecificationId specification(AccountId owner) {
        SpecificationId id = new SpecificationId(UUID.randomUUID());
        jdbc.update("""
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'UPLOAD', ?, ?, 10, 'weather', 'READY', ?, ?)
                """,
                id.value(), owner.value(), "specifications/" + id.value(), HASH,
                Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private ToolCatalogPublication publication() {
        List<RuntimeTool> tools = List.of(tool("zeta", "zetaOperation"), tool("alpha", "alphaOperation"));
        return ToolCatalogPublication.from(new CanonicalRuntimeMetadataCodec().encode(
                new RuntimeMetadataDocument(RuntimeMetadataDocument.VERSION, HASH, tools)));
    }

    private RuntimeTool tool(String name, String operationId) {
        return new RuntimeTool(
                operationId, name, "Catalog Tool",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(GET, "https://api.example.test", "/weather", List.of(), false, false),
                null, null, null, List.of());
    }

    private List<JobArtifact> artifacts(JobLease lease) {
        return List.of("ARCHIVE", "MANIFEST", "VALIDATION_REPORT").stream()
                .map(type -> new JobArtifact(
                        type,
                        ObjectKey.parse("artifacts/" + lease.jobId().value() + "/"
                                + lease.fencingToken() + "-"
                                + type.toLowerCase(java.util.Locale.ROOT).replace('_', '-')),
                        "c".repeat(64), 10, "application/json", NOW.plus(Duration.ofDays(30))))
                .toList();
    }
}
