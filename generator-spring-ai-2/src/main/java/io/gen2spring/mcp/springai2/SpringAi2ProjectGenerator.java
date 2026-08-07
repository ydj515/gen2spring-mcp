package io.gen2spring.mcp.springai2;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectFiles;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationContext;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ProjectGenerator;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SpringAi2ProjectGenerator implements ProjectGenerator {
    private final ProjectFileRenderer renderer;
    private final JavaSourceRenderer javaSourceRenderer;

    public SpringAi2ProjectGenerator() {
        this(new ProjectFileRenderer(CompatibilityProfile.p0()), new JavaSourceRenderer());
    }

    SpringAi2ProjectGenerator(ProjectFileRenderer renderer, JavaSourceRenderer javaSourceRenderer) {
        this.renderer = renderer;
        this.javaSourceRenderer = javaSourceRenderer;
    }

    @Override
    public GeneratedProjectFiles generate(GenerationContext context) {
        var coordinates = renderer.requireContext(context);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(".gitignore", utf8(renderer.gitignore()));
        files.put("Dockerfile", utf8(renderer.dockerfile(coordinates)));
        files.put("README.md", utf8(renderer.readme(context)));
        files.put("build.gradle.kts", utf8(renderer.buildGradle(coordinates)));
        files.put("gradle.properties", utf8(renderer.gradleProperties()));
        files.put("gradle/wrapper/gradle-wrapper.jar", renderer.wrapperAsset("gradle-wrapper.jar"));
        files.put("gradle/wrapper/gradle-wrapper.properties", renderer.wrapperAsset("gradle-wrapper.properties"));
        files.put("gradlew", renderer.wrapperAsset("gradlew"));
        files.put("gradlew.bat", renderer.wrapperAsset("gradlew.bat"));
        files.put("settings.gradle.kts", utf8(renderer.settingsGradle(coordinates)));
        files.put("src/main/resources/application.yml", utf8(renderer.applicationYaml(context)));
        javaSourceRenderer.render(context).forEach(files::put);
        return new GeneratedProjectFiles(Collections.unmodifiableMap(files));
    }

    private byte[] utf8(String value) {
        return value.getBytes(UTF_8);
    }
}
