package io.gen2spring.mcp.app.web.presentation.hosted;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

final class HostedCursorCodec {
    private static final String INVALID = "Hosted cursor is invalid";

    Optional<Cursor> decode(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (bytes.length < 39 || bytes.length > 80) throw invalid();
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            if (separator < 1 || separator != decoded.lastIndexOf('|')) throw invalid();
            return Optional.of(new Cursor(
                    Instant.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1))));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    String encode(Cursor cursor) {
        if (cursor == null || cursor.createdAt() == null || cursor.id() == null) throw invalid();
        String value = cursor.createdAt() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }

    record Cursor(Instant createdAt, UUID id) {}
}
