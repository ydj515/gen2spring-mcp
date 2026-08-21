package io.gen2spring.mcp.domain.platform.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagedCredentialTest {
    private static final Instant CREATED = Instant.parse("2026-08-21T00:00:00Z");
    private static final ManagedCredentialId ID = new ManagedCredentialId(
            UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("20000000-0000-0000-0000-000000000002"));

    @Test
    void parsesCredentialIdentifiersWithoutEchoingRejectedInput() {
        assertEquals(ID, ManagedCredentialId.parse(ID.value().toString()));

        for (String invalid : new String[] {null, "", "private-credential-id"}) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> ManagedCredentialId.parse(invalid));
            assertEquals("Platform identifier is invalid", failure.getMessage());
            assertFalse(failure.getMessage().contains("private-credential-id"));
        }
    }

    @Test
    void derivesActiveAndRevokedStateWithoutReopening() {
        ManagedCredential active = credential(Optional.empty());

        assertEquals(ManagedCredential.CredentialState.ACTIVE, active.state());
        ManagedCredential revoked = active.revokeAt(CREATED.plusSeconds(30));
        assertEquals(ManagedCredential.CredentialState.REVOKED, revoked.state());
        assertSame(revoked, revoked.revokeAt(CREATED.plusSeconds(60)));
    }

    @Test
    void rejectsUnsafeLabelsVersionsAndTimestampsWithOneFixedMessage() {
        assertInvalid(() -> new ManagedCredential(
                ID, OWNER, "", ManagedCredentialKind.OPAQUE, 1, CREATED, CREATED, Optional.empty()));
        assertInvalid(() -> new ManagedCredential(
                ID, OWNER, "line\nbreak", ManagedCredentialKind.BEARER, 1,
                CREATED, CREATED, Optional.empty()));
        assertInvalid(() -> new ManagedCredential(
                ID, OWNER, "가".repeat(43), ManagedCredentialKind.BASIC, 1,
                CREATED, CREATED, Optional.empty()));
        assertInvalid(() -> new ManagedCredential(
                ID, OWNER, "provider", ManagedCredentialKind.OPAQUE, 0,
                CREATED, CREATED, Optional.empty()));
        assertInvalid(() -> new ManagedCredential(
                ID, OWNER, "provider", ManagedCredentialKind.OPAQUE, 1,
                CREATED, CREATED.minusSeconds(1), Optional.empty()));
        assertInvalid(() -> new ManagedCredential(
                ID, OWNER, "provider", ManagedCredentialKind.OPAQUE, 1,
                CREATED, CREATED, Optional.of(CREATED.minusSeconds(1))));
    }

    @Test
    void keepsOwnerFacingLabelsOutOfDiagnosticText() {
        ManagedCredential credential = new ManagedCredential(
                ID, OWNER, "private-partner-label", ManagedCredentialKind.OPAQUE,
                7, CREATED, CREATED.plusSeconds(30), Optional.empty());

        assertFalse(credential.toString().contains("private-partner-label"));
    }

    private ManagedCredential credential(Optional<Instant> revokedAt) {
        return new ManagedCredential(
                ID, OWNER, "provider", ManagedCredentialKind.OPAQUE,
                1, CREATED, CREATED, revokedAt);
    }

    private void assertInvalid(Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals("Managed credential is invalid", failure.getMessage());
    }
}
