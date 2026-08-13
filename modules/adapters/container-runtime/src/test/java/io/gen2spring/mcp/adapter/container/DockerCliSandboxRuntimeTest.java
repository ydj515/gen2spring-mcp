package io.gen2spring.mcp.adapter.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import io.gen2spring.mcp.application.hosted.worker.SandboxInput;
import io.gen2spring.mcp.application.hosted.worker.SandboxLimits;
import io.gen2spring.mcp.application.hosted.worker.SandboxResult;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerCliSandboxRuntimeTest {
    private static final String IMAGE = "registry.example/gen2spring/runner@sha256:" + "a".repeat(64);
    private static final JobLease LEASE = new JobLease(
            JobId.parse("1a803410-a22a-4bc6-b951-7dbc301ae800"),
            new WorkerId("worker-01"),
            11,
            Instant.parse("2026-08-13T00:00:30Z"),
            JobKind.GENERATION,
            "{\"profile\":\"java21\"}");
    private static final SandboxLimits LIMITS = new SandboxLimits(
            2.0, 4L * 1024 * 1024 * 1024, 256, Duration.ofMinutes(10));

    @TempDir
    private Path temporaryDirectory;

    @Test
    void runsOneFixedNetworklessRootlessContainerAndCollectsAllowListedOutputs() throws Exception {
        Path docker = executable();
        FakeRunner commands = new FakeRunner();
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(
                docker, Path.of("/run/user/10001/docker.sock"), IMAGE,
                temporaryDirectory, new StubStorage(), commands);

        SandboxResult result = runtime.run(LEASE, input(), LIMITS);

        assertEquals("SUCCESS", result.outcome());
        assertEquals(List.of("archive", "manifest", "validation-report"),
                result.artifacts().stream().map(artifact -> artifact.name()).toList());
        List<String> create = commands.invocations.getFirst();
        assertEquals(docker.toString(), create.getFirst());
        assertContainsPair(create, "--host", "unix:///run/user/10001/docker.sock");
        assertContainsPair(create, "--network", "none");
        assertContainsPair(create, "--user", "10001:10001");
        assertContainsPair(create, "--cap-drop", "ALL");
        assertContainsPair(create, "--security-opt", "no-new-privileges");
        assertContainsPair(create, "--cpus", "2.0");
        assertContainsPair(create, "--memory", "4294967296");
        assertContainsPair(create, "--pids-limit", "256");
        assertContainsPair(create, "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=268435456");
        assertTrue(create.stream().anyMatch(value -> value.startsWith("type=bind,src=")
                && value.endsWith(",dst=/job/output")));
        assertContainsPair(create, "--tmpfs", "/job/work:rw,exec,nosuid,nodev,size=1073741824");
        String inputMount = create.stream()
                .filter(value -> value.startsWith("type=bind,src=") && value.endsWith(",dst=/job/input,readonly"))
                .findFirst()
                .orElseThrow();
        Path inputDirectory = Path.of(inputMount.substring(
                "type=bind,src=".length(), inputMount.length() - ",dst=/job/input,readonly".length()));
        assertTrue(Files.isRegularFile(inputDirectory.resolve("specification.yaml")));
        if (Files.getFileStore(inputDirectory).supportsFileAttributeView("posix")) {
            assertTrue(Files.getPosixFilePermissions(inputDirectory)
                    .contains(PosixFilePermission.OTHERS_EXECUTE));
            assertTrue(Files.getPosixFilePermissions(inputDirectory.resolve("specification.yaml"))
                    .contains(PosixFilePermission.OTHERS_READ));
            Path outputDirectory = commands.workspace.resolve("output");
            assertTrue(Files.getPosixFilePermissions(outputDirectory)
                    .contains(PosixFilePermission.OTHERS_WRITE));
            assertTrue(Files.getPosixFilePermissions(outputDirectory)
                    .contains(PosixFilePermission.OTHERS_EXECUTE));
        }
        assertFalse(Files.exists(inputDirectory.resolve("specification.openapi")));
        assertTrue(create.contains("io.gen2spring.job=1a803410-a22a-4bc6-b951-7dbc301ae800"));
        assertTrue(create.contains("io.gen2spring.fencing-token=11"));
        assertTrue(create.contains("--read-only"));
        assertTrue(create.contains(IMAGE));
        assertEquals(List.of("create", "start", "wait", "inspect", "rm"), commands.operations());

        result.close();
        assertFalse(Files.exists(commands.workspace));
    }

    @Test
    void acceptsEveryYamlMediaTypeAcceptedByHostedImports() throws Exception {
        for (String contentType : List.of("text/x-yaml", "application/vnd.openapi+yaml")) {
            FakeRunner commands = new FakeRunner();
            DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(
                    executable(), Path.of("/run/user/10001/docker.sock"), IMAGE,
                    temporaryDirectory, new StubStorage(contentType), commands);

            try (SandboxResult ignored = runtime.run(LEASE, input(), LIMITS)) {
                String mount = commands.invocations.getFirst().stream()
                        .filter(value -> value.startsWith("type=bind,src=")
                                && value.endsWith(",dst=/job/input,readonly"))
                        .findFirst()
                        .orElseThrow();
                Path directory = Path.of(mount.substring(
                        "type=bind,src=".length(), mount.length() - ",dst=/job/input,readonly".length()));
                assertTrue(Files.isRegularFile(directory.resolve("specification.yaml")));
            }
        }
    }

    @Test
    void rejectsUnpinnedImagesUnsafeSocketAndExecutableBoundaries() throws Exception {
        Path docker = executable();
        assertThrows(IllegalArgumentException.class, () -> new DockerCliSandboxRuntime(
                docker, Path.of("/var/run/docker.sock"), "runner:latest",
                temporaryDirectory, new StubStorage(), new FakeRunner()));
        assertThrows(IllegalArgumentException.class, () -> new DockerCliSandboxRuntime(
                docker, Path.of("/var/run/docker.sock"), IMAGE,
                temporaryDirectory, new StubStorage(), new FakeRunner()));
        assertThrows(IllegalArgumentException.class, () -> new DockerCliSandboxRuntime(
                Path.of("docker"), Path.of("/run/user/10001/docker.sock"), IMAGE,
                temporaryDirectory, new StubStorage(), new FakeRunner()));
    }

    @Test
    void failsClosedAndRemovesTheContainerForUnknownOrSymlinkedOutput() throws Exception {
        FakeRunner unknown = new FakeRunner();
        unknown.outputMode = OutputMode.UNKNOWN;
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(
                executable(), Path.of("/run/user/10001/docker.sock"), IMAGE,
                temporaryDirectory, new StubStorage(), unknown);

        SandboxRuntimeFailure failure = assertThrows(
                SandboxRuntimeFailure.class,
                () -> runtime.run(LEASE, input(), LIMITS));
        assertEquals("Sandbox container execution failed", failure.getMessage());
        assertEquals("rm", unknown.operations().getLast());
        assertFalse(failure.toString().contains("private marker"));

        FakeRunner symlink = new FakeRunner();
        symlink.outputMode = OutputMode.SYMLINK;
        DockerCliSandboxRuntime symlinkRuntime = new DockerCliSandboxRuntime(
                executable(), Path.of("/run/user/10001/docker.sock"), IMAGE,
                temporaryDirectory, new StubStorage(), symlink);
        assertThrows(SandboxRuntimeFailure.class, () -> symlinkRuntime.run(LEASE, input(), LIMITS));
        assertEquals("rm", symlink.operations().getLast());
    }

    @Test
    void cancellationRemovesOnlyTheContainerForTheFencedLease() throws Exception {
        FakeRunner commands = new FakeRunner();
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(
                executable(), Path.of("/run/user/10001/docker.sock"), IMAGE,
                temporaryDirectory, new StubStorage(), commands);
        Path staleWorkspace = temporaryDirectory.resolve(
                "gen2spring-1a803410-a22a-4bc6-b951-7dbc301ae800-11");
        Files.createDirectories(staleWorkspace);
        Files.writeString(staleWorkspace.resolve("stale"), "private marker");

        runtime.cancel(LEASE);

        assertEquals(1, commands.invocations.size());
        assertEquals(List.of("rm"), commands.operations());
        assertEquals("gen2spring-1a803410-a22a-4bc6-b951-7dbc301ae800-11",
                commands.invocations.getFirst().getLast());
        assertFalse(Files.exists(staleWorkspace));
    }

    @Test
    void retainsThePrivateWorkspaceWhenContainerRemovalFails() throws Exception {
        FakeRunner commands = new FakeRunner();
        commands.removeExitCode = 1;
        DockerCliSandboxRuntime runtime = new DockerCliSandboxRuntime(
                executable(), Path.of("/run/user/10001/docker.sock"), IMAGE,
                temporaryDirectory, new StubStorage(), commands);
        Path workspace = temporaryDirectory.resolve(
                "gen2spring-1a803410-a22a-4bc6-b951-7dbc301ae800-11");
        Files.createDirectory(workspace);

        assertThrows(SandboxRuntimeFailure.class, () -> runtime.removeExpired(LEASE));

        assertTrue(Files.exists(workspace));
    }

    private SandboxInput input() {
        return new SandboxInput(
                ObjectKey.parse("specifications/80782e7c-337d-4d4d-bd4d-ad478359563c/source"),
                "{\"targetProfileId\":\"spring-ai-2.0-java21-mvc-streamable\"}",
                "spring-ai-2.0-java21-mvc-streamable");
    }

    private Path executable() throws IOException {
        Path docker = temporaryDirectory.resolve("docker-" + java.util.UUID.randomUUID());
        Files.writeString(docker, "test");
        docker.toFile().setExecutable(true, true);
        return docker.toAbsolutePath();
    }

    private void assertContainsPair(List<String> values, String option, String expected) {
        for (int index = 0; index + 1 < values.size(); index++) {
            if (values.get(index).equals(option) && values.get(index + 1).equals(expected)) {
                return;
            }
        }
        throw new AssertionError(option + " pair is absent");
    }

    private enum OutputMode { VALID, UNKNOWN, SYMLINK }

    private static final class FakeRunner implements DockerCommandRunner {
        private final List<List<String>> invocations = new ArrayList<>();
        private OutputMode outputMode = OutputMode.VALID;
        private int removeExitCode;
        private Path workspace;

        @Override
        public CommandResult run(List<String> argv, Duration timeout) throws InterruptedException {
            invocations.add(List.copyOf(argv));
            String operation = argv.get(3);
            if (operation.equals("rm")) {
                return new CommandResult(removeExitCode, "private marker\n");
            }
            if (operation.equals("create")) {
                String outputMount = argv.stream()
                        .filter(value -> value.startsWith("type=bind,src=") && value.endsWith(",dst=/job/output"))
                        .findFirst()
                        .orElseThrow();
                String output = outputMount.substring("type=bind,src=".length(), outputMount.length() - ",dst=/job/output".length());
                workspace = Path.of(output).getParent();
                return new CommandResult(0, "container-id\n");
            }
            if (operation.equals("wait")) {
                createOutput(workspace.resolve("output"));
                return new CommandResult(0, "0\n");
            }
            if (operation.equals("inspect")) {
                return new CommandResult(0, "false 0\n");
            }
            return new CommandResult(0, "");
        }

        private List<String> operations() {
            return invocations.stream().map(invocation -> invocation.get(3)).toList();
        }

        private void createOutput(Path output) {
            try {
                Files.createDirectories(output);
                Files.write(output.resolve("archive.zip"), "archive".getBytes(StandardCharsets.UTF_8));
                Files.writeString(output.resolve("manifest.json"), "{}");
                Files.writeString(output.resolve("validation-report.json"), "{}");
                Files.writeString(output.resolve("result.json"),
                        "{\"outcome\":\"SUCCESS\",\"exitCode\":0}");
                if (outputMode == OutputMode.UNKNOWN) {
                    Files.writeString(output.resolve("private-marker.txt"), "private marker");
                } else if (outputMode == OutputMode.SYMLINK) {
                    Files.delete(output.resolve("archive.zip"));
                    Files.createSymbolicLink(output.resolve("archive.zip"), output.resolve("manifest.json"));
                }
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class StubStorage implements ObjectStorage {
        private final byte[] source = "openapi: 3.0.3\ninfo: {}\npaths: {}\n".getBytes(StandardCharsets.UTF_8);
        private final String contentType;

        private StubStorage() {
            this("application/yaml");
        }

        private StubStorage(String contentType) {
            this.contentType = contentType;
        }

        @Override public StoredObject put(ObjectKey key, InputStream body, long size, String sha256, String type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredObjectContent get(ObjectKey key) {
            return new StoredObjectContent() {
                @Override public InputStream body() { return new ByteArrayInputStream(source); }
                @Override public long size() { return source.length; }
                @Override public String sha256() { return StubStorage.this.sha256(source); }
                @Override public String contentType() { return contentType; }
                @Override public void close() {}
            };
        }

        @Override public void delete(ObjectKey key) { throw new UnsupportedOperationException(); }

        private String sha256(byte[] value) {
            try {
                return java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(value));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
