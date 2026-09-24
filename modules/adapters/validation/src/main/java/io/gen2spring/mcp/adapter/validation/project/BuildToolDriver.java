package io.gen2spring.mcp.adapter.validation.project;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

interface BuildToolDriver {
    Result build(Request request);

    Path relativeArtifact(String artifactId);

    default Path resolveArtifact(Path root, String artifactId) {
        Path normalizedRoot;
        try {
            normalizedRoot = root.toAbsolutePath().normalize().toRealPath();
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Validation workspace is unavailable", exception);
        }
        Path artifact = normalizedRoot.resolve(relativeArtifact(artifactId)).normalize();
        if (!artifact.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Application artifact escaped the validation workspace");
        }
        if (Files.isSymbolicLink(artifact)
                || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Application artifact is missing");
        }
        try {
            Path physical = artifact.toRealPath();
            if (!physical.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException("Application artifact escaped the validation workspace");
            }
            return physical;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Application artifact could not be inspected", exception);
        }
    }

    record Request(
            Path root,
            CompatibilityProfile profile,
            Path javaHome) {}

    record Result(
            int exitCode,
            boolean timedOut,
            boolean processAlive,
            String safeSummary) {}
}
