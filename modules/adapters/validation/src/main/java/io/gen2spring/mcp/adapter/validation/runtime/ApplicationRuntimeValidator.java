package io.gen2spring.mcp.adapter.validation.runtime;

import io.gen2spring.mcp.adapter.validation.process.BoundedProcessRunner;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public final class ApplicationRuntimeValidator {
    private final Duration startupTimeout;
    private final Duration pollInterval;
    private final ServerEndpointDetector tomcatDetector;
    private final ServerEndpointDetector nettyDetector;

    public ApplicationRuntimeValidator(
            Duration startupTimeout,
            Duration pollInterval,
            LoopbackPortAllocator portAllocator) {
        this.startupTimeout = positive(startupTimeout, "startupTimeout");
        this.pollInterval = positive(pollInterval, "pollInterval");
        LoopbackPortAllocator allocator = Objects.requireNonNull(portAllocator, "portAllocator");
        this.tomcatDetector = new TomcatServerEndpointDetector(allocator);
        this.nettyDetector = new NettyServerEndpointDetector(allocator);
    }

    public ServerEndpointDetector requireEndpointDetector(CompatibilityProfile profile) {
        if (profile == null || profile.target() == null) {
            throw new IllegalArgumentException("Validation profile is unavailable");
        }
        return switch (profile.target().webStack()) {
            case "MVC" -> tomcatDetector;
            case "WEBFLUX" -> nettyDetector;
            default -> throw new IllegalArgumentException("Validation profile web stack is unsupported");
        };
    }

    public ReadinessResult awaitReadiness(
            BoundedProcessRunner.RunningProcess application,
            ServerEndpointDetector detector) throws InterruptedException {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(detector, "detector");
        long deadline = deadline(startupTimeout);
        while (System.nanoTime() < deadline) {
            if (!application.isAlive()) {
                return new ReadinessResult(Readiness.EXITED, null);
            }
            if (application.stdoutTruncated()) {
                return new ReadinessResult(Readiness.OUTPUT_LIMIT_REACHED, null);
            }
            URI endpoint = detector.detect(application.stdoutSnapshot()).orElse(null);
            if (endpoint == null) {
                sleepUntilNextPoll(deadline);
                continue;
            }
            int connectMillis = (int) Math.max(1, Math.min(250,
                    Duration.ofNanos(Math.max(1, deadline - System.nanoTime())).toMillis()));
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", endpoint.getPort()), connectMillis);
                return application.isAlive()
                        ? new ReadinessResult(Readiness.READY, endpoint)
                        : new ReadinessResult(Readiness.EXITED, null);
            } catch (IOException ignored) {
                sleepUntilNextPoll(deadline);
            }
        }
        return new ReadinessResult(application.isAlive() ? Readiness.TIMED_OUT : Readiness.EXITED, null);
    }

    public String integrityFailure(
            BoundedProcessRunner.RunningProcess application,
            URI expectedEndpoint,
            ServerEndpointDetector detector) throws IOException, InterruptedException {
        application.requireCollectorsHealthy();
        if (!application.isAlive()) {
            return "application exited after MCP validation";
        }
        if (application.stdoutTruncated()) {
            return "application output exceeded the discovery limit after MCP validation";
        }
        URI endpoint = detector.detect(application.stdoutSnapshot()).orElse(null);
        if (!expectedEndpoint.equals(endpoint)) {
            return "application endpoint changed during MCP validation";
        }
        return null;
    }

    private void sleepUntilNextPoll(long deadline) throws InterruptedException {
        long sleepMillis = Math.max(1, Math.min(
                pollInterval.toMillis(),
                Duration.ofNanos(Math.max(1, deadline - System.nanoTime())).toMillis()));
        Thread.sleep(sleepMillis);
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long deadline(Duration duration) {
        long now = System.nanoTime();
        long nanos = duration.toNanos();
        return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
    }

    public enum Readiness { READY, EXITED, TIMED_OUT, START_FAILED, OUTPUT_LIMIT_REACHED }

    public record ReadinessResult(Readiness status, URI endpoint) {}
}
