package io.gen2spring.mcp.application.validation;

import java.util.List;

public record ValidationReport(
        ValidationStatus status,
        List<ValidationStageResult> stages,
        List<ObservedTool> tools) {}
