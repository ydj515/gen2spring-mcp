package io.gen2spring.mcp.adapter.emitter.support;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GradleKotlinProjectScaffoldTest {
    @Test
    void rendersTheCharacterizedGradleProjectFiles() {
        Map<String, byte[]> files = new GradleKotlinProjectScaffold().render(model());

        assertEquals(List.of(
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
                "src/main/resources/application.yml"), List.copyOf(files.keySet()));
        assertEquals(expectedBuildGradle(), text(files, "build.gradle.kts"));
        assertEquals("rootProject.name = \"weather-mcp-server\"\n", text(files, "settings.gradle.kts"));
        assertEquals("org.gradle.caching=true\norg.gradle.configuration-cache=true\n",
                text(files, "gradle.properties"));
        assertTrue(text(files, "gradle/wrapper/gradle-wrapper.properties")
                .contains("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.6.1-bin.zip"));
        assertTrue(text(files, "README.md").contains("./gradlew bootRun"));
        assertTrue(text(files, "README.md").contains("./gradlew test"));
        assertTrue(text(files, "Dockerfile").contains("COPY build/libs/weather-mcp-server.jar /app/app.jar"));
    }

    @Test
    void registryRejectsBlankAndUnknownBuildToolsBeforeRendering() {
        var registry = BuildProjectScaffoldRegistry.defaults();

        assertThrows(GeneratorException.class, () -> registry.require(null));
        assertThrows(GeneratorException.class, () -> registry.require(" "));
        assertThrows(GeneratorException.class, () -> registry.require("UNKNOWN"));
    }

    private ProjectScaffoldModel model() {
        CompatibilityProfile profile = CompatibilityProfile.p0();
        return new ProjectScaffoldModel(
                "com.example",
                "weather-mcp-server",
                "com.example.weather",
                "WeatherMcpApplication",
                profile,
                List.of(
                        dependency("org.springframework.ai", "spring-ai-starter-mcp-server-webmvc"),
                        dependency("org.springframework.boot", "spring-boot-restclient"),
                        dependency("org.springframework.boot", "spring-boot-starter-validation"),
                        dependency("org.springframework.boot", "spring-boot-starter-actuator"),
                        dependency("org.springframework.boot", "spring-boot-starter-opentelemetry"),
                        dependency("io.micrometer", "micrometer-registry-prometheus"),
                        dependency("io.micrometer", "micrometer-registry-otlp"),
                        new ProjectScaffoldModel.Dependency(
                                "org.springframework.boot",
                                "spring-boot-starter-test",
                                ProjectScaffoldModel.Scope.TEST_IMPLEMENTATION)),
                "spring:\n  application:\n    name: weather-mcp-server\n",
                new ProjectScaffoldModel.ProjectDocumentation(
                        "Generated Spring AI MCP server. It exposes the selected OpenAPI operations as\n"
                                + "deterministic MCP tools and invokes the configured provider over HTTP.",
                        List.of("KMA_SERVICE_KEY"),
                        " -e PROVIDER_BASE_URL=https://api.example.test -e KMA_SERVICE_KEY",
                        "- `weather_get_forecast`: Get a weather forecast.",
                        "## Observability\n\nObservability details.",
                        "## Response handling\n\nResponse handling details."));
    }

    private ProjectScaffoldModel.Dependency dependency(String groupId, String artifactId) {
        return new ProjectScaffoldModel.Dependency(
                groupId, artifactId, ProjectScaffoldModel.Scope.IMPLEMENTATION);
    }

    private String text(Map<String, byte[]> files, String path) {
        return new String(files.get(path), UTF_8);
    }

    private String expectedBuildGradle() {
        return """
                plugins {
                    java
                    id("org.springframework.boot") version "4.1.0"
                }

                group = "com.example"
                version = "0.1.0"

                repositories {
                    mavenCentral()
                }

                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }

                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
                    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
                    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
                    implementation("org.springframework.boot:spring-boot-restclient")
                    implementation("org.springframework.boot:spring-boot-starter-validation")
                    implementation("org.springframework.boot:spring-boot-starter-actuator")
                    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
                    implementation("io.micrometer:micrometer-registry-prometheus")
                    implementation("io.micrometer:micrometer-registry-otlp")
                    testImplementation("org.springframework.boot:spring-boot-starter-test")
                }

                tasks.bootJar {
                    archiveFileName.set("weather-mcp-server.jar")
                }

                tasks.withType<JavaCompile> {
                    options.compilerArgs.add("-parameters")
                }

                tasks.test {
                    useJUnitPlatform()
                }
                """;
    }
}
