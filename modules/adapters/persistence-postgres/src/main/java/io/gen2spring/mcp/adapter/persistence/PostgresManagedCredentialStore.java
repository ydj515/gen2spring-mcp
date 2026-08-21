package io.gen2spring.mcp.adapter.persistence;

import io.gen2spring.mcp.application.managed.credential.ManagedCredentialStore;
import io.gen2spring.mcp.application.managed.credential.ProtectedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialKind;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresManagedCredentialStore implements ManagedCredentialStore {
    private static final String COLUMNS = """
            id, owner_account_id, label, kind, credential_version, envelope_version, key_id,
            wrapped_key_nonce, wrapped_key, payload_nonce, ciphertext, created_at, rotated_at, revoked_at
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresManagedCredentialStore(DataSource dataSource) {
        DataSource checked = Objects.requireNonNull(dataSource, "dataSource");
        jdbc = new JdbcTemplate(checked);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(checked));
    }

    @Override
    public void create(ManagedCredential credential, ProtectedCredential protectedCredential) {
        requireMatching(credential, protectedCredential);
        byte[][] values = secretValues(protectedCredential);
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                        Object.class, credential.owner().value().toString());
                Long active = jdbc.queryForObject("""
                        select count(*) from managed_credential
                         where owner_account_id = ? and revoked_at is null
                        """, Long.class, credential.owner().value());
                if (active == null || active >= 100) throw writeFailed();
                jdbc.update("""
                        insert into managed_credential(
                            id, owner_account_id, label, kind, credential_version,
                            envelope_version, key_id, wrapped_key_nonce, wrapped_key,
                            payload_nonce, ciphertext, created_at, rotated_at, revoked_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        credential.id().value(), credential.owner().value(), credential.label(), credential.kind().name(),
                        credential.version(), protectedCredential.envelopeVersion(), protectedCredential.keyId(),
                        values[0], values[1], values[2], values[3],
                        Timestamp.from(credential.createdAt()), Timestamp.from(credential.rotatedAt()),
                        credential.revokedAt().map(Timestamp::from).orElse(null));
            });
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw writeFailed();
        } finally {
            clear(values);
        }
    }

    @Override
    public boolean rotate(
            AccountId owner,
            ManagedCredentialId id,
            long expectedVersion,
            ManagedCredential credential,
            ProtectedCredential protectedCredential) {
        if (owner == null || id == null || expectedVersion < 1
                || credential == null || !owner.equals(credential.owner()) || !id.equals(credential.id())) {
            throw invalid();
        }
        requireMatching(credential, protectedCredential);
        byte[][] values = secretValues(protectedCredential);
        try {
            return jdbc.update("""
                    update managed_credential
                       set credential_version = ?, envelope_version = ?, key_id = ?,
                           wrapped_key_nonce = ?, wrapped_key = ?, payload_nonce = ?, ciphertext = ?,
                           rotated_at = ?
                     where id = ? and owner_account_id = ? and credential_version = ? and revoked_at is null
                    """,
                    credential.version(), protectedCredential.envelopeVersion(), protectedCredential.keyId(),
                    values[0], values[1], values[2], values[3], Timestamp.from(credential.rotatedAt()),
                    id.value(), owner.value(), expectedVersion) == 1;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw writeFailed();
        } finally {
            clear(values);
        }
    }

    @Override
    public boolean revoke(AccountId owner, ManagedCredentialId id, Instant revokedAt) {
        if (owner == null || id == null || revokedAt == null) throw invalid();
        try {
            return jdbc.update("""
                    update managed_credential
                       set revoked_at = coalesce(revoked_at, ?)
                     where id = ? and owner_account_id = ?
                    """, Timestamp.from(revokedAt), id.value(), owner.value()) == 1;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw writeFailed();
        }
    }

    @Override
    public Optional<StoredCredential> find(AccountId owner, ManagedCredentialId id) {
        if (owner == null || id == null) throw invalid();
        try {
            return jdbc.query(
                            "select " + COLUMNS + " from managed_credential where id = ? and owner_account_id = ?",
                            (resultSet, row) -> stored(resultSet), id.value(), owner.value())
                    .stream().findFirst();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw readFailed();
        }
    }

    @Override
    public List<ManagedCredential> list(AccountId owner) {
        if (owner == null) throw invalid();
        try {
            return List.copyOf(jdbc.query("""
                    select id, owner_account_id, label, kind, credential_version,
                           created_at, rotated_at, revoked_at
                      from managed_credential
                     where owner_account_id = ?
                     order by created_at, id
                    """, (resultSet, row) -> credential(resultSet), owner.value()));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw readFailed();
        }
    }

    @Override
    public long countActive(AccountId owner) {
        if (owner == null) throw invalid();
        try {
            Long count = jdbc.queryForObject("""
                    select count(*) from managed_credential
                     where owner_account_id = ? and revoked_at is null
                    """, Long.class, owner.value());
            return Objects.requireNonNull(count);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw readFailed();
        }
    }

    private StoredCredential stored(ResultSet resultSet) throws SQLException {
        ManagedCredential credential = credential(resultSet);
        ProtectedCredential protectedCredential = new ProtectedCredential(
                resultSet.getInt("envelope_version"), resultSet.getLong("credential_version"),
                resultSet.getString("key_id"), resultSet.getBytes("wrapped_key_nonce"),
                resultSet.getBytes("wrapped_key"), resultSet.getBytes("payload_nonce"),
                resultSet.getBytes("ciphertext"));
        return new StoredCredential(credential, protectedCredential);
    }

    private ManagedCredential credential(ResultSet resultSet) throws SQLException {
        Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
        return new ManagedCredential(
                new ManagedCredentialId(resultSet.getObject("id", UUID.class)),
                new AccountId(resultSet.getObject("owner_account_id", UUID.class)),
                resultSet.getString("label"), ManagedCredentialKind.valueOf(resultSet.getString("kind")),
                resultSet.getLong("credential_version"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("rotated_at").toInstant(),
                revokedAt == null ? Optional.empty() : Optional.of(revokedAt.toInstant()));
    }

    private static void requireMatching(ManagedCredential credential, ProtectedCredential protectedCredential) {
        if (credential == null || protectedCredential == null
                || credential.version() != protectedCredential.credentialVersion()) throw invalid();
    }

    private static byte[][] secretValues(ProtectedCredential value) {
        return new byte[][] {value.wrappedKeyNonce(), value.wrappedKey(), value.payloadNonce(), value.ciphertext()};
    }

    private static void clear(byte[][] values) {
        for (byte[] value : values) Arrays.fill(value, (byte) 0);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Managed credential storage request is invalid");
    }

    private static IllegalStateException writeFailed() {
        return new IllegalStateException("Managed credential storage write failed");
    }

    private static IllegalStateException readFailed() {
        return new IllegalStateException("Managed credential storage read failed");
    }
}
