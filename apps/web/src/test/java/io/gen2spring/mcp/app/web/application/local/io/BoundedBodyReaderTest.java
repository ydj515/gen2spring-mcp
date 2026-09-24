package io.gen2spring.mcp.app.web.application.local.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class BoundedBodyReaderTest {
    @Test
    void readsTheExactLimitAndRejectsOneAdditionalByte() {
        BoundedBodyReader reader = new BoundedBodyReader(4);

        assertArrayEquals(new byte[] {1, 2, 3, 4},
                reader.read(new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));
        assertThrows(BoundedBodyReader.BodyTooLargeException.class,
                () -> reader.read(new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5})));
    }
}
