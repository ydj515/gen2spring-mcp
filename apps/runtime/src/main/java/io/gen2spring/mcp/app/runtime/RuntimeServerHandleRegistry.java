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
    private final LinkedHashMap<RuntimeInstanceId, RuntimeServerHandle> handles = new LinkedHashMap<>();

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
        RuntimeInstanceId id = access.instance().id();
        RuntimeServerHandle existing = handles.get(id);
        if (existing != null) {
            if (!existing.instance().catalogChecksum().equals(access.instance().catalogChecksum())) {
                handles.remove(id);
                existing.close();
            } else {
                return existing;
            }
        }
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
        handles.put(id, created);
        return created;
    }

    synchronized void invalidate(RuntimeInstanceId id) {
        RuntimeServerHandle removed = handles.remove(id);
        if (removed != null) removed.close();
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
