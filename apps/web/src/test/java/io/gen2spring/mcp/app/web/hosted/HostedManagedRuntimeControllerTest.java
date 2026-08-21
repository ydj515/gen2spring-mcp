package io.gen2spring.mcp.app.web.hosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeService;
import io.gen2spring.mcp.application.managed.runtime.RuntimeActivation;
import io.gen2spring.mcp.app.web.security.HostedAccountPrincipal;
import io.gen2spring.mcp.app.web.security.HostedAccountResolver;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class HostedManagedRuntimeControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final AccountId OWNER = new AccountId(UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9"));
    private static final UUID CATALOG = UUID.fromString("6d65bd83-547b-4965-82f0-eb31af0dcd21");
    private static final RuntimeInstanceId RUNTIME =
            new RuntimeInstanceId(UUID.fromString("aa57ce1b-ed73-4505-a8a7-67de1e098ee4"));

    @Test
    void delegatesOwnerScopedActivationReadAndRevocationWithoutRepeatingTheToken() {
        HostedAccountResolver accounts = mock(HostedAccountResolver.class);
        ManagedRuntimeService runtimes = mock(ManagedRuntimeService.class);
        Authentication authentication = mock(Authentication.class);
        ManagedRuntimeInstance instance = instance();
        when(accounts.resolve(authentication)).thenReturn(new HostedAccountPrincipal(OWNER));
        when(runtimes.activate(
                OWNER, CATALOG, Optional.of("https://api.example/"), Optional.of(Duration.ofHours(2)), java.util.Map.of()))
                .thenReturn(new RuntimeActivation(
                        instance, "g2s_rt_private-token", URI.create("https://runtime.example/mcp/" + RUNTIME.value())));
        when(runtimes.require(OWNER, RUNTIME)).thenReturn(instance);
        HostedManagedRuntimeController controller = new HostedManagedRuntimeController(
                accounts, runtimes, Clock.fixed(NOW, ZoneOffset.UTC));

        ManagedRuntimeResponse activated = controller.activate(
                authentication,
                CATALOG.toString(),
                new HostedManagedRuntimeController.ActivationRequest("https://api.example/", 7200L, java.util.Map.of()));
        ManagedRuntimeResponse details = controller.runtime(authentication, RUNTIME.value().toString());
        controller.revoke(authentication, RUNTIME.value().toString());

        assertEquals("g2s_rt_private-token", activated.token());
        assertEquals("https://runtime.example/mcp/" + RUNTIME.value(), activated.endpoint());
        assertNull(details.token());
        assertNull(details.endpoint());
        assertEquals("ACTIVE", details.state());
        verify(runtimes).revoke(OWNER, RUNTIME);
    }

    private ManagedRuntimeInstance instance() {
        return new ManagedRuntimeInstance(
                RUNTIME, OWNER, CATALOG, "a".repeat(64), Optional.empty(), NOW, NOW.plusSeconds(7200), Optional.empty());
    }
}
