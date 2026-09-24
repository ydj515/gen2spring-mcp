package io.gen2spring.mcp.application.hosted.worker.port.out;

import io.gen2spring.mcp.application.hosted.job.WorkerId;
import java.time.Instant;

public interface WorkerHeartbeatStore {
    void beat(WorkerId worker, Instant observedAt);

    boolean hasRecentHeartbeat(Instant notBefore);
}
