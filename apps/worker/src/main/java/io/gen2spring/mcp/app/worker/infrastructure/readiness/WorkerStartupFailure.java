package io.gen2spring.mcp.app.worker.infrastructure.readiness;

public final class WorkerStartupFailure extends RuntimeException {
    WorkerStartupFailure(Throwable cause) {
        super("Hosted worker dependencies are unavailable", cause, false, false);
    }
}
