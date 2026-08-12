package io.gen2spring.mcp.app.web.hosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.application.hosted.imports.ImportTargetProtector;
import io.gen2spring.mcp.application.hosted.job.CreateJob;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.hosted.job.JobCompletion;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.JobQueue;
import io.gen2spring.mcp.application.hosted.job.JobView;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.job.HostedJobService;
import io.gen2spring.mcp.application.hosted.query.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.specification.SpecificationCatalog;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostedSubmissionServiceTest {
    @Test
    void rejectsInvalidImportTargetsWithOneFixedNonLeakingFailure(@TempDir java.nio.file.Path workRoot) {
        ImportTargetProtector protector = mock(ImportTargetProtector.class);
        HostedSubmissionService service = new HostedSubmissionService(
                mock(GeneratorRuntime.class), mock(ObjectStorage.class), mock(SpecificationCatalog.class),
                mock(HostedResourceStore.class), mock(HostedJobService.class), protector, workRoot, Clock.systemUTC());

        var failure = assertThrows(
                HostedSubmissionService.HostedSubmissionFailure.class,
                () -> service.importUrl(
                        new AccountId(UUID.randomUUID()), "request-1", "https://private-marker.example:8443/openapi.yaml"));

        assertEquals("Hosted submission failed", failure.getMessage());
        verifyNoInteractions(protector);
    }

    @Test
    void hashesTheCanonicalUrlInsteadOfRandomizedCiphertext(@TempDir java.nio.file.Path workRoot) {
        ImportTargetProtector protector = mock(ImportTargetProtector.class);
        when(protector.protect(org.mockito.ArgumentMatchers.any())).thenReturn(encrypted((byte) 1), encrypted((byte) 2));
        CapturingQueue queue = new CapturingQueue();
        HostedJobService jobs = new HostedJobService(queue, (owner, specification) -> false);
        HostedSubmissionService service = new HostedSubmissionService(
                mock(GeneratorRuntime.class), mock(ObjectStorage.class), mock(SpecificationCatalog.class),
                mock(HostedResourceStore.class), jobs, protector, workRoot, Clock.systemUTC());
        AccountId owner = new AccountId(UUID.randomUUID());

        service.importUrl(owner, "request-1", "https://public.example/openapi.yaml");
        service.importUrl(owner, "request-2", "https://public.example/openapi.yaml");

        assertEquals(queue.commands.get(0).requestHash(), queue.commands.get(1).requestHash());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                queue.commands.get(0).requestSnapshot(), queue.commands.get(1).requestSnapshot());
    }

    private EncryptedImportTarget encrypted(byte value) {
        return new EncryptedImportTarget(
                1, "key-1", encoded(12, value), encoded(48, value), encoded(12, (byte) (value + 1)),
                encoded(32, (byte) (value + 2)));
    }

    private String encoded(int length, byte value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class CapturingQueue implements JobQueue {
        private final ArrayList<CreateJob> commands = new ArrayList<>();

        @Override
        public CreateJobResult create(CreateJob command) {
            commands.add(command);
            return new CreateJobResult(new JobView(
                    new io.gen2spring.mcp.domain.platform.job.JobId(UUID.randomUUID()), command.owner(), command.kind(),
                    io.gen2spring.mcp.domain.platform.job.JobStatus.QUEUED, command.specificationId(), 0, false), false);
        }

        @Override public Optional<JobView> find(AccountId owner, io.gen2spring.mcp.domain.platform.job.JobId jobId) { return Optional.empty(); }
        @Override public boolean requestCancellation(AccountId owner, io.gen2spring.mcp.domain.platform.job.JobId jobId) { return false; }
        @Override public Optional<JobLease> claim(WorkerId worker, Instant now, Duration duration) { return Optional.empty(); }
        @Override public boolean heartbeat(JobLease lease, Instant leaseUntil) { return false; }
        @Override public boolean complete(JobLease lease, JobCompletion completion) { return false; }
        @Override public int recoverExpired(Instant now, int maxAttempts) { return 0; }
    }
}
