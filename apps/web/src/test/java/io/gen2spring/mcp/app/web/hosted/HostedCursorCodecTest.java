package io.gen2spring.mcp.app.web.hosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.hosted.query.HostedResourceStore.ResourceCursor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HostedCursorCodecTest {
    @Test
    void roundTripsOneOpaqueBoundedCursorAndRejectsMalformedValues() {
        HostedCursorCodec codec = new HostedCursorCodec();
        ResourceCursor cursor = new ResourceCursor(
                Instant.parse("2026-08-13T00:00:00Z"),
                UUID.fromString("80782e7c-337d-4d4d-bd4d-ad478359563c"));

        assertEquals(cursor, codec.decode(codec.encode(cursor)).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> codec.decode("private-marker"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("a".repeat(200)));
    }
}
