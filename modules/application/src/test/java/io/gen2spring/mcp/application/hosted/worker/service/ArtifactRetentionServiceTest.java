package io.gen2spring.mcp.application.hosted.worker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import io.gen2spring.mcp.application.hosted.storage.port.out.ArtifactRetentionStore;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void deletesExpiredObjectsAndOnlyMetadataForImportedSourcesStillInUse() {
        ObjectKey archive = ObjectKey.parse("artifacts/1a803410-a22a-4bc6-b951-7dbc301ae800/archive");
        ObjectKey source = ObjectKey.parse("specifications/1a803410-a22a-4bc6-b951-7dbc301ae800/source");
        RecordingStore store = new RecordingStore(List.of(
                new ArtifactRetentionStore.ExpiredArtifact(UUID.randomUUID(), archive, false),
                new ArtifactRetentionStore.ExpiredArtifact(UUID.randomUUID(), source, true)));
        RecordingStorage storage = new RecordingStorage();
        ArtifactRetentionService service = new ArtifactRetentionService(
                store, storage, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(2, service.sweep(100));

        assertEquals(List.of(archive), storage.deleted);
        assertEquals(List.of(archive, source), store.deleted);
    }

    private static final class RecordingStore implements ArtifactRetentionStore {
        private final List<ExpiredArtifact> expired;
        private final List<ObjectKey> deleted = new ArrayList<>();
        private RecordingStore(List<ExpiredArtifact> expired) { this.expired = expired; }
        @Override public List<ExpiredArtifact> findExpired(Instant now, int limit) { return expired; }
        @Override public boolean deleteExpired(
                UUID id, ObjectKey key, Instant now, boolean objectDeleted) {
            deleted.add(key);
            return true;
        }
    }

    private static final class RecordingStorage implements ObjectStorage {
        private final List<ObjectKey> deleted = new ArrayList<>();
        @Override public StoredObject put(ObjectKey key, InputStream body, long size, String sha256, String type) {
            throw new UnsupportedOperationException();
        }
        @Override public StoredObjectContent get(ObjectKey key) { throw new UnsupportedOperationException(); }
        @Override public void delete(ObjectKey key) { deleted.add(key); }
    }
}
