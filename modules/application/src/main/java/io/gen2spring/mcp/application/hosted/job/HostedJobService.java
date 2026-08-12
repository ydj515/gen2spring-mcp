package io.gen2spring.mcp.application.hosted.job;

import io.gen2spring.mcp.application.hosted.specification.SpecificationCatalog;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class HostedJobService {
    private static final JobQuota DEFAULT_QUOTA = new JobQuota(2, 10);
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    private final JobQueue jobs;
    private final SpecificationCatalog specifications;

    public HostedJobService(JobQueue jobs, SpecificationCatalog specifications) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.specifications = Objects.requireNonNull(specifications, "specifications");
    }

    public CreateJobResult submitGeneration(
            AccountId owner,
            SpecificationId specificationId,
            String idempotencyKey,
            String requestHash,
            String requestSnapshot) {
        requireRequest(owner, idempotencyKey, requestHash, requestSnapshot);
        if (specificationId == null || !specifications.belongsTo(owner, specificationId)) {
            throw failure(HostedJobFailure.Code.NOT_FOUND, "The hosted resource was not found");
        }
        return create(new CreateJob(
                owner,
                JobKind.GENERATION,
                "generation",
                idempotencyKey,
                requestHash,
                requestSnapshot,
                Optional.of(specificationId),
                DEFAULT_QUOTA));
    }

    public CreateJobResult submitImport(
            AccountId owner,
            String idempotencyKey,
            String requestHash,
            String requestSnapshot) {
        requireRequest(owner, idempotencyKey, requestHash, requestSnapshot);
        return create(new CreateJob(
                owner,
                JobKind.SPEC_IMPORT,
                "specification-import",
                idempotencyKey,
                requestHash,
                requestSnapshot,
                Optional.empty(),
                DEFAULT_QUOTA));
    }

    public JobView require(AccountId owner, JobId jobId) {
        if (owner == null || jobId == null) {
            throw failure(HostedJobFailure.Code.NOT_FOUND, "The hosted resource was not found");
        }
        return jobs.find(owner, jobId)
                .orElseThrow(() -> failure(HostedJobFailure.Code.NOT_FOUND, "The hosted resource was not found"));
    }

    public boolean cancel(AccountId owner, JobId jobId) {
        return owner != null && jobId != null && jobs.requestCancellation(owner, jobId);
    }

    private CreateJobResult create(CreateJob command) {
        try {
            return jobs.create(command);
        } catch (JobQueue.CreateRejected rejection) {
            if (rejection.rejection() == JobQueue.CreateRejection.IDEMPOTENCY_CONFLICT) {
                throw failure(
                        HostedJobFailure.Code.IDEMPOTENCY_CONFLICT,
                        "The idempotency key is already used for another request");
            }
            throw failure(HostedJobFailure.Code.CAPACITY_EXCEEDED, "The hosted job capacity is exhausted");
        }
    }

    private void requireRequest(
            AccountId owner,
            String idempotencyKey,
            String requestHash,
            String requestSnapshot) {
        if (owner == null
                || idempotencyKey == null
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()
                || requestHash == null
                || !SHA_256.matcher(requestHash).matches()
                || requestSnapshot == null
                || requestSnapshot.isBlank()
                || requestSnapshot.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            throw failure(HostedJobFailure.Code.INVALID_REQUEST, "The hosted job request is invalid");
        }
    }

    private HostedJobFailure failure(HostedJobFailure.Code code, String message) {
        return new HostedJobFailure(code, message);
    }
}
