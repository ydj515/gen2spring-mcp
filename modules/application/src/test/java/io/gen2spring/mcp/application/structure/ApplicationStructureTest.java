package io.gen2spring.mcp.application.structure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ApplicationStructureTest {
    @Test
    void ownsUseCasesPoliciesAndOutboundPortsOutsideLegacyPackages() {
        assertPresent("io.gen2spring.mcp.application.command.GenerationCommand");
        assertPresent("io.gen2spring.mcp.application.usecase.GenerationPipeline");
        assertPresent("io.gen2spring.mcp.application.planning.GenerationPlanner");
        assertPresent("io.gen2spring.mcp.application.toolmodel.ToolModelFactory");
        assertPresent("io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer");
        assertPresent("io.gen2spring.mcp.application.port.outbound.ProjectGenerator");
        assertPresent("io.gen2spring.mcp.application.port.outbound.ToolEmitter");
        assertPresent("io.gen2spring.mcp.application.port.outbound.GeneratedProjectValidator");

        assertAbsent("io.gen2spring.mcp.core.GenerationPipeline");
        assertAbsent("io.gen2spring.mcp.policy.ToolModelFactory");
    }

    private void assertPresent(String type) {
        assertDoesNotThrow(() -> Class.forName(type), type);
    }

    private void assertAbsent(String type) {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(type), type);
    }
}
