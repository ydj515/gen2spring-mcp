package io.gen2spring.mcp.adapter.emitter.mcpruntime;

import io.gen2spring.mcp.adapter.emitter.support.JavaStringLiteral;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Renders MCP registration without coupling the provider execution engine to a framework. */
public final class McpRegistrationSourceRenderer {
    public Map<String, String> render(McpRegistrationModel model) {
        String root = "src/main/java/" + model.packageName().replace('.', '/') + "/generated/tool/";
        Map<String, String> files = new LinkedHashMap<>();
        files.put(root + "GeneratedToolCalls.java", calls(model));
        if (model.implementation() == io.gen2spring.mcp.domain.profile.McpImplementation.MCP_JAVA_SDK) {
            files.put(root + "GeneratedMcpServerConfiguration.java", sdkServer(model));
        } else {
            files.put(root + model.domainClass() + "McpTools.java", annotatedTools(model));
            files.put(root + "GeneratedMcpRegistration.java", annotationRegistration(model));
        }
        return Map.copyOf(files);
    }

    private String calls(McpRegistrationModel model) {
        String mapper = model.jackson3() ? "tools.jackson.databind.json.JsonMapper"
                : "com.fasterxml.jackson.databind.ObjectMapper";
        String mapperName = model.jackson3() ? "JsonMapper" : "ObjectMapper";
        String invocations = model.tools().stream().map(tool -> "        define(" + quote(tool.name()) + ", "
                + quote(tool.description()) + ", " + quote(tool.operationId()) + ", " + quote(tool.inputSchema())
                + ", arguments -> executor.execute(" + model.domainClass() + "Operations."
                + tool.operationConstant() + ", arguments" + (tool.resultClass() == null ? ""
                : ", " + model.packageName() + ".generated.model." + tool.resultClass() + ".class") + "));")
                .collect(Collectors.joining("\n"));
        String source = """
                package %s.generated.tool;

                import %s.generated.metadata.%sOperations;
                import %s.runtime.OpenApiOperationExecutor;
                import %s.runtime.ProviderErrorException;
                import %s.runtime.RuntimeTelemetry;
                import %s.runtime.SchemaValueValidator;
                import %s;
                import io.modelcontextprotocol.json.McpJsonDefaults;
                import io.modelcontextprotocol.spec.McpSchema;
                import java.util.Collections;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import org.springframework.stereotype.Component;

                @Component
                public final class GeneratedToolCalls {
                    private final %s json;
                    private final RuntimeTelemetry telemetry;
                    private final SchemaValueValidator validator = new SchemaValueValidator();
                    private final Map<String, Definition> definitions = new LinkedHashMap<>();

                    public GeneratedToolCalls(OpenApiOperationExecutor executor, %s json, RuntimeTelemetry telemetry) {
                        this.json = json;
                        this.telemetry = telemetry;
                %s
                    }

                    @SuppressWarnings("unchecked")
                    private void define(String name, String description, String operationId, String schema, Invocation invocation) {
                        try {
                            Map<String, Object> parsed = json.readValue(schema, Map.class);
                            McpSchema.Tool tool = McpSchema.Tool.builder().name(name).description(description)
                                    .inputSchema(McpJsonDefaults.getMapper(), schema).build();
                            if (definitions.putIfAbsent(name, new Definition(tool, operationId, parsed, invocation)) != null) {
                                throw new IllegalStateException("Generated Tool names are duplicated");
                            }
                        } catch (Exception failure) {
                            throw new IllegalStateException("Generated Tool schema is invalid");
                        }
                    }

                    public List<McpSchema.Tool> tools() {
                        return definitions.values().stream().map(Definition::tool).toList();
                    }

                    public McpSchema.Tool tool(String name) {
                        return definition(name).tool();
                    }

                    private Definition definition(String name) {
                        Definition definition = definitions.get(name);
                        if (definition == null) {
                            throw new IllegalArgumentException("Generated Tool is unavailable");
                        }
                        return definition;
                    }

                    public McpSchema.CallToolResult call(String name, Map<String, Object> arguments) {
                        Definition definition = definition(name);
                        RuntimeTelemetry.Call call = telemetry.startToolCall(name, definition.operationId());
                        try (var scope = call.openScope()) {
                            try {
                                Map<String, Object> input = immutableArguments(arguments);
                                validator.validate(definition.schema(), input);
                                McpSchema.CallToolResult result = result(definition.invocation().invoke(input), false);
                                call.complete(RuntimeTelemetry.Outcome.SUCCESS, RuntimeTelemetry.ErrorCategory.NONE,
                                        RuntimeTelemetry.HttpStatusClass.NONE);
                                return result;
                            } catch (ProviderErrorException failure) {
                                return providerError(call, failure);
                            } catch (Throwable failure) {
                                throw safeFailure(call, failure);
                            }
                        }
                    }

                    private McpSchema.CallToolResult providerError(RuntimeTelemetry.Call call, ProviderErrorException failure) {
                        try {
                            McpSchema.CallToolResult result = result(failure.error().payload(), true);
                            call.complete(RuntimeTelemetry.Outcome.EXPECTED_ERROR,
                                    RuntimeTelemetry.ErrorCategory.valueOf(failure.error().category().name()),
                                    RuntimeTelemetry.HttpStatusClass.NONE);
                            return result;
                        } catch (Throwable conversionFailure) {
                            throw safeFailure(call, conversionFailure);
                        }
                    }

                    private RuntimeException safeFailure(RuntimeTelemetry.Call call, Throwable failure) {
                        if (failure instanceof Error fatal) {
                            call.complete(RuntimeTelemetry.Outcome.FATAL, RuntimeTelemetry.ErrorCategory.FATAL,
                                    RuntimeTelemetry.HttpStatusClass.NONE);
                            throw fatal;
                        }
                        RuntimeTelemetry.ErrorCategory category = failure instanceof SchemaValueValidator.SchemaValueInvalid
                                ? RuntimeTelemetry.ErrorCategory.ARGUMENT_CONVERSION
                                : failure instanceof ResultConversionException ? RuntimeTelemetry.ErrorCategory.RESULT_CONVERSION
                                : RuntimeTelemetry.ErrorCategory.UNEXPECTED_RUNTIME;
                        call.complete(RuntimeTelemetry.Outcome.INTERNAL_ERROR, category, RuntimeTelemetry.HttpStatusClass.NONE);
                        return new IllegalStateException("Generated Tool execution failed");
                    }

                    private McpSchema.CallToolResult result(Object value, boolean error) {
                        try {
                            return McpSchema.CallToolResult.builder()
                                    .content(List.of(new McpSchema.TextContent(json.writeValueAsString(value))))
                                    .isError(error).build();
                        } catch (Exception failure) {
                            throw new ResultConversionException();
                        }
                    }

                    private Map<String, Object> immutableArguments(Map<String, Object> arguments) {
                        return arguments == null ? Map.of()
                                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
                    }

                    private static final class ResultConversionException extends RuntimeException {}
                    private record Definition(McpSchema.Tool tool, String operationId,
                                              Map<String, Object> schema, Invocation invocation) {}
                    @FunctionalInterface
                    private interface Invocation { Object invoke(Map<String, Object> arguments); }
                }
                """.formatted(model.packageName(), model.packageName(), model.domainClass(), model.packageName(),
                model.packageName(), model.packageName(), model.packageName(), mapper, mapperName, mapperName, invocations);
        if (!model.reactive()) {
            return source;
        }
        int start = source.indexOf("    public McpSchema.CallToolResult call(");
        int end = source.indexOf("    private McpSchema.CallToolResult providerError(", start);
        source = source.substring(0, start) + """
                    public Mono<McpSchema.CallToolResult> call(String name, Map<String, Object> arguments) {
                        return Mono.defer(() -> {
                            Definition definition = definition(name);
                            RuntimeTelemetry.Call call = telemetry.startToolCall(name, definition.operationId());
                            try (var scope = call.openScope()) {
                                try {
                                    Map<String, Object> input = immutableArguments(arguments);
                                    validator.validate(definition.schema(), input);
                                    Mono<McpSchema.CallToolResult> result = definition.invocation().invoke(input)
                                            .map(value -> result(value, false))
                                            .switchIfEmpty(Mono.error(new IllegalStateException("Generated Tool result is empty")))
                                            .onErrorResume(ProviderErrorException.class,
                                                    failure -> Mono.fromSupplier(() -> providerError(call, failure)))
                                            .doOnNext(value -> {
                                                if (!Boolean.TRUE.equals(value.isError())) {
                                                    call.complete(RuntimeTelemetry.Outcome.SUCCESS,
                                                            RuntimeTelemetry.ErrorCategory.NONE, RuntimeTelemetry.HttpStatusClass.NONE);
                                                }
                                            })
                                            .doOnCancel(() -> call.complete(RuntimeTelemetry.Outcome.CANCELLED,
                                                    RuntimeTelemetry.ErrorCategory.NONE, RuntimeTelemetry.HttpStatusClass.NONE))
                                            .onErrorMap(failure -> safeFailure(call, failure));
                                    return telemetry.propagateCurrentSpan(result);
                                } catch (Throwable failure) {
                                    return Mono.error(safeFailure(call, failure));
                                }
                            }
                        });
                    }

                """ + source.substring(end);
        return source.replace("import org.springframework.stereotype.Component;",
                        "import org.springframework.stereotype.Component;\nimport reactor.core.publisher.Mono;")
                .replace("interface Invocation { Object invoke(", "interface Invocation { Mono<?> invoke(");
    }

