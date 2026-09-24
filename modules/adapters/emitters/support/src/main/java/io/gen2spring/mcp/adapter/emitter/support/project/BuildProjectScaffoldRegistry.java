package io.gen2spring.mcp.adapter.emitter.support.project;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;

import io.gen2spring.mcp.domain.error.GeneratorException;
import java.util.Map;
import java.util.Objects;

public final class BuildProjectScaffoldRegistry {
    private final Map<String, BuildProjectScaffold> scaffolds;

    private BuildProjectScaffoldRegistry(Map<String, BuildProjectScaffold> scaffolds) {
        this.scaffolds = scaffolds;
    }

    public static BuildProjectScaffoldRegistry defaults() {
        return Defaults.INSTANCE;
    }

    public static BuildProjectScaffoldRegistry of(Map<String, BuildProjectScaffold> scaffolds) {
        Objects.requireNonNull(scaffolds, "scaffolds");
        return new BuildProjectScaffoldRegistry(Map.copyOf(scaffolds));
    }

    public BuildProjectScaffold require(String buildTool) {
        if (buildTool == null || buildTool.isBlank()) {
            throw unsupportedBuildTool();
        }
        BuildProjectScaffold scaffold = scaffolds.get(buildTool);
        if (scaffold == null) {
            throw unsupportedBuildTool();
        }
        return scaffold;
    }

    private GeneratorException unsupportedBuildTool() {
        return GeneratorException.user(
                SOURCE_GENERATION_FAILED,
                "project-scaffold",
                "The requested build tool is not supported");
    }

    private static final class Defaults {
        private static final BuildProjectScaffoldRegistry INSTANCE = BuildProjectScaffoldRegistry.of(
                Map.of(
                        "GRADLE_KOTLIN", new GradleKotlinProjectScaffold(),
                        "MAVEN", new MavenProjectScaffold()));

        private Defaults() {}
    }
}
