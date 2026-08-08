package io.gen2spring.mcp.openapi;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_FILE_UNSUPPORTED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_TOO_LARGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.error.GeneratorException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSpecificationLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsDirectoriesAsSpecifications() {
        var loader = new LocalSpecificationLoader();

        var exception = assertThrows(GeneratorException.class,
                () -> loader.load(temporaryDirectory, 1024));

        assertEquals(SPEC_FILE_UNSUPPORTED, exception.code());
    }

    @Test
    void rejectsAnOversizedFileBeforeReadingIt() throws Exception {
        Path specification = temporaryDirectory.resolve("large.yaml");
        Files.writeString(specification, "openapi: 3.0.3\ninfo: {}\npaths: {}\n");
        var loader = new LocalSpecificationLoader();

        var exception = assertThrows(GeneratorException.class,
                () -> loader.load(specification, 1));

        assertEquals(SPEC_TOO_LARGE, exception.code());
    }

    @Test
    void rejectsAStreamThatExceedsTheLimitAfterMetadataWasChecked() {
        var loader = new LocalSpecificationLoader();

        var exception = assertThrows(GeneratorException.class,
                () -> loader.readBounded(new ByteArrayInputStream(new byte[4]), 3));

        assertEquals(SPEC_TOO_LARGE, exception.code());
    }

    @Test
    void retainsExactBytesAndComputesTheirChecksum() throws Exception {
        byte[] expectedBytes = "openapi: 3.0.3\ninfo: {}\npaths: {}\n".getBytes();
        Path specification = temporaryDirectory.resolve("weather.YAML");
        Files.write(specification, expectedBytes);

        var loaded = new LocalSpecificationLoader().load(specification, 1024);

        assertArrayEquals(expectedBytes, loaded.bytes());
        assertEquals("yaml", loaded.extension());
        assertEquals("46951c0e1d6e2ba28488dedf31c9592f95daa3b874beb2a26f7c1c5aa314e9fc", loaded.checksum());
    }
}
