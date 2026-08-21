package io.gen2spring.mcp.application.hosted.worker;

import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record SandboxResult(
        List<SandboxArtifact> artifacts,
        Optional<RuntimeMetadataArtifact> runtimeMetadata,
        String outcome) implements AutoCloseable {
    private static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILED", "TIMED_OUT");

    public SandboxResult {
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        runtimeMetadata = Objects.requireNonNull(runtimeMetadata, "runtimeMetadata");
        if (artifacts.size() > 8
                || !OUTCOMES.contains(outcome)
                || ("SUCCESS".equals(outcome) && artifacts.isEmpty())
                || (!"SUCCESS".equals(outcome) && (!artifacts.isEmpty() || runtimeMetadata.isPresent()))) {
            throw new IllegalArgumentException("Sandbox result is invalid");
        }
        Set<String> names = new HashSet<>();
        for (SandboxArtifact artifact : artifacts) {
            if (artifact == null || !names.add(artifact.name())) {
                throw new IllegalArgumentException("Sandbox result is invalid");
            }
        }
    }

    public SandboxResult(List<SandboxArtifact> artifacts, String outcome) {
        this(artifacts, Optional.empty(), outcome);
    }

    @Override
    public void close() {
        artifacts.forEach(SandboxArtifact::close);
    }
}
