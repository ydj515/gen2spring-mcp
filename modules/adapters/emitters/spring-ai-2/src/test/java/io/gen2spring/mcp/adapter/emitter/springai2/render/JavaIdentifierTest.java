package io.gen2spring.mcp.adapter.emitter.springai2.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.adapter.emitter.support.source.JavaStringLiteral;
import io.gen2spring.mcp.domain.error.GeneratorException;
import org.junit.jupiter.api.Test;

class JavaIdentifierTest {
    @Test
    void acceptsAQualifiedJavaPackage() {
        assertEquals("com.example.weather", JavaIdentifier.requirePackage("com.example.weather"));
    }

    @Test
    void rejectsAPathBreakingPackageName() {
        assertThrows(GeneratorException.class,
                () -> JavaIdentifier.requirePackage("com.example.../../escape"));
    }

    @Test
    void rejectsKotlinDslInterpolationCharactersInPackageSegments() {
        assertThrows(GeneratorException.class, () -> JavaIdentifier.requirePackage("com.$bad"));
    }

    @Test
    void rejectsJavaKeywordsInPackageSegments() {
        assertThrows(GeneratorException.class, () -> JavaIdentifier.requirePackage("com.example.class"));
    }

    @Test
    void quotesJavaStringContentWithoutAllowingSourceEscape() {
        assertEquals("\"line\\n\\\"quoted\\\"\\\\path\"", JavaStringLiteral.quote("line\n\"quoted\"\\path"));
    }
}
