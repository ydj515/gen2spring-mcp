package io.gen2spring.mcp.application.managed.policy;

import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import java.util.Objects;

public record IssuedRuntimeGrant(ManagedRuntimeGrant grant, String plaintextToken) {
    public IssuedRuntimeGrant {
        Objects.requireNonNull(grant, "grant");
        if (plaintextToken == null || plaintextToken.length() < 8 || plaintextToken.length() > 512
                || !plaintextToken.startsWith("g2s_rt_")
                || plaintextToken.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Issued runtime grant is invalid");
        }
    }

    @Override
    public String toString() {
        return "IssuedRuntimeGrant[grant=" + grant + ", plaintextToken=redacted]";
    }
}
