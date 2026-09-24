package io.gen2spring.mcp.application.hosted.catalog.result;

import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import java.util.Objects;
import java.util.regex.Pattern;

public record CatalogDetails(
        CatalogSummary summary,
        String specificationChecksum,
        RuntimeMetadataArtifact metadata) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

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
