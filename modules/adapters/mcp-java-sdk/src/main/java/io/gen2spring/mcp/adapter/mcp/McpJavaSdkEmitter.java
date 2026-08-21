package io.gen2spring.mcp.adapter.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.managed.execution.ManagedToolCallHandler;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class McpJavaSdkEmitter {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JacksonMcpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapper(objectMapper);
    private final McpToolResultMapper resultMapper = new McpToolResultMapper();

    public List<McpServerFeatures.SyncToolSpecification> emit(
            List<RuntimeTool> tools,
            ManagedToolCallHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (tools == null) {
            throw invalid();
        }
        List<RuntimeTool> ordered = tools.stream()
                .map(tool -> Objects.requireNonNull(tool, "tool"))
                .sorted(Comparator.comparing(RuntimeTool::name).thenComparing(RuntimeTool::operationId))
                .toList();
        HashSet<String> names = new HashSet<>();
        if (ordered.stream().anyMatch(tool -> !names.add(tool.name()))) {
            throw invalid();
        }
        return ordered.stream().map(tool -> specification(tool, handler)).toList();
    }

    private McpServerFeatures.SyncToolSpecification specification(
            RuntimeTool runtimeTool,
            ManagedToolCallHandler handler) {
        McpSchema.Tool.Builder builder = McpSchema.Tool.builder()
                .name(runtimeTool.name())
                .description(runtimeTool.description())
                .inputSchema(mcpJsonMapper, json(runtimeTool.inputSchema()));
        if (!runtimeTool.outputSchema().isEmpty()) {
            builder.outputSchema(runtimeTool.outputSchema());
        }
        McpSchema.Tool tool = builder.build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments() == null
                                ? Map.of()
                                : Collections.unmodifiableMap(new LinkedHashMap<>(request.arguments()));
                        return resultMapper.map(handler.call(tool.name(), arguments));
                    } catch (Error fatal) {
                        throw fatal;
                    } catch (RuntimeException failure) {
                        throw new IllegalStateException("Managed Tool execution failed");
                    }
                })
                .build();
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Managed MCP Tool definition is invalid");
    }
}
