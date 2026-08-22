package io.gen2spring.mcp.application.hosted.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.specification.SpecificationCatalog;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HostedJobServiceTest {
    private static final AccountId OWNER = account("41dd3b69-589c-4466-a78e-d448407d17b9");
    private static final AccountId OTHER = account("5d0c27ac-1c18-487d-b922-28653572fc4a");
    private static final SpecificationId SPECIFICATION = specification("80782e7c-337d-4d4d-bd4d-ad478359563c");
    private static final UUID PREDECESSOR = UUID.fromString("2c7fab42-1acd-4f90-bd3f-f7de5ec81edb");
    private static final String HASH = "a".repeat(64);

    @Test
    void createsOwnerScopedGenerationAndImportJobsWithTheCanonicalQuota() {
        StubJobQueue jobs = new StubJobQueue();
        SpecificationCatalog specifications = (owner, id) -> OWNER.equals(owner) && SPECIFICATION.equals(id);
        HostedJobService service = new HostedJobService(jobs, specifications);

        CreateJobResult generation = service.submitGeneration(
                OWNER, SPECIFICATION, Optional.of(PREDECESSOR),
                "generation-key", HASH, "{\"profile\":\"java21\"}");

        assertEquals(JobKind.GENERATION, generation.job().kind());
        assertEquals(Optional.of(SPECIFICATION), generation.job().specificationId());
        assertEquals(Optional.of(PREDECESSOR), generation.job().predecessorCatalogId());
        assertEquals(Optional.of(PREDECESSOR), jobs.lastCommand.predecessorCatalogId());

        CreateJobResult imported = service.submitImport(
                OWNER, "import-key", "b".repeat(64), "{\"url\":\"encrypted\"}");
        assertEquals(JobKind.SPEC_IMPORT, imported.job().kind());
        assertEquals(Optional.empty(), imported.job().specificationId());
        assertEquals(new JobQuota(2, 10), jobs.lastCommand.quota());
    }

    @Test
    void returnsTheExistingJobWhenTheTransactionalQueueReportsAnIdempotentReplay() {
        StubJobQueue jobs = new StubJobQueue();
        jobs.replay = true;
        HostedJobService service = new HostedJobService(jobs, (owner, id) -> true);

        CreateJobResult result = service.submitGeneration(
                OWNER, SPECIFICATION, "same-key", HASH, "{\"request\":1}");

        assertTrue(result.replayed());
        assertEquals(jobs.jobId, result.job().id());
    }

    @Test
    void mapsAtomicIdempotencyAndCapacityRejectionsToFixedApplicationFailures() {
        StubJobQueue jobs = new StubJobQueue();
        HostedJobService service = new HostedJobService(jobs, (owner, id) -> true);

        jobs.rejection = JobQueue.CreateRejection.IDEMPOTENCY_CONFLICT;
        assertFailure(HostedJobFailure.Code.IDEMPOTENCY_CONFLICT,
                "The idempotency key is already used for another request",
                () -> service.submitGeneration(OWNER, SPECIFICATION, "same-key", HASH, "{}"));

        jobs.rejection = JobQueue.CreateRejection.CAPACITY_EXCEEDED;
        assertFailure(HostedJobFailure.Code.CAPACITY_EXCEEDED,
                "The hosted job capacity is exhausted",
                () -> service.submitGeneration(OWNER, SPECIFICATION, "next-key", HASH, "{}"));

        jobs.rejection = JobQueue.CreateRejection.CATALOG_NOT_FOUND;
        assertFailure(HostedJobFailure.Code.NOT_FOUND,
                "The hosted resource was not found",
                () -> service.submitGeneration(
                        OWNER, SPECIFICATION, Optional.of(PREDECESSOR), "lineage-key", HASH, "{}"));
    }

    @Test
    void rejectsCrossOwnerSpecificationsAndJobAccessWithoutCallingTheQueue() {
        StubJobQueue jobs = new StubJobQueue();
        HostedJobService service = new HostedJobService(jobs, (owner, id) -> OWNER.equals(owner));

        assertFailure(HostedJobFailure.Code.NOT_FOUND, "The hosted resource was not found",
                () -> service.submitGeneration(OTHER, SPECIFICATION, "private-key", HASH, "{}"));
        assertEquals(0, jobs.createCount);

        assertFailure(HostedJobFailure.Code.NOT_FOUND, "The hosted resource was not found",
                () -> service.require(OTHER, jobs.jobId));
        assertFalse(service.cancel(OTHER, jobs.jobId));
    }

    @Test
    void rejectsMalformedRequestsWithoutEchoingValues() {
        HostedJobService service = new HostedJobService(new StubJobQueue(), (owner, id) -> true);

        assertFailure(HostedJobFailure.Code.INVALID_REQUEST, "The hosted job request is invalid",
                () -> service.submitImport(OWNER, "private marker with spaces", HASH, "{}"));
        assertFailure(HostedJobFailure.Code.INVALID_REQUEST, "The hosted job request is invalid",
                () -> service.submitImport(OWNER, "valid-key", "hash-private-marker", "{}"));
        assertFailure(HostedJobFailure.Code.INVALID_REQUEST, "The hosted job request is invalid",
                () -> service.submitImport(OWNER, "valid-key", HASH, "x".repeat(1_048_577)));
    }

    @Test
    void rejectsJobCommandsWhoseKindAndSpecificationDoNotMatch() {
        assertThrows(IllegalArgumentException.class, () -> new CreateJob(
                OWNER,
                JobKind.GENERATION,
                "generation",
                "valid-key",
                HASH,
                "{}",
                Optional.empty(),
                new JobQuota(2, 10)));
        assertThrows(IllegalArgumentException.class, () -> new CreateJob(
                OWNER,
                JobKind.SPEC_IMPORT,
                "specification-import",
                "valid-key",
                HASH,
                "{}",
                Optional.of(SPECIFICATION),
                Optional.of(PREDECESSOR),
                new JobQuota(2, 10)));
    }

    private static void assertFailure(HostedJobFailure.Code code, String message, Runnable action) {
        HostedJobFailure failure = assertThrows(HostedJobFailure.class, action::run);
        assertEquals(code, failure.code());
        assertEquals(message, failure.getMessage());
    }

    private static AccountId account(String value) {
        return AccountId.parse(value);
    }

    private static SpecificationId specification(String value) {
        return SpecificationId.parse(value);
    }

    private static final class StubJobQueue implements JobQueue {
        private final JobId jobId = JobId.parse("1a803410-a22a-4bc6-b951-7dbc301ae800");
        private CreateJob lastCommand;
        private CreateRejection rejection;
        private boolean replay;
        private int createCount;

        @Override
        public CreateJobResult create(CreateJob command) {
            createCount++;
            lastCommand = command;
            if (rejection != null) {
                throw new CreateRejected(rejection);
            }
            return new CreateJobResult(new JobView(
                    jobId,
                    command.owner(),
                    command.kind(),
                    JobStatus.QUEUED,
                    command.specificationId(),
                    command.predecessorCatalogId(),
                    0,
                    false), replay);
        }

        @Override
        public Optional<JobView> find(AccountId owner, JobId jobId) {
            return OWNER.equals(owner) && this.jobId.equals(jobId)
                    ? Optional.of(new JobView(
                            jobId, owner, JobKind.GENERATION, JobStatus.QUEUED,
                            Optional.of(SPECIFICATION), 0, false))
                    : Optional.empty();
        }

        @Override
        public boolean requestCancellation(AccountId owner, JobId jobId) {
            return OWNER.equals(owner) && this.jobId.equals(jobId);
        }

        @Override
        public Optional<JobLease> claim(WorkerId worker, java.time.Instant now, java.time.Duration duration) {
            return Optional.empty();
        }

        @Override
        public boolean heartbeat(JobLease lease, java.time.Instant leaseUntil) {
            return false;
        }

        @Override
        public boolean complete(JobLease lease, JobCompletion completion) {
            return false;
        }

        @Override
        public int recoverExpired(java.time.Instant now, int maxAttempts) {
            return 0;
        }
    }
}
