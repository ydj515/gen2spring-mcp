package io.gen2spring.mcp.adapter.emitter.springai1.render;

import io.gen2spring.mcp.adapter.emitter.support.source.JavaStringLiteral;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.ToolInput;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class ToolCallbackConfigurationRenderer {
    String render(
            String packageName,
            String domainClass,
            List<ToolDefinition> tools,
            Map<String, String> inputSchemas) {
        Set<String> imports = new TreeSet<>(Set.of(
                packageName + ".runtime.ProviderErrorException",
                packageName + ".runtime.RuntimeTelemetry",
                packageName + ".runtime.SchemaValueValidator",
                packageName + ".runtime.ToolArgumentContext",
                "io.modelcontextprotocol.server.McpServerFeatures",
                "io.modelcontextprotocol.spec.McpSchema",
                "java.lang.reflect.Method",
                "java.util.List",
                "java.util.Map",
                "org.springframework.ai.mcp.McpToolUtils",
                "org.springframework.ai.tool.definition.DefaultToolDefinition",
                "org.springframework.ai.tool.execution.ToolExecutionException",
                "org.springframework.ai.tool.method.MethodToolCallback",
                "org.springframework.context.annotation.Bean",
                "org.springframework.context.annotation.Configuration",
                "org.slf4j.Logger",
                "org.slf4j.LoggerFactory",
                "com.fasterxml.jackson.core.JsonProcessingException",
                "com.fasterxml.jackson.databind.ObjectMapper"));
        for (ToolDefinition tool : tools) {
            for (ToolInput input : InputRecordRenderer.inputs(tool)) {
                String type = JavaSourceRenderer.javaType(
                        input.schema(), JavaSourceRenderer.upperCamel(tool.operationId())
                                + JavaSourceRenderer.upperCamel(input.name()));
                if (type.endsWith(".JsonNode")) {
                    continue;
                }
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
                .append("    private static final Logger logger = LoggerFactory.getLogger(")
                .append(domainClass).append("McpToolCallbacks.class);\n\n")
                .append("    private final ").append(domainClass).append("McpTools tools;\n")
                .append("    private final RuntimeTelemetry runtimeTelemetry;\n\n")
                .append("    public ").append(domainClass).append("McpToolCallbacks(")
                .append(domainClass).append("McpTools tools, RuntimeTelemetry runtimeTelemetry) {\n")
                .append("        this.tools = tools;\n")
                .append("        this.runtimeTelemetry = runtimeTelemetry;\n")
                .append("    }\n\n")
                .append("    @Bean\n")
                .append("    public List<McpServerFeatures.SyncToolSpecification> generatedToolSpecifications(\n")
                .append("            ObjectMapper objectMapper) {\n")
                .append("        return List.of(\n");
        for (int index = 0; index < tools.size(); index++) {
            ToolDefinition tool = tools.get(index);
            String schema = inputSchemas.get(tool.name());
            if (schema == null) {
                throw JavaSourceRenderer.invalid("Explicit MCP Tool schemas must be present for every Tool");
            }
            source.append("                specification(MethodToolCallback.builder()\n")
                    .append("                        .toolDefinition(DefaultToolDefinition.builder()\n")
                    .append("                                .name(").append(JavaStringLiteral.quote(tool.name())).append(")\n")
                    .append("                                .description(").append(JavaStringLiteral.quote(tool.description())).append(")\n")
                    .append("                                .inputSchema(").append(JavaStringLiteral.quote(schema)).append(")\n")
                    .append("                                .build())\n")
                    .append("                        .toolMethod(toolMethod(")
                    .append(JavaStringLiteral.quote(JavaSourceRenderer.lowerCamel(tool.operationId())));
            for (ToolInput input : InputRecordRenderer.inputs(tool)) {
                source.append(", ").append(rawParameterClass(input.schema(),
                        JavaSourceRenderer.upperCamel(tool.operationId())
                                + JavaSourceRenderer.upperCamel(input.name())));
            }
            source.append("))\n")
                    .append("                        .toolObject(tools)\n")
                    .append("                        .build(), objectMapper, runtimeTelemetry, ")
                    .append(JavaStringLiteral.quote(tool.operationId())).append(", ")
                    .append(JavaStringLiteral.quote(schema)).append(")")
                    .append(index + 1 == tools.size() ? "\n" : ",\n");
        }
        return source.append("        );\n")
                .append("    }\n\n")
                .append("    private static McpServerFeatures.SyncToolSpecification specification(\n")
                .append("            MethodToolCallback callback,\n")
                .append("            ObjectMapper objectMapper,\n")
                .append("            RuntimeTelemetry runtimeTelemetry,\n")
                .append("            String operationId,\n")
                .append("            String inputSchema) {\n")
                .append("        McpSchema.Tool tool = McpToolUtils.toSyncToolSpecification(callback).tool();\n")
                .append("        Map<String, Object> parsedInputSchema = inputSchema(objectMapper, inputSchema);\n")
                .append("        SchemaValueValidator schemaValues = new SchemaValueValidator();\n")
                .append("        return McpServerFeatures.SyncToolSpecification.builder()\n")
                .append("                .tool(tool)\n")
                .append("                .callHandler((exchange, request) -> {\n")
                .append("                    RuntimeTelemetry.Call telemetryCall =\n")
                .append("                            runtimeTelemetry.startToolCall(tool.name(), operationId);\n")
                .append("                    try (var ignored = telemetryCall.openScope()) {\n")
                .append("                    try {\n")
                .append("                        Map<String, Object> rawArguments =\n")
                .append("                                request.arguments() == null ? Map.of() : request.arguments();\n")
                .append("                        schemaValues.validate(parsedInputSchema, rawArguments);\n")
                .append("                        String input = objectMapper.writeValueAsString(rawArguments);\n")
                .append("                        String output;\n")
                .append("                        try (var argumentContext = ToolArgumentContext.open(rawArguments)) {\n")
                .append("                            output = callback.call(input);\n")
                .append("                        }\n")
                .append("                        telemetryCall.complete(\n")
                .append("                                RuntimeTelemetry.Outcome.SUCCESS,\n")
                .append("                                RuntimeTelemetry.ErrorCategory.NONE,\n")
                .append("                                RuntimeTelemetry.HttpStatusClass.NONE);\n")
                .append("                        return McpSchema.CallToolResult.builder()\n")
                .append("                                .content(List.of(new McpSchema.TextContent(output)))\n")
                .append("                                .isError(false)\n")
                .append("                                .build();\n")
                .append("                    } catch (ToolExecutionException failure) {\n")
                .append("                        if (failure.getCause() instanceof ProviderErrorException providerFailure) {\n")
                .append("                            try {\n")
                .append("                                String output = objectMapper.writeValueAsString(\n")
                .append("                                        providerFailure.error().payload());\n")
                .append("                                telemetryCall.complete(\n")
                .append("                                        RuntimeTelemetry.Outcome.EXPECTED_ERROR,\n")
                .append("                                        RuntimeTelemetry.ErrorCategory.valueOf(\n")
                .append("                                                providerFailure.error().category().name()),\n")
                .append("                                        RuntimeTelemetry.HttpStatusClass.NONE);\n")
                .append("                                return McpSchema.CallToolResult.builder()\n")
                .append("                                        .content(List.of(new McpSchema.TextContent(output)))\n")
                .append("                                        .isError(true)\n")
                .append("                                        .build();\n")
                .append("                            } catch (JsonProcessingException serializationFailure) {\n")
                .append("                                telemetryCall.complete(\n")
                .append("                                        RuntimeTelemetry.Outcome.INTERNAL_ERROR,\n")
                .append("                                        RuntimeTelemetry.ErrorCategory.RESULT_CONVERSION,\n")
                .append("                                        RuntimeTelemetry.HttpStatusClass.NONE);\n")
                .append("                                logSafeFailure(tool.name(), serializationFailure);\n")
                .append("                                throw new IllegalStateException(\n")
                .append("                                        \"Generated Tool result conversion failed\");\n")
                .append("                            }\n")
                .append("                        }\n")
                .append("                        if (failure.getCause() instanceof Error fatal) {\n")
                .append("                            telemetryCall.complete(\n")
                .append("                                    RuntimeTelemetry.Outcome.FATAL,\n")
                .append("                                    RuntimeTelemetry.ErrorCategory.FATAL,\n")
                .append("                                    RuntimeTelemetry.HttpStatusClass.NONE);\n")
                .append("                            throw fatal;\n")
                .append("                        }\n")
                .append("                        telemetryCall.complete(\n")
                .append("                                RuntimeTelemetry.Outcome.INTERNAL_ERROR,\n")
                .append("                                RuntimeTelemetry.ErrorCategory.TOOL_EXECUTION,\n")
                .append("                                RuntimeTelemetry.HttpStatusClass.NONE);\n")
                .append("                        logSafeFailure(tool.name(), failure);\n")
                .append("                        throw new IllegalStateException(\"Generated Tool execution failed\");\n")
                .append("                    } catch (SchemaValueValidator.SchemaValueInvalid failure) {\n")
                .append("                        telemetryCall.complete(\n")
                .append("                                RuntimeTelemetry.Outcome.INTERNAL_ERROR,\n")
                .append("                                RuntimeTelemetry.ErrorCategory.ARGUMENT_CONVERSION,\n")
                .append("                                RuntimeTelemetry.HttpStatusClass.NONE);\n")
                .append("                        logSafeFailure(tool.name(), failure);\n")
                .append("                        throw new IllegalStateException(\"Generated Tool execution failed\");\n")
                .append("                    } catch (JsonProcessingException failure) {\n")
                .append("                        telemetryCall.complete(\n")
                .append("                                RuntimeTelemetry.Outcome.INTERNAL_ERROR,\n")
                .append("                                RuntimeTelemetry.ErrorCategory.ARGUMENT_CONVERSION,\n")
                .append("                                RuntimeTelemetry.HttpStatusClass.NONE);\n")
                .append("                        logSafeFailure(tool.name(), failure);\n")
                .append("                        throw new IllegalStateException(\"Generated Tool argument conversion failed\");\n")
                .append("                    } catch (RuntimeException failure) {\n")
                .append("                        telemetryCall.complete(\n")
                .append("                                RuntimeTelemetry.Outcome.INTERNAL_ERROR,\n")
                .append("                                RuntimeTelemetry.ErrorCategory.UNEXPECTED_RUNTIME,\n")
                .append("                                RuntimeTelemetry.HttpStatusClass.NONE);\n")
                .append("                        logSafeFailure(tool.name(), failure);\n")
                .append("                        throw new IllegalStateException(\"Generated Tool execution failed\");\n")
                .append("                    } catch (Error fatal) {\n")
                .append("                        telemetryCall.complete(\n")
                .append("                                RuntimeTelemetry.Outcome.FATAL,\n")
                .append("                                RuntimeTelemetry.ErrorCategory.FATAL,\n")
                .append("                                RuntimeTelemetry.HttpStatusClass.NONE);\n")
                .append("                        throw fatal;\n")
                .append("                    }\n")
                .append("                    }\n")
                .append("                })\n")
                .append("                .build();\n")
                .append("    }\n\n")
                .append("    @SuppressWarnings(\"unchecked\")\n")
                .append("    private static Map<String, Object> inputSchema(ObjectMapper objectMapper, String schema) {\n")
                .append("        try {\n")
                .append("            return objectMapper.readValue(schema, Map.class);\n")
                .append("        } catch (JsonProcessingException failure) {\n")
                .append("            throw new IllegalStateException(\"Generated Tool schema is invalid\");\n")
                .append("        }\n")
                .append("    }\n\n")
                .append("    private static void logSafeFailure(String toolName, Throwable failure) {\n")
                .append("        Throwable cause = failure.getCause();\n")
                .append("        logger.error(\n")
                .append("                \"generated_tool_adapter_failure tool={} exception={} cause={}\",\n")
                .append("                toolName,\n")
                .append("                failure.getClass().getName(),\n")
                .append("                cause == null ? \"none\" : cause.getClass().getName());\n")
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
