package io.gen2spring.mcp.application.structure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ApplicationStructureTest {
    @Test
    void ownsUseCasesPoliciesAndOutboundPortsOutsideLegacyPackages() {
        assertPresent("io.gen2spring.mcp.application.generation.command.GenerationCommand");
        assertPresent("io.gen2spring.mcp.application.generation.usecase.GenerationPipeline");
        assertPresent("io.gen2spring.mcp.application.generation.planning.GenerationPlanner");
        assertPresent("io.gen2spring.mcp.application.toolmodel.ToolModelFactory");
        assertPresent("io.gen2spring.mcp.application.generation.port.out.SpecificationAnalyzer");
        assertPresent("io.gen2spring.mcp.application.generation.port.out.ProjectGenerator");
        assertPresent("io.gen2spring.mcp.application.generation.port.out.ToolEmitter");
        assertPresent("io.gen2spring.mcp.application.generation.port.out.GeneratedProjectValidator");
        assertPresent("io.gen2spring.mcp.application.hosted.job.port.out.JobQueue");
        assertPresent("io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage");
        assertPresent("io.gen2spring.mcp.application.managed.runtime.port.out.ManagedRuntimeStore");
        assertPresent("io.gen2spring.mcp.application.managed.execution.port.in.ManagedToolCallHandler");
        assertPresent("io.gen2spring.mcp.application.toolmodel.observability.RuntimeObservabilityContract");

        assertAbsent("io.gen2spring.mcp.adapter.filesystem.GenerationPipeline");
        assertAbsent("io.gen2spring.mcp.policy.ToolModelFactory");
        assertAbsent("io.gen2spring.mcp.application.hosted.job.JobQueue");
        assertAbsent("io.gen2spring.mcp.application.usecase.GenerationPipeline");
        assertAbsent("io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer");
        assertAbsent("io.gen2spring.mcp.domain.observability.RuntimeObservabilityContract");
    }

    private void assertPresent(String type) {
        assertDoesNotThrow(() -> Class.forName(type), type);
    }

    private void assertAbsent(String type) {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(type), type);
    }
}
