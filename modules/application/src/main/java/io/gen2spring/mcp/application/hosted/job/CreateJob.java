package io.gen2spring.mcp.application.hosted.job;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CreateJob(
        AccountId owner,
        JobKind kind,
        String operation,
        String idempotencyKey,
        String requestHash,
        String requestSnapshot,
        Optional<SpecificationId> specificationId,
        Optional<UUID> predecessorCatalogId,
        JobQuota quota) {
    public CreateJob {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(requestSnapshot, "requestSnapshot");
        specificationId = Objects.requireNonNull(specificationId, "specificationId");
        predecessorCatalogId = Objects.requireNonNull(predecessorCatalogId, "predecessorCatalogId");
        Objects.requireNonNull(quota, "quota");
        if ((kind == JobKind.GENERATION && specificationId.isEmpty())
                || (kind == JobKind.SPEC_IMPORT
                        && (specificationId.isPresent() || predecessorCatalogId.isPresent()))) {
            throw new IllegalArgumentException("Hosted job request is invalid");
        }
    }

    public CreateJob(
            AccountId owner,
            JobKind kind,
            String operation,
            String idempotencyKey,
            String requestHash,
            String requestSnapshot,
            Optional<SpecificationId> specificationId,
            JobQuota quota) {
        this(owner, kind, operation, idempotencyKey, requestHash, requestSnapshot,
                specificationId, Optional.empty(), quota);
    }
}
