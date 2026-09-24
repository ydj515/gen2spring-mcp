package io.gen2spring.mcp.application.hosted.catalog.result;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CatalogVersion(
        UUID familyId,
        long revision,
        Optional<UUID> predecessorCatalogId) {
    public CatalogVersion {
        Objects.requireNonNull(familyId, "familyId");
        predecessorCatalogId = Objects.requireNonNull(predecessorCatalogId, "predecessorCatalogId");
        if (revision < 1 || (revision == 1) == predecessorCatalogId.isPresent()) {
            throw new IllegalArgumentException("Tool Catalog version is invalid");
        }
    }
}
