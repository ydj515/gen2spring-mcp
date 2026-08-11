package io.gen2spring.mcp.validation;

import static io.gen2spring.mcp.domain.config.GenerationRequest.ValidationLevel.MCP_PROTOCOL;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.FAILED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.SKIPPED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.SUCCESS;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.UNVERIFIED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.VALIDATED;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedTool;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedToolCall;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectValidator;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationProgress;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationProgressListener;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ObservedTool;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ProgressStatus;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationReport;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationRequest;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStageResult;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

public final class GradleMcpProjectValidator implements GeneratedProjectValidator {
    private static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Duration DEFAULT_BUILD_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration POST_MCP_OBSERVATION_WINDOW = Duration.ofMillis(50);
    private static final int DEFAULT_MAX_PROCESS_OUTPUT_BYTES = 64 * 1024;
    private static final Pattern TOMCAT_STARTUP_PORT = Pattern.compile(
            "(?m)^.*\\bTomcat started on port ([1-9][0-9]{0,4}) \\(http\\) with context path '.*'$");
    private static final List<String> ORDERED_STAGES = List.of(
            "COMPILE", "APPLICATION_CONTEXT", "MCP_INITIALIZE", "MCP_TOOLS_LIST", "MCP_TOOL_CALL");
    private static final String INITIALIZE_SUCCESS = "MCP initialize contract matched";
    private static final String TOOLS_LIST_SUCCESS = "MCP Tool metadata matched the generated contract";
    private static final String TOOL_CALL_SUCCESS =
            "Representative MCP Tool call matched the mock upstream contract";
    private static final String TOOL_CALL_FAILURE = "MCP Tool call validation failed safely";

    private final BoundedProcessRunner processRunner;
    private final LoopbackPortAllocator portAllocator;
    private final McpStreamableHttpClient mcpClient;
    private final Duration buildTimeout;
    private final Duration startupTimeout;
    private final Duration pollInterval;
    private final int maxProcessOutputBytes;
    private final WrapperSnapshotHook wrapperSnapshotHook;
    private final ApplicationLaunchHook applicationLaunchHook;
    private final MockUpstreamFactory mockUpstreamFactory;
    private final ApplicationCleanupHook applicationCleanupHook;
    private final JavaRuntimeResolver javaRuntimeResolver;

    public GradleMcpProjectValidator() {
        this(new BoundedProcessRunner(), new LoopbackPortAllocator(), new McpStreamableHttpClient(),
                DEFAULT_BUILD_TIMEOUT, DEFAULT_STARTUP_TIMEOUT, DEFAULT_POLL_INTERVAL,
                DEFAULT_MAX_PROCESS_OUTPUT_BYTES, WrapperSnapshotHook.NOOP, ApplicationLaunchHook.NOOP,
                GradleMcpProjectValidator::startMockUpstream);
    }

    GradleMcpProjectValidator(
            Duration buildTimeout,
            Duration startupTimeout,
            Duration pollInterval,
            int maxProcessOutputBytes) {
        this(new BoundedProcessRunner(), new LoopbackPortAllocator(), new McpStreamableHttpClient(),
                buildTimeout, startupTimeout, pollInterval, maxProcessOutputBytes, WrapperSnapshotHook.NOOP,
                ApplicationLaunchHook.NOOP, GradleMcpProjectValidator::startMockUpstream);
    }

    GradleMcpProjectValidator(
            Duration buildTimeout,
            Duration startupTimeout,
            Duration pollInterval,
            int maxProcessOutputBytes,
            WrapperSnapshotHook wrapperSnapshotHook) {
        this(buildTimeout, startupTimeout, pollInterval, maxProcessOutputBytes, wrapperSnapshotHook,
                ApplicationLaunchHook.NOOP);
    }

    GradleMcpProjectValidator(
            Duration buildTimeout,
            Duration startupTimeout,
            Duration pollInterval,
            int maxProcessOutputBytes,
            WrapperSnapshotHook wrapperSnapshotHook,
            ApplicationLaunchHook applicationLaunchHook) {
        this(new BoundedProcessRunner(), new LoopbackPortAllocator(), new McpStreamableHttpClient(),
                buildTimeout, startupTimeout, pollInterval, maxProcessOutputBytes, wrapperSnapshotHook,
                applicationLaunchHook, GradleMcpProjectValidator::startMockUpstream);
    }

    GradleMcpProjectValidator(
            BoundedProcessRunner processRunner,
            LoopbackPortAllocator portAllocator,
            McpStreamableHttpClient mcpClient,
            Duration buildTimeout,
            Duration startupTimeout,
            Duration pollInterval,
            int maxProcessOutputBytes,
            WrapperSnapshotHook wrapperSnapshotHook,
            ApplicationLaunchHook applicationLaunchHook) {
        this(processRunner, portAllocator, mcpClient, buildTimeout, startupTimeout, pollInterval,
                maxProcessOutputBytes, wrapperSnapshotHook, applicationLaunchHook,
                GradleMcpProjectValidator::startMockUpstream);
    }

    GradleMcpProjectValidator(
            BoundedProcessRunner processRunner,
            LoopbackPortAllocator portAllocator,
            McpStreamableHttpClient mcpClient,
            Duration buildTimeout,
            Duration startupTimeout,
            Duration pollInterval,
            int maxProcessOutputBytes,
            WrapperSnapshotHook wrapperSnapshotHook,
            ApplicationLaunchHook applicationLaunchHook,
            MockUpstreamFactory mockUpstreamFactory) {
        this(processRunner, portAllocator, mcpClient, buildTimeout, startupTimeout, pollInterval,
                maxProcessOutputBytes, wrapperSnapshotHook, applicationLaunchHook, mockUpstreamFactory,
                ApplicationCleanupHook.NOOP);
    }

