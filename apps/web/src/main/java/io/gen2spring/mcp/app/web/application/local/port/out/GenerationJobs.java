package io.gen2spring.mcp.app.web.application.local.port.out;

import io.gen2spring.mcp.app.web.application.local.job.JobSnapshot;
import io.gen2spring.mcp.app.web.application.local.result.ArtifactDownload;
import io.gen2spring.mcp.app.web.application.local.result.VersionedJobSnapshot;
import io.gen2spring.mcp.application.command.GenerationCommand;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Executes and retains local generation jobs independently of HTTP transport. */
public interface GenerationJobs {
    JobSnapshot submit(Path specification, GenerationCommand request, Runnable completionHook);

    JobSnapshot snapshot(String identifier);

    VersionedJobSnapshot current(String identifier);

    Optional<VersionedJobSnapshot> awaitChange(String identifier, long sinceVersion, Duration timeout)
            throws InterruptedException;

    ArtifactDownload download(String identifier, String name);

    void delete(String identifier);

}
