package io.gen2spring.mcp.application.planning;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.TARGET_COMBINATION_UNSUPPORTED;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.application.port.outbound.ProjectGenerator;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProjectGeneratorRegistry {
    private static final String INVALID_GENERATORS_MESSAGE = "Project generators are invalid";
    private static final String UNSUPPORTED_PROFILE_MESSAGE =
            "The requested compatibility profile is unsupported";

    private final Map<String, ProjectGenerator> generators;

    private ProjectGeneratorRegistry(Map<String, ProjectGenerator> generators) {
        this.generators = generators;
    }

    public static ProjectGeneratorRegistry of(Map<String, ProjectGenerator> generators) {
        if (generators == null) {
            throw new IllegalArgumentException(INVALID_GENERATORS_MESSAGE);
        }
        Map<String, ProjectGenerator> copy = new LinkedHashMap<>();
        generators.forEach((module, generator) -> {
            if (module == null || module.isBlank() || generator == null) {
                throw new IllegalArgumentException(INVALID_GENERATORS_MESSAGE);
            }
            copy.put(module, generator);
        });
        return new ProjectGeneratorRegistry(Map.copyOf(copy));
    }

    public ProjectGenerator require(CompatibilityProfile profile) {
        ProjectGenerator generator = profile == null || profile.generatorModule() == null
                ? null
                : generators.get(profile.generatorModule());
        if (generator == null) {
            throw GeneratorException.user(
                    TARGET_COMBINATION_UNSUPPORTED,
                    "TARGET_VALIDATE",
                    UNSUPPORTED_PROFILE_MESSAGE);
        }
        return generator;
    }
}
