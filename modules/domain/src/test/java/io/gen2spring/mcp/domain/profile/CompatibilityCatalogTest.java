package io.gen2spring.mcp.domain.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CompatibilityCatalogTest {
    @Test
    void exposesActiveProfilesAndTheCanonicalDeferredTargetNotice() {
        var catalog = CompatibilityCatalog.defaults();

        assertEquals(12, catalog.profiles().profiles().size());
        assertEquals(List.of(
                        "spring-ai-1.1-java17-maven-mvc-streamable",
                        "spring-ai-1.1-java17-mvc-streamable",
                        "spring-ai-1.1-java21-maven-mvc-streamable",
                        "spring-ai-1.1-java21-mvc-streamable",
                        "spring-ai-2.0-java17-maven-mvc-streamable",
                        "spring-ai-2.0-java17-maven-webflux-async-streamable",
                        "spring-ai-2.0-java17-mvc-streamable",
                        "spring-ai-2.0-java17-webflux-async-streamable",
                        "spring-ai-2.0-java21-maven-mvc-streamable",
                        "spring-ai-2.0-java21-maven-webflux-async-streamable",
                        "spring-ai-2.0-java21-mvc-streamable",
                        "spring-ai-2.0-java21-webflux-async-streamable"),
                catalog.profiles().profiles().stream().map(CompatibilityProfile::id).toList());
        assertEquals("9.6.1", catalog.profiles()
                .find("spring-ai-2.0-java21-mvc-streamable")
                .orElseThrow()
                .buildToolchain()
                .distributionVersion());
        assertEquals(List.of("SPRING_AI_1_WEBFLUX_ASYNC_DEFERRED"),
                catalog.notices().stream().map(CompatibilityNotice::code).toList());
        assertEquals(List.of(), catalog.profiles().profiles().stream()
                .filter(profile -> profile.id().startsWith("spring-ai-1.1"))
                .filter(profile -> "WEBFLUX".equals(profile.target().webStack()))
                .toList());

        CompatibilityNotice notice = catalog.notices().getFirst();
        assertEquals("WARNING", notice.severity());
        assertEquals("Spring AI 1.1 WebFlux Async generation is deferred", notice.summary());
        assertEquals("SPRING_AI_1_1", notice.affectedTarget().springAiFamily());
        assertEquals("WEBFLUX", notice.affectedTarget().webStack());
        assertEquals("ASYNC", notice.affectedTarget().programmingModel());
        assertEquals("STREAMABLE_HTTP", notice.affectedTarget().transport());
    }

    @Test
    void preservesOneCanonicalDefaultRegistryAndImmutableNotices() {
        var catalog = CompatibilityCatalog.defaults();

        assertSame(catalog.profiles(), CompatibilityProfileRegistry.defaults());
        assertThrows(UnsupportedOperationException.class, () -> catalog.notices().clear());
    }
}
