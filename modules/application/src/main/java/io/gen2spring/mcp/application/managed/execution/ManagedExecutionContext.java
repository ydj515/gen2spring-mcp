package io.gen2spring.mcp.application.managed.execution;

import io.gen2spring.mcp.application.managed.credential.RuntimeCredentialResolver;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import java.util.Objects;

public record ManagedExecutionContext(
        RuntimeAccess access,
        ManagedRuntimeBinding binding,
        RuntimeCredentialResolver credentials) {
    public ManagedExecutionContext {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(credentials, "credentials");
        if (!access.instance().equals(binding.instance())) {
            throw new IllegalArgumentException("Managed execution context is invalid");
        }
    }
}
