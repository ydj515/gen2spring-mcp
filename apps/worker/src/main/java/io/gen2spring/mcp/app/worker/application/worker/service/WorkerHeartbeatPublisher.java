package io.gen2spring.mcp.app.worker.application.worker.service;

import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.worker.port.out.WorkerHeartbeatStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class WorkerHeartbeatPublisher {
    private final WorkerHeartbeatStore store;
    private final WorkerId worker;
    private final Clock clock;
    private final Duration interval;
    private Instant lastPublished;

    public WorkerHeartbeatPublisher(WorkerHeartbeatStore store, WorkerId worker, Clock clock, Duration interval) {
        this.store = Objects.requireNonNull(store, "store");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative() || interval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Hosted worker configuration is invalid");
        }
    }

    public synchronized void publishIfDue() {
        Instant now = clock.instant();
        if (lastPublished != null && now.isBefore(lastPublished.plus(interval))) return;
        store.beat(worker, now);
        lastPublished = now;
    }
}
