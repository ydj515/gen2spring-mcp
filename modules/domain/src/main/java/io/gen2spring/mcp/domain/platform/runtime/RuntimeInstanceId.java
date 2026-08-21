package io.gen2spring.mcp.domain.platform.runtime;

import java.util.UUID;

public record RuntimeInstanceId(UUID value) {
    private static final String INVALID = "Platform identifier is invalid";

    public RuntimeInstanceId {
        if (value == null) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    public static RuntimeInstanceId parse(String value) {
        try {
            return new RuntimeInstanceId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(INVALID);
        }
    }
}
