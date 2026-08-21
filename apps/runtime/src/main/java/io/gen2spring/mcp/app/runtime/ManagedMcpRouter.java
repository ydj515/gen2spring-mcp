package io.gen2spring.mcp.app.runtime;

import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import java.util.Objects;
import java.util.Optional;
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
        return handles.get(access).router().route(request);
    }
}
