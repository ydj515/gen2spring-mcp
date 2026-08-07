package io.gen2spring.mcp.validation;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

public final class BoundedProcessRunner {
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(2);
    private static final long POLL_NANOS = Duration.ofMillis(25).toNanos();

    private final OutputCollector collector;

    public BoundedProcessRunner() {
        this(BoundedProcessRunner::drainBounded);
    }

    BoundedProcessRunner(OutputCollector collector) {
        this.collector = Objects.requireNonNull(collector, "collector");
    }

    public Result run(List<String> command, Path workingRoot, Duration timeout, int maxBytes)
            throws IOException, InterruptedException {
        requirePositive(timeout, "timeout");
        RunningProcess process = start(command, workingRoot, maxBytes);
        Throwable primary = null;
        boolean timedOut = false;
        try {
            timedOut = !process.awaitExit(timeout);
            if (!timedOut) {
                process.requireCollectorsHealthy();
            }
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                process.close();
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                if (primary != null) {
                    primary.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
        return process.result(timedOut);
    }

    public RunningProcess start(List<String> command, Path workingRoot, int maxBytes) throws IOException {
        List<String> safeCommand = validateCommand(command);
        Path fixedRoot = fixedWorkingRoot(workingRoot);
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        Process process = new ProcessBuilder(safeCommand)
                .directory(fixedRoot.toFile())
                .redirectErrorStream(false)
                .start();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("bounded-process-output-", 0).factory());
        OutputSnapshot stdoutSnapshot = new OutputSnapshot(maxBytes);
        try {
            Future<StreamSummary> stdout = executor.submit(
                    () -> collector.collect(new CapturingInputStream(process.getInputStream(), stdoutSnapshot), maxBytes));
            Future<StreamSummary> stderr = executor.submit(() -> collector.collect(process.getErrorStream(), maxBytes));
            return new RunningProcess(process, executor, stdout, stderr, stdoutSnapshot);
        } catch (RuntimeException | Error failure) {
            process.destroyForcibly();
            executor.shutdownNow();
            throw failure;
        }
    }

    private static List<String> validateCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        List<String> copy = List.copyOf(command);
        if (copy.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("command arguments must not be blank");
        }
        return copy;
    }

    private static Path fixedWorkingRoot(Path workingRoot) throws IOException {
        if (workingRoot == null || Files.isSymbolicLink(workingRoot)) {
            throw new IOException("Process working root is unavailable");
        }
        Path fixed = workingRoot.toAbsolutePath().normalize().toRealPath(NOFOLLOW_LINKS);
        if (!Files.isDirectory(fixed, NOFOLLOW_LINKS)) {
            throw new IOException("Process working root is not a directory");
        }
        return fixed;
    }

