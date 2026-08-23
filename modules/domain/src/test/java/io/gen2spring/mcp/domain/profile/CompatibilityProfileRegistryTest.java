package io.gen2spring.mcp.domain.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompatibilityProfileRegistryTest {
    private static final String INVALID_PROFILES_MESSAGE = "Compatibility profiles are invalid";
    private static final String JAVA_17_IMAGE = "eclipse-temurin:17.0.19_10-jre-noble@sha256:"
            + "543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8";
    private static final String JAVA_21_IMAGE = "eclipse-temurin:21.0.11_10-jre-noble@sha256:"
            + "373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64";

    @Test
    void exposesThePinnedProfilesInAscendingIdOrder() {
        var registry = CompatibilityProfileRegistry.defaults();

        assertEquals(List.of(
                "spring-ai-1.1-java17-maven-mvc-streamable",
                "spring-ai-1.1-java17-mvc-streamable",
                "spring-ai-1.1-java21-maven-mvc-streamable",
                "spring-ai-1.1-java21-mvc-streamable",
                "spring-ai-2.0-java17-maven-mvc-streamable",
                "spring-ai-2.0-java17-maven-webflux-async-streamable",
                "spring-ai-2.0-java17-mvc-streamable",
                "spring-ai-2.0-java17-webflux-async-streamable",
                "spring-ai-2.0-java21-maven-mvc-streamable",
                "spring-ai-2.0-java21-maven-webflux-async-streamable",
                "spring-ai-2.0-java21-mvc-streamable",
                "spring-ai-2.0-java21-webflux-async-streamable"),
                registry.profiles().stream().map(CompatibilityProfile::id).toList());

        assertProfile(registry.find("spring-ai-1.1-java17-maven-mvc-streamable").orElseThrow(),
                "spring-ai-1.1-java17-maven-mvc-streamable", 17, "3.5.16", "1.1.8",
                "MAVEN", "3.9.16", "3.3.4",
                "generator-spring-ai-1", "spring-ai-1-v2", JAVA_17_IMAGE);
        assertProfile(registry.find("spring-ai-1.1-java17-mvc-streamable").orElseThrow(),
                "spring-ai-1.1-java17-mvc-streamable", 17, "3.5.16", "1.1.8",
                "GRADLE_KOTLIN", "9.6.1", "9.6.1",
                "generator-spring-ai-1", "spring-ai-1-v2", JAVA_17_IMAGE);
        assertProfile(registry.find("spring-ai-1.1-java21-maven-mvc-streamable").orElseThrow(),
                "spring-ai-1.1-java21-maven-mvc-streamable", 21, "3.5.16", "1.1.8",
                "MAVEN", "3.9.16", "3.3.4",
                "generator-spring-ai-1", "spring-ai-1-v2", JAVA_21_IMAGE);
        assertProfile(registry.find("spring-ai-1.1-java21-mvc-streamable").orElseThrow(),
                "spring-ai-1.1-java21-mvc-streamable", 21, "3.5.16", "1.1.8",
                "GRADLE_KOTLIN", "9.6.1", "9.6.1",
                "generator-spring-ai-1", "spring-ai-1-v2", JAVA_21_IMAGE);
        assertProfile(registry.find("spring-ai-2.0-java17-maven-mvc-streamable").orElseThrow(),
                "spring-ai-2.0-java17-maven-mvc-streamable", 17, "4.1.0", "2.0.0",
                "MAVEN", "3.9.16", "3.3.4",
                "generator-spring-ai-2", "spring-ai-2-v3", JAVA_17_IMAGE);
        assertProfile(registry.find("spring-ai-2.0-java17-mvc-streamable").orElseThrow(),
                "spring-ai-2.0-java17-mvc-streamable", 17, "4.1.0", "2.0.0",
                "GRADLE_KOTLIN", "9.6.1", "9.6.1",
                "generator-spring-ai-2", "spring-ai-2-v3", JAVA_17_IMAGE);
        assertProfile(registry.find("spring-ai-2.0-java21-maven-mvc-streamable").orElseThrow(),
                "spring-ai-2.0-java21-maven-mvc-streamable", 21, "4.1.0", "2.0.0",
                "MAVEN", "3.9.16", "3.3.4",
                "generator-spring-ai-2", "spring-ai-2-v3", JAVA_21_IMAGE);
        assertProfile(registry.find("spring-ai-2.0-java21-mvc-streamable").orElseThrow(),
                "spring-ai-2.0-java21-mvc-streamable", 21, "4.1.0", "2.0.0",
                "GRADLE_KOTLIN", "9.6.1", "9.6.1",
                "generator-spring-ai-2", "spring-ai-2-v3", JAVA_21_IMAGE);
        assertProfile(registry.find("spring-ai-2.0-java17-maven-webflux-async-streamable").orElseThrow(),
                "spring-ai-2.0-java17-maven-webflux-async-streamable", 17, "4.1.0", "2.0.0",
                "MAVEN", "WEBFLUX", "ASYNC", "3.9.16", "3.3.4",
                "generator-spring-ai-2", "spring-ai-2-v3", JAVA_17_IMAGE);
        assertProfile(registry.find("spring-ai-2.0-java17-webflux-async-streamable").orElseThrow(),
                "spring-ai-2.0-java17-webflux-async-streamable", 17, "4.1.0", "2.0.0",
                "GRADLE_KOTLIN", "WEBFLUX", "ASYNC", "9.6.1", "9.6.1",
                "generator-spring-ai-2", "spring-ai-2-v3", JAVA_17_IMAGE);
        assertProfile(registry.find("spring-ai-2.0-java21-maven-webflux-async-streamable").orElseThrow(),
                "spring-ai-2.0-java21-maven-webflux-async-streamable", 21, "4.1.0", "2.0.0",
                "MAVEN", "WEBFLUX", "ASYNC", "3.9.16", "3.3.4",
                "generator-spring-ai-2", "spring-ai-2-v3", JAVA_21_IMAGE);
        assertProfile(registry.find("spring-ai-2.0-java21-webflux-async-streamable").orElseThrow(),
                "spring-ai-2.0-java21-webflux-async-streamable", 21, "4.1.0", "2.0.0",
                "GRADLE_KOTLIN", "WEBFLUX", "ASYNC", "9.6.1", "9.6.1",
                "generator-spring-ai-2", "spring-ai-2-v3", JAVA_21_IMAGE);
    }

    @Test
    void returnsCanonicalProfilesByIdWithoutFallback() {
        var registry = CompatibilityProfileRegistry.defaults();
        for (String id : List.of(
                "spring-ai-1.1-java17-maven-mvc-streamable",
                "spring-ai-1.1-java17-mvc-streamable",
                "spring-ai-1.1-java21-maven-mvc-streamable",
                "spring-ai-1.1-java21-mvc-streamable",
                "spring-ai-2.0-java17-maven-mvc-streamable",
                "spring-ai-2.0-java17-maven-webflux-async-streamable",
                "spring-ai-2.0-java17-mvc-streamable",
                "spring-ai-2.0-java17-webflux-async-streamable",
                "spring-ai-2.0-java21-maven-mvc-streamable",
                "spring-ai-2.0-java21-maven-webflux-async-streamable",
                "spring-ai-2.0-java21-mvc-streamable",
                "spring-ai-2.0-java21-webflux-async-streamable")) {
            var found = registry.find(id);
            assertTrue(found.isPresent(), id);
            CompatibilityProfile listed = registry.profiles().stream()
                    .filter(profile -> id.equals(profile.id()))
                    .findFirst()
                    .orElseThrow();
            assertSame(listed, found.orElseThrow(), id);
        }
        assertSame(registry.find("spring-ai-2.0-java21-mvc-streamable").orElseThrow(), CompatibilityProfile.p0());
        assertFalse(registry.find("spring-ai-2.0-java99-mvc-streamable").isPresent());
        assertFalse(registry.find(null).isPresent());
        assertFalse(registry.find("  ").isPresent());
    }

    @Test
    void defensivelyCopiesTheInputAndDoesNotExposeMutableCollections() {
        var java21 = profile(
                "spring-ai-2.0-java21-mvc-streamable",
                new CompatibilityProfile.TargetPlatform(
                        21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP"));
        var input = new ArrayList<>(List.of(java21));

        var registry = CompatibilityProfileRegistry.of(input);
        input.clear();

        assertEquals(List.of(java21), registry.profiles());
        assertThrows(UnsupportedOperationException.class, () -> registry.profiles().clear());
    }

    @Test
    void rejectsNullProfileCollectionsAndMembersWithASafeMessage() {
        assertInvalid(() -> CompatibilityProfileRegistry.of(null));
        assertInvalid(() -> CompatibilityProfileRegistry.of(Arrays.asList(profile(
                "spring-ai-2.0-java21-mvc-streamable",
                new CompatibilityProfile.TargetPlatform(
                        21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")), null)));
    }

    @Test
    void rejectsBlankProfileMetadataWithASafeMessage() {
        var target = new CompatibilityProfile.TargetPlatform(
                21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP");

        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(new CompatibilityProfile(
                null, target, "generator-spring-ai-2", "spring-ai-2-v2", "0.2.0", "9.6.1", "image"))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(new CompatibilityProfile(
                " ", target, "generator-spring-ai-2", "spring-ai-2-v2", "0.2.0", "9.6.1", "image"))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(new CompatibilityProfile(
                "id", target, " ", "spring-ai-2-v2", "0.2.0", "9.6.1", "image"))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(new CompatibilityProfile(
                "id", target, "generator", " ", "0.2.0", "9.6.1", "image"))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(new CompatibilityProfile(
                "id", target, "generator", "template", " ", "9.6.1", "image"))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(new CompatibilityProfile(
                "id", target, "generator", "template", "runtime", " ", "image"))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(new CompatibilityProfile(
                "id", target, "generator", "template", "runtime", "gradle", " "))));
    }

    @Test
    void rejectsInvalidTargetMetadataWithASafeMessage() {
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(new CompatibilityProfile(
                "id", null, "generator", "template", "runtime", "gradle", "image"))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(profile(
                "id", new CompatibilityProfile.TargetPlatform(
                        0, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(profile(
                "id", new CompatibilityProfile.TargetPlatform(
                        21, " ", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(profile(
                "id", new CompatibilityProfile.TargetPlatform(
                        21, "4.1.0", " ", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(profile(
                "id", new CompatibilityProfile.TargetPlatform(
                        21, "4.1.0", "2.0.0", " ", "MVC", "SYNC", "STREAMABLE_HTTP")))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(profile(
                "id", new CompatibilityProfile.TargetPlatform(
                        21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", " ", "SYNC", "STREAMABLE_HTTP")))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(profile(
                "id", new CompatibilityProfile.TargetPlatform(
                        21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", " ", "STREAMABLE_HTTP")))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(profile(
                "id", new CompatibilityProfile.TargetPlatform(
                        21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", " ")))));
    }

    @Test
    void rejectsDuplicateIdsAndTargetPlatformsWithASafeMessage() {
        var java21 = new CompatibilityProfile.TargetPlatform(
                21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP");
        var java17 = new CompatibilityProfile.TargetPlatform(
                17, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP");

        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(
                profile("duplicate", java17), profile("duplicate", java21))));
        assertInvalid(() -> CompatibilityProfileRegistry.of(List.of(
                profile("first", java21), profile("second", java21))));
    }

    private static CompatibilityProfile profile(
            String id,
            CompatibilityProfile.TargetPlatform target) {
        return new CompatibilityProfile(
                id, target, "generator-spring-ai-2", "spring-ai-2-v2", "0.2.0", "9.6.1", "image");
    }

    private static void assertProfile(
            CompatibilityProfile profile,
            String id,
            int javaVersion,
            String springBootVersion,
            String springAiVersion,
            String buildTool,
            String distributionVersion,
            String wrapperVersion,
            String generatorModule,
            String templateVersion,
            String containerImage) {
        assertProfile(
                profile, id, javaVersion, springBootVersion, springAiVersion, buildTool,
                "MVC", "SYNC", distributionVersion, wrapperVersion,
                generatorModule, templateVersion, containerImage);
    }

    private static void assertProfile(
            CompatibilityProfile profile,
            String id,
            int javaVersion,
            String springBootVersion,
            String springAiVersion,
            String buildTool,
            String webStack,
            String programmingModel,
            String distributionVersion,
            String wrapperVersion,
            String generatorModule,
            String templateVersion,
            String containerImage) {
        assertEquals(id, profile.id());
        assertEquals(new CompatibilityProfile.TargetPlatform(
                javaVersion, springBootVersion, springAiVersion,
                buildTool, webStack, programmingModel, "STREAMABLE_HTTP"), profile.target());
        assertEquals(generatorModule, profile.generatorModule());
        assertEquals(templateVersion, profile.templateVersion());
        assertEquals("0.3.0", profile.runtimeVersion());
        assertEquals(distributionVersion, profile.buildToolchain().distributionVersion());
        assertEquals(wrapperVersion, profile.buildToolchain().wrapperVersion());
        assertEquals("GRADLE_KOTLIN".equals(buildTool) ? distributionVersion : null, profile.gradleVersion());
        assertEquals(containerImage, profile.containerImage());
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        var failure = assertThrows(IllegalArgumentException.class, executable);
        assertEquals(INVALID_PROFILES_MESSAGE, failure.getMessage());
    }
}
