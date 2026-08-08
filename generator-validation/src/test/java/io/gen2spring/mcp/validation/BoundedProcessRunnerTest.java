package io.gen2spring.mcp.validation;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.validation.support.SleepingProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
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

    private List<String> javaCommand(String... args) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(
                java.toString(), "-cp", System.getProperty("java.class.path"), SleepingProcess.class.getName()));
        command.addAll(List.of(args));
        return List.copyOf(command);
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
