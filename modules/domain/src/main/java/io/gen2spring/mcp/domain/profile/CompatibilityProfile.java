package io.gen2spring.mcp.domain.profile;

public record CompatibilityProfile(
        String id,
        TargetPlatform target,
        String generatorModule,
        String templateVersion,
        String runtimeVersion,
        BuildToolchain buildToolchain,
        String containerImage) {
    public CompatibilityProfile(
            String id,
            TargetPlatform target,
            String generatorModule,
            String templateVersion,
            String runtimeVersion,
            String gradleVersion,
            String containerImage) {
        this(
                id,
                target,
                generatorModule,
                templateVersion,
                runtimeVersion,
                new BuildToolchain(gradleVersion, gradleVersion),
                containerImage);
    }

    public static CompatibilityProfile p0() {
        return CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java21-mvc-streamable")
                .orElseThrow();
    }

    public boolean supports(TargetPlatform candidate) {
        return target.equals(candidate);
    }

    public String gradleVersion() {
        if (target == null || !"GRADLE_KOTLIN".equals(target.buildTool()) || buildToolchain == null) {
            return null;
        }
        return buildToolchain.distributionVersion();
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
