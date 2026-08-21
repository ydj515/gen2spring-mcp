package io.gen2spring.mcp.app.web.hosted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogCursor;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogSummary;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.app.web.security.HostedAccountResolver;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "gen2spring.mode", havingValue = "hosted")
final class HostedToolCatalogController {
    private final HostedAccountResolver accounts;
    private final ToolCatalogService catalogs;
    private final ObjectMapper json;
    private final HostedCursorCodec cursors = new HostedCursorCodec();
    private final CanonicalRuntimeMetadataCodec metadata = new CanonicalRuntimeMetadataCodec();

    HostedToolCatalogController(
            HostedAccountResolver accounts,
            ToolCatalogService catalogs,
            ObjectMapper json) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.json = Objects.requireNonNull(json, "json");
    }

    @GetMapping("/api/tool-catalogs")
    JsonNode catalogs(
            Authentication authentication,
            @RequestParam(defaultValue = "50") String limit,
            @RequestParam(required = false) String cursor) {
        Optional<HostedCursorCodec.Cursor> decoded;
        try {
            decoded = cursors.decode(cursor);
        } catch (IllegalArgumentException failure) {
            throw new ToolCatalogService.ToolCatalogQueryInvalid();
        }
        var page = catalogs.list(
                accounts.resolve(authentication).accountId(),
                limit(limit),
                decoded.map(value -> new CatalogCursor(value.createdAt(), value.id())));
        ObjectNode response = json.createObjectNode();
        var items = response.putArray("items");
        page.items().forEach(summary -> items.add(summary(summary)));
        page.nextCursor().ifPresentOrElse(
                next -> response.put("nextCursor", cursors.encode(
                        new HostedCursorCodec.Cursor(next.createdAt(), next.id()))),
                () -> response.putNull("nextCursor"));
        return response;
    }

    private int limit(String value) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException failure) {
            throw new ToolCatalogService.ToolCatalogQueryInvalid();
        }
    }

    @GetMapping("/api/tool-catalogs/{catalogId}")
    JsonNode catalog(Authentication authentication, @PathVariable String catalogId) {
        var details = catalogs.require(
                accounts.resolve(authentication).accountId(), catalogId(catalogId));
        ObjectNode response = summary(details.summary());
        response.put("specificationChecksum", details.specificationChecksum());
        response.set("metadata", read(details.metadata().content()));
        return response;
    }

    @GetMapping("/api/tool-catalogs/{catalogId}/tools/{toolName}")
    JsonNode tool(
            Authentication authentication,
            @PathVariable String catalogId,
            @PathVariable String toolName) {
        var details = catalogs.requireTool(
                accounts.resolve(authentication).accountId(), catalogId(catalogId), toolName);
        ObjectNode response = summary(details.summary());
        response.set("tool", read(metadata.encodeTool(details.tool())));
        return response;
    }

    private ObjectNode summary(CatalogSummary summary) {
        return json.createObjectNode()
                .put("catalogId", summary.catalogId().toString())
                .put("generationId", summary.generationId().value().toString())
                .put("metadataVersion", summary.metadataVersion())
                .put("metadataChecksum", summary.metadataChecksum())
                .put("toolCount", summary.toolCount())
                .put("createdAt", summary.createdAt().toString());
    }

    private UUID catalogId(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException failure) {
            throw new ToolCatalogService.ToolCatalogQueryInvalid();
        }
    }

    private JsonNode read(byte[] value) {
        try {
            return json.readTree(value);
        } catch (Exception failure) {
            throw new ToolCatalogService.ToolCatalogReadFailure();
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception failure) {
            throw new ToolCatalogService.ToolCatalogReadFailure();
        }
    }
}
