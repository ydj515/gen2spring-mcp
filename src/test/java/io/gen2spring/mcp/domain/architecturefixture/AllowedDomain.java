package io.gen2spring.mcp.domain.architecturefixture;

public record AllowedDomain(String value) {
    public String example() {
        return "io.gen2spring.mcp.application.architecturefixture.ApplicationTarget";
    }
}
