package io.gen2spring.mcp.application.managed.execution;

import java.util.Map;

@FunctionalInterface
public interface ManagedToolCallHandler {
    ManagedToolResult call(String toolName, Map<String, Object> arguments);
}
