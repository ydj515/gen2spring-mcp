package io.gen2spring.mcp.adapter.cryptography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.application.hosted.imports.ImportTargetProtectionFailure;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AesGcmImportTargetProtectorTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void protectsTheSameTargetWithNondeterministicVersionedEnvelopes() throws Exception {
        Path keyFile = keyFile("key-a.bin", (byte) 0x11);
        AesGcmImportTargetProtector protector =
                new AesGcmImportTargetProtector(Map.of("key-a", keyFile), "key-a");
        ImportTarget target = ImportTarget.parse(
                "https://api.example.com/private/openapi.json?tenant=private-marker");

        EncryptedImportTarget first = protector.protect(target);
        EncryptedImportTarget second = protector.protect(target);

        assertEquals(1, first.version());
        assertEquals("key-a", first.keyId());
        assertNotEquals(first, second);
        assertEquals(target, protector.reveal(first));
        assertEquals(target, protector.reveal(second));
        assertFalse(first.toString().contains("api.example.com"));
        assertFalse(first.toString().contains("private-marker"));
    }

    @Test
    void rejectsModifiedEnvelopeFieldsAndWrongOrRetiredKeysWithOneFixedFailure() throws Exception {
        Path firstKey = keyFile("key-a.bin", (byte) 0x11);
        Path secondKey = keyFile("key-b.bin", (byte) 0x22);
        AesGcmImportTargetProtector protector =
                new AesGcmImportTargetProtector(Map.of("key-a", firstKey), "key-a");
        EncryptedImportTarget encrypted = protector.protect(
                ImportTarget.parse("https://api.example.com/openapi.json?private=marker"));

        assertProtectedFailure(() -> protector.reveal(new EncryptedImportTarget(
                encrypted.version(), encrypted.keyId(), mutate(encrypted.wrappedKeyNonce()),
                encrypted.wrappedKey(), encrypted.targetNonce(), encrypted.ciphertext())));
        assertProtectedFailure(() -> protector.reveal(new EncryptedImportTarget(
                encrypted.version(), encrypted.keyId(), encrypted.wrappedKeyNonce(),
                mutate(encrypted.wrappedKey()), encrypted.targetNonce(), encrypted.ciphertext())));
        assertProtectedFailure(() -> protector.reveal(new EncryptedImportTarget(
                encrypted.version(), encrypted.keyId(), encrypted.wrappedKeyNonce(),
                encrypted.wrappedKey(), mutate(encrypted.targetNonce()), encrypted.ciphertext())));
        assertProtectedFailure(() -> protector.reveal(new EncryptedImportTarget(
                encrypted.version(), encrypted.keyId(), encrypted.wrappedKeyNonce(),
                encrypted.wrappedKey(), encrypted.targetNonce(), mutate(encrypted.ciphertext()))));

        AesGcmImportTargetProtector wrongKey =
                new AesGcmImportTargetProtector(Map.of("key-a", secondKey), "key-a");
        assertProtectedFailure(() -> wrongKey.reveal(encrypted));

        AesGcmImportTargetProtector retired =
                new AesGcmImportTargetProtector(Map.of("key-b", secondKey), "key-b");
        assertProtectedFailure(() -> retired.reveal(encrypted));
    }

    @Test
    void rejectsMissingSymlinkedOrOverexposedKeyFilesWithoutLeakingPaths() throws Exception {
        Path missing = temporaryDirectory.resolve("private-marker-missing-key.bin");
        assertProtectedFailure(() -> new AesGcmImportTargetProtector(Map.of("key-a", missing), "key-a"));

        Path target = keyFile("target.bin", (byte) 0x11);
        Path symlink = temporaryDirectory.resolve("private-marker-link.bin");
        try {
            Files.createSymbolicLink(symlink, target);
            assertProtectedFailure(() -> new AesGcmImportTargetProtector(Map.of("key-a", symlink), "key-a"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows CI may not grant symlink creation to the test process.
        }

        Path exposed = temporaryDirectory.resolve("private-marker-exposed.bin");
        Files.write(exposed, keyBytes((byte) 0x33));
        if (supportsPosix(exposed)) {
            Files.setPosixFilePermissions(exposed, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ));
            assertProtectedFailure(() -> new AesGcmImportTargetProtector(Map.of("key-a", exposed), "key-a"));
        }
    }

    private Path keyFile(String name, byte value) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        Files.write(path, keyBytes(value));
        if (supportsPosix(path)) {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
        return path;
    }

    private boolean supportsPosix(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView("posix");
    }

    private byte[] keyBytes(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private String mutate(String value) {
        char replacement = value.charAt(0) == 'A' ? 'B' : 'A';
        return replacement + value.substring(1);
    }

    private void assertProtectedFailure(Runnable action) {
        ImportTargetProtectionFailure failure = assertThrows(
                ImportTargetProtectionFailure.class,
                action::run);
        assertEquals("Import target protection failed", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private-marker"));
    }
}
