package io.gen2spring.mcp.adapter.cryptography;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.managed.runtime.IssuedRuntimeToken;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HmacRuntimeTokenCodecTest {
    @TempDir
    Path tempDir;

    private int keySequence;

    @Test
    void issuesA256BitTokenAndMatchesAnIndependentHmacVector() throws Exception {
        Path key = validKey();
        HmacRuntimeTokenCodec codec = new HmacRuntimeTokenCodec(key, new ZeroRandom());

        IssuedRuntimeToken issued = codec.issue();

        assertEquals("g2s_rt_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", issued.plaintext());
        assertEquals(
                "e3125551106f33379c7528de818ab798839c1c04b652ab13a3d843f0bcb3e0f3",
                HexFormat.of().formatHex(issued.digest().value()));
        assertTrue(codec.matches(issued.plaintext(), issued.digest()));
        assertFalse(codec.matches(issued.plaintext() + "x", issued.digest()));
        assertFalse(codec.matches(null, issued.digest()));
        assertFalse(codec.matches(issued.plaintext(), null));
        assertFalse(issued.toString().contains(issued.plaintext()));
    }

    @Test
    void tokenDigestsDefensivelyCopyMutableBytes() throws Exception {
        HmacRuntimeTokenCodec codec = new HmacRuntimeTokenCodec(validKey(), new ZeroRandom());
        IssuedRuntimeToken issued = codec.issue();
        byte[] first = issued.digest().value();
        byte[] expected = first.clone();

        first[0] ^= 0x7f;

        assertArrayEquals(expected, issued.digest().value());
    }

    @Test
    void rejectsUnsafePepperFilesWithOneNonLeakingFailure() throws Exception {
        Path shortKey = Files.write(tempDir.resolve("private-short-pepper"), new byte[31]);
        assertInvalid(shortKey);

        Path target = validKey();
        Path symlink = tempDir.resolve("private-pepper-link");
        Files.createSymbolicLink(symlink, target.getFileName());
        assertInvalid(symlink);

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
            assertInvalid(target);
        }
    }

    private Path validKey() throws Exception {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        Path path = Files.write(tempDir.resolve("runtime-token-pepper-" + keySequence++), key);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
        return path;
    }

    private void assertInvalid(Path path) {
        HmacRuntimeTokenCodec.RuntimeTokenProtectionFailure failure = assertThrows(
                HmacRuntimeTokenCodec.RuntimeTokenProtectionFailure.class,
                () -> new HmacRuntimeTokenCodec(path));
        assertEquals("Runtime token protection failed", failure.getMessage());
        assertFalse(failure.toString().contains("private"));
        assertFalse(failure.toString().contains(tempDir.toString()));
    }

    private static final class ZeroRandom extends SecureRandom {
        @Override
        public void nextBytes(byte[] bytes) {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }
}
