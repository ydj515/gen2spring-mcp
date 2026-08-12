package io.gen2spring.mcp.application.hosted.worker;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class SandboxArtifact implements AutoCloseable {
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern CONTENT_TYPE = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*");

    private final String name;
    private final long size;
    private final String sha256;
    private final String contentType;
    private final InputStream content;
    private final AtomicBoolean opened = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private SandboxArtifact(
            String name,
            InputStream content,
            long size,
            String sha256,
            String contentType) {
        if (name == null
                || !NAME.matcher(name).matches()
                || content == null
                || size < 1
                || size > MAX_BYTES
                || sha256 == null
                || !SHA256.matcher(sha256).matches()
                || contentType == null
                || contentType.length() > 128
                || !CONTENT_TYPE.matcher(contentType).matches()) {
            throw new IllegalArgumentException("Sandbox artifact is invalid");
        }
        this.name = name;
        this.content = content;
        this.size = size;
        this.sha256 = sha256;
        this.contentType = contentType;
    }

    public static SandboxArtifact of(
            String name,
            InputStream content,
            long size,
            String sha256,
            String contentType) {
        return new SandboxArtifact(name, content, size, sha256, contentType);
    }

    public String name() {
        return name;
    }

    public long size() {
        return size;
    }

    public String sha256() {
        return sha256;
    }

    public String contentType() {
        return contentType;
    }

    public InputStream body() {
        if (closed.get() || !opened.compareAndSet(false, true)) {
            throw new IllegalStateException("Sandbox artifact body is unavailable");
        }
        return content;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                content.close();
            } catch (IOException ignored) {
                // The worker's terminal state remains authoritative.
            }
        }
    }
}
