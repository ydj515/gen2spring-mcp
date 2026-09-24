package io.gen2spring.mcp.app.web.application.local.result;

import io.gen2spring.mcp.app.web.application.local.job.JobSnapshot;

public record VersionedJobSnapshot(long version, JobSnapshot snapshot) {}
