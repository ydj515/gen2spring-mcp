package io.gen2spring.mcp.app.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManagedRuntimeIsolationIntegrationTest {
    @Test
    void neverSharesHandlesAcrossRuntimeIds() {
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        AtomicInteger builds = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(access -> {
            builds.incrementAndGet();
            return RuntimeServerHandle.testing(access.instance(), closes::incrementAndGet);
        }, 4, Clock.fixed(now, ZoneOffset.UTC));

        RuntimeAccess first = access(instance(1, now));
        RuntimeAccess second = access(instance(2, now));
        registry.get(first);
        registry.get(second);
        registry.get(first);

        assertEquals(2, builds.get());
        registry.close();
        assertEquals(2, closes.get());
    }

    private RuntimeAccess access(ManagedRuntimeInstance instance) {
        return new RuntimeAccess(
                instance, Optional.empty(), "owner", Set.of("managed_tool"), 600, true,
                "a".repeat(64), instance.expiresAt());
    }

    private ManagedRuntimeInstance instance(int suffix, Instant now) {
        return new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.fromString("10000000-0000-0000-0000-00000000000" + suffix)),
                new AccountId(UUID.fromString("20000000-0000-0000-0000-00000000000" + suffix)),
                UUID.fromString("30000000-0000-0000-0000-00000000000" + suffix),
                Integer.toString(suffix).repeat(64), Optional.empty(),
                now.minusSeconds(1), now.plusSeconds(3600), Optional.empty());
    }
}
