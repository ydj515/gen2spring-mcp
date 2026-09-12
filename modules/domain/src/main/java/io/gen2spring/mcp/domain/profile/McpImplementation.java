package io.gen2spring.mcp.domain.profile;

public enum McpImplementation {
    SPRING_AI_EXPLICIT,
    SPRING_AI_ANNOTATIONS,
    MCP_JAVA_SDK;

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
