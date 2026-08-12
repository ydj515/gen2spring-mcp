package io.gen2spring.mcp.adapter.emitter.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmitterSupportTest {
    @Test
    void ownsTheSingleVerifiedWrapperAssetSet() throws Exception {
        Map<String, String> checksums = Map.of(
                "gradle-wrapper.jar", "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
                "gradle-wrapper.properties", "556f4aa5f360e35fca77b010b306307765d4f4915a82a612035cd5cfb7a587cb",
                "gradlew", "a5a5c199ba02189ae8c46a334223371a20599d9c298ef65e7540ede4a3f72d59",
                "gradlew.bat", "d539676c48b596afda64c963ec8f7ee56c7b3fe7e3b81d1dbe2d1a1e3dd9e9f8");

        for (var entry : checksums.entrySet()) {
            try (var stream = getClass().getResourceAsStream("/wrapper/" + entry.getKey())) {
                assertNotNull(stream, entry.getKey());
                assertEquals(entry.getValue(), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
            }
        }
    }

    @Test
    void quotesJavaStringsWithoutOwningFrameworkDialect() {
        assertEquals("\"line\\n\\\"quoted\\\"\"", JavaStringLiteral.quote("line\n\"quoted\""));
    }
}
