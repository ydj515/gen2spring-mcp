package io.gen2spring.mcp.application.managed.credential;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialKind;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CredentialSecretTest {
    @Test
    void formatsOpaqueBearerAndBasicWireValuesWithoutExposingThemInDiagnostics() {
        try (CredentialSecret opaque = CredentialSecret.opaque("private-api-key");
             CredentialSecret bearer = CredentialSecret.bearer("private-token");
             CredentialSecret basic = CredentialSecret.basic("partner", "private-password")) {
            assertEquals(ManagedCredentialKind.OPAQUE, opaque.kind());
            assertArrayEquals("private-api-key".getBytes(UTF_8), opaque.wireValue());
            assertArrayEquals("Bearer private-token".getBytes(UTF_8), bearer.wireValue());
            assertArrayEquals("Basic cGFydG5lcjpwcml2YXRlLXBhc3N3b3Jk".getBytes(UTF_8), basic.wireValue());
            assertFalse(opaque.toString().contains("private-api-key"));
            assertFalse(bearer.toString().contains("private-token"));
            assertFalse(basic.toString().contains("private-password"));
        }
    }

    @Test
    void roundTripsTheBoundedBinaryEnvelopeAndDefensivelyCopiesValues() {
        byte[] encoded;
        try (CredentialSecret source = CredentialSecret.basic("partner", "private-password")) {
            encoded = source.encoded();
        }

        try (CredentialSecret decoded = CredentialSecret.decode(encoded)) {
            Arrays.fill(encoded, (byte) 0);
            assertArrayEquals("Basic cGFydG5lcjpwcml2YXRlLXBhc3N3b3Jk".getBytes(UTF_8), decoded.wireValue());
        }
    }

    @Test
    void rejectsUnsafeOrOversizedValuesAndUseAfterCloseWithOneFixedMessage() {
        assertInvalid(() -> CredentialSecret.opaque(""));
        assertInvalid(() -> CredentialSecret.opaque("line\nbreak"));
        try (CredentialSecret maximumBearer = CredentialSecret.bearer("a".repeat(8185))) {
            assertEquals(8192, maximumBearer.wireValue().length);
        }
        assertInvalid(() -> CredentialSecret.bearer("a".repeat(8186)));
        assertInvalid(() -> CredentialSecret.basic("user:name", "password"));
        assertInvalid(() -> CredentialSecret.basic("user", "line\nbreak"));

        CredentialSecret secret = CredentialSecret.opaque("private-api-key");
        byte[] first = secret.wireValue();
        first[0] = 0;
        assertArrayEquals("private-api-key".getBytes(UTF_8), secret.wireValue());
        secret.close();
        assertInvalid(secret::wireValue);
        secret.close();
    }

    private void assertInvalid(Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals("Managed credential secret is invalid", failure.getMessage());
    }
}
