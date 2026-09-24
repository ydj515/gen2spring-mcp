package io.gen2spring.mcp.application.hosted.catalog.result;

import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public record CatalogSummary(
        UUID catalogId,
        JobId generationId,
        String metadataVersion,
        String metadataChecksum,
        int toolCount,
        Instant createdAt,
        CatalogVersion version) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public CatalogSummary {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(version, "version");
        if (!RuntimeMetadataDocument.VERSION.equals(metadataVersion)
                || metadataChecksum == null
                || !SHA_256.matcher(metadataChecksum).matches()
                || toolCount < 1 || toolCount > 1_000
                || (version.revision() == 1 && !catalogId.equals(version.familyId()))
                || (version.revision() > 1 && catalogId.equals(version.familyId()))) {
            throw new IllegalArgumentException("Tool Catalog summary is invalid");
        }
    }

    public CatalogSummary(
            UUID catalogId,
            JobId generationId,
            String metadataVersion,
            String metadataChecksum,
            int toolCount,
            Instant createdAt) {
        this(catalogId, generationId, metadataVersion, metadataChecksum, toolCount, createdAt,
                new CatalogVersion(catalogId, 1, Optional.empty()));
    }
}
