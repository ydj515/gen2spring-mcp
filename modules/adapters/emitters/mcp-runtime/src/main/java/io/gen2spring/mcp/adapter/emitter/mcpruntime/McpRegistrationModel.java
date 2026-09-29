package io.gen2spring.mcp.adapter.emitter.mcpruntime;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.McpImplementation;
import io.gen2spring.mcp.domain.profile.McpProtocolMode;
import java.util.List;
import java.util.Objects;

public record McpRegistrationModel(
        String packageName,
        String domainClass,
        String artifactId,
        CompatibilityProfile profile,
        McpImplementation implementation,
        List<Tool> tools,
        McpProtocolMode protocol) {
    public McpRegistrationModel {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(domainClass);
        Objects.requireNonNull(artifactId);
        Objects.requireNonNull(profile);
        Objects.requireNonNull(implementation);
        tools = List.copyOf(tools);
        if (!implementation.supports(profile) || protocol == null || !protocol.supports(implementation)) {
            throw new IllegalArgumentException("Unsupported MCP implementation profile");
        }
    }

    public McpRegistrationModel(String packageName, String domainClass, String artifactId,
            CompatibilityProfile profile, McpImplementation implementation, List<Tool> tools) {
        this(packageName, domainClass, artifactId, profile, implementation, tools, McpProtocolMode.LEGACY);
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
