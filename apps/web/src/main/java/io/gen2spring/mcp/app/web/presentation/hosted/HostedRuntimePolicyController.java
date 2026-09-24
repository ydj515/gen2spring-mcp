package io.gen2spring.mcp.app.web.presentation.hosted;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.gen2spring.mcp.application.managed.audit.RuntimeAuditService;
import io.gen2spring.mcp.application.managed.policy.IssuedRuntimeGrant;
import io.gen2spring.mcp.application.managed.policy.RuntimeGrantService;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore.AuditCursor;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeService;
import io.gen2spring.mcp.app.web.presentation.security.HostedAccountResolver;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "gen2spring.mode", havingValue = "hosted")
final class HostedRuntimePolicyController {
    private final HostedAccountResolver accounts;
    private final RuntimeGrantService grants;
    private final RuntimeAuditService audits;
    private final Clock clock;
    private final HostedCursorCodec cursors = new HostedCursorCodec();

    HostedRuntimePolicyController(
            HostedAccountResolver accounts,
            RuntimeGrantService grants,
            RuntimeAuditService audits,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.audits = Objects.requireNonNull(audits, "audits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping("/api/runtimes/{runtimeId}/grants")
    ResponseEntity<GrantResponse> createGrant(
            Authentication authentication,
            @PathVariable String runtimeId,
            @RequestBody GrantRequest request) {
        GrantRequest checked = require(request);
        Set<String> allowedTools;
        Duration lifetime;
        try {
            allowedTools = Set.copyOf(checked.allowedTools());
            lifetime = Duration.ofSeconds(checked.lifetimeSeconds());
        } catch (RuntimeException failure) {
            throw invalidGrant();
        }
        IssuedRuntimeGrant issued = grants.create(
                accounts.resolve(authentication).accountId(), runtimeId(runtimeId), checked.principal(),
                allowedTools, checked.requestsPerMinute(), lifetime);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(grantResponse(issued.grant(), issued.plaintextToken()));
    }

    @GetMapping("/api/runtimes/{runtimeId}/grants")
    List<GrantResponse> grants(Authentication authentication, @PathVariable String runtimeId) {
        return grants.list(accounts.resolve(authentication).accountId(), runtimeId(runtimeId)).stream()
                .map(grant -> grantResponse(grant, null)).toList();
    }

    @PostMapping("/api/runtimes/{runtimeId}/grants/{grantId}/revocation")
    ResponseEntity<Void> revokeGrant(
            Authentication authentication,
            @PathVariable String runtimeId,
            @PathVariable String grantId) {
        grants.revoke(
                accounts.resolve(authentication).accountId(), runtimeId(runtimeId), grantId(grantId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/runtimes/{runtimeId}/audit")
    AuditResponse audit(
            Authentication authentication,
            @PathVariable String runtimeId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        Optional<HostedCursorCodec.Cursor> decoded;
        try {
            decoded = cursors.decode(cursor);
        } catch (RuntimeException failure) {
            throw new RuntimeAuditService.RuntimeAuditRequestInvalid();
        }
        var page = audits.list(
                accounts.resolve(authentication).accountId(), runtimeId(runtimeId), limit,
                decoded.map(value -> new AuditCursor(value.createdAt(), value.id())));
        return new AuditResponse(
                page.items().stream().map(HostedRuntimePolicyController::auditResponse).toList(),
                page.nextCursor().map(value -> cursors.encode(
                        new HostedCursorCodec.Cursor(value.startedAt(), value.executionId()))).orElse(null));
    }

    private RuntimeInstanceId runtimeId(String value) {
        try {
            return RuntimeInstanceId.parse(value);
        } catch (RuntimeException failure) {
            throw new ManagedRuntimeService.ManagedRuntimeRequestInvalid();
        }
    }

    private RuntimeGrantId grantId(String value) {
        try {
            return RuntimeGrantId.parse(value);
        } catch (RuntimeException failure) {
            throw invalidGrant();
        }
    }

    private GrantRequest require(GrantRequest request) {
        if (request == null || request.allowedTools() == null || request.lifetimeSeconds() == null
                || request.requestsPerMinute() == null) throw invalidGrant();
        return request;
    }

    private GrantResponse grantResponse(ManagedRuntimeGrant grant, String token) {
        return new GrantResponse(
                grant.id().value().toString(), grant.runtimeId().value().toString(), grant.principal(),
                List.copyOf(grant.allowedTools()), grant.requestsPerMinute(), grant.stateAt(clock.instant()).name(),
                grant.createdAt().toString(), grant.expiresAt().toString(),
                grant.revokedAt().map(Instant::toString).orElse(null), token);
    }

    private static AuditItem auditResponse(ToolExecutionAudit audit) {
        return new AuditItem(
                audit.executionId().toString(), audit.runtimeId().value().toString(),
                audit.grantId().map(value -> value.value().toString()).orElse(null), audit.principal(),
                audit.catalogChecksum(), audit.toolName(), audit.status().name(),
                audit.errorCategory().orElse(null), audit.providerStatus().orElse(null),
                audit.durationMillis(), audit.requestBytes(), audit.responseBytes(),
                audit.startedAt().toString(), audit.completedAt().map(Instant::toString).orElse(null));
    }

    private static RuntimeGrantService.RuntimeGrantRequestInvalid invalidGrant() {
        return new RuntimeGrantService.RuntimeGrantRequestInvalid();
    }

    record GrantRequest(
            String principal,
            Set<String> allowedTools,
            Integer requestsPerMinute,
            Long lifetimeSeconds) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GrantResponse(
            String grantId,
            String runtimeId,
            String principal,
            List<String> allowedTools,
            int requestsPerMinute,
            String state,
            String createdAt,
            String expiresAt,
            String revokedAt,
            String token) {
        @Override
        public String toString() {
            return "GrantResponse[grantId=" + grantId + ", runtimeId=" + runtimeId
                    + ", principal=redacted, allowedTools=" + allowedTools
                    + ", requestsPerMinute=" + requestsPerMinute + ", state=" + state
                    + ", createdAt=" + createdAt + ", expiresAt=" + expiresAt
                    + ", revokedAt=" + revokedAt + ", token=redacted]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AuditResponse(List<AuditItem> items, String nextCursor) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AuditItem(
            String executionId,
            String runtimeId,
            String grantId,
            String principal,
            String catalogChecksum,
            String toolName,
            String status,
            String errorCategory,
            Integer providerStatus,
            long durationMillis,
            long requestBytes,
            long responseBytes,
            String startedAt,
            String completedAt) {}
}
