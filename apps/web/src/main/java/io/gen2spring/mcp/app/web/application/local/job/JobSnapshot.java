package io.gen2spring.mcp.app.web.application.local.job;

import io.gen2spring.mcp.application.generation.usecase.ProgressStatus;
import io.gen2spring.mcp.application.generation.validation.ValidationStatus;
import java.util.List;

public record JobSnapshot(
        String id,
        State state,
        String currentStage,
        List<JobStage> stages,
        ValidationStatus validationStatus,
        JobError error,
        List<String> downloads) {
    public JobSnapshot {
        stages = List.copyOf(stages);
        downloads = List.copyOf(downloads);
    }

    public enum State { QUEUED, RUNNING, VALIDATED, UNVERIFIED, FAILED }

    public record JobStage(String stage, ProgressStatus status) {}

    public record JobError(String code, String stage, String message) {}
}
