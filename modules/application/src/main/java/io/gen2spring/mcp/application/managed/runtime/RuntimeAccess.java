package io.gen2spring.mcp.application.managed.runtime;

import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import java.util.Objects;

public record RuntimeAccess(ManagedRuntimeInstance instance) {
    public RuntimeAccess {
        Objects.requireNonNull(instance, "instance");
    }
}
