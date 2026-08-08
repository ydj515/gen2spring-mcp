package io.gen2spring.mcp.springai2;

import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.McpInputDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class ToolCallbackConfigurationRenderer {
    String render(
            String packageName,
            String domainClass,
            List<McpToolDefinition> tools,
            Map<String, String> inputSchemas) {
        Set<String> imports = new TreeSet<>(Set.of(
                "java.lang.reflect.Method",
                "org.springframework.ai.tool.StaticToolCallbackProvider",
                "org.springframework.ai.tool.ToolCallbackProvider",
                "org.springframework.ai.tool.definition.DefaultToolDefinition",
                "org.springframework.ai.tool.method.MethodToolCallback",
                "org.springframework.context.annotation.Bean",
                "org.springframework.context.annotation.Configuration"));
        for (McpToolDefinition tool : tools) {
            for (McpInputDefinition input : InputRecordRenderer.inputs(tool)) {
                String type = JavaSourceRenderer.javaType(
                        input.schema(), JavaSourceRenderer.upperCamel(tool.operationId())
                                + JavaSourceRenderer.upperCamel(input.name()));
                if (type.startsWith("java.util.List<")) {
                    imports.add("java.util.List");
                }
                if (type.contains("java.math.BigDecimal")) {
                    imports.add("java.math.BigDecimal");
                }
                String simple = type.replace("java.math.", "").replace("java.util.List<", "")
                        .replace("<", "").replace(">", "");
                if (!Set.of("String", "Integer", "Long", "Boolean", "BigDecimal").contains(simple)) {
                    imports.add(packageName + ".generated.model." + simple);
                }
            }
        }
        StringBuilder source = new StringBuilder("package ").append(packageName).append(".generated.tool;\n\n");
        imports.forEach(value -> source.append("import ").append(value).append(";\n"));
        source.append("\n@Configuration\npublic class ").append(domainClass).append("McpToolCallbacks {\n")
                .append("    private final ").append(domainClass).append("McpTools tools;\n\n")
                .append("    public ").append(domainClass).append("McpToolCallbacks(")
                .append(domainClass).append("McpTools tools) {\n")
                .append("        this.tools = tools;\n")
                .append("    }\n\n")
                .append("    @Bean\n")
                .append("    public ToolCallbackProvider generatedToolCallbacks() {\n")
                .append("        return new StaticToolCallbackProvider(\n");
        for (int index = 0; index < tools.size(); index++) {
            McpToolDefinition tool = tools.get(index);
            String schema = inputSchemas.get(tool.name());
            if (schema == null) {
                throw JavaSourceRenderer.invalid("Explicit MCP Tool schemas must be present for every Tool");
            }
            source.append("                MethodToolCallback.builder()\n")
                    .append("                        .toolDefinition(DefaultToolDefinition.builder()\n")
                    .append("                                .name(").append(JavaStringLiteral.quote(tool.name())).append(")\n")
                    .append("                                .description(").append(JavaStringLiteral.quote(tool.description())).append(")\n")
                    .append("                                .inputSchema(").append(JavaStringLiteral.quote(schema)).append(")\n")
                    .append("                                .build())\n")
                    .append("                        .toolMethod(toolMethod(")
                    .append(JavaStringLiteral.quote(JavaSourceRenderer.lowerCamel(tool.operationId())));
            for (McpInputDefinition input : InputRecordRenderer.inputs(tool)) {
                source.append(", ").append(rawParameterClass(input.schema(),
                        JavaSourceRenderer.upperCamel(tool.operationId())
                                + JavaSourceRenderer.upperCamel(input.name())));
            }
            source.append("))\n")
                    .append("                        .toolObject(tools)\n")
                    .append("                        .build()")
                    .append(index + 1 == tools.size() ? "\n" : ",\n");
        }
        return source.append("        );\n")
                .append("    }\n\n")
                .append("    private static Method toolMethod(String name, Class<?>... parameterTypes) {\n")
                .append("        try {\n")
                .append("            return ").append(domainClass).append("McpTools.class.getMethod(name, parameterTypes);\n")
                .append("        } catch (NoSuchMethodException exception) {\n")
                .append("            throw new IllegalStateException(\"Generated Tool method is missing\", exception);\n")
                .append("        }\n")
                .append("    }\n")
                .append("}\n").toString();
    }

    private String rawParameterClass(ApiSchema schema, String suggestedName) {
        if (schema.enumValues() != null && !schema.enumValues().isEmpty()) {
            return "String.class";
        }
        String type = JavaSourceRenderer.javaType(schema, suggestedName);
        if (type.startsWith("java.util.List<")) {
            return "List.class";
        }
        return type.replace("java.math.", "").replace("java.util.", "") + ".class";
    }
}
