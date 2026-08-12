package io.gen2spring.mcp.application.hosted.storage;

import java.util.regex.Pattern;

public record ObjectKey(String value) {
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern VALID = Pattern.compile(
            "^(specifications|artifacts)/" + UUID + "/[a-z0-9][a-z0-9-]{0,63}$");

    public ObjectKey {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Object key is invalid");
        }
    }

    public static ObjectKey parse(String value) {
        return new ObjectKey(value);
    }
}
