package io.gen2spring.mcp.app.worker.application.worker.service;

import io.gen2spring.mcp.app.worker.application.worker.port.in.WorkerTasks;
import io.gen2spring.mcp.application.hosted.job.WorkerLeaseService;
import io.gen2spring.mcp.application.hosted.worker.ArtifactRetentionService;
import io.gen2spring.mcp.application.hosted.worker.HostedWorker;
import java.util.Objects;

public final class WorkerTaskService implements WorkerTasks {
    private static final int RETENTION_BATCH_SIZE = 100;

    private final HostedWorker worker;
    private final WorkerHeartbeatPublisher heartbeats;
    private final WorkerLeaseService leases;
    private final ArtifactRetentionService retention;

    public WorkerTaskService(
            HostedWorker worker,
            WorkerHeartbeatPublisher heartbeats,
            WorkerLeaseService leases,
            ArtifactRetentionService retention) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.heartbeats = Objects.requireNonNull(heartbeats, "heartbeats");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.retention = Objects.requireNonNull(retention, "retention");
    }

    @Override
    public boolean poll() throws InterruptedException {
        return worker.pollOnce() != HostedWorker.PollResult.EMPTY;
    }

    @Override
    public void heartbeat() {
        heartbeats.publishIfDue();
    }

    @Override
    public void maintain() {
        leases.recoverExpired();
        retention.sweep(RETENTION_BATCH_SIZE);
    }
}
