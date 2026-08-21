package io.gen2spring.mcp.app.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeServerHandleRegistryTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void buildsOneIsolatedHandlePerRuntimeUnderConcurrencyAndClosesOnEviction() throws Exception {
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

        RuntimeServerHandle second = registry.get(new RuntimeAccess(instance(2)));
        assertNotSame(registry.get(new RuntimeAccess(instance(1))), second);
        assertEquals(2, closes.get());
        registry.close();
        assertEquals(3, closes.get());
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

    private ManagedRuntimeInstance instance(int suffix) {
        return new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.fromString("10000000-0000-0000-0000-00000000000" + suffix)),
                new AccountId(UUID.fromString("20000000-0000-0000-0000-00000000000" + suffix)),
                UUID.fromString("30000000-0000-0000-0000-00000000000" + suffix),
                Integer.toHexString(suffix).repeat(64), Optional.empty(),
                NOW.minusSeconds(1), NOW.plusSeconds(3600), Optional.empty());
    }
}
