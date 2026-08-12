package io.gen2spring.mcp.application.hosted.worker;

import java.time.Duration;
import java.util.Objects;

public record SandboxLimits(double cpus, long memoryBytes, int pids, Duration timeout) {
    public SandboxLimits {
        Objects.requireNonNull(timeout, "timeout");
        if (!Double.isFinite(cpus)
                || cpus <= 0
                || cpus > 2.0
                || memoryBytes < 64L * 1024 * 1024
                || memoryBytes > 4L * 1024 * 1024 * 1024
                || pids < 16
                || pids > 256
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Sandbox limits are invalid");
        }
    }
}
