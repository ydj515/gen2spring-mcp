package io.gen2spring.mcp.adapter.persistence;

import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.ProviderTarget;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PostgresManagedRuntimeStore implements ManagedRuntimeStore {
    private static final String COLUMNS = """
            id, owner_account_id, catalog_id, catalog_checksum, provider_base_url,
            token_digest, created_at, expires_at, revoked_at
            """;

    private final JdbcTemplate jdbc;

    public PostgresManagedRuntimeStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest) {
        if (instance == null || digest == null) {
            throw invalid();
        }
        byte[] digestBytes = digest.value();
        try {
            jdbc.update("""
                    insert into managed_runtime_instance(
                        id, owner_account_id, catalog_id, catalog_checksum, provider_base_url,
                        token_digest, created_at, expires_at, revoked_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    instance.id().value(),
                    instance.owner().value(),
                    instance.catalogId(),
                    instance.catalogChecksum(),
                    instance.providerBaseUrl().map(target -> target.uri().toASCIIString()).orElse(null),
                    digestBytes,
                    Timestamp.from(instance.createdAt()),
                    Timestamp.from(instance.expiresAt()),
                    instance.revokedAt().map(Timestamp::from).orElse(null));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw writeFailed();
        } finally {
            java.util.Arrays.fill(digestBytes, (byte) 0);
        }
    }

    @Override
    public Optional<StoredRuntime> find(RuntimeInstanceId id) {
        if (id == null) {
            throw invalid();
        }
        try {
            return jdbc.query(
                            "select " + COLUMNS + " from managed_runtime_instance where id = ?",
                            (resultSet, row) -> stored(resultSet),
                            id.value())
                    .stream()
                    .findFirst();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw readFailed();
        }
    }

    @Override
    public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant revokedAt) {
        if (owner == null || id == null || revokedAt == null) {
            throw invalid();
        }
        try {
            return jdbc.update("""
                    update managed_runtime_instance
                       set revoked_at = coalesce(revoked_at, ?)
                     where id = ? and owner_account_id = ?
                    """, Timestamp.from(revokedAt), id.value(), owner.value()) == 1;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw writeFailed();
        }
    }

    private StoredRuntime stored(ResultSet resultSet) throws SQLException {
        String provider = resultSet.getString("provider_base_url");
        Timestamp revoked = resultSet.getTimestamp("revoked_at");
        ManagedRuntimeInstance instance = new ManagedRuntimeInstance(
                new RuntimeInstanceId(resultSet.getObject("id", UUID.class)),
                new AccountId(resultSet.getObject("owner_account_id", UUID.class)),
                resultSet.getObject("catalog_id", UUID.class),
                resultSet.getString("catalog_checksum"),
                provider == null ? Optional.empty() : Optional.of(ProviderTarget.parse(provider)),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                revoked == null ? Optional.empty() : Optional.of(revoked.toInstant()));
        return new StoredRuntime(instance, new RuntimeTokenDigest(resultSet.getBytes("token_digest")));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Managed runtime storage request is invalid");
    }

    private static IllegalStateException writeFailed() {
        return new IllegalStateException("Managed runtime storage write failed");
    }

    private static IllegalStateException readFailed() {
        return new IllegalStateException("Managed runtime storage read failed");
    }
}
