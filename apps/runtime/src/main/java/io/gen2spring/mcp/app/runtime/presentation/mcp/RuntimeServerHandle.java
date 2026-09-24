package io.gen2spring.mcp.app.runtime.presentation.mcp;

import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

public final class RuntimeServerHandle implements AutoCloseable {
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
        return new RuntimeServerHandle(instance, request -> Optional.empty(), closeAction);
    }

    public static RuntimeServerHandle stateless(
            ManagedRuntimeInstance instance,
            WebMvcStatelessServerTransport transport,
            McpStatelessSyncServer server) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(server, "server");
        return new RuntimeServerHandle(instance, transport.getRouterFunction(), () -> {
            try {
                server.close();
            } finally {
                transport.closeGracefully().block(Duration.ofSeconds(5));
            }
        });
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
            closeAction.run();
        }
    }
}
