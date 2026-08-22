package io.gen2spring.mcp.application.hosted.catalog;

import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.CatalogEndpoint;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.Compatibility;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ToolChange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class CatalogDiffChecksum {
    private CatalogDiffChecksum() {}

    public static String calculate(
            CatalogEndpoint source,
            CatalogEndpoint target,
            Compatibility compatibility,
            List<ToolChange> changes) {
        try {
            StringBuilder canonical = new StringBuilder("catalog-diff-v1");
            append(canonical, source.catalogId().toString());
            append(canonical, Long.toString(source.revision()));
            append(canonical, source.metadataChecksum());
            append(canonical, source.specificationChecksum());
            append(canonical, target.catalogId().toString());
            append(canonical, Long.toString(target.revision()));
            append(canonical, target.metadataChecksum());
            append(canonical, target.specificationChecksum());
            append(canonical, compatibility.name());
            append(canonical, Integer.toString(changes.size()));
            for (ToolChange change : changes) {
                append(canonical, change.toolName());
                append(canonical, change.kind().name());
                append(canonical, change.field());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Catalog diff checksum failed", failure);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }
}
