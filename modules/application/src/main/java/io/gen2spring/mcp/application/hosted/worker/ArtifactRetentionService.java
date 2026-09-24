package io.gen2spring.mcp.application.hosted.worker;

import io.gen2spring.mcp.application.hosted.storage.port.out.ArtifactRetentionStore;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class ArtifactRetentionService {
    private final ArtifactRetentionStore artifacts;
    private final ObjectStorage storage;
    private final Clock clock;

    public ArtifactRetentionService(ArtifactRetentionStore artifacts, ObjectStorage storage, Clock clock) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int sweep(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Artifact retention request is invalid");
        }
        Instant now = clock.instant();
        int removed = 0;
        for (ArtifactRetentionStore.ExpiredArtifact artifact : artifacts.findExpired(now, limit)) {
            boolean objectDeleted = !artifact.referencedBySpecification();
            try {
                if (objectDeleted) {
                    storage.delete(artifact.objectKey());
                }
                if (artifacts.deleteExpired(artifact.id(), artifact.objectKey(), now, objectDeleted)) {
                    removed++;
                }
            } catch (RuntimeException ignored) {
                // A later bounded sweep retries object and metadata removal.
            }
        }
        return removed;
    }
}
