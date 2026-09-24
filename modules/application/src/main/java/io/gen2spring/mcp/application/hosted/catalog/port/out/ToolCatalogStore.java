package io.gen2spring.mcp.application.hosted.catalog.port.out;

import io.gen2spring.mcp.application.hosted.catalog.result.CatalogCursor;
import io.gen2spring.mcp.application.hosted.catalog.result.CatalogDetails;
import io.gen2spring.mcp.application.hosted.catalog.result.CatalogSummary;
import io.gen2spring.mcp.application.hosted.catalog.result.ToolDetails;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToolCatalogStore {
    List<CatalogSummary> list(AccountId owner, int fetchLimit, Optional<CatalogCursor> cursor);

    Optional<CatalogDetails> find(AccountId owner, UUID catalogId);

    Optional<ToolDetails> findTool(AccountId owner, UUID catalogId, String toolName);

}
