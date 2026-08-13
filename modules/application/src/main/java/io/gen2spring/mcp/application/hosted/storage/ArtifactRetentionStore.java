package io.gen2spring.mcp.application.hosted.storage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ArtifactRetentionStore {
    List<ExpiredArtifact> findExpired(Instant now, int limit);

    boolean deleteExpired(UUID id, ObjectKey objectKey, Instant now, boolean objectDeleted);

    record ExpiredArtifact(UUID id, ObjectKey objectKey, boolean referencedBySpecification) {
        public ExpiredArtifact {
            if (id == null || objectKey == null) {
                throw new IllegalArgumentException("Artifact retention record is invalid");
            }
        }
    }
}
