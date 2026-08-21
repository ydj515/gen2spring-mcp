package io.gen2spring.mcp.domain.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagedRuntimeGrantTest {
    private static final Instant CREATED = Instant.parse("2026-08-21T00:00:00Z");
    private static final RuntimeGrantId ID = new RuntimeGrantId(
            UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private static final RuntimeInstanceId RUNTIME = new RuntimeInstanceId(
            UUID.fromString("20000000-0000-0000-0000-000000000002"));
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("30000000-0000-0000-0000-000000000003"));

    @Test
    void parsesGrantIdentifiersWithFixedFailures() {
        assertEquals(ID, RuntimeGrantId.parse(ID.value().toString()));
        assertEquals("Platform identifier is invalid", assertThrows(
                IllegalArgumentException.class, () -> RuntimeGrantId.parse("secret-grant")).getMessage());
    }

    @Test
    void copiesAndSortsTheExactAllowedToolSet() {
        LinkedHashSet<String> source = new LinkedHashSet<>(Set.of("weather_get", "alerts_list"));

        ManagedRuntimeGrant grant = grant(source, 60, CREATED.plusSeconds(60), Optional.empty());
        source.clear();

        assertEquals(Set.of("alerts_list", "weather_get"), grant.allowedTools());
        assertThrows(UnsupportedOperationException.class, () -> grant.allowedTools().add("other"));
    }

    @Test
    void derivesActiveExpiredAndRevokedStateWithoutReopening() {
        ManagedRuntimeGrant active = grant(Set.of("weather_get"), 60, CREATED.plusSeconds(60), Optional.empty());

        assertEquals(ManagedRuntimeGrant.GrantState.ACTIVE, active.stateAt(CREATED.plusSeconds(1)));
        assertEquals(ManagedRuntimeGrant.GrantState.EXPIRED, active.stateAt(CREATED.plusSeconds(60)));
        ManagedRuntimeGrant revoked = active.revokeAt(CREATED.plusSeconds(30));
        assertEquals(ManagedRuntimeGrant.GrantState.REVOKED, revoked.stateAt(CREATED.plusSeconds(31)));
        assertSame(revoked, revoked.revokeAt(CREATED.plusSeconds(40)));
    }

    @Test
    void rejectsUnsafePrincipalToolsRateAndLifetimeWithOneFixedMessage() {
        assertInvalid(() -> grant(Set.of("weather_get"), 0, CREATED.plusSeconds(60), Optional.empty()));
        assertInvalid(() -> grant(Set.of("weather_get"), 6001, CREATED.plusSeconds(60), Optional.empty()));
        assertInvalid(() -> grant(Set.of(), 60, CREATED.plusSeconds(60), Optional.empty()));
        assertInvalid(() -> grant(Set.of("Weather Get"), 60, CREATED.plusSeconds(60), Optional.empty()));
        assertInvalid(() -> new ManagedRuntimeGrant(
                ID, RUNTIME, OWNER, "line\nbreak", Set.of("weather_get"), 60,
                CREATED, CREATED.plusSeconds(60), Optional.empty()));
        assertInvalid(() -> grant(Set.of("weather_get"), 60, CREATED, Optional.empty()));
        assertInvalid(() -> grant(Set.of("weather_get"), 60,
                CREATED.plusSeconds(30L * 24 * 60 * 60 + 1), Optional.empty()));
        assertInvalid(() -> grant(Set.of("weather_get"), 60,
                CREATED.plusSeconds(60), Optional.of(CREATED.minusSeconds(1))));
    }

    @Test
    void keepsPrincipalOutOfDiagnosticText() {
        ManagedRuntimeGrant grant = new ManagedRuntimeGrant(
                ID, RUNTIME, OWNER, "private-client", Set.of("weather_get"), 60,
                CREATED, CREATED.plusSeconds(60), Optional.empty());

        assertFalse(grant.toString().contains("private-client"));
    }

    private ManagedRuntimeGrant grant(
            Set<String> tools, int rate, Instant expiresAt, Optional<Instant> revokedAt) {
        return new ManagedRuntimeGrant(
                ID, RUNTIME, OWNER, "client-1", tools, rate,
                CREATED, expiresAt, revokedAt);
    }

    private void assertInvalid(Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals("Managed runtime grant is invalid", failure.getMessage());
    }
}
