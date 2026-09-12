package io.gen2spring.mcp.application.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.toolmodel.ToolModelFactory;
import io.gen2spring.mcp.domain.error.GeneratorErrorCode;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.profile.McpImplementation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpImplementationPlanningTest {
    @Test
    void rejectsUnsupportedSdkProfilesBeforeResolvingAnEmitter() {
        var profiles = CompatibilityProfileRegistry.defaults();
        var planner = new GenerationPlanner(new ToolModelFactory(), profiles,
                ProjectGeneratorRegistry.of(Map.of()));
        int rejected = 0;
        for (var profile : profiles.profiles()) {
            if (McpImplementation.MCP_JAVA_SDK.supports(profile)) {
                continue;
            }
            var request = new GenerationCommand(null, "provider", "api", profile.id(),
                    GenerationCommand.ValidationLevel.MCP_PROTOCOL, null, List.of(), McpImplementation.MCP_JAVA_SDK);
            var failure = assertThrows(GeneratorException.class, () -> planner.resolve(request));
            assertEquals(GeneratorErrorCode.TARGET_COMBINATION_UNSUPPORTED, failure.code());
            assertEquals("MCP Java SDK generation requires a Spring Boot 3 MVC Sync profile", failure.safeMessage());
            rejected++;
        }
        assertEquals(8, rejected);
    }
}
