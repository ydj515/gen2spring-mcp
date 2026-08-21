package io.gen2spring.mcp.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.managed.credential.ManagedCredentialStore.StoredCredential;
import io.gen2spring.mcp.application.managed.credential.ProtectedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialKind;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresManagedCredentialStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.9-alpine");

    private JdbcTemplate jdbc;
    private PostgresManagedCredentialStore store;

    @BeforeEach
    void resetDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new PostgresManagedCredentialStore(dataSource);
    }

    @Test
    void storesEncryptedVersionsAndExposesOnlyOwnerScopedMetadataLists() {
        AccountId owner = account();
        AccountId foreign = account();
        ManagedCredential credential = credential(owner, 1, Optional.empty());
        ProtectedCredential protectedValue = protectedValue(1, (byte) 1);

        store.create(credential, protectedValue);

        StoredCredential stored = store.find(owner, credential.id()).orElseThrow();
        assertEquals(credential, stored.credential());
        assertEquals(protectedValue, stored.protectedValue());
        assertEquals(java.util.List.of(credential), store.list(owner));
        assertEquals(1, store.countActive(owner));
        assertTrue(store.find(foreign, credential.id()).isEmpty());
        assertTrue(store.list(foreign).isEmpty());
        assertEquals(0, store.countActive(foreign));
    }

    @Test
    void rotatesWithCompareAndSetAndRevokesIdempotently() {
        AccountId owner = account();
        ManagedCredential initial = credential(owner, 1, Optional.empty());
        store.create(initial, protectedValue(1, (byte) 1));
        ManagedCredential rotated = new ManagedCredential(
                initial.id(), owner, initial.label(), initial.kind(), 2,
                initial.createdAt(), NOW.plusSeconds(10), Optional.empty());

        assertFalse(store.rotate(owner, initial.id(), 2, rotated, protectedValue(2, (byte) 2)));
        assertTrue(store.rotate(owner, initial.id(), 1, rotated, protectedValue(2, (byte) 2)));
        assertEquals(2, store.find(owner, initial.id()).orElseThrow().credential().version());
        assertFalse(store.rotate(owner, initial.id(), 1, rotated, protectedValue(2, (byte) 2)));

        assertTrue(store.revoke(owner, initial.id(), NOW.plusSeconds(20)));
        assertTrue(store.revoke(owner, initial.id(), NOW.plusSeconds(30)));
        StoredCredential revoked = store.find(owner, initial.id()).orElseThrow();
        assertEquals(Optional.of(NOW.plusSeconds(20)), revoked.credential().revokedAt());
        assertEquals(0, store.countActive(owner));
    }

    private AccountId account() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into account(id, issuer, subject, created_at, last_seen_at)
                values (?, 'https://issuer.example', ?, ?, ?)
                """, id, id.toString(), Timestamp.from(NOW), Timestamp.from(NOW));
        return new AccountId(id);
    }

    private ManagedCredential credential(AccountId owner, long version, Optional<Instant> revokedAt) {
        return new ManagedCredential(
                new ManagedCredentialId(UUID.randomUUID()), owner, "provider-key",
                ManagedCredentialKind.OPAQUE, version, NOW, NOW, revokedAt);
    }

    private ProtectedCredential protectedValue(long version, byte fill) {
        return new ProtectedCredential(
                1, version, "operator-key", bytes(12, fill), bytes(48, fill),
                bytes(12, (byte) (fill + 1)), bytes(32, (byte) (fill + 2)));
    }

    private byte[] bytes(int size, byte fill) {
        byte[] value = new byte[size];
        java.util.Arrays.fill(value, fill);
        return value;
    }
}
