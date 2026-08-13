package io.gen2spring.mcp.application.hosted.job;

import java.util.Objects;

public record CreateJobResult(JobView job, boolean replayed) {
    public CreateJobResult {
        Objects.requireNonNull(job, "job");
    }
}
