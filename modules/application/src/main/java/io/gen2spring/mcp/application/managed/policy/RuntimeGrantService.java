package io.gen2spring.mcp.application.managed.policy;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.managed.runtime.IssuedRuntimeToken;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeService;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenCodec;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class RuntimeGrantService {
    private static final Duration MAX_LIFETIME = Duration.ofDays(30);
    private final ManagedRuntimeService runtimes;
    private final ToolCatalogService catalogs;
    private final RuntimePolicyStore policies;
    private final RuntimeTokenCodec tokens;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public RuntimeGrantService(
            ManagedRuntimeService runtimes,
            ToolCatalogService catalogs,
            RuntimePolicyStore policies,
            RuntimeTokenCodec tokens,
            Clock clock) {
        this(runtimes, catalogs, policies, tokens, clock, UUID::randomUUID);
    }

    RuntimeGrantService(
            ManagedRuntimeService runtimes,
            ToolCatalogService catalogs,
            RuntimePolicyStore policies,
            RuntimeTokenCodec tokens,
            Clock clock,
            Supplier<UUID> identifiers) {
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    public IssuedRuntimeGrant create(
            AccountId owner,
            RuntimeInstanceId runtimeId,
            String principal,
            Set<String> allowedTools,
            int requestsPerMinute,
            Duration lifetime) {
        if (owner == null || runtimeId == null || allowedTools == null || lifetime == null
                || lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw invalid();
        }
        try {
            ManagedRuntimeInstance runtime = requireRuntime(owner, runtimeId);
            Instant now = clock.instant();
            if (runtime.stateAt(now) != ManagedRuntimeInstance.RuntimeState.ACTIVE
                    || now.plus(lifetime).isAfter(runtime.expiresAt())) throw invalid();
            var catalog = catalogs.require(owner, runtime.catalogId());
            Set<String> catalogTools = catalog.metadata().document().tools().stream()
                    .map(tool -> tool.name()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (allowedTools.isEmpty() || !catalogTools.containsAll(allowedTools)) throw invalid();
            IssuedRuntimeToken issued = tokens.issue();
            ManagedRuntimeGrant grant = new ManagedRuntimeGrant(
                    new RuntimeGrantId(Objects.requireNonNull(identifiers.get())), runtimeId, owner,
                    principal, allowedTools, requestsPerMinute, now, now.plus(lifetime), Optional.empty());
            policies.createGrant(grant, issued.digest());
            return new IssuedRuntimeGrant(grant, issued.plaintext());
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeGrantRequestInvalid failure) {
            throw failure;
        } catch (RuntimeGrantNotFound | ToolCatalogService.ToolCatalogNotFound failure) {
            throw notFound();
        } catch (RuntimeGrantUnavailable failure) {
            throw failure;
        } catch (IllegalArgumentException | ToolCatalogService.ToolCatalogQueryInvalid failure) {
            throw invalid();
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    public List<ManagedRuntimeGrant> list(AccountId owner, RuntimeInstanceId runtimeId) {
        requireRuntime(owner, runtimeId);
        try {
            List<ManagedRuntimeGrant> result = List.copyOf(policies.listGrants(owner, runtimeId));
            if (result.stream().anyMatch(grant -> grant == null || !owner.equals(grant.owner())
                    || !runtimeId.equals(grant.runtimeId()))) throw unavailable();
            return result;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeGrantUnavailable failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    public void revoke(AccountId owner, RuntimeInstanceId runtimeId, RuntimeGrantId grantId) {
        requireRuntime(owner, runtimeId);
        if (grantId == null) throw invalid();
        try {
            ManagedRuntimeGrant grant = policies.listGrants(owner, runtimeId).stream()
                    .filter(value -> value.id().equals(grantId)).findFirst().orElseThrow(RuntimeGrantService::notFound);
            if (grant.revokedAt().isPresent()) return;
            if (!policies.revokeGrant(owner, runtimeId, grantId, clock.instant())) throw unavailable();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeGrantNotFound | RuntimeGrantUnavailable failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private ManagedRuntimeInstance requireRuntime(AccountId owner, RuntimeInstanceId runtimeId) {
        try {
            return runtimes.require(owner, runtimeId);
        } catch (ManagedRuntimeService.ManagedRuntimeNotFound failure) {
            throw notFound();
        } catch (ManagedRuntimeService.ManagedRuntimeRequestInvalid failure) {
            throw invalid();
        } catch (ManagedRuntimeService.ManagedRuntimeUnavailable failure) {
            throw unavailable();
        }
    }

    private static RuntimeGrantRequestInvalid invalid() { return new RuntimeGrantRequestInvalid(); }
    private static RuntimeGrantNotFound notFound() { return new RuntimeGrantNotFound(); }
    private static RuntimeGrantUnavailable unavailable() { return new RuntimeGrantUnavailable(); }

    public static final class RuntimeGrantRequestInvalid extends RuntimeException {
        public RuntimeGrantRequestInvalid() { super("Managed runtime grant request is invalid", null, false, false); }
    }
    public static final class RuntimeGrantNotFound extends RuntimeException {
        public RuntimeGrantNotFound() { super("Managed runtime grant was not found", null, false, false); }
    }
    public static final class RuntimeGrantUnavailable extends RuntimeException {
        public RuntimeGrantUnavailable() { super("Managed runtime grant service is unavailable", null, false, false); }
    }
}
