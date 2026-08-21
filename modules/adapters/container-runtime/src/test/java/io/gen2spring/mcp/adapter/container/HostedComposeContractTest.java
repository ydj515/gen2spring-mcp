package io.gen2spring.mcp.adapter.container;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HostedComposeContractTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize().getParent().getParent();

    @Test
    void isolatesRuntimeDatabaseAndProviderEgressCapabilities() throws Exception {
        String compose = Files.readString(ROOT.resolve("deploy/hosted/compose.yml"));
        assertTrue(compose.contains("  runtime:"));
        assertTrue(compose.contains("  provider-egress:"));
        assertTrue(compose.contains("networks: [runtime-control, provider-call, proxy]"));
        assertTrue(compose.contains("networks: [provider-call, egress]"));
        assertTrue(compose.contains("postgres:17.9-alpine@sha256:c7526c0f6c3f30260a563d7bcf8ad778effac59a44f8ffa86678c35418338609"));
        assertTrue(compose.contains("provider-egress-client.p12"));
        assertTrue(compose.contains("provider-egress-server.p12"));
        assertFalse(runtimeBlock(compose).contains("minio"));
        assertFalse(runtimeBlock(compose).contains("docker.sock"));
        assertFalse(providerBlock(compose).contains("postgres-password"));
        assertFalse(providerBlock(compose).contains("OIDC"));
    }

    @Test
    void publishesOnlyTheRuntimeMcpPathThroughTheExistingTlsProxy() throws Exception {
        String nginx = Files.readString(ROOT.resolve("deploy/hosted/proxy/nginx.conf"));
        assertTrue(nginx.contains("location ~ \"^/mcp/[a-f0-9-]{36}$\""));
        assertTrue(nginx.contains("proxy_pass http://runtime:8081;"));
        assertTrue(nginx.contains("proxy_buffering off;"));
    }

    @Test
    void usesNumericNonRootReadOnlyImages() throws Exception {
        for (String name : new String[] {"runtime", "provider-egress"}) {
            String dockerfile = Files.readString(ROOT.resolve("deploy/hosted/" + name + "/Dockerfile"));
            assertTrue(dockerfile.contains("USER 10001:10001"));
            assertTrue(dockerfile.contains(":apps:" + name + ":bootJar"));
        }
    }

    private String runtimeBlock(String compose) {
        return compose.substring(compose.indexOf("  runtime:"), compose.indexOf("  proxy:"));
    }

    private String providerBlock(String compose) {
        return compose.substring(compose.indexOf("  provider-egress:"), compose.indexOf("  runtime:"));
    }
}
