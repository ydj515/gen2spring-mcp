package io.gen2spring.mcp.application.hosted.worker;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.application.hosted.job.JobCompletion;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.JobQueue;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class HostedWorker {
    private static final ObjectMapper REQUESTS = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final JobQueue jobs;
    private final SandboxRuntime sandbox;
    private final ImportRuntime imports;
    private final ObjectStorage storage;
    private final WorkerId worker;
    private final SandboxLimits limits;
    private final Clock clock;
    private final Duration leaseDuration;

    public HostedWorker(
            JobQueue jobs,
            SandboxRuntime sandbox,
            ImportRuntime imports,
            ObjectStorage storage,
            WorkerId worker,
            SandboxLimits limits,
            Clock clock,
            Duration leaseDuration) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.imports = Objects.requireNonNull(imports, "imports");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Hosted worker configuration is invalid");
        }
    }

    public PollResult pollOnce() throws InterruptedException {
        Optional<JobLease> claimed = jobs.claim(worker, clock.instant(), leaseDuration);
        if (claimed.isEmpty()) {
            return PollResult.EMPTY;
        }
        JobLease lease = claimed.get();
        RuntimeControl control = runtimeControl(lease);
        AtomicReference<Instant> leaseUntil = new AtomicReference<>(lease.leaseUntil());
        if (!extendLease(lease, leaseUntil)) {
            return PollResult.STALE;
        }
        if (jobs.cancellationRequested(lease)) {
            return completeCancellation(lease);
        }

        try (LeaseMonitor monitor = new LeaseMonitor(lease, control, leaseUntil)) {
            SandboxResult result;
            try {
                result = execute(lease);
            } catch (InterruptedException interrupted) {
                control.cancel();
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Error fatal) {
                control.cancel();
                throw fatal;
            } catch (RuntimeException failure) {
                if (!monitor.current()) {
                    return PollResult.STALE;
                }
                if (monitor.cancelled() || jobs.cancellationRequested(lease)) {
                    return completeCancellation(lease);
                }
                return completeFailure(
                        lease,
                        "SANDBOX_FAILED",
                        "The sandbox execution failed");
            }

            if (result == null) {
                return completeFailure(
                        lease,
                        "SANDBOX_FAILED",
                        "The sandbox execution failed");
            }

            try (result) {
                if (!monitor.current()) {
                    return PollResult.STALE;
                }
                if (monitor.cancelled() || jobs.cancellationRequested(lease)) {
                    return completeCancellation(lease);
                }
                if (!"SUCCESS".equals(result.outcome())) {
                    return completeFailure(
                            lease,
                            "SANDBOX_FAILED",
                            "The sandbox execution failed");
                }
                return publish(lease, result);
            }
        }
    }

    private SandboxResult execute(JobLease lease) throws InterruptedException {
        if (lease.kind() == JobKind.GENERATION) {
            GenerationRequest request = parse(lease.requestSnapshot(), GenerationRequest.class);
            return sandbox.run(
                    lease,
                    new SandboxInput(
                            ObjectKey.parse(request.specificationObjectKey()),
                            lease.requestSnapshot(),
                            request.targetProfileId()),
                    limits);
        }
        return imports.run(lease, parse(lease.requestSnapshot(), EncryptedImportTarget.class), limits);
    }

    private PollResult publish(JobLease lease, SandboxResult result) {
        List<ObjectKey> published = new ArrayList<>();
        try {
            for (SandboxArtifact artifact : result.artifacts()) {
                ObjectKey key = artifactKey(lease, artifact.name());
                StoredObject stored = storage.put(
                        key,
                        artifact.body(),
                        artifact.size(),
                        artifact.sha256(),
                        artifact.contentType());
                if (!stored.key().equals(key)
                        || stored.size() != artifact.size()
                        || !stored.sha256().equals(artifact.sha256())
                        || !stored.contentType().equals(artifact.contentType())) {
                    throw new IllegalStateException("Sandbox artifact publication failed");
                }
                published.add(key);
            }
        } catch (Error fatal) {
            deleteAll(published);
            throw fatal;
        } catch (RuntimeException failure) {
            deleteAll(published);
            return completeFailure(
                    lease,
                    "ARTIFACT_PUBLICATION_FAILED",
                    "The sandbox artifact could not be published");
        }

        try {
            if (jobs.cancellationRequested(lease)) {
                deleteAll(published);
                return completeCancellation(lease);
            }
            if (jobs.complete(lease, JobCompletion.success())) {
                return PollResult.COMPLETED;
            }
        } catch (Error fatal) {
            deleteAll(published);
            throw fatal;
        } catch (RuntimeException failure) {
            deleteAll(published);
            return PollResult.STALE;
        }
        deleteAll(published);
        return PollResult.STALE;
    }

    private ObjectKey artifactKey(JobLease lease, String name) {
        String prefix = lease.kind() == JobKind.SPEC_IMPORT ? "specifications" : "artifacts";
        return ObjectKey.parse(prefix + "/" + lease.jobId().value() + "/" + name);
    }

    private PollResult completeFailure(JobLease lease, String code, String summary) {
        return jobs.complete(lease, JobCompletion.failure(code, summary))
                ? PollResult.FAILED
                : PollResult.STALE;
    }

    private PollResult completeCancellation(JobLease lease) {
        return jobs.complete(lease, JobCompletion.cancelled())
                ? PollResult.CANCELLED
                : PollResult.STALE;
    }

    private boolean extendLease(JobLease lease, AtomicReference<Instant> lastLeaseUntil) {
        Instant candidate = clock.instant().plus(leaseDuration);
        Instant previous = lastLeaseUntil.get();
        if (!candidate.isAfter(previous)) {
            candidate = previous.plusNanos(1);
        }
        if (!jobs.heartbeat(lease, candidate)) {
            return false;
        }
        lastLeaseUntil.set(candidate);
        return true;
    }

    private <T> T parse(String value, Class<T> type) {
        try {
            return REQUESTS.readValue(value, type);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Hosted worker request is invalid");
        }
    }

    private void deleteAll(List<ObjectKey> keys) {
        for (ObjectKey key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException ignored) {
                // Retention cleanup can retry independently; do not publish the artifact metadata.
            }
        }
    }

    private RuntimeControl runtimeControl(JobLease lease) {
        return lease.kind() == JobKind.GENERATION
                ? () -> sandbox.cancel(lease)
                : () -> imports.cancel(lease);
    }

    public enum PollResult {
        EMPTY,
        COMPLETED,
        FAILED,
        CANCELLED,
        STALE
    }

    @FunctionalInterface
    private interface RuntimeControl {
        void cancel();
    }

    private record GenerationRequest(String specificationObjectKey, String targetProfileId) {}

    private final class LeaseMonitor implements AutoCloseable {
        private final JobLease lease;
        private final RuntimeControl control;
        private final AtomicReference<Instant> leaseUntil;
        private final AtomicBoolean current = new AtomicBoolean(true);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("hosted-lease-monitor-", 0).factory());

        private LeaseMonitor(
                JobLease lease,
                RuntimeControl control,
                AtomicReference<Instant> leaseUntil) {
            this.lease = lease;
            this.control = control;
            this.leaseUntil = leaseUntil;
            long interval = Math.max(1, leaseDuration.toSeconds() / 3);
            executor.scheduleAtFixedRate(this::check, interval, interval, TimeUnit.SECONDS);
        }

        private void check() {
            try {
                if (jobs.cancellationRequested(lease)) {
                    cancelled.set(true);
                    control.cancel();
                    return;
                }
                if (!extendLease(lease, leaseUntil)) {
                    current.set(false);
                    control.cancel();
                }
            } catch (RuntimeException failure) {
                current.set(false);
                control.cancel();
            }
        }

        private boolean current() {
            return current.get();
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        @Override
        public void close() {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    current.set(false);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                current.set(false);
            }
        }
    }
}
