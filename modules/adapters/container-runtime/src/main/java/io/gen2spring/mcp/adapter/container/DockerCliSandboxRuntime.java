package io.gen2spring.mcp.adapter.container;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import io.gen2spring.mcp.application.hosted.worker.SandboxInput;
import io.gen2spring.mcp.application.hosted.worker.SandboxLimits;
import io.gen2spring.mcp.application.hosted.worker.SandboxResult;
import io.gen2spring.mcp.application.hosted.worker.port.out.SandboxRuntime;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DockerCliSandboxRuntime implements SandboxRuntime {
    private static final long MAX_SPECIFICATION_BYTES = 10L * 1024 * 1024;
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern IMAGE = Pattern.compile(
            "[a-z0-9][a-z0-9._/-]{0,255}@sha256:[a-f0-9]{64}");
    private static final Pattern SOCKET = Pattern.compile("/run/user/[1-9][0-9]*/docker\\.sock");
    private static final Pattern WAIT_OUTPUT = Pattern.compile("([0-9]{1,3})");
    private static final Pattern INSPECT_OUTPUT = Pattern.compile("(true|false) ([0-9]{1,3})");
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> SANDBOX_INPUT_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<PosixFilePermission> SANDBOX_OUTPUT_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<PosixFilePermission> SANDBOX_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    private final Path docker;
    private final Path socket;
    private final String image;
    private final Path workspaceRoot;
    private final ObjectStorage storage;
    private final DockerCommandRunner commands;
    private final SandboxOutputCollector outputs;

    public DockerCliSandboxRuntime(
            Path docker,
            Path socket,
            String image,
            Path workspaceRoot,
            ObjectStorage storage,
            DockerCommandRunner commands) {
        this.docker = executable(docker);
        this.socket = socket(socket);
        this.image = image(image);
        this.workspaceRoot = workspaceRoot(workspaceRoot);
        this.storage = Objects.requireNonNull(storage, "storage");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.outputs = new SandboxOutputCollector(this.workspaceRoot);
    }

    @Override
    public SandboxResult run(JobLease lease, SandboxInput input, SandboxLimits limits)
            throws InterruptedException {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(limits, "limits");
        String container = containerName(lease);
        Path workspace = createWorkspace(container);
        SandboxResult result = null;
        boolean created = false;
        try {
            Path inputDirectory = createDirectory(workspace.resolve("input"), SANDBOX_INPUT_DIRECTORY);
            Path outputDirectory = createDirectory(workspace.resolve("output"), SANDBOX_OUTPUT_DIRECTORY);
            writeSpecification(inputDirectory, input);
            writePrivateFile(inputDirectory.resolve("generation-config.json"),
                    input.generationConfiguration().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            writePrivateFile(inputDirectory.resolve("target-profile.txt"), input.targetProfileId().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            requireSuccess(createCommand(container, lease, inputDirectory, outputDirectory, limits), CONTROL_TIMEOUT);
            created = true;
            requireSuccess(command("start", container), CONTROL_TIMEOUT);
            int waitExitCode = waitForContainer(container, limits.timeout().plusSeconds(5));
            ContainerState state = inspect(container);
            if (state.exitCode() != waitExitCode) {
                throw failed();
            }
            result = outputs.collect(workspace, outputDirectory, waitExitCode, state.outOfMemory());
            requireSuccess(command("rm", "-f", "--volumes", container), CONTROL_TIMEOUT);
            created = false;
            if (!"SUCCESS".equals(result.outcome())) {
                outputs.deleteWorkspace(workspace);
            }
            return result;
        } catch (InterruptedException interrupted) {
            close(result);
            if (cleanupContainer(created, container, interrupted)) {
                outputs.deleteWorkspace(workspace);
            }
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Error fatal) {
            close(result);
            if (cleanupContainer(created, container, fatal)) {
                outputs.deleteWorkspace(workspace);
            }
            throw fatal;
        } catch (RuntimeException failure) {
            close(result);
            if (cleanupContainer(created, container, failure)) {
                outputs.deleteWorkspace(workspace);
            }
            throw failure instanceof SandboxRuntimeFailure ? failure : failed();
        }
    }

    @Override
    public void cancel(JobLease lease) {
        terminateLease(Objects.requireNonNull(lease, "lease"));
    }

    @Override
    public void removeExpired(JobLease lease) {
        terminateLease(Objects.requireNonNull(lease, "lease"));
    }

    private void terminateLease(JobLease lease) {
        String container = containerName(lease);
        remove(container);
        outputs.deleteWorkspace(workspaceRoot.resolve(container));
    }

    private void remove(String container) {
        try {
            requireSuccess(command("rm", "-f", "--volumes", container), CONTROL_TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failed();
        }
    }

    private List<String> createCommand(
            String container,
            JobLease lease,
            Path inputDirectory,
            Path outputDirectory,
            SandboxLimits limits) {
        List<String> values = new ArrayList<>(command("create"));
        add(values, "--name", container);
        add(values, "--label", "io.gen2spring.managed=true");
        add(values, "--label", "io.gen2spring.job=" + lease.jobId().value());
        add(values, "--label", "io.gen2spring.fencing-token=" + lease.fencingToken());
        add(values, "--network", "none");
        add(values, "--user", "10001:10001");
        values.add("--read-only");
        add(values, "--cap-drop", "ALL");
        add(values, "--security-opt", "no-new-privileges");
        add(values, "--cpus", Double.toString(limits.cpus()));
        add(values, "--memory", Long.toString(limits.memoryBytes()));
        add(values, "--pids-limit", Integer.toString(limits.pids()));
        add(values, "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=268435456");
        add(values, "--tmpfs", "/job/work:rw,exec,nosuid,nodev,size=1073741824");
        add(values, "--mount", "type=bind,src=" + inputDirectory + ",dst=/job/input,readonly");
        add(values, "--mount", "type=bind,src=" + outputDirectory + ",dst=/job/output");
        values.add(image);
        values.add("/opt/gen2spring/job-entrypoint");
        return List.copyOf(values);
    }

    private int waitForContainer(String container, Duration timeout) throws InterruptedException {
        DockerCommandRunner.CommandResult result = requireSuccess(command("wait", container), timeout);
        Matcher matcher = WAIT_OUTPUT.matcher(result.output().strip());
        if (!matcher.matches()) {
            throw failed();
        }
        return Integer.parseInt(matcher.group(1));
    }

    private ContainerState inspect(String container) throws InterruptedException {
        DockerCommandRunner.CommandResult result = requireSuccess(
                command("inspect", "--format", "{{.State.OOMKilled}} {{.State.ExitCode}}", container),
                CONTROL_TIMEOUT);
        Matcher matcher = INSPECT_OUTPUT.matcher(result.output().strip());
        if (!matcher.matches()) {
            throw failed();
        }
        return new ContainerState(Boolean.parseBoolean(matcher.group(1)), Integer.parseInt(matcher.group(2)));
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
        List<String> values = new ArrayList<>(4 + arguments.length);
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

    private void writeSpecification(Path inputDirectory, SandboxInput input) {
        try (StoredObjectContent content = storage.get(input.specification());
                InputStream source = content.body()) {
            if (content.size() < 1 || content.size() > MAX_SPECIFICATION_BYTES) {
                throw failed();
            }
            Path target = inputDirectory.resolve(specificationName(content.contentType()));
            try (OutputStream destination = Files.newOutputStream(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                long total = 0;
                while (true) {
                    int read = source.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    total += read;
                    if (total > content.size() || total > MAX_SPECIFICATION_BYTES) {
                        throw failed();
                    }
                    digest.update(buffer, 0, read);
                    destination.write(buffer, 0, read);
                }
                String actual = HexFormat.of().formatHex(digest.digest());
                if (total != content.size() || !MessageDigest.isEqual(
                        actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        content.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    throw failed();
                }
                setPermissions(target, SANDBOX_FILE);
            }
        } catch (Error fatal) {
            throw fatal;
        } catch (SandboxRuntimeFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failed();
        }
    }

    private String specificationName(String contentType) {
        if ("application/json".equals(contentType) || (contentType != null && contentType.endsWith("+json"))) {
            return "specification.json";
        }
        if (Set.of("application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml").contains(contentType)
                || (contentType != null && contentType.endsWith("+yaml"))) {
            return "specification.yaml";
        }
        throw failed();
    }

    private void writePrivateFile(Path target, byte[] value) {
        try {
            Files.write(target, value, CREATE_NEW, WRITE, NOFOLLOW_LINKS);
            setPermissions(target, SANDBOX_FILE);
        } catch (Exception failure) {
            throw failed();
        }
    }

    private Path createWorkspace(String container) {
        try {
            Path workspace = Files.createDirectory(workspaceRoot.resolve(container));
            setOwnerDirectory(workspace);
            return workspace;
        } catch (Exception failure) {
            throw failed();
        }
    }

    private Path createDirectory(Path path, Set<PosixFilePermission> permissions) {
        try {
            Path directory = Files.createDirectory(path);
            setPermissions(directory, permissions);
            return directory;
        } catch (Exception failure) {
            throw failed();
        }
    }

    private boolean cleanupContainer(boolean created, String container, Throwable primary) {
        if (!created) {
            return true;
        }
        try {
            DockerCommandRunner.CommandResult result = commands.run(
                    command("rm", "-f", "--volumes", container), CONTROL_TIMEOUT);
            if (result.exitCode() != 0) {
                primary.addSuppressed(failed());
                return false;
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            primary.addSuppressed(interrupted);
            return false;
        } catch (Error cleanupFatal) {
            if (cleanupFatal != primary) {
                primary.addSuppressed(cleanupFatal);
            }
            return false;
        } catch (RuntimeException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
            return false;
        }
    }

    private void close(SandboxResult result) {
        if (result != null) {
            result.close();
        }
    }

    private static Path executable(Path candidate) {
        Objects.requireNonNull(candidate, "docker");
        try {
            if (!candidate.isAbsolute()
                    || Files.isSymbolicLink(candidate)
                    || !Files.isRegularFile(candidate, NOFOLLOW_LINKS)
                    || !Files.isExecutable(candidate)) {
                throw new IllegalArgumentException("Docker runtime configuration is invalid");
            }
            return candidate.toRealPath(NOFOLLOW_LINKS);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Docker runtime configuration is invalid");
        }
    }

    private static Path socket(Path candidate) {
        Objects.requireNonNull(candidate, "socket");
        String value = candidate.toAbsolutePath().normalize().toString();
        if (!candidate.isAbsolute() || !SOCKET.matcher(value).matches()) {
            throw new IllegalArgumentException("Docker runtime configuration is invalid");
        }
        return Path.of(value);
    }

    private static String image(String candidate) {
        if (candidate == null || !IMAGE.matcher(candidate).matches()) {
            throw new IllegalArgumentException("Docker runtime configuration is invalid");
        }
        return candidate;
    }

    private static Path workspaceRoot(Path candidate) {
        Objects.requireNonNull(candidate, "workspaceRoot");
        try {
            if (!candidate.isAbsolute()
                    || Files.isSymbolicLink(candidate)
                    || !Files.isDirectory(candidate, NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Docker runtime configuration is invalid");
            }
            return candidate.toRealPath(NOFOLLOW_LINKS);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Docker runtime configuration is invalid");
        }
    }

    private static String containerName(JobLease lease) {
        return "gen2spring-" + lease.jobId().value() + "-" + lease.fencingToken();
    }

    private static void setOwnerDirectory(Path path) throws java.io.IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_DIRECTORY);
        } catch (UnsupportedOperationException ignored) {
            // Windows tests rely on the platform ACL inherited from the private workspace root.
        }
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions)
            throws java.io.IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows tests rely on the platform ACL inherited from the private workspace root.
        }
    }

    private static SandboxRuntimeFailure failed() {
        return new SandboxRuntimeFailure();
    }

    private record ContainerState(boolean outOfMemory, int exitCode) {}
}
