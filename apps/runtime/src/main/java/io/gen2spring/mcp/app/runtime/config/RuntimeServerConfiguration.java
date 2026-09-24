package io.gen2spring.mcp.app.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.adapter.mcp.McpJavaSdkEmitter;
import io.gen2spring.mcp.app.runtime.presentation.mcp.ManagedMcpRouter;
import io.gen2spring.mcp.app.runtime.presentation.mcp.RuntimeServerHandle;
import io.gen2spring.mcp.app.runtime.presentation.mcp.RuntimeServerHandleRegistry;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.managed.credential.service.RuntimeCredentialResolver;
import io.gen2spring.mcp.application.managed.execution.ManagedRuntimeBinding;
import io.gen2spring.mcp.application.managed.execution.service.ManagedExecutionContext;
import io.gen2spring.mcp.application.managed.execution.service.ManagedToolExecutor;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration(proxyBeanMethods = false)
final class RuntimeServerConfiguration {
    @Bean(destroyMethod = "close")
    RuntimeServerHandleRegistry runtimeServerHandleRegistry(
            ToolCatalogService catalogs,
            ManagedToolExecutor executor,
            RuntimeCredentialResolver credentials,
            RuntimeProperties properties,
            Clock clock) {
        McpJavaSdkEmitter emitter = new McpJavaSdkEmitter();
        ObjectMapper json = new ObjectMapper();
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(json);
        return new RuntimeServerHandleRegistry(access -> {
            var instance = access.instance();
            var catalog = catalogs.require(instance.owner(), instance.catalogId());
            ManagedRuntimeBinding binding = new ManagedRuntimeBinding(instance, catalog.metadata());
            ManagedExecutionContext context = new ManagedExecutionContext(access, binding, credentials);
            var tools = catalog.metadata().document().tools().stream()
                    .filter(tool -> access.allowedTools().contains(tool.name()))
                    .toList();
            var specifications = emitter.emitStateless(
                    tools,
                    (toolName, arguments) -> executor.call(context, toolName, arguments));
            String endpoint = "/mcp/" + instance.id().value();
            var transport = WebMvcStatelessServerTransport.builder()
                    .jsonMapper(mapper)
                    .messageEndpoint(endpoint)
                    .build();
            var server = McpServer.sync(transport)
                    .jsonMapper(mapper)
                    .serverInfo("gen2spring-managed-runtime", "1.0")
                    .requestTimeout(Duration.ofSeconds(30))
                    .tools(specifications)
                    .build();
            return RuntimeServerHandle.stateless(instance, transport, server);
        }, properties.cacheSize(), clock);
    }

    @Bean
    RouterFunction<ServerResponse> managedMcpRouter(RuntimeServerHandleRegistry handles) {
        return new ManagedMcpRouter(handles);
    }
}
