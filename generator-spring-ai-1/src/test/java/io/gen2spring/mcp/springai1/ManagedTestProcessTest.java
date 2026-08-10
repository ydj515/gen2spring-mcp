package io.gen2spring.mcp.springai1;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedTestProcessTest {
    @TempDir
    Path tempDir;

    @Test
    void terminatesAndReapsATimedOutProcess() throws Exception {
        Process process = startSleeper();

        assertThrows(TimeoutException.class, () -> ManagedTestProcess.run(
                process, Duration.ofMillis(100), Duration.ofSeconds(2)));

        assertStopped(process);
    }

    @Test
    void terminatesAndReapsWhenOutputCollectionFails() throws Exception {
        Process process = startSleeper();

        assertThrows(ExecutionException.class, () -> ManagedTestProcess.run(
                process,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                input -> {
                    throw new IOException("synthetic output failure");
                }));

        assertStopped(process);
    }

    @Test
    void retainsAndTerminatesAnObservedDescendantAfterItsParentExits() throws Exception {
        Path childPid = tempDir.resolve("child.pid");
        Process process = startParentThatLeavesAChild(childPid);

        assertThrows(TimeoutException.class, () -> ManagedTestProcess.run(
                process, Duration.ofSeconds(5), Duration.ofSeconds(2)));

        assertTrue(Files.isRegularFile(childPid));
        long pid = Long.parseLong(Files.readString(childPid).trim());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertStopped(process);
    }

    @Test
    void repeatedInterruptionDuringCleanupStillTerminatesTheProcessTree() throws Exception {
        Process process = startSleeper();
        Thread testThread = Thread.currentThread();
        AtomicBoolean interrupting = new AtomicBoolean(true);
        Thread interrupter = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(1_000);
                while (interrupting.get()) {
                    testThread.interrupt();
                    Thread.sleep(10);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        boolean aliveAfterRunner;
        try {
            assertThrows(InterruptedException.class, () -> ManagedTestProcess.run(
                    process, Duration.ofSeconds(10), Duration.ofSeconds(2)));
            aliveAfterRunner = process.isAlive();
        } finally {
            try {
                stopInterrupterUninterruptibly(interrupter, interrupting);
            } finally {
                Thread.interrupted();
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(2, SECONDS);
                }
            }
        }
        assertFalse(aliveAfterRunner);
    }

    private void stopInterrupterUninterruptibly(Thread interrupter, AtomicBoolean interrupting) {
        interrupting.set(false);
        interrupter.interrupt();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (interrupter.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("Interrupter did not stop");
            }
            try {
                interrupter.join(Math.max(1, Math.min(100, Duration.ofNanos(remaining).toMillis())));
            } catch (InterruptedException ignored) {
                // The test intentionally races interrupts; keep joining until their source has stopped.
            }
        }
    }

    private Process startSleeper() throws IOException {
        Path source = tempDir.resolve("SleepChild.java");
        Files.writeString(source, """
                public class SleepChild {
                    public static void main(String[] args) throws Exception {
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            try {
                                Thread.sleep(60_000);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                        }));
                        Thread.sleep(60_000);
                    }
                }
                """);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, source.toString())
                .redirectErrorStream(true)
                .start();
    }

    private Process startParentThatLeavesAChild(Path childPid) throws IOException {
        Path child = tempDir.resolve("LongLivedChild.java");
        Files.writeString(child, """
                public class LongLivedChild {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(60_000);
                    }
                }
                """);
        Path parent = tempDir.resolve("SpawnAndExit.java");
        Files.writeString(parent, """
                import java.nio.file.Files;
                import java.nio.file.Path;

                public class SpawnAndExit {
                    public static void main(String[] args) throws Exception {
                        Process child = new ProcessBuilder(args[0], args[1]).inheritIO().start();
                        Files.writeString(Path.of(args[2]), Long.toString(child.pid()));
                        Thread.sleep(600);
                    }
                }
                """);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, parent.toString(), java, child.toString(), childPid.toString())
                .redirectErrorStream(true)
                .start();
    }

    private void assertStopped(Process process) throws Exception {
        process.onExit().get(2, SECONDS);
        assertFalse(process.isAlive());
        assertFalse(process.descendants().anyMatch(ProcessHandle::isAlive));
    }
}
