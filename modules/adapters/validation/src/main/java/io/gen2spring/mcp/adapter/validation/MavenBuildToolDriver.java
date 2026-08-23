package io.gen2spring.mcp.adapter.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class MavenBuildToolDriver implements BuildToolDriver {
    private static final Duration DEFAULT_BUILD_TIMEOUT = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_PROCESS_OUTPUT_BYTES = 64 * 1024;

    private final BoundedProcessRunner processRunner;
    private final ValidationHostPlatform platform;
    private final Duration buildTimeout;
    private final int maxProcessOutputBytes;

    MavenBuildToolDriver() {
        this(new BoundedProcessRunner(), ValidationHostPlatform.current(),
                DEFAULT_BUILD_TIMEOUT, DEFAULT_MAX_PROCESS_OUTPUT_BYTES);
    }

    MavenBuildToolDriver(
            BoundedProcessRunner processRunner,
            ValidationHostPlatform platform,
            Duration buildTimeout,
            int maxProcessOutputBytes) {
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.buildTimeout = Objects.requireNonNull(buildTimeout, "buildTimeout");
        this.maxProcessOutputBytes = maxProcessOutputBytes;
    }

    @Override
    public Result build(Request request) {
        Path root = request.root().toAbsolutePath().normalize();
        String wrapperName = platform.wrapperFileName("MAVEN");
        try (VerifiedWrapper wrapper = VerifiedWrapper.pin(
                root, root.resolve(wrapperName), platform, "MAVEN")) {
            BoundedProcessRunner.Result result = processRunner.runWithEnvironmentOverlay(
                    platform.buildCommand(wrapper.verifiedExecutable(), arguments(request.javaHome())),
                    root,
                    buildTimeout,
                    maxProcessOutputBytes,
                    Map.of("JAVA_HOME", request.javaHome().toString()));
            return new Result(
                    result.exitCode(), result.timedOut(), result.processAlive(), result.safeSummary());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result(-1, false, false, "build interrupted");
        } catch (IOException | RuntimeException exception) {
            return new Result(-1, false, false, "build process failed safely");
        }
    }

    List<String> arguments(Path javaHome) {
        return List.of("test", "package", "--batch-mode", "--no-transfer-progress");
    }

    @Override
    public Path relativeArtifact(String artifactId) {
        return Path.of("target", artifactId + ".jar");
    }
}