    GradleMcpProjectValidator(
            BoundedProcessRunner processRunner,
            LoopbackPortAllocator portAllocator,
            McpStreamableHttpClient mcpClient,
            Duration buildTimeout,
            Duration startupTimeout,
            Duration pollInterval,
            int maxProcessOutputBytes,
            WrapperSnapshotHook wrapperSnapshotHook,
            ApplicationLaunchHook applicationLaunchHook,
            MockUpstreamFactory mockUpstreamFactory,
            ApplicationCleanupHook applicationCleanupHook) {
        this(processRunner, portAllocator, mcpClient, buildTimeout, startupTimeout, pollInterval,
                maxProcessOutputBytes, wrapperSnapshotHook, applicationLaunchHook, mockUpstreamFactory,
                applicationCleanupHook, new JavaRuntimeResolver());
    }

    GradleMcpProjectValidator(
            BoundedProcessRunner processRunner,
            LoopbackPortAllocator portAllocator,
            McpStreamableHttpClient mcpClient,
            Duration buildTimeout,
            Duration startupTimeout,
            Duration pollInterval,
            int maxProcessOutputBytes,
            WrapperSnapshotHook wrapperSnapshotHook,
            ApplicationLaunchHook applicationLaunchHook,
            MockUpstreamFactory mockUpstreamFactory,
            ApplicationCleanupHook applicationCleanupHook,
            JavaRuntimeResolver javaRuntimeResolver) {
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.portAllocator = Objects.requireNonNull(portAllocator, "portAllocator");
        this.mcpClient = Objects.requireNonNull(mcpClient, "mcpClient");
        this.buildTimeout = positive(buildTimeout, "buildTimeout");
        this.startupTimeout = positive(startupTimeout, "startupTimeout");
        this.pollInterval = positive(pollInterval, "pollInterval");
        this.wrapperSnapshotHook = Objects.requireNonNull(wrapperSnapshotHook, "wrapperSnapshotHook");
        this.applicationLaunchHook = Objects.requireNonNull(applicationLaunchHook, "applicationLaunchHook");
        this.mockUpstreamFactory = Objects.requireNonNull(mockUpstreamFactory, "mockUpstreamFactory");
        this.applicationCleanupHook = Objects.requireNonNull(applicationCleanupHook, "applicationCleanupHook");
        this.javaRuntimeResolver = Objects.requireNonNull(javaRuntimeResolver, "javaRuntimeResolver");
        if (maxProcessOutputBytes <= 0) {
            throw new IllegalArgumentException("maxProcessOutputBytes must be positive");
        }
        this.maxProcessOutputBytes = maxProcessOutputBytes;
    }

    @Override
    public ValidationReport validate(ValidationRequest request) {
        return validate(request, GenerationProgressListener.NOOP);
    }

    @Override
    public ValidationReport validate(
            ValidationRequest request,
            GenerationProgressListener listener) {
        ValidationProgress progress = new ValidationProgress(listener);
        progress.start("COMPILE");
        try {
            return progress.complete(validate(request, progress));
        } catch (Error | RuntimeException failure) {
            progress.failActiveAndSkipRemaining();
            throw failure;
        }
    }

