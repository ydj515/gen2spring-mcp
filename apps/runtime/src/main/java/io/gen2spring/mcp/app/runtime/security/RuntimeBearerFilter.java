package io.gen2spring.mcp.app.runtime.security;

import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccessAuthenticator;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RuntimeBearerFilter extends OncePerRequestFilter {
    public static final String RUNTIME_ACCESS = RuntimeBearerFilter.class.getName() + ".access";
    private static final byte[] UNAUTHORIZED = ("{\"code\":\"UNAUTHORIZED\","
            + "\"message\":\"Managed runtime authentication failed\"}").getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNAVAILABLE = ("{\"code\":\"RUNTIME_UNAVAILABLE\","
            + "\"message\":\"Managed runtime is unavailable\"}").getBytes(StandardCharsets.UTF_8);
    private final RuntimeAccessAuthenticator authenticator;
    private final Consumer<RuntimeInstanceId> inactiveRuntime;

    public RuntimeBearerFilter(RuntimeAccessAuthenticator authenticator) {
        this(authenticator, ignored -> {});
    }

    public RuntimeBearerFilter(
            RuntimeAccessAuthenticator authenticator,
            Consumer<RuntimeInstanceId> inactiveRuntime) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.inactiveRuntime = Objects.requireNonNull(inactiveRuntime, "inactiveRuntime");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RuntimeAccess access;
        RuntimeInstanceId id = null;
        try {
            id = runtimeId(request.getRequestURI());
            List<String> authorization = java.util.Collections.list(request.getHeaders("Authorization"));
            if (authorization.size() != 1 || !authorization.getFirst().startsWith("Bearer ")) {
                throw new RuntimeAccessAuthenticator.RuntimeUnauthorized();
            }
            String token = authorization.getFirst().substring("Bearer ".length());
            access = authenticator.authenticate(id, token);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeAccessAuthenticator.RuntimeInactive failure) {
            try {
                inactiveRuntime.accept(id);
            } catch (Error fatal) {
                throw fatal;
            } catch (RuntimeException cleanupFailure) {
                respond(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, UNAVAILABLE);
                return;
            }
            respond(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED);
            return;
        } catch (RuntimeAccessAuthenticator.RuntimeAccessUnavailable failure) {
            respond(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, UNAVAILABLE);
            return;
        } catch (RuntimeException failure) {
            respond(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED);
            return;
        }
        request.setAttribute(RUNTIME_ACCESS, access);
        chain.doFilter(request, response);
    }

    private void respond(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.reset();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private RuntimeInstanceId runtimeId(String path) {
        String value = path.substring("/mcp/".length());
        if (value.contains("/")) throw new RuntimeAccessAuthenticator.RuntimeUnauthorized();
        try {
            return RuntimeInstanceId.parse(value);
        } catch (RuntimeException failure) {
            throw new RuntimeAccessAuthenticator.RuntimeUnauthorized();
        }
    }
}
