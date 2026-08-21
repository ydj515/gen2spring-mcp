package io.gen2spring.mcp.application.managed.credential;

import java.util.Arrays;
import java.util.regex.Pattern;

public record ProtectedCredential(
        int envelopeVersion,
        long credentialVersion,
        String keyId,
        byte[] wrappedKeyNonce,
        byte[] wrappedKey,
        byte[] payloadNonce,
        byte[] ciphertext) {
    private static final String INVALID = "Protected credential is invalid";
    private static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public ProtectedCredential {
        if (envelopeVersion != 1 || credentialVersion < 1
                || keyId == null || !KEY_ID.matcher(keyId).matches()
                || !length(wrappedKeyNonce, 12, 12)
                || !length(wrappedKey, 48, 48)
                || !length(payloadNonce, 12, 12)
                || !length(ciphertext, 17, 12_500)) {
            throw new IllegalArgumentException(INVALID);
        }
        wrappedKeyNonce = wrappedKeyNonce.clone();
        wrappedKey = wrappedKey.clone();
        payloadNonce = payloadNonce.clone();
        ciphertext = ciphertext.clone();
    }

    @Override public byte[] wrappedKeyNonce() { return wrappedKeyNonce.clone(); }
    @Override public byte[] wrappedKey() { return wrappedKey.clone(); }
    @Override public byte[] payloadNonce() { return payloadNonce.clone(); }
    @Override public byte[] ciphertext() { return ciphertext.clone(); }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProtectedCredential value
                && envelopeVersion == value.envelopeVersion
                && credentialVersion == value.credentialVersion
                && keyId.equals(value.keyId)
                && Arrays.equals(wrappedKeyNonce, value.wrappedKeyNonce)
                && Arrays.equals(wrappedKey, value.wrappedKey)
                && Arrays.equals(payloadNonce, value.payloadNonce)
                && Arrays.equals(ciphertext, value.ciphertext);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(envelopeVersion, credentialVersion, keyId);
        result = 31 * result + Arrays.hashCode(wrappedKeyNonce);
        result = 31 * result + Arrays.hashCode(wrappedKey);
        result = 31 * result + Arrays.hashCode(payloadNonce);
        result = 31 * result + Arrays.hashCode(ciphertext);
        return result;
    }

    @Override
    public String toString() {
        return "ProtectedCredential[envelopeVersion=" + envelopeVersion
                + ", credentialVersion=" + credentialVersion + ", value=redacted]";
    }

    private static boolean length(byte[] value, int minimum, int maximum) {
        return value != null && value.length >= minimum && value.length <= maximum;
    }
}
