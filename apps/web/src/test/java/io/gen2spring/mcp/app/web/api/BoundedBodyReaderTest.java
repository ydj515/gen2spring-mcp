package io.gen2spring.mcp.app.web.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class BoundedBodyReaderTest {
    @Test
    void readsTheExactLimitAndRejectsOneAdditionalByte() {
        BoundedBodyReader reader = new BoundedBodyReader(4);

        assertArrayEquals(new byte[] {1, 2, 3, 4},
                reader.read(new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));
        assertThrows(WebErrorMapper.WebException.class,
                () -> reader.read(new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5})));
    }
}
