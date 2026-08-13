package io.gen2spring.mcp.domain.platform.identity;

import java.util.UUID;

public record AccountId(UUID value) {
    private static final String INVALID = "Platform identifier is invalid";

    public AccountId {
        if (value == null) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    public static AccountId parse(String value) {
        try {
            return new AccountId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(INVALID);
        }
    }
}
