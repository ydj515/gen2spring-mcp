package io.gen2spring.mcp.application.managed.audit;

import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore;
import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore.AuditCursor;
import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore.AuditPage;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeService;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.util.Objects;
import java.util.Optional;

public final class RuntimeAuditService {
    private final ManagedRuntimeService runtimes;
    private final RuntimePolicyStore policies;

    public RuntimeAuditService(ManagedRuntimeService runtimes, RuntimePolicyStore policies) {
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    public AuditPage list(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            int limit,
            Optional<AuditCursor> cursor) {
        if (owner == null || runtimeId == null || cursor == null || limit < 1 || limit > 100) throw invalid();
        try {
            runtimes.require(owner, runtimeId);
            return Objects.requireNonNull(policies.listAudits(owner, runtimeId, limit, cursor));
        } catch (Error fatal) {
            throw fatal;
        } catch (ManagedRuntimeService.ManagedRuntimeNotFound failure) {
            throw notFound();
        } catch (ManagedRuntimeService.ManagedRuntimeRequestInvalid | IllegalArgumentException failure) {
            throw invalid();
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static RuntimeAuditRequestInvalid invalid() { return new RuntimeAuditRequestInvalid(); }
    private static RuntimeAuditNotFound notFound() { return new RuntimeAuditNotFound(); }
    private static RuntimeAuditUnavailable unavailable() { return new RuntimeAuditUnavailable(); }

    public static final class RuntimeAuditRequestInvalid extends RuntimeException {
        public RuntimeAuditRequestInvalid() { super("Managed runtime audit request is invalid", null, false, false); }
    }
    public static final class RuntimeAuditNotFound extends RuntimeException {
        public RuntimeAuditNotFound() { super("Managed runtime audit was not found", null, false, false); }
    }
    public static final class RuntimeAuditUnavailable extends RuntimeException {
        public RuntimeAuditUnavailable() { super("Managed runtime audit is unavailable", null, false, false); }
    }
}
