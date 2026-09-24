package io.gen2spring.mcp.application.managed.runtime;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore;
import io.gen2spring.mcp.application.managed.runtime.port.out.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.port.out.ManagedRuntimeStore.StoredRuntime;
import io.gen2spring.mcp.application.managed.runtime.port.out.RuntimeTokenCodec;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant.GrantState;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance.RuntimeState;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class RuntimeAccessAuthenticator {
    private static final int MAX_TOKEN_LENGTH = 512;
    private final ManagedRuntimeStore store;
    private final RuntimeTokenCodec tokens;
    private final Clock clock;
    private final ToolCatalogService catalogs;
    private final RuntimePolicyStore policies;

    public RuntimeAccessAuthenticator(
            ManagedRuntimeStore store,
            RuntimeTokenCodec tokens,
            Clock clock,
            ToolCatalogService catalogs,
            RuntimePolicyStore policies) {
        this.store = Objects.requireNonNull(store, "store");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    public RuntimeAccess authenticate(RuntimeInstanceId id, String bearerToken) {
        if (id == null || bearerToken == null || bearerToken.isBlank()
                || bearerToken.length() > MAX_TOKEN_LENGTH
                || bearerToken.chars().anyMatch(Character::isISOControl)) {
            throw unauthorized();
        }
        try {
            Optional<StoredRuntime> found = Objects.requireNonNull(store.find(id));
            StoredRuntime stored = found.orElseThrow(RuntimeAccessAuthenticator::unauthorized);
            if (stored.instance().stateAt(clock.instant()) != RuntimeState.ACTIVE) {
                throw inactive();
            }
            Set<String> catalogTools = new LinkedHashSet<>();
            catalogs.require(stored.instance().owner(), stored.instance().catalogId())
                    .metadata().document().tools().forEach(tool -> catalogTools.add(tool.name()));
            if (tokens.matches(bearerToken, stored.tokenDigest())) {
                return access(stored, Optional.empty(), "owner", catalogTools, 600, true,
                        stored.instance().expiresAt());
            }
            RuntimeTokenDigest digest = tokens.digest(bearerToken);
            var grant = Objects.requireNonNull(policies.authenticateGrant(id, digest))
                    .orElseThrow(RuntimeAccessAuthenticator::unauthorized).grant();
            if (!grant.runtimeId().equals(id)
                    || !grant.owner().equals(stored.instance().owner())
                    || grant.stateAt(clock.instant()) != GrantState.ACTIVE
                    || !catalogTools.containsAll(grant.allowedTools())) throw unauthorized();
            return access(stored, Optional.of(grant.id()), grant.principal(), grant.allowedTools(),
                    grant.requestsPerMinute(), false, grant.expiresAt());
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeUnauthorized failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private RuntimeAccess access(
            StoredRuntime stored,
            Optional<io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId> grantId,
            String principal,
            Set<String> allowedTools,
            int requestsPerMinute,
            boolean ownerGrant,
            Instant validUntil) {
        return new RuntimeAccess(
                stored.instance(), grantId, principal, allowedTools, requestsPerMinute, ownerGrant,
                policyChecksum(grantId, principal, allowedTools, requestsPerMinute, ownerGrant, validUntil),
                validUntil);
    }

    private String policyChecksum(
            Optional<io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId> grantId,
            String principal,
            Set<String> tools,
            int requestsPerMinute,
            boolean ownerGrant,
            Instant validUntil) {
        try {
            String canonical = "runtime-policy:v1\n"
                    + (grantId.isEmpty() ? "owner" : grantId.orElseThrow().value()) + "\n"
                    + principal + "\n" + requestsPerMinute + "\n" + ownerGrant + "\n"
                    + validUntil + "\n"
                    + String.join("\n", new java.util.TreeSet<>(tools)) + "\n";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    private static RuntimeUnauthorized unauthorized() {
        return new RuntimeUnauthorized();
    }

    private static RuntimeAccessUnavailable unavailable() {
        return new RuntimeAccessUnavailable();
    }

    private static RuntimeInactive inactive() {
        return new RuntimeInactive();
    }

    public static class RuntimeUnauthorized extends RuntimeException {
        public RuntimeUnauthorized() {
            super("Managed runtime authentication failed", null, false, false);
        }
    }

    public static final class RuntimeInactive extends RuntimeUnauthorized {
        public RuntimeInactive() {}
    }

    public static final class RuntimeAccessUnavailable extends RuntimeException {
        public RuntimeAccessUnavailable() {
            super("Managed runtime authentication is unavailable", null, false, false);
        }
    }
}
