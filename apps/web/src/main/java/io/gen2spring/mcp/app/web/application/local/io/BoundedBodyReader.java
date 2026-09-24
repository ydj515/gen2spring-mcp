package io.gen2spring.mcp.app.web.application.local.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class BoundedBodyReader {
    private final int maxBytes;

    public BoundedBodyReader(int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("Request body limit is invalid");
        }
        this.maxBytes = maxBytes;
    }

    public byte[] read(InputStream input) {
        Objects.requireNonNull(input, "input");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        try {
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    return output.toByteArray();
                }
                if (read == 0) {
                    continue;
                }
                if (read > maxBytes - total) {
                    throw new BodyTooLargeException();
                }
                output.write(buffer, 0, read);
                total += read;
            }
        } catch (IOException exception) {
            throw new BodyReadException(exception);
        }
    }

    public static final class BodyTooLargeException extends RuntimeException {
        public BodyTooLargeException() {
            super("Request body is too large", null, false, false);
        }
    }

    public static final class BodyReadException extends RuntimeException {
        public BodyReadException(IOException cause) {
            super("Request body could not be read", cause);
        }
    }
}
