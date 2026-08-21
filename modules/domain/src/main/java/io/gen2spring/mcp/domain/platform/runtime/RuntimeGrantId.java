package io.gen2spring.mcp.domain.platform.runtime;

import java.util.UUID;

public record RuntimeGrantId(UUID value) {
    private static final String INVALID = "Platform identifier is invalid";

    public RuntimeGrantId {
        if (value == null) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    public static RuntimeGrantId parse(String value) {
        try {
            return new RuntimeGrantId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(INVALID);
        }
    }
}
