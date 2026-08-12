package io.gen2spring.mcp.domain.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompatibilityProfileTest {
    @Test
    void p0RemainsTheJava21DefaultProfile() {
        var profile = CompatibilityProfile.p0();

        assertEquals("spring-ai-2.0-java21-mvc-streamable", profile.id());
        assertEquals("spring-ai-2-v3", profile.templateVersion());
        assertEquals("0.3.0", profile.runtimeVersion());
        assertEquals("9.6.1", profile.gradleVersion());
        assertEquals(
                "eclipse-temurin:21.0.11_10-jre-noble@sha256:"
                        + "373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64",
                profile.containerImage());
        assertEquals(CompatibilityProfileRegistry.defaults().find(profile.id()).orElseThrow(), profile);
        assertTrue(profile.supports(new CompatibilityProfile.TargetPlatform(
                21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")));
        assertFalse(profile.supports(new CompatibilityProfile.TargetPlatform(
                17, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")));
    }
}
