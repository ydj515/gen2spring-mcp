package io.gen2spring.mcp.application.managed.execution.port.in;

import io.gen2spring.mcp.application.managed.execution.ManagedToolResult;
import java.util.Map;

@FunctionalInterface
public interface ManagedToolCallHandler {
    ManagedToolResult call(String toolName, Map<String, Object> arguments);
}
