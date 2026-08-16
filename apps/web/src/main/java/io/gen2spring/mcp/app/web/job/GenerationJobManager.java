package io.gen2spring.mcp.app.web.job;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.application.usecase.GenerationOutcome;
import io.gen2spring.mcp.application.usecase.GenerationProgress;
import io.gen2spring.mcp.application.usecase.ProgressStatus;
import io.gen2spring.mcp.application.validation.ValidationStatus;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class GenerationJobManager implements AutoCloseable {
    private static final int MAX_RETAINED_JOBS = 8;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-f0-9]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CLOSE_GRACE = Duration.ofSeconds(2);

    private final Path root;
    private final JobWorkspace workspace;
    private final GenerationExecutor generation;
    private final Clock clock;
    private final Duration ttl;
    private final Consumer<Error> fatalSink;
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService cleanup;
    private final Map<String, MutableJob> jobs = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public GenerationJobManager(
            Path temporaryParent,
            GenerationExecutor generation,
            Clock clock,
            Duration ttl,
            Consumer<Error> fatalSink) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.fatalSink = Objects.requireNonNull(fatalSink, "fatalSink");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Job retention is invalid");
        }
        workspace = new JobWorkspace(temporaryParent);
        root = workspace.root();
        ThreadFactory workerThreads = runnable -> {
            Thread thread = new Thread(runnable, "gen2spring-generation-worker");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> {
                if (failure instanceof Error fatal) {
                    fatalSink.accept(fatal);
                }
            });
            return thread;
        };
        worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), workerThreads, new ThreadPoolExecutor.AbortPolicy());
        cleanup = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gen2spring-job-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = Math.max(1_000L, Math.min(ttl.toMillis(), Duration.ofMinutes(1).toMillis()));
        cleanup.scheduleWithFixedDelay(this::safeCleanupExpired,
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized JobSnapshot submit(Path specification, GenerationCommand request) {
        return submit(specification, request, () -> {});
    }

    public synchronized JobSnapshot submit(
            Path specification,
            GenerationCommand request,
            Runnable completionHook) {
        requireOpen();
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(completionHook, "completionHook");
        if (jobs.values().stream().filter(job -> !job.terminal()).count() >= 2) {
            throw new GenerationCapacityException();
        }
        MutableJob eviction = evictionCandidate();
        String id = identifier();
        MutableJob job = new MutableJob(id, specification.toAbsolutePath().normalize(), request,
                root.resolve(id), clock.instant(), completionHook);
        jobs.put(id, job);
        try {
            worker.execute(() -> run(job));
        } catch (RuntimeException failure) {
            jobs.remove(id);
            job.completeHook();
            throw new GenerationCapacityException();
        }
        if (eviction != null) {
            jobs.remove(eviction.id);
            deleteJob(eviction);
        }
        synchronized (job) {
            return job.snapshot();
        }
    }

    public synchronized JobSnapshot snapshot(String id) {
        MutableJob job = requireJob(id);
        synchronized (job) {
            job.lastAccess = clock.instant();
            return job.snapshot();
        }
    }

    public JobSnapshot await(String id, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        MutableJob job;
        synchronized (this) {
            job = requireJob(id);
        }
        synchronized (job) {
            while (!job.terminal()) {
                if (remainingNanos <= 0) {
                    throw new IllegalStateException("Generation job did not finish in time");
                }
                TimeUnit.NANOSECONDS.timedWait(job, remainingNanos);
                remainingNanos = deadline - System.nanoTime();
            }
            job.lastAccess = clock.instant();
            return job.snapshot();
        }
    }

    /** A snapshot paired with the change version it was taken at. */
    public record VersionedSnapshot(long version, JobSnapshot snapshot) {}

    public VersionedSnapshot current(String id) {
        MutableJob job;
        synchronized (this) {
            job = requireJob(id);
        }
        synchronized (job) {
            job.lastAccess = clock.instant();
            return new VersionedSnapshot(job.version, job.snapshot());
        }
    }

    /**
     * Blocks until the job changes past {@code sinceVersion}, or the timeout elapses.
     * Returns empty on timeout rather than throwing, because a quiet interval is the
     * normal heartbeat path for a stream reader and not a failure.
     */
    public Optional<VersionedSnapshot> awaitChange(String id, long sinceVersion, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        MutableJob job;
        synchronized (this) {
            job = requireJob(id);
        }
        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (job) {
            while (job.version <= sinceVersion) {
                if (remainingNanos <= 0) {
                    return Optional.empty();
                }
                TimeUnit.NANOSECONDS.timedWait(job, remainingNanos);
                remainingNanos = deadline - System.nanoTime();
            }
            job.lastAccess = clock.instant();
            return Optional.of(new VersionedSnapshot(job.version, job.snapshot()));
        }
    }

    public synchronized JobWorkspace.Artifact artifact(String id, String name) {
        MutableJob job = requireJob(id);
        JobWorkspace.Artifact artifact;
        synchronized (job) {
            job.lastAccess = clock.instant();
            artifact = job.artifacts.get(name);
            if (artifact == null || ("archive".equals(name) && job.state != JobSnapshot.State.VALIDATED)) {
                throw new ArtifactUnavailableException();
            }
        }
        if (!artifact.stable()) {
            throw new ArtifactUnavailableException();
        }
        return artifact;
    }

    public synchronized void delete(String id) {
        MutableJob job = requireJob(id);
        if (!job.terminal()) {
            throw new JobStateException();
        }
        jobs.remove(id);
        deleteJob(job);
    }

    public synchronized void cleanupExpired() {
        Instant threshold = clock.instant().minus(ttl);
        List<MutableJob> expired = jobs.values().stream()
                .filter(MutableJob::terminal)
                .filter(job -> job.lastAccess.isBefore(threshold))
                .toList();
        expired.forEach(job -> {
            jobs.remove(job.id);
            deleteJob(job);
        });
    }

    public Path root() {
        return root;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cleanup.shutdownNow();
        worker.shutdownNow();
        synchronized (this) {
            jobs.values().forEach(job -> {
                synchronized (job) {
                    if (!job.terminal()) {
                        job.sealFailure(new JobSnapshot.JobError(
                                "INTERNAL_ERROR", "JOB_EXECUTION", "Generation was interrupted"));
                    }
                }
            });
        }
        try {
            worker.awaitTermination(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            cleanup.awaitTermination(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            jobs.values().forEach(this::deleteJob);
            jobs.clear();
        }
        workspace.close();
    }

    private void run(MutableJob job) {
        synchronized (job) {
            if (job.terminal()) {
                return;
            }
            job.state = JobSnapshot.State.RUNNING;
            job.markChanged();
        }
        try {
            GenerationOutcome outcome = generation.generate(
                    job.specification, job.request, job.outputRoot, progress -> applyProgress(job, progress));
            synchronized (job) {
                if (!job.terminal()) {
                    job.validationStatus = outcome.validationStatus();
                    job.artifacts = workspace.capture(job.id, outcome);
                    job.lastAccess = clock.instant();
                    job.seal(outcome.validationStatus() == ValidationStatus.VALIDATED
                            ? JobSnapshot.State.VALIDATED
                            : JobSnapshot.State.UNVERIFIED, null);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            synchronized (job) {
                job.lastAccess = clock.instant();
                job.sealFailure(new JobSnapshot.JobError(
                        "INTERNAL_ERROR", "JOB_EXECUTION", "Generation was interrupted"));
            }
            deleteJob(job);
        } catch (GeneratorException exception) {
            synchronized (job) {
                job.artifacts = workspace.captureFailure(job.id, job.outputRoot);
                job.lastAccess = clock.instant();
                job.sealFailure(new JobSnapshot.JobError(
                        exception.code().name(), exception.stage(), exception.safeMessage()));
            }
        } catch (Error fatal) {
            synchronized (job) {
                job.lastAccess = clock.instant();
                job.sealFailure(internalFailure());
            }
            deleteJob(job);
            throw fatal;
        } catch (Exception exception) {
            synchronized (job) {
                job.artifacts = workspace.captureFailure(job.id, job.outputRoot);
                job.lastAccess = clock.instant();
                job.sealFailure(internalFailure());
            }
        } finally {
            if (closed.get()) {
                deleteJob(job);
            }
        }
    }

    private void applyProgress(MutableJob job, GenerationProgress progress) {
        if (progress == null) {
            return;
        }
        synchronized (job) {
            if (job.terminal()) {
                return;
            }
            ProgressStatus current = job.stages.get(progress.stage());
            if (current == null) {
                return;
            }
            if (progress.status() == ProgressStatus.RUNNING && current == ProgressStatus.PENDING
                    && job.previousSettled(progress.stage())) {
                job.stages.put(progress.stage(), ProgressStatus.RUNNING);
                job.markChanged();
                return;
            }
            if ((progress.status() == ProgressStatus.SUCCESS || progress.status() == ProgressStatus.FAILED)
                    && current == ProgressStatus.RUNNING) {
                job.stages.put(progress.stage(), progress.status());
                job.markChanged();
                return;
            }
            if (progress.status() == ProgressStatus.SKIPPED && current == ProgressStatus.PENDING
                    && job.previousSettled(progress.stage())) {
                job.stages.put(progress.stage(), ProgressStatus.SKIPPED);
                job.markChanged();
            }
        }
    }

    private synchronized MutableJob requireJob(String id) {
        if (id == null || !IDENTIFIER.matcher(id).matches()) {
            throw new JobNotFoundException();
        }
        MutableJob job = jobs.get(id);
        if (job == null) {
            throw new JobNotFoundException();
        }
        return job;
    }

    private MutableJob evictionCandidate() {
        if (jobs.size() < MAX_RETAINED_JOBS) {
            return null;
        }
        return jobs.values().stream()
                .filter(MutableJob::terminal)
                .min(Comparator.comparing(job -> job.lastAccess))
                .orElseThrow(GenerationCapacityException::new);
    }

    private void deleteJob(MutableJob job) {
        workspace.deleteJob(job.outputRoot);
    }

    private void safeCleanupExpired() {
        try {
            cleanupExpired();
        } catch (RuntimeException ignored) {
            // Cleanup retries on the next bounded interval.
        }
    }

    private String identifier() {
        byte[] bytes = new byte[32];
        String candidate;
        do {
            RANDOM.nextBytes(bytes);
            candidate = HexFormat.of().formatHex(bytes);
        } while (jobs.containsKey(candidate));
        return candidate;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Generation jobs are closed");
        }
    }

    private static JobSnapshot.JobError internalFailure() {
        return new JobSnapshot.JobError(
                "INTERNAL_ERROR", "JOB_EXECUTION", "Generation failed safely");
    }

    public static final class GenerationCapacityException extends RuntimeException {}
    public static final class JobNotFoundException extends RuntimeException {}
    public static final class JobStateException extends RuntimeException {}
    public static final class ArtifactUnavailableException extends RuntimeException {}

    private static final class MutableJob {
        private final String id;
        private final Path specification;
        private final GenerationCommand request;
        private final Path outputRoot;
        private final LinkedHashMap<String, ProgressStatus> stages = new LinkedHashMap<>();
        private final Runnable completionHook;
        private final AtomicBoolean completionReleased = new AtomicBoolean();
        // Starts at 1, not 0. A stream opens with cursor 0, and a job that has not
        // changed yet must still exceed that cursor or the immediate first snapshot
        // the stream contract promises would never be sent.
        private long version = 1;
        private volatile JobSnapshot.State state = JobSnapshot.State.QUEUED;
        private ValidationStatus validationStatus;
        private JobSnapshot.JobError error;
        private Map<String, JobWorkspace.Artifact> artifacts = Map.of();
        private volatile Instant lastAccess;

        private MutableJob(
                String id,
                Path specification,
                GenerationCommand request,
                Path outputRoot,
                Instant created,
                Runnable completionHook) {
            this.id = id;
            this.specification = specification;
            this.request = request;
            this.outputRoot = outputRoot;
            this.lastAccess = created;
            this.completionHook = completionHook;
            GenerationProgress.STAGES.forEach(stage -> stages.put(stage, ProgressStatus.PENDING));
        }

        private boolean terminal() {
            return state == JobSnapshot.State.VALIDATED
                    || state == JobSnapshot.State.UNVERIFIED
                    || state == JobSnapshot.State.FAILED;
        }

        private boolean previousSettled(String stage) {
            for (Map.Entry<String, ProgressStatus> entry : stages.entrySet()) {
                if (entry.getKey().equals(stage)) {
                    return true;
                }
                if (entry.getValue() == ProgressStatus.PENDING || entry.getValue() == ProgressStatus.RUNNING) {
                    return false;
                }
            }
            return false;
        }

        private void sealFailure(JobSnapshot.JobError failure) {
            if (!terminal()) {
                seal(JobSnapshot.State.FAILED, failure);
            }
        }

        private void seal(JobSnapshot.State terminalState, JobSnapshot.JobError terminalError) {
            stages.replaceAll((stage, status) -> status == ProgressStatus.PENDING
                    ? ProgressStatus.SKIPPED
                    : status == ProgressStatus.RUNNING ? ProgressStatus.FAILED : status);
            state = terminalState;
            error = terminalError;
            completeHook();
            markChanged();
        }

        /**
         * Bumps the change version and wakes every waiter. Version and notification
         * are raised together so a change can never be signalled without advancing
         * the cursor a stream reader is comparing against.
         */
        private void markChanged() {
            version++;
            notifyAll();
        }

        private void completeHook() {
            if (completionReleased.compareAndSet(false, true)) {
                try {
                    completionHook.run();
                } catch (RuntimeException ignored) {
                    // Retention bookkeeping must not alter the terminal result.
                }
            }
        }

        private JobSnapshot snapshot() {
            List<JobSnapshot.JobStage> snapshotStages = new ArrayList<>();
            stages.forEach((stage, status) -> snapshotStages.add(new JobSnapshot.JobStage(stage, status)));
            String currentStage = stages.entrySet().stream()
                    .filter(entry -> entry.getValue() == ProgressStatus.RUNNING)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            List<String> downloads = artifacts.keySet().stream().sorted().toList();
            return new JobSnapshot(
                    id, state, currentStage, snapshotStages, validationStatus, error, downloads);
        }
    }
}
