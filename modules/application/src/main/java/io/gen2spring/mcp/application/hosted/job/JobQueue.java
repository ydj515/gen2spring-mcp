package io.gen2spring.mcp.application.hosted.job;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface JobQueue {
    CreateJobResult create(CreateJob command);

    Optional<JobView> find(AccountId owner, JobId jobId);

    boolean requestCancellation(AccountId owner, JobId jobId);

    Optional<JobLease> claim(WorkerId worker, Instant now, Duration duration);

    boolean heartbeat(JobLease lease, Instant leaseUntil);

    default boolean cancellationRequested(JobLease lease) {
        return false;
    }

    boolean complete(JobLease lease, JobCompletion completion);

    default boolean complete(JobLease lease, JobCompletion completion, List<JobArtifact> artifacts) {
        Objects.requireNonNull(artifacts, "artifacts");
        if (!artifacts.isEmpty()) {
            throw new UnsupportedOperationException("Hosted artifact publication is unavailable");
        }
        return complete(lease, completion);
    }

    int recoverExpired(Instant now, int maxAttempts);

    enum CreateRejection {
        IDEMPOTENCY_CONFLICT,
        CAPACITY_EXCEEDED
    }

    final class CreateRejected extends RuntimeException {
        private final CreateRejection rejection;

        public CreateRejected(CreateRejection rejection) {
            super("Hosted job creation was rejected");
            this.rejection = java.util.Objects.requireNonNull(rejection, "rejection");
        }

        public CreateRejection rejection() {
            return rejection;
        }
    }
}
