package io.gen2spring.mcp.adapter.cryptography;

import io.gen2spring.mcp.application.managed.credential.CredentialProtectionFailure;
import io.gen2spring.mcp.application.managed.credential.CredentialProtector;
import io.gen2spring.mcp.application.managed.credential.CredentialSecret;
import io.gen2spring.mcp.application.managed.credential.ProtectedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmCredentialProtector implements CredentialProtector {
    private static final int ENVELOPE_VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Set<PosixFilePermission> FORBIDDEN_PERMISSIONS = EnumSet.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);

    private final Map<String, SecretKey> operatorKeys;
    private final String activeKeyId;
    private final SecureRandom random;

    public AesGcmCredentialProtector(Map<String, Path> keyFiles, String activeKeyId) {
        this(keyFiles, activeKeyId, new SecureRandom());
    }

    AesGcmCredentialProtector(Map<String, Path> keyFiles, String activeKeyId, SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
        try {
            if (keyFiles == null || keyFiles.isEmpty()
                    || activeKeyId == null || !KEY_ID.matcher(activeKeyId).matches()
                    || !keyFiles.containsKey(activeKeyId)) throw failed();
            Map<String, SecretKey> loaded = new HashMap<>();
            for (Map.Entry<String, Path> entry : keyFiles.entrySet()) {
                if (entry.getKey() == null || !KEY_ID.matcher(entry.getKey()).matches()) throw failed();
                loaded.put(entry.getKey(), loadKey(entry.getValue()));
            }
            this.operatorKeys = Map.copyOf(loaded);
            this.activeKeyId = activeKeyId;
        } catch (CredentialProtectionFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failed();
        }
    }

    @Override
    public ProtectedCredential protect(
            AccountId owner,
            ManagedCredentialId id,
            long version,
            CredentialSecret secret) {
        byte[] dataKey = new byte[KEY_BYTES];
        byte[] plaintext = null;
        try {
            requireIdentity(owner, id, version);
            Objects.requireNonNull(secret, "secret");
            random.nextBytes(dataKey);
            byte[] wrappedNonce = nonce();
            byte[] payloadNonce = nonce();
            plaintext = secret.encoded();
            byte[] wrappedKey = encrypt(
                    operatorKeys.get(activeKeyId), wrappedNonce,
                    aad(owner, id, version, activeKeyId, "wrapped-key"), dataKey);
            byte[] ciphertext = encrypt(
                    new SecretKeySpec(dataKey, "AES"), payloadNonce,
                    aad(owner, id, version, activeKeyId, "payload"), plaintext);
            return new ProtectedCredential(
                    ENVELOPE_VERSION, version, activeKeyId,
                    wrappedNonce, wrappedKey, payloadNonce, ciphertext);
        } catch (Error fatal) {
            throw fatal;
        } catch (CredentialProtectionFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failed();
        } finally {
            Arrays.fill(dataKey, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    public CredentialSecret reveal(
            AccountId owner,
            ManagedCredentialId id,
            long version,
            ProtectedCredential protectedCredential) {
        byte[] dataKey = null;
        byte[] plaintext = null;
        try {
            requireIdentity(owner, id, version);
            if (protectedCredential == null
                    || protectedCredential.envelopeVersion() != ENVELOPE_VERSION
                    || protectedCredential.credentialVersion() != version) throw failed();
            SecretKey operatorKey = operatorKeys.get(protectedCredential.keyId());
            if (operatorKey == null) throw failed();
            dataKey = decrypt(
                    operatorKey,
                    protectedCredential.wrappedKeyNonce(),
                    aad(owner, id, version, protectedCredential.keyId(), "wrapped-key"),
                    protectedCredential.wrappedKey());
            if (dataKey.length != KEY_BYTES) throw failed();
            plaintext = decrypt(
                    new SecretKeySpec(dataKey, "AES"),
                    protectedCredential.payloadNonce(),
                    aad(owner, id, version, protectedCredential.keyId(), "payload"),
                    protectedCredential.ciphertext());
            return CredentialSecret.decode(plaintext);
        } catch (Error fatal) {
            throw fatal;
        } catch (CredentialProtectionFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failed();
        } finally {
            if (dataKey != null) Arrays.fill(dataKey, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private SecretKey loadKey(Path path) {
        byte[] bytes = null;
        try {
            if (path == null || !path.isAbsolute() || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) != KEY_BYTES) throw failed();
            requireOwnerOnly(path);
            bytes = Files.readAllBytes(path);
            if (bytes.length != KEY_BYTES) throw failed();
            return new SecretKeySpec(bytes, "AES");
        } catch (CredentialProtectionFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failed();
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private void requireOwnerOnly(Path path) throws Exception {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(FORBIDDEN_PERMISSIONS::contains)) throw failed();
        } catch (UnsupportedOperationException ignored) {
            // Windows has no POSIX permission view; deployment provisioning owns ACL hardening.
        }
    }

    private void requireIdentity(AccountId owner, ManagedCredentialId id, long version) {
        if (owner == null || id == null || version < 1) throw failed();
    }

    private byte[] nonce() {
        byte[] value = new byte[NONCE_BYTES];
        random.nextBytes(value);
        return value;
    }

    private byte[] aad(
            AccountId owner,
            ManagedCredentialId id,
            long version,
            String keyId,
            String purpose) {
        return ("gen2spring-managed-credential:" + ENVELOPE_VERSION
                + ":" + owner.value() + ":" + id.value() + ":" + version
                + ":" + keyId + ":" + purpose).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] encrypt(SecretKey key, byte[] nonce, byte[] aad, byte[] plaintext) {
        return crypt(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext);
    }

    private byte[] decrypt(SecretKey key, byte[] nonce, byte[] aad, byte[] ciphertext) {
        return crypt(Cipher.DECRYPT_MODE, key, nonce, aad, ciphertext);
    }

    private byte[] crypt(int mode, SecretKey key, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException failure) {
            throw failed();
        }
    }

    private static CredentialProtectionFailure failed() {
        return new CredentialProtectionFailure();
    }
}
