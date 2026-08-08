package io.gen2spring.mcp.domain.config;

import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import java.util.List;
import java.util.Map;

public record GenerationRequest(
        ProjectCoordinates project,
        String provider,
        String domain,
        String targetProfileId,
        ValidationLevel validationLevel,
        List<OperationSelection> operations) {
    public enum ValidationLevel { MCP_PROTOCOL }

    public record ProjectCoordinates(String groupId, String artifactId, String packageName) {}

    public record OperationSelection(
            String operationId,
            boolean enabled,
            String toolName,
            String toolDescription,
            Map<String, ParameterOverride> parameters) {}

    public record ParameterOverride(
            McpToolDefinition.ParameterSource source,
            String environmentVariable) {}
}
