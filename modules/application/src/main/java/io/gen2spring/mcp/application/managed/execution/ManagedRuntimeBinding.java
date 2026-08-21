package io.gen2spring.mcp.application.managed.execution;

import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import java.util.Objects;

public record ManagedRuntimeBinding(
        ManagedRuntimeInstance instance,
        RuntimeMetadataArtifact metadata) {
    public ManagedRuntimeBinding {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(metadata, "metadata");
        if (!instance.catalogChecksum().equals(metadata.checksum())
                || metadata.document().tools().isEmpty()) {
            throw new IllegalArgumentException("Managed runtime binding is invalid");
        }
    }
}
