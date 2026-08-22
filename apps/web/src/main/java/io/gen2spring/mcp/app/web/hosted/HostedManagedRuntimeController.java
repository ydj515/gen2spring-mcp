package io.gen2spring.mcp.app.web.hosted;

import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeService;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeMigrationService;
import io.gen2spring.mcp.app.web.security.HostedAccountResolver;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.TreeMap;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "gen2spring.mode", havingValue = "hosted")
final class HostedManagedRuntimeController {
    private final HostedAccountResolver accounts;
    private final ManagedRuntimeService runtimes;
    private final ManagedRuntimeMigrationService migrations;
    private final Clock clock;

    HostedManagedRuntimeController(
            HostedAccountResolver accounts,
            ManagedRuntimeService runtimes,
            ManagedRuntimeMigrationService migrations,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.migrations = Objects.requireNonNull(migrations, "migrations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping("/api/tool-catalogs/{catalogId}/runtimes")
    @ResponseStatus(HttpStatus.CREATED)
    ManagedRuntimeResponse activate(
            Authentication authentication,
            @PathVariable String catalogId,
            @RequestBody(required = false) ActivationRequest request) {
        ActivationRequest value = request == null ? new ActivationRequest(null, null, null) : request;
        var activation = runtimes.activate(
                accounts.resolve(authentication).accountId(),
                uuid(catalogId),
                Optional.ofNullable(value.providerBaseUrl()),
                Optional.ofNullable(value.lifetimeSeconds()).map(this::duration),
                credentialBindings(value.credentialBindings()));
        return ManagedRuntimeResponse.activated(activation, clock.instant());
    }

    @GetMapping("/api/runtimes/{runtimeId}")
    ManagedRuntimeResponse runtime(Authentication authentication, @PathVariable String runtimeId) {
        var instance = runtimes.require(
                accounts.resolve(authentication).accountId(), runtimeId(runtimeId));
        return ManagedRuntimeResponse.details(instance, clock.instant());
    }

    @PostMapping("/api/runtimes/{runtimeId}/revocation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(Authentication authentication, @PathVariable String runtimeId) {
        runtimes.revoke(accounts.resolve(authentication).accountId(), runtimeId(runtimeId));
    }

    @PostMapping("/api/runtimes/{runtimeId}/migrations")
    RuntimeMigrationResponse migrate(
            Authentication authentication,
            @PathVariable String runtimeId,
            @RequestBody(required = false) MigrationRequest request) {
        if (request == null) {
            throw new ManagedRuntimeMigrationService.RuntimeMigrationRequestInvalid();
        }
        var result = migrations.migrate(
                accounts.resolve(authentication).accountId(), migrationRuntimeId(runtimeId),
                migrationUuid(request.expectedCurrentCatalogId()),
                migrationUuid(request.targetCatalogId()), request.targetChecksum());
        return RuntimeMigrationResponse.from(result, clock.instant());
    }

    @GetMapping("/api/runtimes/{runtimeId}/migrations")
    RuntimeTransitionPageResponse migrations(
            Authentication authentication,
            @PathVariable String runtimeId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long before) {
        var page = migrations.history(
                accounts.resolve(authentication).accountId(), migrationRuntimeId(runtimeId),
                limit, Optional.ofNullable(before));
        return RuntimeTransitionPageResponse.from(page);
    }

    @PostMapping("/api/runtimes/{runtimeId}/rollback")
    RuntimeMigrationResponse rollback(
            Authentication authentication,
            @PathVariable String runtimeId,
            @RequestBody(required = false) RollbackRequest request) {
        if (request == null) {
            throw new ManagedRuntimeMigrationService.RuntimeMigrationRequestInvalid();
        }
        var result = migrations.rollback(
                accounts.resolve(authentication).accountId(), migrationRuntimeId(runtimeId),
                migrationUuid(request.expectedCurrentCatalogId()));
        return RuntimeMigrationResponse.from(result, clock.instant());
    }

    private Duration duration(long seconds) {
        return Duration.ofSeconds(seconds);
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException failure) {
            throw new ManagedRuntimeService.ManagedRuntimeRequestInvalid();
        }
    }

    private RuntimeInstanceId runtimeId(String value) {
        try {
            return RuntimeInstanceId.parse(value);
        } catch (RuntimeException failure) {
            throw new ManagedRuntimeService.ManagedRuntimeRequestInvalid();
        }
    }

    private UUID migrationUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException failure) {
            throw new ManagedRuntimeMigrationService.RuntimeMigrationRequestInvalid();
        }
    }

    private RuntimeInstanceId migrationRuntimeId(String value) {
        try {
            return RuntimeInstanceId.parse(value);
        } catch (RuntimeException failure) {
            throw new ManagedRuntimeMigrationService.RuntimeMigrationRequestInvalid();
        }
    }

    private Map<String, ManagedCredentialId> credentialBindings(Map<String, String> requested) {
        if (requested == null) return Map.of();
        try {
            Map<String, ManagedCredentialId> result = new TreeMap<>();
            requested.forEach((slot, id) -> result.put(slot, ManagedCredentialId.parse(id)));
            return java.util.Collections.unmodifiableMap(result);
        } catch (RuntimeException failure) {
            throw new ManagedRuntimeService.ManagedRuntimeRequestInvalid();
        }
    }

    record ActivationRequest(
            String providerBaseUrl,
            Long lifetimeSeconds,
            Map<String, String> credentialBindings) {}

    record MigrationRequest(
            String expectedCurrentCatalogId,
            String targetCatalogId,
            String targetChecksum) {}

    record RollbackRequest(String expectedCurrentCatalogId) {}
}
