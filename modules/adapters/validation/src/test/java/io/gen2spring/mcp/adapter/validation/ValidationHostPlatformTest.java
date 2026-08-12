package io.gen2spring.mcp.adapter.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidationHostPlatformTest {
    private static final String SAFE_FAILURE = "Validation host platform is unsupported";

    @TempDir
    Path tempDir;

    @Test
    void selectsKnownHostsCaseInsensitivelyAndRejectsUnknownHostsSafely() throws Exception {
        Path systemRoot = windowsSystemRoot();

        assertEquals("gradlew", ValidationHostPlatform.forHost("LiNuX", Map.of()).wrapperFileName());
        assertEquals("gradlew", ValidationHostPlatform.forHost("Mac OS X", Map.of()).wrapperFileName());
        assertEquals("gradlew", ValidationHostPlatform.forHost("FreeBSD", Map.of()).wrapperFileName());
        assertEquals("gradlew", ValidationHostPlatform.forHost("OpenBSD", Map.of()).wrapperFileName());
        assertEquals("gradlew", ValidationHostPlatform.forHost("SunOS", Map.of()).wrapperFileName());
        assertEquals("gradlew", ValidationHostPlatform.forHost("AIX", Map.of()).wrapperFileName());
        assertEquals("gradlew.bat", ValidationHostPlatform.forHost(
                "wInDoWs 11", Map.of("SystemRoot", systemRoot.toString())).wrapperFileName());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ValidationHostPlatform.forHost("private-unknown-host", Map.of()));
        assertEquals(SAFE_FAILURE, failure.getMessage());
        assertFalse(failure.toString().contains("private-unknown-host"));
    }

    @Test
    void buildsExactPosixAndWindowsCommandsWithoutWorkspacePathExpansion() throws Exception {
        ValidationHostPlatform posix = ValidationHostPlatform.forHost("Linux", Map.of());
        Path posixSnapshot = tempDir.resolve(".gradlew-validated-123");
        Path posixHome = tempDir.resolve("java-21");
        assertEquals(List.of(
                posixSnapshot.toString(),
                "-Dorg.gradle.java.installations.auto-detect=false",
                "-Dorg.gradle.java.installations.auto-download=false",
                "-Dorg.gradle.java.installations.paths=" + posixHome,
                "classes", "test", "bootJar", "--no-daemon", "--non-interactive"),
                posix.buildCommand(posixSnapshot, posixHome));

        Path systemRoot = windowsSystemRoot();
        ValidationHostPlatform windows = ValidationHostPlatform.forHost(
                "Windows Server 2025", Map.of("SystemRoot", systemRoot.toString()));
        Path snapshot = tempDir.resolve("private-workspace").resolve(".gradlew-validated-123.bat");
        Path javaHome = tempDir.resolve("Java 17");
        assertEquals(List.of(
                systemRoot.resolve("System32/cmd.exe").toString(),
                "/D", "/E:OFF", "/V:OFF", "/S", "/C",
                "call .gradlew-validated-123.bat "
                        + "-Dorg.gradle.java.installations.auto-detect=false "
                        + "-Dorg.gradle.java.installations.auto-download=false "
                        + "-Dorg.gradle.java.installations.paths=\"" + javaHome + "\" "
                        + "classes test bootJar --no-daemon --non-interactive"),
                windows.buildCommand(snapshot, javaHome));
        assertFalse(windows.buildCommand(snapshot, javaHome).getLast().contains("private-workspace"));
    }

    @Test
    void exposesExactWrapperAndJavaExecutableContracts() throws Exception {
        ValidationHostPlatform posix = ValidationHostPlatform.forHost("Linux", Map.of());
        ValidationHostPlatform windows = ValidationHostPlatform.forHost(
                "Windows 11", Map.of("SystemRoot", windowsSystemRoot().toString()));
        Path home = tempDir.resolve("jdk");

        assertEquals(home.resolve("bin/java"), posix.javaExecutable(home));
        assertEquals(home.resolve("bin/java.exe"), windows.javaExecutable(home));
        assertEquals(true, posix.requiresOwnerExecutable());
        assertEquals(false, windows.requiresOwnerExecutable());
    }

    @Test
    void resolvesOnlyJavaExeForAnInjectedWindowsRuntime() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("windows-runtime/bin")).getParent().toRealPath();
        Files.writeString(home.resolve("bin/java"), "wrong-platform");
        Path javaExe = Files.writeString(home.resolve("bin/java.exe"), "windows-runtime").toRealPath();
        AtomicReference<Path> probed = new AtomicReference<>();
        ValidationHostPlatform windows = ValidationHostPlatform.forHost(
                "Windows 11", Map.of("SystemRoot", windowsSystemRoot().toString()));
        var resolver = new JavaRuntimeResolver(
                Map.of("GEN2SPRING_JAVA_17_HOME", home.toString()), home,
                executable -> {
                    probed.set(executable);
                    return 17;
                }, windows);

        var runtime = resolver.resolve(io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java17-mvc-streamable").orElseThrow());

        assertEquals(javaExe, runtime.executable());
        assertEquals(javaExe, probed.get());
        runtime.requireStable();
    }

    @Test
    void rejectsEveryWindowsCommandControlAndMetacharacterWithOneSafeMessage() {
        List<String> unsafe = List.of("\r", "\n", "\0", "\"", "%", "!", "^", "&", "|", "<", ">");
        for (String marker : unsafe) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ValidationHostPlatform.requireSafeWindowsArgument("C:\\Java" + marker + "17"));
            assertEquals(SAFE_FAILURE, failure.getMessage());
            assertFalse(failure.toString().contains("C:\\Java"));
        }
    }

    private Path windowsSystemRoot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("Windows"));
        Files.createDirectories(root.resolve("System32"));
        Files.writeString(root.resolve("System32/cmd.exe"), "fixed-test-command");
        return root.toRealPath();
    }
}