    private static StreamSummary drainBounded(InputStream input, int maxBytes) throws IOException {
        long observed = 0;
        try (input) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                observed = Math.min(Long.MAX_VALUE, observed + read);
            }
        }
        return new StreamSummary(observed, (int) Math.min(observed, maxBytes), observed > maxBytes);
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    @FunctionalInterface
    interface OutputCollector {
        StreamSummary collect(InputStream input, int maxBytes) throws Exception;
    }

    public static final class RunningProcess implements AutoCloseable {
        private final Process process;
        private final ExecutorService executor;
        private final Future<StreamSummary> stdout;
        private final Future<StreamSummary> stderr;
        private final OutputSnapshot stdoutSnapshot;
        private final Map<Long, ProcessHandle> observedTree = new LinkedHashMap<>();
        private boolean closed;
        private Result closedResult;

        private RunningProcess(
                Process process,
                ExecutorService executor,
                Future<StreamSummary> stdout,
                Future<StreamSummary> stderr,
                OutputSnapshot stdoutSnapshot) {
            this.process = process;
            this.executor = executor;
            this.stdout = stdout;
            this.stderr = stderr;
            this.stdoutSnapshot = stdoutSnapshot;
            trackTree();
        }

        public boolean isAlive() {
            trackTree();
            return process.isAlive();
        }

        String stdoutSnapshot() {
            return stdoutSnapshot.text();
        }

        boolean stdoutTruncated() {
            return stdoutSnapshot.truncated();
        }

        boolean awaitExit(Duration timeout) throws IOException, InterruptedException {
            long deadline = deadline(timeout);
            while (process.isAlive()) {
                trackTree();
                requireCollectorsHealthy();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                process.waitFor(Math.min(POLL_NANOS, remaining), NANOSECONDS);
            }
            trackTree();
            return true;
        }

        void requireCollectorsHealthy() throws IOException, InterruptedException {
            requireHealthy(stdout);
            requireHealthy(stderr);
        }

        public Result result(boolean timedOut) {
            if (!closed || closedResult == null) {
                throw new IllegalStateException("Process must be closed before reading its result");
            }
            return new Result(
                    closedResult.exitCode(), timedOut, closedResult.processAlive(),
                    closedResult.stdout(), closedResult.stderr());
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            List<Throwable> failures = new ArrayList<>();
            attempt(failures, () -> process.getOutputStream().close());
            attempt(failures, this::terminateTree);
            attempt(failures, () -> {
                process.getInputStream().close();
                process.getErrorStream().close();
            });
            StreamSummary stdoutSummary = finishCollector(stdout, failures);
            StreamSummary stderrSummary = finishCollector(stderr, failures);
            executor.shutdownNow();
            if (!awaitTerminationUninterruptibly(executor, CLEANUP_TIMEOUT)) {
                failures.add(new TimeoutException("Process output collectors did not terminate"));
            }
            closedResult = new Result(
                    process.isAlive() ? -1 : process.exitValue(), false, process.isAlive(),
                    stdoutSummary, stderrSummary);
            if (!failures.isEmpty()) {
                Throwable first = failures.getFirst();
                failures.stream().skip(1).forEach(first::addSuppressed);
                if (first instanceof IOException exception) {
                    throw exception;
                }
                throw new IOException("Process cleanup failed", first);
            }
        }

        private void terminateTree() throws IOException {
            trackTree();
            List<ProcessHandle> handles = new ArrayList<>(observedTree.values());
            handles.reversed().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
            waitForTree(handles, CLEANUP_TIMEOUT);
            List<ProcessHandle> survivors = handles.stream().filter(ProcessHandle::isAlive).toList();
            survivors.reversed().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            waitForTree(survivors, CLEANUP_TIMEOUT);
            if (handles.stream().anyMatch(ProcessHandle::isAlive)) {
                throw new IOException("Process tree did not terminate within the cleanup bound");
            }
        }

        private void trackTree() {
            process.descendants().forEach(handle -> observedTree.putIfAbsent(handle.pid(), handle));
            observedTree.putIfAbsent(process.pid(), process.toHandle());
        }

        private static void requireHealthy(Future<StreamSummary> future) throws IOException, InterruptedException {
            if (!future.isDone()) {
                return;
            }
            try {
                future.get();
            } catch (ExecutionException exception) {
                throw new IOException("Process output collection failed", exception.getCause());
            } catch (java.util.concurrent.CancellationException exception) {
                throw new IOException("Process output collection was cancelled", exception);
            }
        }

        private static StreamSummary finishCollector(Future<StreamSummary> future, List<Throwable> failures) {
            try {
                return future.get(CLEANUP_TIMEOUT.toNanos(), NANOSECONDS);
            } catch (Exception exception) {
                future.cancel(true);
                Throwable failure = exception instanceof ExecutionException && exception.getCause() != null
                        ? exception.getCause() : exception;
                failures.add(failure);
                return StreamSummary.EMPTY;
            }
        }

        private static void waitForTree(List<ProcessHandle> handles, Duration timeout) {
            boolean interrupted = Thread.interrupted();
            long deadline = deadline(timeout);
            try {
                while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                    try {
                        NANOSECONDS.sleep(Math.min(POLL_NANOS, Math.max(1, deadline - System.nanoTime())));
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static boolean awaitTerminationUninterruptibly(ExecutorService executor, Duration timeout) {
            boolean interrupted = Thread.interrupted();
            long deadline = deadline(timeout);
            try {
                while (!executor.isTerminated()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return false;
                    }
                    try {
                        if (executor.awaitTermination(remaining, NANOSECONDS)) {
                            return true;
                        }
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                return true;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static void attempt(List<Throwable> failures, CleanupAction action) {
            try {
                action.run();
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
    }

    public record StreamSummary(long observedBytes, int retainedBytes, boolean truncated) {
        private static final StreamSummary EMPTY = new StreamSummary(0, 0, false);

        public StreamSummary {
            if (observedBytes < 0 || retainedBytes < 0) {
                throw new IllegalArgumentException("Stream byte counts must not be negative");
            }
        }
    }

    private static final class CapturingInputStream extends InputStream {
        private final InputStream delegate;
        private final OutputSnapshot snapshot;

        private CapturingInputStream(InputStream delegate, OutputSnapshot snapshot) {
            this.delegate = delegate;
            this.snapshot = snapshot;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                snapshot.append(new byte[] {(byte) value}, 0, 1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                snapshot.append(bytes, offset, read);
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class OutputSnapshot {
        private final int maxBytes;
        private final ByteArrayOutputStream output;
        private long observedBytes;

        private OutputSnapshot(int maxBytes) {
            this.maxBytes = maxBytes;
            this.output = new ByteArrayOutputStream(Math.min(maxBytes, 8_192));
        }

        private synchronized void append(byte[] bytes, int offset, int length) {
            observedBytes = observedBytes > Long.MAX_VALUE - length
                    ? Long.MAX_VALUE
                    : observedBytes + length;
            int remaining = maxBytes - output.size();
            if (remaining > 0) {
                output.write(bytes, offset, Math.min(remaining, length));
            }
        }

        private synchronized String text() {
            return output.toString(StandardCharsets.UTF_8);
        }

        private synchronized boolean truncated() {
            return observedBytes > maxBytes;
        }
    }

    public record Result(
            int exitCode,
            boolean timedOut,
            boolean processAlive,
            StreamSummary stdout,
            StreamSummary stderr) {
        public Result {
            Objects.requireNonNull(stdout, "stdout");
            Objects.requireNonNull(stderr, "stderr");
        }

        public String safeSummary() {
            return "exitCode=" + exitCode
                    + ", timedOut=" + timedOut
                    + ", stdoutBytes=" + stdout.observedBytes()
                    + ", stdoutTruncated=" + stdout.truncated()
                    + ", stderrBytes=" + stderr.observedBytes()
                    + ", stderrTruncated=" + stderr.truncated();
        }
    }

    @FunctionalInterface
    private interface CleanupAction {
        void run() throws Exception;
    }

    private static long deadline(Duration duration) {
        long now = System.nanoTime();
        long nanos = duration.toNanos();
        return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
    }
}
