package io.gen2spring.mcp.adapter.emitter.springai1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeneratedSourceContractTest {
    private static final List<String> PROJECT_FILES = List.of(
            ".dockerignore",
            ".gitignore",
            "Dockerfile",
            "README.md",
            "build.gradle.kts",
            "gradle.properties",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradlew",
            "gradlew.bat",
            "settings.gradle.kts",
            "src/main/resources/application.yml");

    @Test
    void emitsScaffoldBeforeSortedGeneratedSources() {
        Map<String, byte[]> files = generatedFiles();
        List<String> paths = List.copyOf(files.keySet());

        assertEquals(PROJECT_FILES, paths.subList(0, PROJECT_FILES.size()));
        List<String> sourcePaths = paths.subList(PROJECT_FILES.size(), paths.size());
        assertEquals(sourcePaths.stream().sorted().toList(), sourcePaths);
    }

    @Test
    void returnsAnImmutableGeneratedFileMap() {
        Map<String, byte[]> files = generatedFiles();

        assertThrows(UnsupportedOperationException.class,
                () -> files.put("unexpected", new byte[0]));
    }

    private Map<String, byte[]> generatedFiles() {
        return new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.contextWithWeatherTool(
                        CompatibilityProfileRegistry.defaults()
                                .find("spring-ai-1.1-java21-mvc-streamable")
                                .orElseThrow()))
                .files();
    }
}
