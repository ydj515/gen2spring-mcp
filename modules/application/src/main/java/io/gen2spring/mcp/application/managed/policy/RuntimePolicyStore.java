package io.gen2spring.mcp.application.managed.policy;

import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface RuntimePolicyStore {
    boolean createGrant(
            ManagedRuntimeGrant grant,
            RuntimeTokenDigest digest,
            UUID expectedCatalogId,
            String expectedCatalogChecksum,
            Instant observedAt);

    Optional<StoredGrant> authenticateGrant(RuntimeInstanceId runtimeId, RuntimeTokenDigest digest);

    List<ManagedRuntimeGrant> listGrants(AccountId owner, RuntimeInstanceId runtimeId);

    boolean revokeGrant(AccountId owner, RuntimeInstanceId runtimeId, RuntimeGrantId grantId, Instant revokedAt);

    boolean acquireRate(RuntimeInstanceId runtimeId, Optional<RuntimeGrantId> grantId, int requestsPerMinute);

    void startAudit(ToolExecutionAudit audit);

    boolean completeAudit(ToolExecutionAudit audit);

    AuditPage listAudits(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            int limit,
            Optional<AuditCursor> cursor);

    record StoredGrant(ManagedRuntimeGrant grant, RuntimeTokenDigest tokenDigest) {
        public StoredGrant {
            Objects.requireNonNull(grant, "grant");
            Objects.requireNonNull(tokenDigest, "tokenDigest");
        }
    }

    record AuditCursor(Instant startedAt, UUID executionId) {
        public AuditCursor {
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(executionId, "executionId");
        }
    }

    record AuditPage(List<ToolExecutionAudit> items, Optional<AuditCursor> nextCursor) {
        public AuditPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (items.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Runtime audit page is invalid");
            }
            Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }
}
