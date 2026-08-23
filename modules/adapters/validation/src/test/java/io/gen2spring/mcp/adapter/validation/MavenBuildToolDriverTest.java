package io.gen2spring.mcp.adapter.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
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

    @Test
    void executesThePinnedOnlyScriptWrapperWithItsCanonicalNameAndMetadataPath() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
        Path root = Files.createDirectories(tempDir.resolve("maven-project")).toRealPath();
        Path wrapperMetadata = Files.createDirectories(root.resolve(".mvn/wrapper"));
        Files.writeString(wrapperMetadata.resolve("maven-wrapper.properties"), "distributionUrl=test\n");
        Path wrapper = Files.writeString(root.resolve("mvnw"), """
                #!/bin/sh
                [ "$(basename "$0")" = "mvnw" ] || exit 11
                [ -f "$(dirname "$0")/.mvn/wrapper/maven-wrapper.properties" ] || exit 12
                exit 0
                """);
        Files.setPosixFilePermissions(wrapper, java.util.Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        var driver = new MavenBuildToolDriver(
                new BoundedProcessRunner(),
                ValidationHostPlatform.forHost("Linux", Map.of()),
                Duration.ofSeconds(5),
                4 * 1024);
        var profile = CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java21-maven-mvc-streamable")
                .orElseThrow();

        try (VerifiedWrapper pinned = VerifiedWrapper.pin(
                root, wrapper, ValidationHostPlatform.forHost("Linux", Map.of()), "MAVEN")) {
            assertEquals("mvnw", pinned.verifiedExecutable().getFileName().toString());
            assertTrue(Files.isRegularFile(pinned.verifiedExecutable()
                    .getParent().resolve(".mvn/wrapper/maven-wrapper.properties")));
        }

        BuildToolDriver.Result result = driver.build(new BuildToolDriver.Request(
                root, profile, Path.of(System.getProperty("java.home"))));

        assertEquals(0, result.exitCode(), result.safeSummary());
        try (var children = Files.list(root)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith(".mvnw-validated-")));
        }
        assertTrue(Files.isRegularFile(wrapper));
    }
}
