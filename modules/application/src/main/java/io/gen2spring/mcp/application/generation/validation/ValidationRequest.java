package io.gen2spring.mcp.application.generation.validation;

import io.gen2spring.mcp.application.generation.command.GenerationCommand.ValidationLevel;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.McpImplementation;
import io.gen2spring.mcp.domain.profile.McpProtocolMode;
import java.nio.file.Path;
import java.util.Map;

public record ValidationRequest(
        Path projectRoot,
        String artifactId,
        ValidationLevel level,
        Map<String, ExpectedTool> expectedTools,
        ExpectedToolCall expectedToolCall,
        CompatibilityProfile profile,
        McpImplementation implementation,
        McpProtocolMode protocol) {
    public ValidationRequest {
        if (expectedToolCall == null || profile == null || implementation == null) {
            throw new IllegalArgumentException("Validation request expected Tool call is incomplete");
        }
        if (!implementation.supports(profile) || protocol == null || !protocol.supports(implementation)) {
            throw new IllegalArgumentException("Validation request MCP implementation is unsupported");
        }
    }

    public ValidationRequest(Path projectRoot, String artifactId, ValidationLevel level,
            Map<String, ExpectedTool> expectedTools, ExpectedToolCall expectedToolCall, CompatibilityProfile profile,
            McpImplementation implementation) {
        this(projectRoot, artifactId, level, expectedTools, expectedToolCall, profile, implementation, McpProtocolMode.LEGACY);
    }

    public ValidationRequest(Path projectRoot, String artifactId, ValidationLevel level,
            Map<String, ExpectedTool> expectedTools, ExpectedToolCall expectedToolCall, CompatibilityProfile profile) {
        this(projectRoot, artifactId, level, expectedTools, expectedToolCall, profile,
                McpImplementation.SPRING_AI_EXPLICIT);
    }

    public ValidationRequest(
            Path projectRoot,
            String artifactId,
            ValidationLevel level,
            Map<String, ExpectedTool> expectedTools,
            ExpectedToolCall expectedToolCall) {
        this(projectRoot, artifactId, level, expectedTools, expectedToolCall, CompatibilityProfile.p0());
    }
}
