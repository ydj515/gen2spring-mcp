package io.gen2spring.mcp.application.hosted.job;

import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import java.time.Instant;
import java.util.regex.Pattern;

public record JobArtifact(
        String type,
        ObjectKey objectKey,
        String sha256,
        long byteSize,
        String contentType,
        Instant expiresAt) {
    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern CONTENT_TYPE = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*");

    public JobArtifact {
        if (type == null
                || !TYPE.matcher(type).matches()
                || objectKey == null
                || sha256 == null
                || !SHA256.matcher(sha256).matches()
                || byteSize < 1
                || contentType == null
                || contentType.length() > 128
                || !CONTENT_TYPE.matcher(contentType).matches()
                || expiresAt == null) {
            throw new IllegalArgumentException("Hosted job artifact is invalid");
        }
    }
}
