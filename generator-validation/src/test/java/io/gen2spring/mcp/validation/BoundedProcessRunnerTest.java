package io.gen2spring.mcp.validation;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.validation.support.EnvironmentProbeProcess;
import io.gen2spring.mcp.validation.support.SleepingProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedProcessRunnerTest {
    @TempDir
    Path tempDir;

    private final BoundedProcessRunner runner = new BoundedProcessRunner();

    @Test
    void terminatesAProcessAndItsDescendantAfterTheConfiguredTimeout() throws Exception {
        Path childPid = tempDir.resolve("child.pid");

        var result = runner.run(javaCommand("child", childPid.toString()), tempDir, Duration.ofMillis(300), 64 * 1024);

        assertTrue(result.timedOut());
        assertFalse(result.processAlive());
        long pid = waitForPid(childPid);
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

    @Test
    void collectorFailureTerminatesTheProcess() throws Exception {
        Path pidFile = tempDir.resolve("collector.pid");
        var failing = new BoundedProcessRunner((input, limit) -> {
            Thread.sleep(300);
            throw new IOException("synthetic collector failure");
        });

        assertThrows(IOException.class,
                () -> failing.run(javaCommand("pid", pidFile.toString()), tempDir, Duration.ofSeconds(10), 1024));

        assertTrue(waitUntilDead(waitForPid(pidFile)));
    }

    @Test
    void interruptionStillTerminatesTheProcess() throws Exception {
        Path pidFile = tempDir.resolve("interrupt.pid");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> runner.run(
                    javaCommand("pid", pidFile.toString()), tempDir, Duration.ofSeconds(30), 1024));
            long pid = waitForPid(pidFile);
            future.cancel(true);
            assertThrows(java.util.concurrent.CancellationException.class, future::get);
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
}
