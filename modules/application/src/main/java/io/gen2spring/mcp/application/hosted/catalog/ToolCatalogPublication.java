package io.gen2spring.mcp.application.hosted.catalog;

import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.util.ArrayList;
import java.util.List;

public record ToolCatalogPublication(RuntimeMetadataArtifact metadata) {
    private static final String SAFE_MESSAGE = "Hosted Tool Catalog publication is invalid";
    private static final int MAX_TOOLS = 1_000;

    public ToolCatalogPublication {
        try {
            if (metadata == null) {
                throw invalid();
            }
            RuntimeMetadataArtifact decoded = new CanonicalRuntimeMetadataCodec().decode(metadata.content());
            if (!decoded.document().equals(metadata.document())
                    || !decoded.checksum().equals(metadata.checksum())) {
                throw invalid();
            }
            int toolCount = decoded.document().tools().size();
            if (toolCount < 1 || toolCount > MAX_TOOLS) {
                throw invalid();
            }
            metadata = decoded;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public static ToolCatalogPublication from(RuntimeMetadataArtifact artifact) {
        return new ToolCatalogPublication(artifact);
    }

    public List<ToolEntry> entries() {
        List<ToolEntry> entries = new ArrayList<>(metadata.document().tools().size());
        for (int ordinal = 0; ordinal < metadata.document().tools().size(); ordinal++) {
            entries.add(new ToolEntry(ordinal, metadata.document().tools().get(ordinal)));
        }
        return List.copyOf(entries);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(SAFE_MESSAGE);
    }

    public record ToolEntry(int ordinal, RuntimeTool tool) {
        public ToolEntry {
            if (ordinal < 0 || ordinal >= MAX_TOOLS || tool == null) {
                throw invalid();
            }
        }
    }
}
