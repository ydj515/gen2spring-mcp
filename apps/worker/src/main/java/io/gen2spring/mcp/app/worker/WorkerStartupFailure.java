package io.gen2spring.mcp.app.worker;

final class WorkerStartupFailure extends RuntimeException {
    WorkerStartupFailure() {
        super("Hosted worker dependencies are unavailable", null, false, false);
    }
}
