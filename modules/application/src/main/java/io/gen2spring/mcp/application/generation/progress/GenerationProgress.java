package io.gen2spring.mcp.application.generation.progress;

import java.util.List;

public record GenerationProgress(String stage, ProgressStatus status) {
    public static final List<String> STAGES = List.of(
            "ANALYZE",
            "GENERATE",
            "COMPILE",
            "APPLICATION_CONTEXT",
            "MCP_INITIALIZE",
            "MCP_TOOLS_LIST",
            "MCP_TOOL_CALL",
            "PACKAGE");

    public GenerationProgress {
        if (!STAGES.contains(stage) || status == null) {
            throw new IllegalArgumentException("Generation progress is invalid");
        }
    }
}
