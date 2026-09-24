package io.gen2spring.mcp.application.hosted.catalog;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore;
import io.gen2spring.mcp.application.hosted.catalog.result.CatalogCursor;
import io.gen2spring.mcp.application.hosted.catalog.result.CatalogDetails;
import io.gen2spring.mcp.application.hosted.catalog.result.CatalogPage;
import io.gen2spring.mcp.application.hosted.catalog.result.CatalogSummary;
import io.gen2spring.mcp.application.hosted.catalog.result.ToolDetails;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolCatalogServiceTest {
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9"));
    private static final UUID CATALOG = UUID.fromString("6d65bd83-547b-4965-82f0-eb31af0dcd21");
    private static final Instant CREATED = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void returnsBoundedPagesAndDerivesTheCursorFromTheLastReturnedItem() {
        StubStore store = new StubStore();
        store.list = List.of(summary(0), summary(1), summary(2));
        ToolCatalogService service = new ToolCatalogService(store);

        CatalogPage page = service.list(OWNER, 2, Optional.empty());

        assertEquals(3, store.fetchLimit);
        assertEquals(2, page.items().size());
        assertEquals(page.items().getLast().catalogId(), page.nextCursor().orElseThrow().id());
        assertEquals(page.items().getLast().createdAt(), page.nextCursor().orElseThrow().createdAt());

        store.list = List.of(summary(0));
        CatalogPage finalPage = service.list(OWNER, 2, page.nextCursor());
        assertEquals(1, finalPage.items().size());
        assertTrue(finalPage.nextCursor().isEmpty());
    }

    @Test
    void requiresOwnerScopedCatalogAndToolDetails() {
        StubStore store = new StubStore();
        RuntimeMetadataArtifact metadata = metadata();
        CatalogSummary summary = new CatalogSummary(
                CATALOG,
                new JobId(new UUID(1, 1)),
                RuntimeMetadataDocument.VERSION,
                metadata.checksum(),
                metadata.document().tools().size(),
                CREATED);
        store.catalog = Optional.of(new CatalogDetails(
                summary, metadata.document().specificationChecksum(), metadata));
        store.tool = Optional.of(new ToolDetails(summary, metadata.document().tools().getFirst()));
        ToolCatalogService service = new ToolCatalogService(store);

        assertEquals(CATALOG, service.require(OWNER, CATALOG).summary().catalogId());
        assertEquals("weather", service.requireTool(OWNER, CATALOG, "weather").tool().name());

        store.catalog = Optional.empty();
        assertNotFound(() -> service.require(OWNER, CATALOG));
        store.tool = Optional.empty();
        assertNotFound(() -> service.requireTool(OWNER, CATALOG, "weather"));
    }

    @Test
    void rejectsInvalidQueriesAndMasksStoreFailures() {
        ToolCatalogService service = new ToolCatalogService(new StubStore());
        for (Runnable invalid : List.<Runnable>of(
                () -> service.list(null, 1, Optional.empty()),
                () -> service.list(OWNER, 0, Optional.empty()),
                () -> service.list(OWNER, 101, Optional.empty()),
                () -> service.list(OWNER, 1, null),
                () -> service.list(OWNER, 1, Optional.of(new CatalogCursor(null, CATALOG))),
                () -> service.require(OWNER, null),
                () -> service.requireTool(OWNER, CATALOG, "Bad-Tool"))) {
            ToolCatalogService.ToolCatalogQueryInvalid failure = assertThrows(
                    ToolCatalogService.ToolCatalogQueryInvalid.class, invalid::run);
            assertEquals("The Tool Catalog query is invalid", failure.getMessage());
            assertFalse(failure.toString().contains(CATALOG.toString()));
        }

        StubStore failed = new StubStore();
        failed.failure = new IllegalStateException("private database marker");
        ToolCatalogService.ToolCatalogReadFailure failure = assertThrows(
                ToolCatalogService.ToolCatalogReadFailure.class,
                () -> new ToolCatalogService(failed).list(OWNER, 50, Optional.empty()));
        assertEquals("The Tool Catalog could not be read", failure.getMessage());
        assertFalse(failure.toString().contains("private database marker"));
    }

    private void assertNotFound(Runnable action) {
        ToolCatalogService.ToolCatalogNotFound failure = assertThrows(
                ToolCatalogService.ToolCatalogNotFound.class, action::run);
        assertEquals("The Tool Catalog was not found", failure.getMessage());
        assertFalse(failure.toString().contains(CATALOG.toString()));
    }

    private CatalogSummary summary(int offset) {
        return new CatalogSummary(
                offset == 0 ? CATALOG : new UUID(0, offset),
                new JobId(new UUID(1, offset + 1)),
                RuntimeMetadataDocument.VERSION,
                "b".repeat(64),
                1,
                CREATED.minusSeconds(offset));
    }

    private RuntimeMetadataArtifact metadata() {
        RuntimeTool tool = new RuntimeTool(
                "getWeather", "weather", "Get weather",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(GET, "https://api.example.test", "/weather", List.of(), false, false),
                null, null, null, List.of());
        return new CanonicalRuntimeMetadataCodec().encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "a".repeat(64), List.of(tool)));
    }

    private static final class StubStore implements ToolCatalogStore {
        private List<CatalogSummary> list = new ArrayList<>();
        private Optional<CatalogDetails> catalog = Optional.empty();
        private Optional<ToolDetails> tool = Optional.empty();
        private RuntimeException failure;
        private int fetchLimit;

        @Override
        public List<CatalogSummary> list(AccountId owner, int fetchLimit, Optional<CatalogCursor> cursor) {
            if (failure != null) {
                throw failure;
            }
            this.fetchLimit = fetchLimit;
            return list;
        }

        @Override
        public Optional<CatalogDetails> find(AccountId owner, UUID catalogId) {
            if (failure != null) {
                throw failure;
            }
            return catalog;
        }

        @Override
        public Optional<ToolDetails> findTool(AccountId owner, UUID catalogId, String toolName) {
            if (failure != null) {
                throw failure;
            }
            return tool;
        }
    }
}
