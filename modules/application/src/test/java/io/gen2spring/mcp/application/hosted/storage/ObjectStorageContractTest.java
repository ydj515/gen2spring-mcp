package io.gen2spring.mcp.application.hosted.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ObjectStorageContractTest {
    @Test
    void acceptsOnlyOpaqueGeneratedObjectKeys() {
        ObjectKey key = ObjectKey.parse(
                "specifications/80782e7c-337d-4d4d-bd4d-ad478359563c/source");

        assertEquals(
                "specifications/80782e7c-337d-4d4d-bd4d-ad478359563c/source",
                key.value());
        assertInvalidKey("/specifications/source");
        assertInvalidKey("specifications/../source");
        assertInvalidKey("specifications/weather.yaml");
        assertInvalidKey("specifications//source");
        assertInvalidKey("private marker with spaces");
    }

    @Test
    void validatesStoredMetadataWithoutEchoingValues() {
        ObjectKey key = ObjectKey.parse("artifacts/1a803410-a22a-4bc6-b951-7dbc301ae800/archive");
        StoredObject stored = new StoredObject(key, 10, "a".repeat(64), "application/zip");

        assertEquals(10, stored.size());
        assertThrows(IllegalArgumentException.class,
                () -> new StoredObject(key, -1, "a".repeat(64), "application/zip"));
        assertThrows(IllegalArgumentException.class,
                () -> new StoredObject(key, 10, "private-marker", "application/zip"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new StoredObject(key, 10, "a".repeat(64), "private\nmarker"));
        assertEquals("Stored object metadata is invalid", failure.getMessage());
    }

    private void assertInvalidKey(String value) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ObjectKey.parse(value));
        assertEquals("Object key is invalid", failure.getMessage());
    }
}
