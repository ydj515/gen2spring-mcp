package io.gen2spring.mcp.adapter.emitter.springai1.render;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.error.GeneratorException;
import org.junit.jupiter.api.Test;

class JavaIdentifierTest {
    @Test
    void acceptsAQualifiedJavaPackage() {
        assertEquals("com.example.weather", JavaIdentifier.requirePackage("com.example.weather"));
    }

    @Test
    void rejectsAPathBreakingPackageName() {
        assertInvalid(() -> JavaIdentifier.requirePackage("com.example.../../escape"));
    }

    @Test
    void rejectsKotlinDslInterpolationCharactersInPackageSegments() {
        assertInvalid(() -> JavaIdentifier.requirePackage("com.$bad"));
    }

    @Test
    void rejectsJavaKeywordsInPackageSegments() {
        assertInvalid(() -> JavaIdentifier.requirePackage("com.example.class"));
    }

    private void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        GeneratorException failure = assertThrows(GeneratorException.class, action);
        assertEquals(SOURCE_GENERATION_FAILED, failure.code());
        assertEquals("spring-ai-1-render", failure.stage());
    }
}
