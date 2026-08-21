package io.gen2spring.mcp.app.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeServerHandleRegistryTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void buildsOneIsolatedHandlePerRuntimeAndRejectsCapacityWithoutClosingLiveSessions() throws Exception {
        AtomicInteger builds = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(
                access -> {
                    builds.incrementAndGet();
                    return RuntimeServerHandle.testing(access.instance(), closes::incrementAndGet);
                }, 1, Clock.fixed(NOW, ZoneOffset.UTC));
        RuntimeAccess first = new RuntimeAccess(instance(1));

        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<RuntimeServerHandle>> calls = new ArrayList<>();
            for (int index = 0; index < 20; index++) calls.add(() -> registry.get(first));
            List<RuntimeServerHandle> handles = pool.invokeAll(calls).stream()
                    .map(future -> {
                        try { return future.get(); } catch (Exception failure) { throw new RuntimeException(failure); }
                    }).toList();
            handles.forEach(handle -> assertSame(handles.getFirst(), handle));
        }
        assertEquals(1, builds.get());

        assertThrows(RuntimeServerHandleRegistry.RuntimeCapacityExceeded.class,
                () -> registry.get(new RuntimeAccess(instance(2))));
        assertSame(registry.get(new RuntimeAccess(instance(1))), registry.get(first));
        assertEquals(0, closes.get());
        registry.close();
        assertEquals(1, closes.get());
    }

    @Test
    void mapsCapacityExhaustionToOneFixedServiceUnavailableResponse() throws Exception {
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(
                access -> RuntimeServerHandle.testing(access.instance(), () -> {}),
                1,
                Clock.fixed(NOW, ZoneOffset.UTC));
        registry.get(new RuntimeAccess(instance(1)));
        RuntimeAccess second = new RuntimeAccess(instance(2));

        org.springframework.test.web.servlet.setup.MockMvcBuilders
                .routerFunctions(new ManagedMcpRouter(registry))
                .build()
                .perform(get("/mcp/" + second.instance().id().value())
                        .requestAttr(RuntimeBearerFilter.RUNTIME_ACCESS, second))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType("application/json"))
                .andExpect(content().json("{\"error\":\"MANAGED_RUNTIME_CAPACITY_EXHAUSTED\"}", true));
        registry.close();
    }

    @Test
    void rejectsExpiredAccessAndEvictsFailedBuilds() {
        AtomicInteger attempts = new AtomicInteger();
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(access -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("private");
            return RuntimeServerHandle.testing(access.instance(), () -> {});
        }, 2, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, () -> registry.get(new RuntimeAccess(instance(1))));
        registry.get(new RuntimeAccess(instance(1)));
        assertEquals(2, attempts.get());

        ManagedRuntimeInstance expired = new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.randomUUID()), instance(1).owner(), UUID.randomUUID(), "b".repeat(64),
                Optional.empty(), NOW.minusSeconds(20), NOW.minusSeconds(1), Optional.empty());
        assertThrows(IllegalStateException.class, () -> registry.get(new RuntimeAccess(expired)));
    }

    @Test
    void keepsExpiredHandlesTrackedWhenCleanupFails() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger builds = new AtomicInteger();
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(access -> {
            builds.incrementAndGet();
            return RuntimeServerHandle.testing(access.instance(), () -> {
                throw new IllegalStateException("private close failure");
            });
        }, 1, clock);
        registry.get(new RuntimeAccess(instance(1, NOW.plusSeconds(1))));
        clock.now = NOW.plusSeconds(2);
        RuntimeAccess second = new RuntimeAccess(instance(2, NOW.plusSeconds(3600)));

        assertThrows(IllegalStateException.class, () -> registry.get(second));
        assertThrows(IllegalStateException.class, () -> registry.get(second));
        assertEquals(1, builds.get());
    }

    @Test
    void separatesHandlesByCatalogAndGrantPolicyWithoutCachingBearerMaterial() {
        AtomicInteger builds = new AtomicInteger();
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(access -> {
            builds.incrementAndGet();
            return RuntimeServerHandle.testing(access.instance(), () -> {});
        }, 4, Clock.fixed(NOW, ZoneOffset.UTC));
        ManagedRuntimeInstance runtime = instance(1);
        RuntimeAccess owner = access(runtime, "a".repeat(64), "owner");
        RuntimeAccess scoped = access(runtime, "b".repeat(64), "client-a");

        assertSame(registry.get(owner), registry.get(owner));
        registry.get(scoped);

        assertEquals(2, builds.get());
        assertEquals(false, registry.toString().contains("owner-private-token"));
        assertEquals(false, registry.toString().contains("credential-private-marker"));
        registry.close();
    }

    private RuntimeAccess access(ManagedRuntimeInstance instance, String policyChecksum, String principal) {
        return new RuntimeAccess(
                instance,
                "owner".equals(principal) ? Optional.empty() : Optional.of(
                        new io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId(UUID.randomUUID())),
                principal, Set.of("managed_tool"), 5, "owner".equals(principal), policyChecksum);
    }

    private ManagedRuntimeInstance instance(int suffix) {
        return instance(suffix, NOW.plusSeconds(3600));
    }

    private ManagedRuntimeInstance instance(int suffix, Instant expiresAt) {
        return new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.fromString("10000000-0000-0000-0000-00000000000" + suffix)),
                new AccountId(UUID.fromString("20000000-0000-0000-0000-00000000000" + suffix)),
                UUID.fromString("30000000-0000-0000-0000-00000000000" + suffix),
                Integer.toHexString(suffix).repeat(64), Optional.empty(),
                NOW.minusSeconds(1), expiresAt, Optional.empty());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
