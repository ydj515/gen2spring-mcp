package io.gen2spring.mcp.app.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

final class BoundedBodyReader {
    private final int maxBytes;

    BoundedBodyReader(int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("Request body limit is invalid");
        }
        this.maxBytes = maxBytes;
    }

    byte[] read(InputStream input) {
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
                    throw new PayloadTooLargeException();
                }
                output.write(buffer, 0, read);
                total += read;
            }
        } catch (PayloadTooLargeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BodyReadException(exception);
        }
    }

    static final class PayloadTooLargeException extends RuntimeException {
        PayloadTooLargeException() {
            super("Request body exceeds the configured limit");
        }
    }

    static final class BodyReadException extends RuntimeException {
        BodyReadException(IOException cause) {
            super("Request body could not be read", cause);
        }
    }
}
