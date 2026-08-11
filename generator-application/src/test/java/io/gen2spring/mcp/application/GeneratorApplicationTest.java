package io.gen2spring.mcp.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratorApplicationTest {
    @Test
    void createsOneCanonicalFourProfileTwoEmitterApplicationGraph() {
        GeneratorApplication application = GeneratorApplication.defaults();

        assertSame(CompatibilityProfileRegistry.defaults(), application.profiles());
        assertEquals(List.of(
                "spring-ai-1.1-java17-mvc-streamable",
                "spring-ai-1.1-java21-mvc-streamable",
                "spring-ai-2.0-java17-mvc-streamable",
                "spring-ai-2.0-java21-mvc-streamable"),
                application.profiles().profiles().stream().map(profile -> profile.id()).toList());
        assertEquals(List.of("generator-spring-ai-1", "generator-spring-ai-2"),
                application.generatorModules());
        application.profiles().profiles().forEach(profile ->
                assertNotNull(application.projectGenerators().require(profile)));
        assertNotNull(application.analyzer());
        assertNotNull(application.configurationParser());
        assertNotNull(application.pipeline());
    }
}
