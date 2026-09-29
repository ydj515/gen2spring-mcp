package io.gen2spring.mcp.domain.profile;

import java.util.List;

public enum McpProtocolMode {
    LEGACY(List.of("2025-03-26")),
    MODERN(List.of("2026-07-28")),
    DUAL(List.of("2025-03-26", "2026-07-28"));

    private final List<String> versions;

    McpProtocolMode(List<String> versions) {
        this.versions = versions;
    }

    public List<String> versions() { return versions; }
    public boolean legacy() { return this != MODERN; }
    public boolean modern() { return this != LEGACY; }
    public boolean supports(McpImplementation implementation) {
        return implementation != null && implementation.protocolVersions().containsAll(versions);
    }
}
