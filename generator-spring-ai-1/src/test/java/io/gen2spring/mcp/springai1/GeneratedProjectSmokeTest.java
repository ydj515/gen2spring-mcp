package io.gen2spring.mcp.springai1;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class GeneratedProjectSmokeTest {
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

    @TempDir
    Path tempDir;

    @Test
    void emitsFixedProjectFilesBeforeSortedJavaSourcesAndReturnsAnUnmodifiableMap() {
        Map<String, byte[]> files = new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.contextWithWeatherTool(profile(21)))
                .files();
        List<String> paths = List.copyOf(files.keySet());

        assertEquals(PROJECT_FILES, paths.subList(0, PROJECT_FILES.size()));
        List<String> javaPaths = paths.subList(PROJECT_FILES.size(), paths.size());
        assertEquals(javaPaths.stream().sorted().toList(), javaPaths);
        assertThrows(UnsupportedOperationException.class,
                () -> files.put("unexpected", new byte[0]));
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedJava17ProjectCompilesAndTestsOnJava17() throws Exception {
        assertProjectBuilds(tempDir.resolve("java17"), 17);
    }

    @Test
    @Timeout(value = 5, unit = MINUTES)
    void generatedJava21ProjectCompilesBootsAndRegistersExactlyOneToolOnJava21() throws Exception {
        assertProjectBuilds(tempDir.resolve("java21"), 21);
    }

    private void assertProjectBuilds(Path project, int javaVersion) throws Exception {
        var generated = new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.contextWithWeatherTool(profile(javaVersion)))
                .files();
        Map<String, byte[]> files = new LinkedHashMap<>(generated);
        files.put(
                "src/test/java/com/example/weather/application/GeneratedSpecificationRegistrationTest.java",
                exactSpecificationRegistrationTest().getBytes(UTF_8));
        writeProject(project, files);

        Path javaHome = requiredJavaHome(javaVersion);
        List<String> command = new ArrayList<>(List.of(
                "./gradlew", "compileJava", "test", "--no-daemon", "--non-interactive"));
        command.add("-Dorg.gradle.java.installations.auto-detect=false");
        command.add("-Dorg.gradle.java.installations.auto-download=false");
        command.add("-Dorg.gradle.java.installations.paths=" + javaHome);
        Process process = new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        ManagedTestProcess.Result result = ManagedTestProcess.run(
                process, Duration.ofMinutes(4), Duration.ofSeconds(10));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("BUILD SUCCESSFUL"), result.output());
    }

    private void writeProject(Path project, Map<String, byte[]> files) throws Exception {
        for (var entry : files.entrySet()) {
            Path target = project.resolve(entry.getKey()).normalize();
            assertTrue(target.startsWith(project), entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        assertTrue(project.resolve("gradlew").toFile().setExecutable(true));
    }

    private CompatibilityProfile profile(int javaVersion) {
        return CompatibilityProfileRegistry.defaults()
                .find("spring-ai-1.1-java" + javaVersion + "-mvc-streamable")
                .orElseThrow();
    }

    private Path requiredJavaHome(int javaVersion) {
        String environmentVariable = "GEN2SPRING_JAVA_" + javaVersion + "_HOME";
        String configured = System.getenv(environmentVariable);
        if ((configured == null || configured.isBlank()) && javaVersion == Runtime.version().feature()) {
            configured = System.getProperty("java.home");
        }
        assertTrue(configured != null && !configured.isBlank(), environmentVariable + " must be configured");
        Path javaHome = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(javaHome.resolve("bin/java")), environmentVariable);
        return javaHome;
    }

    private String exactSpecificationRegistrationTest() {
        return """
                package com.example.weather.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import io.modelcontextprotocol.server.McpServerFeatures;
                import java.util.List;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                class GeneratedSpecificationRegistrationTest {
                    @Autowired
                    @Qualifier("generatedToolSpecifications")
                    private List<McpServerFeatures.SyncToolSpecification> specifications;

                    @Test
                    void registersExactlyOneGeneratedSpecification() {
                        assertEquals(1, specifications.size());
                        assertEquals("kma_weather_get_forecast", specifications.get(0).tool().name());
                    }
                }
                """;
    }
}
