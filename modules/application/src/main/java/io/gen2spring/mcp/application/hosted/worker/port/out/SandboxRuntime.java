package io.gen2spring.mcp.application.hosted.worker.port.out;

import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.worker.SandboxInput;
import io.gen2spring.mcp.application.hosted.worker.SandboxLimits;
import io.gen2spring.mcp.application.hosted.worker.SandboxResult;

public interface SandboxRuntime {
    SandboxResult run(JobLease lease, SandboxInput input, SandboxLimits limits) throws InterruptedException;

    default void cancel(JobLease lease) {}

    default void removeExpired(JobLease lease) {}
}
