package io.gen2spring.mcp.web;

import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationOutcome;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationProgress;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ProgressStatus;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class GenerationJobManager implements AutoCloseable {
    private static final int MAX_RETAINED_JOBS = 8;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-f0-9]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CLOSE_GRACE = Duration.ofSeconds(2);

    private final Path root;
    private final GenerationExecutor generation;
    private final Clock clock;
    private final Duration ttl;
    private final Consumer<Error> fatalSink;
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService cleanup;
    private final Map<String, MutableJob> jobs = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    GenerationJobManager(
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
        try {
            Path parent = Objects.requireNonNull(temporaryParent, "temporaryParent")
                    .toAbsolutePath().normalize();
            Files.createDirectories(parent);
            root = Files.createTempDirectory(parent, "gen2spring-jobs-").toAbsolutePath().normalize();
            setPermissions(root, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (IOException exception) {
            throw WebErrorMapper.failure(500, "WORKSPACE_CREATE_FAILED", "JOB_CREATE",
                    "The generation workspace could not be created");
        }
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

    synchronized JobSnapshot submit(Path specification, GenerationRequest request) {
        return submit(specification, request, () -> {});
    }

    synchronized JobSnapshot submit(
            Path specification,
            GenerationRequest request,
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

    synchronized JobSnapshot snapshot(String id) {
        MutableJob job = requireJob(id);
        synchronized (job) {
            job.lastAccess = clock.instant();
            return job.snapshot();
        }
    }

    JobSnapshot await(String id, Duration timeout) throws InterruptedException {
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

    synchronized Artifact artifact(String id, String name) {
        MutableJob job = requireJob(id);
        Artifact artifact;
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

    synchronized void delete(String id) {
        MutableJob job = requireJob(id);
        if (!job.terminal()) {
            throw new JobStateException();
        }
        jobs.remove(id);
        deleteJob(job);
    }

    synchronized void cleanupExpired() {
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

    Path root() {
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
        deleteTree(root);
    }

    private void run(MutableJob job) {
        synchronized (job) {
            if (job.terminal()) {
                return;
            }
            job.state = JobSnapshot.State.RUNNING;
            job.notifyAll();
        }
        try {
            GenerationOutcome outcome = generation.generate(
                    job.specification, job.request, job.outputRoot, progress -> applyProgress(job, progress));
            synchronized (job) {
                if (!job.terminal()) {
                    job.validationStatus = outcome.validationStatus();
                    job.artifacts = artifacts(job, outcome);
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
                job.artifacts = failureArtifacts(job);
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
                job.artifacts = failureArtifacts(job);
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
                job.notifyAll();
                return;
            }
            if ((progress.status() == ProgressStatus.SUCCESS || progress.status() == ProgressStatus.FAILED)
                    && current == ProgressStatus.RUNNING) {
                job.stages.put(progress.stage(), progress.status());
                job.notifyAll();
                return;
            }
            if (progress.status() == ProgressStatus.SKIPPED && current == ProgressStatus.PENDING
                    && job.previousSettled(progress.stage())) {
                job.stages.put(progress.stage(), ProgressStatus.SKIPPED);
                job.notifyAll();
            }
        }
    }

    private Map<String, Artifact> artifacts(MutableJob job, GenerationOutcome outcome) {
        Map<String, Artifact> result = new LinkedHashMap<>();
        capture(result, "manifest", outcome.projectRoot().resolve("GENERATION_MANIFEST.json"),
                "application/json; charset=utf-8", job.id + "-manifest.json", 1024 * 1024L);
        capture(result, "report", outcome.projectRoot().resolve("VALIDATION_REPORT.json"),
                "application/json; charset=utf-8", job.id + "-report.json", 1024 * 1024L);
        if (outcome.validationStatus() == ValidationStatus.VALIDATED && outcome.archive() != null) {
            capture(result, "archive", outcome.archive(), "application/zip",
                    job.id + ".zip", 100L * 1024L * 1024L);
        }
        return Map.copyOf(result);
    }

    private Map<String, Artifact> failureArtifacts(MutableJob job) {
        Map<String, Artifact> result = new LinkedHashMap<>();
        capture(result, "manifest", job.outputRoot.resolve("GENERATION_MANIFEST.json"),
                "application/json; charset=utf-8", job.id + "-manifest.json", 1024 * 1024L);
        capture(result, "report", job.outputRoot.resolve("VALIDATION_REPORT.json"),
                "application/json; charset=utf-8", job.id + "-report.json", 1024 * 1024L);
        return Map.copyOf(result);
    }

    private void capture(
            Map<String, Artifact> result,
            String name,
            Path path,
            String contentType,
            String downloadName,
            long maxBytes) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!owned(normalized) || Files.isSymbolicLink(normalized)) {
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isRegularFile() && attributes.size() <= maxBytes) {
                result.put(name, new Artifact(
                        normalized, attributes.fileKey(), attributes.size(), contentType, downloadName,
                        digest(readBounded(normalized, maxBytes))));
            }
        } catch (IOException ignored) {
            // An unavailable artifact is omitted from the terminal snapshot.
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
        deleteTree(job.outputRoot);
        deletePath(job.outputRoot.resolveSibling(job.outputRoot.getFileName() + ".zip"));
    }

    private void deleteTree(Path tree) {
        if (tree == null) {
            return;
        }
        Path normalized = tree.toAbsolutePath().normalize();
        if (!normalized.equals(root) && !owned(normalized)) {
            return;
        }
        try (var paths = Files.walk(tree)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deletePath);
        } catch (IOException ignored) {
            deletePath(tree);
        }
    }

    private void deletePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.equals(root) && !owned(normalized)) {
            return;
        }
        try {
            Files.deleteIfExists(normalized);
        } catch (IOException ignored) {
            // Cleanup remains confined to the private job root.
        }
    }

    private boolean owned(Path path) {
        return path.startsWith(root) && !path.equals(root);
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

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // POSIX permissions are unavailable on this filesystem.
        }
    }

    static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] readBounded(Path path, long maxBytes) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return new BoundedBodyReader(Math.toIntExact(maxBytes)).read(input);
        } catch (BoundedBodyReader.PayloadTooLargeException | BoundedBodyReader.BodyReadException exception) {
            throw new IOException("Artifact could not be pinned", exception);
        }
    }

    record Artifact(
            Path path,
            Object fileKey,
            long size,
            String contentType,
            String downloadName,
            byte[] digest) {
        Artifact {
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }

        boolean stable() {
            try {
                if (Files.isSymbolicLink(path)) {
                    return false;
                }
                BasicFileAttributes current = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return current.isRegularFile() && current.size() == size
                        && Objects.equals(current.fileKey(), fileKey);
            } catch (IOException exception) {
                return false;
            }
        }
    }

    static final class GenerationCapacityException extends RuntimeException {}
    static final class JobNotFoundException extends RuntimeException {}
    static final class JobStateException extends RuntimeException {}
    static final class ArtifactUnavailableException extends RuntimeException {}

    private static final class MutableJob {
        private final String id;
        private final Path specification;
        private final GenerationRequest request;
        private final Path outputRoot;
        private final LinkedHashMap<String, ProgressStatus> stages = new LinkedHashMap<>();
        private final Runnable completionHook;
        private final AtomicBoolean completionReleased = new AtomicBoolean();
        private volatile JobSnapshot.State state = JobSnapshot.State.QUEUED;
        private ValidationStatus validationStatus;
        private JobSnapshot.JobError error;
        private Map<String, Artifact> artifacts = Map.of();
        private volatile Instant lastAccess;

        private MutableJob(
                String id,
                Path specification,
                GenerationRequest request,
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
