package io.gen2spring.mcp.application.hosted.storage;

import java.util.Objects;
import java.util.regex.Pattern;

public record StoredObject(ObjectKey key, long size, String sha256, String contentType) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern CONTENT_TYPE = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*"
                    + "(?:; ?[a-z0-9-]+=[A-Za-z0-9._-]+)*");

    public StoredObject {
        Objects.requireNonNull(key, "key");
        if (size < 0
                || sha256 == null
                || !SHA_256.matcher(sha256).matches()
                || contentType == null
                || contentType.length() > 128
                || !CONTENT_TYPE.matcher(contentType).matches()) {
            throw new IllegalArgumentException("Stored object metadata is invalid");
        }
    }
}
