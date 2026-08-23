package io.gen2spring.mcp.adapter.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

final class GradleBuildToolDriver implements BuildToolDriver {
    private static final Duration DEFAULT_BUILD_TIMEOUT = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_PROCESS_OUTPUT_BYTES = 64 * 1024;

    private final BoundedProcessRunner processRunner;
    private final ValidationHostPlatform platform;
    private final Duration buildTimeout;
    private final int maxProcessOutputBytes;

    GradleBuildToolDriver() {
        this(new BoundedProcessRunner(), ValidationHostPlatform.current(),
                DEFAULT_BUILD_TIMEOUT, DEFAULT_MAX_PROCESS_OUTPUT_BYTES);
    }

    GradleBuildToolDriver(
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
        return execute(request, arguments(request.javaHome()));
    }

    List<String> arguments(Path javaHome) {
        return List.of(
                "-Dorg.gradle.java.installations.auto-detect=false",
                "-Dorg.gradle.java.installations.auto-download=false",
                "-Dorg.gradle.java.installations.paths=" + javaHome,
                "classes", "test", "bootJar", "--no-daemon", "--non-interactive");
    }

    @Override
    public Path relativeArtifact(String artifactId) {
        return Path.of("build", "libs", artifactId + ".jar");
    }

    private Result execute(Request request, List<String> arguments) {
        Path root = request.root().toAbsolutePath().normalize();
        String wrapperName = platform.wrapperFileName("GRADLE_KOTLIN");
        try (VerifiedWrapper wrapper = VerifiedWrapper.pin(
                root, root.resolve(wrapperName), platform, "GRADLE_KOTLIN")) {
            BoundedProcessRunner.Result result = processRunner.run(
                    platform.buildCommand(wrapper.verifiedExecutable(), arguments),
                    root,
                    buildTimeout,
                    maxProcessOutputBytes);
            return new Result(
                    result.exitCode(), result.timedOut(), result.processAlive(), result.safeSummary());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result(-1, false, false, "build interrupted");
        } catch (IOException | RuntimeException exception) {
            return new Result(-1, false, false, "build process failed safely");
        }
    }
}
