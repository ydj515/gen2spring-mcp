package io.gen2spring.mcp.adapter.cryptography;

import io.gen2spring.mcp.application.managed.runtime.IssuedRuntimeToken;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.application.managed.runtime.port.out.RuntimeTokenCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class HmacRuntimeTokenCodec implements RuntimeTokenCodec {
    private static final int KEY_BYTES = 32;
    private static final int TOKEN_BYTES = 32;
    private static final byte[] DOMAIN = "gen2spring-runtime-token:v1:".getBytes(StandardCharsets.UTF_8);
    private static final Set<PosixFilePermission> FORBIDDEN_PERMISSIONS = EnumSet.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);

    private final SecretKey key;
    private final SecureRandom random;

    public HmacRuntimeTokenCodec(Path pepperFile) {
        this(pepperFile, new SecureRandom());
    }

    HmacRuntimeTokenCodec(Path pepperFile, SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
        this.key = loadKey(pepperFile);
    }

    @Override
    public IssuedRuntimeToken issue() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        random.nextBytes(randomBytes);
        try {
            String plaintext = "g2s_rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            return new IssuedRuntimeToken(plaintext, digest(plaintext));
        } catch (RuntimeException failure) {
            if (failure instanceof RuntimeTokenProtectionFailure protectionFailure) {
                throw protectionFailure;
            }
            throw failed();
        } finally {
            Arrays.fill(randomBytes, (byte) 0);
        }
    }

    @Override
    public boolean matches(String presentedToken, RuntimeTokenDigest persistedDigest) {
        if (presentedToken == null || persistedDigest == null) {
            return false;
        }
        byte[] computed = null;
        byte[] persisted = persistedDigest.value();
        try {
            computed = hmac(presentedToken);
            return MessageDigest.isEqual(computed, persisted);
        } catch (RuntimeException failure) {
            if (failure instanceof RuntimeTokenProtectionFailure protectionFailure) {
                throw protectionFailure;
            }
            throw failed();
        } finally {
            if (computed != null) {
                Arrays.fill(computed, (byte) 0);
            }
            Arrays.fill(persisted, (byte) 0);
        }
    }

    @Override
    public RuntimeTokenDigest digest(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || plaintext.length() > 512
                || plaintext.chars().anyMatch(Character::isISOControl)) {
            throw failed();
        }
        byte[] value = hmac(plaintext);
        try {
            return new RuntimeTokenDigest(value);
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    private byte[] hmac(String plaintext) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(DOMAIN);
            return mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw failed();
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
                throw failed();
            }
            requireOwnerOnly(path);
            bytes = Files.readAllBytes(path);
            if (bytes.length != KEY_BYTES) {
                throw failed();
            }
            return new SecretKeySpec(bytes, "HmacSHA256");
        } catch (RuntimeTokenProtectionFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failed();
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
                throw failed();
            }
        } catch (UnsupportedOperationException ignored) {
            // Windows uses deployment ACLs instead of POSIX mode bits.
        }
    }

    private RuntimeTokenProtectionFailure failed() {
        return new RuntimeTokenProtectionFailure();
    }

    public static final class RuntimeTokenProtectionFailure extends RuntimeException {
        private RuntimeTokenProtectionFailure() {
            super("Runtime token protection failed", null, false, false);
        }
    }
}
