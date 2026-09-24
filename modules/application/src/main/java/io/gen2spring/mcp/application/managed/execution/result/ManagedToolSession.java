package io.gen2spring.mcp.application.managed.execution.result;

import io.gen2spring.mcp.application.managed.execution.port.in.ManagedToolCallHandler;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.util.List;
import java.util.Objects;

/** Runtime-specific Tool exposure and invocation contract, independent of the MCP SDK. */
public record ManagedToolSession(List<RuntimeTool> tools, ManagedToolCallHandler handler) {
    public ManagedToolSession {
        tools = List.copyOf(tools);
        Objects.requireNonNull(handler, "handler");
    }
}
