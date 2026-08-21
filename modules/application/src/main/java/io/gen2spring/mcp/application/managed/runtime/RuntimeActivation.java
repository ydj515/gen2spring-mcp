package io.gen2spring.mcp.application.managed.runtime;

import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import java.net.URI;
import java.util.Objects;

public record RuntimeActivation(
        ManagedRuntimeInstance instance,
        String plaintextToken,
        URI endpoint) {
    public RuntimeActivation {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(endpoint, "endpoint");
        if (plaintextToken == null || plaintextToken.isBlank()) {
            throw new IllegalArgumentException("Runtime activation is invalid");
        }
    }

    @Override
    public String toString() {
        return "RuntimeActivation[instance=" + instance + ", plaintextToken=redacted, endpoint=" + endpoint + "]";
    }
}
