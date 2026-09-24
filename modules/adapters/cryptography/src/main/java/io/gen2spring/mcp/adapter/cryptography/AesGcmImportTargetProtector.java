package io.gen2spring.mcp.adapter.cryptography;

import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.application.hosted.imports.ImportTargetProtectionFailure;
import io.gen2spring.mcp.application.hosted.imports.port.out.ImportTargetProtector;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
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

public final class AesGcmImportTargetProtector implements ImportTargetProtector {
    private static final int VERSION = 1;
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

    public AesGcmImportTargetProtector(Map<String, Path> keyFiles, String activeKeyId) {
        this(keyFiles, activeKeyId, new SecureRandom());
    }

    AesGcmImportTargetProtector(Map<String, Path> keyFiles, String activeKeyId, SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
        try {
            if (keyFiles == null
                    || keyFiles.isEmpty()
                    || activeKeyId == null
                    || !KEY_ID.matcher(activeKeyId).matches()
                    || !keyFiles.containsKey(activeKeyId)) {
                throw new IllegalArgumentException();
            }
            Map<String, SecretKey> loaded = new HashMap<>();
            for (Map.Entry<String, Path> entry : keyFiles.entrySet()) {
                if (entry.getKey() == null || !KEY_ID.matcher(entry.getKey()).matches()) {
                    throw new IllegalArgumentException();
                }
                loaded.put(entry.getKey(), loadKey(entry.getValue()));
            }
            this.operatorKeys = Map.copyOf(loaded);
            this.activeKeyId = activeKeyId;
        } catch (ImportTargetProtectionFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failed(failure);
        }
    }

    @Override
    public EncryptedImportTarget protect(ImportTarget target) {
        if (target == null) {
            throw failed(null);
        }
        byte[] dataKey = new byte[KEY_BYTES];
        random.nextBytes(dataKey);
        try {
            byte[] wrappedKeyNonce = nonce();
            byte[] targetNonce = nonce();
            byte[] wrappedKey = encrypt(
                    operatorKeys.get(activeKeyId),
                    wrappedKeyNonce,
                    aad(activeKeyId, "wrapped-key"),
                    dataKey);
            byte[] ciphertext = encrypt(
                    new SecretKeySpec(dataKey, "AES"),
                    targetNonce,
                    aad(activeKeyId, "target"),
                    target.uri().toString().getBytes(StandardCharsets.UTF_8));
            return new EncryptedImportTarget(
                    VERSION,
                    activeKeyId,
                    encode(wrappedKeyNonce),
                    encode(wrappedKey),
                    encode(targetNonce),
                    encode(ciphertext));
        } catch (RuntimeException failure) {
            if (failure instanceof ImportTargetProtectionFailure protectionFailure) {
                throw protectionFailure;
            }
            throw failed(failure);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public ImportTarget reveal(EncryptedImportTarget encrypted) {
        if (encrypted == null || encrypted.version() != VERSION) {
            throw failed(null);
        }
        SecretKey operatorKey = operatorKeys.get(encrypted.keyId());
        if (operatorKey == null) {
            throw failed(null);
        }
        byte[] dataKey = null;
        byte[] plaintext = null;
        try {
            dataKey = decrypt(
                    operatorKey,
                    decode(encrypted.wrappedKeyNonce()),
                    aad(encrypted.keyId(), "wrapped-key"),
                    decode(encrypted.wrappedKey()));
            if (dataKey.length != KEY_BYTES) {
                throw new GeneralSecurityException();
            }
            plaintext = decrypt(
                    new SecretKeySpec(dataKey, "AES"),
                    decode(encrypted.targetNonce()),
                    aad(encrypted.keyId(), "target"),
                    decode(encrypted.ciphertext()));
            return ImportTarget.parse(decodeUtf8(plaintext));
        } catch (Exception failure) {
            throw failed(failure);
        } finally {
            if (dataKey != null) {
                Arrays.fill(dataKey, (byte) 0);
            }
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private SecretKey loadKey(Path path) {
        byte[] bytes = null;
        try {
            if (path == null
                    || !path.isAbsolute()
                    || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) != KEY_BYTES) {
                throw new IllegalArgumentException();
            }
            requireOwnerOnly(path);
            bytes = Files.readAllBytes(path);
            if (bytes.length != KEY_BYTES) {
                throw new IllegalArgumentException();
            }
            return new SecretKeySpec(bytes, "AES");
        } catch (Exception failure) {
            throw failed(failure);
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private void requireOwnerOnly(Path path) throws Exception {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(FORBIDDEN_PERMISSIONS::contains)) {
                throw new IllegalArgumentException();
            }
        } catch (UnsupportedOperationException ignored) {
            // Windows has no POSIX permission view; ACL hardening is performed by deployment provisioning.
        }
    }

    private byte[] nonce() {
        byte[] value = new byte[NONCE_BYTES];
        random.nextBytes(value);
        return value;
    }

    private byte[] aad(String keyId, String purpose) {
        return ("gen2spring-import-target:" + VERSION + ":" + keyId + ":" + purpose)
                .getBytes(StandardCharsets.UTF_8);
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
            throw failed(failure);
        }
    }

    private String decodeUtf8(byte[] value) throws Exception {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value));
        return decoded.toString();
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private ImportTargetProtectionFailure failed(Throwable cause) {
        return new ImportTargetProtectionFailure(cause);
    }
}
