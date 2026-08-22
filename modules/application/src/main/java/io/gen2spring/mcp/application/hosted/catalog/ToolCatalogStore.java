package io.gen2spring.mcp.application.hosted.catalog;

import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public interface ToolCatalogStore {
    Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
    List<CatalogSummary> list(AccountId owner, int fetchLimit, Optional<CatalogCursor> cursor);

    Optional<CatalogDetails> find(AccountId owner, UUID catalogId);

    Optional<ToolDetails> findTool(AccountId owner, UUID catalogId, String toolName);

    record CatalogCursor(Instant createdAt, UUID id) {}

    record CatalogVersion(
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

    record CatalogSummary(
            UUID catalogId,
            JobId generationId,
            String metadataVersion,
            String metadataChecksum,
            int toolCount,
            Instant createdAt,
            CatalogVersion version) {
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

    record CatalogPage(List<CatalogSummary> items, Optional<CatalogCursor> nextCursor) {
        public CatalogPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }

    record CatalogDetails(
            CatalogSummary summary,
            String specificationChecksum,
            RuntimeMetadataArtifact metadata) {
        public CatalogDetails {
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(metadata, "metadata");
            if (specificationChecksum == null
                    || !SHA_256.matcher(specificationChecksum).matches()
                    || !summary.metadataVersion().equals(metadata.document().metadataVersion())
                    || !summary.metadataChecksum().equals(metadata.checksum())
                    || !specificationChecksum.equals(metadata.document().specificationChecksum())
                    || summary.toolCount() != metadata.document().tools().size()) {
                throw new IllegalArgumentException("Tool Catalog details are invalid");
            }
        }
    }

    record ToolDetails(CatalogSummary summary, RuntimeTool tool) {
        public ToolDetails {
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(tool, "tool");
        }
    }
}
