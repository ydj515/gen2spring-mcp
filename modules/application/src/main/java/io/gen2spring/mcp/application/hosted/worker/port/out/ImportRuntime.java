package io.gen2spring.mcp.application.hosted.worker.port.out;

import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.worker.SandboxLimits;
import io.gen2spring.mcp.application.hosted.worker.SandboxResult;

public interface ImportRuntime {
    SandboxResult run(JobLease lease, EncryptedImportTarget target, SandboxLimits limits)
            throws InterruptedException;

    default void cancel(JobLease lease) {}

    default void removeExpired(JobLease lease) {}
}
