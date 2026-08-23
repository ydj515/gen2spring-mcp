package io.gen2spring.mcp.domain.profile;

public record CompatibilityNotice(
        String code,
        String severity,
        String summary,
        String reason,
        String referenceUrl,
        AffectedTarget affectedTarget) {
    public record AffectedTarget(
            String springAiFamily,
            String webStack,
            String programmingModel,
            String transport) {}
}
