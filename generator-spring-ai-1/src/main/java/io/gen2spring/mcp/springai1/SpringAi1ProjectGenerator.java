package io.gen2spring.mcp.springai1;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.gen2spring.mcp.application.port.outbound.GeneratedProjectFiles;
import io.gen2spring.mcp.application.usecase.GenerationContext;
import io.gen2spring.mcp.application.port.outbound.ProjectGenerator;
import io.gen2spring.mcp.application.port.outbound.ToolEmitter;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SpringAi1ProjectGenerator implements ProjectGenerator {
    private final ToolEmitter toolEmitter;

    public SpringAi1ProjectGenerator() {
        this(new SpringAi1ToolEmitter());
    }

    SpringAi1ProjectGenerator(ToolEmitter toolEmitter) {
        this.toolEmitter = Objects.requireNonNull(toolEmitter, "toolEmitter");
    }

    @Override
    public GeneratedProjectFiles generate(GenerationContext context) {
        CompatibilityProfile profile = context == null ? null : context.profile();
        var project = new ProjectFileRenderer(profile);
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
        toolEmitter.emit(context).files().forEach(files::put);
        return new GeneratedProjectFiles(Collections.unmodifiableMap(files));
    }

    private byte[] utf8(String value) {
        return value.getBytes(UTF_8);
    }
}
