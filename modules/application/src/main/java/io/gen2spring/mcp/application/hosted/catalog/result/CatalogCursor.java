package io.gen2spring.mcp.application.hosted.catalog.result;

import java.time.Instant;
import java.util.UUID;

public record CatalogCursor(Instant createdAt, UUID id) {}
