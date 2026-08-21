package io.gen2spring.mcp.application.managed.execution;

import java.time.Duration;

public record ManagedExecutionLimits(Duration timeout, int workers, int queueCapacity) {
    public ManagedExecutionLimits {
        if (timeout == null || timeout.compareTo(Duration.ofMillis(10)) < 0
                || timeout.compareTo(Duration.ofSeconds(60)) > 0
                || workers < 1 || workers > 64 || queueCapacity < 1 || queueCapacity > 10_000) {
            throw new IllegalArgumentException("Managed execution limits are invalid");
        }
    }
}
