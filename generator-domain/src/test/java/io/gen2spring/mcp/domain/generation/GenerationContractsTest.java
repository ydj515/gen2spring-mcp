package io.gen2spring.mcp.domain.generation;

import static io.gen2spring.mcp.domain.generation.GenerationContracts.ProgressStatus.PENDING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationProgress;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationProgressListener;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedToolSources;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Test
    void ownsSortedDefensiveCopiesOfSafeToolSourceFiles() {
        byte[] first = {1};
        byte[] second = {2};
        GeneratedToolSources sources = new GeneratedToolSources(Map.of(
                "src/main/java/example/B.java", second,
                "src/main/java/example/A.java", first));

        first[0] = 9;
        second[0] = 9;
        assertEquals(List.of(
                        "src/main/java/example/A.java",
                        "src/main/java/example/B.java"),
                new ArrayList<>(sources.files().keySet()));
        assertArrayEquals(new byte[] {1}, sources.files().get("src/main/java/example/A.java"));
        assertArrayEquals(new byte[] {2}, sources.files().get("src/main/java/example/B.java"));

        byte[] returned = sources.files().get("src/main/java/example/A.java");
        returned[0] = 7;
        assertArrayEquals(new byte[] {1}, sources.files().get("src/main/java/example/A.java"));
        assertThrows(UnsupportedOperationException.class,
                () -> sources.files().put("src/main/java/example/C.java", new byte[] {3}));
    }

    @Test
    void rejectsUnsafeToolSourcePathsAndMissingBytesWithOneFixedMessage() {
        for (String path : List.of(
                "", "   ", "/absolute.java", "C:/absolute.java", "src//Empty.java",
                "src/./Current.java", "src/../Parent.java", "src\\Backslash.java", "src/Control\n.java")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> new GeneratedToolSources(Map.of(path, new byte[] {1})),
                    path);
            assertEquals("Generated Tool sources are invalid", failure.getMessage());
        }

        Map<String, byte[]> missingBytes = new LinkedHashMap<>();
        missingBytes.put("src/main/java/example/Missing.java", null);
        assertEquals("Generated Tool sources are invalid", assertThrows(
                IllegalArgumentException.class,
                () -> new GeneratedToolSources(missingBytes)).getMessage());
        Map<String, byte[]> missingPath = new LinkedHashMap<>();
        missingPath.put(null, new byte[] {1});
        assertEquals("Generated Tool sources are invalid", assertThrows(
                IllegalArgumentException.class,
                () -> new GeneratedToolSources(missingPath)).getMessage());
        assertEquals("Generated Tool sources are invalid", assertThrows(
                IllegalArgumentException.class,
                () -> new GeneratedToolSources(null)).getMessage());
    }
}
