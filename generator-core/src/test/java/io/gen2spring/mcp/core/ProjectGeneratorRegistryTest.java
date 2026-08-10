package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.TARGET_COMBINATION_UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectFiles;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ProjectGenerator;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectGeneratorRegistryTest {

    @Test
    void resolvesEachProfileFamilyToItsRegisteredGeneratorModule() {
        ProjectGenerator springAi1Generator = context -> new GeneratedProjectFiles(Map.of());
        ProjectGenerator springAi2Generator = context -> new GeneratedProjectFiles(Map.of());
        ProjectGeneratorRegistry registry = ProjectGeneratorRegistry.of(Map.of(
                "generator-spring-ai-1", springAi1Generator,
                "generator-spring-ai-2", springAi2Generator));

        for (CompatibilityProfile profile : CompatibilityProfileRegistry.defaults().profiles()) {
            ProjectGenerator expected = profile.generatorModule().equals("generator-spring-ai-1")
                    ? springAi1Generator
                    : springAi2Generator;
            assertSame(expected, registry.require(profile));
        }
    }

    @Test
    void rejectsAProfileWithoutARegisteredEmitterUsingAFixedSafeError() {
        CompatibilityProfile profile = CompatibilityProfileRegistry.defaults().profiles().getFirst();
        ProjectGeneratorRegistry registry = ProjectGeneratorRegistry.of(Map.of());

        GeneratorException failure = assertThrows(GeneratorException.class, () -> registry.require(profile));

        assertEquals(TARGET_COMBINATION_UNSUPPORTED, failure.code());
        assertEquals("TARGET_VALIDATE", failure.stage());
        assertEquals("The requested compatibility profile is unsupported", failure.safeMessage());
    }
}
