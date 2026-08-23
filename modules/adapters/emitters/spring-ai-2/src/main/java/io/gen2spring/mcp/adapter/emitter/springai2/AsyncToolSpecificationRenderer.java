package io.gen2spring.mcp.adapter.emitter.springai2;

import io.gen2spring.mcp.adapter.emitter.support.JavaStringLiteral;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.util.List;
import java.util.Map;

final class AsyncToolSpecificationRenderer {
    String render(
            String packageName,
            String domainClass,
            List<ToolDefinition> tools,
            Map<String, String> inputSchemas) {
        StringBuilder specifications = new StringBuilder();
        for (int index = 0; index < tools.size(); index++) {
            ToolDefinition tool = tools.get(index);
            String schema = inputSchemas.get(tool.name());
            if (schema == null) {
                throw JavaSourceRenderer.invalid("Explicit MCP Tool schemas must be present for every Tool");
            }
            specifications.append("                specification(\n")
                    .append("                        ")
                    .append(JavaStringLiteral.quote(tool.name())).append(",\n")
                    .append("                        ")
                    .append(JavaStringLiteral.quote(tool.description())).append(",\n")
                    .append("                        ")
                    .append(JavaStringLiteral.quote(tool.operationId())).append(",\n")
                    .append("                        ")
                    .append(JavaStringLiteral.quote(schema)).append(",\n")
                    .append("                        tools::")
                    .append(JavaSourceRenderer.lowerCamel(tool.operationId())).append(",\n")
                    .append("                        jsonMapper)")
                    .append(index + 1 == tools.size() ? "\n" : ",\n");
        }
        return """
                package %s.generated.tool;

                import %s.runtime.ProviderErrorException;
                import %s.runtime.RuntimeTelemetry;
                import %s.runtime.SchemaValueValidator;
                import io.modelcontextprotocol.server.McpServerFeatures;
                import io.modelcontextprotocol.spec.McpSchema;
                import java.util.Collections;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import reactor.core.publisher.Mono;
                import tools.jackson.core.JacksonException;
                import tools.jackson.databind.json.JsonMapper;

                @Configuration
                public class %sMcpToolSpecifications {
                    private static final Logger logger = LoggerFactory.getLogger(
                            %sMcpToolSpecifications.class);

                    private final %sMcpTools tools;
                    private final RuntimeTelemetry runtimeTelemetry;

                    public %sMcpToolSpecifications(
                            %sMcpTools tools,
                            RuntimeTelemetry runtimeTelemetry) {
                        this.tools = tools;
                        this.runtimeTelemetry = runtimeTelemetry;
                    }

                    @Bean
                    public List<McpServerFeatures.AsyncToolSpecification> generatedToolSpecifications(
                            JsonMapper jsonMapper) {
                        return List.of(
                %s        );
                    }

                    private McpServerFeatures.AsyncToolSpecification specification(
                            String toolName,
                            String description,
                            String operationId,
                            String inputSchema,
                            AsyncToolInvocation invocation,
                            JsonMapper jsonMapper) {
                        Map<String, Object> parsedInputSchema = inputSchema(jsonMapper, inputSchema);
                        SchemaValueValidator schemaValues = new SchemaValueValidator();
                        McpSchema.Tool tool = McpSchema.Tool.builder(toolName, parsedInputSchema)
                                .description(description)
                                .build();
                        return McpServerFeatures.AsyncToolSpecification.builder()
                                .tool(tool)
                                .callHandler((exchange, request) -> Mono.defer(() -> {
                                    RuntimeTelemetry.Call telemetryCall =
                                            runtimeTelemetry.startToolCall(toolName, operationId);
                                    try (var ignored = telemetryCall.openScope()) {
                                        Map<String, Object> rawArguments = immutableArguments(request.arguments());
                                        schemaValues.validate(parsedInputSchema, rawArguments);
                                        Mono<McpSchema.CallToolResult> result = invocation.invoke(rawArguments)
                                                .map(value -> successResult(jsonMapper, value))
                                                .onErrorResume(
                                                        ProviderErrorException.class,
                                                        failure -> Mono.fromSupplier(() -> expectedErrorResult(
                                                                jsonMapper, telemetryCall, failure)))
                                                .doOnSuccess(value -> {
                                                    if (!Boolean.TRUE.equals(value.isError())) {
                                                        telemetryCall.complete(
                                                                RuntimeTelemetry.Outcome.SUCCESS,
                                                                RuntimeTelemetry.ErrorCategory.NONE,
                                                                RuntimeTelemetry.HttpStatusClass.NONE);
                                                    }
                                                })
                                                .doOnCancel(() -> telemetryCall.complete(
                                                        RuntimeTelemetry.Outcome.CANCELLED,
                                                        RuntimeTelemetry.ErrorCategory.NONE,
                                                        RuntimeTelemetry.HttpStatusClass.NONE))
                                                .onErrorMap(failure -> safeFailure(
                                                        telemetryCall, toolName, failure));
                                        return runtimeTelemetry.propagateCurrentSpan(result);
                                    } catch (SchemaValueValidator.SchemaValueInvalid failure) {
                                        telemetryCall.complete(
                                                RuntimeTelemetry.Outcome.INTERNAL_ERROR,
                                                RuntimeTelemetry.ErrorCategory.ARGUMENT_CONVERSION,
                                                RuntimeTelemetry.HttpStatusClass.NONE);
                                        logSafeFailure(toolName, failure);
                                        return Mono.error(new IllegalStateException(
                                                "Generated Tool argument conversion failed"));
                                    } catch (RuntimeException failure) {
                                        telemetryCall.complete(
                                                RuntimeTelemetry.Outcome.INTERNAL_ERROR,
                                                RuntimeTelemetry.ErrorCategory.UNEXPECTED_RUNTIME,
                                                RuntimeTelemetry.HttpStatusClass.NONE);
                                        logSafeFailure(toolName, failure);
                                        return Mono.error(new IllegalStateException(
                                                "Generated Tool execution failed"));
                                    } catch (Error fatal) {
                                        telemetryCall.complete(
                                                RuntimeTelemetry.Outcome.FATAL,
                                                RuntimeTelemetry.ErrorCategory.FATAL,
                                                RuntimeTelemetry.HttpStatusClass.NONE);
                                        throw fatal;
                                    }
                                }))
                                .build();
                    }

                    private McpSchema.CallToolResult successResult(JsonMapper jsonMapper, Object result) {
                        try {
                            return McpSchema.CallToolResult.builder()
                                    .content(List.of(new McpSchema.TextContent(
                                            jsonMapper.writeValueAsString(result))))
                                    .isError(false)
                                    .build();
                        } catch (JacksonException failure) {
                            throw new ResultConversionException();
                        }
                    }

                    private McpSchema.CallToolResult expectedErrorResult(
                            JsonMapper jsonMapper,
                            RuntimeTelemetry.Call telemetryCall,
                            ProviderErrorException failure) {
                        try {
                            String output = jsonMapper.writeValueAsString(failure.error().payload());
                            telemetryCall.complete(
                                    RuntimeTelemetry.Outcome.EXPECTED_ERROR,
                                    RuntimeTelemetry.ErrorCategory.valueOf(
                                            failure.error().category().name()),
                                    RuntimeTelemetry.HttpStatusClass.NONE);
                            return McpSchema.CallToolResult.builder()
                                    .content(List.of(new McpSchema.TextContent(output)))
                                    .isError(true)
                                    .build();
                        } catch (JacksonException serializationFailure) {
                            throw new ResultConversionException();
                        }
                    }

                    private RuntimeException safeFailure(
                            RuntimeTelemetry.Call telemetryCall,
                            String toolName,
                            Throwable failure) {
                        if (failure instanceof Error fatal) {
                            telemetryCall.complete(
                                    RuntimeTelemetry.Outcome.FATAL,
                                    RuntimeTelemetry.ErrorCategory.FATAL,
                                    RuntimeTelemetry.HttpStatusClass.NONE);
                            throw fatal;
                        }
                        RuntimeTelemetry.ErrorCategory category = failure instanceof ResultConversionException
                                ? RuntimeTelemetry.ErrorCategory.RESULT_CONVERSION
                                : RuntimeTelemetry.ErrorCategory.UNEXPECTED_RUNTIME;
                        telemetryCall.complete(
                                RuntimeTelemetry.Outcome.INTERNAL_ERROR,
                                category,
                                RuntimeTelemetry.HttpStatusClass.NONE);
                        logSafeFailure(toolName, failure);
                        return new IllegalStateException("Generated Tool execution failed");
                    }

                    private Map<String, Object> immutableArguments(Map<String, Object> arguments) {
                        if (arguments == null || arguments.isEmpty()) {
                            return Map.of();
                        }
                        return Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
                    }

                    @SuppressWarnings("unchecked")
                    private Map<String, Object> inputSchema(JsonMapper jsonMapper, String schema) {
                        try {
                            return jsonMapper.readValue(schema, Map.class);
                        } catch (JacksonException failure) {
                            throw new IllegalStateException("Generated Tool schema is invalid");
                        }
                    }

                    private void logSafeFailure(String toolName, Throwable failure) {
                        Throwable cause = failure.getCause();
                        logger.error(
                                "generated_tool_adapter_failure tool={} exception={} cause={}",
                                toolName,
                                failure.getClass().getName(),
                                cause == null ? "none" : cause.getClass().getName());
                    }

                    @FunctionalInterface
                    private interface AsyncToolInvocation {
                        Mono<?> invoke(Map<String, Object> rawArguments);
                    }

                    private static final class ResultConversionException extends RuntimeException {}
                }
                """.formatted(
                packageName,
                packageName,
                packageName,
                packageName,
                domainClass,
                domainClass,
                domainClass,
                domainClass,
                domainClass,
                specifications);
    }
}
