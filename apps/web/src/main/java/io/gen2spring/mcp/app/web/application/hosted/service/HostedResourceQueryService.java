package io.gen2spring.mcp.app.web.application.hosted.service;

import io.gen2spring.mcp.app.web.application.hosted.exception.HostedResourceNotFound;
import io.gen2spring.mcp.application.hosted.query.port.out.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.query.port.out.HostedResourceStore.ResourceCursor;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class HostedResourceQueryService {
    private static final int DASHBOARD_LIMIT = 50;
    private static final int EVENT_LIMIT = 100;
    private final HostedResourceStore resources;

    public HostedResourceQueryService(HostedResourceStore resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public Dashboard dashboard(AccountId owner) {
        return new Dashboard(resources.specifications(owner, DASHBOARD_LIMIT).stream()
                .map(SpecificationSummary::from).toList(),
                resources.jobs(owner, DASHBOARD_LIMIT).stream().map(JobSummary::from).toList());
    }

    public JobSnapshot job(AccountId owner, JobId id) {
        var job = resources.job(owner, id).orElseThrow(HostedResourceNotFound::new);
        return new JobSnapshot(JobSummary.from(job),
                resources.events(owner, id, EVENT_LIMIT).stream().map(JobEventSummary::from).toList(),
                resources.artifacts(owner, id).stream().map(ArtifactSummary::from).toList());
    }

    public SpecificationPage specifications(AccountId owner, int limit, Optional<Cursor> cursor) {
        if (limit < 1 || limit > DASHBOARD_LIMIT) {
            throw new IllegalArgumentException("Hosted specification page limit is invalid");
        }
        var values = resources.specifications(owner, limit + 1,
                cursor.map(value -> new ResourceCursor(value.createdAt(), value.id())));
        List<SpecificationSummary> items = values.stream().limit(limit).map(SpecificationSummary::from).toList();
        Optional<Cursor> next = values.size() > limit
                ? Optional.of(new Cursor(items.getLast().createdAt(), items.getLast().id().value()))
                : Optional.empty();
        return new SpecificationPage(items, next);
    }

    public record Cursor(Instant createdAt, UUID id) {}

    public record SpecificationSummary(
            SpecificationId id, String sourceType, String label, long byteSize, Instant createdAt) {
        private static SpecificationSummary from(HostedResourceStore.SpecificationView value) {
            return new SpecificationSummary(value.id(), value.sourceType(), value.label(),
                    value.byteSize(), value.createdAt());
        }
    }

    public record JobSummary(
            JobId id, JobKind kind, JobStatus status, Optional<SpecificationId> specificationId,
            int attempt, boolean cancellationRequested, Instant createdAt, Instant updatedAt) {
        private static JobSummary from(HostedResourceStore.JobDetails value) {
            return new JobSummary(value.id(), value.kind(), value.status(), value.specificationId(),
                    value.attempt(), value.cancellationRequested(), value.createdAt(), value.updatedAt());
        }
    }

    public record JobEventSummary(
            long sequence, JobStatus toStatus, String stage, String safeCode, String safeSummary, Instant createdAt) {
        private static JobEventSummary from(HostedResourceStore.JobEvent value) {
            return new JobEventSummary(value.sequence(), value.toStatus(), value.stage(),
                    value.safeCode(), value.safeSummary(), value.createdAt());
        }
    }

    public record ArtifactSummary(UUID id, String type, long byteSize, String contentType, Instant expiresAt) {
        private static ArtifactSummary from(HostedResourceStore.ArtifactView value) {
            return new ArtifactSummary(value.id(), value.type(), value.byteSize(),
                    value.contentType(), value.expiresAt());
        }
    }

    public record Dashboard(List<SpecificationSummary> specifications, List<JobSummary> jobs) {
        public Dashboard {
            specifications = List.copyOf(specifications);
            jobs = List.copyOf(jobs);
        }
    }

    public record JobSnapshot(JobSummary job, List<JobEventSummary> events, List<ArtifactSummary> artifacts) {
        public JobSnapshot {
            Objects.requireNonNull(job, "job");
            events = List.copyOf(events);
            artifacts = List.copyOf(artifacts);
        }
    }

    public record SpecificationPage(List<SpecificationSummary> items, Optional<Cursor> nextCursor) {
        public SpecificationPage {
            items = List.copyOf(items);
            Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }
}
