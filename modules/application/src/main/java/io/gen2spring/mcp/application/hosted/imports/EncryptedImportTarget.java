package io.gen2spring.mcp.application.hosted.imports;

import java.util.Base64;
import java.util.regex.Pattern;

public record EncryptedImportTarget(
        int version,
        String keyId,
        String wrappedKeyNonce,
        String wrappedKey,
        String targetNonce,
        String ciphertext) {
    private static final String INVALID = "Encrypted import target is invalid";
    private static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");

    public EncryptedImportTarget {
        if (version != 1
                || keyId == null
                || !KEY_ID.matcher(keyId).matches()
                || !encodedLength(wrappedKeyNonce, 12, 12)
                || !encodedLength(wrappedKey, 48, 48)
                || !encodedLength(targetNonce, 12, 12)
                || !encodedLength(ciphertext, 17, 6000)) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    @Override
    public String toString() {
        return "EncryptedImportTarget[redacted]";
    }

    private static boolean encodedLength(String value, int minimum, int maximum) {
        if (value == null || !BASE64_URL.matcher(value).matches()) {
            return false;
        }
        try {
            int size = Base64.getUrlDecoder().decode(value).length;
            return size >= minimum && size <= maximum;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
