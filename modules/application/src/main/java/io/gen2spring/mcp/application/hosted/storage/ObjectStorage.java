package io.gen2spring.mcp.application.hosted.storage;

import java.io.InputStream;

public interface ObjectStorage {
    StoredObject put(ObjectKey key, InputStream body, long size, String sha256, String contentType);

    StoredObjectContent get(ObjectKey key);

    void delete(ObjectKey key);
}
