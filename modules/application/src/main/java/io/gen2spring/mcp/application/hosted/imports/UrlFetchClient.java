package io.gen2spring.mcp.application.hosted.imports;

import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.IOException;
import java.io.InputStream;

public interface UrlFetchClient {
    FetchedSpecification fetch(ImportTarget target);

    interface FetchedSpecification extends AutoCloseable {
        InputStream body();

        long size();

        String mediaType();

        @Override
        void close() throws IOException;
    }
}
