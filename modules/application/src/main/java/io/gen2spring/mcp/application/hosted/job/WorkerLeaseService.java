package io.gen2spring.mcp.application.hosted.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class WorkerLeaseService {
    private final JobQueue jobs;
    private final Clock clock;
    private final Duration leaseDuration;
    private final int maxAttempts;

    public WorkerLeaseService(JobQueue jobs, Clock clock, Duration leaseDuration, int maxAttempts) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.maxAttempts = maxAttempts;
        if (leaseDuration.isZero() || leaseDuration.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("Worker lease configuration is invalid");
        }
    }

    public Optional<JobLease> claim(WorkerId worker) {
        Objects.requireNonNull(worker, "worker");
        return jobs.claim(worker, clock.instant(), leaseDuration);
    }

    public boolean heartbeat(JobLease lease) {
        Objects.requireNonNull(lease, "lease");
        return jobs.heartbeat(lease, clock.instant().plus(leaseDuration));
    }

    public boolean complete(JobLease lease, JobCompletion completion) {
        return jobs.complete(
                Objects.requireNonNull(lease, "lease"),
                Objects.requireNonNull(completion, "completion"));
    }

    public int recoverExpired() {
        Instant now = clock.instant();
        return jobs.recoverExpired(now, maxAttempts);
    }
}
