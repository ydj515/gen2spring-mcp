package io.gen2spring.mcp.app.web.hosted;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.gen2spring.mcp.application.managed.runtime.RuntimeActivation;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
record ManagedRuntimeResponse(
        String runtimeId,
        String catalogId,
        String catalogChecksum,
        String state,
        String createdAt,
        String expiresAt,
        String revokedAt,
        String endpoint,
        String token) {

    static ManagedRuntimeResponse activated(RuntimeActivation activation, Instant now) {
        ManagedRuntimeInstance instance = activation.instance();
        return response(
                instance,
                now,
                activation.endpoint().toASCIIString(),
                activation.plaintextToken());
    }

    static ManagedRuntimeResponse details(ManagedRuntimeInstance instance, Instant now) {
        return response(instance, now, null, null);
    }

    private static ManagedRuntimeResponse response(
            ManagedRuntimeInstance instance,
            Instant now,
            String endpoint,
            String token) {
        return new ManagedRuntimeResponse(
                instance.id().value().toString(),
                instance.catalogId().toString(),
                instance.catalogChecksum(),
                instance.stateAt(now).name(),
                instance.createdAt().toString(),
                instance.expiresAt().toString(),
                instance.revokedAt().map(Instant::toString).orElse(null),
                endpoint,
                token);
    }
}
