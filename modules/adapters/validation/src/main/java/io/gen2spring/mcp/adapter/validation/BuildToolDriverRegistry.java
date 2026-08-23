package io.gen2spring.mcp.adapter.validation;

import java.util.Map;
import java.util.Objects;

final class BuildToolDriverRegistry {
    private static final String SAFE_FAILURE = "Build tool driver is unsupported";

    private final Map<String, BuildToolDriver> drivers;

    private BuildToolDriverRegistry(Map<String, BuildToolDriver> drivers) {
        this.drivers = drivers;
    }

    static BuildToolDriverRegistry defaults() {
        return Defaults.INSTANCE;
    }

    static BuildToolDriverRegistry of(Map<String, BuildToolDriver> drivers) {
        Objects.requireNonNull(drivers, "drivers");
        return new BuildToolDriverRegistry(Map.copyOf(drivers));
    }

    BuildToolDriver require(String buildTool) {
        if (buildTool == null || buildTool.isBlank()) {
            throw unsupported();
        }
        BuildToolDriver driver = drivers.get(buildTool);
        if (driver == null) {
            throw unsupported();
        }
        return driver;
    }

    private IllegalArgumentException unsupported() {
        return new IllegalArgumentException(SAFE_FAILURE);
    }

    private static final class Defaults {
        private static final BuildToolDriverRegistry INSTANCE = new BuildToolDriverRegistry(Map.of(
                "GRADLE_KOTLIN", new GradleBuildToolDriver(),
                "MAVEN", new MavenBuildToolDriver()));

        private Defaults() {}
    }
}
