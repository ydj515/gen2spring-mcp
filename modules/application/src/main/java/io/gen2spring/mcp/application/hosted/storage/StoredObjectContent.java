package io.gen2spring.mcp.application.hosted.storage;

import java.io.IOException;
import java.io.InputStream;

public interface StoredObjectContent extends AutoCloseable {
    InputStream body();

    long size();

    String sha256();

    String contentType();

    @Override
    void close() throws IOException;
}
