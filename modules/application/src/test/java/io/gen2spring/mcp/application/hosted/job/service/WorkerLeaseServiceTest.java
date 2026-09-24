package io.gen2spring.mcp.application.hosted.job.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.job.CreateJob;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.hosted.job.JobCompletion;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.JobView;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.job.port.out.JobQueue;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkerLeaseServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void claimsHeartbeatsAndCompletesUsingTheCanonicalLeaseWindow() {
        LeaseQueue queue = new LeaseQueue();
        WorkerLeaseService service = new WorkerLeaseService(
                queue, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), 3);
        WorkerId worker = new WorkerId("worker-01");

        JobLease lease = service.claim(worker).orElseThrow();

        assertEquals(NOW.plusSeconds(30), lease.leaseUntil());
        assertTrue(service.heartbeat(lease));
        assertEquals(NOW.plusSeconds(30), queue.heartbeatUntil);
        assertTrue(service.complete(lease, JobCompletion.success()));
        assertEquals(JobStatus.SUCCEEDED, queue.completion.status());
    }

    @Test
    void preservesStaleLeaseAndRecoveryRejections() {
        LeaseQueue queue = new LeaseQueue();
        WorkerLeaseService service = new WorkerLeaseService(
                queue, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), 3);
        JobLease lease = service.claim(new WorkerId("worker-01")).orElseThrow();

        queue.acceptWrites = false;

        assertFalse(service.heartbeat(lease));
        assertFalse(service.complete(lease, JobCompletion.failure("SANDBOX_FAILED", "The sandbox failed")));
        assertEquals(2, service.recoverExpired());
        assertEquals(3, queue.recoveryMaxAttempts);
    }

    @Test
    void rejectsInvalidWorkerLeaseConfigurationAndNonTerminalCompletion() {
        LeaseQueue queue = new LeaseQueue();

        assertThrows(IllegalArgumentException.class,
                () -> new WorkerLeaseService(queue, Clock.systemUTC(), Duration.ZERO, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkerLeaseService(queue, Clock.systemUTC(), Duration.ofSeconds(30), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkerId("private marker with spaces"));
        assertThrows(IllegalArgumentException.class,
                () -> new JobCompletion(JobStatus.RUNNING, null, null));
    }

    private static final class LeaseQueue implements JobQueue {
        private final JobId jobId = JobId.parse("bf1dd77f-968c-4517-bb16-21860ac739d5");
        private boolean acceptWrites = true;
        private Instant heartbeatUntil;
        private JobCompletion completion;
        private int recoveryMaxAttempts;

        @Override
        public CreateJobResult create(CreateJob command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<JobView> find(AccountId owner, JobId jobId) {
            return Optional.empty();
        }

        @Override
        public boolean requestCancellation(AccountId owner, JobId jobId) {
            return false;
        }

        @Override
        public Optional<JobLease> claim(WorkerId worker, Instant now, Duration duration) {
            return Optional.of(new JobLease(
                    jobId,
                    worker,
                    11,
                    now.plus(duration),
                    JobKind.GENERATION,
                    "{\"request\":1}"));
        }

        @Override
        public boolean heartbeat(JobLease lease, Instant leaseUntil) {
            heartbeatUntil = leaseUntil;
            return acceptWrites;
        }

        @Override
        public boolean complete(JobLease lease, JobCompletion completion) {
            this.completion = completion;
            return acceptWrites;
        }

        @Override
        public int recoverExpired(Instant now, int maxAttempts) {
            recoveryMaxAttempts = maxAttempts;
            return 2;
        }
    }
}
