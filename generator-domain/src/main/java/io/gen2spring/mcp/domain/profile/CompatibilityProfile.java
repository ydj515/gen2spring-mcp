package io.gen2spring.mcp.domain.profile;

public record CompatibilityProfile(
        String id,
        TargetPlatform target,
        String generatorModule,
        String templateVersion,
        String runtimeVersion) {
    public static CompatibilityProfile p0() {
        return new CompatibilityProfile(
                "spring-ai-2.0-java21-mvc-streamable",
                new TargetPlatform(21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP"),
                "generator-spring-ai-2", "spring-ai-2-v1", "0.1.0");
    }

    public boolean supports(TargetPlatform candidate) {
        return target.equals(candidate);
    }

    public record TargetPlatform(
            int javaVersion,
            String springBootVersion,
            String springAiVersion,
            String buildTool,
            String webStack,
            String programmingModel,
            String transport) {}
}
