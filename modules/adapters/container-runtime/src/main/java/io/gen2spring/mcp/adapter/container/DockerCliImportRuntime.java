package io.gen2spring.mcp.adapter.container;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.application.hosted.imports.ImportTargetProtector;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.worker.ImportRuntime;
import io.gen2spring.mcp.application.hosted.worker.SandboxArtifact;
import io.gen2spring.mcp.application.hosted.worker.SandboxLimits;
import io.gen2spring.mcp.application.hosted.worker.SandboxResult;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DockerCliImportRuntime implements ImportRuntime {
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_SOURCE_BYTES = 10L * 1024 * 1024;
    private static final Pattern IMAGE = Pattern.compile("[a-z0-9][a-z0-9._/-]{0,255}@sha256:[a-f0-9]{64}");
    private static final Pattern SOCKET = Pattern.compile("/run/user/[1-9][0-9]*/docker\\.sock");
    private static final Pattern NETWORK = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern WAIT = Pattern.compile("[0-9]{1,3}");
    private static final Pattern INSPECT = Pattern.compile("(true|false) ([0-9]{1,3})");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> SANDBOX_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
    private static final Set<PosixFilePermission> SANDBOX_OUTPUT_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    private final Path docker;
    private final Path socket;
    private final String image;
    private final String network;
    private final URI gatewayEndpoint;
    private final Path workspaceRoot;
    private final Path secretRoot;
    private final Path keyStore;
    private final Path keyStorePassword;
    private final Path trustStore;
    private final Path trustStorePassword;
    private final ImportTargetProtector protector;
    private final DockerCommandRunner commands;

    public DockerCliImportRuntime(
            Path docker,
            Path socket,
            String image,
            String network,
            URI gatewayEndpoint,
            Path workspaceRoot,
            Path secretRoot,
            Path keyStore,
            Path keyStorePassword,
            Path trustStore,
            Path trustStorePassword,
            ImportTargetProtector protector,
            DockerCommandRunner commands) {
        this.docker = executable(docker);
        this.socket = socket(socket);
        this.image = image(image);
        this.network = network(network);
        this.gatewayEndpoint = gatewayEndpoint(gatewayEndpoint);
        this.workspaceRoot = directory(workspaceRoot);
        this.secretRoot = directory(secretRoot);
        this.keyStore = regularFile(keyStore);
        this.keyStorePassword = regularFile(keyStorePassword);
        this.trustStore = regularFile(trustStore);
        this.trustStorePassword = regularFile(trustStorePassword);
        this.protector = Objects.requireNonNull(protector, "protector");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    @Override
    public SandboxResult run(JobLease lease, EncryptedImportTarget encrypted, SandboxLimits limits)
            throws InterruptedException {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(encrypted, "encrypted");
        Objects.requireNonNull(limits, "limits");
        String container = containerName(lease);
        Path workspace = createDirectory(workspaceRoot.resolve(container));
        Path output = createDirectory(workspace.resolve("output"), SANDBOX_OUTPUT_DIRECTORY);
        StagedSecrets secrets = stagedSecrets(container);
        boolean created = false;
        SandboxResult result = null;
        try {
            createDirectory(secrets.directory());
            writeTarget(secrets.target(), protector.reveal(encrypted).uri().toString());
            stageSecret(keyStore, secrets.keyStore());
            stageSecret(keyStorePassword, secrets.keyStorePassword());
            stageSecret(trustStore, secrets.trustStore());
            stageSecret(trustStorePassword, secrets.trustStorePassword());
            requireSuccess(createCommand(container, lease, secrets, output, limits), CONTROL_TIMEOUT);
            created = true;
            requireSuccess(command("start", container), CONTROL_TIMEOUT);
            int waited = waitFor(container, limits.timeout().plusSeconds(5));
            State state = inspect(container);
            if (state.exitCode() != waited) {
                throw failed();
            }
            requireSuccess(command("rm", "-f", "--volumes", container), CONTROL_TIMEOUT);
            created = false;
            result = collect(workspace, output, waited, state.outOfMemory());
            if (!"SUCCESS".equals(result.outcome())) {
                deleteTree(workspace);
            }
            return result;
        } catch (InterruptedException interrupted) {
            if (!created || cleanup(container, interrupted)) {
                created = false;
                close(result);
                deleteTree(workspace);
            }
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Error fatal) {
            if (!created || cleanup(container, fatal)) {
                created = false;
                close(result);
                deleteTree(workspace);
            }
            throw fatal;
        } catch (RuntimeException failure) {
            if (!created || cleanup(container, failure)) {
                created = false;
                close(result);
                deleteTree(workspace);
            }
            throw failure instanceof SandboxRuntimeFailure ? failure : failed();
        } finally {
            if (!created) {
                deleteStaged(secrets);
            }
        }
    }

    @Override
    public void cancel(JobLease lease) {
        terminate(lease);
    }

    @Override
    public void removeExpired(JobLease lease) {
        terminate(lease);
    }

    private void terminate(JobLease lease) {
        String container = containerName(Objects.requireNonNull(lease, "lease"));
        try {
            requireSuccess(command("rm", "-f", "--volumes", container), CONTROL_TIMEOUT);
            deleteStaged(stagedSecrets(container));
            deleteTree(workspaceRoot.resolve(container));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failed();
        }
    }

    private List<String> createCommand(
            String container,
            JobLease lease,
            StagedSecrets secrets,
            Path output,
            SandboxLimits limits) {
        List<String> values = new ArrayList<>(command("create"));
        add(values, "--name", container);
        add(values, "--label", "io.gen2spring.managed=true");
        add(values, "--label", "io.gen2spring.job=" + lease.jobId().value());
        add(values, "--label", "io.gen2spring.fencing-token=" + lease.fencingToken());
        add(values, "--network", network);
        add(values, "--env", "GEN2SPRING_FETCH_GATEWAY_ENDPOINT=" + gatewayEndpoint);
        add(values, "--user", "10001:10001");
        values.add("--read-only");
        add(values, "--cap-drop", "ALL");
        add(values, "--security-opt", "no-new-privileges");
        add(values, "--cpus", Double.toString(limits.cpus()));
        add(values, "--memory", Long.toString(limits.memoryBytes()));
        add(values, "--pids-limit", Integer.toString(limits.pids()));
        add(values, "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=268435456");
        add(values, "--tmpfs", "/job/work:rw,noexec,nosuid,nodev,size=67108864");
        add(values, "--mount", "type=bind,src=" + secrets.target() + ",dst=/job/input/target.txt,readonly");
        add(values, "--mount", "type=bind,src=" + output + ",dst=/job/output");
        add(values, "--mount", "type=bind,src=" + secrets.keyStore() + ",dst=/run/secrets/fetch-client.p12,readonly");
        add(values, "--mount", "type=bind,src=" + secrets.keyStorePassword()
                + ",dst=/run/secrets/fetch-client-password,readonly");
        add(values, "--mount", "type=bind,src=" + secrets.trustStore() + ",dst=/run/secrets/fetch-ca.p12,readonly");
        add(values, "--mount", "type=bind,src=" + secrets.trustStorePassword()
                + ",dst=/run/secrets/fetch-ca-password,readonly");
        values.add(image);
        return List.copyOf(values);
    }

    private SandboxResult collect(Path workspace, Path output, int exitCode, boolean outOfMemory) {
        try {
            List<Path> entries;
            try (var stream = Files.list(output)) {
                entries = stream.toList();
            }
            if (exitCode != 0 || outOfMemory) {
                if (!entries.isEmpty()) {
                    throw failed();
                }
                return new SandboxResult(List.of(), outOfMemory ? "FAILED" : "FAILED");
            }
            if (entries.size() != 2
                    || entries.stream().anyMatch(path -> Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS))) {
                throw failed();
            }
            Path resultPath = output.resolve("result.json");
            JsonNode metadata = JSON.readTree(resultPath.toFile());
            if (metadata == null
                    || metadata.size() != 4
                    || !"SUCCESS".equals(metadata.path("outcome").textValue())
                    || !metadata.path("mediaType").isTextual()
                    || !metadata.path("size").canConvertToLong()
                    || !metadata.path("sha256").isTextual()) {
                throw failed();
            }
            String mediaType = mediaType(metadata.path("mediaType").textValue());
            Path source = output.resolve(json(mediaType) ? "source.json" : "source.yaml");
            if (!entries.contains(source) || !entries.contains(resultPath)) {
                throw failed();
            }
            long size = Files.size(source);
            String sha256 = digest(source);
            if (size < 1
                    || size > MAX_SOURCE_BYTES
                    || size != metadata.path("size").longValue()
                    || !SHA256.matcher(metadata.path("sha256").textValue()).matches()
                    || !MessageDigest.isEqual(
                            sha256.getBytes(StandardCharsets.US_ASCII),
                            metadata.path("sha256").textValue().getBytes(StandardCharsets.US_ASCII))) {
                throw failed();
            }
            InputStream body = new WorkspaceInputStream(Files.newInputStream(source, NOFOLLOW_LINKS), workspace);
            return new SandboxResult(List.of(SandboxArtifact.of("source", body, size, sha256, mediaType)), "SUCCESS");
        } catch (SandboxRuntimeFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failed();
        }
    }

    private String mediaType(String value) {
        if (value == null || value.length() > 128) {
            throw failed();
        }
        if (json(value)
                || Set.of("application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml").contains(value)
                || value.endsWith("+yaml")) {
            return value;
        }
        throw failed();
    }

    private boolean json(String value) {
        return "application/json".equals(value) || value.endsWith("+json");
    }

    private int waitFor(String container, Duration timeout) throws InterruptedException {
        String value = requireSuccess(command("wait", container), timeout).output().strip();
        if (!WAIT.matcher(value).matches()) {
            throw failed();
        }
        return Integer.parseInt(value);
    }

    private State inspect(String container) throws InterruptedException {
        String value = requireSuccess(
                        command("inspect", "--format", "{{.State.OOMKilled}} {{.State.ExitCode}}", container),
                        CONTROL_TIMEOUT)
                .output()
                .strip();
        Matcher matcher = INSPECT.matcher(value);
        if (!matcher.matches()) {
            throw failed();
        }
        return new State(Boolean.parseBoolean(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private DockerCommandRunner.CommandResult requireSuccess(List<String> argv, Duration timeout)
            throws InterruptedException {
        DockerCommandRunner.CommandResult result = commands.run(argv, timeout);
        if (result.exitCode() != 0) {
            throw failed();
        }
        return result;
    }

    private List<String> command(String operation, String... arguments) {
        List<String> values = new ArrayList<>();
        values.add(docker.toString());
        values.add("--host");
        values.add("unix://" + socket);
        values.add(operation);
        values.addAll(List.of(arguments));
        return List.copyOf(values);
    }

    private void add(List<String> values, String option, String value) {
        values.add(option);
        values.add(value);
    }

    private void writeTarget(Path target, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            if (bytes.length < 1 || bytes.length > 4096) {
                throw failed();
            }
            Files.write(target, bytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS);
            permissions(target, SANDBOX_FILE);
        } catch (RuntimeException failure) {
            throw failure instanceof SandboxRuntimeFailure ? failure : failed();
        } catch (Exception failure) {
            throw failed();
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private Path createDirectory(Path path) {
        return createDirectory(path, PRIVATE_DIRECTORY);
    }

    private Path createDirectory(Path path, Set<PosixFilePermission> permissions) {
        try {
            Path created = Files.createDirectory(path);
            permissions(created, permissions);
            return created;
        } catch (Exception failure) {
            throw failed();
        }
    }

    private void stageSecret(Path source, Path target) {
        try {
            Files.copy(source, target);
            permissions(target, SANDBOX_FILE);
        } catch (Exception failure) {
            throw failed();
        }
    }

    private StagedSecrets stagedSecrets(String container) {
        Path directory = secretRoot.resolve(container);
        return new StagedSecrets(
                directory,
                directory.resolve("target"),
                directory.resolve("client"),
                directory.resolve("client-password"),
                directory.resolve("ca"),
                directory.resolve("ca-password"));
    }

    private void deleteStaged(StagedSecrets secrets) {
        deleteTree(secrets.directory());
    }

    private boolean cleanup(String container, Throwable primary) {
        try {
            DockerCommandRunner.CommandResult removed = commands.run(
                    command("rm", "-f", "--volumes", container), CONTROL_TIMEOUT);
            if (removed.exitCode() != 0) {
                primary.addSuppressed(failed());
                return false;
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            primary.addSuppressed(interrupted);
            return false;
        } catch (Throwable cleanup) {
            if (cleanup != primary) {
                primary.addSuppressed(cleanup);
            }
            return false;
        }
    }

    private void close(SandboxResult result) {
        if (result != null) {
            result.close();
        }
    }

    private String digest(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path, NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void deleteFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // The fixed sandbox failure remains authoritative; startup recovery retries cleanup.
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    if (!Files.isSymbolicLink(path)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException ignored) {
                    // Bounded recovery can retry by deterministic workspace name.
                }
            });
        } catch (IOException ignored) {
            // Bounded recovery can retry by deterministic workspace name.
        }
    }

    private void permissions(Path path, Set<PosixFilePermission> permissions) throws Exception {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows support uses deployment-provisioned ACLs.
        }
    }

    private static Path executable(Path path) {
        try {
            if (path == null
                    || !path.isAbsolute()
                    || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, NOFOLLOW_LINKS)
                    || !Files.isExecutable(path)) {
                throw new IllegalArgumentException();
            }
            return path.toRealPath(NOFOLLOW_LINKS);
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static Path socket(Path path) {
        if (path == null || !path.isAbsolute() || !SOCKET.matcher(path.normalize().toString()).matches()) {
            throw invalid();
        }
        return path.normalize();
    }

    private static String image(String value) {
        if (value == null || !IMAGE.matcher(value).matches()) {
            throw invalid();
        }
        return value;
    }

    private static String network(String value) {
        if (value == null
                || !NETWORK.matcher(value).matches()
                || Set.of("bridge", "default", "host", "none").contains(value)) {
            throw invalid();
        }
        return value;
    }

    private static URI gatewayEndpoint(URI value) {
        if (value == null
                || !"https".equals(value.getScheme())
                || value.getHost() == null
                || value.getRawUserInfo() != null
                || value.getRawQuery() != null
                || value.getRawFragment() != null
                || !"/internal/fetch".equals(value.getRawPath())) {
            throw invalid();
        }
        return value;
    }

    private static Path directory(Path path) {
        try {
            if (path == null
                    || !path.isAbsolute()
                    || Files.isSymbolicLink(path)
                    || !Files.isDirectory(path, NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException();
            }
            return path.toRealPath(NOFOLLOW_LINKS);
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static Path regularFile(Path path) {
        try {
            if (path == null
                    || !path.isAbsolute()
                    || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException();
            }
            return path.toRealPath(NOFOLLOW_LINKS);
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static String containerName(JobLease lease) {
        return "gen2spring-" + lease.jobId().value() + "-" + lease.fencingToken();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Docker import runtime configuration is invalid");
    }

    private record StagedSecrets(
            Path directory,
            Path target,
            Path keyStore,
            Path keyStorePassword,
            Path trustStore,
            Path trustStorePassword) {}

    private SandboxRuntimeFailure failed() {
        return new SandboxRuntimeFailure();
    }

    private record State(boolean outOfMemory, int exitCode) {}

    private static final class WorkspaceInputStream extends FilterInputStream {
        private final Path workspace;

        private WorkspaceInputStream(InputStream input, Path workspace) {
            super(input);
            this.workspace = workspace;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                super.close();
            } catch (IOException exception) {
                failure = exception;
            } finally {
                deleteTree(workspace);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
