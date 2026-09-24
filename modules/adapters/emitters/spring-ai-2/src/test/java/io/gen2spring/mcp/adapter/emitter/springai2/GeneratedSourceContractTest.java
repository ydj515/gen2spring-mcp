package io.gen2spring.mcp.adapter.emitter.springai2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.adapter.emitter.springai2.fixture.RendererFixtures;
import io.gen2spring.mcp.adapter.emitter.support.project.BuildProjectScaffoldRegistry;
import io.gen2spring.mcp.application.generation.port.out.GeneratedToolSources;
import io.gen2spring.mcp.domain.error.GeneratorException;
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

    @Test
    void emitsOnlyMavenBuildFilesForAMavenProfile() {
        var profile = CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java21-maven-mvc-streamable")
                .orElseThrow();

        Map<String, byte[]> files = new SpringAi2ProjectGenerator()
                .generate(RendererFixtures.contextWithWeatherTool(profile))
                .files();

        assertTrue(files.keySet().containsAll(List.of(
                "pom.xml", "mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")));
        assertFalse(files.containsKey("build.gradle.kts"));
        assertFalse(files.containsKey("gradlew"));
    }

    @Test
    void rejectsDuplicatePathsAcrossScaffoldAndGeneratedSources() {
        var projectScaffolds = BuildProjectScaffoldRegistry.of(Map.of(
                "GRADLE_KOTLIN", model -> Map.of("duplicate.txt", new byte[] {1})));
        var generator = new SpringAi2ProjectGenerator(
                context -> new GeneratedToolSources(Map.of("duplicate.txt", new byte[] {2})),
                projectScaffolds);

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> generator.generate(RendererFixtures.contextWithWeatherTool()));

        assertEquals("Generated project files contain duplicate paths", failure.safeMessage());
    }

    private Map<String, byte[]> generatedFiles() {
        return new SpringAi2ProjectGenerator()
                .generate(RendererFixtures.contextWithWeatherTool())
                .files();
    }
}
