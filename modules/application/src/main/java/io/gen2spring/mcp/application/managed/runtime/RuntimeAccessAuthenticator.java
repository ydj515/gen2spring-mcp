package io.gen2spring.mcp.application.managed.runtime;

import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore.StoredRuntime;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance.RuntimeState;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

public final class RuntimeAccessAuthenticator {
    private static final int MAX_TOKEN_LENGTH = 512;
    private final ManagedRuntimeStore store;
    private final RuntimeTokenCodec tokens;
    private final Clock clock;

    public RuntimeAccessAuthenticator(ManagedRuntimeStore store, RuntimeTokenCodec tokens, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
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
            if (!tokens.matches(bearerToken, stored.tokenDigest())) {
                throw unauthorized();
            }
            return new RuntimeAccess(stored.instance());
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeUnauthorized failure) {
            throw failure;
        } catch (RuntimeException failure) {
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
