package io.gen2spring.mcp.adapter.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.application.hosted.imports.ImportTargetProtector;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.worker.SandboxLimits;
import io.gen2spring.mcp.application.hosted.worker.SandboxResult;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerCliImportRuntimeTest {
    private static final String IMAGE = "registry.example/gen2spring/import-runner@sha256:" + "b".repeat(64);
    private static final JobLease LEASE = new JobLease(
            JobId.parse("1a803410-a22a-4bc6-b951-7dbc301ae800"),
            new WorkerId("worker-01"),
            11,
            Instant.parse("2026-08-13T00:00:30Z"),
            JobKind.SPEC_IMPORT,
            "{}");
    private static final SandboxLimits LIMITS = new SandboxLimits(
            2.0, 4L * 1024 * 1024 * 1024, 256, Duration.ofMinutes(10));

    @TempDir
    private Path temporaryDirectory;

    @Test
    void runsOnlyOnTheGatewayNetworkAndReturnsOneValidatedSource() throws Exception {
        Files.createDirectory(temporaryDirectory.resolve("work"));
        Files.createDirectory(temporaryDirectory.resolve("secrets"));
        Path keyStore = secret("fetch-client.p12");
        Path keyPassword = secret("fetch-client-password");
        Path trustStore = secret("fetch-ca.p12");
        Path trustPassword = secret("fetch-ca-password");
        FakeRunner commands = new FakeRunner();
        DockerCliImportRuntime runtime = new DockerCliImportRuntime(
                executable(),
                Path.of("/run/user/10001/docker.sock"),
                IMAGE,
                "gen2spring-import",
                URI.create("https://fetch-gateway:8443/internal/fetch"),
                temporaryDirectory.resolve("work"),
                temporaryDirectory.resolve("secrets"),
                keyStore,
                keyPassword,
                trustStore,
                trustPassword,
                new ImportTargetProtector() {
                    @Override
                    public EncryptedImportTarget protect(ImportTarget target) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public ImportTarget reveal(EncryptedImportTarget encrypted) {
                        return ImportTarget.parse("https://api.example.com/private-marker/openapi.yaml");
                    }
                },
                commands);

        SandboxResult result = runtime.run(LEASE, encrypted(), LIMITS);

        assertEquals("SUCCESS", result.outcome());
        assertEquals(List.of("source"), result.artifacts().stream().map(artifact -> artifact.name()).toList());
        List<String> create = commands.invocations.getFirst();
        assertPair(create, "--network", "gen2spring-import");
        assertPair(create, "--env", "GEN2SPRING_FETCH_GATEWAY_ENDPOINT=https://fetch-gateway:8443/internal/fetch");
        assertPair(create, "--user", "10001:10001");
        assertPair(create, "--cap-drop", "ALL");
        assertPair(create, "--security-opt", "no-new-privileges");
        assertTrue(create.contains("--read-only"));
        assertFalse(create.contains("host"));
        assertTrue(create.stream().anyMatch(value -> value.endsWith(",dst=/job/input/target.txt,readonly")));
        assertTrue(create.stream().anyMatch(value -> value.endsWith(",dst=/job/output")));
        assertTrue(create.stream().anyMatch(value -> value.endsWith(",dst=/run/secrets/fetch-client.p12,readonly")));
        assertTrue(create.stream().anyMatch(value -> value.endsWith(",dst=/run/secrets/fetch-client-password,readonly")));
        assertTrue(create.stream().anyMatch(value -> value.endsWith(",dst=/run/secrets/fetch-ca.p12,readonly")));
        assertTrue(create.stream().anyMatch(value -> value.endsWith(",dst=/run/secrets/fetch-ca-password,readonly")));
        assertEquals(List.of("create", "start", "wait", "inspect", "rm"), commands.operations());
        assertFalse(Files.exists(temporaryDirectory.resolve("secrets")
                .resolve("gen2spring-1a803410-a22a-4bc6-b951-7dbc301ae800-11.target")));
    }

    @Test
    void preservesMountedSecretsAndWorkspaceUntilContainerRemovalSucceeds() throws Exception {
        Files.createDirectory(temporaryDirectory.resolve("work"));
        Files.createDirectory(temporaryDirectory.resolve("secrets"));
        FakeRunner commands = new FakeRunner();
        commands.rmExitCode = 1;
        DockerCliImportRuntime runtime = new DockerCliImportRuntime(
                executable(),
                Path.of("/run/user/10001/docker.sock"),
                IMAGE,
                "gen2spring-import",
                URI.create("https://fetch-gateway:8443/internal/fetch"),
                temporaryDirectory.resolve("work"),
                temporaryDirectory.resolve("secrets"),
                secret("fetch-client.p12"),
                secret("fetch-client-password"),
                secret("fetch-ca.p12"),
                secret("fetch-ca-password"),
                protector(),
                commands);

        assertThrows(SandboxRuntimeFailure.class, () -> runtime.run(LEASE, encrypted(), LIMITS));

        String name = "gen2spring-1a803410-a22a-4bc6-b951-7dbc301ae800-11";
        assertTrue(Files.exists(temporaryDirectory.resolve("secrets").resolve(name + ".target")));
        assertTrue(Files.exists(temporaryDirectory.resolve("work").resolve(name)));
        assertEquals(List.of("create", "start", "wait", "inspect", "rm", "rm"), commands.operations());
    }

    private Path executable() throws IOException {
        Path docker = temporaryDirectory.resolve("docker");
        Files.writeString(docker, "test");
        docker.toFile().setExecutable(true, true);
        return docker;
    }

    private Path secret(String name) throws IOException {
        Path file = temporaryDirectory.resolve(name);
        Files.writeString(file, "test");
        return file;
    }

    private EncryptedImportTarget encrypted() {
        return new EncryptedImportTarget(
                1,
                "key-1",
                "AAAAAAAAAAAAAAAA",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "AAAAAAAAAAAAAAAA",
                "AAAAAAAAAAAAAAAAAAAAAAA");
    }

    private ImportTargetProtector protector() {
        return new ImportTargetProtector() {
            @Override
            public EncryptedImportTarget protect(ImportTarget target) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ImportTarget reveal(EncryptedImportTarget encrypted) {
                return ImportTarget.parse("https://api.example.com/private-marker/openapi.yaml");
            }
        };
    }

    private void assertPair(List<String> values, String option, String expected) {
        for (int index = 0; index + 1 < values.size(); index++) {
            if (option.equals(values.get(index)) && expected.equals(values.get(index + 1))) {
                return;
            }
        }
        throw new AssertionError(option + " pair is absent");
    }

    private static final class FakeRunner implements DockerCommandRunner {
        private final List<List<String>> invocations = new ArrayList<>();
        private Path output;
        private int rmExitCode;

        @Override
        public CommandResult run(List<String> argv, Duration timeout) {
            invocations.add(List.copyOf(argv));
            String operation = argv.get(3);
            if (operation.equals("create")) {
                String mount = argv.stream()
                        .filter(value -> value.startsWith("type=bind,src=") && value.endsWith(",dst=/job/output"))
                        .findFirst()
                        .orElseThrow();
                output = Path.of(mount.substring(
                        "type=bind,src=".length(), mount.length() - ",dst=/job/output".length()));
                return new CommandResult(0, "container\n");
            }
            if (operation.equals("wait")) {
                try {
                    byte[] source = "openapi: 3.0.3\ninfo: {}\npaths: {}\n".getBytes(StandardCharsets.UTF_8);
                    Files.write(output.resolve("source.yaml"), source);
                    Files.writeString(output.resolve("result.json"), "{\"outcome\":\"SUCCESS\","
                            + "\"mediaType\":\"application/yaml\","
                            + "\"size\":" + source.length + ","
                            + "\"sha256\":\"" + sha256(source) + "\"}");
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
                return new CommandResult(0, "0\n");
            }
            if (operation.equals("inspect")) {
                return new CommandResult(0, "false 0\n");
            }
            if (operation.equals("rm")) {
                return new CommandResult(rmExitCode, "");
            }
            return new CommandResult(0, "");
        }

        private List<String> operations() {
            return invocations.stream().map(invocation -> invocation.get(3)).toList();
        }

        private String sha256(byte[] source) {
            try {
                return java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(source));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
