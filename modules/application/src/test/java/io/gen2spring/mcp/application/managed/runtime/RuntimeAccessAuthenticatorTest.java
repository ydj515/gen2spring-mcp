package io.gen2spring.mcp.application.managed.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeAccessAuthenticatorTest {
    private static final Instant NOW = Instant.parse("2026-08-21T01:00:00Z");
    private static final RuntimeInstanceId ID = new RuntimeInstanceId(
            UUID.fromString("30000000-0000-0000-0000-000000000001"));

    @Test
    void authenticatesOneActiveRuntimeWithOneStoreLookup() {
        Store store = new Store(active());
        RuntimeAccessAuthenticator authenticator = authenticator(store, true);

        RuntimeAccess access = authenticator.authenticate(ID, "g2s_rt_valid");

        assertEquals(ID, access.instance().id());
        assertEquals(1, store.findCount);
    }

    @Test
    void rejectsMissingWrongExpiredAndRevokedTokensWithOneSafeFailure() {
        assertUnauthorized(authenticator(new Store(null), true), "g2s_rt_missing");
        assertUnauthorized(authenticator(new Store(active()), false), "g2s_rt_wrong-private-marker");
        assertUnauthorized(authenticator(new Store(instance(NOW)), true), "g2s_rt_expired");
        assertUnauthorized(authenticator(new Store(instance(NOW.plusSeconds(60)).revokeAt(NOW)), true),
                "g2s_rt_revoked");
        assertUnauthorized(authenticator(new Store(active()), true), "");
        assertUnauthorized(authenticator(new Store(active()), true), "x".repeat(513));
    }

    private RuntimeAccessAuthenticator authenticator(Store store, boolean matches) {
        return new RuntimeAccessAuthenticator(
                store,
                new RuntimeTokenCodec() {
                    @Override
                    public IssuedRuntimeToken issue() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean matches(String token, RuntimeTokenDigest digest) {
                        return matches;
                    }
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ManagedRuntimeInstance active() {
        return instance(NOW.plusSeconds(60));
    }

    private ManagedRuntimeInstance instance(Instant expiresAt) {
        return new ManagedRuntimeInstance(
                ID,
                new AccountId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "a".repeat(64),
                Optional.empty(),
                NOW.minusSeconds(60),
                expiresAt,
                Optional.empty());
    }

    private void assertUnauthorized(RuntimeAccessAuthenticator authenticator, String token) {
        RuntimeAccessAuthenticator.RuntimeUnauthorized failure = assertThrows(
                RuntimeAccessAuthenticator.RuntimeUnauthorized.class,
                () -> authenticator.authenticate(ID, token));
        assertEquals("Managed runtime authentication failed", failure.getMessage());
        assertFalse(failure.toString().contains("private-marker"));
        assertFalse(failure.toString().contains(ID.value().toString()));
    }

    private static final class Store implements ManagedRuntimeStore {
        private final StoredRuntime stored;
        private int findCount;

        private Store(ManagedRuntimeInstance instance) {
            stored = instance == null ? null : new StoredRuntime(instance, new RuntimeTokenDigest(new byte[32]));
        }

        @Override
        public void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredRuntime> find(RuntimeInstanceId id) {
            findCount++;
            return Optional.ofNullable(stored);
        }

        @Override
        public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant revokedAt) {
            throw new UnsupportedOperationException();
        }
    }
}
