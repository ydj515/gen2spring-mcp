package io.gen2spring.mcp.adapter.emitter.support.project;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class GradleKotlinProjectScaffold implements BuildProjectScaffold {
    @Override
    public Map<String, byte[]> render(ProjectScaffoldModel model) {
        requireModel(model);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(".dockerignore", utf8(dockerignore(model.artifactId())));
        files.put(".gitignore", utf8(gitignore()));
        files.put("Dockerfile", utf8(dockerfile(model.profile(), model.artifactId())));
        files.put("README.md", utf8(readme(model)));
        files.put("build.gradle.kts", utf8(buildGradle(model)));
        files.put("gradle.properties", utf8(gradleProperties()));
        files.put("gradle/wrapper/gradle-wrapper.jar", wrapperAsset("gradle-wrapper.jar"));
        files.put("gradle/wrapper/gradle-wrapper.properties", wrapperAsset("gradle-wrapper.properties"));
        files.put("gradlew", wrapperAsset("gradlew"));
        files.put("gradlew.bat", wrapperAsset("gradlew.bat"));
        files.put("settings.gradle.kts", utf8(settingsGradle(model.artifactId())));
        files.put("src/main/resources/application.yml", utf8(model.applicationYaml()));
        return Collections.unmodifiableMap(files);
    }

    public String buildGradle(ProjectScaffoldModel model) {
        requireModel(model);
        return buildGradle(
                model.groupId(), model.artifactId(), model.profile(), model.dependencies());
    }

    public String buildGradle(
            String groupId,
            String artifactId,
            CompatibilityProfile profile,
            java.util.List<ProjectScaffoldModel.Dependency> projectDependencies) {
        var target = profile.target();
        String dependencies = projectDependencies.stream()
                .map(dependency -> dependency.scope().gradleConfiguration()
                        + "(\"" + dependency.coordinate() + "\")")
                .collect(Collectors.joining("\n    "));
        String source = """
                plugins {
                    java
                    id("org.springframework.boot") version "%s"
                }

                group = "%s"
                version = "0.1.0"

                repositories {
                    mavenCentral()
                }

                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(%d)
                    }
                }

                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:%s"))
                    implementation(platform("org.springframework.ai:spring-ai-bom:%s"))
                    %s
                }

                tasks.bootJar {
                    archiveFileName.set("%s.jar")
                }

                tasks.withType<JavaCompile> {
                    options.compilerArgs.add("-parameters")
                }

                tasks.test {
                    useJUnitPlatform()
                }
                """.formatted(
                target.springBootVersion(),
                groupId,
                target.javaVersion(),
                target.springBootVersion(),
                target.springAiVersion(),
                dependencies,
                artifactId);
        if (projectDependencies.stream().anyMatch(dependency -> dependency.groupId().equals("io.modelcontextprotocol.sdk"))) {
            source = source.replace("    implementation(platform(\"org.springframework.ai:spring-ai-bom:"
                    + target.springAiVersion() + "\"))\n", "");
        }
        return source;
    }

    public String settingsGradle(String artifactId) {
        return "rootProject.name = \"%s\"\n".formatted(artifactId);
    }

    public String gradleProperties() {
        return """
                org.gradle.caching=true
                org.gradle.configuration-cache=true
                """;
    }

    public String dockerfile(CompatibilityProfile profile, String artifactId) {
        return """
                FROM %s
                WORKDIR /app
                COPY build/libs/%s.jar /app/app.jar
                USER 10001:10001
                ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                """.formatted(profile.containerImage(), artifactId);
    }

    public String dockerignore(String artifactId) {
        return """
                **
                !Dockerfile
                !build/
                !build/libs/
                !build/libs/%s.jar
                """.formatted(artifactId);
    }

    public String gitignore() {
        return "/.gradle/\n/build/\n";
    }

    public String readme(ProjectScaffoldModel model) {
        requireModel(model);
        var documentation = model.projectDescription();
        var profile = model.profile();
        var target = profile.target();
        String secrets = documentation.providerEnvironmentVariables().isEmpty()
                ? "No provider secrets are required."
                : "Set these environment variables before starting:\n\n"
                        + documentation.providerEnvironmentVariables().stream()
                                .map(value -> "- `" + value + "`")
                                .reduce("", (left, right) -> left + right + "\n");
        return """
                # %s

                %s

                ## Run

                Requirements: Java %d.

                ```bash
                ./gradlew bootRun
                ```

                The Streamable HTTP MCP endpoint is `http://localhost:8080/mcp`.

                %s

                ## Provider configuration

                %s

                Provider calls use a 2 second connect timeout, 5 second response-read timeout,
                10 second total timeout, 1 MiB response limit, 16 concurrent requests, and a
                64 request queue by default. Override the `provider` values in `application.yml`
                only with positive timeout values up to 300000 milliseconds.

                ## MCP client configuration

                ```json
                {
                  "mcpServers": {
                    "%s": {
                      "url": "http://localhost:8080/mcp"
                    }
                  }
                }
                ```

                ## Tools

                %s

                ## Test

                ```bash
                ./gradlew test
                ```

                ## Docker

                ```bash
                ./gradlew bootJar
                docker build -t %s .
                docker run --rm -p 8080:8080%s %s
                ```

                ## Generator information

                - Compatibility profile: `%s`
                - Template: `%s`
                - Generator module: `%s`
                - Runtime version: `%s`
                - Gradle %s
                - Container image: `%s`
                - Java %d
                - Spring Boot %s
                - Spring AI %s

                %s
                """.formatted(
                model.artifactId(),
                documentation.summary(),
                target.javaVersion(),
                documentation.observability(),
                secrets,
                model.artifactId(),
                documentation.tools(),
                model.artifactId(),
                documentation.dockerEnvironment(),
                model.artifactId(),
                profile.id(),
                profile.templateVersion(),
                profile.generatorModule(),
                profile.runtimeVersion(),
                profile.gradleVersion(),
                profile.containerImage(),
                target.javaVersion(),
                target.springBootVersion(),
                target.springAiVersion(),
                documentation.responseHandling());
    }

    public byte[] wrapperAsset(String fileName) {
        try (InputStream resource = GradleKotlinProjectScaffold.class
                .getResourceAsStream("/wrapper/" + fileName)) {
            if (resource == null) {
                throw GeneratorException.user(
                        SOURCE_GENERATION_FAILED,
                        "project-scaffold",
                        "A required Gradle wrapper asset is missing");
            }
            return resource.readAllBytes();
        } catch (IOException exception) {
            throw GeneratorException.system(
                    SOURCE_GENERATION_FAILED,
                    "project-scaffold",
                    "Failed to load a Gradle wrapper asset",
                    exception);
        }
    }

    private void requireModel(ProjectScaffoldModel model) {
        if (model == null || model.profile().target() == null
                || !"GRADLE_KOTLIN".equals(model.profile().target().buildTool())) {
            throw GeneratorException.user(
                    SOURCE_GENERATION_FAILED,
                    "project-scaffold",
                    "A Gradle Kotlin project scaffold model is required");
        }
    }

    private byte[] utf8(String value) {
        return value.getBytes(UTF_8);
    }
}
