package io.gen2spring.mcp.adapter.emitter.springai2;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;

import io.gen2spring.mcp.adapter.emitter.support.BuildProjectScaffoldRegistry;
import io.gen2spring.mcp.application.port.outbound.GeneratedProjectFiles;
import io.gen2spring.mcp.application.usecase.GenerationContext;
import io.gen2spring.mcp.application.port.outbound.ProjectGenerator;
import io.gen2spring.mcp.application.port.outbound.ToolEmitter;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SpringAi2ProjectGenerator implements ProjectGenerator {
    private final ToolEmitter toolEmitter;
    private final BuildProjectScaffoldRegistry projectScaffolds;

    public SpringAi2ProjectGenerator() {
        this(new SpringAi2ToolEmitter(), BuildProjectScaffoldRegistry.defaults());
    }

    SpringAi2ProjectGenerator(ToolEmitter toolEmitter) {
        this(toolEmitter, BuildProjectScaffoldRegistry.defaults());
    }

    SpringAi2ProjectGenerator(
            ToolEmitter toolEmitter,
            BuildProjectScaffoldRegistry projectScaffolds) {
        this.toolEmitter = Objects.requireNonNull(toolEmitter, "toolEmitter");
        this.projectScaffolds = Objects.requireNonNull(projectScaffolds, "projectScaffolds");
    }

    @Override
    public GeneratedProjectFiles generate(GenerationContext context) {
        CompatibilityProfile profile = context == null ? null : context.profile();
        var renderer = new ProjectFileRenderer(profile);
        var scaffoldModel = renderer.scaffoldModel(context);
        Map<String, byte[]> files = new LinkedHashMap<>(projectScaffolds
                .require(profile.target().buildTool())
                .render(scaffoldModel));
        toolEmitter.emit(context).files().forEach((path, content) -> {
            if (files.putIfAbsent(path, content) != null) {
                throw GeneratorException.user(
                        SOURCE_GENERATION_FAILED,
                        "spring-ai-2-render",
                        "Generated project files contain duplicate paths");
            }
        });
        return new GeneratedProjectFiles(Collections.unmodifiableMap(files));
    }
}