    private String annotatedTools(McpRegistrationModel model) {
        String annotationPackage = model.jackson3() ? "org.springframework.ai.mcp.annotation"
                : "org.springaicommunity.mcp.annotation";
        String returnType = model.reactive() ? "Mono<McpSchema.CallToolResult>" : "McpSchema.CallToolResult";
        String methods = model.tools().stream().map(tool -> "\n    @McpTool(name = " + quote(tool.name())
                + ", description = " + quote(tool.description()) + ")\n    public " + returnType
                + " " + tool.operationConstant().toLowerCase(java.util.Locale.ROOT)
                + "(McpSchema.CallToolRequest request) {\n        return calls.call(" + quote(tool.name())
                + ", request.arguments());\n    }\n").collect(Collectors.joining());
        return """
                package %s.generated.tool;

                import %s.McpTool;
                import io.modelcontextprotocol.spec.McpSchema;
                import org.springframework.stereotype.Component;
                %s
                @Component
                public final class %sMcpTools {
                    private final GeneratedToolCalls calls;

                    public %sMcpTools(GeneratedToolCalls calls) {
                        this.calls = calls;
                    }
                %s
                }
                """.formatted(model.packageName(), annotationPackage,
                model.reactive() ? "import reactor.core.publisher.Mono;\n" : "", model.domainClass(), model.domainClass(), methods);
    }

