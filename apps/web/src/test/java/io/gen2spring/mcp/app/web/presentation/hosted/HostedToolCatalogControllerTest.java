package io.gen2spring.mcp.app.web.presentation.hosted;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.app.web.presentation.security.HostedAccountPrincipal;
import io.gen2spring.mcp.app.web.presentation.security.HostedAccountResolver;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.CatalogEndpoint;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.Compatibility;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ToolChange;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiffService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogCursor;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogDetails;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogPage;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogSummary;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.ToolDetails;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class HostedToolCatalogControllerTest {
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9"));
    private static final UUID CATALOG = UUID.fromString("6d65bd83-547b-4965-82f0-eb31af0dcd21");
    private static final Instant CREATED = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void presentsBoundedCatalogPagesAndCanonicalDetailDocuments() {
        HostedAccountResolver accounts = mock(HostedAccountResolver.class);
        ToolCatalogService service = mock(ToolCatalogService.class);
        CatalogDiffService diffs = mock(CatalogDiffService.class);
        Authentication authentication = mock(Authentication.class);
        when(accounts.resolve(authentication)).thenReturn(new HostedAccountPrincipal(OWNER));
        RuntimeMetadataArtifact metadata = metadata();
        CatalogSummary summary = summary(metadata);
        CatalogCursor next = new CatalogCursor(CREATED, CATALOG);
        when(service.list(OWNER, 50, Optional.empty()))
                .thenReturn(new CatalogPage(List.of(summary), Optional.of(next)));
        when(service.require(OWNER, CATALOG)).thenReturn(new CatalogDetails(
                summary, metadata.document().specificationChecksum(), metadata));
        when(service.requireTool(OWNER, CATALOG, "weather"))
                .thenReturn(new ToolDetails(summary, metadata.document().tools().getFirst()));
        UUID targetCatalog = UUID.fromString("8f5a48fd-f34b-4ba5-b749-eb79008370d5");
        when(diffs.compare(OWNER, CATALOG, targetCatalog)).thenReturn(new CatalogDiff(
                new CatalogEndpoint(CATALOG, 1, metadata.checksum(), "a".repeat(64)),
                new CatalogEndpoint(targetCatalog, 2, "b".repeat(64), "c".repeat(64)),
                Compatibility.COMPATIBLE,
                List.of(new ToolChange("forecast", ChangeKind.TOOL_ADDED, "tool")),
                "d".repeat(64)));
        HostedToolCatalogController controller = new HostedToolCatalogController(
                accounts, service, diffs, new ObjectMapper());

        JsonNode page = controller.catalogs(authentication, "50", null);
        JsonNode details = controller.catalog(authentication, CATALOG.toString());
        JsonNode tool = controller.tool(authentication, CATALOG.toString(), "weather");
        JsonNode diff = controller.diff(authentication, CATALOG.toString(), targetCatalog.toString());

        assertEquals(CATALOG.toString(), page.path("items").get(0).path("catalogId").asText());
        assertTrue(page.path("nextCursor").isTextual());
        assertEquals("1.0", details.path("metadata").path("metadataVersion").asText());
        assertEquals(metadata.checksum(), details.path("metadata").path("checksum").asText());
        assertEquals("weather", tool.path("tool").path("name").asText());
        assertEquals(CATALOG.toString(), details.path("familyId").asText());
        assertEquals(1, details.path("revision").asInt());
        assertTrue(details.path("predecessorCatalogId").isNull());
        assertEquals("COMPATIBLE", diff.path("compatibility").asText());
        assertEquals("TOOL_ADDED", diff.path("changes").get(0).path("kind").asText());
        assertEquals(2, diff.path("target").path("revision").asInt());
        String all = page + details.toString() + tool + diff;
        for (String forbidden : List.of(
                "KMA_SERVICE_KEY", "synthetic-secret", "objectKey", "/private/work", "worker-01")) {
            assertFalse(all.contains(forbidden), forbidden);
        }
    }

    private CatalogSummary summary(RuntimeMetadataArtifact metadata) {
        return new CatalogSummary(
                CATALOG,
                new JobId(UUID.fromString("1a803410-a22a-4bc6-b951-7dbc301ae800")),
                RuntimeMetadataDocument.VERSION,
                metadata.checksum(),
                1,
                CREATED);
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
}
