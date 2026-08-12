package io.gen2spring.mcp.application.hosted.worker;

import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import java.util.Objects;
import java.util.regex.Pattern;

public record SandboxInput(
        ObjectKey specification,
        String requestSnapshot,
        String targetProfileId) {
    private static final Pattern PROFILE = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");

    public SandboxInput {
        Objects.requireNonNull(specification, "specification");
        if (requestSnapshot == null
                || requestSnapshot.length() < 2
                || requestSnapshot.length() > 1_048_576
                || targetProfileId == null
                || !PROFILE.matcher(targetProfileId).matches()) {
            throw new IllegalArgumentException("Sandbox input is invalid");
        }
    }

    @Override
    public String toString() {
        return "SandboxInput[redacted]";
    }
}
