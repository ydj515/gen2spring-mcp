package io.gen2spring.mcp.application.hosted.storage.port.out;

import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import java.io.InputStream;

public interface ObjectStorage {
    StoredObject put(ObjectKey key, InputStream body, long size, String sha256, String contentType);

    StoredObjectContent get(ObjectKey key);

    void delete(ObjectKey key);
}
