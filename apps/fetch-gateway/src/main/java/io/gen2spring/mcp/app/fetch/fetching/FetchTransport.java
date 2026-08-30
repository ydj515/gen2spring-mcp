package io.gen2spring.mcp.app.fetch.fetching;

import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

interface FetchTransport {
    Response execute(ImportTarget target, Duration timeout);

    record Response(int status, Map<String, List<String>> headers, InputStream body) implements AutoCloseable {
        public Response {
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            Objects.requireNonNull(body, "body");
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }
}
