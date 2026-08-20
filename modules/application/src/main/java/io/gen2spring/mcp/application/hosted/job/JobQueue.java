package io.gen2spring.mcp.application.hosted.job;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogPublication;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
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
        return complete(lease, completion, artifacts, Optional.empty());
    }

    default boolean complete(
            JobLease lease,
            JobCompletion completion,
            List<JobArtifact> artifacts,
            Optional<ToolCatalogPublication> catalog) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(catalog, "catalog");
        boolean generationSuccess = lease.kind() == JobKind.GENERATION
                && completion.status() == JobStatus.SUCCEEDED;
        if (generationSuccess != catalog.isPresent()
                || completion.status() != JobStatus.SUCCEEDED && catalog.isPresent()
                || lease.kind() == JobKind.SPEC_IMPORT && catalog.isPresent()) {
            throw new IllegalArgumentException("Hosted Tool Catalog publication is invalid");
        }
        if (!artifacts.isEmpty() || catalog.isPresent()) {
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