    private String annotationRegistration(McpRegistrationModel model) {
        String providerRoot = model.jackson3() ? "org.springframework.ai.mcp.annotation.provider.tool"
                : "org.springaicommunity.mcp.provider.tool";
        String prefix = model.reactive() ? "Async" : "Sync";
        return """
                package %s.generated.tool;

                import %s.%sMcpToolProvider;
                import io.modelcontextprotocol.server.McpServerFeatures;
                import java.util.Comparator;
                import java.util.List;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration(proxyBeanMethods = false)
                public class GeneratedMcpRegistration {
                    @Bean
                    public List<McpServerFeatures.%sToolSpecification> generatedToolSpecifications(
                            %sMcpTools tools, GeneratedToolCalls calls) {
                        // Discover annotations once; retain the original OpenAPI schemas instead of inferring them.
                        var provider = new %sMcpToolProvider(List.of(tools)) {
                            @Override
                            protected Class<? extends Throwable> doGetToolCallException() {
                                return UnreportedToolException.class;
                            }
                        };
                        return provider.getToolSpecifications().stream()
                                .sorted(Comparator.comparing(specification -> specification.tool().name()))
                                .map(specification -> McpServerFeatures.%sToolSpecification.builder()
                                        .tool(calls.tool(specification.tool().name()))
                                        .callHandler(specification.callHandler()).build())
                                .toList();
                    }

                    private static final class UnreportedToolException extends Exception {}
                }
                """.formatted(model.packageName(), providerRoot, prefix, prefix, model.domainClass(), prefix, prefix);
    }

    private String sdkServer(McpRegistrationModel model) {
        return """
                package %s.generated.tool;

                import io.modelcontextprotocol.json.McpJsonDefaults;
                import io.modelcontextprotocol.server.McpServer;
                import io.modelcontextprotocol.server.McpServerFeatures;
                import io.modelcontextprotocol.server.McpSyncServer;
                import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
                import io.modelcontextprotocol.spec.McpSchema;
                import java.time.Duration;
                import org.springframework.boot.web.servlet.ServletRegistrationBean;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration(proxyBeanMethods = false)
                public class GeneratedMcpServerConfiguration {
                    @Bean(destroyMethod = "")
                    public HttpServletStreamableServerTransportProvider mcpTransport() {
                        return HttpServletStreamableServerTransportProvider.builder()
                                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/mcp").build();
                    }

                    @Bean
                    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
                            HttpServletStreamableServerTransportProvider transport) {
                        var registration = new ServletRegistrationBean<>(transport, "/mcp");
                        registration.setAsyncSupported(true);
                        return registration;
                    }

                    @Bean(destroyMethod = "close")
                    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport,
                                                   GeneratedToolCalls calls) {
                        var specifications = calls.tools().stream()
                                .map(tool -> McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                                        .callHandler((exchange, request) -> calls.call(tool.name(), request.arguments()))
                                        .build()).toList();
                        return McpServer.sync(transport).jsonMapper(McpJsonDefaults.getMapper())
                                .serverInfo(%s, "0.1.0")
                                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                                .requestTimeout(Duration.ofSeconds(30)).tools(specifications).build();
                    }
                }
                """.formatted(model.packageName(), quote(model.artifactId()));
    }

    private String quote(String value) {
        return JavaStringLiteral.quote(value);
    }
}
