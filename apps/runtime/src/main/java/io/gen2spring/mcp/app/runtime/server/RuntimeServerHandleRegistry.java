package io.gen2spring.mcp.app.runtime.server;

import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance.RuntimeState;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class RuntimeServerHandleRegistry implements AutoCloseable {
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

    RuntimeServerHandle get(RuntimeAccess access) {
        List<RuntimeServerHandle> stale;
        RuntimeServerHandle created = null;
        Throwable primary = null;
        synchronized (this) {
            Instant now = clock.instant();
            if (access == null || access.instance().stateAt(now) != RuntimeState.ACTIVE
                    || !now.isBefore(access.validUntil())) {
                throw new IllegalStateException("Managed runtime handle is unavailable");
            }
            HandleKey key = HandleKey.from(access);
            RuntimeServerHandle existing = handles.get(key);
            if (existing != null) return existing;
            stale = removeSupersededCatalogs(key);
            stale.addAll(removeExpired(now));
            if (handles.size() >= maximumSize) {
                primary = new RuntimeCapacityExceeded();
            } else {
                try {
                    created = Objects.requireNonNull(factory.create(access));
                    handles.put(key, created);
                } catch (Error fatal) {
                    primary = fatal;
                } catch (RuntimeException failure) {
                    primary = new IllegalStateException("Managed runtime handle could not be created");
                }
            }
        }
        closeHandlesPreserving(stale, primary);
        return created;
    }

    public void invalidate(RuntimeInstanceId id) {
        List<RuntimeServerHandle> removed = new ArrayList<>();
        synchronized (this) {
            var iterator = handles.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey().runtimeId().equals(id)) {
                    removed.add(entry.getValue());
                    iterator.remove();
                }
            }
        }
        closeHandles(removed);
    }

    @Override
    public void close() {
        List<RuntimeServerHandle> removed;
        synchronized (this) {
            removed = new ArrayList<>(handles.values());
            handles.clear();
        }
        closeHandles(removed);
    }

    synchronized List<String> cachedKeyDescriptions() {
        return handles.keySet().stream().map(HandleKey::toString).toList();
    }

    private void closeHandles(List<RuntimeServerHandle> removed) {
        RuntimeException failure = null;
        for (RuntimeServerHandle handle : removed) {
            try {
                handle.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private void closeHandlesPreserving(List<RuntimeServerHandle> removed, Throwable primary) {
        try {
            closeHandles(removed);
        } catch (Throwable cleanupFailure) {
            if (primary == null) rethrow(cleanupFailure);
            if (cleanupFailure != primary) primary.addSuppressed(cleanupFailure);
        }
        if (primary != null) rethrow(primary);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error fatal) throw fatal;
        throw (RuntimeException) failure;
    }

    private List<RuntimeServerHandle> removeExpired(Instant now) {
        List<RuntimeServerHandle> removed = new ArrayList<>();
        var iterator = handles.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            RuntimeServerHandle handle = entry.getValue();
            if (handle.instance().stateAt(now) != RuntimeState.ACTIVE
                    || !now.isBefore(entry.getKey().validUntil())) {
                removed.add(entry.getValue());
                iterator.remove();
            }
        }
        return removed;
    }

    private List<RuntimeServerHandle> removeSupersededCatalogs(HandleKey requested) {
        List<RuntimeServerHandle> removed = new ArrayList<>();
        var iterator = handles.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            HandleKey current = entry.getKey();
            if (current.runtimeId().equals(requested.runtimeId())
                    && !current.catalogChecksum().equals(requested.catalogChecksum())) {
                removed.add(entry.getValue());
                iterator.remove();
            }
        }
        return removed;
    }

    private record HandleKey(
            RuntimeInstanceId runtimeId,
            String catalogChecksum,
            String policyChecksum,
            Instant validUntil) {
        private static HandleKey from(RuntimeAccess access) {
            return new HandleKey(
                    access.instance().id(), access.instance().catalogChecksum(), access.policyChecksum(),
                    access.validUntil());
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
