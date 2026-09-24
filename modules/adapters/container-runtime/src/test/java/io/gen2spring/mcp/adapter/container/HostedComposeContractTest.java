package io.gen2spring.mcp.adapter.container;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HostedComposeContractTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize().getParent().getParent();

    @Test
    void usesPinnedGarageImageAndScopedPrivateBucketCredentials() throws Exception {
        String compose = Files.readString(ROOT.resolve("deploy/hosted/compose.yml"));
        String init = Files.readString(ROOT.resolve("deploy/hosted/garage-init/init.sh"));
        String config = Files.readString(ROOT.resolve("deploy/hosted/garage/garage.toml"));
        String image = "dxflrs/garage:v2.4.1"
                + "@sha256:9c96caa2612d3411acc5b0e6701fb238dbfba33e533a6d7d3d811a4b12d0d020";

        assertTrue(compose.contains("  garage:\n    image: " + image));
        assertTrue(compose.contains("  garage-init:\n    build:"));
        assertTrue(compose.contains("GEN2SPRING_S3_ENDPOINT: http://garage:3900"));
        assertTrue(compose.contains("garage-rpc-secret"));
        assertTrue(init.contains("garage bucket allow --read --write"));
        assertTrue(config.contains("metadata_fsync = true"));
        assertTrue(config.contains("data_fsync = true"));
        assertTrue(config.contains("rpc_public_addr = \"garage:3901\""));
        assertFalse(compose.contains("tobi312/minio"));
    }

    @Test
    void isolatesRuntimeDatabaseAndProviderEgressCapabilities() throws Exception {
        String compose = Files.readString(ROOT.resolve("deploy/hosted/compose.yml"));
        String environment = Files.readString(ROOT.resolve("deploy/hosted/compose.env.example"));
        assertTrue(compose.contains("  runtime:"));
        assertTrue(compose.contains("  provider-egress:"));
        assertTrue(compose.contains("networks: [runtime-control, provider-call, proxy]"));
        assertTrue(compose.contains("networks: [provider-call, egress]"));
        assertTrue(compose.contains("postgres:17.9-alpine@sha256:c7526c0f6c3f30260a563d7bcf8ad778effac59a44f8ffa86678c35418338609"));
        assertTrue(compose.contains("provider-egress-client.p12"));
        assertTrue(compose.contains("provider-egress-server.p12"));
        assertTrue(runtimeBlock(compose).contains("healthcheck:"));
        assertTrue(runtimeBlock(compose).contains("/actuator/health"));
        assertTrue(providerBlock(compose).contains("healthcheck:"));
        assertTrue(providerBlock(compose).contains("/actuator/health"));
        assertTrue(runtimeBlock(compose).contains("provider-egress: {condition: service_healthy}"));
        assertTrue(providerBlock(compose).contains("provider-egress-server-password"));
        assertTrue(providerBlock(compose).contains("provider-egress-server-trust-password"));
        assertFalse(runtimeBlock(compose).contains("garage"));
        assertFalse(runtimeBlock(compose).contains("docker.sock"));
        assertFalse(providerBlock(compose).contains("postgres-password"));
        assertFalse(providerBlock(compose).contains("OIDC"));
        assertTrue(webBlock(compose).contains("GEN2SPRING_RUNTIME_TOKEN_PEPPER_FILE: /run/secrets/runtime-token-pepper"));
        assertTrue(webBlock(compose).contains("- runtime-token-pepper"));
        assertTrue(webBlock(compose).contains("- credential-key-active"));
        assertTrue(webBlock(compose).contains("- credential-key-retired"));
        assertTrue(runtimeBlock(compose).contains("- credential-key-active"));
        assertTrue(runtimeBlock(compose).contains("- credential-key-retired"));
        assertFalse(workerBlock(compose).contains("credential-key-"));
        assertFalse(providerBlock(compose).contains("credential-key-"));
        assertFalse(providerBlock(compose).contains("GEN2SPRING_DATABASE"));
        assertFalse(runtimeBlock(compose).contains("container_name:"));
        assertFalse(runtimeBlock(compose).contains("ports:"));
        assertTrue(webBlock(compose).contains(
                "GEN2SPRING_RUNTIME_BASE_URI: ${GEN2SPRING_RUNTIME_BASE_URI:?set externally reachable HTTPS runtime base URI}"));
        assertTrue(environment.contains("GEN2SPRING_RUNTIME_BASE_URI=https://gen2spring.example.com"));
    }

    @Test
    void publishesOnlyTheRuntimeMcpPathThroughTheExistingTlsProxy() throws Exception {
        String nginx = Files.readString(ROOT.resolve("deploy/hosted/proxy/nginx.conf"));
        assertTrue(nginx.contains("location ~ \"^/mcp/[a-f0-9-]{36}$\""));
        assertTrue(nginx.contains("proxy_pass http://runtime:8081;"));
        assertTrue(nginx.contains("proxy_buffering off;"));
        assertFalse(nginx.contains("ip_hash"));
        assertFalse(nginx.contains("hash $"));
        assertFalse(nginx.contains("Mcp-Session-Id"));
    }

    @Test
    void usesNumericNonRootReadOnlyImages() throws Exception {
        for (String name : new String[] {"runtime", "provider-egress"}) {
            String dockerfile = Files.readString(ROOT.resolve("deploy/hosted/" + name + "/Dockerfile"));
            assertTrue(dockerfile.contains("USER 10001:10001"));
            assertTrue(dockerfile.contains(":apps:" + name + ":bootJar"));
        }
    }

    @Test
    void includesManagedRuntimeProjectsInTheDockerBuildContext() throws Exception {
        String dockerignore = Files.readString(ROOT.resolve(".dockerignore"));
        for (String path : new String[] {
                "apps/runtime", "apps/provider-egress",
                "modules/adapters/mcp-java-sdk", "modules/adapters/provider-egress"
        }) {
            assertTrue(dockerignore.contains("!" + path + "/build.gradle.kts"));
            assertTrue(dockerignore.contains("!" + path + "/src/main/**"));
        }
    }

    @Test
    void documentsMultiGenerationCredentialKeyRotationWithoutInPlaceReplacement() throws Exception {
        String deployment = Files.readString(ROOT.resolve("deploy/hosted/README.md"));
        assertTrue(deployment.contains("select distinct key_id from managed_credential"));
        assertTrue(deployment.contains("one\nmapping and immutable key file for every ID"));
        assertTrue(deployment.contains("select count(*) from managed_credential where key_id"));
        assertTrue(deployment.contains("Never overwrite a key file in place or reuse a key ID"));
    }

    @Test
    void documentsV7BackupRestoreAndReplicaCutoverWithoutAffinity() throws Exception {
        String deployment = Files.readString(ROOT.resolve("deploy/hosted/README.md"));
        assertTrue(deployment.contains("V7 Catalog version rollout"));
        assertTrue(deployment.contains("managed_runtime_catalog_transition"));
        assertTrue(deployment.contains("Do not down-migrate V7"));
        assertTrue(deployment.contains("PostgreSQL and object storage from the same pre-V7"));
        assertTrue(deployment.contains("sticky routing is neither required nor supported"));
    }

    private String runtimeBlock(String compose) {
        return compose.substring(compose.indexOf("  runtime:"), compose.indexOf("  proxy:"));
    }

    private String providerBlock(String compose) {
        return compose.substring(compose.indexOf("  provider-egress:"), compose.indexOf("  runtime:"));
    }

    private String webBlock(String compose) {
        return compose.substring(compose.indexOf("  web:"), compose.indexOf("  provider-egress:"));
    }

    private String workerBlock(String compose) {
        return compose.substring(compose.indexOf("  worker:"), compose.indexOf("  web:"));
    }
}
