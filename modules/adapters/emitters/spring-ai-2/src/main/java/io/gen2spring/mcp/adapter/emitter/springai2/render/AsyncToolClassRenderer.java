package io.gen2spring.mcp.adapter.emitter.springai2.render;

import io.gen2spring.mcp.domain.tool.OutputKind;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class AsyncToolClassRenderer {
    String render(String packageName, String domainClass, List<ToolDefinition> tools) {
        Set<String> imports = new TreeSet<>(Set.of(
                packageName + ".generated.metadata." + domainClass + "Operations",
                packageName + ".runtime.OpenApiOperationExecutor",
                "java.util.Map",
                "org.springframework.stereotype.Component",
                "reactor.core.publisher.Mono"));
        for (ToolDefinition tool : tools) {
            if (tool.outputKind() == OutputKind.TYPED_DTO) {
                imports.add(packageName + ".generated.model."
                        + JavaSourceRenderer.upperCamel(tool.operationId()) + "Result");
            } else {
                imports.add("tools.jackson.databind.JsonNode");
            }
        }
        StringBuilder source = new StringBuilder("package ")
                .append(packageName).append(".generated.tool;\n\n");
        imports.forEach(value -> source.append("import ").append(value).append(";\n"));
        source.append("\n@Component\npublic final class ").append(domainClass).append("McpTools {\n")
                .append("    private final OpenApiOperationExecutor executor;\n\n")
                .append("    public ").append(domainClass)
                .append("McpTools(OpenApiOperationExecutor executor) {\n")
                .append("        this.executor = executor;\n")
                .append("    }\n");
        for (ToolDefinition tool : tools) {
            appendMethod(source, domainClass, tool);
        }
        return source.append("}\n").toString();
    }

    private void appendMethod(StringBuilder source, String domainClass, ToolDefinition tool) {
        String operationClass = JavaSourceRenderer.upperCamel(tool.operationId());
        String resultType = tool.outputKind() == OutputKind.TYPED_DTO
                ? operationClass + "Result" : "JsonNode";
        source.append("\n    public Mono<").append(resultType).append("> ")
                .append(JavaSourceRenderer.lowerCamel(tool.operationId()))
                .append("(Map<String, Object> rawArguments) {\n")
                .append("        return executor.execute(")
                .append(domainClass).append("Operations.")
                .append(JavaSourceRenderer.constantName(tool.operationId()))
                .append(", rawArguments")
                .append(tool.outputKind() == OutputKind.TYPED_DTO
                        ? ", " + operationClass + "Result.class" : "")
                .append(");\n")
                .append("    }\n");
    }
}
