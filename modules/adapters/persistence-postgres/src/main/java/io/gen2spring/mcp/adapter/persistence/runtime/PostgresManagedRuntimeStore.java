package io.gen2spring.mcp.adapter.persistence.runtime;

import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.application.managed.runtime.port.out.ManagedRuntimeStore;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.ProviderTarget;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresManagedRuntimeStore implements ManagedRuntimeStore {
    private static final String COLUMNS = """
            id, owner_account_id, catalog_id, catalog_checksum, provider_base_url,
            token_digest, created_at, expires_at, revoked_at
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresManagedRuntimeStore(DataSource dataSource) {
        DataSource checked = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(checked);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(checked));
    }

    @Override
    public void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest) {
        create(instance, digest, Map.of());
    }

    @Override
    public void create(
            ManagedRuntimeInstance instance,
            RuntimeTokenDigest digest,
            Map<String, ManagedCredentialId> credentialBindings) {
        if (instance == null || digest == null || credentialBindings == null
                || credentialBindings.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                        || !entry.getKey().matches("[a-z][a-z0-9_-]{0,127}") || entry.getValue() == null)) {
            throw invalid();
        }
        Map<String, ManagedCredentialId> bindings = Collections.unmodifiableMap(new TreeMap<>(credentialBindings));
        byte[] digestBytes = digest.value();
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("""
                        insert into managed_runtime_instance(
                            id, owner_account_id, catalog_id, catalog_checksum, provider_base_url,
                            token_digest, created_at, expires_at, revoked_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        instance.id().value(), instance.owner().value(), instance.catalogId(),
                        instance.catalogChecksum(),
                        instance.providerBaseUrl().map(target -> target.uri().toASCIIString()).orElse(null),
                        digestBytes, Timestamp.from(instance.createdAt()), Timestamp.from(instance.expiresAt()),
                        instance.revokedAt().map(Timestamp::from).orElse(null));
                bindings.forEach((slot, credentialId) -> {
                    int inserted = jdbc.update("""
                            insert into managed_runtime_credential_binding(
                                runtime_id, owner_account_id, credential_slot, credential_id, credential_version)
                            select ?, ?, ?, id, credential_version
                              from managed_credential
                             where id = ? and owner_account_id = ? and revoked_at is null
                            """, instance.id().value(), instance.owner().value(), slot,
                            credentialId.value(), instance.owner().value());
                    if (inserted != 1) throw writeFailed();
                });
            });
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
            Optional<StoredRuntime> runtime = jdbc.query(
                            "select " + COLUMNS + " from managed_runtime_instance where id = ?",
                            (resultSet, row) -> stored(resultSet, Map.of()),
                            id.value())
                    .stream()
                    .findFirst();
            if (runtime.isEmpty()) return Optional.empty();
            Map<String, ManagedCredentialId> bindings = new TreeMap<>();
            jdbc.query("""
                    select credential_slot, credential_id
                      from managed_runtime_credential_binding
                     where runtime_id = ?
                     order by credential_slot
                    """, resultSet -> {
                bindings.put(resultSet.getString("credential_slot"),
                        new ManagedCredentialId(resultSet.getObject("credential_id", UUID.class)));
            }, id.value());
            StoredRuntime stored = runtime.orElseThrow();
            return Optional.of(new StoredRuntime(stored.instance(), stored.tokenDigest(), bindings));
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

    private StoredRuntime stored(ResultSet resultSet, Map<String, ManagedCredentialId> bindings) throws SQLException {
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
        return new StoredRuntime(instance, new RuntimeTokenDigest(resultSet.getBytes("token_digest")), bindings);
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
