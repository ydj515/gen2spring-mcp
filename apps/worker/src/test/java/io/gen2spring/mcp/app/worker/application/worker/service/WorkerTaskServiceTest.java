package io.gen2spring.mcp.app.worker.application.worker.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.application.hosted.job.WorkerLeaseService;
import io.gen2spring.mcp.application.hosted.worker.ArtifactRetentionService;
import io.gen2spring.mcp.application.hosted.worker.HostedWorker;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

final class WorkerTaskServiceTest {
    @Test
    void delegatesPollingAndKeepsMaintenanceOrder() throws InterruptedException {
        HostedWorker worker = mock(HostedWorker.class);
        WorkerHeartbeatPublisher heartbeats = mock(WorkerHeartbeatPublisher.class);
        WorkerLeaseService leases = mock(WorkerLeaseService.class);
        ArtifactRetentionService retention = mock(ArtifactRetentionService.class);
        WorkerTaskService tasks = new WorkerTaskService(worker, heartbeats, leases, retention);

        when(worker.pollOnce()).thenReturn(HostedWorker.PollResult.EMPTY, HostedWorker.PollResult.STALE);
        assertFalse(tasks.poll());
        assertTrue(tasks.poll());

        tasks.heartbeat();
        verify(heartbeats).publishIfDue();

        tasks.maintain();
        InOrder order = inOrder(leases, retention);
        order.verify(leases).recoverExpired();
        order.verify(retention).sweep(100);
    }
}
