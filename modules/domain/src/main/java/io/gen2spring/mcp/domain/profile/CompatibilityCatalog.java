package io.gen2spring.mcp.domain.profile;

import java.util.List;
import java.util.Objects;

public record CompatibilityCatalog(
        CompatibilityProfileRegistry profiles,
        List<CompatibilityNotice> notices) {
    private static final String JAVA_17_IMAGE = "eclipse-temurin:17.0.19_10-jre-noble@sha256:"
            + "543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8";
    private static final String JAVA_21_IMAGE = "eclipse-temurin:21.0.11_10-jre-noble@sha256:"
            + "373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64";

    public CompatibilityCatalog {
        Objects.requireNonNull(profiles, "profiles");
        notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
    }

    public static CompatibilityCatalog defaults() {
        return Defaults.INSTANCE;
    }

    private static CompatibilityProfile profile(
            String family,
            String springBootVersion,
            String springAiVersion,
            String generatorModule,
            String templateVersion,
            int javaVersion,
            String buildTool,
            String containerImage) {
        return profile(
                family, springBootVersion, springAiVersion, generatorModule, templateVersion,
                javaVersion, buildTool, "MVC", "SYNC", containerImage);
    }

    private static CompatibilityProfile profile(
            String family,
            String springBootVersion,
            String springAiVersion,
            String generatorModule,
            String templateVersion,
            int javaVersion,
            String buildTool,
            String webStack,
            String programmingModel,
            String containerImage) {
        boolean maven = "MAVEN".equals(buildTool);
        boolean webFlux = "WEBFLUX".equals(webStack);
        return new CompatibilityProfile(
                family + "-java" + javaVersion + (maven ? "-maven" : "") + "-"
                        + (webFlux ? "webflux-async" : "mvc") + "-streamable",
                new CompatibilityProfile.TargetPlatform(
                        javaVersion,
                        springBootVersion,
                        springAiVersion,
                        buildTool,
                        webStack,
                        programmingModel,
                        "STREAMABLE_HTTP"),
                generatorModule,
                templateVersion,
                "0.3.0",
                maven
                        ? new BuildToolchain("3.9.16", "3.3.4")
                        : new BuildToolchain("9.6.1", "9.6.1"),
                containerImage);
    }

    private static CompatibilityNotice deferredSpringAi1WebFluxAsyncNotice() {
        return new CompatibilityNotice(
                "SPRING_AI_1_WEBFLUX_ASYNC_DEFERRED",
                "WARNING",
                "Spring AI 1.1 WebFlux Async generation is deferred",
                "Spring AI 1.1.8 uses an MCP transport that can fail when a Streamable HTTP SSE message ID is null.",
                "https://github.com/spring-projects/spring-ai/issues/6274",
                new CompatibilityNotice.AffectedTarget(
                        "SPRING_AI_1_1", "WEBFLUX", "ASYNC", "STREAMABLE_HTTP"));
    }

    private static final class Defaults {
        private static final CompatibilityCatalog INSTANCE = new CompatibilityCatalog(
                CompatibilityProfileRegistry.of(List.of(
                        profile("spring-ai-1.1", "3.5.16", "1.1.8", "generator-spring-ai-1",
                                "spring-ai-1-v2", 17, "GRADLE_KOTLIN", JAVA_17_IMAGE),
                        profile("spring-ai-1.1", "3.5.16", "1.1.8", "generator-spring-ai-1",
                                "spring-ai-1-v2", 17, "MAVEN", JAVA_17_IMAGE),
                        profile("spring-ai-1.1", "3.5.16", "1.1.8", "generator-spring-ai-1",
                                "spring-ai-1-v2", 21, "GRADLE_KOTLIN", JAVA_21_IMAGE),
                        profile("spring-ai-1.1", "3.5.16", "1.1.8", "generator-spring-ai-1",
                                "spring-ai-1-v2", 21, "MAVEN", JAVA_21_IMAGE),
                        profile("spring-ai-2.0", "4.1.0", "2.0.0", "generator-spring-ai-2",
                                "spring-ai-2-v3", 17, "GRADLE_KOTLIN", JAVA_17_IMAGE),
                        profile("spring-ai-2.0", "4.1.0", "2.0.0", "generator-spring-ai-2",
                                "spring-ai-2-v3", 17, "MAVEN", JAVA_17_IMAGE),
                        profile("spring-ai-2.0", "4.1.0", "2.0.0", "generator-spring-ai-2",
                                "spring-ai-2-v3", 17, "GRADLE_KOTLIN", "WEBFLUX", "ASYNC", JAVA_17_IMAGE),
                        profile("spring-ai-2.0", "4.1.0", "2.0.0", "generator-spring-ai-2",
                                "spring-ai-2-v3", 17, "MAVEN", "WEBFLUX", "ASYNC", JAVA_17_IMAGE),
                        profile("spring-ai-2.0", "4.1.0", "2.0.0", "generator-spring-ai-2",
                                "spring-ai-2-v3", 21, "GRADLE_KOTLIN", JAVA_21_IMAGE),
                        profile("spring-ai-2.0", "4.1.0", "2.0.0", "generator-spring-ai-2",
                                "spring-ai-2-v3", 21, "MAVEN", JAVA_21_IMAGE),
                        profile("spring-ai-2.0", "4.1.0", "2.0.0", "generator-spring-ai-2",
                                "spring-ai-2-v3", 21, "GRADLE_KOTLIN", "WEBFLUX", "ASYNC", JAVA_21_IMAGE),
                        profile("spring-ai-2.0", "4.1.0", "2.0.0", "generator-spring-ai-2",
                                "spring-ai-2-v3", 21, "MAVEN", "WEBFLUX", "ASYNC", JAVA_21_IMAGE))),
                List.of(deferredSpringAi1WebFluxAsyncNotice()));

        private Defaults() {}
    }
}
