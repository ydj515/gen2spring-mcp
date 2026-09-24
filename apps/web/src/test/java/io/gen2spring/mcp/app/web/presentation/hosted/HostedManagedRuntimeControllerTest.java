package io.gen2spring.mcp.app.web.presentation.hosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.app.web.presentation.security.HostedAccountPrincipal;
import io.gen2spring.mcp.app.web.presentation.security.HostedAccountResolver;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeMigrationService;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeMigrationService.MigrationResult;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeService;
import io.gen2spring.mcp.application.managed.runtime.RuntimeActivation;
import io.gen2spring.mcp.application.managed.runtime.port.out.RuntimeCatalogTransitionStore.RuntimeCatalogTransition;
import io.gen2spring.mcp.application.managed.runtime.port.out.RuntimeCatalogTransitionStore.TransitionKind;
import io.gen2spring.mcp.application.managed.runtime.port.out.RuntimeCatalogTransitionStore.TransitionPage;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
        ManagedRuntimeMigrationService migrations = mock(ManagedRuntimeMigrationService.class);
        Authentication authentication = mock(Authentication.class);
        ManagedRuntimeInstance instance = instance();
        when(accounts.resolve(authentication)).thenReturn(new HostedAccountPrincipal(OWNER));
        when(runtimes.activate(
                OWNER, CATALOG, Optional.of("https://api.example/"), Optional.of(Duration.ofHours(2)), Map.of()))
                .thenReturn(new RuntimeActivation(
                        instance, "g2s_rt_private-token", URI.create("https://runtime.example/mcp/" + RUNTIME.value())));
        when(runtimes.require(OWNER, RUNTIME)).thenReturn(instance);
        HostedManagedRuntimeController controller = new HostedManagedRuntimeController(
                accounts, runtimes, migrations, Clock.fixed(NOW, ZoneOffset.UTC));

        ManagedRuntimeResponse activated = controller.activate(
                authentication,
                CATALOG.toString(),
                new HostedManagedRuntimeController.ActivationRequest("https://api.example/", 7200L, Map.of()));
        ManagedRuntimeResponse details = controller.runtime(authentication, RUNTIME.value().toString());
        controller.revoke(authentication, RUNTIME.value().toString());

        assertEquals("g2s_rt_private-token", activated.token());
        assertEquals("https://runtime.example/mcp/" + RUNTIME.value(), activated.endpoint());
        assertNull(details.token());
        assertNull(details.endpoint());
        assertEquals("ACTIVE", details.state());
        verify(runtimes).revoke(OWNER, RUNTIME);
    }

    @Test
    void delegatesMigrationHistoryAndRollbackWithoutReturningRuntimeTokens() {
        HostedAccountResolver accounts = mock(HostedAccountResolver.class);
        ManagedRuntimeService runtimes = mock(ManagedRuntimeService.class);
        ManagedRuntimeMigrationService migrations = mock(ManagedRuntimeMigrationService.class);
        Authentication authentication = mock(Authentication.class);
        UUID target = UUID.fromString("8f5a48fd-f34b-4ba5-b749-eb79008370d5");
        ManagedRuntimeInstance migrated = new ManagedRuntimeInstance(
                RUNTIME, OWNER, target, "b".repeat(64), Optional.empty(),
                NOW, NOW.plusSeconds(7200), Optional.empty());
        RuntimeCatalogTransition transition = new RuntimeCatalogTransition(
                1, RUNTIME, CATALOG, "a".repeat(64), target, "b".repeat(64),
                "d".repeat(64), TransitionKind.MIGRATION, NOW);
        MigrationResult result = new MigrationResult(migrated, transition, "d".repeat(64));
        when(accounts.resolve(authentication)).thenReturn(new HostedAccountPrincipal(OWNER));
        when(migrations.migrate(OWNER, RUNTIME, CATALOG, target, "b".repeat(64))).thenReturn(result);
        when(migrations.history(OWNER, RUNTIME, 20, Optional.of(8L)))
                .thenReturn(new TransitionPage(List.of(transition), Optional.empty()));
        when(migrations.rollback(OWNER, RUNTIME, target)).thenReturn(result);
        HostedManagedRuntimeController controller = new HostedManagedRuntimeController(
                accounts, runtimes, migrations, Clock.fixed(NOW, ZoneOffset.UTC));

        var migration = controller.migrate(authentication, RUNTIME.value().toString(),
                new HostedManagedRuntimeController.MigrationRequest(
                        CATALOG.toString(), target.toString(), "b".repeat(64)));
        var history = controller.migrations(authentication, RUNTIME.value().toString(), 20, 8L);
        var rollback = controller.rollback(authentication, RUNTIME.value().toString(),
                new HostedManagedRuntimeController.RollbackRequest(target.toString()));

        assertEquals(target.toString(), migration.catalogId());
        assertNull(migration.token());
        assertEquals(List.of(1L), history.items().stream().map(item -> item.sequence()).toList());
        assertNull(rollback.token());
    }

    private ManagedRuntimeInstance instance() {
        return new ManagedRuntimeInstance(
                RUNTIME, OWNER, CATALOG, "a".repeat(64), Optional.empty(), NOW, NOW.plusSeconds(7200), Optional.empty());
    }
}
