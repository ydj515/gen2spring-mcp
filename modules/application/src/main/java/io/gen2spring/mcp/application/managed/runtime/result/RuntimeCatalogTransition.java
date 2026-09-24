package io.gen2spring.mcp.application.managed.runtime.result;

import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record RuntimeCatalogTransition(
        long sequence,
        RuntimeInstanceId runtimeId,
        UUID sourceCatalogId,
        String sourceChecksum,
        UUID targetCatalogId,
        String targetChecksum,
        String diffChecksum,
        TransitionKind kind,
        Instant createdAt) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public RuntimeCatalogTransition {
        Objects.requireNonNull(runtimeId, "runtimeId");
        Objects.requireNonNull(sourceCatalogId, "sourceCatalogId");
        Objects.requireNonNull(targetCatalogId, "targetCatalogId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sequence < 1
                || sourceCatalogId.equals(targetCatalogId)
                || !checksum(sourceChecksum)
                || !checksum(targetChecksum)
                || !checksum(diffChecksum)) {
            throw new IllegalArgumentException("Runtime Catalog transition is invalid");
        }
    }

    private static boolean checksum(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }
}
