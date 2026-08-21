package io.gen2spring.mcp.application.managed.runtime;

import java.util.Arrays;

public final class RuntimeTokenDigest {
    private static final int BYTES = 32;
    private final byte[] value;

    public RuntimeTokenDigest(byte[] value) {
        if (value == null || value.length != BYTES) {
            throw new IllegalArgumentException("Runtime token digest is invalid");
        }
        this.value = value.clone();
    }

    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RuntimeTokenDigest digest && Arrays.equals(value, digest.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "RuntimeTokenDigest[redacted]";
    }
}
