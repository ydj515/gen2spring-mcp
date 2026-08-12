package io.gen2spring.mcp.adapter.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class BoundedDockerCommandRunnerTest {
    private final BoundedDockerCommandRunner runner = new BoundedDockerCommandRunner();

    @Test
    void runsFixedArgvAndCapturesBoundedMergedOutput() throws Exception {
        DockerCommandRunner.CommandResult result = runner.run(command("success"), Duration.ofSeconds(5));

        assertEquals(0, result.exitCode());
        assertEquals("standard\nerror\n", result.output());
        assertFalse(result.toString().contains("standard"));
    }

    @Test
    void rejectsOversizedOutputAndTerminatesTimedOutProcessesWithFixedFailures() {
        SandboxRuntimeFailure oversized = assertThrows(
                SandboxRuntimeFailure.class,
                () -> runner.run(command("oversized"), Duration.ofSeconds(5)));
        assertEquals("Sandbox container execution failed", oversized.getMessage());

        long started = System.nanoTime();
        SandboxRuntimeFailure timedOut = assertThrows(
                SandboxRuntimeFailure.class,
                () -> runner.run(command("sleep"), Duration.ofMillis(100)));
        assertEquals("Sandbox container execution failed", timedOut.getMessage());
        assertFalse(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) > 0);
    }

    @Test
    void rejectsShellControlCharactersBeforeStartingAProcess() {
        assertThrows(IllegalArgumentException.class, () -> runner.run(
                List.of(javaExecutable(), "unsafe\nargument"), Duration.ofSeconds(1)));
    }

    private List<String> command(String mode) {
        return List.of(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                DockerCommandFixture.class.getName(),
                mode);
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
