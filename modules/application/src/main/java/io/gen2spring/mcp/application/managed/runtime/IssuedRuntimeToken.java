package io.gen2spring.mcp.application.managed.runtime;

import java.util.Objects;

public record IssuedRuntimeToken(String plaintext, RuntimeTokenDigest digest) {
    public IssuedRuntimeToken {
        Objects.requireNonNull(digest, "digest");
        if (plaintext == null || plaintext.length() < 8 || plaintext.length() > 512
                || !plaintext.startsWith("g2s_rt_")
                || plaintext.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Issued runtime token is invalid");
        }
    }

    @Override
    public String toString() {
        return "IssuedRuntimeToken[plaintext=redacted, digest=redacted]";
    }
}
