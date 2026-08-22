package io.gen2spring.mcp.application.hosted.job;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record JobView(
        JobId id,
        AccountId owner,
        JobKind kind,
        JobStatus status,
        Optional<SpecificationId> specificationId,
        Optional<UUID> predecessorCatalogId,
        int attempt,
        boolean cancellationRequested) {
    public JobView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        specificationId = Objects.requireNonNull(specificationId, "specificationId");
        predecessorCatalogId = Objects.requireNonNull(predecessorCatalogId, "predecessorCatalogId");
        if (attempt < 0) {
            throw new IllegalArgumentException("Hosted job view is invalid");
        }
    }

    public JobView(
            JobId id,
            AccountId owner,
            JobKind kind,
            JobStatus status,
            Optional<SpecificationId> specificationId,
            int attempt,
            boolean cancellationRequested) {
        this(id, owner, kind, status, specificationId, Optional.empty(), attempt, cancellationRequested);
    }
}
