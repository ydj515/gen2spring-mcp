package io.gen2spring.mcp.springai2;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

final class ManagedTestProcess {
    private static final long POLL_NANOS = Duration.ofMillis(50).toNanos();

    private ManagedTestProcess() {}

    static Result run(Process process, Duration timeout, Duration cleanupTimeout) throws Exception {
        return run(process, timeout, cleanupTimeout, input -> {
            try (input; var bytes = new ByteArrayOutputStream()) {
                input.transferTo(bytes);
                return bytes.toString(UTF_8);
            }
        });
    }

    static Result run(
            Process process,
            Duration timeout,
            Duration cleanupTimeout,
            OutputCollector collector) throws Exception {
        requirePositive(timeout, "timeout");
        requirePositive(cleanupTimeout, "cleanupTimeout");
        ExecutorService outputExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("managed-test-process-output", 0).factory());
        Future<String> output = null;
        Throwable primaryFailure = null;
        try {
            InputStream input = process.getInputStream();
            output = outputExecutor.submit(() -> collector.collect(input));
            waitForProcess(process, output, timeout);
            String captured = output.get(cleanupTimeout.toNanos(), NANOSECONDS);
            return new Result(process.exitValue(), captured);
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                cleanup(process, output, outputExecutor, cleanupTimeout);
            } catch (Exception | Error cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static void waitForProcess(Process process, Future<String> output, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive()) {
            if (output.isDone()) {
                output.get();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Managed test process timed out");
            }
            process.waitFor(Math.min(POLL_NANOS, remaining), NANOSECONDS);
        }
    }

    private static void cleanup(
            Process process,
            Future<String> output,
            ExecutorService outputExecutor,
            Duration timeout) throws Exception {
        List<Throwable> failures = new ArrayList<>();
        attempt(failures, () -> {
            process.getOutputStream().close();
        });
        attempt(failures, () -> {
            terminateTree(process, timeout);
        });
        attempt(failures, () -> {
            process.getInputStream().close();
            process.getErrorStream().close();
        });
        if (output != null && !output.isDone()) {
            output.cancel(true);
        }
        outputExecutor.shutdownNow();
        attempt(failures, () -> {
            if (!awaitExecutorTermination(outputExecutor, timeout)) {
                throw new TimeoutException("Output collector did not terminate");
            }
        });
        if (!failures.isEmpty()) {
            Throwable first = failures.getFirst();
            failures.stream().skip(1).forEach(first::addSuppressed);
            rethrow(first);
        }
    }

    private static void terminateTree(Process process, Duration timeout) throws Exception {
        List<ProcessHandle> handles = Stream.concat(process.descendants(), Stream.of(process.toHandle()))
                .distinct()
                .toList();
        List<ProcessHandle> reverse = new ArrayList<>(handles);
        Collections.reverse(reverse);
        reverse.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        waitForExit(handles, timeout);

        List<ProcessHandle> survivors = handles.stream().filter(ProcessHandle::isAlive).toList();
        if (!survivors.isEmpty()) {
            survivors.reversed().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            waitForExit(survivors, timeout);
        }
        if (handles.stream().anyMatch(ProcessHandle::isAlive)) {
            throw new TimeoutException("Managed test process tree did not terminate");
        }
    }

    private static void waitForExit(List<ProcessHandle> handles, Duration timeout) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (handles.stream().anyMatch(ProcessHandle::isAlive)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                try {
                    NANOSECONDS.sleep(Math.min(POLL_NANOS, remaining));
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

    private static boolean awaitExecutorTermination(ExecutorService executor, Duration timeout) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + timeout.toNanos();
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
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(failure);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    @FunctionalInterface
    interface OutputCollector {
        String collect(InputStream input) throws Exception;
    }

    @FunctionalInterface
    private interface CleanupAction {
        void run() throws Exception;
    }

    record Result(int exitCode, String output) {}
}
