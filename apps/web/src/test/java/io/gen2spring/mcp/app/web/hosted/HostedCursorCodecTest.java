package io.gen2spring.mcp.app.web.hosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HostedCursorCodecTest {
    @Test
    void roundTripsOneOpaqueBoundedCursorAndRejectsMalformedValues() {
        HostedCursorCodec codec = new HostedCursorCodec();
        HostedCursorCodec.Cursor cursor = new HostedCursorCodec.Cursor(
                Instant.parse("2026-08-13T00:00:00Z"),
                UUID.fromString("80782e7c-337d-4d4d-bd4d-ad478359563c"));

        String encoded = codec.encode(cursor);
        assertEquals(
                "MjAyNi0wOC0xM1QwMDowMDowMFp8ODA3ODJlN2MtMzM3ZC00ZDRkLWJkNGQtYWQ0NzgzNTk1NjNj",
                encoded);
        assertEquals(cursor, codec.decode(encoded).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> codec.decode("private-marker"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("a".repeat(200)));
    }
}
