package io.gen2spring.mcp.adapter.emitter.mcpruntime;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.McpImplementation;
import java.util.List;
import java.util.Objects;

public record McpRegistrationModel(
        String packageName,
        String domainClass,
        String artifactId,
        CompatibilityProfile profile,
        McpImplementation implementation,
        List<Tool> tools) {
    public McpRegistrationModel {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(domainClass);
        Objects.requireNonNull(artifactId);
        Objects.requireNonNull(profile);
        Objects.requireNonNull(implementation);
        tools = List.copyOf(tools);
        if (!implementation.supports(profile)) {
            throw new IllegalArgumentException("Unsupported MCP implementation profile");
        }
    }

    public boolean reactive() {
        return "ASYNC".equals(profile.target().programmingModel());
    }

    public boolean jackson3() {
        return profile.target().springBootVersion().startsWith("4.");
    }

    public record Tool(String name, String description, String operationId,
                       String operationConstant, String resultClass, String inputSchema) {}
}
