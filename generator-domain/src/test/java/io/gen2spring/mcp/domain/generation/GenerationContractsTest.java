package io.gen2spring.mcp.domain.generation;

import static io.gen2spring.mcp.domain.generation.GenerationContracts.ProgressStatus.PENDING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationProgress;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationProgressListener;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenerationContractsTest {
    @Test
    void acceptsOnlyTheEightFixedProgressStagesAndFiveStatuses() {
        assertEquals(List.of(
                "ANALYZE", "GENERATE", "COMPILE", "APPLICATION_CONTEXT",
                "MCP_INITIALIZE", "MCP_TOOLS_LIST", "MCP_TOOL_CALL", "PACKAGE"),
                GenerationProgress.STAGES);
        GenerationProgress.STAGES.forEach(stage ->
                assertDoesNotThrow(() -> new GenerationProgress(stage, PENDING)));
        assertThrows(IllegalArgumentException.class, () -> new GenerationProgress("private-stage", PENDING));
        assertThrows(IllegalArgumentException.class, () -> new GenerationProgress("ANALYZE", null));
        assertDoesNotThrow(() -> GenerationProgressListener.NOOP.onProgress(
                new GenerationProgress("ANALYZE", PENDING)));
    }
}
