package io.gen2spring.mcp.domain.profile;

public enum McpImplementation {
    SPRING_AI_EXPLICIT,
    SPRING_AI_ANNOTATIONS,
    MCP_JAVA_SDK;

    public java.util.List<String> protocolVersions() {
        return this == MCP_JAVA_SDK ? java.util.List.of("2025-03-26", "2026-07-28")
                : java.util.List.of("2025-03-26");
    }

    public boolean supports(CompatibilityProfile profile) {
        if (profile == null || profile.target() == null) {
            return false;
        }
        return this != MCP_JAVA_SDK
                || ("3.5.16".equals(profile.target().springBootVersion())
                    && "MVC".equals(profile.target().webStack())
                    && "SYNC".equals(profile.target().programmingModel()));
    }
}
