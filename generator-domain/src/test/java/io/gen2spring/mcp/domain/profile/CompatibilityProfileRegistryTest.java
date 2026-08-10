package io.gen2spring.mcp.domain.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompatibilityProfileRegistryTest {
    private static final String INVALID_PROFILES_MESSAGE = "Compatibility profiles are invalid";

    @Test
    void exposesThePinnedProfilesInAscendingIdOrder() {
        var registry = CompatibilityProfileRegistry.defaults();

        assertEquals(List.of(
                "spring-ai-2.0-java17-mvc-streamable",
                "spring-ai-2.0-java21-mvc-streamable"),
                registry.profiles().stream().map(CompatibilityProfile::id).toList());

        var java17 = registry.profiles().get(0);
        assertEquals(new CompatibilityProfile.TargetPlatform(
                17, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP"),
                java17.target());
        assertEquals("generator-spring-ai-2", java17.generatorModule());
        assertEquals("spring-ai-2-v2", java17.templateVersion());
        assertEquals("0.2.0", java17.runtimeVersion());
        assertEquals("9.6.1", java17.gradleVersion());
        assertEquals(
                "eclipse-temurin:17.0.19_10-jre-noble@sha256:"
                        + "543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8",
                java17.containerImage());

        var java21 = registry.profiles().get(1);
        assertEquals(new CompatibilityProfile.TargetPlatform(
                21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP"),
                java21.target());
        assertEquals("generator-spring-ai-2", java21.generatorModule());
        assertEquals("spring-ai-2-v2", java21.templateVersion());
        assertEquals("0.2.0", java21.runtimeVersion());
        assertEquals("9.6.1", java21.gradleVersion());
        assertEquals(
                "eclipse-temurin:21.0.11_10-jre-noble@sha256:"
                        + "373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64",
                java21.containerImage());
    }

    @Test
    void returnsCanonicalProfilesByIdWithoutFallback() {
        var registry = CompatibilityProfileRegistry.defaults();
        var java17 = registry.profiles().get(0);

        assertSame(java17, registry.find(java17.id()).orElseThrow());
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

    private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        var failure = assertThrows(IllegalArgumentException.class, executable);
        assertEquals(INVALID_PROFILES_MESSAGE, failure.getMessage());
    }
}
