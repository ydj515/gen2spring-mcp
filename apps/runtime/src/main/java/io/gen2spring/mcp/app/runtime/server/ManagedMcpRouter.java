package io.gen2spring.mcp.app.runtime.server;

import io.gen2spring.mcp.app.runtime.security.RuntimeBearerFilter;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

final class ManagedMcpRouter implements RouterFunction<ServerResponse> {
    private final RuntimeServerHandleRegistry handles;

    ManagedMcpRouter(RuntimeServerHandleRegistry handles) {
        this.handles = Objects.requireNonNull(handles, "handles");
    }

    @Override
    public Optional<HandlerFunction<ServerResponse>> route(ServerRequest request) {
        Object value = request.servletRequest().getAttribute(RuntimeBearerFilter.RUNTIME_ACCESS);
        if (!(value instanceof RuntimeAccess access)
                || !request.path().equals("/mcp/" + access.instance().id().value())) {
            return Optional.empty();
        }
        try {
            return handles.get(access).router().route(request);
        } catch (RuntimeServerHandleRegistry.RuntimeCapacityExceeded exhausted) {
            return Optional.of(ignored -> ServerResponse.status(503)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"MANAGED_RUNTIME_CAPACITY_EXHAUSTED\"}"));
        }
    }
}
