package io.gen2spring.mcp.adapter.persistence;

import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit.AuditStatus;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresRuntimePolicyStore implements RuntimePolicyStore {
    private static final String GRANT_COLUMNS = """
            id, runtime_id, owner_account_id, principal, allowed_tools, requests_per_minute,
            token_digest, created_at, expires_at, revoked_at
            """;
    private static final String GRANT_METADATA_COLUMNS = """
            id, runtime_id, owner_account_id, principal, allowed_tools, requests_per_minute,
            created_at, expires_at, revoked_at
            """;
    private static final String AUDIT_COLUMNS = """
            execution_id, owner_account_id, runtime_id, grant_id, principal, catalog_checksum,
            tool_name, status, error_category, provider_status, duration_millis,
            request_bytes, response_bytes, started_at, completed_at
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresRuntimePolicyStore(DataSource dataSource) {
        DataSource checked = Objects.requireNonNull(dataSource, "dataSource");
        jdbc = new JdbcTemplate(checked);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(checked));
    }

    @Override
    public boolean createGrant(
            ManagedRuntimeGrant grant,
            RuntimeTokenDigest digest,
            UUID expectedCatalogId,
            String expectedCatalogChecksum,
            Instant observedAt) {
        if (grant == null || digest == null || expectedCatalogId == null
                || expectedCatalogChecksum == null || observedAt == null) throw invalid();
        byte[] digestBytes = digest.value();
        try {
            Boolean created = transactions.execute(status -> createGrantLocked(
                    grant, digestBytes, expectedCatalogId, expectedCatalogChecksum, observedAt));
            return Boolean.TRUE.equals(created);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw writeFailed();
        } finally {
            Arrays.fill(digestBytes, (byte) 0);
        }
    }

    private boolean createGrantLocked(
            ManagedRuntimeGrant grant,
            byte[] digestBytes,
            UUID expectedCatalogId,
            String expectedCatalogChecksum,
            Instant observedAt) {
        boolean current = !jdbc.queryForList("""
                select id
                  from managed_runtime_instance
                 where id = ? and owner_account_id = ?
                   and catalog_id = ? and catalog_checksum = ?
                   and revoked_at is null and expires_at > ?
                 for update
                """, UUID.class, grant.runtimeId().value(), grant.owner().value(),
                expectedCatalogId, expectedCatalogChecksum, Timestamp.from(observedAt)).isEmpty();
        if (!current) {
            return false;
        }
        return jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        insert into managed_runtime_grant(
                            id, runtime_id, owner_account_id, principal, allowed_tools,
                            requests_per_minute, token_digest, created_at, expires_at, revoked_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                statement.setObject(1, grant.id().value());
                statement.setObject(2, grant.runtimeId().value());
                statement.setObject(3, grant.owner().value());
                statement.setString(4, grant.principal());
                statement.setArray(5, connection.createArrayOf("text", grant.allowedTools().toArray(String[]::new)));
                statement.setInt(6, grant.requestsPerMinute());
                statement.setBytes(7, digestBytes);
                statement.setTimestamp(8, Timestamp.from(grant.createdAt()));
                statement.setTimestamp(9, Timestamp.from(grant.expiresAt()));
                statement.setTimestamp(10, grant.revokedAt().map(Timestamp::from).orElse(null));
                return statement;
            }) == 1;
    }

    @Override
    public Optional<StoredGrant> authenticateGrant(RuntimeInstanceId runtimeId, RuntimeTokenDigest digest) {
        if (runtimeId == null || digest == null) throw invalid();
        byte[] digestBytes = digest.value();
        try {
            return jdbc.query(
                            "select " + GRANT_COLUMNS
                                    + " from managed_runtime_grant where runtime_id = ? and token_digest = ?",
                            (resultSet, row) -> storedGrant(resultSet), runtimeId.value(), digestBytes)
                    .stream().findFirst();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw readFailed();
        } finally {
            Arrays.fill(digestBytes, (byte) 0);
        }
    }

    @Override
    public List<ManagedRuntimeGrant> listGrants(AccountId owner, RuntimeInstanceId runtimeId) {
        if (owner == null || runtimeId == null) throw invalid();
        try {
            return List.copyOf(jdbc.query(
                    "select " + GRANT_METADATA_COLUMNS + " from managed_runtime_grant"
                            + " where owner_account_id = ? and runtime_id = ? order by created_at, id",
                    (resultSet, row) -> grant(resultSet), owner.value(), runtimeId.value()));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw readFailed();
        }
    }

    @Override
    public boolean revokeGrant(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            RuntimeGrantId grantId,
            Instant revokedAt) {
        if (owner == null || runtimeId == null || grantId == null || revokedAt == null) throw invalid();
        try {
            return jdbc.update("""
                    update managed_runtime_grant
                       set revoked_at = coalesce(revoked_at, ?)
                     where id = ? and runtime_id = ? and owner_account_id = ?
                    """, Timestamp.from(revokedAt), grantId.value(), runtimeId.value(), owner.value()) == 1;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw writeFailed();
        }
    }

    @Override
    public boolean acquireRate(
            RuntimeInstanceId runtimeId,
            Optional<RuntimeGrantId> grantId,
            int requestsPerMinute) {
        if (runtimeId == null || grantId == null || requestsPerMinute < 1 || requestsPerMinute > 6000) {
            throw invalid();
        }
        String accessKey = grantId.map(value -> value.value().toString()).orElse("owner");
        try {
            return !jdbc.queryForList("""
                    insert into managed_runtime_rate_window(runtime_id, access_key, window_start, request_count)
                    values (?, ?, date_trunc('minute', clock_timestamp()), 1)
                    on conflict (runtime_id, access_key) do update
                       set window_start = excluded.window_start,
                           request_count = case
                               when managed_runtime_rate_window.window_start < excluded.window_start then 1
                               else managed_runtime_rate_window.request_count + 1
                           end
                     where managed_runtime_rate_window.window_start < excluded.window_start
                        or managed_runtime_rate_window.request_count < ?
                    returning request_count
                    """, Integer.class, runtimeId.value(), accessKey, requestsPerMinute).isEmpty();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw writeFailed();
        }
    }

    @Override
    public void startAudit(ToolExecutionAudit audit) {
        if (audit == null || audit.status() != AuditStatus.STARTED) throw invalid();
        try {
            jdbc.update("""
                    insert into managed_tool_execution_audit(
                        execution_id, owner_account_id, runtime_id, grant_id, principal,
                        catalog_checksum, tool_name, status, duration_millis,
                        request_bytes, response_bytes, started_at)
                    values (?, ?, ?, ?, ?, ?, ?, 'STARTED', 0, 0, 0, ?)
                    """, audit.executionId(), audit.owner().value(), audit.runtimeId().value(),
                    audit.grantId().map(RuntimeGrantId::value).orElse(null), audit.principal(),
                    audit.catalogChecksum(), audit.toolName(), Timestamp.from(audit.startedAt()));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw writeFailed();
        }
    }

    @Override
    public boolean completeAudit(ToolExecutionAudit audit) {
        if (audit == null || audit.status() == AuditStatus.STARTED || audit.completedAt().isEmpty()) throw invalid();
        try {
            return jdbc.update("""
                    update managed_tool_execution_audit
                       set status = ?, error_category = ?, provider_status = ?, duration_millis = ?,
                           request_bytes = ?, response_bytes = ?, completed_at = ?
                     where execution_id = ? and owner_account_id = ? and runtime_id = ? and status = 'STARTED'
                    """, audit.status().name(), audit.errorCategory().orElse(null),
                    audit.providerStatus().orElse(null), audit.durationMillis(), audit.requestBytes(),
                    audit.responseBytes(), Timestamp.from(audit.completedAt().orElseThrow()),
                    audit.executionId(), audit.owner().value(), audit.runtimeId().value()) == 1;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw writeFailed();
        }
    }

    @Override
    public AuditPage listAudits(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            int limit,
            Optional<AuditCursor> cursor) {
        if (owner == null || runtimeId == null || cursor == null || limit < 1 || limit > 100) throw invalid();
        try {
            List<ToolExecutionAudit> rows;
            if (cursor.isPresent()) {
                AuditCursor value = cursor.orElseThrow();
                rows = jdbc.query("select " + AUDIT_COLUMNS + " from managed_tool_execution_audit"
                                + " where owner_account_id = ? and runtime_id = ?"
                                + " and (started_at, execution_id) < (?, ?)"
                                + " order by started_at desc, execution_id desc limit ?",
                        (resultSet, row) -> audit(resultSet), owner.value(), runtimeId.value(),
                        Timestamp.from(value.startedAt()), value.executionId(), limit + 1);
            } else {
                rows = jdbc.query("select " + AUDIT_COLUMNS + " from managed_tool_execution_audit"
                                + " where owner_account_id = ? and runtime_id = ?"
                                + " order by started_at desc, execution_id desc limit ?",
                        (resultSet, row) -> audit(resultSet), owner.value(), runtimeId.value(), limit + 1);
            }
            boolean more = rows.size() > limit;
            List<ToolExecutionAudit> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            Optional<AuditCursor> next = more
                    ? Optional.of(new AuditCursor(items.getLast().startedAt(), items.getLast().executionId()))
                    : Optional.empty();
            return new AuditPage(items, next);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw readFailed();
        }
    }

    private StoredGrant storedGrant(ResultSet resultSet) throws SQLException {
        return new StoredGrant(grant(resultSet), new RuntimeTokenDigest(resultSet.getBytes("token_digest")));
    }

    private ManagedRuntimeGrant grant(ResultSet resultSet) throws SQLException {
        Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
        Array array = resultSet.getArray("allowed_tools");
        String[] tools = (String[]) array.getArray();
        return new ManagedRuntimeGrant(
                new RuntimeGrantId(resultSet.getObject("id", UUID.class)),
                new RuntimeInstanceId(resultSet.getObject("runtime_id", UUID.class)),
                new AccountId(resultSet.getObject("owner_account_id", UUID.class)),
                resultSet.getString("principal"), Set.copyOf(new TreeSet<>(List.of(tools))),
                resultSet.getInt("requests_per_minute"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                revokedAt == null ? Optional.empty() : Optional.of(revokedAt.toInstant()));
    }

    private ToolExecutionAudit audit(ResultSet resultSet) throws SQLException {
        UUID grant = resultSet.getObject("grant_id", UUID.class);
        String category = resultSet.getString("error_category");
        Integer providerStatus = resultSet.getObject("provider_status", Integer.class);
        Timestamp completedAt = resultSet.getTimestamp("completed_at");
        return new ToolExecutionAudit(
                resultSet.getObject("execution_id", UUID.class),
                new AccountId(resultSet.getObject("owner_account_id", UUID.class)),
                new RuntimeInstanceId(resultSet.getObject("runtime_id", UUID.class)),
                grant == null ? Optional.empty() : Optional.of(new RuntimeGrantId(grant)),
                resultSet.getString("principal"), resultSet.getString("catalog_checksum"),
                resultSet.getString("tool_name"), AuditStatus.valueOf(resultSet.getString("status")),
                Optional.ofNullable(category), Optional.ofNullable(providerStatus),
                resultSet.getLong("duration_millis"), resultSet.getLong("request_bytes"),
                resultSet.getLong("response_bytes"), resultSet.getTimestamp("started_at").toInstant(),
                completedAt == null ? Optional.empty() : Optional.of(completedAt.toInstant()));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Runtime policy storage request is invalid");
    }

    private static IllegalStateException writeFailed() {
        return new IllegalStateException("Runtime policy storage write failed");
    }

    private static IllegalStateException readFailed() {
        return new IllegalStateException("Runtime policy storage read failed");
    }
}
