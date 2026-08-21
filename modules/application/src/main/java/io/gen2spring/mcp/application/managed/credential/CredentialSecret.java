package io.gen2spring.mcp.application.managed.credential;

import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialKind;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

public final class CredentialSecret implements AutoCloseable {
    private static final String INVALID = "Managed credential secret is invalid";
    private static final int ENCODING_VERSION = 1;
    private final ManagedCredentialKind kind;
    private byte[] first;
    private byte[] second;
    private boolean closed;

    private CredentialSecret(ManagedCredentialKind kind, byte[] first, byte[] second) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.first = first.clone();
        this.second = second.clone();
        validate();
    }

    public static CredentialSecret opaque(String value) {
        return single(ManagedCredentialKind.OPAQUE, value);
    }

    public static CredentialSecret bearer(String value) {
        return single(ManagedCredentialKind.BEARER, value);
    }

    public static CredentialSecret basic(String username, String password) {
        try {
            return new CredentialSecret(
                    ManagedCredentialKind.BASIC, utf8(username), utf8(password));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public static CredentialSecret decode(byte[] encoded) {
        try {
            if (encoded == null || encoded.length < 10) throw invalid();
            ByteBuffer buffer = ByteBuffer.wrap(encoded.clone());
            if (Byte.toUnsignedInt(buffer.get()) != ENCODING_VERSION) throw invalid();
            int ordinal = Byte.toUnsignedInt(buffer.get());
            if (ordinal >= ManagedCredentialKind.values().length) throw invalid();
            int firstLength = buffer.getInt();
            int secondLength = buffer.getInt();
            if (firstLength < 0 || secondLength < 0
                    || firstLength > buffer.remaining()
                    || secondLength != buffer.remaining() - firstLength) throw invalid();
            byte[] first = new byte[firstLength];
            byte[] second = new byte[secondLength];
            buffer.get(first);
            buffer.get(second);
            decodeUtf8(first);
            decodeUtf8(second);
            return new CredentialSecret(ManagedCredentialKind.values()[ordinal], first, second);
        } catch (IllegalArgumentException failure) {
            if (INVALID.equals(failure.getMessage())) throw failure;
            throw invalid();
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public ManagedCredentialKind kind() {
        return kind;
    }

    public byte[] encoded() {
        requireOpen();
        ByteBuffer result = ByteBuffer.allocate(10 + first.length + second.length);
        result.put((byte) ENCODING_VERSION);
        result.put((byte) kind.ordinal());
        result.putInt(first.length);
        result.putInt(second.length);
        result.put(first);
        result.put(second);
        return result.array();
    }

    public byte[] wireValue() {
        requireOpen();
        return switch (kind) {
            case OPAQUE -> first.clone();
            case BEARER -> concatenate("Bearer ".getBytes(StandardCharsets.UTF_8), first);
            case BASIC -> ("Basic " + Base64.getEncoder().encodeToString(concatenate(
                    first, ":".getBytes(StandardCharsets.UTF_8), second)))
                    .getBytes(StandardCharsets.UTF_8);
        };
    }

    @Override
    public void close() {
        if (closed) return;
        Arrays.fill(first, (byte) 0);
        Arrays.fill(second, (byte) 0);
        closed = true;
    }

    @Override
    public String toString() {
        return "CredentialSecret[kind=" + kind + ", value=redacted]";
    }

    private static CredentialSecret single(ManagedCredentialKind kind, String value) {
        try {
            return new CredentialSecret(kind, utf8(value), new byte[0]);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private void validate() {
        String firstValue = decodeUtf8(first);
        String secondValue = decodeUtf8(second);
        if (kind == ManagedCredentialKind.OPAQUE || kind == ManagedCredentialKind.BEARER) {
            if (!secondValue.isEmpty() || !safe(firstValue, 1, 8192)) throw invalid();
            return;
        }
        if (!safe(firstValue, 1, 256) || firstValue.indexOf(':') >= 0
                || !safe(secondValue, 1, 4096)) throw invalid();
    }

    private static boolean safe(String value, int minimumBytes, int maximumBytes) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= minimumBytes && bytes <= maximumBytes
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static byte[] utf8(String value) {
        if (value == null) throw invalid();
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static byte[] concatenate(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
        ByteBuffer result = ByteBuffer.allocate(length);
        Arrays.stream(values).forEach(result::put);
        return result.array();
    }

    private void requireOpen() {
        if (closed) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
}
