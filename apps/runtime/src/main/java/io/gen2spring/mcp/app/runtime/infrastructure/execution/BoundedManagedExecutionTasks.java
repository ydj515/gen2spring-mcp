package io.gen2spring.mcp.app.runtime.infrastructure.execution;

import io.gen2spring.mcp.application.managed.execution.ManagedExecutionLimits;
import io.gen2spring.mcp.application.managed.execution.ManagedToolResult;
import io.gen2spring.mcp.application.managed.execution.port.out.ManagedExecutionTasks;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class BoundedManagedExecutionTasks implements ManagedExecutionTasks {
    private final ThreadPoolExecutor executor;

    public BoundedManagedExecutionTasks(ManagedExecutionLimits limits) {
        Objects.requireNonNull(limits, "limits");
        executor = new ThreadPoolExecutor(
                limits.workers(), limits.workers(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "managed-provider-call");
                    thread.setDaemon(false);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public Future<ManagedToolResult> submit(Callable<ManagedToolResult> task) {
        return executor.submit(task);
    }

    @Override
    public void close() {
        for (Runnable pending : executor.shutdownNow()) {
            if (pending instanceof Future<?> future) future.cancel(true);
        }
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Managed Tool executor did not stop");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Managed Tool executor did not stop", failure);
        }
    }
}
