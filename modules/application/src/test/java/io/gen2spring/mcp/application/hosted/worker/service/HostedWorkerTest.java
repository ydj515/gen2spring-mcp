package io.gen2spring.mcp.application.hosted.worker.service;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogPublication;
import io.gen2spring.mcp.application.hosted.job.CreateJob;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.hosted.job.JobArtifact;
import io.gen2spring.mcp.application.hosted.job.JobCompletion;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.JobView;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.job.port.out.JobQueue;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import io.gen2spring.mcp.application.hosted.worker.SandboxArtifact;
import io.gen2spring.mcp.application.hosted.worker.SandboxInput;
import io.gen2spring.mcp.application.hosted.worker.SandboxLimits;
import io.gen2spring.mcp.application.hosted.worker.SandboxResult;
import io.gen2spring.mcp.application.hosted.worker.port.out.ImportRuntime;
import io.gen2spring.mcp.application.hosted.worker.port.out.LeaseMonitorScheduler;
import io.gen2spring.mcp.application.hosted.worker.port.out.SandboxRuntime;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostedWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
    private static final JobId JOB = JobId.parse("1a803410-a22a-4bc6-b951-7dbc301ae800");
    private static final WorkerId WORKER = new WorkerId("worker-01");
    private static final SandboxLimits LIMITS = new SandboxLimits(
            2.0, 4L * 1024 * 1024 * 1024, 256, Duration.ofMinutes(10));

    @Test
    void schedulesLeaseChecksBeforeShortAndFractionalLeasesExpire() throws Exception {
        for (Duration lease : List.of(Duration.ofMillis(900), Duration.ofMillis(1500), Duration.ofNanos(1))) {
            ManualScheduler scheduler = new ManualScheduler();
            StubSandbox sandbox = new StubSandbox();
            sandbox.result = generationResult();
            var worker = new HostedWorker(generationQueue(), sandbox,
                    (claimed, target, limits) -> { throw new AssertionError(); }, new StubStorage(),
                    WORKER, LIMITS, Clock.fixed(NOW, ZoneOffset.UTC), lease, scheduler);

            worker.pollOnce();

            assertEquals(Duration.ofNanos(Math.max(1, lease.toNanos() / 3)), scheduler.interval);
            assertTrue(scheduler.stopped);
        }
    }

    @Test
    void doesNothingWhenNoLeaseIsAvailable() throws Exception {
        StubQueue queue = new StubQueue();
        StubSandbox sandbox = new StubSandbox();
        HostedWorker worker = worker(
                queue, sandbox, (lease, target, limits) -> { throw new AssertionError(); }, new StubStorage());

        assertEquals(HostedWorker.PollResult.EMPTY, worker.pollOnce());
        assertEquals(0, sandbox.calls);
        assertEquals(0, queue.completions);
    }

    @Test
    void publishesGenerationArtifactsAndCompletesExactlyOnce() throws Exception {
        StubQueue queue = generationQueue();
        StubSandbox sandbox = new StubSandbox();
        sandbox.result = generationResult();
        StubStorage storage = new StubStorage();

        HostedWorker.PollResult result = worker(queue, sandbox, (lease, target, limits) -> {
            throw new AssertionError();
        }, storage).pollOnce();

        assertEquals(HostedWorker.PollResult.COMPLETED, result);
        assertEquals(1, sandbox.calls);
        assertEquals("spring-ai-2.0-java21-mvc-streamable", sandbox.input.targetProfileId());
        assertEquals(
                "{\"project\":{\"groupId\":\"com.example\",\"artifactId\":\"weather\",\"packageName\":\"com.example.weather\"},"
                        + "\"provider\":\"kma\",\"domain\":\"weather\","
                        + "\"targetProfileId\":\"spring-ai-2.0-java21-mvc-streamable\","
                        + "\"validationLevel\":\"COMPILE\",\"operations\":[]}",
                sandbox.input.generationConfiguration());
        assertEquals(List.of("ARCHIVE", "MANIFEST", "VALIDATION_REPORT"),
                queue.artifacts.stream().map(JobArtifact::type).toList());
        assertEquals(3, storage.keys.size());
        assertTrue(queue.catalog.isPresent());
        assertEquals("weather", queue.catalog.orElseThrow().entries().getFirst().tool().name());
        assertEquals(JobStatus.SUCCEEDED, queue.completion.status());
        assertEquals(1, queue.completions);
        assertTrue(queue.heartbeats.get() >= 1);
    }

    @Test
    void acceptsTheStoredPredecessorWithoutUsingItAsPublicationAuthority() throws Exception {
        StubQueue queue = generationQueue();
        queue.lease = new JobLease(
                JOB, WORKER, 11, NOW.plusSeconds(30), JobKind.GENERATION,
                queue.lease.requestSnapshot().replace(
                        "\"configuration\":",
                        "\"predecessorCatalogId\":\"2c7fab42-1acd-4f90-bd3f-f7de5ec81edb\",\"configuration\":"));
        StubSandbox sandbox = new StubSandbox();
        sandbox.result = generationResult();

        assertEquals(HostedWorker.PollResult.COMPLETED,
                worker(queue, sandbox, (lease, target, limits) -> { throw new AssertionError(); }, new StubStorage())
                        .pollOnce());
        assertEquals(JobStatus.SUCCEEDED, queue.completion.status());
    }

    @Test
    void dispatchesEncryptedImportsWithoutExposingThePayload() throws Exception {
        StubQueue queue = importQueue();
        AtomicInteger imports = new AtomicInteger();
        ImportRuntime importsRuntime = (lease, target, limits) -> {
            imports.incrementAndGet();
            assertEquals("key-1", target.keyId());
            return new SandboxResult(List.of(artifact("source", "yaml")), "SUCCESS");
        };
        StubStorage storage = new StubStorage();

        assertEquals(HostedWorker.PollResult.COMPLETED,
                worker(queue, new StubSandbox(), importsRuntime, storage).pollOnce());
        assertEquals(1, imports.get());
        assertEquals(ObjectKey.parse("specifications/1a803410-a22a-4bc6-b951-7dbc301ae800/11-source"),
                storage.keys.getFirst());
        assertTrue(queue.catalog.isEmpty());
        assertFalse(queue.lease.toString().contains("wrappedKey"));
        assertFalse(queue.lease.toString().contains("ciphertext"));
    }

    @Test
    void rejectsGenerationSuccessWithoutMetadataBeforeUploadingArtifacts() throws Exception {
        StubQueue queue = generationQueue();
        StubSandbox sandbox = new StubSandbox();
        sandbox.result = new SandboxResult(List.of(
                artifact("archive", "zip"),
                artifact("manifest", "json"),
                artifact("validation-report", "json")), "SUCCESS");
        StubStorage storage = new StubStorage();

        assertEquals(HostedWorker.PollResult.FAILED,
                worker(queue, sandbox, (lease, target, limits) -> { throw new AssertionError(); }, storage)
                        .pollOnce());

        assertTrue(storage.keys.isEmpty());
        assertTrue(queue.artifacts.isEmpty());
        assertTrue(queue.catalog.isEmpty());
        assertEquals("CATALOG_PUBLICATION_FAILED", queue.completion.safeCode());
    }

    @Test
    void deletesEveryUploadedObjectWhenCatalogCompletionFails() throws Exception {
        StubQueue queue = generationQueue();
        queue.failCompletion = true;
        StubSandbox sandbox = new StubSandbox();
        sandbox.result = generationResult();
        StubStorage storage = new StubStorage();

        assertEquals(HostedWorker.PollResult.STALE,
                worker(queue, sandbox, (lease, target, limits) -> { throw new AssertionError(); }, storage)
                        .pollOnce());

        assertEquals(storage.keys, storage.deleted);
        assertTrue(queue.catalog.isEmpty());
    }

    @Test
    void convertsCatalogLineageConflictsToSafeFailedCompletion() throws Exception {
        StubQueue queue = generationQueue();
        queue.lineageConflict = true;
        StubSandbox sandbox = new StubSandbox();
        sandbox.result = generationResult();
        StubStorage storage = new StubStorage();

        assertEquals(HostedWorker.PollResult.FAILED,
                worker(queue, sandbox, (lease, target, limits) -> { throw new AssertionError(); }, storage)
                        .pollOnce());

        assertEquals(storage.keys, storage.deleted);
        assertEquals("CATALOG_LINEAGE_CONFLICT", queue.completion.safeCode());
        assertTrue(queue.artifacts.isEmpty());
        assertTrue(queue.catalog.isEmpty());
        assertEquals(2, queue.completions);
    }

    @Test
    void removesPublishedObjectsWhenTheLeaseIsStale() throws Exception {
        StubQueue queue = generationQueue();
        queue.acceptCompletion = false;
        StubSandbox sandbox = new StubSandbox();
        sandbox.result = generationResult();
        StubStorage storage = new StubStorage();

        assertEquals(HostedWorker.PollResult.STALE,
                worker(queue, sandbox, (lease, target, limits) -> { throw new AssertionError(); }, storage).pollOnce());

        assertEquals(3, storage.deleted.size());
        assertEquals(1, queue.completions);
        assertTrue(queue.catalog.isEmpty());
    }

    @Test
    void mapsRuntimeAndUploadFailuresToFixedCompletionAndCleansPartialObjects() throws Exception {
        StubQueue runtimeQueue = generationQueue();
        StubSandbox failing = new StubSandbox();
        failing.failure = new IllegalStateException("private process output");
        assertEquals(HostedWorker.PollResult.FAILED,
                worker(runtimeQueue, failing, (lease, target, limits) -> { throw new AssertionError(); },
                        new StubStorage()).pollOnce());
        assertEquals("SANDBOX_FAILED", runtimeQueue.completion.safeCode());

        StubQueue storageQueue = generationQueue();
        StubSandbox succeeds = new StubSandbox();
        succeeds.result = new SandboxResult(
                List.of(artifact("archive", "zip"), artifact("report", "json")),
                Optional.of(runtimeMetadata()),
                "SUCCESS");
        StubStorage storage = new StubStorage();
        storage.failAt = 2;
        assertEquals(HostedWorker.PollResult.FAILED,
                worker(storageQueue, succeeds, (lease, target, limits) -> { throw new AssertionError(); }, storage)
                        .pollOnce());
        assertEquals(List.of(ObjectKey.parse(
                "artifacts/1a803410-a22a-4bc6-b951-7dbc301ae800/11-archive")), storage.deleted);
        assertEquals("ARTIFACT_PUBLICATION_FAILED", storageQueue.completion.safeCode());
    }

    @Test
    void preservesFatalErrorAndInterruptionAfterCleanup() {
        StubQueue fatalQueue = generationQueue();
        StubSandbox fatal = new StubSandbox();
        AssertionError error = new AssertionError("private fatal marker");
        fatal.failure = error;
        assertEquals(error, assertThrows(AssertionError.class,
                () -> worker(fatalQueue, fatal, (lease, target, limits) -> { throw new AssertionError(); },
                                new StubStorage())
                        .pollOnce()));
        assertEquals(0, fatalQueue.completions);

        StubQueue interruptedQueue = generationQueue();
        StubSandbox interrupted = new StubSandbox();
        interrupted.interrupted = true;
        assertThrows(InterruptedException.class,
                () -> worker(interruptedQueue, interrupted,
                                (lease, target, limits) -> { throw new AssertionError(); }, new StubStorage())
                        .pollOnce());
        assertTrue(Thread.interrupted());
        assertEquals(0, interruptedQueue.completions);
    }

    @Test
    void cancelsBeforeExecutionAndNeverPublishes() throws Exception {
        StubQueue queue = generationQueue();
        queue.cancelled = true;
        StubSandbox sandbox = new StubSandbox();

        assertEquals(HostedWorker.PollResult.CANCELLED,
                worker(queue, sandbox, (lease, target, limits) -> { throw new AssertionError(); }, new StubStorage())
                        .pollOnce());
        assertEquals(0, sandbox.calls);
        assertEquals(JobStatus.CANCELLED, queue.completion.status());
    }

    @Test
    void checksLeaseDuringExecutionAndStopsTheMonitorAfterCompletion() throws Exception {
        StubQueue queue = generationQueue();
        ManualScheduler scheduler = new ManualScheduler();
        SandboxRuntime sandbox = (lease, input, limits) -> {
            scheduler.check.run();
            return generationResult();
        };

        assertEquals(HostedWorker.PollResult.COMPLETED,
                worker(queue, sandbox, (lease, target, limits) -> { throw new AssertionError(); },
                        new StubStorage(), scheduler).pollOnce());

        assertEquals(Duration.ofSeconds(10), scheduler.interval);
        assertTrue(queue.heartbeats.get() >= 2);
        assertTrue(scheduler.stopped);
    }

    @Test
    void staleLeaseCancelsExecutionAndPreventsPublication() throws Exception {
        StubQueue queue = generationQueue();
        ManualScheduler scheduler = new ManualScheduler();
        StubStorage storage = new StubStorage();
        AtomicInteger cancellations = new AtomicInteger();
        SandboxRuntime sandbox = new SandboxRuntime() {
            @Override
            public SandboxResult run(JobLease lease, SandboxInput input, SandboxLimits limits) {
                queue.acceptHeartbeat = false;
                scheduler.check.run();
                return generationResult();
            }

            @Override
            public void cancel(JobLease lease) { cancellations.incrementAndGet(); }
        };

        assertEquals(HostedWorker.PollResult.STALE,
                worker(queue, sandbox, (lease, target, limits) -> { throw new AssertionError(); },
                        storage, scheduler).pollOnce());

        assertEquals(1, cancellations.get());
        assertEquals(0, queue.completions);
        assertTrue(storage.keys.isEmpty());
        assertTrue(scheduler.stopped);
    }

    @Test
    void cancellationDuringExecutionStopsTheMonitorAndReturnsCancellation() throws Exception {
        StubQueue queue = generationQueue();
        ManualScheduler scheduler = new ManualScheduler();
        SandboxRuntime sandbox = (lease, input, limits) -> {
            queue.cancelled = true;
            scheduler.check.run();
            throw new IllegalStateException("Sandbox cancelled");
        };

        assertEquals(HostedWorker.PollResult.CANCELLED,
                worker(queue, sandbox, (lease, target, limits) -> { throw new AssertionError(); },
                        new StubStorage(), scheduler).pollOnce());
        assertEquals(JobStatus.CANCELLED, queue.completion.status());
        assertTrue(scheduler.stopped);
    }

    private HostedWorker worker(
            StubQueue queue,
            SandboxRuntime sandbox,
            ImportRuntime imports,
            ObjectStorage storage) {
        return worker(queue, sandbox, imports, storage, new ManualScheduler());
    }

    private HostedWorker worker(
            StubQueue queue,
            SandboxRuntime sandbox,
            ImportRuntime imports,
            ObjectStorage storage,
            LeaseMonitorScheduler scheduler) {
        return new HostedWorker(
                queue,
                sandbox,
                imports,
                storage,
                WORKER,
                LIMITS,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30),
                scheduler);
    }

    private static final class ManualScheduler implements LeaseMonitorScheduler {
        private Duration interval;
        private Runnable check;
        private boolean stopped;

        @Override
        public Registration schedule(Duration interval, Runnable check) {
            this.interval = interval;
            this.check = check;
            return () -> {
                stopped = true;
                return true;
            };
        }
    }

    private StubQueue generationQueue() {
        StubQueue queue = new StubQueue();
        queue.lease = new JobLease(
                JOB, WORKER, 11, NOW.plusSeconds(30), JobKind.GENERATION,
                "{\"specificationObjectKey\":\"specifications/80782e7c-337d-4d4d-bd4d-ad478359563c/source\","
                        + "\"configuration\":{"
                        + "\"project\":{\"groupId\":\"com.example\",\"artifactId\":\"weather\","
                        + "\"packageName\":\"com.example.weather\"},"
                        + "\"provider\":\"kma\",\"domain\":\"weather\","
                        + "\"targetProfileId\":\"spring-ai-2.0-java21-mvc-streamable\","
                        + "\"validationLevel\":\"COMPILE\",\"operations\":[]}}");
        return queue;
    }

    private StubQueue importQueue() {
        StubQueue queue = new StubQueue();
        queue.lease = new JobLease(
                JOB, WORKER, 11, NOW.plusSeconds(30), JobKind.SPEC_IMPORT,
                "{\"version\":1,\"keyId\":\"key-1\","
                        + "\"wrappedKeyNonce\":\"AAAAAAAAAAAAAAAA\","
                        + "\"wrappedKey\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\","
                        + "\"targetNonce\":\"AAAAAAAAAAAAAAAA\","
                        + "\"ciphertext\":\"AAAAAAAAAAAAAAAAAAAAAAA\"}");
        return queue;
    }

    private SandboxArtifact artifact(String name, String type) {
        byte[] body = name.getBytes();
        return SandboxArtifact.of(
                name,
                new java.io.ByteArrayInputStream(body),
                body.length,
                sha256(body),
                type.equals("zip") ? "application/zip"
                        : type.equals("json") ? "application/json" : "application/yaml");
    }

    private SandboxResult generationResult() {
        return new SandboxResult(
                List.of(
                        artifact("archive", "zip"),
                        artifact("manifest", "json"),
                        artifact("validation-report", "json")),
                Optional.of(runtimeMetadata()),
                "SUCCESS");
    }

    private RuntimeMetadataArtifact runtimeMetadata() {
        RuntimeTool tool = new RuntimeTool(
                "getWeather",
                "weather",
                "Get weather",
                java.util.Map.of(
                        "type", "object", "properties", java.util.Map.of(), "required", List.of()),
                "GENERIC_JSON",
                java.util.Map.of(),
                new RuntimeHttp(GET, "https://api.example.test", "/weather", List.of(), false, false),
                null,
                null,
                null,
                List.of());
        return new CanonicalRuntimeMetadataCodec().encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "a".repeat(64), List.of(tool)));
    }

    private String sha256(byte[] body) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class StubSandbox implements SandboxRuntime {
        private int calls;
        private SandboxResult result = new SandboxResult(List.of(), "FAILED");
        private Throwable failure;
        private boolean interrupted;
        private SandboxInput input;

        @Override
        public SandboxResult run(JobLease lease, SandboxInput input, SandboxLimits limits)
                throws InterruptedException {
            calls++;
            this.input = input;
            if (interrupted) {
                throw new InterruptedException("private interruption marker");
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            return result;
        }
    }

    private static final class StubStorage implements ObjectStorage {
        private final List<ObjectKey> keys = new ArrayList<>();
        private final List<ObjectKey> deleted = new ArrayList<>();
        private int failAt = Integer.MAX_VALUE;

        @Override
        public StoredObject put(ObjectKey key, InputStream body, long size, String sha256, String contentType) {
            if (keys.size() + 1 == failAt) {
                throw new IllegalStateException("private storage marker");
            }
            keys.add(key);
            return new StoredObject(key, size, sha256, contentType);
        }

        @Override
        public StoredObjectContent get(ObjectKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(ObjectKey key) {
            deleted.add(key);
        }
    }

    private static final class StubQueue implements JobQueue {
        private JobLease lease;
        private boolean cancelled;
        private boolean acceptCompletion = true;
        private boolean acceptHeartbeat = true;
        private JobCompletion completion;
        private List<JobArtifact> artifacts = List.of();
        private Optional<ToolCatalogPublication> catalog = Optional.empty();
        private boolean failCompletion;
        private boolean lineageConflict;
        private int completions;
        private final AtomicInteger heartbeats = new AtomicInteger();

        @Override
        public Optional<JobLease> claim(WorkerId worker, Instant now, Duration duration) {
            return Optional.ofNullable(lease);
        }

        @Override
        public boolean heartbeat(JobLease lease, Instant leaseUntil) {
            heartbeats.incrementAndGet();
            return acceptHeartbeat;
        }

        @Override
        public boolean cancellationRequested(JobLease lease) {
            return cancelled;
        }

        @Override
        public boolean complete(JobLease lease, JobCompletion completion) {
            completions++;
            this.completion = completion;
            return acceptCompletion;
        }

        @Override
        public boolean complete(JobLease lease, JobCompletion completion, List<JobArtifact> artifacts) {
            this.artifacts = List.copyOf(artifacts);
            return complete(lease, completion);
        }

        @Override
        public boolean complete(
                JobLease lease,
                JobCompletion completion,
                List<JobArtifact> artifacts,
                Optional<ToolCatalogPublication> catalog) {
            if (lineageConflict && completion.status() == JobStatus.SUCCEEDED) {
                lineageConflict = false;
                completions++;
                throw new JobQueue.CatalogLineageConflict();
            }
            if (failCompletion) {
                throw new IllegalStateException("private completion marker");
            }
            boolean accepted = complete(lease, completion);
            if (accepted) {
                this.artifacts = List.copyOf(artifacts);
                this.catalog = catalog;
            }
            return accepted;
        }

        @Override public CreateJobResult create(CreateJob command) { throw new UnsupportedOperationException(); }
        @Override public Optional<JobView> find(AccountId owner, JobId id) { return Optional.empty(); }
        @Override public boolean requestCancellation(AccountId owner, JobId id) { return false; }
        @Override public int recoverExpired(Instant now, int maxAttempts) { return 0; }
    }
}
