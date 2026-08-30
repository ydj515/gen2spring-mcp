package io.gen2spring.mcp.app.runtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimePropertiesTest {
    @Test
    void requiresAnAbsoluteHttpsProviderEgressEndpoint() {
        for (String endpoint : new String[] {
                "http://provider-egress:9443/internal/provider-call",
                "/internal/provider-call",
                "https:///internal/provider-call",
                "https://provider-egress:9443/other"
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> properties(URI.create(endpoint)));
            assertEquals("Managed runtime configuration is invalid", failure.getMessage());
        }
    }

    private RuntimeProperties properties(URI endpoint) {
        return new RuntimeProperties(
                8,
                Path.of("/run/secrets/token-pepper"),
                new RuntimeProperties.Encryption(
                        "key-1", Map.of("key-1", Path.of("/run/secrets/credential-key"))),
                endpoint,
                new RuntimeProperties.Tls(
                        Path.of("/run/secrets/client.p12"), Path.of("/run/secrets/client-password"),
                        Path.of("/run/secrets/trust.p12"), Path.of("/run/secrets/trust-password")),
                new RuntimeProperties.Database(
                        "jdbc:postgresql://postgres/gen2spring", "gen2spring",
                        Path.of("/run/secrets/postgres-password")));
    }
}
