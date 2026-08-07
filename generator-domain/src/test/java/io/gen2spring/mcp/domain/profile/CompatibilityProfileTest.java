package io.gen2spring.mcp.domain.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompatibilityProfileTest {
    @Test
    void acceptsOnlyThePinnedP0Target() {
        var profile = CompatibilityProfile.p0();

        assertTrue(profile.supports(new CompatibilityProfile.TargetPlatform(
                21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")));
        assertFalse(profile.supports(new CompatibilityProfile.TargetPlatform(
                17, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")));
    }
}
