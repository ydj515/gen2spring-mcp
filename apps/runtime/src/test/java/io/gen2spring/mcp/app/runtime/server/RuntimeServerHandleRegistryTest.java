package io.gen2spring.mcp.app.runtime.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import io.gen2spring.mcp.app.runtime.security.RuntimeBearerFilter;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
        RuntimeAccess first = access(instance(1), "a".repeat(64), "owner");

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
                () -> registry.get(access(instance(2), "a".repeat(64), "owner")));
        assertSame(registry.get(access(instance(1), "a".repeat(64), "owner")), registry.get(first));
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
        registry.get(access(instance(1), "a".repeat(64), "owner"));
        RuntimeAccess second = access(instance(2), "a".repeat(64), "owner");

        MockMvcBuilders
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
    void mapsAClosedCachedHandleToTheFixedServiceUnavailableResponse() throws Exception {
        AtomicReference<RuntimeServerHandle> created = new AtomicReference<>();
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(access -> {
            RuntimeServerHandle handle = RuntimeServerHandle.testing(access.instance(), () -> {});
            created.set(handle);
            return handle;
        }, 1, Clock.fixed(NOW, ZoneOffset.UTC));
        RuntimeAccess access = access(instance(1), "a".repeat(64), "owner");
        registry.get(access);
        created.get().close();

        MockMvcBuilders
                .routerFunctions(new ManagedMcpRouter(registry))
                .build()
                .perform(get("/mcp/" + access.instance().id().value())
                        .requestAttr(RuntimeBearerFilter.RUNTIME_ACCESS, access))
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

        assertThrows(IllegalStateException.class,
                () -> registry.get(access(instance(1), "a".repeat(64), "owner")));
        registry.get(access(instance(1), "a".repeat(64), "owner"));
        assertEquals(2, attempts.get());

        ManagedRuntimeInstance expired = new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.randomUUID()), instance(1).owner(), UUID.randomUUID(), "b".repeat(64),
                Optional.empty(), NOW.minusSeconds(20), NOW.minusSeconds(1), Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> registry.get(access(expired, "a".repeat(64), "owner")));
    }

    @Test
    void removesExpiredHandlesEvenWhenCleanupFails() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger builds = new AtomicInteger();
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(access -> {
            builds.incrementAndGet();
            return RuntimeServerHandle.testing(access.instance(), () -> {
                throw new IllegalStateException("private close failure");
            });
        }, 1, clock);
        registry.get(access(instance(1, NOW.plusSeconds(1)), "a".repeat(64), "owner"));
        clock.now = NOW.plusSeconds(2);
        RuntimeAccess second = access(instance(2, NOW.plusSeconds(3600)), "a".repeat(64), "owner");

        assertThrows(IllegalStateException.class, () -> registry.get(second));
        registry.get(second);
        assertEquals(2, builds.get());
    }

    @Test
    void evictsScopedHandlesWhenTheirGrantPolicyExpiresBeforeTheRuntime() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger closes = new AtomicInteger();
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(
                access -> RuntimeServerHandle.testing(access.instance(), closes::incrementAndGet),
                1, clock);
        ManagedRuntimeInstance runtime = instance(1, NOW.plusSeconds(3600));
        RuntimeAccess scoped = access(
                runtime, "a".repeat(64), "client-a", NOW.plusSeconds(1));

        registry.get(scoped);
        clock.now = NOW.plusSeconds(2);
        registry.get(access(instance(2), "b".repeat(64), "owner"));

        assertEquals(1, closes.get());
        registry.close();
    }

    @Test
    void closesHandlesOutsideTheRegistryMonitorAndKeepsFailedHandlesClosed() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(access ->
                RuntimeServerHandle.testing(access.instance(), access.instance().id().equals(instance(1).id()) ? () -> {
                    closeEntered.countDown();
                    try {
                        if (!releaseClose.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted");
                    }
                    throw new IllegalStateException("private close failure");
                } : () -> {}), 2, Clock.fixed(NOW, ZoneOffset.UTC));
        RuntimeAccess first = access(instance(1), "a".repeat(64), "owner");
        RuntimeAccess second = access(instance(2), "b".repeat(64), "owner");
        RuntimeServerHandle firstHandle = registry.get(first);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var invalidation = pool.submit(() -> registry.invalidate(first.instance().id()));
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));
            var lookup = pool.submit(() -> registry.get(second));
            assertSame(lookup.get(1, TimeUnit.SECONDS), registry.get(second));
            releaseClose.countDown();
            assertThrows(ExecutionException.class,
                    () -> invalidation.get(1, TimeUnit.SECONDS));
        } finally {
            releaseClose.countDown();
        }

        assertThrows(IllegalStateException.class, firstHandle::router);
        registry.close();
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
        String cached = registry.cachedKeyDescriptions().toString();
        assertEquals(false, cached.contains("owner-private-token"));
        assertEquals(false, cached.contains("credential-private-marker"));
        assertEquals(false, cached.contains("client-a"));
        registry.close();
    }

    private RuntimeAccess access(ManagedRuntimeInstance instance, String policyChecksum, String principal) {
        return access(instance, policyChecksum, principal, instance.expiresAt());
    }

    private RuntimeAccess access(
            ManagedRuntimeInstance instance,
            String policyChecksum,
            String principal,
            Instant validUntil) {
        return new RuntimeAccess(
                instance,
                "owner".equals(principal) ? Optional.empty() : Optional.of(
                        new RuntimeGrantId(UUID.randomUUID())),
                principal, Set.of("managed_tool"), 5, "owner".equals(principal), policyChecksum, validUntil);
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
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
