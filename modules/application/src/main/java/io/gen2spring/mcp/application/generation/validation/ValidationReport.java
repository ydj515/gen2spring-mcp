package io.gen2spring.mcp.application.generation.validation;

import java.util.List;

public record ValidationReport(
        ValidationStatus status,
        List<ValidationStageResult> stages,
        List<ObservedTool> tools,
        List<String> verifiedProtocolVersions) {
    public ValidationReport {
        verifiedProtocolVersions = List.copyOf(verifiedProtocolVersions);
    }

    public ValidationReport(ValidationStatus status, List<ValidationStageResult> stages, List<ObservedTool> tools) {
        this(status, stages, tools, List.of());
    }
}
