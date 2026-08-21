package io.gen2spring.mcp.app.web.hosted;

import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeService;
import io.gen2spring.mcp.app.web.security.HostedAccountResolver;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "gen2spring.mode", havingValue = "hosted")
final class HostedManagedRuntimeController {
    private final HostedAccountResolver accounts;
    private final ManagedRuntimeService runtimes;
    private final Clock clock;

    HostedManagedRuntimeController(
            HostedAccountResolver accounts,
            ManagedRuntimeService runtimes,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping("/api/tool-catalogs/{catalogId}/runtimes")
    @ResponseStatus(HttpStatus.CREATED)
    ManagedRuntimeResponse activate(
            Authentication authentication,
            @PathVariable String catalogId,
            @RequestBody(required = false) ActivationRequest request) {
        ActivationRequest value = request == null ? new ActivationRequest(null, null) : request;
        var activation = runtimes.activate(
                accounts.resolve(authentication).accountId(),
                uuid(catalogId),
                Optional.ofNullable(value.providerBaseUrl()),
                Optional.ofNullable(value.lifetimeSeconds()).map(this::duration));
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

    private Duration duration(long seconds) {
        try {
            return Duration.ofSeconds(seconds);
        } catch (RuntimeException failure) {
            throw new ManagedRuntimeService.ManagedRuntimeRequestInvalid();
        }
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

    record ActivationRequest(String providerBaseUrl, Long lifetimeSeconds) {}
}
