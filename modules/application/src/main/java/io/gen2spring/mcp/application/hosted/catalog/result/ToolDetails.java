package io.gen2spring.mcp.application.hosted.catalog.result;

import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.util.Objects;

public record ToolDetails(CatalogSummary summary, RuntimeTool tool) {
    public ToolDetails {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(tool, "tool");
    }
}