    private ValidationReport validate(
            ValidationRequest request,
            ValidationProgress progress) {
        ValidatedRequest validated = validateRequest(request);
        List<ValidationStageResult> stages = new ArrayList<>();

        long compileStarted = System.nanoTime();
        try {
            validated = validated.withRuntime(javaRuntimeResolver.resolve(validated.profile()));
        } catch (JavaRuntimeResolver.JavaRuntimeException exception) {
            stages.add(failed("COMPILE", compileStarted, exception.getMessage()));
            return failedReport(stages, List.of());
        }
        VerifiedGradleWrapper gradleWrapper;
        try {
            gradleWrapper = pinGradleWrapper(validated.root(), validated.root().resolve("gradlew"));
        } catch (IllegalArgumentException exception) {
            stages.add(failed("COMPILE", compileStarted, "Gradle wrapper is unsafe or unavailable"));
            return failedReport(stages, List.of());
        }
        BoundedProcessRunner.Result build;
        try (gradleWrapper) {
            wrapperSnapshotHook.beforeExecution(gradleWrapper.original(), gradleWrapper.snapshot());
            validated.runtime().requireStable();
            Path executable = gradleWrapper.verifiedExecutable();
            build = processRunner.run(
                    List.of(
                            executable.toString(),
                            "-Dorg.gradle.java.installations.auto-detect=false",
                            "-Dorg.gradle.java.installations.auto-download=false",
                            "-Dorg.gradle.java.installations.paths=" + validated.runtime().home(),
                            "classes", "test", "bootJar", "--no-daemon", "--non-interactive"),
                    validated.root(), buildTimeout, maxProcessOutputBytes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stages.add(failed("COMPILE", compileStarted, "build interrupted"));
            return failedReport(stages, List.of());
        } catch (JavaRuntimeResolver.JavaRuntimeException exception) {
            stages.add(failed("COMPILE", compileStarted, exception.getMessage()));
            return failedReport(stages, List.of());
        } catch (IOException | RuntimeException exception) {
            stages.add(failed("COMPILE", compileStarted, "build process failed safely"));
            return failedReport(stages, List.of());
        }
        if (build.timedOut() || build.exitCode() != 0 || build.processAlive()) {
            stages.add(stage("COMPILE", FAILED, compileStarted, 1, build.safeSummary()));
            return failedReport(stages, List.of());
        }
        stages.add(stage("COMPILE", SUCCESS, compileStarted, 0, build.safeSummary()));
        progress.succeed("COMPILE");

        long applicationStarted = System.nanoTime();
        Path jar;
        try {
            jar = resolveArtifact(validated.root(), validated.artifactId());
        } catch (ArtifactResolutionException exception) {
            stages.add(failed("APPLICATION_CONTEXT", applicationStarted, exception.getMessage()));
            return failedReport(stages, List.of());
        }
        return validateRunningApplication(validated, jar, stages, applicationStarted, progress);
    }

    private ValidationReport validateRunningApplication(
            ValidatedRequest request,
            Path jar,
            List<ValidationStageResult> stages,
            long applicationStarted,
            ValidationProgress progress) {
        BoundedProcessRunner.RunningProcess application = null;
        BoundedProcessRunner.Result applicationResult = null;
        Readiness readiness = Readiness.START_FAILED;
        long applicationDurationMillis = 0;
        McpStreamableHttpClient.Result mcpResult = null;
        McpStreamableHttpClient.McpValidationException mcpFailure = null;
        String applicationFailure = null;
        Throwable toolCallFailure = null;
        Throwable primaryFailure = null;
        ValidationPhase phase = ValidationPhase.MOCK_START;
        try {
            List<UpstreamCallExpectation> expectations = UpstreamCallExpectation.allFrom(request.expectedToolCall());
            RunningMockUpstream upstream = mockUpstreamFactory.start(expectations);
            progress.start("APPLICATION_CONTEXT");
            Throwable upstreamFailure = null;
            try {
                phase = ValidationPhase.APPLICATION_START;
                Map<String, String> environment = new TreeMap<>(upstream.environmentOverrides());
                environment.put("PROVIDER_BASE_URL", upstream.baseUri().toString());
                List<String> command = List.of(
                        request.runtime().executable().toString(), "-jar", jar.toString(),
                        "--server.address=127.0.0.1", "--server.port=0");
                applicationLaunchHook.beforeLaunch(command);
                request.runtime().requireStable();
                application = processRunner.start(command, request.root(), maxProcessOutputBytes, environment);
                phase = ValidationPhase.APPLICATION_READINESS;
                ReadinessResult readinessResult = awaitReadiness(application);
                readiness = readinessResult.status();
                applicationDurationMillis = elapsedMillis(applicationStarted);
                if (readiness != Readiness.READY) {
                    throw new ApplicationStageException(readinessFailure(readiness));
                }
                if (!application.isAlive()) {
                    throw new ApplicationStageException("application exited after publishing its endpoint");
                }

                phase = ValidationPhase.MCP_VALIDATION;
                mcpResult = mcpClient.validate(
                        readinessResult.endpoint(), request.expectedTools(), request.expectedToolCall(),
                        progress::observeMcpStage);
                phase = ValidationPhase.UPSTREAM_VERIFICATION;
                upstream.sealAndAwaitVerified(startupTimeout);
                phase = ValidationPhase.APPLICATION_INTEGRITY;
                String integrityFailure = verifyApplicationAfterMcpRoundTrip(application, readinessResult.endpoint());
                if (integrityFailure != null) {
                    throw new ApplicationStageException(integrityFailure);
                }
                phase = ValidationPhase.COMPLETE;
            } catch (Throwable failure) {
                upstreamFailure = failure;
                throw failure;
            } finally {
                try {
                    upstream.close();
                } catch (Error cleanupFailure) {
                    if (upstreamFailure instanceof Error fatal) {
                        addSuppressedSafely(fatal, cleanupFailure);
                    } else {
                        if (upstreamFailure != null) {
                            addSuppressedSafely(cleanupFailure, upstreamFailure);
                            restoreInterruptedFlag(upstreamFailure);
                        }
                        throw cleanupFailure;
                    }
                } catch (IOException | RuntimeException cleanupFailure) {
                    if (upstreamFailure != null) {
                        addSuppressedSafely(upstreamFailure, cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        } catch (McpStreamableHttpClient.McpValidationException exception) {
            mcpFailure = exception;
            primaryFailure = exception;
        } catch (ApplicationStageException exception) {
            if (phase == ValidationPhase.APPLICATION_INTEGRITY) {
                toolCallFailure = exception;
            } else {
                applicationFailure = exception.safeSummary();
            }
            primaryFailure = exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            primaryFailure = exception;
            if (phase == ValidationPhase.UPSTREAM_VERIFICATION || phase == ValidationPhase.MCP_VALIDATION) {
                toolCallFailure = exception;
            } else {
                applicationFailure = "application startup was interrupted";
            }
        } catch (Error fatal) {
            primaryFailure = fatal;
            throw fatal;
        } catch (IOException | RuntimeException exception) {
            primaryFailure = exception;
            if (phase == ValidationPhase.MOCK_START) {
                toolCallFailure = exception;
            } else if (phase == ValidationPhase.UPSTREAM_VERIFICATION || phase == ValidationPhase.COMPLETE) {
                toolCallFailure = exception;
            } else {
                applicationFailure = phase == ValidationPhase.APPLICATION_INTEGRITY
                        ? "application post-MCP verification failed safely"
                        : "application process failed safely";
            }
        } finally {
            if (application != null) {
                try {
                    application.close();
                    applicationCleanupHook.afterClose();
                    applicationResult = application.result(false);
                } catch (Error cleanupFailure) {
                    if (primaryFailure instanceof Error fatal) {
                        addSuppressedSafely(fatal, cleanupFailure);
                    } else {
                        if (primaryFailure != null) {
                            addSuppressedSafely(cleanupFailure, primaryFailure);
                        }
                        throw cleanupFailure;
                    }
                } catch (IOException | RuntimeException cleanupFailure) {
                    if (primaryFailure != null) {
                        addSuppressedSafely(primaryFailure, cleanupFailure);
                    } else {
                        primaryFailure = cleanupFailure;
                        if (readiness == Readiness.READY) {
                            toolCallFailure = cleanupFailure;
                        } else {
                            applicationFailure = "application cleanup failed safely";
                        }
                    }
                }
            }
        }
        if (applicationDurationMillis == 0) {
            applicationDurationMillis = elapsedMillis(applicationStarted);
        }

        if (phase == ValidationPhase.MOCK_START && toolCallFailure != null) {
            return unavailableToolCallReport(stages, applicationStarted);
        }
        if (applicationFailure != null) {
            stages.add(new ValidationStageResult(
                    "APPLICATION_CONTEXT", FAILED, applicationDurationMillis, 0, 1, applicationFailure));
            return failedReport(stages, List.of());
        }
        if (readiness != Readiness.READY) {
            stages.add(new ValidationStageResult(
                    "APPLICATION_CONTEXT", FAILED, applicationDurationMillis, 0, 1,
                    readinessFailure(readiness) + safeApplicationSuffix(applicationResult)));
            return failedReport(stages, List.of());
        }
        stages.add(new ValidationStageResult(
                "APPLICATION_CONTEXT", SUCCESS, applicationDurationMillis, 0, 0,
                "application accepted loopback connections" + safeApplicationSuffix(applicationResult)));

        if (mcpFailure != null) {
            return mcpFailureReport(stages, mcpFailure);
        }
        if (toolCallFailure != null) {
            if (mcpResult == null) {
                stages.add(failed("MCP_INITIALIZE", System.nanoTime(), "MCP validation did not return a result"));
                return failedReport(stages, List.of());
            }
            appendSuccessfulMcpPrerequisites(stages, mcpResult);
            stages.add(new ValidationStageResult(
                    "MCP_TOOL_CALL", FAILED, mcpResult.toolsCallDurationMillis(), 0, 1, TOOL_CALL_FAILURE));
            return failedReport(stages, mcpResult.tools());
        }
        if (mcpResult == null) {
            stages.add(failed("MCP_INITIALIZE", System.nanoTime(), "MCP validation did not return a result"));
            return failedReport(stages, List.of());
        }
        appendSuccessfulMcpPrerequisites(stages, mcpResult);
        stages.add(new ValidationStageResult(
                "MCP_TOOL_CALL", SUCCESS, mcpResult.toolsCallDurationMillis(), 0, 0, TOOL_CALL_SUCCESS));
        return new ValidationReport(VALIDATED, List.copyOf(stages), mcpResult.tools());
    }

    private static void addSuppressedSafely(Throwable primary, Throwable suppressed) {
        if (primary == suppressed) {
            return;
        }
        for (Throwable existing : primary.getSuppressed()) {
            if (existing == suppressed) {
                return;
            }
        }
        primary.addSuppressed(suppressed);
    }

    private static void restoreInterruptedFlag(Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readinessFailure(Readiness readiness) {
        return switch (readiness) {
            case TIMED_OUT -> "application startup timeout";
            case OUTPUT_LIMIT_REACHED -> "application startup output exceeded the discovery limit";
            case PORT_DISCOVERY_INVALID -> "application did not publish one valid bound loopback port";
            default -> "application exited before readiness";
        };
    }

    private void appendSuccessfulMcpPrerequisites(
            List<ValidationStageResult> stages,
            McpStreamableHttpClient.Result result) {
        stages.add(new ValidationStageResult(
                "MCP_INITIALIZE", SUCCESS, result.initializeDurationMillis(), 0, 0, INITIALIZE_SUCCESS));
        stages.add(new ValidationStageResult(
                "MCP_TOOLS_LIST", SUCCESS, result.toolsListDurationMillis(), 0, 0, TOOLS_LIST_SUCCESS));
    }

    private ValidationReport unavailableToolCallReport(List<ValidationStageResult> stages, long started) {
        while (stages.size() < ORDERED_STAGES.size() - 1) {
            stages.add(new ValidationStageResult(
                    ORDERED_STAGES.get(stages.size()), SKIPPED, 0, 0, 0,
                    "skipped because the loopback mock upstream was unavailable"));
        }
        stages.add(stage("MCP_TOOL_CALL", FAILED, started, 1, TOOL_CALL_FAILURE));
        return new ValidationReport(UNVERIFIED, List.copyOf(stages), List.of());
    }

    private String verifyApplicationAfterMcpRoundTrip(
            BoundedProcessRunner.RunningProcess application,
            URI expectedEndpoint) throws IOException, InterruptedException {
        String immediateFailure = applicationIntegrityFailure(application, expectedEndpoint);
        if (immediateFailure != null) {
            return immediateFailure;
        }
        Thread.sleep(POST_MCP_OBSERVATION_WINDOW.toMillis());
        return applicationIntegrityFailure(application, expectedEndpoint);
    }

    private String applicationIntegrityFailure(BoundedProcessRunner.RunningProcess application, URI expectedEndpoint)
            throws IOException, InterruptedException {
        application.requireCollectorsHealthy();
        if (!application.isAlive()) {
            return "application exited after MCP validation";
        }
        if (application.stdoutTruncated()) {
            return "application output exceeded the discovery limit after MCP validation";
        }
        EndpointDiscovery discovery = discoverEndpoint(application.stdoutSnapshot());
        if (discovery.invalid() || discovery.endpoint() == null || !expectedEndpoint.equals(discovery.endpoint())) {
            return "application endpoint changed during MCP validation";
        }
        return null;
    }

    private ValidationReport mcpFailureReport(
            List<ValidationStageResult> stages,
            McpStreamableHttpClient.McpValidationException failure) {
        if (failure.stage() == McpStreamableHttpClient.McpStage.INITIALIZE) {
            stages.add(new ValidationStageResult(
                    "MCP_INITIALIZE", FAILED, failure.initializeDurationMillis(), 0, 1, failure.getMessage()));
        } else if (failure.stage() == McpStreamableHttpClient.McpStage.TOOLS_LIST) {
            stages.add(new ValidationStageResult(
                    "MCP_INITIALIZE", SUCCESS, failure.initializeDurationMillis(), 0, 0,
                    INITIALIZE_SUCCESS));
            stages.add(new ValidationStageResult(
                    "MCP_TOOLS_LIST", FAILED, failure.toolsListDurationMillis(), 0, 1, failure.getMessage()));
        } else {
            stages.add(new ValidationStageResult(
                    "MCP_INITIALIZE", SUCCESS, failure.initializeDurationMillis(), 0, 0, INITIALIZE_SUCCESS));
            stages.add(new ValidationStageResult(
                    "MCP_TOOLS_LIST", SUCCESS, failure.toolsListDurationMillis(), 0, 0, TOOLS_LIST_SUCCESS));
            stages.add(new ValidationStageResult(
                    "MCP_TOOL_CALL", FAILED, failure.toolsCallDurationMillis(), 0, 1, TOOL_CALL_FAILURE));
        }
        return failedReport(stages, failure.tools());
    }

    private ReadinessResult awaitReadiness(BoundedProcessRunner.RunningProcess application)
            throws InterruptedException {
        long deadline = deadline(startupTimeout);
        while (System.nanoTime() < deadline) {
            if (!application.isAlive()) {
                return new ReadinessResult(Readiness.EXITED, null);
            }
            if (application.stdoutTruncated()) {
                return new ReadinessResult(Readiness.OUTPUT_LIMIT_REACHED, null);
            }
            EndpointDiscovery discovery = discoverEndpoint(application.stdoutSnapshot());
            if (discovery.invalid()) {
                return new ReadinessResult(Readiness.PORT_DISCOVERY_INVALID, null);
            }
            if (discovery.endpoint() == null) {
                sleepUntilNextPoll(deadline);
                continue;
            }
            URI endpoint = discovery.endpoint();
            int connectMillis = (int) Math.max(1, Math.min(250,
                    Duration.ofNanos(Math.max(1, deadline - System.nanoTime())).toMillis()));
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", endpoint.getPort()), connectMillis);
                if (application.isAlive()) {
                    return new ReadinessResult(Readiness.READY, endpoint);
                }
                return new ReadinessResult(Readiness.EXITED, null);
            } catch (IOException ignored) {
                sleepUntilNextPoll(deadline);
            }
        }
        return new ReadinessResult(application.isAlive() ? Readiness.TIMED_OUT : Readiness.EXITED, null);
    }

    private EndpointDiscovery discoverEndpoint(String output) {
        var matcher = TOMCAT_STARTUP_PORT.matcher(output);
        URI endpoint = null;
        int matches = 0;
        while (matcher.find()) {
            matches++;
            if (matches > 1) {
                return EndpointDiscovery.INVALID;
            }
            try {
                endpoint = portAllocator.mcpUri(Integer.parseInt(matcher.group(1)));
            } catch (IllegalArgumentException exception) {
                return EndpointDiscovery.INVALID;
            }
        }
        return endpoint == null ? EndpointDiscovery.NOT_READY : new EndpointDiscovery(endpoint, false);
    }

    private void sleepUntilNextPoll(long deadline) throws InterruptedException {
        long sleepMillis = Math.max(1, Math.min(
                pollInterval.toMillis(),
                Duration.ofNanos(Math.max(1, deadline - System.nanoTime())).toMillis()));
        Thread.sleep(sleepMillis);
    }

    private Path resolveArtifact(Path root, String artifactId) {
        Path libraries = root.resolve("build/libs").normalize();
        if (!libraries.startsWith(root) || Files.isSymbolicLink(libraries)
                || !Files.isDirectory(libraries, NOFOLLOW_LINKS)) {
            throw new ArtifactResolutionException("application artifact is missing");
        }
        String exactName = artifactId + ".jar";
        List<Path> matches;
        try (var files = Files.list(libraries)) {
            matches = files
                    .filter(path -> isExecutableArtifactCandidate(path.getFileName().toString(), artifactId))
                    .filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new ArtifactResolutionException("application artifact could not be inspected");
        }
        if (matches.isEmpty() || matches.stream().noneMatch(path -> exactName.equals(path.getFileName().toString()))) {
            throw new ArtifactResolutionException("application artifact is missing");
        }
        if (matches.size() != 1) {
            throw new ArtifactResolutionException("application artifact is ambiguous");
        }
        return matches.getFirst().toAbsolutePath().normalize();
    }

    static VerifiedGradleWrapper pinGradleWrapper(Path validationRoot, Path candidate) {
        if (validationRoot == null || candidate == null) {
            throw new IllegalArgumentException("Gradle wrapper path is required");
        }
        Path root = validationRoot.toAbsolutePath().normalize();
        Path wrapper = candidate.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)
                || !wrapper.startsWith(root) || !root.equals(wrapper.getParent())) {
            throw new IllegalArgumentException("Gradle wrapper escaped the validation workspace");
        }
        Path snapshot = null;
        Object originalFileKey = null;
        try {
            Path realRoot = root.toRealPath();
            Path realParent = wrapper.getParent().toRealPath();
            if (!realRoot.equals(root) || !realParent.equals(realRoot)) {
                throw new IllegalArgumentException("Gradle wrapper ancestry is not physical");
            }
            BasicFileAttributes rootAttributes = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!rootAttributes.isDirectory()) {
                throw new IllegalArgumentException("Validation workspace is not a physical directory");
            }
            Object rootFileKey = requiredFileKey(rootAttributes);
            BasicFileAttributes original = regularFileAttributes(wrapper);
            originalFileKey = requiredFileKey(original);
            requireOwnerExecutable(wrapper);
            for (int attempt = 0; attempt < 32; attempt++) {
                Path candidateSnapshot = root.resolve(".gradlew-validated-" + UUID.randomUUID());
                try {
                    Files.createLink(candidateSnapshot, wrapper);
                    snapshot = candidateSnapshot;
                    break;
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // A new unpredictable candidate is tried without replacing the existing path.
                }
            }
            if (snapshot == null) {
                throw new IllegalArgumentException("Gradle wrapper snapshot path could not be reserved");
            }
            Object snapshotFileKey = requiredFileKey(regularFileAttributes(snapshot));
            if (!originalFileKey.equals(snapshotFileKey)) {
                throw new IllegalArgumentException("Gradle wrapper changed while its identity was pinned");
            }
            requireOwnerExecutable(snapshot);
            if (!snapshot.toAbsolutePath().normalize().startsWith(root)
                    || !root.equals(snapshot.getParent())
                    || !snapshot.getParent().toRealPath().equals(root)) {
                throw new IllegalArgumentException("Gradle wrapper snapshot escaped the validation workspace");
            }
            return new VerifiedGradleWrapper(root, rootFileKey, wrapper, snapshot, snapshotFileKey);
        } catch (IllegalArgumentException exception) {
            deleteIfSameFile(snapshot, originalFileKey);
            throw exception;
        } catch (IOException | UnsupportedOperationException exception) {
            deleteIfSameFile(snapshot, originalFileKey);
            throw new IllegalArgumentException("Gradle wrapper identity could not be pinned", exception);
        }
    }

    private static BasicFileAttributes regularFileAttributes(Path path) throws IOException {
        if (path == null || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("Gradle wrapper is not a regular workspace file");
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IllegalArgumentException("Gradle wrapper is not a regular workspace file");
        }
        return attributes;
    }

    private static Object requiredFileKey(BasicFileAttributes attributes) {
        Object fileKey = attributes.fileKey();
        if (fileKey == null) {
            throw new IllegalArgumentException("Validation filesystem does not expose stable file keys");
        }
        return fileKey;
    }

    private static void requireOwnerExecutable(Path wrapper) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                wrapper, PosixFileAttributeView.class, NOFOLLOW_LINKS);
        boolean executable = posix == null
                ? Files.isExecutable(wrapper)
                : posix.readAttributes().permissions().contains(PosixFilePermission.OWNER_EXECUTE);
        if (!executable) {
            throw new IllegalArgumentException("Gradle wrapper is not owner-executable");
        }
    }

