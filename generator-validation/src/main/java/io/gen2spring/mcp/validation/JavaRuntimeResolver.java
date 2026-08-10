package io.gen2spring.mcp.validation;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JavaRuntimeResolver {
    private static final String SAFE_FAILURE = "Target Java runtime is unavailable or invalid";
    private static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_PROBE_OUTPUT_BYTES = 32 * 1024;

    private final Map<String, String> environment;
    private final Path currentJavaHome;
    private final RuntimeProbe probe;

    JavaRuntimeResolver() {
        this(System.getenv(), Path.of(System.getProperty("java.home")), new DefaultRuntimeProbe());
    }

    JavaRuntimeResolver(Map<String, String> environment, Path currentJavaHome, RuntimeProbe probe) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.currentJavaHome = Objects.requireNonNull(currentJavaHome, "currentJavaHome");
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    ResolvedJavaRuntime resolve(CompatibilityProfile profile) {
        if (profile == null || profile.target() == null || profile.target().javaVersion() <= 0) {
            throw safeFailure();
        }
        int feature = profile.target().javaVersion();
        String configured = environment.get("GEN2SPRING_JAVA_" + feature + "_HOME");
        Path candidate;
        try {
            candidate = configured == null ? currentJavaHome : Path.of(configured);
            ResolvedJavaRuntime runtime = pin(candidate);
            if (probe.feature(runtime.executable()) != feature) {
                throw safeFailure();
            }
            return runtime;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw safeFailure();
        } catch (IOException | RuntimeException exception) {
            throw safeFailure();
        }
    }

    private static ResolvedJavaRuntime pin(Path candidate) throws IOException {
        if (candidate == null || !candidate.isAbsolute() || Files.isSymbolicLink(candidate)) {
            throw safeFailure();
        }
        Path home = candidate.normalize();
        BasicFileAttributes homeAttributes = Files.readAttributes(home, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!homeAttributes.isDirectory() || !home.toRealPath().equals(home)) {
            throw safeFailure();
        }
        Object homeFileKey = requiredFileKey(homeAttributes);

        Path executable = home.resolve("bin/java").normalize();
        if (!executable.startsWith(home) || Files.isSymbolicLink(executable)
                || !executable.toRealPath().equals(executable)) {
            throw safeFailure();
        }
        BasicFileAttributes executableAttributes =
                Files.readAttributes(executable, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!executableAttributes.isRegularFile() || !Files.isExecutable(executable)) {
            throw safeFailure();
        }
        Object executableFileKey = requiredFileKey(executableAttributes);
        return new ResolvedJavaRuntime(home, executable, homeFileKey, executableFileKey);
    }

    private static Object requiredFileKey(BasicFileAttributes attributes) {
        Object fileKey = attributes.fileKey();
        if (fileKey == null) {
            throw safeFailure();
        }
        return fileKey;
    }

    private static JavaRuntimeException safeFailure() {
        return new JavaRuntimeException();
    }

    static final class JavaRuntimeException extends IllegalArgumentException {
        private JavaRuntimeException() {
            super(SAFE_FAILURE);
        }
    }

    @FunctionalInterface
    interface RuntimeProbe {
        int feature(Path javaExecutable) throws IOException, InterruptedException;
    }

    static final class ResolvedJavaRuntime {
        private final Path home;
        private final Path executable;
        private final Object homeFileKey;
        private final Object executableFileKey;

        private ResolvedJavaRuntime(
                Path home,
                Path executable,
                Object homeFileKey,
                Object executableFileKey) {
            this.home = home;
            this.executable = executable;
            this.homeFileKey = homeFileKey;
            this.executableFileKey = executableFileKey;
        }

        Path home() {
            return home;
        }

        Path executable() {
            return executable;
        }

        void requireStable() {
            try {
                if (Files.isSymbolicLink(home) || !home.toRealPath().equals(home)) {
                    throw safeFailure();
                }
                BasicFileAttributes currentHome =
                        Files.readAttributes(home, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (!currentHome.isDirectory() || !homeFileKey.equals(requiredFileKey(currentHome))) {
                    throw safeFailure();
                }
                if (Files.isSymbolicLink(executable) || !executable.toRealPath().equals(executable)) {
                    throw safeFailure();
                }
                BasicFileAttributes currentExecutable =
                        Files.readAttributes(executable, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (!currentExecutable.isRegularFile()
                        || !Files.isExecutable(executable)
                        || !executableFileKey.equals(requiredFileKey(currentExecutable))) {
                    throw safeFailure();
                }
            } catch (IOException | RuntimeException exception) {
                throw safeFailure();
            }
        }
    }

    static final class DefaultRuntimeProbe implements RuntimeProbe {
        private static final String SAFE_PROBE_FAILURE = "Target Java runtime probe failed safely";
        private static final Pattern SPECIFICATION_VERSION = Pattern.compile(
                "^\\s*java\\.specification\\.version\\s*=\\s*([0-9]+(?:\\.[0-9]+)*)\\s*$");
        private static final Duration COLLECTOR_TIMEOUT = Duration.ofSeconds(2);

        private final Duration timeout;
        private final int maxRetainedBytes;

        private DefaultRuntimeProbe() {
            this(DEFAULT_PROBE_TIMEOUT, DEFAULT_MAX_PROBE_OUTPUT_BYTES);
        }

        DefaultRuntimeProbe(Duration timeout, int maxRetainedBytes) {
            if (timeout == null || timeout.isZero() || timeout.isNegative() || maxRetainedBytes <= 0) {
                throw new IllegalArgumentException("Runtime probe bounds are invalid");
            }
            this.timeout = timeout;
            this.maxRetainedBytes = maxRetainedBytes;
        }

        @Override
        public int feature(Path javaExecutable) throws IOException, InterruptedException {
            Process process;
            try {
                process = new ProcessBuilder(
                        javaExecutable.toString(), "-XshowSettings:properties", "-version")
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException | RuntimeException exception) {
                throw probeFailure();
            }

            ExecutorService executor = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("java-runtime-probe-", 0).factory());
            Future<List<String>> versions = executor.submit(
                    () -> collectSpecificationVersions(process.getInputStream(), maxRetainedBytes));
            boolean exited = false;
            try {
                exited = process.waitFor(timeout.toNanos(), NANOSECONDS);
                if (!exited) {
                    terminate(process);
                    awaitVersions(versions);
                    throw probeFailure();
                }
                List<String> observed = awaitVersions(versions);
                if (process.exitValue() != 0 || observed.size() != 1) {
                    throw probeFailure();
                }
                return parseFeature(observed.getFirst());
            } catch (InterruptedException exception) {
                terminate(process);
                try {
                    awaitVersions(versions);
                } catch (IOException ignored) {
                    // The externally visible failure remains the original interruption.
                } catch (InterruptedException repeatedInterruption) {
                    Thread.currentThread().interrupt();
                }
                throw exception;
            } catch (IOException | RuntimeException exception) {
                if (!exited || process.isAlive()) {
                    terminate(process);
                }
                throw probeFailure();
            } finally {
                versions.cancel(true);
                executor.shutdownNow();
                try {
                    process.getInputStream().close();
                } catch (IOException ignored) {
                    // Probe output is never surfaced; failure remains fixed and value-free.
                }
            }
        }

        private static List<String> collectSpecificationVersions(InputStream input, int maxLineBytes)
                throws IOException {
            List<String> versions = new ArrayList<>(2);
            ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maxLineBytes, 8_192));
            boolean oversized = false;
            try (input) {
                byte[] buffer = new byte[8_192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    for (int index = 0; index < read; index++) {
                        int value = buffer[index] & 0xff;
                        if (value == '\n') {
                            inspectLine(line, oversized, versions);
                            line.reset();
                            oversized = false;
                        } else if (!oversized) {
                            if (line.size() < maxLineBytes) {
                                line.write(value);
                            } else {
                                oversized = true;
                                line.reset();
                            }
                        }
                    }
                }
                inspectLine(line, oversized, versions);
            }
            return List.copyOf(versions);
        }

        private static void inspectLine(
                ByteArrayOutputStream line,
                boolean oversized,
                List<String> versions) {
            if (oversized || line.size() == 0) {
                return;
            }
            Matcher matcher = SPECIFICATION_VERSION.matcher(line.toString(StandardCharsets.UTF_8));
            if (matcher.matches() && versions.size() < 2) {
                versions.add(matcher.group(1));
            }
        }

        private static List<String> awaitVersions(Future<List<String>> future) throws IOException, InterruptedException {
            try {
                return future.get(COLLECTOR_TIMEOUT.toNanos(), NANOSECONDS);
            } catch (ExecutionException | TimeoutException | java.util.concurrent.CancellationException exception) {
                throw probeFailure();
            }
        }

        private static int parseFeature(String version) throws IOException {
            try {
                String[] parts = version.split("\\.");
                int feature = Integer.parseInt(parts[0]);
                if (feature == 1 && parts.length > 1) {
                    feature = Integer.parseInt(parts[1]);
                }
                if (feature <= 0) {
                    throw probeFailure();
                }
                return feature;
            } catch (NumberFormatException exception) {
                throw probeFailure();
            }
        }

        private static void terminate(Process process) {
            List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
            tree.add(process.toHandle());
            tree.reversed().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
            waitForExit(tree, Duration.ofMillis(200));
            tree.reversed().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            waitForExit(tree, Duration.ofSeconds(1));
        }

        private static void waitForExit(List<ProcessHandle> handles, Duration timeout) {
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(10);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static IOException probeFailure() {
            return new IOException(SAFE_PROBE_FAILURE);
        }
    }
}
