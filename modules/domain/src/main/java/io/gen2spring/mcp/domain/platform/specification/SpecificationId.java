package io.gen2spring.mcp.domain.platform.specification;

import java.util.UUID;

public record SpecificationId(UUID value) {
    private static final String INVALID = "Platform identifier is invalid";

    public SpecificationId {
        if (value == null) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    public static SpecificationId parse(String value) {
        try {
            return new SpecificationId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(INVALID);
        }
    }
}
