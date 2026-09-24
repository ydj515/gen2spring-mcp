package io.gen2spring.mcp.application.managed.execution.service;

import io.gen2spring.mcp.application.managed.execution.ManagedToolResult;
import io.gen2spring.mcp.application.managed.execution.port.out.ManagedExecutionTasks;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class TestManagedExecutionTasks implements ManagedExecutionTasks {
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public Future<ManagedToolResult> submit(Callable<ManagedToolResult> task) {
        return executor.submit(task);
    }

    @Override
    public void close() {
        executor.shutdownNow();
        executor.close();
    }
}
