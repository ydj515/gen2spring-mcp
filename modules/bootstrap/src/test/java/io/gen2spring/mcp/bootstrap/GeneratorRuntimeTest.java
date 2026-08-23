package io.gen2spring.mcp.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.gen2spring.mcp.domain.profile.CompatibilityCatalog;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratorRuntimeTest {
    @Test
    void createsOneCanonicalFourProfileTwoEmitterApplicationGraph() {
        GeneratorRuntime application = GeneratorRuntime.defaults();

        assertSame(CompatibilityCatalog.defaults(), application.compatibilityCatalog());
        assertSame(CompatibilityProfileRegistry.defaults(), application.profiles());
        assertSame(application.compatibilityCatalog().profiles(), application.profiles());
        assertEquals(List.of("SPRING_AI_1_WEBFLUX_ASYNC_DEFERRED"),
                application.compatibilityCatalog().notices().stream().map(notice -> notice.code()).toList());
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
        assertNotNull(application.planner());
        assertNotNull(application.pipeline());
    }
}
