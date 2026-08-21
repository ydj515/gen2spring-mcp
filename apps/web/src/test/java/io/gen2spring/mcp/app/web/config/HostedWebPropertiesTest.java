package io.gen2spring.mcp.app.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HostedWebPropertiesTest {
    @Test
    void rejectsRuntimeBaseUrisWhosePathWouldBeDiscardedByEndpointResolution() {
        for (String uri : new String[] {
                "https://runtime.example/prefix",
                "https://runtime.example/prefix/"
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new HostedWebProperties.Runtime(
                            URI.create(uri), Path.of("/run/secrets/runtime-token-pepper")));
            assertEquals("Hosted Web configuration is invalid", failure.getMessage());
        }
    }
}
