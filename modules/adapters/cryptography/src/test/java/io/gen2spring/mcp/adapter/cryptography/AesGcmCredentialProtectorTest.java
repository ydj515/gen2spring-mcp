package io.gen2spring.mcp.adapter.cryptography;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.managed.credential.CredentialProtectionFailure;
import io.gen2spring.mcp.application.managed.credential.CredentialSecret;
import io.gen2spring.mcp.application.managed.credential.ProtectedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AesGcmCredentialProtectorTest {
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private static final AccountId FOREIGN = new AccountId(
            UUID.fromString("20000000-0000-0000-0000-000000000002"));
    private static final ManagedCredentialId ID = new ManagedCredentialId(
            UUID.fromString("30000000-0000-0000-0000-000000000003"));

    @TempDir
    private Path temporaryDirectory;

    @Test
    void protectsCredentialsWithNondeterministicOwnerBoundEnvelopes() throws Exception {
        AesGcmCredentialProtector protector = new AesGcmCredentialProtector(
                Map.of("key-a", keyFile("key-a.bin", (byte) 0x11)), "key-a");
        ProtectedCredential first;
        ProtectedCredential second;
        try (CredentialSecret source = CredentialSecret.basic("partner", "private-password")) {
            first = protector.protect(OWNER, ID, 1, source);
            second = protector.protect(OWNER, ID, 1, source);
        }

        assertNotEquals(first, second);
        try (CredentialSecret revealed = protector.reveal(OWNER, ID, 1, first)) {
            assertArrayEquals(
                    "Basic cGFydG5lcjpwcml2YXRlLXBhc3N3b3Jk".getBytes(UTF_8),
                    revealed.wireValue());
        }
        assertFalse(first.toString().contains("private-password"));
        assertFalse(first.toString().contains("key-a"));
    }

    @Test
    void rejectsAadAndCiphertextMutationWithOneFixedFailure() throws Exception {
        AesGcmCredentialProtector protector = new AesGcmCredentialProtector(
                Map.of("key-a", keyFile("key-a.bin", (byte) 0x11)), "key-a");
        ProtectedCredential encrypted;
        try (CredentialSecret source = CredentialSecret.bearer("private-token")) {
            encrypted = protector.protect(OWNER, ID, 7, source);
        }

        assertProtectedFailure(() -> protector.reveal(FOREIGN, ID, 7, encrypted));
        assertProtectedFailure(() -> protector.reveal(
                OWNER, new ManagedCredentialId(UUID.randomUUID()), 7, encrypted));
        assertProtectedFailure(() -> protector.reveal(OWNER, ID, 8, encrypted));
        assertProtectedFailure(() -> protector.reveal(OWNER, ID, 7, new ProtectedCredential(
                1, 7, encrypted.keyId(), mutate(encrypted.wrappedKeyNonce()),
                encrypted.wrappedKey(), encrypted.payloadNonce(), encrypted.ciphertext())));
        assertProtectedFailure(() -> protector.reveal(OWNER, ID, 7, new ProtectedCredential(
                1, 7, encrypted.keyId(), encrypted.wrappedKeyNonce(),
                mutate(encrypted.wrappedKey()), encrypted.payloadNonce(), encrypted.ciphertext())));
        assertProtectedFailure(() -> protector.reveal(OWNER, ID, 7, new ProtectedCredential(
                1, 7, encrypted.keyId(), encrypted.wrappedKeyNonce(),
                encrypted.wrappedKey(), mutate(encrypted.payloadNonce()), encrypted.ciphertext())));
        assertProtectedFailure(() -> protector.reveal(OWNER, ID, 7, new ProtectedCredential(
                1, 7, encrypted.keyId(), encrypted.wrappedKeyNonce(),
                encrypted.wrappedKey(), encrypted.payloadNonce(), mutate(encrypted.ciphertext()))));
    }

    @Test
    void readsRetiredKeysButWritesOnlyTheActiveKey() throws Exception {
        Path oldKey = keyFile("old.bin", (byte) 0x11);
        Path activeKey = keyFile("active.bin", (byte) 0x22);
        AesGcmCredentialProtector old = new AesGcmCredentialProtector(Map.of("old", oldKey), "old");
        ProtectedCredential encrypted;
        try (CredentialSecret source = CredentialSecret.opaque("private-key")) {
            encrypted = old.protect(OWNER, ID, 1, source);
        }

        AesGcmCredentialProtector rotated = new AesGcmCredentialProtector(
                Map.of("old", oldKey, "active", activeKey), "active");
        try (CredentialSecret revealed = rotated.reveal(OWNER, ID, 1, encrypted);
             CredentialSecret replacement = CredentialSecret.opaque("replacement")) {
            assertArrayEquals("private-key".getBytes(UTF_8), revealed.wireValue());
            assertEquals("active", rotated.protect(OWNER, ID, 2, replacement).keyId());
        }

        AesGcmCredentialProtector retired = new AesGcmCredentialProtector(
                Map.of("active", activeKey), "active");
        assertProtectedFailure(() -> retired.reveal(OWNER, ID, 1, encrypted));
    }

    @Test
    void rejectsMissingSymlinkedWrongSizedAndOverexposedKeyFilesWithoutPaths() throws Exception {
        Path missing = temporaryDirectory.resolve("private-missing-key.bin");
        assertProtectedFailure(() -> new AesGcmCredentialProtector(Map.of("key-a", missing), "key-a"));

        Path wrongSize = temporaryDirectory.resolve("private-wrong-size.bin");
        Files.write(wrongSize, new byte[31]);
        ownerOnly(wrongSize);
        assertProtectedFailure(() -> new AesGcmCredentialProtector(Map.of("key-a", wrongSize), "key-a"));

        Path target = keyFile("target.bin", (byte) 0x11);
        Path symlink = temporaryDirectory.resolve("private-link.bin");
        try {
            Files.createSymbolicLink(symlink, target);
            assertProtectedFailure(() -> new AesGcmCredentialProtector(Map.of("key-a", symlink), "key-a"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows CI may not grant symlink creation to the test process.
        }

        Path exposed = temporaryDirectory.resolve("private-exposed.bin");
        Files.write(exposed, keyBytes((byte) 0x33));
        if (supportsPosix(exposed)) {
            Files.setPosixFilePermissions(exposed, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ));
            assertProtectedFailure(() -> new AesGcmCredentialProtector(Map.of("key-a", exposed), "key-a"));
        }
    }

    private Path keyFile(String name, byte value) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        Files.write(path, keyBytes(value));
        ownerOnly(path);
        return path;
    }

    private void ownerOnly(Path path) throws Exception {
        if (supportsPosix(path)) {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
    }

    private boolean supportsPosix(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView("posix");
    }

    private byte[] keyBytes(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private byte[] mutate(byte[] value) {
        byte[] changed = value.clone();
        changed[0] ^= 1;
        return changed;
    }

    private void assertProtectedFailure(Runnable action) {
        CredentialProtectionFailure failure = assertThrows(CredentialProtectionFailure.class, action::run);
        assertEquals("Managed credential protection failed", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("private"));
    }
}
