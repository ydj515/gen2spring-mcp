package io.gen2spring.mcp.application.hosted.worker;

import io.gen2spring.mcp.application.hosted.job.JobLease;

public interface SandboxRuntime {
    SandboxResult run(JobLease lease, SandboxInput input, SandboxLimits limits) throws InterruptedException;

    default void cancel(JobLease lease) {}

    default void removeExpired(JobLease lease) {}
}
