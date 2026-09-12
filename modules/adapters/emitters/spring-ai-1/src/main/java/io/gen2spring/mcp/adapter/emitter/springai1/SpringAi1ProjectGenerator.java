package io.gen2spring.mcp.adapter.emitter.springai1;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;

import io.gen2spring.mcp.adapter.emitter.support.BuildProjectScaffoldRegistry;
import io.gen2spring.mcp.adapter.emitter.mcpruntime.McpRegistrationModel;
import io.gen2spring.mcp.adapter.emitter.mcpruntime.McpRegistrationSourceRenderer;
import io.gen2spring.mcp.application.validation.ExpectedToolSchemaFactory;
import io.gen2spring.mcp.domain.profile.McpImplementation;
import io.gen2spring.mcp.domain.tool.OutputKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
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

public final class SpringAi1ProjectGenerator implements ProjectGenerator {
    private final ToolEmitter toolEmitter;
    private final BuildProjectScaffoldRegistry projectScaffolds;

    public SpringAi1ProjectGenerator() {
        this(new SpringAi1ToolEmitter(), BuildProjectScaffoldRegistry.defaults());
    }

    SpringAi1ProjectGenerator(ToolEmitter toolEmitter) {
        this(toolEmitter, BuildProjectScaffoldRegistry.defaults());
    }

    SpringAi1ProjectGenerator(
            ToolEmitter toolEmitter,
            BuildProjectScaffoldRegistry projectScaffolds) {
        this.toolEmitter = Objects.requireNonNull(toolEmitter, "toolEmitter");
        this.projectScaffolds = Objects.requireNonNull(projectScaffolds, "projectScaffolds");
    }

    @Override
    public GeneratedProjectFiles generate(GenerationContext context) {
        CompatibilityProfile profile = context == null ? null : context.profile();
        var project = new ProjectFileRenderer(profile);
        var scaffoldModel = project.scaffoldModel(context);
        Map<String, byte[]> files = new LinkedHashMap<>(projectScaffolds
                .require(profile.target().buildTool())
                .render(scaffoldModel));
        toolEmitter.emit(context).files().forEach((path, content) -> {
            if (files.putIfAbsent(path, content) != null) {
                throw GeneratorException.user(
                        SOURCE_GENERATION_FAILED,
                        "spring-ai-1-render",
                        "Generated project files contain duplicate paths");
            }
        });
        applyMcpImplementation(context, files);
        return new GeneratedProjectFiles(Collections.unmodifiableMap(files));
    }
    private void applyMcpImplementation(GenerationContext context, Map<String, byte[]> files) {
        McpImplementation implementation = context.request().mcpImplementation();
        if (implementation == McpImplementation.SPRING_AI_EXPLICIT) {
            return;
        }
        if (!implementation.supports(context.profile())) {
            throw GeneratorException.user(SOURCE_GENERATION_FAILED, "MCP_RENDER",
                    "Unsupported MCP implementation profile");
        }
        String packageName = context.request().project().packageName();
        String domainClass = JavaSourceRenderer.upperCamel(context.request().domain());
        String toolRoot = "src/main/java/" + packageName.replace('.', '/') + "/generated/tool/";
        files.remove(toolRoot + domainClass + "McpTools.java");
        files.remove(toolRoot + domainClass + "McpToolCallbacks.java");
        files.remove(toolRoot + domainClass + "McpToolSpecifications.java");
        var schemas = new ExpectedToolSchemaFactory().create(context.tools());
        ObjectMapper json = new ObjectMapper();
        var tools = new ArrayList<McpRegistrationModel.Tool>();
        for (var tool : context.tools().stream().sorted(Comparator.comparing(value -> value.name())).toList()) {
            try {
                tools.add(new McpRegistrationModel.Tool(tool.name(), tool.description(), tool.operationId(),
                        JavaSourceRenderer.constantName(tool.operationId()),
                        tool.outputKind() == OutputKind.TYPED_DTO
                                ? JavaSourceRenderer.upperCamel(tool.operationId()) + "Result" : null,
                        json.writeValueAsString(schemas.get(tool.name()).inputSchema())));
            } catch (Exception failure) {
                throw GeneratorException.user(SOURCE_GENERATION_FAILED, "MCP_RENDER", "Generated Tool schema is invalid");
            }
        }
        var model = new McpRegistrationModel(packageName, domainClass, context.request().project().artifactId(),
                context.profile(), implementation, tools);
        new McpRegistrationSourceRenderer().render(model).forEach((path, source) ->
                files.put(path, source.getBytes(StandardCharsets.UTF_8)));
        String readme = new String(files.get("README.md"), StandardCharsets.UTF_8);
        if (implementation == McpImplementation.MCP_JAVA_SDK) {
            readme = readme.replace("- Spring AI " + context.profile().target().springAiVersion(), "- MCP Java SDK 0.18.3")
                    .replace("Spring AI", "MCP Java SDK")
                    + "\nThis project uses MCP Java SDK 0.18.3 and Spring Boot; it has no Spring AI dependencies.\n"
                    + "The compatibility profile identifies the Java, Boot, and build tool baseline.\n";
        } else {
            readme += "\nTool discovery uses Spring AI @McpTool annotations through the annotation provider.\n"
                    + "The global annotation scanner is disabled to prevent duplicate registration.\n"
                    + "GeneratedMcpRegistration preserves explicit OpenAPI input schemas and safe Tool call handling.\n";
        }
        files.put("README.md", readme.getBytes(StandardCharsets.UTF_8));
    }

}
