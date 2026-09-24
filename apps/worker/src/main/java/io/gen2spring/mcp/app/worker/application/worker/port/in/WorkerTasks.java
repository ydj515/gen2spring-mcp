package io.gen2spring.mcp.app.worker.application.worker.port.in;

/** Operations invoked by the worker's independent polling, heartbeat, and maintenance schedules. */
public interface WorkerTasks {
    boolean poll() throws InterruptedException;

    void heartbeat();

    void maintain();
}
