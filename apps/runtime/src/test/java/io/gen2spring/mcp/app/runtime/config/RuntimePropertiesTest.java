package io.gen2spring.mcp.app.runtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimePropertiesTest {
    @TempDir
    Path secrets;

    @Test
    void acceptsAnAbsoluteHttpsProviderEgressEndpoint() {
        URI endpoint = URI.create("https://provider-egress:9443/internal/provider-call");
        assertEquals(endpoint, properties(endpoint).providerEgressEndpoint());
    }

    @Test
    void rejectsNullEncryptionEntriesWithTheFixedConfigurationFailure() {
        Map<String, Path> keyFiles = new LinkedHashMap<>();
        keyFiles.put("key-1", null);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new RuntimeProperties.Encryption("key-1", keyFiles));

        assertEquals("Managed runtime encryption configuration is invalid", failure.getMessage());
    }

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
                secrets.resolve("token-pepper"),
                new RuntimeProperties.Encryption(
                        "key-1", Map.of("key-1", secrets.resolve("credential-key"))),
                endpoint,
                new RuntimeProperties.Tls(
                        secrets.resolve("client.p12"), secrets.resolve("client-password"),
                        secrets.resolve("trust.p12"), secrets.resolve("trust-password")),
                new RuntimeProperties.Database(
                        "jdbc:postgresql://postgres/gen2spring", "gen2spring",
                        secrets.resolve("postgres-password")));
    }
}
