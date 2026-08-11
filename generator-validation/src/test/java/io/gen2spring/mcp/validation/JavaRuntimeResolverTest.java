package io.gen2spring.mcp.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaRuntimeResolverTest {
    private static final String SAFE_FAILURE = "Target Java runtime is unavailable or invalid";

    @TempDir
    Path tempDir;

    @Test
    void environmentRuntimeTakesPrecedenceOverTheCurrentRuntime() throws Exception {
        Path environmentHome = runtimeHome("environment");
        Path currentHome = runtimeHome("current");
        AtomicReference<Path> probed = new AtomicReference<>();
        var resolver = new JavaRuntimeResolver(
                Map.of("GEN2SPRING_JAVA_17_HOME", environmentHome.toString()),
                currentHome,
                executable -> {
                    probed.set(executable);
                    return 17;
                });

        var runtime = resolver.resolve(java17());

        assertEquals(environmentHome, runtime.home());
        assertEquals(environmentHome.resolve("bin/java"), runtime.executable());
        assertEquals(runtime.executable(), probed.get());
    }

    @Test
    void matchingCurrentRuntimeIsUsedWhenTheEnvironmentIsAbsent() throws Exception {
        Path currentHome = runtimeHome("current");
        var resolver = new JavaRuntimeResolver(Map.of(), currentHome, executable -> 21);

        var runtime = resolver.resolve(CompatibilityProfile.p0());

        assertEquals(currentHome, runtime.home());
        assertEquals(currentHome.resolve("bin/java"), runtime.executable());
    }

    @Test
    void missingTargetRuntimeFailsWithoutFallingBackToAWrongCurrentVersion() throws Exception {
        Path currentHome = runtimeHome("current");
        var resolver = new JavaRuntimeResolver(Map.of(), currentHome, executable -> 21);

        assertSafeFailure(() -> resolver.resolve(java17()), currentHome);
    }

    @Test
    void rejectsRelativeAndNonDirectoryHomesWithoutLeakingTheirValues() throws Exception {
        var relative = new JavaRuntimeResolver(
                Map.of("GEN2SPRING_JAVA_17_HOME", "relative-secret-home"),
                runtimeHome("current"),
                executable -> 17);
        Path fileHome = Files.writeString(tempDir.resolve("secret-home-file"), "not a directory");
        var nonDirectory = new JavaRuntimeResolver(
                Map.of("GEN2SPRING_JAVA_17_HOME", fileHome.toString()),
                runtimeHome("other-current"),
                executable -> 17);

        assertSafeFailure(() -> relative.resolve(java17()), Path.of("relative-secret-home"));
        assertSafeFailure(() -> nonDirectory.resolve(java17()), fileHome);
    }

    @Test
    void rejectsASymlinkHome() throws Exception {
        Path physical = runtimeHome("physical-secret-home");
        Path linked = tempDir.resolve("linked-secret-home");
        Files.createSymbolicLink(linked, physical);
        var resolver = new JavaRuntimeResolver(
                Map.of("GEN2SPRING_JAVA_17_HOME", linked.toString()),
                runtimeHome("current"),
                executable -> 17);

        assertSafeFailure(() -> resolver.resolve(java17()), linked);
    }

    @Test
    void rejectsMissingSymlinkedAndNonExecutableJavaExecutables() throws Exception {
        Path missingHome = Files.createDirectories(tempDir.resolve("missing/bin")).getParent().toRealPath();

        Path linkedHome = Files.createDirectories(tempDir.resolve("linked/bin")).getParent().toRealPath();
        Path outside = executable(tempDir.resolve("outside-secret-java"), "#!/bin/sh\nexit 0\n");
        Files.createSymbolicLink(linkedHome.resolve("bin/java"), outside);

        Path nonExecutableHome = Files.createDirectories(tempDir.resolve("non-executable/bin"))
                .getParent().toRealPath();
        Path nonExecutable = Files.writeString(nonExecutableHome.resolve("bin/java"), "not executable");
        assertTrue(nonExecutable.toFile().setExecutable(false, false));

        assertSafeFailure(() -> resolverFor(missingHome, executable -> 17).resolve(java17()), missingHome);
        assertSafeFailure(() -> resolverFor(linkedHome, executable -> 17).resolve(java17()), linkedHome);
        assertSafeFailure(() -> resolverFor(nonExecutableHome, executable -> 17).resolve(java17()), nonExecutableHome);
    }

    @Test
    void rejectsWrongVersionProbeFailureAndInterruptedProbeWithOneSafeMessage() throws Exception {
        Path wrongHome = runtimeHome("wrong-secret-home");
        Path failedHome = runtimeHome("failed-secret-home");
        Path interruptedHome = runtimeHome("interrupted-secret-home");

        assertSafeFailure(() -> resolverFor(wrongHome, executable -> 21).resolve(java17()), wrongHome);
        assertSafeFailure(() -> resolverFor(failedHome, executable -> {
            throw new IOException("raw-secret-probe-output");
        }).resolve(java17()), failedHome);
        try {
            assertSafeFailure(() -> resolverFor(interruptedHome, executable -> {
                throw new InterruptedException("raw-secret-interrupt-output");
            }).resolve(java17()), interruptedHome);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stableRuntimeRejectsExecutableIdentityReplacement() throws Exception {
        Path home = runtimeHome("swapped-secret-home");
        var runtime = resolverFor(home, executable -> 17).resolve(java17());
        Files.delete(runtime.executable());
        executable(runtime.executable(), "#!/bin/sh\nexit 0\n");

        assertSafeFailure(runtime::requireStable, home);
    }

    @Test
    void defaultProbeParsesOneModernOrLegacySpecificationVersion() throws Exception {
        var probe = new JavaRuntimeResolver.DefaultRuntimeProbe(Duration.ofSeconds(1), 32 * 1024);
        Path modern = executable(tempDir.resolve("modern-java"),
                "#!/bin/sh\nprintf '    java.specification.version = 17\\n' >&2\n");
        Path legacy = executable(tempDir.resolve("legacy-java"),
                "#!/bin/sh\nprintf 'java.specification.version = 1.8\\n' >&2\n");

        assertEquals(17, probe.feature(modern));
        assertEquals(8, probe.feature(legacy));
    }

    @Test
    void defaultProbeDrainsBeyondTheRetentionBoundAndStillParsesTheVersion() throws Exception {
        var probe = new JavaRuntimeResolver.DefaultRuntimeProbe(Duration.ofSeconds(2), 32 * 1024);
        Path java = executable(tempDir.resolve("verbose-java"),
                "#!/bin/sh\ni=0\nwhile [ $i -lt 5000 ]; do printf 'diagnostic-line-%s\\n' \"$i\" >&2; i=$((i+1)); done\n"
                        + "printf 'java.specification.version = 17\\n' >&2\n");

        assertEquals(17, probe.feature(java));
    }

    @Test
    void defaultProbeRejectsDuplicateVersionNonZeroExitAndTimeoutWithoutRawOutput() throws Exception {
        var probe = new JavaRuntimeResolver.DefaultRuntimeProbe(Duration.ofMillis(150), 32 * 1024);
        Path duplicate = executable(tempDir.resolve("duplicate-java"),
                "#!/bin/sh\nprintf 'java.specification.version = 17\\njava.specification.version = 21\\n' >&2\n");
        Path failed = executable(tempDir.resolve("failed-java"),
                "#!/bin/sh\nprintf 'raw-secret-probe-output\\njava.specification.version = 17\\n' >&2\nexit 7\n");
        Path timeout = executable(tempDir.resolve("timeout-java"), "#!/bin/sh\nsleep 30\n");

        assertProbeFailure(() -> probe.feature(duplicate));
        assertProbeFailure(() -> probe.feature(failed));
        long started = System.nanoTime();
        assertProbeFailure(() -> probe.feature(timeout));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void defaultProbeKillsObservedDescendantThatKeepsMergedOutputOpenAfterParentExit() throws Exception {
        var probe = new JavaRuntimeResolver.DefaultRuntimeProbe(Duration.ofSeconds(2), 32 * 1024);
        Path childPid = tempDir.resolve("inherited-output-child.pid");
        Path java = executable(tempDir.resolve("inherited-output-java"),
                "#!/bin/sh\n"
                        + "sleep 30 &\n"
                        + "child=$!\n"
                        + "printf '%s' \"$child\" > '" + shellSingleQuoted(childPid) + "'\n"
                        + "printf 'java.specification.version = 17\\n' >&2\n"
                        + "sleep 1\n");

        long pid = -1;
        try {
            long started = System.nanoTime();
            assertProbeFailure(() -> probe.feature(java));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
            pid = readPid(childPid);
            assertTrue(waitUntilDead(pid));
        } finally {
            destroyIfAlive(pid);
        }
    }

    @Test
    void defaultProbeRejectsAndKillsLiveObservedDescendantAfterCleanParentExit() throws Exception {
        var probe = new JavaRuntimeResolver.DefaultRuntimeProbe(Duration.ofSeconds(2), 32 * 1024);
        Path childPid = tempDir.resolve("closed-output-child.pid");
        Path java = executable(tempDir.resolve("closed-output-java"),
                "#!/bin/sh\n"
                        + "sleep 30 >/dev/null 2>&1 &\n"
                        + "child=$!\n"
                        + "printf '%s' \"$child\" > '" + shellSingleQuoted(childPid) + "'\n"
                        + "printf 'java.specification.version = 17\\n' >&2\n"
                        + "sleep 1\n");

        long pid = -1;
        try {
            assertProbeFailure(() -> probe.feature(java));
            pid = readPid(childPid);
            assertTrue(waitUntilDead(pid));
        } finally {
            destroyIfAlive(pid);
        }
    }

    private JavaRuntimeResolver resolverFor(Path home, JavaRuntimeResolver.RuntimeProbe probe) throws IOException {
        return new JavaRuntimeResolver(
                Map.of("GEN2SPRING_JAVA_17_HOME", home.toString()), runtimeHome("unused-current"), probe);
    }

    private CompatibilityProfile java17() {
        return CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java17-mvc-streamable")
                .orElseThrow();
    }

    private Path runtimeHome(String name) throws IOException {
        Path home = Files.createDirectories(tempDir.resolve(name).resolve("bin")).getParent().toRealPath();
        executable(home.resolve("bin/java"), "#!/bin/sh\nexit 0\n");
        return home;
    }

    private Path executable(Path path, String script) throws IOException {
        Path executable = Files.writeString(path, script);
        assertTrue(executable.toFile().setExecutable(true));
        return executable.toRealPath();
    }

    private void assertSafeFailure(ThrowingAction action, Path secretPath) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals(SAFE_FAILURE, failure.getMessage());
        assertFalse(failure.toString().contains(secretPath.toString()));
        assertFalse(failure.toString().contains("secret"));
    }

    private void assertProbeFailure(ThrowingAction action) {
        IOException failure = assertThrows(IOException.class, action::run);
        assertEquals("Target Java runtime probe failed safely", failure.getMessage());
        assertFalse(failure.toString().contains("raw-secret-probe-output"));
    }

    private long readPid(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while ((!Files.exists(path) || Files.size(path) == 0) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return Long.parseLong(Files.readString(path));
    }

    private boolean waitUntilDead(long pid) throws Exception {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null) {
            return true;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (handle.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return !handle.isAlive();
    }

    private void destroyIfAlive(long pid) {
        if (pid > 0) {
            ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private String shellSingleQuoted(Path path) {
        return path.toString().replace("'", "'\"'\"'");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
