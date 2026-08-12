package io.gen2spring.mcp.adapter.container;

public final class SandboxRuntimeFailure extends RuntimeException {
    public SandboxRuntimeFailure() {
        super("Sandbox container execution failed", null, false, false);
    }
}
