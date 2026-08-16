package io.gen2spring.mcp.app.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * nginx buffers proxied responses by default and severs idle connections after
 * sixty seconds, either of which silently breaks a server-sent event stream.
 * The failure mode is not an error but events arriving in one burst at
 * completion, so it has to be pinned here rather than noticed in production.
 */
class HostedProxyContractTest {
    @Test
    void deliversJobEventStreamsUnbufferedThroughTheProxy() throws Exception {
        String nginx = Files.readString(
                repositoryRoot().resolve("deploy/hosted/proxy/nginx.conf"));

        // Hosted JobId wraps a java.util.UUID, so the location must match a UUID.
        assertTrue(nginx.contains("location ~ \"^/api/jobs/[a-f0-9-]{36}/events$\""),
                "the events location must exist, match hosted UUID job ids, and quote the "
                        + "regex so nginx does not read {36} as a block opener");
        assertTrue(nginx.contains("proxy_buffering off;"), "buffering holds events back");
        assertTrue(nginx.contains("proxy_cache off;"), "a cached stream is not a stream");
        assertTrue(nginx.contains("proxy_read_timeout 30m;"), "the default 60s cuts idle streams");
    }

    @Test
    void keepsArtifactDownloadsOnTheBufferedPath() throws Exception {
        String nginx = Files.readString(
                repositoryRoot().resolve("deploy/hosted/proxy/nginx.conf"));

        // Unbuffering everything would change how large artifact downloads are
        // served, so the relaxed settings stay scoped to the events location.
        int eventsLocation = nginx.indexOf("location ~ \"^/api/jobs/");
        int catchAllLocation = nginx.indexOf("location / {");
        assertTrue(eventsLocation >= 0 && catchAllLocation > eventsLocation,
                "the events location must be declared before the catch-all");
        assertTrue(nginx.indexOf("proxy_buffering off;") < catchAllLocation,
                "buffering must stay enabled for the catch-all location");
    }

    private Path repositoryRoot() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IOException("Unable to locate the repository root");
        }
        return current;
    }
}
