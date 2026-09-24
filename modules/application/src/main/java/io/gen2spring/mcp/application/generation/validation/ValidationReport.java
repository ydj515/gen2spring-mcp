package io.gen2spring.mcp.application.generation.validation;

import java.util.List;

public record ValidationReport(
        ValidationStatus status,
        List<ValidationStageResult> stages,
        List<ObservedTool> tools) {}
