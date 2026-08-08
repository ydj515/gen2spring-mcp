package io.gen2spring.mcp.validation;

import static io.gen2spring.mcp.domain.config.GenerationRequest.ValidationLevel.MCP_PROTOCOL;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.FAILED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.SKIPPED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.SUCCESS;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.UNVERIFIED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.VALIDATED;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedTool;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationRequest;
import io.gen2spring.mcp.validation.support.McpTestApplication;
import io.gen2spring.mcp.validation.support.McpTestServer;
import io.gen2spring.mcp.validation.support.PortBindFailureApplication;
import io.gen2spring.mcp.validation.support.StartupDelayApplication;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleMcpProjectValidatorTest {
    private static final String ARTIFACT_ID = "weather-mcp-server";
    private static final Map<String, ExpectedTool> EXPECTED = Map.of(
            "kma_weather_get_forecast",
            new ExpectedTool(
                    "Get the public weather forecast for a grid location.",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("nx", Map.of(
                                    "type", "integer",
                                    "format", "int32",
                                    "description", "Grid x coordinate")),
                            "required", List.of("nx"))));

    @TempDir
    Path tempDir;

    @Test
    void reportsBuildFailureAndSkipsLaterStagesWithoutRetainingRawOutput() throws Exception {
        Path root = project("#!/bin/sh\nprintf 'Authorization: Bearer secret-value'\nprintf 'apiKey=secret-value' >&2\nexit 7\n");

        var report = validator().validate(request(root, EXPECTED));

        assertEquals(UNVERIFIED, report.status());
        assertEquals(List.of("COMPILE", "APPLICATION_CONTEXT", "MCP_INITIALIZE", "MCP_TOOLS_LIST"),
                report.stages().stream().map(stage -> stage.stage()).toList());
        assertEquals(List.of(FAILED, SKIPPED, SKIPPED, SKIPPED),
                report.stages().stream().map(stage -> stage.status()).toList());
        assertTrue(report.stages().getFirst().summary().contains("exitCode=7"));
        assertFalse(report.stages().getFirst().summary().contains("secret-value"));
    }

    @Test
    void reportsBuildTimeoutAndTerminatesTheFakeWrapperTree() throws Exception {
        Path root = project("#!/bin/sh\necho $$ > build.pid\nsleep 60\n");

        var report = validator(Duration.ofSeconds(1), Duration.ofSeconds(3))
                .validate(request(root, EXPECTED));

        assertEquals(UNVERIFIED, report.status());
        assertEquals(FAILED, report.stages().getFirst().status());
        assertTrue(report.stages().getFirst().summary().contains("timedOut=true"));
        assertTrue(waitUntilDead(Long.parseLong(waitForText(root.resolve("build.pid")).trim())));
    }

    @Test
    void rejectsMissingAndAmbiguousArtifactJars() throws Exception {
        Path missing = project("#!/bin/sh\nexit 0\n");
        writeJar(missing, ARTIFACT_ID + "-0.1.0.jar", McpTestApplication.class);
        var missingReport = validator().validate(request(missing, EXPECTED));
        assertEquals(FAILED, missingReport.stages().get(1).status());
        assertTrue(missingReport.stages().get(1).summary().contains("missing"));

        Path ambiguous = projectAt(tempDir.resolve("ambiguous"), "#!/bin/sh\nexit 0\n");
        writeJar(ambiguous, ARTIFACT_ID + ".jar", McpTestApplication.class);
        writeJar(ambiguous, ARTIFACT_ID + "-0.1.0.jar", McpTestApplication.class);
        var ambiguousReport = validator().validate(request(ambiguous, EXPECTED));
        assertEquals(FAILED, ambiguousReport.stages().get(1).status());
        assertTrue(ambiguousReport.stages().get(1).summary().contains("ambiguous"));
    }

    @Test
    void rejectsSymlinkedNonRegularNonExecutableAndOutsideGradleWrappers() throws Exception {
        Path outside = Files.writeString(tempDir.resolve("outside-gradlew"), "#!/bin/sh\nexit 0\n");
        assertTrue(outside.toFile().setExecutable(true));

        Path symlinkRoot = Files.createDirectories(tempDir.resolve("symlink-project/build/libs"))
                .getParent().getParent().toRealPath();
        Files.createSymbolicLink(symlinkRoot.resolve("gradlew"), outside);
        assertEquals(FAILED, validator().validate(request(symlinkRoot, EXPECTED)).stages().getFirst().status());

        Path directoryRoot = Files.createDirectories(tempDir.resolve("directory-project/build/libs"))
                .getParent().getParent().toRealPath();
        Files.createDirectory(directoryRoot.resolve("gradlew"));
        assertEquals(FAILED, validator().validate(request(directoryRoot, EXPECTED)).stages().getFirst().status());

        Path nonExecutableRoot = Files.createDirectories(tempDir.resolve("non-executable-project/build/libs"))
                .getParent().getParent().toRealPath();
        Path nonExecutable = Files.writeString(nonExecutableRoot.resolve("gradlew"), "#!/bin/sh\nexit 0\n");
        assertTrue(nonExecutable.toFile().setExecutable(false, false));
        assertEquals(FAILED,
                validator().validate(request(nonExecutableRoot, EXPECTED)).stages().getFirst().status());

        assertThrows(IllegalArgumentException.class,
                () -> GradleMcpProjectValidator.pinGradleWrapper(symlinkRoot, outside));
    }

    @Test
    void rejectsASymbolicLinkValidationRoot() throws Exception {
        Path realRoot = projectAt(tempDir.resolve("real-root"), "#!/bin/sh\nexit 0\n");
        Path linkedRoot = tempDir.resolve("linked-root");
        Files.createSymbolicLink(linkedRoot, realRoot);

        assertThrows(IllegalArgumentException.class, () -> validator().validate(request(linkedRoot, EXPECTED)));
    }

    @Test
    void rejectsAValidationRootWithASymbolicLinkInItsAncestry() throws Exception {
        Path physicalParent = Files.createDirectory(tempDir.resolve("physical-parent"));
        Path alias = tempDir.resolve("alias-parent");
        Files.createSymbolicLink(alias, physicalParent);
        projectAt(alias.resolve("project"), "#!/bin/sh\nexit 0\n");
        Path rootThroughAlias = alias.resolve("project").toAbsolutePath().normalize();

        assertThrows(IllegalArgumentException.class,
                () -> validator().validate(request(rootThroughAlias, EXPECTED)));
    }

    @Test
    void executesThePinnedWrapperInodeWhenTheOriginalPathIsReplacedBeforeLaunch() throws Exception {
        Path root = project("#!/bin/sh\nprintf 'verified' > verified.marker\nprintf '%s' \"$0\" > gradle.executable\nexit 0\n");
        AtomicReference<Path> snapshot = new AtomicReference<>();
        var validator = validator((original, pinned) -> {
            snapshot.set(pinned);
            Path replacement = Files.writeString(root.resolve("replacement-gradlew"),
                    "#!/bin/sh\nprintf 'replacement' > replacement.marker\nexit 0\n");
            assertTrue(replacement.toFile().setExecutable(true));
            Files.move(replacement, original, StandardCopyOption.REPLACE_EXISTING);
        });

        var report = validator.validate(request(root, EXPECTED));

        assertEquals(SUCCESS, report.stages().getFirst().status());
        assertEquals("verified", Files.readString(root.resolve("verified.marker")));
        assertFalse(Files.exists(root.resolve("replacement.marker")));
        assertTrue(Files.readString(root.resolve("gradlew")).contains("replacement"));
        assertFalse(Files.exists(snapshot.get()));
        assertTrue(Files.readString(root.resolve("gradle.executable")).contains(".gradlew-validated-"));
    }

    @Test
    void leavesACompetingSnapshotPathUntouchedWhenItsFileKeyNoLongerMatches() throws Exception {
        Path root = project("#!/bin/sh\nprintf 'unexpected' > executed.marker\nexit 0\n");
        AtomicReference<Path> snapshot = new AtomicReference<>();
        var validator = validator((original, pinned) -> {
            snapshot.set(pinned);
            Files.delete(pinned);
            Files.writeString(pinned, "competing-file");
        });

        var report = validator.validate(request(root, EXPECTED));

        assertEquals(FAILED, report.stages().getFirst().status());
        assertFalse(Files.exists(root.resolve("executed.marker")));
        assertEquals("competing-file", Files.readString(snapshot.get()));
    }

    @Test
    void timesOutStartupAndAlwaysTerminatesTheApplication() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", StartupDelayApplication.class);

        var report = validator(Duration.ofSeconds(3), Duration.ofMillis(300))
                .validate(request(root, EXPECTED));

        assertEquals(UNVERIFIED, report.status());
        assertEquals(FAILED, report.stages().get(1).status());
        assertTrue(report.stages().get(1).summary().contains("startup timeout"));
        assertTrue(waitUntilDead(readPid(root)));
    }

    @Test
    void rejectsACompetingMcpListenerWhenTheLaunchedChildCannotBind() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", PortBindFailureApplication.class);
        try (var competitor = McpTestServer.start(McpTestServer.Scenario.SUCCESS,
                () -> writeMarker(root.resolve("competitor-used")))) {
            Files.writeString(root.resolve("competitor-port"), Integer.toString(competitor.uri().getPort()));
            var report = validator().validate(request(root, EXPECTED));

            assertEquals(UNVERIFIED, report.status(), report.toString());
            assertEquals(FAILED, report.stages().get(1).status());
        }
    }

    @Test
    void rejectsAChildThatPublishesADelayedDuplicateStartupEndpointDuringTheMcpRoundTrip() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        Files.writeString(root.resolve("test-behavior"), "duplicate-startup");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);

        var report = validator().validate(request(root, EXPECTED));

        assertEquals(UNVERIFIED, report.status(), report.toString());
        assertEquals(FAILED, report.stages().get(1).status());
        assertTrue(waitUntilDead(readPid(root)));
    }

    @Test
    void rejectsAChildWhoseStartupOutputOverflowsDuringTheMcpRoundTrip() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        Files.writeString(root.resolve("test-behavior"), "overflow-output");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);

        var report = validator().validate(request(root, EXPECTED));

        assertEquals(UNVERIFIED, report.status(), report.toString());
        assertEquals(FAILED, report.stages().get(1).status());
        assertTrue(waitUntilDead(readPid(root)));
    }

    @Test
    void validatesBuildStartupAndMcpInOrderAndCleansUpTheApplication() throws Exception {
        Path root = project("#!/bin/sh\nprintf '%s\\n' \"$0\" > gradle.executable\nprintf '%s\\n' \"$@\" > gradle.args\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);
        Files.writeString(root.resolve("build/libs/unrelated.jar"), "ignored");

        var report = validator().validate(request(root, EXPECTED));

        assertEquals(VALIDATED, report.status());
        assertEquals(List.of("COMPILE", "APPLICATION_CONTEXT", "MCP_INITIALIZE", "MCP_TOOLS_LIST"),
                report.stages().stream().map(stage -> stage.stage()).toList());
        assertTrue(report.stages().stream().allMatch(stage -> stage.status() == SUCCESS));
        assertEquals(List.of("kma_weather_get_forecast"),
                report.tools().stream().map(tool -> tool.name()).toList());
        assertEquals(List.of("classes", "test", "bootJar", "--no-daemon", "--non-interactive"),
                Files.readAllLines(root.resolve("gradle.args")));
        Path executedWrapper = Path.of(Files.readString(root.resolve("gradle.executable")).trim());
        assertEquals(root, executedWrapper.getParent());
        assertTrue(executedWrapper.getFileName().toString().startsWith(".gradlew-validated-"));
        assertFalse(Files.exists(executedWrapper));
        assertEquals(List.of("--server.address=127.0.0.1", "--server.port=0"),
                Files.readAllLines(root.resolve("app.args")).stream()
                        .filter(argument -> argument.startsWith("--server.address=")
                                || argument.startsWith("--server.port="))
                        .toList());
        assertTrue(waitUntilDead(readPid(root)));
    }

    @Test
    void mcpContractFailureStillCleansUpAndDoesNotTouchSiblingPaths() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);
        Path sibling = Files.writeString(tempDir.resolve("keep.txt"), "keep");
        Map<String, ExpectedTool> wrong = Map.of(
                "kma_weather_get_forecast", new ExpectedTool(
                        "wrong description", EXPECTED.get("kma_weather_get_forecast").inputSchema()));

        var report = validator().validate(request(root, wrong));

        assertEquals(UNVERIFIED, report.status());
        assertEquals(FAILED, report.stages().get(3).status());
        assertTrue(waitUntilDead(readPid(root)));
        assertEquals("keep", Files.readString(sibling));
    }

    private GradleMcpProjectValidator validator() {
        return validator(Duration.ofSeconds(3), Duration.ofSeconds(3));
    }

    private GradleMcpProjectValidator validator(Duration buildTimeout, Duration startupTimeout) {
        return new GradleMcpProjectValidator(buildTimeout, startupTimeout, Duration.ofMillis(20), 8 * 1024);
    }

    private GradleMcpProjectValidator validator(GradleMcpProjectValidator.WrapperSnapshotHook hook) {
        return new GradleMcpProjectValidator(
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofMillis(20), 8 * 1024, hook);
    }

    private GradleMcpProjectValidator validator(GradleMcpProjectValidator.ApplicationLaunchHook hook) {
        return new GradleMcpProjectValidator(
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofMillis(20), 8 * 1024,
                GradleMcpProjectValidator.WrapperSnapshotHook.NOOP, hook);
    }

    private ValidationRequest request(Path root, Map<String, ExpectedTool> expected) {
        return new ValidationRequest(root, ARTIFACT_ID, MCP_PROTOCOL, expected);
    }

    private Path project(String gradlew) throws IOException {
        return projectAt(tempDir.resolve("project"), gradlew);
    }

    private Path projectAt(Path root, String gradlew) throws IOException {
        Files.createDirectories(root.resolve("build/libs"));
        Path wrapper = Files.writeString(root.resolve("gradlew"), gradlew);
        assertTrue(wrapper.toFile().setExecutable(true));
        return root.toRealPath();
    }

    private void writeJar(Path root, String fileName, Class<?> mainClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass.getName());
        Path jar = root.resolve("build/libs").resolve(fileName);
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            String resource = mainClass.getName().replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(resource));
            try (InputStream input = mainClass.getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("test application class resource is missing");
                }
                input.transferTo(output);
            }
            output.closeEntry();
        }
    }

    private long readPid(Path root) throws Exception {
        return Long.parseLong(waitForText(root.resolve("app.pid")));
    }

    private String waitForText(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while ((!Files.exists(path) || Files.size(path) == 0) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return Files.readString(path);
    }

    private boolean waitUntilDead(long pid) throws Exception {
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

    private static void writeMarker(Path path) {
        try {
            Files.writeString(path, "used");
        } catch (IOException exception) {
            throw new IllegalStateException("competitor marker could not be written", exception);
        }
    }
}
