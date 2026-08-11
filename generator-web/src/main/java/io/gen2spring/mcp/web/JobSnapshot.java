package io.gen2spring.mcp.web;

import io.gen2spring.mcp.domain.generation.GenerationContracts.ProgressStatus;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus;
import java.util.List;

record JobSnapshot(
        String id,
        State state,
        String currentStage,
        List<JobStage> stages,
        ValidationStatus validationStatus,
        JobError error,
        List<String> downloads) {
    JobSnapshot {
        stages = List.copyOf(stages);
        downloads = List.copyOf(downloads);
    }

    enum State { QUEUED, RUNNING, VALIDATED, UNVERIFIED, FAILED }

    record JobStage(String stage, ProgressStatus status) {}

    record JobError(String code, String stage, String message) {}
}
