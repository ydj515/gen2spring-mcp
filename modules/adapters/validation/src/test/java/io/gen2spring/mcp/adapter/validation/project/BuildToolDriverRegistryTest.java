package io.gen2spring.mcp.adapter.validation.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BuildToolDriverRegistryTest {
    @Test
    void selectsExactGradleAndMavenDriversWithoutFallback() {
        var registry = BuildToolDriverRegistry.defaults();

        assertSame(registry.require("GRADLE_KOTLIN"), registry.require("GRADLE_KOTLIN"));
        assertSame(registry.require("MAVEN"), registry.require("MAVEN"));
        assertThrows(IllegalArgumentException.class, () -> registry.require(null));
        assertThrows(IllegalArgumentException.class, () -> registry.require(" "));
        assertThrows(IllegalArgumentException.class, () -> registry.require("UNKNOWN"));
    }

    @Test
    void exposesBuildToolSpecificArtifactLocations() {
        var registry = BuildToolDriverRegistry.defaults();

        assertEquals(Path.of("build/libs/weather-mcp.jar"),
                registry.require("GRADLE_KOTLIN").relativeArtifact("weather-mcp"));
        assertEquals(Path.of("target/weather-mcp.jar"),
                registry.require("MAVEN").relativeArtifact("weather-mcp"));
    }
}
