package io.gen2spring.mcp.adapter.mcp;

import io.gen2spring.mcp.application.managed.execution.ManagedToolResult;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class McpToolResultMapper {
    private final JacksonMcpJsonMapper jsonMapper;

    McpToolResultMapper(JacksonMcpJsonMapper jsonMapper) {
        this.jsonMapper = java.util.Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    McpSchema.CallToolResult map(ManagedToolResult result, boolean includeStructuredContent) {
        if (result == null) {
            throw failed();
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(result.json()))
                    .toString();
            McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(json)))
                    .isError(result.error());
            if (includeStructuredContent && !result.error()) {
                builder.structuredContent(jsonMapper, json);
            }
            return builder.build();
        } catch (RuntimeException failure) {
            throw failed();
        } catch (Error fatal) {
            throw fatal;
        } catch (Exception failure) {
            throw failed();
        }
    }

    private IllegalStateException failed() {
        return new IllegalStateException("Managed Tool result conversion failed");
    }
}
