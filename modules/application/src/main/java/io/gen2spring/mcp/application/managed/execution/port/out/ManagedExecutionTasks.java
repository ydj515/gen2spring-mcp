package io.gen2spring.mcp.application.managed.execution.port.out;

import io.gen2spring.mcp.application.managed.execution.ManagedToolResult;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/** Execution capacity owned exclusively by one managed Tool executor. */
public interface ManagedExecutionTasks extends AutoCloseable {
    /**
     * Schedules work without blocking the caller for capacity.
     * The returned future must support interruption through cancellation.
     *
     * @throws java.util.concurrent.RejectedExecutionException when capacity is exhausted or closed
     */
    Future<ManagedToolResult> submit(Callable<ManagedToolResult> task);

    /** Cancels pending work and stops owned resources; repeated calls are safe. */
    @Override
    void close();
}
