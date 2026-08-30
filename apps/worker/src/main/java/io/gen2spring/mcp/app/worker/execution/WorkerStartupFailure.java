package io.gen2spring.mcp.app.worker.execution;

public final class WorkerStartupFailure extends RuntimeException {
    WorkerStartupFailure() {
        super("Hosted worker dependencies are unavailable", null, false, false);
    }
}