    private static void deleteIfSameFile(Path path, Object expectedFileKey) {
        if (path == null || expectedFileKey == null) {
            return;
        }
        try {
            BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (current.isRegularFile() && expectedFileKey.equals(current.fileKey())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ignored) {
            // Fail closed: an unverified path is never removed.
        }
    }

    private boolean isExecutableArtifactCandidate(String fileName, String artifactId) {
        if (fileName.equals(artifactId + ".jar")) {
            return true;
        }
        return fileName.startsWith(artifactId + "-")
                && fileName.endsWith(".jar")
                && !fileName.endsWith("-plain.jar")
                && !fileName.endsWith("-sources.jar")
                && !fileName.endsWith("-javadoc.jar");
    }

    private ValidatedRequest validateRequest(ValidationRequest request) {
        if (request == null || request.projectRoot() == null || request.level() != MCP_PROTOCOL
                || request.expectedTools() == null || request.expectedToolCall() == null || request.artifactId() == null
                || !ARTIFACT_ID.matcher(request.artifactId()).matches()) {
            throw new IllegalArgumentException("Validation request is incomplete or unsupported");
        }
        Path supplied = request.projectRoot().toAbsolutePath().normalize();
        Path root;
        try {
            root = supplied.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Validation workspace is unavailable", exception);
        }
        if (!root.equals(supplied) || !Files.isDirectory(root, NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Validation workspace is not a directory");
        }
        Map<String, ExpectedTool> expected = new java.util.TreeMap<>();
        request.expectedTools().forEach((name, tool) -> {
            if (name == null || name.isBlank() || tool == null || tool.description() == null) {
                throw new IllegalArgumentException("Expected Tool metadata is incomplete");
            }
            expected.put(name, tool);
        });
        ExpectedToolCall expectedToolCall = request.expectedToolCall();
        if (expectedToolCall.tool() == null
                || expectedToolCall.tool().name() == null
                || !expected.containsKey(expectedToolCall.tool().name())) {
            throw new IllegalArgumentException("Expected Tool call is not bound to expected Tool metadata");
        }
        return new ValidatedRequest(
                root, request.artifactId(), Collections.unmodifiableMap(expected), expectedToolCall,
                request.profile(), null);
    }

    private ValidationReport failedReport(List<ValidationStageResult> attempted, List<ObservedTool> observed) {
        List<ValidationStageResult> complete = new ArrayList<>(attempted);
        while (complete.size() < ORDERED_STAGES.size()) {
            complete.add(new ValidationStageResult(
                    ORDERED_STAGES.get(complete.size()), SKIPPED, 0, 0, 0,
                    "skipped after an earlier validation failure"));
        }
        return new ValidationReport(UNVERIFIED, List.copyOf(complete), List.copyOf(observed));
    }

    private static final class ValidationProgress {
        private final GenerationProgressListener listener;
        private final Map<String, ProgressStatus> statuses = new java.util.LinkedHashMap<>();
        private final List<McpStreamableHttpClient.McpStage> observedMcpStages = new ArrayList<>();

        private ValidationProgress(GenerationProgressListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            ORDERED_STAGES.forEach(stage -> statuses.put(stage, ProgressStatus.PENDING));
        }

        private void start(String stage) {
            ProgressStatus current = status(stage);
            if (current == ProgressStatus.RUNNING) {
                return;
            }
            if (current != ProgressStatus.PENDING) {
                throw invalidTransition();
            }
            publish(stage, ProgressStatus.RUNNING);
        }

        private void succeed(String stage) {
            finish(stage, ProgressStatus.SUCCESS);
        }

        private void observeMcpStage(McpStreamableHttpClient.McpStage stage) {
            List<McpStreamableHttpClient.McpStage> order = List.of(
                    McpStreamableHttpClient.McpStage.INITIALIZE,
                    McpStreamableHttpClient.McpStage.TOOLS_LIST,
                    McpStreamableHttpClient.McpStage.TOOL_CALL);
            int index = observedMcpStages.size();
            if (index >= order.size() || order.get(index) != stage) {
                throw invalidTransition();
            }
            observedMcpStages.add(stage);
            switch (stage) {
                case INITIALIZE -> {
                    succeed("APPLICATION_CONTEXT");
                    start("MCP_INITIALIZE");
                }
                case TOOLS_LIST -> {
                    succeed("MCP_INITIALIZE");
                    start("MCP_TOOLS_LIST");
                }
                case TOOL_CALL -> {
                    succeed("MCP_TOOLS_LIST");
                    start("MCP_TOOL_CALL");
                }
            }
        }

        private ValidationReport complete(ValidationReport report) {
            Objects.requireNonNull(report, "report");
            for (ValidationStageResult result : report.stages()) {
                if (!ORDERED_STAGES.contains(result.stage())) {
                    continue;
                }
                ProgressStatus terminal = switch (result.status()) {
                    case SUCCESS -> ProgressStatus.SUCCESS;
                    case FAILED -> ProgressStatus.FAILED;
                    case SKIPPED -> ProgressStatus.SKIPPED;
                };
                if (status(result.stage()) == ProgressStatus.PENDING && terminal != ProgressStatus.SKIPPED) {
                    start(result.stage());
                }
                finish(result.stage(), terminal);
            }
            for (String stage : ORDERED_STAGES) {
                if (status(stage) == ProgressStatus.PENDING) {
                    finish(stage, ProgressStatus.SKIPPED);
                } else if (status(stage) == ProgressStatus.RUNNING) {
                    finish(stage, ProgressStatus.FAILED);
                }
            }
            return report;
        }

        private void failActiveAndSkipRemaining() {
            for (String stage : ORDERED_STAGES) {
                if (status(stage) == ProgressStatus.RUNNING) {
                    publish(stage, ProgressStatus.FAILED);
                    break;
                }
            }
            for (String stage : ORDERED_STAGES) {
                if (status(stage) == ProgressStatus.PENDING) {
                    publish(stage, ProgressStatus.SKIPPED);
                }
            }
        }

        private void finish(String stage, ProgressStatus terminal) {
            ProgressStatus current = status(stage);
            if (current == terminal) {
                return;
            }
            if (terminal == ProgressStatus.SKIPPED) {
                if (current != ProgressStatus.PENDING) {
                    throw invalidTransition();
                }
            } else if (current != ProgressStatus.RUNNING) {
                throw invalidTransition();
            }
            publish(stage, terminal);
        }

        private ProgressStatus status(String stage) {
            ProgressStatus status = statuses.get(stage);
            if (status == null) {
                throw invalidTransition();
            }
            return status;
        }

        private void publish(String stage, ProgressStatus status) {
            statuses.put(stage, status);
            listener.onProgress(new GenerationProgress(stage, status));
        }

        private IllegalStateException invalidTransition() {
            return new IllegalStateException("Generation progress transition is invalid");
        }
    }

    private static ValidationStageResult failed(String stage, long started, String summary) {
        return stage(stage, FAILED, started, 1, summary);
    }

    private static ValidationStageResult stage(
            String name,
            io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus status,
            long started,
            int errors,
            String summary) {
        return new ValidationStageResult(name, status, elapsedMillis(started), 0, errors, summary);
    }

    private static String safeApplicationSuffix(BoundedProcessRunner.Result result) {
        return result == null ? "" : "; " + result.safeSummary();
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private static long deadline(Duration duration) {
        long now = System.nanoTime();
        long nanos = duration.toNanos();
        return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
    }

    private enum Readiness { READY, EXITED, TIMED_OUT, START_FAILED, OUTPUT_LIMIT_REACHED, PORT_DISCOVERY_INVALID }

    private record ReadinessResult(Readiness status, URI endpoint) {}

    private record EndpointDiscovery(URI endpoint, boolean invalid) {
        private static final EndpointDiscovery NOT_READY = new EndpointDiscovery(null, false);
        private static final EndpointDiscovery INVALID = new EndpointDiscovery(null, true);
    }

    @FunctionalInterface
    interface WrapperSnapshotHook {
        WrapperSnapshotHook NOOP = (original, snapshot) -> {};

        void beforeExecution(Path original, Path snapshot) throws IOException;
    }

    @FunctionalInterface
    interface ApplicationLaunchHook {
        ApplicationLaunchHook NOOP = command -> {};

        void beforeLaunch(List<String> command) throws IOException;
    }

    @FunctionalInterface
    interface MockUpstreamFactory {
        RunningMockUpstream start(List<UpstreamCallExpectation> expectations) throws IOException;
    }

    interface RunningMockUpstream extends AutoCloseable {
        URI baseUri();

        Map<String, String> environmentOverrides();

        void sealAndAwaitVerified(Duration timeout);

        @Override
        void close() throws IOException;
    }

    @FunctionalInterface
    interface ApplicationCleanupHook {
        ApplicationCleanupHook NOOP = () -> {};

        void afterClose() throws IOException;
    }

    private static RunningMockUpstream startMockUpstream(List<UpstreamCallExpectation> expectations) throws IOException {
        MockUpstreamServer delegate = MockUpstreamServer.start(expectations);
        return new RunningMockUpstream() {
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
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }

    static final class VerifiedGradleWrapper implements AutoCloseable {
        private final Path root;
        private final Object rootFileKey;
        private final Path original;
        private final Path snapshot;
        private final Object fileKey;

        private VerifiedGradleWrapper(
                Path root,
                Object rootFileKey,
                Path original,
                Path snapshot,
                Object fileKey) {
            this.root = root;
            this.rootFileKey = rootFileKey;
            this.original = original;
            this.snapshot = snapshot;
            this.fileKey = fileKey;
        }

        Path original() {
            return original;
        }

        Path snapshot() {
            return snapshot;
        }

        Path verifiedExecutable() {
            try {
                BasicFileAttributes currentRoot = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (!currentRoot.isDirectory() || !rootFileKey.equals(requiredFileKey(currentRoot))
                        || !root.toRealPath().equals(root)
                        || !root.equals(snapshot.getParent())
                        || !snapshot.getParent().toRealPath().equals(root)) {
                    throw new IllegalArgumentException("Gradle wrapper snapshot escaped the validation workspace");
                }
                BasicFileAttributes current = regularFileAttributes(snapshot);
                if (!fileKey.equals(requiredFileKey(current))) {
                    throw new IllegalArgumentException("Gradle wrapper snapshot identity changed before execution");
                }
                requireOwnerExecutable(snapshot);
                return snapshot;
            } catch (IOException exception) {
                throw new IllegalArgumentException("Gradle wrapper snapshot could not be reverified", exception);
            }
        }

        @Override
        public void close() {
            try {
                BasicFileAttributes currentRoot = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (currentRoot.isDirectory() && rootFileKey.equals(requiredFileKey(currentRoot))) {
                    deleteIfSameFile(snapshot, fileKey);
                }
            } catch (IOException | RuntimeException ignored) {
                // Fail closed: cleanup never follows a changed workspace identity.
            }
        }
    }

    private record ValidatedRequest(
            Path root,
            String artifactId,
            Map<String, ExpectedTool> expectedTools,
            ExpectedToolCall expectedToolCall,
            CompatibilityProfile profile,
            JavaRuntimeResolver.ResolvedJavaRuntime runtime) {
        private ValidatedRequest withRuntime(JavaRuntimeResolver.ResolvedJavaRuntime resolvedRuntime) {
            return new ValidatedRequest(root, artifactId, expectedTools, expectedToolCall, profile, resolvedRuntime);
        }
    }

    private enum ValidationPhase {
        MOCK_START,
        APPLICATION_START,
        APPLICATION_READINESS,
        MCP_VALIDATION,
        UPSTREAM_VERIFICATION,
        APPLICATION_INTEGRITY,
        COMPLETE
    }

    private static final class ApplicationStageException extends RuntimeException {
        private final String safeSummary;

        private ApplicationStageException(String safeSummary) {
            super(safeSummary);
            this.safeSummary = safeSummary;
        }

        private String safeSummary() {
            return safeSummary;
        }
    }

    private static final class ArtifactResolutionException extends RuntimeException {
        private ArtifactResolutionException(String message) {
            super(message);
        }
    }
}
