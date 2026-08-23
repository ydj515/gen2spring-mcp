package io.gen2spring.mcp.adapter.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenBuildToolDriverTest {
    @TempDir
    Path tempDir;

    @Test
    void usesPinnedBatchArgumentsAndExactArtifactName() {
        var driver = new MavenBuildToolDriver(
                new BoundedProcessRunner(),
                ValidationHostPlatform.forHost("Linux", Map.of()),
                Duration.ofMinutes(5),
                64 * 1024);

        assertEquals(List.of("test", "package", "--batch-mode", "--no-transfer-progress"),
                driver.arguments(Path.of("/jdk-21")));
        assertEquals(Path.of("target/weather-mcp.jar"), driver.relativeArtifact("weather-mcp"));
    }

    @Test
    void exposesMavenWrapperNamesOnPosixAndWindows() throws Exception {
        ValidationHostPlatform posix = ValidationHostPlatform.forHost("Linux", Map.of());
        Path systemRoot = Files.createDirectories(tempDir.resolve("Windows"));
        Files.createDirectories(systemRoot.resolve("System32"));
        Files.writeString(systemRoot.resolve("System32/cmd.exe"), "fixed-test-command");
        ValidationHostPlatform windows = ValidationHostPlatform.forHost(
                "Windows 11", Map.of("SystemRoot", systemRoot.toRealPath().toString()));

        assertEquals("mvnw", posix.wrapperFileName("MAVEN"));
        assertEquals("mvnw.cmd", windows.wrapperFileName("MAVEN"));
        assertEquals("gradlew", posix.wrapperFileName("GRADLE_KOTLIN"));
        assertEquals("gradlew.bat", windows.wrapperFileName("GRADLE_KOTLIN"));
    }

    @Test
    void resolvesOnlyTheExactPhysicalMavenArtifact() throws Exception {
        MavenBuildToolDriver driver = new MavenBuildToolDriver();
        Path root = Files.createDirectories(tempDir.resolve("project"));

        assertThrows(IllegalArgumentException.class,
                () -> driver.resolveArtifact(root, "weather-mcp"));

        Path target = Files.createDirectories(root.resolve("target"));
        Path artifact = Files.writeString(target.resolve("weather-mcp.jar"), "jar");

        assertEquals(artifact.toRealPath(), driver.resolveArtifact(root, "weather-mcp"));
    }
}
