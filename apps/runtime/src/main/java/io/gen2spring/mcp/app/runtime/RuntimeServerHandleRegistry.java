package io.gen2spring.mcp.app.runtime;

import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance.RuntimeState;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Objects;

final class RuntimeServerHandleRegistry implements AutoCloseable {
    private final HandleFactory factory;
    private final int maximumSize;
    private final Clock clock;
    private final LinkedHashMap<HandleKey, RuntimeServerHandle> handles = new LinkedHashMap<>();

    RuntimeServerHandleRegistry(HandleFactory factory, int maximumSize, Clock clock) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumSize < 1 || maximumSize > 10_000) {
            throw new IllegalArgumentException("Managed runtime cache configuration is invalid");
        }
        this.maximumSize = maximumSize;
    }

    synchronized RuntimeServerHandle get(RuntimeAccess access) {
        if (access == null || access.instance().stateAt(clock.instant()) != RuntimeState.ACTIVE) {
            throw new IllegalStateException("Managed runtime handle is unavailable");
        }
        HandleKey key = HandleKey.from(access);
        RuntimeServerHandle existing = handles.get(key);
        if (existing != null) return existing;
        removeSupersededCatalogs(key);
        removeExpired();
        if (handles.size() >= maximumSize) {
            throw new RuntimeCapacityExceeded();
        }
        RuntimeServerHandle created;
        try {
            created = Objects.requireNonNull(factory.create(access));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Managed runtime handle could not be created");
        }
        handles.put(key, created);
        return created;
    }

    synchronized void invalidate(RuntimeInstanceId id) {
        var iterator = handles.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().runtimeId().equals(id)) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    @Override
    public synchronized void close() {
        RuntimeException failure = null;
        for (RuntimeServerHandle handle : handles.values()) {
            try {
                handle.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        handles.clear();
        if (failure != null) throw failure;
    }

    private void removeExpired() {
        var iterator = handles.entrySet().iterator();
        while (iterator.hasNext()) {
            RuntimeServerHandle handle = iterator.next().getValue();
            if (handle.instance().stateAt(clock.instant()) != RuntimeState.ACTIVE) {
                handle.close();
                iterator.remove();
            }
        }
    }

    private void removeSupersededCatalogs(HandleKey requested) {
        var iterator = handles.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            HandleKey current = entry.getKey();
            if (current.runtimeId().equals(requested.runtimeId())
                    && !current.catalogChecksum().equals(requested.catalogChecksum())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    private record HandleKey(RuntimeInstanceId runtimeId, String catalogChecksum, String policyChecksum) {
        private static HandleKey from(RuntimeAccess access) {
            return new HandleKey(
                    access.instance().id(), access.instance().catalogChecksum(), access.policyChecksum());
        }

        @Override public String toString() {
            return "HandleKey[runtimeId=" + runtimeId + ", catalogChecksum=" + catalogChecksum
                    + ", policyChecksum=" + policyChecksum + "]";
        }
    }

    static final class RuntimeCapacityExceeded extends RuntimeException {
        private RuntimeCapacityExceeded() {
            super("Managed runtime capacity is exhausted", null, false, false);
        }
    }

    @FunctionalInterface
    interface HandleFactory {
        RuntimeServerHandle create(RuntimeAccess access);
    }
}
