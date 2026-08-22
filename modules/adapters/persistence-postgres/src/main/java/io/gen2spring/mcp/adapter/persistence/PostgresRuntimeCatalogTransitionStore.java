package io.gen2spring.mcp.adapter.persistence;

import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionOutcome.APPLIED;
import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionOutcome.BLOCKED;
import static io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionOutcome.CONFLICT;

import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance.RuntimeState;
import io.gen2spring.mcp.domain.platform.runtime.ProviderTarget;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresRuntimeCatalogTransitionStore implements RuntimeCatalogTransitionStore {
    private static final String RUNTIME_COLUMNS = """
            id, owner_account_id, catalog_id, catalog_checksum, provider_base_url,
            created_at, expires_at, revoked_at
            """;
    private static final String TRANSITION_COLUMNS = """
            sequence, runtime_id, source_catalog_id, source_catalog_checksum,
            target_catalog_id, target_catalog_checksum, diff_checksum,
            transition_kind, created_at
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresRuntimeCatalogTransitionStore(DataSource dataSource) {
        DataSource checked = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(checked);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(checked));
    }

    @Override
    public TransitionResult apply(MigrationCommand command, TransitionKind kind) {
        if (command == null || kind == null) {
            throw invalid();
        }
        try {
            TransitionResult result = transactions.execute(status -> applyLocked(command, kind));
            return Objects.requireNonNull(result);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw writeFailed();
        }
    }

    @Override
    public TransitionPage history(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            int limit,
            Optional<Long> before) {
        if (owner == null || runtimeId == null || before == null
                || limit < 1 || limit > 100 || before.filter(value -> value < 1).isPresent()) {
            throw invalid();
        }
        try {
            List<RuntimeCatalogTransition> rows;
            if (before.isPresent()) {
                rows = jdbc.query("select " + TRANSITION_COLUMNS
                                + " from managed_runtime_catalog_transition"
                                + " where owner_account_id = ? and runtime_id = ? and sequence < ?"
                                + " order by sequence desc limit ?",
                        (resultSet, row) -> transition(resultSet), owner.value(), runtimeId.value(),
                        before.orElseThrow(), limit + 1);
            } else {
                rows = jdbc.query("select " + TRANSITION_COLUMNS
                                + " from managed_runtime_catalog_transition"
                                + " where owner_account_id = ? and runtime_id = ?"
                                + " order by sequence desc limit ?",
                        (resultSet, row) -> transition(resultSet), owner.value(), runtimeId.value(), limit + 1);
            }
            boolean more = rows.size() > limit;
            List<RuntimeCatalogTransition> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            Optional<Long> next = more ? Optional.of(items.getLast().sequence()) : Optional.empty();
            return new TransitionPage(items, next);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw readFailed();
        }
    }

    @Override
    public Optional<RuntimeCatalogTransition> findRollbackCandidate(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            UUID currentCatalogId) {
        if (owner == null || runtimeId == null || currentCatalogId == null) {
            throw invalid();
        }
        try {
            return jdbc.query("select " + prefixedTransitionColumns("transition") + """
                             from managed_runtime_catalog_transition transition
                            where transition.owner_account_id = ?
                              and transition.runtime_id = ?
                              and transition.transition_kind = 'MIGRATION'
                              and transition.target_catalog_id = ?
                              and not exists (
                                  select 1
                                    from managed_runtime_catalog_transition rollback
                                   where rollback.runtime_id = transition.runtime_id
                                     and rollback.sequence > transition.sequence
                                     and rollback.transition_kind = 'ROLLBACK'
                                     and rollback.source_catalog_id = transition.target_catalog_id
                                     and rollback.target_catalog_id = transition.source_catalog_id)
                            order by transition.sequence desc
                            limit 1
                            """, (resultSet, row) -> transition(resultSet),
                    owner.value(), runtimeId.value(), currentCatalogId).stream().findFirst();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw readFailed();
        }
    }

    private TransitionResult applyLocked(MigrationCommand command, TransitionKind kind) {
        Optional<ManagedRuntimeInstance> found = jdbc.query(
                        "select " + RUNTIME_COLUMNS
                                + " from managed_runtime_instance where id = ? and owner_account_id = ? for update",
                        (resultSet, row) -> instance(resultSet),
                        command.runtimeId().value(), command.owner().value())
                .stream().findFirst();
        if (found.isEmpty()) {
            return outcome(CONFLICT);
        }
        ManagedRuntimeInstance current = found.orElseThrow();
        if (current.stateAt(command.observedAt()) != RuntimeState.ACTIVE) {
            return outcome(BLOCKED);
        }
        if (!current.catalogId().equals(command.expectedCurrentCatalogId())
                || !current.catalogChecksum().equals(command.expectedCurrentChecksum())) {
            return outcome(CONFLICT);
        }
        Integer targetExists = jdbc.queryForObject("""
                select count(*)
                  from tool_catalog
                 where id = ? and owner_account_id = ? and metadata_checksum = ?
                """, Integer.class, command.targetCatalogId(), command.owner().value(), command.targetChecksum());
        if (targetExists == null || targetExists != 1) {
            return outcome(CONFLICT);
        }
        if (!activeGrantTools(command).stream().allMatch(command.targetTools()::contains)) {
            return outcome(BLOCKED);
        }

        int updated = jdbc.update("""
                update managed_runtime_instance
                   set catalog_id = ?, catalog_checksum = ?
                 where id = ? and owner_account_id = ?
                   and catalog_id = ? and catalog_checksum = ?
                   and revoked_at is null and expires_at > ?
                """, command.targetCatalogId(), command.targetChecksum(), command.runtimeId().value(),
                command.owner().value(), command.expectedCurrentCatalogId(), command.expectedCurrentChecksum(),
                Timestamp.from(command.observedAt()));
        if (updated != 1) {
            return outcome(CONFLICT);
        }
        Long maximum = jdbc.queryForObject("""
                select coalesce(max(sequence), 0)
                  from managed_runtime_catalog_transition
                 where runtime_id = ?
                """, Long.class, command.runtimeId().value());
        long sequence = Objects.requireNonNull(maximum) + 1;
        jdbc.update("""
                insert into managed_runtime_catalog_transition(
                    runtime_id, sequence, owner_account_id,
                    source_catalog_id, source_catalog_checksum,
                    target_catalog_id, target_catalog_checksum,
                    diff_checksum, transition_kind, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.runtimeId().value(), sequence, command.owner().value(),
                command.expectedCurrentCatalogId(), command.expectedCurrentChecksum(),
                command.targetCatalogId(), command.targetChecksum(), command.diffChecksum(),
                kind.name(), Timestamp.from(command.observedAt()));

        ManagedRuntimeInstance migrated = new ManagedRuntimeInstance(
                current.id(), current.owner(), command.targetCatalogId(), command.targetChecksum(),
                current.providerBaseUrl(), current.createdAt(), current.expiresAt(), current.revokedAt());
        RuntimeCatalogTransition transition = new RuntimeCatalogTransition(
                sequence, current.id(), current.catalogId(), current.catalogChecksum(),
                command.targetCatalogId(), command.targetChecksum(), command.diffChecksum(),
                kind, command.observedAt());
        return new TransitionResult(APPLIED, Optional.of(migrated), Optional.of(transition));
    }

    private Set<String> activeGrantTools(MigrationCommand command) {
        Set<String> result = new HashSet<>();
        jdbc.query("""
                select allowed_tools
                  from managed_runtime_grant
                 where runtime_id = ? and owner_account_id = ?
                   and revoked_at is null and expires_at > ?
                 for update
                """, resultSet -> {
            Array array = resultSet.getArray("allowed_tools");
            result.addAll(List.of((String[]) array.getArray()));
        }, command.runtimeId().value(), command.owner().value(), Timestamp.from(command.observedAt()));
        return Set.copyOf(result);
    }

    private ManagedRuntimeInstance instance(ResultSet resultSet) throws SQLException {
        String provider = resultSet.getString("provider_base_url");
        Timestamp revoked = resultSet.getTimestamp("revoked_at");
        return new ManagedRuntimeInstance(
                new RuntimeInstanceId(resultSet.getObject("id", UUID.class)),
                new AccountId(resultSet.getObject("owner_account_id", UUID.class)),
                resultSet.getObject("catalog_id", UUID.class),
                resultSet.getString("catalog_checksum"),
                provider == null ? Optional.empty() : Optional.of(ProviderTarget.parse(provider)),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                revoked == null ? Optional.empty() : Optional.of(revoked.toInstant()));
    }

    private RuntimeCatalogTransition transition(ResultSet resultSet) throws SQLException {
        return new RuntimeCatalogTransition(
                resultSet.getLong("sequence"),
                new RuntimeInstanceId(resultSet.getObject("runtime_id", UUID.class)),
                resultSet.getObject("source_catalog_id", UUID.class),
                resultSet.getString("source_catalog_checksum"),
                resultSet.getObject("target_catalog_id", UUID.class),
                resultSet.getString("target_catalog_checksum"),
                resultSet.getString("diff_checksum"),
                TransitionKind.valueOf(resultSet.getString("transition_kind")),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private String prefixedTransitionColumns(String alias) {
        return """
                %s.sequence, %s.runtime_id, %s.source_catalog_id, %s.source_catalog_checksum,
                %s.target_catalog_id, %s.target_catalog_checksum, %s.diff_checksum,
                %s.transition_kind, %s.created_at
                """.formatted(alias, alias, alias, alias, alias, alias, alias, alias, alias);
    }

    private TransitionResult outcome(TransitionOutcome outcome) {
        return new TransitionResult(outcome, Optional.empty(), Optional.empty());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Runtime Catalog transition storage request is invalid");
    }

    private static IllegalStateException writeFailed() {
        return new IllegalStateException("Runtime Catalog transition storage write failed");
    }

    private static IllegalStateException readFailed() {
        return new IllegalStateException("Runtime Catalog transition storage read failed");
    }
}
