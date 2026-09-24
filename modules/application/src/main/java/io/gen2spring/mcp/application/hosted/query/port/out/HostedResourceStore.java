package io.gen2spring.mcp.application.hosted.query.port.out;

import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HostedResourceStore {
    List<SpecificationView> specifications(AccountId owner, int limit);

    List<SpecificationView> specifications(AccountId owner, int limit, Optional<ResourceCursor> cursor);

    Optional<SpecificationView> specification(AccountId owner, SpecificationId id);

    List<JobDetails> jobs(AccountId owner, int limit);

    List<JobDetails> jobs(AccountId owner, int limit, Optional<ResourceCursor> cursor);

    Optional<JobDetails> job(AccountId owner, JobId id);

    List<JobEvent> events(AccountId owner, JobId id, int limit);

    List<ArtifactView> artifacts(AccountId owner, JobId id);

    Optional<ArtifactView> artifact(AccountId owner, UUID artifactId);

    record SpecificationView(
            SpecificationId id,
            String sourceType,
            ObjectKey objectKey,
            String sha256,
            long byteSize,
            String label,
            Instant createdAt) {}

    record JobDetails(
            JobId id,
            JobKind kind,
            JobStatus status,
            Optional<SpecificationId> specificationId,
            int attempt,
            boolean cancellationRequested,
            String safeCode,
            String safeSummary,
            Instant createdAt,
            Instant updatedAt) {}

    record JobEvent(
            long sequence,
            JobStatus toStatus,
            String stage,
            String safeCode,
            String safeSummary,
            Instant createdAt) {}

    record ArtifactView(
            UUID id,
            JobId jobId,
            String type,
            ObjectKey objectKey,
            String sha256,
            long byteSize,
            String contentType,
            Instant createdAt,
            Instant expiresAt) {}

    record ResourceCursor(Instant createdAt, UUID id) {
        public ResourceCursor {
            if (createdAt == null || id == null) {
                throw new IllegalArgumentException("Hosted resource cursor is invalid");
            }
        }
    }
}
