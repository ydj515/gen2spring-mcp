package io.gen2spring.mcp.validation;

import static io.gen2spring.mcp.domain.config.GenerationRequest.ValidationLevel.MCP_PROTOCOL;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.FAILED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.SKIPPED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.SUCCESS;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.UNVERIFIED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.VALIDATED;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation.QUERY;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedTool;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedToolCall;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedUpstreamInteraction;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedUpstreamOutcome;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedUpstreamResponse;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationProgress;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ProgressStatus;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationRequest;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.HttpExecutionDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterBinding;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.SecretBinding;
import io.gen2spring.mcp.validation.support.McpTestApplication;
import io.gen2spring.mcp.validation.support.McpTestServer;
import io.gen2spring.mcp.validation.support.PortBindFailureApplication;
import io.gen2spring.mcp.validation.support.StartupDelayApplication;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void fiveArgumentValidationRequestUsesTheJava21CompatibilityProfile() throws IOException {
        ValidationRequest request = request(project("#!/bin/sh\nexit 0\n"), EXPECTED);

        assertSame(CompatibilityProfile.p0(), request.profile());
    }

    @Test
    void reportsBuildFailureAndSkipsLaterStagesWithoutRetainingRawOutput() throws Exception {
        Path root = project("#!/bin/sh\nprintf 'Authorization: Bearer secret-value'\nprintf 'apiKey=secret-value' >&2\nexit 7\n");
        List<GenerationProgress> progress = new java.util.ArrayList<>();

        var report = validator().validate(request(root, EXPECTED), progress::add);

        assertEquals(UNVERIFIED, report.status());
        assertEquals(List.of("COMPILE", "APPLICATION_CONTEXT", "MCP_INITIALIZE", "MCP_TOOLS_LIST", "MCP_TOOL_CALL"),
                report.stages().stream().map(stage -> stage.stage()).toList());
        assertEquals(List.of(FAILED, SKIPPED, SKIPPED, SKIPPED, SKIPPED),
                report.stages().stream().map(stage -> stage.status()).toList());
        assertTrue(report.stages().getFirst().summary().contains("exitCode=7"));
        assertFalse(report.stages().getFirst().summary().contains("secret-value"));
        assertEquals(List.of(
                new GenerationProgress("COMPILE", ProgressStatus.RUNNING),
                new GenerationProgress("COMPILE", ProgressStatus.FAILED),
                new GenerationProgress("APPLICATION_CONTEXT", ProgressStatus.SKIPPED),
                new GenerationProgress("MCP_INITIALIZE", ProgressStatus.SKIPPED),
                new GenerationProgress("MCP_TOOLS_LIST", ProgressStatus.SKIPPED),
                new GenerationProgress("MCP_TOOL_CALL", ProgressStatus.SKIPPED)), progress);
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
            assertEquals(List.of(SUCCESS, SUCCESS, SUCCESS, SUCCESS, FAILED),
                    report.stages().stream().map(stage -> stage.status()).toList());
            assertTrue(waitUntilDead(readPid(root)));
        }
    }

    @Test
    void reportsEndpointDriftDuringMcpAtTheActiveToolCallStage() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        Files.writeString(root.resolve("test-behavior"), "duplicate-startup");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);

        var report = validator().validate(request(root, EXPECTED));

        assertEquals(UNVERIFIED, report.status(), report.toString());
        assertEquals(SUCCESS, report.stages().get(1).status());
        assertEquals(FAILED, report.stages().get(4).status());
        assertTrue(waitUntilDead(readPid(root)));
    }

    @Test
    void reportsOutputOverflowDuringMcpAtTheActiveToolCallStage() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        Files.writeString(root.resolve("test-behavior"), "overflow-output");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);

        var report = validator().validate(request(root, EXPECTED));

        assertEquals(UNVERIFIED, report.status(), report.toString());
        assertEquals(SUCCESS, report.stages().get(1).status());
        assertEquals(FAILED, report.stages().get(4).status());
        assertTrue(waitUntilDead(readPid(root)));
    }

    @Test
    void validatesBuildStartupAndMcpInOrderAndCleansUpTheApplication() throws Exception {
        Path root = project("#!/bin/sh\nprintf '%s\\n' \"$0\" > gradle.executable\nprintf '%s\\n' \"$@\" > gradle.args\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);
        Files.writeString(root.resolve("build/libs/unrelated.jar"), "ignored");

        var report = validator().validate(request(root, EXPECTED));

        assertEquals(VALIDATED, report.status());
        assertEquals(List.of("COMPILE", "APPLICATION_CONTEXT", "MCP_INITIALIZE", "MCP_TOOLS_LIST", "MCP_TOOL_CALL"),
                report.stages().stream().map(stage -> stage.stage()).toList());
        assertTrue(report.stages().stream().allMatch(stage -> stage.status() == SUCCESS));
        assertEquals(List.of("kma_weather_get_forecast"),
                report.tools().stream().map(tool -> tool.name()).toList());
        Path currentJavaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        assertEquals(List.of(
                        "-Dorg.gradle.java.installations.auto-detect=false",
                        "-Dorg.gradle.java.installations.auto-download=false",
                        "-Dorg.gradle.java.installations.paths=" + currentJavaHome,
                        "classes", "test", "bootJar", "--no-daemon", "--non-interactive"),
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
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
    }

    @Test
    void publishesMcpProgressBeforeTheUpstreamVerificationGate() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);
        List<GenerationProgress> progress = new java.util.ArrayList<>();
        GradleMcpProjectValidator.MockUpstreamFactory factory = expectation -> {
            MockUpstreamServer delegate = MockUpstreamServer.start(expectation);
            return new GradleMcpProjectValidator.RunningMockUpstream() {
                @Override
                public URI baseUri() {
                    return delegate.baseUri();
                }

                @Override
                public Map<String, String> environmentOverrides() {
                    return delegate.environmentOverrides();
                }

                @Override
                public void sealAndAwaitVerified(Duration timeout) {
                    delegate.sealAndAwaitVerified(timeout);
                    assertEquals(List.of(
                            new GenerationProgress("COMPILE", ProgressStatus.RUNNING),
                            new GenerationProgress("COMPILE", ProgressStatus.SUCCESS),
                            new GenerationProgress("APPLICATION_CONTEXT", ProgressStatus.RUNNING),
                            new GenerationProgress("APPLICATION_CONTEXT", ProgressStatus.SUCCESS),
                            new GenerationProgress("MCP_INITIALIZE", ProgressStatus.RUNNING),
                            new GenerationProgress("MCP_INITIALIZE", ProgressStatus.SUCCESS),
                            new GenerationProgress("MCP_TOOLS_LIST", ProgressStatus.RUNNING),
                            new GenerationProgress("MCP_TOOLS_LIST", ProgressStatus.SUCCESS),
                            new GenerationProgress("MCP_TOOL_CALL", ProgressStatus.RUNNING)), progress);
                }

                @Override
                public void close() throws IOException {
                    delegate.close();
                }
            };
        };

        var report = validator(factory).validate(request(root, EXPECTED), progress::add);

        assertEquals(VALIDATED, report.status());
        assertEquals(new GenerationProgress("MCP_TOOL_CALL", ProgressStatus.SUCCESS), progress.getLast());
    }

    @Test
    void passesTheCompleteOrderedInteractionSequenceToTheMockFactory() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);
        AtomicReference<List<UpstreamCallExpectation>> captured = new AtomicReference<>();
        GradleMcpProjectValidator.MockUpstreamFactory factory = expectations -> {
            captured.set(expectations);
            throw new IOException("intentional mock start failure");
        };

        var report = validator(factory).validate(request(root, EXPECTED, paginatedExpectedToolCall()));

        assertEquals(UNVERIFIED, report.status());
        assertEquals(2, captured.get().size());
        assertEquals(List.of("first"), captured.get().get(0).query().get("cursor"));
        assertEquals(List.of("second"), captured.get().get(1).query().get("cursor"));
    }

    @Test
    void reportsTargetRuntimeResolutionFailureAtCompileWithoutLeakingTheConfiguredPath() throws Exception {
        Path root = project("#!/bin/sh\nprintf 'unexpected' > wrapper-ran\nexit 0\n");
        Path unavailable = tempDir.resolve("secret-java17-home").toAbsolutePath();
        JavaRuntimeResolver resolver = new JavaRuntimeResolver(
                Map.of("GEN2SPRING_JAVA_17_HOME", unavailable.toString()),
                Path.of(System.getProperty("java.home")),
                executable -> 17);

        var report = validator(resolver).validate(request(root, EXPECTED, java17()));

        assertEquals(UNVERIFIED, report.status());
        assertEquals(List.of(FAILED, SKIPPED, SKIPPED, SKIPPED, SKIPPED),
                report.stages().stream().map(stage -> stage.status()).toList());
        assertEquals("Target Java runtime is unavailable or invalid", report.stages().getFirst().summary());
        assertFalse(report.toString().contains(unavailable.toString()));
        assertFalse(Files.exists(root.resolve("wrapper-ran")));
    }

    @Test
    void rechecksRuntimeIdentityAfterTheWrapperHookAndBeforeGradleExecution() throws Exception {
        Path root = project("#!/bin/sh\nprintf 'unexpected' > wrapper-ran\nexit 0\n");
        RuntimeFixture runtime = runtimeFixture("compile-swap");
        JavaRuntimeResolver resolver = resolver(runtime, 21);
        var validator = validator(resolver, (original, snapshot) -> replace(runtime.executable()),
                GradleMcpProjectValidator.ApplicationLaunchHook.NOOP);

        var report = validator.validate(request(root, EXPECTED));

        assertEquals(FAILED, report.stages().getFirst().status());
        assertEquals("Target Java runtime is unavailable or invalid", report.stages().getFirst().summary());
        assertFalse(Files.exists(root.resolve("wrapper-ran")));
        assertFalse(report.toString().contains(runtime.home().toString()));
    }

    @Test
    void launchesTheBootJarWithTheResolvedRuntimeAndRechecksItsIdentityLast() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);
        RuntimeFixture runtime = runtimeFixture("boot-swap");
        JavaRuntimeResolver resolver = resolver(runtime, 21);
        AtomicReference<List<String>> command = new AtomicReference<>();
        var validator = validator(
                resolver,
                GradleMcpProjectValidator.WrapperSnapshotHook.NOOP,
                arguments -> {
                    command.set(List.copyOf(arguments));
                    replace(runtime.executable());
                });

        var report = validator.validate(request(root, EXPECTED));

        assertEquals(runtime.executable().toString(), command.get().getFirst());
        assertEquals(FAILED, report.stages().get(1).status());
        assertEquals("application process failed safely", report.stages().get(1).summary());
        assertFalse(Files.exists(root.resolve("app.pid")));
        assertFalse(report.toString().contains(runtime.home().toString()));
    }

    @Test
    void rejectsAnExpectedToolCallThatIsNotBoundToExpectedToolMetadata() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        ExpectedToolCall unbound = new ExpectedToolCall(new McpToolDefinition(
                "otherOperation", "other_tool", "Other Tool", List.of(),
                new HttpExecutionDefinition(GET, URI.create("https://api.example.test"), "/forecast", List.of()),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON), Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> validator().validate(new ValidationRequest(root, ARTIFACT_ID, MCP_PROTOCOL, EXPECTED, unbound)));
    }

    @Test
    void reportsMockBindFailureAtTheToolCallGateWithoutLaunchingTheApplication() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);
        var validator = validator((GradleMcpProjectValidator.MockUpstreamFactory) expectation -> {
            throw new IOException("bind failed near configured-secret-like-value");
        });

        var report = validator.validate(request(root, EXPECTED));

        assertEquals(UNVERIFIED, report.status());
        assertEquals(List.of(SUCCESS, SKIPPED, SKIPPED, SKIPPED, FAILED),
                report.stages().stream().map(stage -> stage.status()).toList());
        assertEquals("MCP Tool call validation failed safely", report.stages().get(4).summary());
        assertFalse(Files.exists(root.resolve("app.pid")));
        assertSummariesExclude(report, "configured-secret-like-value", "mcp-validation-secret-1");
    }

    @Test
    void rethrowsFatalMockStartupErrorsInsteadOfConvertingThemToAValidationReport() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);
        AssertionError fatal = new AssertionError("fatal-startup-secret-like-value");
        var validator = validator((GradleMcpProjectValidator.MockUpstreamFactory) expectation -> {
            throw fatal;
        });

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> validator.validate(request(root, EXPECTED)));

        assertSame(fatal, thrown);
        assertFalse(Files.exists(root.resolve("app.pid")));
    }

    @Test
    void rethrowsThePrimaryFatalErrorWithMockCleanupFailureSuppressed() throws Exception {
        Path root = runnableProject("");
        AssertionError primary = new AssertionError("fatal-primary-secret-like-value");
        AssertionError cleanup = new AssertionError("fatal-cleanup-secret-like-value");
        AtomicBoolean closed = new AtomicBoolean();
        GradleMcpProjectValidator.MockUpstreamFactory factory = expectation -> {
            MockUpstreamServer delegate = MockUpstreamServer.start(expectation);
            return new GradleMcpProjectValidator.RunningMockUpstream() {
                @Override
                public URI baseUri() {
                    return delegate.baseUri();
                }

                @Override
                public Map<String, String> environmentOverrides() {
                    return delegate.environmentOverrides();
                }

                @Override
                public void sealAndAwaitVerified(Duration timeout) {
                    delegate.sealAndAwaitVerified(timeout);
                    throw primary;
                }

                @Override
                public void close() {
                    delegate.close();
                    closed.set(true);
                    throw cleanup;
                }
            };
        };

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> validator(factory).validate(request(root, EXPECTED)));

        assertSame(primary, thrown);
        assertEquals(List.of(cleanup), List.of(thrown.getSuppressed()));
        assertTrue(waitUntilDead(readPid(root)));
        assertTrue(closed.get());
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
    }

    @Test
    void rethrowsTheSameFatalInstanceFromAwaitAndMockCloseWithoutSelfSuppression() throws Exception {
        Path root = runnableProject("");
        AssertionError fatal = new AssertionError("shared-fatal-secret-like-value");
        TrackingMockFactory mocks = new TrackingMockFactory(false, fatal, false, fatal);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> validator(mocks).validate(request(root, EXPECTED)));

        assertSame(fatal, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertTrue(waitUntilDead(readPid(root)));
        assertTrue(mocks.closed.get());
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
    }

    @Test
    void rethrowsTheSameFatalInstanceFromAwaitAndApplicationCleanupWithoutSelfSuppression() throws Exception {
        Path root = runnableProject("");
        AssertionError fatal = new AssertionError("shared-fatal-secret-like-value");
        TrackingMockFactory mocks = new TrackingMockFactory(false, fatal, false);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> validator(mocks, () -> {
                    throw fatal;
                }).validate(request(root, EXPECTED)));

        assertSame(fatal, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertTrue(waitUntilDead(readPid(root)));
        assertTrue(mocks.closed.get());
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
    }

    @Test
    void suppressesTheSameCleanupFatalOnlyOnceAcrossMockAndApplicationCleanup() throws Exception {
        Path root = runnableProject("");
        AssertionError primary = new AssertionError("primary-fatal-secret-like-value");
        AssertionError cleanup = new AssertionError("shared-cleanup-secret-like-value");
        TrackingMockFactory mocks = new TrackingMockFactory(false, primary, false, cleanup);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> validator(mocks, () -> {
                    throw cleanup;
                }).validate(request(root, EXPECTED)));

        assertSame(primary, thrown);
        assertEquals(List.of(cleanup), List.of(thrown.getSuppressed()));
        assertTrue(waitUntilDead(readPid(root)));
        assertTrue(mocks.closed.get());
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
    }

    @Test
    void rethrowsMockCleanupFatalErrorWithEarlierNonFatalFailureSuppressed() throws Exception {
        Path root = runnableProject("");
        RuntimeException primary = new RuntimeException("primary-secret-like-value");
        AssertionError cleanup = new AssertionError("fatal-cleanup-secret-like-value");
        AtomicBoolean closed = new AtomicBoolean();
        GradleMcpProjectValidator.MockUpstreamFactory factory = expectation -> {
            MockUpstreamServer delegate = MockUpstreamServer.start(expectation);
            return new GradleMcpProjectValidator.RunningMockUpstream() {
                @Override
                public URI baseUri() {
                    return delegate.baseUri();
                }

                @Override
                public Map<String, String> environmentOverrides() {
                    return delegate.environmentOverrides();
                }

                @Override
                public void sealAndAwaitVerified(Duration timeout) {
                    delegate.sealAndAwaitVerified(timeout);
                    throw primary;
                }

                @Override
                public void close() {
                    delegate.close();
                    closed.set(true);
                    throw cleanup;
                }
            };
        };

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> validator(factory).validate(request(root, EXPECTED)));

        assertSame(cleanup, thrown);
        assertEquals(List.of(primary), List.of(thrown.getSuppressed()));
        assertTrue(waitUntilDead(readPid(root)));
        assertTrue(closed.get());
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
    }

    @Test
    void rethrowsThePrimaryFatalErrorWithApplicationCleanupFailureSuppressed() throws Exception {
        Path root = runnableProject("");
        AssertionError primary = new AssertionError("fatal-primary-secret-like-value");
        AssertionError cleanup = new AssertionError("fatal-application-cleanup-secret-like-value");
        TrackingMockFactory mocks = new TrackingMockFactory(false, primary, false);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> validator(mocks, () -> {
                    throw cleanup;
                }).validate(request(root, EXPECTED)));

        assertSame(primary, thrown);
        assertEquals(List.of(cleanup), List.of(thrown.getSuppressed()));
        assertTrue(waitUntilDead(readPid(root)));
        assertTrue(mocks.closed.get());
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
    }

    @Test
    void rethrowsAnApplicationCleanupFatalErrorWhenNoEarlierFailureExists() throws Exception {
        Path root = runnableProject("");
        AssertionError cleanup = new AssertionError("fatal-application-cleanup-secret-like-value");
        TrackingMockFactory mocks = new TrackingMockFactory(false);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> validator(mocks, () -> {
                    throw cleanup;
                }).validate(request(root, EXPECTED)));

        assertSame(cleanup, thrown);
        assertTrue(waitUntilDead(readPid(root)));
        assertTrue(mocks.closed.get());
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
    }

    @Test
    void rethrowsApplicationCleanupFatalErrorWithEarlierNonFatalFailureSuppressed() throws Exception {
        Path root = runnableProject("");
        RuntimeException primary = new RuntimeException("primary-secret-like-value");
        AssertionError cleanup = new AssertionError("fatal-application-cleanup-secret-like-value");
        TrackingMockFactory mocks = new TrackingMockFactory(false, primary, false);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> validator(mocks, () -> {
                    throw cleanup;
                }).validate(request(root, EXPECTED)));

        assertSame(cleanup, thrown);
        assertEquals(List.of(primary), List.of(thrown.getSuppressed()));
        assertTrue(waitUntilDead(readPid(root)));
        assertTrue(mocks.closed.get());
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
    }

    @Test
    void reportsApplicationCleanupExceptionsSafely() throws Exception {
        Path root = runnableProject("");
        IOException cleanup = new IOException("application-cleanup-secret-like-value");
        AtomicBoolean cleanupAttempted = new AtomicBoolean();
        TrackingMockFactory mocks = new TrackingMockFactory(false);

        var report = validator(mocks, () -> {
            cleanupAttempted.set(true);
            throw cleanup;
        }).validate(request(root, EXPECTED));

        assertCallFailureAndCleanup(report, root, mocks);
        assertTrue(cleanupAttempted.get());
        assertSummariesExclude(report, "application-cleanup-secret-like-value", "mcp-validation-secret-1");
    }

    @Test
    void preservesTheInterruptFlagWhileCleaningUpAfterUpstreamVerification() throws Exception {
        Path root = runnableProject("");
        TrackingMockFactory mocks = new TrackingMockFactory(
                false, new RuntimeException("interrupted-secret-like-value"), true);
        io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationReport report;

        try {
            report = validator(mocks).validate(request(root, EXPECTED));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertCallFailureAndCleanup(report, root, mocks);
        assertSummariesExclude(report, "interrupted-secret-like-value", "mcp-validation-secret-1");
    }

    @Test
    void restoresTheInterruptFlagWhenFatalMockCleanupOverridesAnInterruptedReadinessWait() throws Exception {
        Path root = project("#!/bin/sh\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", StartupDelayApplication.class);
        AssertionError fatal = new AssertionError("fatal-cleanup-secret-like-value");
        TrackingMockFactory mocks = new TrackingMockFactory(false, null, false, fatal);
        AtomicReference<Throwable> interrupterFailure = new AtomicReference<>();
        AtomicReference<Thread> interrupter = new AtomicReference<>();
        Thread validationThread = Thread.currentThread();
        boolean interrupted;
        AssertionError thrown;

        try {
            thrown = assertThrows(AssertionError.class,
                    () -> validator(mocks, command -> interrupter.set(Thread.ofPlatform()
                            .name("validator-test-interrupter")
                            .start(() -> {
                                try {
                                    waitForText(root.resolve("app.pid"));
                                    validationThread.interrupt();
                                } catch (Throwable failure) {
                                    interrupterFailure.set(failure);
                                }
                            }))).validate(request(root, EXPECTED)));
            interrupted = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
            Thread interruptingThread = interrupter.get();
            if (interruptingThread != null) {
                interruptingThread.join(3_000);
            }
        }

        assertSame(fatal, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof InterruptedException);
        assertTrue(interrupted);
        assertTrue(interrupterFailure.get() == null, String.valueOf(interrupterFailure.get()));
        assertTrue(waitUntilDead(readPid(root)));
        assertTrue(mocks.closed.get());
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
    }

    @Test
    void failsClosedWhenTheApplicationDoesNotSendAnUpstreamRequestBeforeTheMockTimeout() throws Exception {
        Path root = runnableProject("no-upstream");
        TrackingMockFactory mocks = new TrackingMockFactory(false);

        var report = validator(Duration.ofSeconds(3), Duration.ofMillis(600), mocks)
                .validate(request(root, EXPECTED));

        assertCallFailureAndCleanup(report, root, mocks);
    }

    @Test
    void failsClosedOnAnObservedUpstreamRequestMismatch() throws Exception {
        Path root = runnableProject("upstream-mismatch");
        TrackingMockFactory mocks = new TrackingMockFactory(false);

        var report = validator(mocks).validate(request(root, EXPECTED));

        assertCallFailureAndCleanup(report, root, mocks);
    }

    @Test
    void failsClosedWhenAValidUpstreamRequestIsFollowedByADuplicate() throws Exception {
        Path root = runnableProject("duplicate-upstream");
        TrackingMockFactory mocks = new TrackingMockFactory(false);

        var report = validator(mocks).validate(request(root, EXPECTED));

        assertCallFailureAndCleanup(report, root, mocks);
        assertEquals("200", waitForText(root.resolve("upstream.status")));
        assertEquals("409", waitForText(root.resolve("duplicate-upstream.status")));
    }

    @Test
    void failsClosedWhenADuplicateArrivesAfterTheMcpRoundTripWasSealed() throws Exception {
        Path root = runnableProject("delayed-duplicate-upstream");
        TrackingMockFactory mocks = new TrackingMockFactory(false);

        var report = validator(mocks).validate(request(root, EXPECTED));

        assertCallFailureAndCleanup(report, root, mocks);
        assertEquals("200", waitForText(root.resolve("upstream.status")));
        assertEquals("409", waitForText(root.resolve("duplicate-upstream.status")));
    }

    @Test
    void failsClosedOnAnMcpToolResultMismatch() throws Exception {
        Path root = runnableProject("mcp-result-mismatch");
        TrackingMockFactory mocks = new TrackingMockFactory(false);

        var report = validator(mocks).validate(request(root, EXPECTED));

        assertCallFailureAndCleanup(report, root, mocks);
        assertEquals("200", waitForText(root.resolve("upstream.status")));
    }

    @Test
    void failsClosedWhenTheApplicationExitsDuringTheToolCall() throws Exception {
        Path root = runnableProject("application-exit-during-call");
        TrackingMockFactory mocks = new TrackingMockFactory(false);

        var report = validator(mocks).validate(request(root, EXPECTED));

        assertCallFailureAndCleanup(report, root, mocks);
    }

    @Test
    void failsClosedWhenTheMcpToolCallTimesOut() throws Exception {
        Path root = runnableProject("tool-call-timeout");
        TrackingMockFactory mocks = new TrackingMockFactory(false);
        var client = new McpStreamableHttpClient(Duration.ofMillis(300), 8 * 1024);

        var report = validator(Duration.ofSeconds(3), Duration.ofSeconds(3), client, mocks)
                .validate(request(root, EXPECTED));

        assertCallFailureAndCleanup(report, root, mocks);
    }

    @Test
    void reportsAnOtherwiseSuccessfulMockCleanupFailureAtTheToolCallGate() throws Exception {
        Path root = runnableProject("");
        TrackingMockFactory mocks = new TrackingMockFactory(true);

        var report = validator(mocks).validate(request(root, EXPECTED));

        assertCallFailureAndCleanup(report, root, mocks);
        assertSummariesExclude(report, "cleanup-secret-like-value", "mcp-validation-secret-1");
    }

    @Test
    void preservesThePrimaryUpstreamFailureWhenMockCleanupAlsoFails() throws Exception {
        Path root = runnableProject("upstream-mismatch");
        TrackingMockFactory mocks = new TrackingMockFactory(true);

        var report = validator(mocks).validate(request(root, EXPECTED));

        assertCallFailureAndCleanup(report, root, mocks);
        assertSummariesExclude(report, "cleanup-secret-like-value", "mcp-validation-secret-1");
    }

    @Test
    void neverIncludesConfiguredArgumentsOrSyntheticSecretsInStageSummaries() throws Exception {
        Path root = runnableProject("");
        String configuredValue = "configured-secret-like-value";

        var report = validator().validate(request(root, EXPECTED, expectedToolCall(configuredValue)));

        assertEquals(VALIDATED, report.status(), report.toString());
        assertSummariesExclude(report, configuredValue, "mcp-validation-secret-1");
        assertEquals("Representative MCP Tool call matched the mock upstream contract",
                report.stages().get(4).summary());
        assertTrue(waitUntilDead(readPid(root)));
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
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

    private GradleMcpProjectValidator validator(GradleMcpProjectValidator.MockUpstreamFactory mockUpstreamFactory) {
        return validator(Duration.ofSeconds(3), Duration.ofSeconds(3), mockUpstreamFactory);
    }

    private GradleMcpProjectValidator validator(
            GradleMcpProjectValidator.MockUpstreamFactory mockUpstreamFactory,
            GradleMcpProjectValidator.ApplicationCleanupHook applicationCleanupHook) {
        return validator(
                mockUpstreamFactory,
                GradleMcpProjectValidator.ApplicationLaunchHook.NOOP,
                applicationCleanupHook);
    }

    private GradleMcpProjectValidator validator(
            GradleMcpProjectValidator.MockUpstreamFactory mockUpstreamFactory,
            GradleMcpProjectValidator.ApplicationLaunchHook applicationLaunchHook) {
        return validator(
                mockUpstreamFactory,
                applicationLaunchHook,
                GradleMcpProjectValidator.ApplicationCleanupHook.NOOP);
    }

    private GradleMcpProjectValidator validator(
            GradleMcpProjectValidator.MockUpstreamFactory mockUpstreamFactory,
            GradleMcpProjectValidator.ApplicationLaunchHook applicationLaunchHook,
            GradleMcpProjectValidator.ApplicationCleanupHook applicationCleanupHook) {
        return new GradleMcpProjectValidator(
                new BoundedProcessRunner(), new LoopbackPortAllocator(), new McpStreamableHttpClient(),
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofMillis(20), 8 * 1024,
                GradleMcpProjectValidator.WrapperSnapshotHook.NOOP,
                applicationLaunchHook,
                mockUpstreamFactory,
                applicationCleanupHook);
    }

    private GradleMcpProjectValidator validator(
            Duration buildTimeout,
            Duration startupTimeout,
            GradleMcpProjectValidator.MockUpstreamFactory mockUpstreamFactory) {
        return validator(buildTimeout, startupTimeout, new McpStreamableHttpClient(), mockUpstreamFactory);
    }

    private GradleMcpProjectValidator validator(
            Duration buildTimeout,
            Duration startupTimeout,
            McpStreamableHttpClient mcpClient,
            GradleMcpProjectValidator.MockUpstreamFactory mockUpstreamFactory) {
        return new GradleMcpProjectValidator(
                new BoundedProcessRunner(), new LoopbackPortAllocator(), mcpClient,
                buildTimeout, startupTimeout, Duration.ofMillis(20), 8 * 1024,
                GradleMcpProjectValidator.WrapperSnapshotHook.NOOP,
                GradleMcpProjectValidator.ApplicationLaunchHook.NOOP,
                mockUpstreamFactory);
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

    private GradleMcpProjectValidator validator(JavaRuntimeResolver resolver) {
        return validator(
                resolver,
                GradleMcpProjectValidator.WrapperSnapshotHook.NOOP,
                GradleMcpProjectValidator.ApplicationLaunchHook.NOOP);
    }

    private GradleMcpProjectValidator validator(
            JavaRuntimeResolver resolver,
            GradleMcpProjectValidator.WrapperSnapshotHook wrapperHook,
            GradleMcpProjectValidator.ApplicationLaunchHook launchHook) {
        return new GradleMcpProjectValidator(
                new BoundedProcessRunner(), new LoopbackPortAllocator(), new McpStreamableHttpClient(),
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofMillis(20), 8 * 1024,
                wrapperHook, launchHook, new TrackingMockFactory(false),
                GradleMcpProjectValidator.ApplicationCleanupHook.NOOP, resolver);
    }

    private ValidationRequest request(Path root, Map<String, ExpectedTool> expected) {
        return new ValidationRequest(root, ARTIFACT_ID, MCP_PROTOCOL, expected, expectedToolCall());
    }

    private ValidationRequest request(
            Path root,
            Map<String, ExpectedTool> expected,
            ExpectedToolCall expectedToolCall) {
        return new ValidationRequest(root, ARTIFACT_ID, MCP_PROTOCOL, expected, expectedToolCall);
    }

    private ValidationRequest request(
            Path root,
            Map<String, ExpectedTool> expected,
            CompatibilityProfile profile) {
        return new ValidationRequest(root, ARTIFACT_ID, MCP_PROTOCOL, expected, expectedToolCall(), profile);
    }

    private ExpectedToolCall expectedToolCall() {
        return expectedToolCall(60);
    }

    private ExpectedToolCall expectedToolCall(Object nx) {
        return new ExpectedToolCall(new McpToolDefinition(
                "getForecast", "kma_weather_get_forecast", "Get the public weather forecast.",
                List.of(),
                new HttpExecutionDefinition(
                        GET,
                        URI.create("https://api.example.test"),
                        "/forecast",
                        List.of(new ParameterBinding("nx", QUERY, "nx"))),
                List.of(new SecretBinding(
                        "VALIDATOR_SERVICE_KEY", "service-key", QUERY, "serviceKey", true)),
                McpToolDefinition.OutputKind.GENERIC_JSON), Map.of("nx", nx));
    }

    private ExpectedToolCall paginatedExpectedToolCall() {
        ExpectedToolCall base = expectedToolCall();
        HttpExecutionDefinition execution = base.tool().execution();
        var tool = new McpToolDefinition(
                base.tool().operationId(), base.tool().name(), base.tool().description(), base.tool().inputs(),
                new HttpExecutionDefinition(
                        execution.method(), execution.baseUrl(), execution.path(), execution.bindings(),
                        false, false, null, null,
                        new PaginationPolicy("cursor", "first", "/items", "/next", 2, 10)),
                base.tool().secretBindings(), base.tool().output());
        return new ExpectedToolCall(
                tool,
                base.arguments(),
                List.of(
                        new ExpectedUpstreamInteraction(
                                Map.of("cursor", "first"),
                                ExpectedUpstreamOutcome.RESPONSE,
                                new ExpectedUpstreamResponse(
                                        200, "application/json", Map.of("items", List.of(1), "next", "second"))),
                        new ExpectedUpstreamInteraction(
                                Map.of("cursor", "second"),
                                ExpectedUpstreamOutcome.RESPONSE,
                                new ExpectedUpstreamResponse(
                                        200, "application/json", Map.of("items", List.of(2))))),
                Map.of("items", List.of(1, 2)));
    }

    private Path runnableProject(String behavior) throws IOException {
        Path root = project("#!/bin/sh\nexit 0\n");
        writeJar(root, ARTIFACT_ID + ".jar", McpTestApplication.class);
        if (!behavior.isEmpty()) {
            Files.writeString(root.resolve("test-behavior"), behavior);
        }
        return root;
    }

    private CompatibilityProfile java17() {
        return CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java17-mvc-streamable")
                .orElseThrow();
    }

    private RuntimeFixture runtimeFixture(String name) throws IOException {
        Path home = Files.createDirectories(tempDir.resolve(name).resolve("bin")).getParent().toRealPath();
        Path executable = home.resolve("bin/java");
        Files.createLink(executable, Path.of(System.getProperty("java.home"), "bin", "java"));
        assertTrue(Files.isExecutable(executable));
        return new RuntimeFixture(home, executable);
    }

    private JavaRuntimeResolver resolver(RuntimeFixture runtime, int feature) {
        return new JavaRuntimeResolver(
                Map.of("GEN2SPRING_JAVA_" + feature + "_HOME", runtime.home().toString()),
                Path.of(System.getProperty("java.home")),
                executable -> feature);
    }

    private void replace(Path executable) throws IOException {
        Files.delete(executable);
        Files.writeString(executable, "replacement");
        assertTrue(executable.toFile().setExecutable(true));
    }

    private record RuntimeFixture(Path home, Path executable) {}

    private void assertCallFailureAndCleanup(
            io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationReport report,
            Path root,
            TrackingMockFactory mocks) throws Exception {
        assertEquals(UNVERIFIED, report.status(), report.toString());
        assertEquals(List.of(SUCCESS, SUCCESS, SUCCESS, SUCCESS, FAILED),
                report.stages().stream().map(stage -> stage.status()).toList());
        assertEquals("MCP Tool call validation failed safely", report.stages().get(4).summary());
        assertEquals(List.of("kma_weather_get_forecast"),
                report.tools().stream().map(tool -> tool.name()).toList());
        assertTrue(waitUntilDead(readPid(root)));
        assertTrue(mocks.closed.get());
        assertTrue(waitUntilNoThreadWithPrefix("mock-upstream-"));
    }

    private void assertSummariesExclude(
            io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationReport report,
            String... values) {
        for (var stage : report.stages()) {
            for (String value : values) {
                assertFalse(stage.summary().contains(value), stage.toString());
            }
        }
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

    private boolean waitUntilNoThreadWithPrefix(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            boolean alive = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith(prefix));
            if (!alive) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static final class TrackingMockFactory implements GradleMcpProjectValidator.MockUpstreamFactory {
        private final boolean failOnClose;
        private final RuntimeException runtimeFailure;
        private final Error fatalFailure;
        private final boolean interruptBeforeFailure;
        private final Error closeFailure;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TrackingMockFactory(boolean failOnClose) {
            this(failOnClose, null, false);
        }

        private TrackingMockFactory(boolean failOnClose, Throwable failure, boolean interruptBeforeFailure) {
            this(failOnClose, failure, interruptBeforeFailure, null);
        }

        private TrackingMockFactory(
                boolean failOnClose,
                Throwable failure,
                boolean interruptBeforeFailure,
                Error closeFailure) {
            this.failOnClose = failOnClose;
            this.runtimeFailure = failure instanceof RuntimeException exception ? exception : null;
            this.fatalFailure = failure instanceof Error error ? error : null;
            this.interruptBeforeFailure = interruptBeforeFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public GradleMcpProjectValidator.RunningMockUpstream start(List<UpstreamCallExpectation> expectations)
                throws IOException {
            MockUpstreamServer delegate = MockUpstreamServer.start(expectations);
            return new GradleMcpProjectValidator.RunningMockUpstream() {
                @Override
                public URI baseUri() {
                    return delegate.baseUri();
                }

                @Override
                public Map<String, String> environmentOverrides() {
                    return delegate.environmentOverrides();
                }

                @Override
                public void sealAndAwaitVerified(Duration timeout) {
                    delegate.sealAndAwaitVerified(timeout);
                    if (interruptBeforeFailure) {
                        Thread.currentThread().interrupt();
                    }
                    if (fatalFailure != null) {
                        throw fatalFailure;
                    }
                    if (runtimeFailure != null) {
                        throw runtimeFailure;
                    }
                }

                @Override
                public void close() throws IOException {
                    try {
                        delegate.close();
                    } finally {
                        closed.set(true);
                    }
                    if (closeFailure != null) {
                        throw closeFailure;
                    }
                    if (failOnClose) {
                        throw new IOException("cleanup failed near cleanup-secret-like-value");
                    }
                }
            };
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
