package io.gen2spring.mcp.application.managed.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.managed.credential.port.out.CredentialProtector;
import io.gen2spring.mcp.application.managed.credential.port.out.ManagedCredentialStore;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagedCredentialServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private static final AccountId FOREIGN = new AccountId(
            UUID.fromString("20000000-0000-0000-0000-000000000002"));
    private static final ManagedCredentialId ID = new ManagedCredentialId(
            UUID.fromString("30000000-0000-0000-0000-000000000003"));

    @Test
    void createsListsRotatesAndRevokesWithoutReturningProtectedData() {
        Store store = new Store();
        Protector protector = new Protector();
        ManagedCredentialService service = service(store, protector);

        ManagedCredential created = service.create(
                OWNER, "Partner API", CredentialSecret.opaque("private-value"));

        assertEquals(ID, created.id());
        assertEquals(1, created.version());
        assertEquals(List.of(created), service.list(OWNER));
        assertEquals(created, service.require(OWNER, ID));
        assertFalse(created.toString().contains("private-value"));
        assertFalse(protector.lastSecret.toString().contains("private-value"));

        ManagedCredential rotated = service.rotate(
                OWNER, ID, CredentialSecret.opaque("replacement-private-value"));
        assertEquals(2, rotated.version());
        assertEquals(rotated, service.require(OWNER, ID));

        service.revoke(OWNER, ID);
        assertEquals(ManagedCredential.CredentialState.REVOKED, service.require(OWNER, ID).state());
        service.revoke(OWNER, ID);
    }

    @Test
    void rejectsForeignMissingKindChangingAndOverLimitRequestsWithFixedMessages() {
        Store store = new Store();
        ManagedCredentialService service = service(store, new Protector());
        service.create(OWNER, "Partner API", CredentialSecret.opaque("private-value"));

        assertNotFound(() -> service.require(FOREIGN, ID));
        assertNotFound(() -> service.rotate(FOREIGN, ID, CredentialSecret.opaque("other-private")));
        assertInvalid(() -> service.rotate(OWNER, ID, CredentialSecret.bearer("other-private")));
        assertInvalid(() -> service.create(null, "Partner API", CredentialSecret.opaque("private")));

        store.activeCount = 100;
        assertInvalid(() -> service.create(OWNER, "Another", CredentialSecret.opaque("private")));

        store.activeCount = 0;
        store.failure = new ManagedCredentialStore.ManagedCredentialQuotaExceeded();
        assertInvalid(() -> service.create(OWNER, "Concurrent", CredentialSecret.opaque("private")));
    }

    @Test
    void preservesFatalErrorsAndMapsStoreFailuresWithoutLeakingValues() {
        Store store = new Store();
        ManagedCredentialService service = service(store, new Protector());
        store.failure = new IllegalStateException("private-db-diagnostic");

        ManagedCredentialService.ManagedCredentialUnavailable unavailable = assertThrows(
                ManagedCredentialService.ManagedCredentialUnavailable.class,
                () -> service.create(OWNER, "Partner API", CredentialSecret.opaque("private-value")));
        assertEquals("Managed credential service is unavailable", unavailable.getMessage());
        assertFalse(unavailable.toString().contains("private"));

        AssertionError fatal = new AssertionError("fatal");
        store.failure = fatal;
        assertSame(fatal, assertThrows(AssertionError.class,
                () -> service.list(OWNER)));
    }

    @Test
    void listsBoundedRevokedHistoryBeyondTheActiveCredentialQuota() {
        Store store = new Store();
        for (int index = 0; index < 101; index++) {
            ManagedCredential credential = new ManagedCredential(
                    new ManagedCredentialId(new UUID(0, index + 1)), OWNER, "credential-" + index,
                    io.gen2spring.mcp.domain.platform.credential.ManagedCredentialKind.OPAQUE, 1,
                    NOW.minusSeconds(200 - index), NOW.minusSeconds(200 - index), Optional.of(NOW));
            store.values.put(credential.id(),
                    new ManagedCredentialStore.StoredCredential(credential, protectedValue(1)));
        }

        assertEquals(101, service(store, new Protector()).list(OWNER).size());
    }

    private ManagedCredentialService service(Store store, Protector protector) {
        return new ManagedCredentialService(
                store, protector, Clock.fixed(NOW, ZoneOffset.UTC), () -> ID.value());
    }

    private void assertInvalid(Runnable action) {
        ManagedCredentialService.ManagedCredentialRequestInvalid failure = assertThrows(
                ManagedCredentialService.ManagedCredentialRequestInvalid.class, action::run);
        assertEquals("Managed credential request is invalid", failure.getMessage());
    }

    private void assertNotFound(Runnable action) {
        ManagedCredentialService.ManagedCredentialNotFound failure = assertThrows(
                ManagedCredentialService.ManagedCredentialNotFound.class, action::run);
        assertEquals("Managed credential was not found", failure.getMessage());
    }

    private static final class Protector implements CredentialProtector {
        private CredentialSecret lastSecret;

        @Override
        public ProtectedCredential protect(
                AccountId owner, ManagedCredentialId id, long version, CredentialSecret secret) {
            lastSecret = secret;
            return protectedValue(version);
        }

        @Override
        public CredentialSecret reveal(
                AccountId owner, ManagedCredentialId id, long version, ProtectedCredential protectedCredential) {
            return CredentialSecret.opaque("private-value");
        }
    }

    private static final class Store implements ManagedCredentialStore {
        private final Map<ManagedCredentialId, StoredCredential> values = new LinkedHashMap<>();
        private Throwable failure;
        private long activeCount;

        @Override
        public void create(ManagedCredential credential, ProtectedCredential protectedCredential) {
            fail();
            values.put(credential.id(), new StoredCredential(credential, protectedCredential));
        }

        @Override
        public boolean rotate(
                AccountId owner,
                ManagedCredentialId id,
                long expectedVersion,
                ManagedCredential credential,
                ProtectedCredential protectedCredential) {
            fail();
            StoredCredential existing = values.get(id);
            if (existing == null || !existing.credential().owner().equals(owner)
                    || existing.credential().version() != expectedVersion) return false;
            values.put(id, new StoredCredential(credential, protectedCredential));
            return true;
        }

        @Override
        public boolean revoke(AccountId owner, ManagedCredentialId id, Instant revokedAt) {
            fail();
            StoredCredential existing = values.get(id);
            if (existing == null || !existing.credential().owner().equals(owner)) return false;
            values.put(id, new StoredCredential(existing.credential().revokeAt(revokedAt), existing.protectedValue()));
            return true;
        }

        @Override
        public Optional<StoredCredential> find(AccountId owner, ManagedCredentialId id) {
            fail();
            return Optional.ofNullable(values.get(id))
                    .filter(value -> value.credential().owner().equals(owner));
        }

        @Override
        public List<ManagedCredential> list(AccountId owner) {
            fail();
            List<ManagedCredential> result = new ArrayList<>();
            values.values().stream()
                    .map(StoredCredential::credential)
                    .filter(value -> value.owner().equals(owner))
                    .forEach(result::add);
            return result;
        }

        @Override
        public long countActive(AccountId owner) {
            fail();
            return activeCount == 0 ? values.values().stream()
                    .map(StoredCredential::credential)
                    .filter(value -> value.owner().equals(owner))
                    .filter(value -> value.state() == ManagedCredential.CredentialState.ACTIVE)
                    .count() : activeCount;
        }

        private void fail() {
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
        }
    }

    private static ProtectedCredential protectedValue(long credentialVersion) {
        return new ProtectedCredential(
                1, credentialVersion, "key-a", new byte[12], new byte[48], new byte[12], new byte[32]);
    }
}
