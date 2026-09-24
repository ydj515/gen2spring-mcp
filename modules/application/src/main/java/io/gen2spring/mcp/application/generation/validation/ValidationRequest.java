package io.gen2spring.mcp.application.generation.validation;

import io.gen2spring.mcp.application.generation.command.GenerationCommand.ValidationLevel;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.nio.file.Path;
import java.util.Map;

public record ValidationRequest(
        Path projectRoot,
        String artifactId,
        ValidationLevel level,
        Map<String, ExpectedTool> expectedTools,
        ExpectedToolCall expectedToolCall,
        CompatibilityProfile profile) {
    public ValidationRequest {
        if (expectedToolCall == null || profile == null) {
            throw new IllegalArgumentException("Validation request expected Tool call is incomplete");
        }
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
