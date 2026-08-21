package io.gen2spring.mcp.domain.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagedRuntimeInstanceTest {
    private static final Instant CREATED = Instant.parse("2026-08-21T00:00:00Z");
    private static final String CHECKSUM = "a".repeat(64);

    @Test
    void parsesRuntimeIdentifiersWithFixedSafeFailures() {
        UUID value = UUID.fromString("10000000-0000-0000-0000-000000000001");

        assertEquals(value, RuntimeInstanceId.parse(value.toString()).value());
        for (String invalid : new String[] {null, "", "secret-runtime-id"}) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> RuntimeInstanceId.parse(invalid));
            assertEquals("Platform identifier is invalid", failure.getMessage());
            assertFalse(failure.getMessage().contains("secret-runtime-id"));
        }
    }

    @Test
    void derivesActiveExpiredAndRevokedStateWithoutReopening() {
        ManagedRuntimeInstance active = instance(CREATED.plusSeconds(3600), Optional.empty());

        assertEquals(ManagedRuntimeInstance.RuntimeState.ACTIVE, active.stateAt(CREATED.plusSeconds(1)));
        assertEquals(ManagedRuntimeInstance.RuntimeState.EXPIRED, active.stateAt(CREATED.plusSeconds(3600)));

        ManagedRuntimeInstance revoked = active.revokeAt(CREATED.plusSeconds(10));
        assertEquals(ManagedRuntimeInstance.RuntimeState.REVOKED, revoked.stateAt(CREATED.plusSeconds(11)));
        assertSame(revoked, revoked.revokeAt(CREATED.plusSeconds(12)));
    }

    @Test
    void rejectsInvalidIdentityLifetimeAndRevocationBoundaries() {
        ProviderTarget target = ProviderTarget.parse("https://api.example.com/v1");
        RuntimeInstanceId id = new RuntimeInstanceId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        AccountId owner = new AccountId(UUID.fromString("20000000-0000-0000-0000-000000000002"));
        UUID catalog = UUID.fromString("30000000-0000-0000-0000-000000000003");

        assertInvalid(() -> new ManagedRuntimeInstance(
                id, owner, catalog, "not-a-checksum", Optional.of(target), CREATED,
                CREATED.plusSeconds(10), Optional.empty()));
        assertInvalid(() -> new ManagedRuntimeInstance(
                id, owner, catalog, CHECKSUM, Optional.of(target), CREATED,
                CREATED, Optional.empty()));
        assertInvalid(() -> new ManagedRuntimeInstance(
                id, owner, catalog, CHECKSUM, Optional.of(target), CREATED,
                CREATED.plusSeconds(30L * 24 * 60 * 60 + 1), Optional.empty()));
        assertInvalid(() -> new ManagedRuntimeInstance(
                id, owner, catalog, CHECKSUM, Optional.of(target), CREATED,
                CREATED.plusSeconds(10), Optional.of(CREATED.minusSeconds(1))));
    }

    @Test
    void redactsProviderTargetsFromDiagnosticText() {
        ManagedRuntimeInstance runtime = instance(
                CREATED.plusSeconds(3600), Optional.of(ProviderTarget.parse("https://private-label.example/v1")));

        assertFalse(runtime.toString().contains("private-label"));
        assertFalse(runtime.toString().contains("https://"));
    }

    private ManagedRuntimeInstance instance(Instant expiresAt, Optional<ProviderTarget> providerTarget) {
        return new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                new AccountId(UUID.fromString("20000000-0000-0000-0000-000000000002")),
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                CHECKSUM,
                providerTarget,
                CREATED,
                expiresAt,
                Optional.empty());
    }

    private void assertInvalid(Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals("Managed runtime instance is invalid", failure.getMessage());
    }
}
