package io.gen2spring.mcp.app.runtime;

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
import org.springframework.web.filter.OncePerRequestFilter;

final class RuntimeBearerFilter extends OncePerRequestFilter {
    static final String RUNTIME_ACCESS = RuntimeBearerFilter.class.getName() + ".access";
    private static final byte[] UNAUTHORIZED = ("{\"code\":\"UNAUTHORIZED\","
            + "\"message\":\"Managed runtime authentication failed\"}").getBytes(StandardCharsets.UTF_8);
    private final RuntimeAccessAuthenticator authenticator;

    RuntimeBearerFilter(RuntimeAccessAuthenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            RuntimeInstanceId id = runtimeId(request.getRequestURI());
            List<String> authorization = java.util.Collections.list(request.getHeaders("Authorization"));
            if (authorization.size() != 1 || !authorization.getFirst().startsWith("Bearer ")) {
                throw new RuntimeAccessAuthenticator.RuntimeUnauthorized();
            }
            String token = authorization.getFirst().substring("Bearer ".length());
            RuntimeAccess access = authenticator.authenticate(id, token);
            request.setAttribute(RUNTIME_ACCESS, access);
            chain.doFilter(request, response);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            response.reset();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentLength(UNAUTHORIZED.length);
            response.getOutputStream().write(UNAUTHORIZED);
        }
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
