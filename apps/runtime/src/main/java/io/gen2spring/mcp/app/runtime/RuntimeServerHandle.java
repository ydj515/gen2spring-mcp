package io.gen2spring.mcp.app.runtime;

import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

final class RuntimeServerHandle implements AutoCloseable {
    private final ManagedRuntimeInstance instance;
    private final RouterFunction<ServerResponse> router;
    private final Runnable closeAction;
    private final AtomicBoolean closed = new AtomicBoolean();

    RuntimeServerHandle(
            ManagedRuntimeInstance instance,
            RouterFunction<ServerResponse> router,
            Runnable closeAction) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.router = Objects.requireNonNull(router, "router");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    static RuntimeServerHandle testing(ManagedRuntimeInstance instance, Runnable closeAction) {
        return new RuntimeServerHandle(instance, request -> java.util.Optional.empty(), closeAction);
    }

    ManagedRuntimeInstance instance() {
        return instance;
    }

    RouterFunction<ServerResponse> router() {
        if (closed.get()) throw new IllegalStateException("Managed runtime handle is closed");
        return router;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                closeAction.run();
            } catch (RuntimeException | Error failure) {
                closed.set(false);
                throw failure;
            }
        }
    }
}
