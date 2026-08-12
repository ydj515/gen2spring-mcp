package io.gen2spring.mcp.application.hosted.job;

import java.util.regex.Pattern;

public record WorkerId(String value) {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public WorkerId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Worker identifier is invalid");
        }
    }
}
