package io.gen2spring.mcp.application.generation.validation;

public record ValidationStageResult(
        String stage,
        StageStatus status,
        long durationMillis,
        int warningCount,
        int errorCount,
        String summary) {}
