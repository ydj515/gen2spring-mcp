package io.gen2spring.mcp.adapter.mcp;

import io.gen2spring.mcp.application.managed.execution.ManagedToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class McpToolResultMapper {
    McpSchema.CallToolResult map(ManagedToolResult result) {
        if (result == null) {
            throw failed();
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(result.json()))
                    .toString();
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(json)))
                    .isError(result.error())
                    .build();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw failed();
        }
    }

    private IllegalStateException failed() {
        return new IllegalStateException("Managed Tool result conversion failed");
    }
}
