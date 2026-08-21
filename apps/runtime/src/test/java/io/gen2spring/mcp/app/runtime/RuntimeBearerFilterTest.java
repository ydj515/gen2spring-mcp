package io.gen2spring.mcp.app.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccessAuthenticator;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenCodec;
import io.gen2spring.mcp.application.managed.runtime.IssuedRuntimeToken;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RuntimeBearerFilterTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void authenticatesBeforeDelegationAndPublishesOnlyRuntimeAccess() throws Exception {
        ManagedRuntimeInstance instance = instance();
        AtomicInteger lookups = new AtomicInteger();
        ManagedRuntimeStore store = store(instance, lookups);
        RuntimeAccessAuthenticator authenticator = new RuntimeAccessAuthenticator(
                store, tokens(presented -> "valid-token".equals(presented)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RuntimeBearerFilter filter = new RuntimeBearerFilter(authenticator);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/mcp/" + instance.id().value());
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger delegated = new AtomicInteger();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            delegated.incrementAndGet();
            RuntimeAccess access = (RuntimeAccess) servletRequest.getAttribute(RuntimeBearerFilter.RUNTIME_ACCESS);
            assertSame(instance, access.instance());
        });

        assertEquals(1, lookups.get());
        assertEquals(1, delegated.get());
        assertNull(request.getSession(false));
    }

    @Test
    void returnsEquivalentFixedUnauthorizedResponsesWithoutCacheDelegation() throws Exception {
        ManagedRuntimeInstance instance = instance();
        RuntimeAccessAuthenticator authenticator = new RuntimeAccessAuthenticator(
                store(instance, new AtomicInteger()), tokens(presented -> false),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RuntimeBearerFilter filter = new RuntimeBearerFilter(authenticator);

        for (String header : new String[] {null, "Basic private", "Bearer wrong"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "POST", "/mcp/" + instance.id().value());
            if (header != null) request.addHeader("Authorization", header);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicInteger delegated = new AtomicInteger();
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> delegated.incrementAndGet());

            assertEquals(401, response.getStatus());
            assertEquals("{\"code\":\"UNAUTHORIZED\",\"message\":\"Managed runtime authentication failed\"}",
                    response.getContentAsString());
            assertEquals(0, delegated.get());
        }
    }

    private ManagedRuntimeStore store(ManagedRuntimeInstance instance, AtomicInteger lookups) {
        return new ManagedRuntimeStore() {
            @Override public void create(ManagedRuntimeInstance ignored, RuntimeTokenDigest digest) {}
            @Override public Optional<StoredRuntime> find(RuntimeInstanceId id) {
                lookups.incrementAndGet();
                return id.equals(instance.id())
                        ? Optional.of(new StoredRuntime(instance, new RuntimeTokenDigest(new byte[32])))
                        : Optional.empty();
            }
            @Override public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant at) { return false; }
        };
    }

    private RuntimeTokenCodec tokens(java.util.function.Predicate<String> matches) {
        return new RuntimeTokenCodec() {
            @Override public IssuedRuntimeToken issue() { throw new UnsupportedOperationException(); }
            @Override public boolean matches(String presented, RuntimeTokenDigest digest) {
                return matches.test(presented);
            }
        };
    }

    private ManagedRuntimeInstance instance() {
        return new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                new AccountId(UUID.fromString("20000000-0000-0000-0000-000000000001")),
                UUID.fromString("30000000-0000-0000-0000-000000000001"), "a".repeat(64),
                Optional.empty(), NOW.minusSeconds(1), NOW.plusSeconds(3600), Optional.empty());
    }
}
