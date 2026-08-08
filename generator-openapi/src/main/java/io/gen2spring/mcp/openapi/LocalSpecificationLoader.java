package io.gen2spring.mcp.openapi;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_FILE_UNSUPPORTED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_TOO_LARGE;

import io.gen2spring.mcp.domain.error.GeneratorException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class LocalSpecificationLoader {
    private static final String SOURCE_LOAD = "SOURCE_LOAD";

    public LoadedSpecification load(Path path, long maxBytes) {
        if (maxBytes < 0) {
            throw GeneratorException.user(SPEC_TOO_LARGE, SOURCE_LOAD, "Maximum specification size must not be negative");
        }

        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw GeneratorException.user(SPEC_FILE_UNSUPPORTED, SOURCE_LOAD, "Specification must be a regular file");
        }

        String extension = extension(normalized);
        try {
            long size = Files.size(normalized);
            if (size > maxBytes) {
                throw GeneratorException.user(SPEC_TOO_LARGE, SOURCE_LOAD,
                        "Specification exceeds " + maxBytes + " bytes");
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(normalized)) {
                bytes = readBounded(input, maxBytes);
            }
            return new LoadedSpecification(bytes, extension, sha256(bytes));
        } catch (IOException exception) {
            throw GeneratorException.user(SPEC_FILE_UNSUPPORTED, SOURCE_LOAD,
                    "Specification could not be read", exception);
        }
    }

    private String extension(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals("json") && !extension.equals("yaml") && !extension.equals("yml")) {
            throw GeneratorException.user(SPEC_FILE_UNSUPPORTED, SOURCE_LOAD,
                    "Specification extension must be json, yaml, or yml");
        }
        return extension;
    }

    byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        long bytesRead = 0;
        while (true) {
            long remaining = maxBytes - bytesRead;
            int maximumRead = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
            int read = input.read(buffer, 0, maximumRead);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read > remaining) {
                throw GeneratorException.user(SPEC_TOO_LARGE, SOURCE_LOAD,
                        "Specification exceeds " + maxBytes + " bytes");
            }
            output.write(buffer, 0, read);
            bytesRead += read;
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record LoadedSpecification(byte[] bytes, String extension, String checksum) {}
}
