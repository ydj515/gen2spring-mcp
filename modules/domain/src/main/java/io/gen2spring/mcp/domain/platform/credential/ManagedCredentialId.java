package io.gen2spring.mcp.domain.platform.credential;

import java.util.UUID;

public record ManagedCredentialId(UUID value) {
    private static final String INVALID = "Platform identifier is invalid";

    public ManagedCredentialId {
        if (value == null) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    public static ManagedCredentialId parse(String value) {
        try {
            return new ManagedCredentialId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(INVALID);
        }
    }
}
