package io.gen2spring.mcp.application.runtime.metadata;

import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import java.util.Objects;
import java.util.regex.Pattern;

public record RuntimeMetadataArtifact(
        RuntimeMetadataDocument document,
        String checksum,
        byte[] content) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public RuntimeMetadataArtifact {
        Objects.requireNonNull(document, "document");
        if (checksum == null || !SHA_256.matcher(checksum).matches()) {
            throw new IllegalArgumentException("Runtime metadata checksum is invalid");
        }
        if (content == null || content.length == 0 || content.length > CanonicalRuntimeMetadataCodec.MAX_BYTES) {
            throw new IllegalArgumentException("Runtime metadata content is invalid");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
