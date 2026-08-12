package io.gen2spring.mcp.domain.structure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DomainStructureTest {
    @Test
    void exposesFocusedDomainTypesWithoutLegacyContainers() {
        assertPresent("io.gen2spring.mcp.domain.specification.OpenApiDocument");
        assertPresent("io.gen2spring.mcp.domain.tool.ToolDefinition");
        assertPresent("io.gen2spring.mcp.domain.tool.ToolInput");
        assertPresent("io.gen2spring.mcp.domain.tool.ToolOutput");
        assertPresent("io.gen2spring.mcp.domain.tool.HttpExecution");

        assertAbsent("io.gen2spring.mcp.domain.generation.GenerationContracts");
        assertAbsent("io.gen2spring.mcp.domain.config.GenerationRequest");
        assertAbsent("io.gen2spring.mcp.domain.openapi.OpenApiDocument");
        assertAbsent("io.gen2spring.mcp.domain.tool.McpToolDefinition");
    }

    private void assertPresent(String type) {
        assertDoesNotThrow(() -> Class.forName(type), type);
    }

    private void assertAbsent(String type) {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(type), type);
    }
}
