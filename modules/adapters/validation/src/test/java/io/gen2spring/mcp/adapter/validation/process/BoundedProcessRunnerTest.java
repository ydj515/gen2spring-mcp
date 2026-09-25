package io.gen2spring.mcp.adapter.validation.process;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.adapter.validation.support.EnvironmentProbeProcess;
import io.gen2spring.mcp.adapter.validation.support.SleepingProcess;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedProcessRunnerTest {
    @TempDir
    Path tempDir;

    private final BoundedProcessRunner runner = new BoundedProcessRunner();

    @RepeatedTest(10)
    void terminatesAProcessAndItsDescendantAfterTheConfiguredTimeout() throws Exception {
        Path childPid = tempDir.resolve("child.pid");
        BoundedProcessRunner.RunningProcess process = runner.start(
                javaCommand("child", childPid.toString()), tempDir, 64 * 1024);
        long pid;
        boolean timedOut;

        try (process) {
            pid = waitForPid(childPid);
            timedOut = !process.awaitExit(Duration.ofMillis(300));
        }
        var result = process.result(timedOut);

        assertTrue(result.timedOut());
        assertFalse(result.processAlive());
        assertTrue(result.exitCode() != -1, "cleanup must observe the root process exit code");
        assertTrue(waitUntilDead(pid), "descendant must not survive timeout cleanup");
    }

    @Test
    void drainsLargeStdoutAndStderrWithoutExceedingEachCaptureBound() throws Exception {
        var result = runner.run(javaCommand("large"), tempDir, Duration.ofSeconds(10), 4_096);

        assertFalse(result.timedOut());
        assertTrue(result.stdout().observedBytes() >= 512 * 1024);
        assertTrue(result.stderr().observedBytes() >= 512 * 1024);
        assertTrue(result.stdout().retainedBytes() <= 4_096);
        assertTrue(result.stderr().retainedBytes() <= 4_096);
        assertTrue(result.stdout().truncated());
        assertTrue(result.stderr().truncated());
    }

    @Test
    void resultNeverExposesCapturedSecretText() throws Exception {
        var result = runner.run(javaCommand("secret"), tempDir, Duration.ofSeconds(5), 4_096);

        assertFalse(result.toString().contains("secret-value"));
        assertFalse(result.safeSummary().contains("secret-value"));
    }

    @RepeatedTest(10)
    void collectorFailureTerminatesTheProcess() throws Exception {
        Path workingDirectory = Files.createDirectory(tempDir.resolve("collector-workspace"));
        Path pidFile = workingDirectory.resolve("collector.pid");
        IOException collectorFailure = new IOException("synthetic collector failure");
        CountDownLatch collectorsReady = new CountDownLatch(2);
        var failing = new BoundedProcessRunner((input, limit) -> {
            // Inject failure only after the helper closes its PID file, including on Windows.
            assertEquals('R', input.read(), "helper must signal readiness before collector failure");
            collectorsReady.countDown();
            assertTrue(collectorsReady.await(3, SECONDS), "both collectors must receive readiness");
            throw collectorFailure;
        });

        IOException failure = null;
        try {
            failing.run(javaCommand("pid", pidFile.toString()), workingDirectory, Duration.ofSeconds(10), 1024);
        } catch (IOException exception) {
            failure = exception;
        }

        assertTrue(failure != null, "collector failure must be propagated");
        assertSame(collectorFailure, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertNoRepeatedThrowableReferences(failure);
        assertTrue(waitUntilDead(waitForPid(pidFile)));
        Files.delete(pidFile);
        deleteReleasedWorkingDirectory(workingDirectory);
    }

    private void deleteReleasedWorkingDirectory(Path directory) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                Files.delete(directory);
                return;
            } catch (FileSystemException exception) {
                // Retry transient Windows directory locks only after asserting that the helper process is dead.
                if (System.nanoTime() >= deadline) {
                    throw exception;
                }
                Thread.sleep(25);
            }
        }
    }

    @Test
    void interruptionStillTerminatesTheProcess() throws Exception {
        Path pidFile = tempDir.resolve("interrupt.pid");
        CountDownLatch cleanupFinished = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> {
                try {
                    return runner.run(javaCommand("pid", pidFile.toString()), tempDir, Duration.ofSeconds(30), 1024);
                } finally {
                    cleanupFinished.countDown();
                }
            });
            long pid = waitForPid(pidFile);
            future.cancel(true);
            assertThrows(java.util.concurrent.CancellationException.class, future::get);
            // Future cancellation completes before the interrupted task finishes its bounded cleanup.
            assertTrue(cleanupFinished.await(15, SECONDS), "interrupted runner must finish cleanup");
            assertTrue(waitUntilDead(pid));
        }
    }

    @Test
    void applicationEnvironmentContainsOnlyValidatedOverrides() throws Exception {
        Map<String, String> overrides = Map.of(
                "PROVIDER_BASE_URL", "http://127.0.0.1:12345",
                "KMA_SERVICE_KEY", "mcp-validation-secret-1");
        Path output = tempDir.resolve("application-environment.txt");

        try (var process = runner.start(probeCommand(output,
                "PROVIDER_BASE_URL", "KMA_SERVICE_KEY", "UNRELATED_PARENT_SECRET", "PATH"),
                tempDir, 8_192, overrides)) {
            assertTrue(process.awaitExit(Duration.ofSeconds(3)));
        }

        assertEquals(overrides, readProbeOutput(output));
        assertFalse(readProbeOutput(output).containsKey("UNRELATED_PARENT_SECRET"));
    }

    @Test
    void applicationEnvironmentRejectsInvalidOverridesWithoutEchoingTheirContents() {
        String oversizedKey = "A".repeat(129);
        String oversizedValue = "a".repeat(2_049);
        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("VALID", null);

        Stream.of(
                        Map.of("lowercase", "value"),
                        Map.of(oversizedKey, "value"),
                        Map.of("INVALID\nKEY", "value"))
                .forEach(overrides -> assertInvalidEnvironment(overrides, "Environment override key is invalid"));
        Stream.of(
                        nullValue,
                        Map.of("VALID", oversizedValue),
                        Map.of("VALID", "mcp-validation-secret-1\u0000"))
                .forEach(overrides -> assertInvalidEnvironment(overrides, "Environment override value is invalid"));
        assertInvalidEnvironment(null, "Environment overrides are required");
    }

    @Test
    void applicationEnvironmentCopiesOverridesBeforeTheProcessStarts() throws Exception {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("PROVIDER_BASE_URL", "http://127.0.0.1:12345");
        Path output = tempDir.resolve("copied-environment.txt");

        try (var process = runner.start(probeCommand(output, "PROVIDER_BASE_URL", "KMA_SERVICE_KEY"),
                tempDir, 8_192, overrides)) {
            overrides.clear();
            overrides.put("KMA_SERVICE_KEY", "caller-mutation");
            assertTrue(process.awaitExit(Duration.ofSeconds(3)));
        }

        assertEquals(Map.of("PROVIDER_BASE_URL", "http://127.0.0.1:12345"), readProbeOutput(output));
    }

    @Test
    void existingStartOverloadStillInheritsTheParentEnvironment() throws Exception {
        Path output = tempDir.resolve("inherited-environment.txt");

        try (var process = runner.start(probeCommand(output, "PATH"), tempDir, 8_192)) {
            assertTrue(process.awaitExit(Duration.ofSeconds(3)));
        }

        assertEquals(System.getenv("PATH"), readProbeOutput(output).get("PATH"));
    }

    @Test
    void buildEnvironmentOverlaysJavaHomeAndKeepsTheParentPath() throws Exception {
        Path output = tempDir.resolve("build-environment.txt");

        BoundedProcessRunner.Result result = runner.runWithEnvironmentOverlay(
                probeCommand(output, "JAVA_HOME", "PATH"),
                tempDir,
                Duration.ofSeconds(3),
                8_192,
                Map.of("JAVA_HOME", "/validated/java-home"));

        assertEquals(0, result.exitCode());
        assertEquals("/validated/java-home", readProbeOutput(output).get("JAVA_HOME"));
        assertEquals(System.getenv("PATH"), readProbeOutput(output).get("PATH"));
    }

    private List<String> javaCommand(String... args) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(
                java.toString(), "-cp", System.getProperty("java.class.path"), SleepingProcess.class.getName()));
        command.addAll(List.of(args));
        return List.copyOf(command);
    }

    private List<String> probeCommand(Path output, String... names) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(
                java.toString(), "-cp", System.getProperty("java.class.path"), EnvironmentProbeProcess.class.getName(),
                output.toString()));
        command.addAll(List.of(names));
        return List.copyOf(command);
    }

    private static Map<String, String> readProbeOutput(Path output) throws IOException {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String line : Files.readAllLines(output)) {
            int delimiter = line.indexOf('=');
            environment.put(line.substring(0, delimiter), line.substring(delimiter + 1));
        }
        return Map.copyOf(environment);
    }

    private void assertInvalidEnvironment(Map<String, String> overrides, String expectedMessage) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> runner.start(probeCommand(tempDir.resolve("invalid-environment.txt"), "VALID"),
                        tempDir, 8_192, overrides));
        assertEquals(expectedMessage, exception.getMessage());
    }

    private long waitForPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(pidFile) && Files.size(pidFile) > 0, "helper did not publish its pid");
        return Long.parseLong(Files.readString(pidFile));
    }

    private boolean waitUntilDead(long pid) throws InterruptedException, ExecutionException {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null) {
            return true;
        }
        try {
            handle.onExit().get(3, SECONDS);
            return !handle.isAlive();
        } catch (java.util.concurrent.TimeoutException exception) {
            return false;
        }
    }

    private void assertNoRepeatedThrowableReferences(Throwable root) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            assertTrue(visited.add(current), "exception graph must not contain repeated throwable references");
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
    }
}
