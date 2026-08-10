package io.gen2spring.mcp.springai1;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectFiles;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationContext;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ProjectGenerator;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SpringAi1ProjectGenerator implements ProjectGenerator {
    public SpringAi1ProjectGenerator() {}

    @Override
    public GeneratedProjectFiles generate(GenerationContext context) {
        CompatibilityProfile profile = context == null ? null : context.profile();
        var project = new ProjectFileRenderer(profile);
        var java = new JavaSourceRenderer(profile);
        var coordinates = project.requireContext(context);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(".dockerignore", utf8(project.dockerignore(coordinates)));
        files.put(".gitignore", utf8(project.gitignore()));
        files.put("Dockerfile", utf8(project.dockerfile(coordinates)));
        files.put("README.md", utf8(project.readme(context)));
        files.put("build.gradle.kts", utf8(project.buildGradle(coordinates)));
        files.put("gradle.properties", utf8(project.gradleProperties()));
        files.put("gradle/wrapper/gradle-wrapper.jar", project.wrapperAsset("gradle-wrapper.jar"));
        files.put("gradle/wrapper/gradle-wrapper.properties", project.wrapperAsset("gradle-wrapper.properties"));
        files.put("gradlew", project.wrapperAsset("gradlew"));
        files.put("gradlew.bat", project.wrapperAsset("gradlew.bat"));
        files.put("settings.gradle.kts", utf8(project.settingsGradle(coordinates)));
        files.put("src/main/resources/application.yml", utf8(project.applicationYaml(context)));
        java.render(context).forEach(files::put);
        return new GeneratedProjectFiles(Collections.unmodifiableMap(files));
    }

    private byte[] utf8(String value) {
        return value.getBytes(UTF_8);
    }
}
