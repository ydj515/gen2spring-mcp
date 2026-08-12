package io.gen2spring.mcp.application.hosted.job;

import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import java.time.Instant;
import java.util.Objects;

public record JobLease(
        JobId jobId,
        WorkerId worker,
        long fencingToken,
        Instant leaseUntil,
        JobKind kind,
        String requestSnapshot) {
    public JobLease {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requestSnapshot, "requestSnapshot");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("Hosted job lease is invalid");
        }
    }
}
