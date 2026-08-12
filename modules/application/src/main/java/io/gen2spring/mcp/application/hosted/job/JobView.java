package io.gen2spring.mcp.application.hosted.job;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.util.Objects;
import java.util.Optional;

public record JobView(
        JobId id,
        AccountId owner,
        JobKind kind,
        JobStatus status,
        Optional<SpecificationId> specificationId,
        int attempt,
        boolean cancellationRequested) {
    public JobView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        specificationId = Objects.requireNonNull(specificationId, "specificationId");
        if (attempt < 0) {
            throw new IllegalArgumentException("Hosted job view is invalid");
        }
    }
}
