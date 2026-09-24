package io.gen2spring.mcp.application.hosted.catalog.result;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CatalogPage(List<CatalogSummary> items, Optional<CatalogCursor> nextCursor) {
    public CatalogPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
