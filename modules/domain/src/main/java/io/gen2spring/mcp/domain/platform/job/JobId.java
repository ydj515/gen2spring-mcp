package io.gen2spring.mcp.domain.platform.job;

import java.util.UUID;

public record JobId(UUID value) {
    private static final String INVALID = "Platform identifier is invalid";

    public JobId {
        if (value == null) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    public static JobId parse(String value) {
        try {
            return new JobId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(INVALID);
        }
    }
}
