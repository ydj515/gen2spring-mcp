package io.gen2spring.mcp.app.worker.infrastructure.scheduling;

import io.gen2spring.mcp.application.hosted.worker.port.out.LeaseMonitorScheduler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ExecutorLeaseMonitorScheduler implements LeaseMonitorScheduler {
    @Override
    public Registration schedule(Duration interval, Runnable check) {
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(check, "check");
        long nanos = interval.toNanos();
        if (nanos < 1) throw new IllegalArgumentException("Lease monitor interval must be positive");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("hosted-lease-monitor-", 0).factory());
        try {
            executor.scheduleAtFixedRate(check, nanos, nanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException | Error failure) {
            executor.shutdownNow();
            throw failure;
        }
        return () -> {
            executor.shutdownNow();
            try {
                return executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
    }
}
