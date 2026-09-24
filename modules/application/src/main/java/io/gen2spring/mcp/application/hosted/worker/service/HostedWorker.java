package io.gen2spring.mcp.application.hosted.worker.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogPublication;
import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.application.hosted.job.JobArtifact;
import io.gen2spring.mcp.application.hosted.job.JobCompletion;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.job.port.out.JobQueue;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import io.gen2spring.mcp.application.hosted.worker.SandboxArtifact;
import io.gen2spring.mcp.application.hosted.worker.SandboxInput;
import io.gen2spring.mcp.application.hosted.worker.SandboxLimits;
import io.gen2spring.mcp.application.hosted.worker.SandboxResult;
import io.gen2spring.mcp.application.hosted.worker.port.out.ImportRuntime;
import io.gen2spring.mcp.application.hosted.worker.port.out.LeaseMonitorScheduler;
import io.gen2spring.mcp.application.hosted.worker.port.out.SandboxRuntime;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
    private final Duration artifactRetention;
    private final LeaseMonitorScheduler scheduler;

    public HostedWorker(
            JobQueue jobs,
            SandboxRuntime sandbox,
            ImportRuntime imports,
            ObjectStorage storage,
            WorkerId worker,
            SandboxLimits limits,
            Clock clock,
            Duration leaseDuration,
            LeaseMonitorScheduler scheduler) {
        this(jobs, sandbox, imports, storage, worker, limits, clock, leaseDuration, Duration.ofDays(30), scheduler);
    }

    public HostedWorker(
            JobQueue jobs,
            SandboxRuntime sandbox,
            ImportRuntime imports,
            ObjectStorage storage,
            WorkerId worker,
            SandboxLimits limits,
            Clock clock,
            Duration leaseDuration,
            Duration artifactRetention,
            LeaseMonitorScheduler scheduler) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.imports = Objects.requireNonNull(imports, "imports");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.artifactRetention = Objects.requireNonNull(artifactRetention, "artifactRetention");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0
                || artifactRetention.compareTo(Duration.ofHours(1)) < 0
                || artifactRetention.compareTo(Duration.ofDays(365)) > 0) {
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
            if (request.configuration() == null
                    || !request.configuration().isObject()
                    || !request.configuration().path("targetProfileId").isTextual()) {
                throw new IllegalArgumentException("Hosted worker request is invalid");
            }
            return sandbox.run(
                    lease,
                    new SandboxInput(
                            ObjectKey.parse(request.specificationObjectKey()),
                            serializeConfiguration(request.configuration()),
                            request.configuration().path("targetProfileId").textValue()),
                    limits);
        }
        return imports.run(lease, parse(lease.requestSnapshot(), EncryptedImportTarget.class), limits);
    }

    private PollResult publish(JobLease lease, SandboxResult result) {
        List<ObjectKey> published = new ArrayList<>();
        List<JobArtifact> artifacts = new ArrayList<>();
        Optional<ToolCatalogPublication> catalog;
        try {
            catalog = catalogPublication(lease, result);
        } catch (RuntimeException failure) {
            return completeFailure(
                    lease,
                    "CATALOG_PUBLICATION_FAILED",
                    "The Tool Catalog could not be published");
        }
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
                artifacts.add(new JobArtifact(
                        artifact.name().toUpperCase(java.util.Locale.ROOT).replace('-', '_'),
                        key,
                        stored.sha256(),
                        stored.size(),
                        stored.contentType(),
                        clock.instant().plus(artifactRetention)));
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
            if (jobs.complete(lease, JobCompletion.success(), List.copyOf(artifacts), catalog)) {
                return PollResult.COMPLETED;
            }
        } catch (Error fatal) {
            deleteAll(published);
            throw fatal;
        } catch (JobQueue.CatalogLineageConflict conflict) {
            deleteAll(published);
            return completeFailure(
                    lease,
                    "CATALOG_LINEAGE_CONFLICT",
                    "The Tool Catalog lineage changed before publication");
        } catch (RuntimeException failure) {
            deleteAll(published);
            return PollResult.STALE;
        }
        deleteAll(published);
        return PollResult.STALE;
    }

    private Optional<ToolCatalogPublication> catalogPublication(JobLease lease, SandboxResult result) {
        if (lease.kind() == JobKind.GENERATION) {
            return Optional.of(ToolCatalogPublication.from(result.runtimeMetadata().orElseThrow()));
        }
        if (result.runtimeMetadata().isPresent()) {
            throw new IllegalArgumentException("Hosted Tool Catalog publication is invalid");
        }
        return Optional.empty();
    }

    private ObjectKey artifactKey(JobLease lease, String name) {
        String prefix = lease.kind() == JobKind.SPEC_IMPORT ? "specifications" : "artifacts";
        return ObjectKey.parse(
                prefix + "/" + lease.jobId().value() + "/" + lease.fencingToken() + "-" + name);
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

    private String serializeConfiguration(JsonNode configuration) {
        try {
            return REQUESTS.writeValueAsString(configuration);
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

    private record GenerationRequest(
            String specificationObjectKey,
            UUID predecessorCatalogId,
            JsonNode configuration) {}

    private final class LeaseMonitor implements AutoCloseable {
        private final JobLease lease;
        private final RuntimeControl control;
        private final AtomicReference<Instant> leaseUntil;
        private final AtomicBoolean current = new AtomicBoolean(true);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final LeaseMonitorScheduler.Registration registration;

        private LeaseMonitor(
                JobLease lease,
                RuntimeControl control,
                AtomicReference<Instant> leaseUntil) {
            this.lease = lease;
            this.control = control;
            this.leaseUntil = leaseUntil;
            Duration interval = Duration.ofNanos(Math.max(1, leaseDuration.toNanos() / 3));
            registration = scheduler.schedule(interval, this::check);
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
            if (!registration.stop()) {
                current.set(false);
            }
        }
    }
}
