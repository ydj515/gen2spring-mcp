package io.gen2spring.mcp.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class LocalRequestSecurityFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "forwarded",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-port",
            "x-forwarded-proto",
            "x-real-ip");

    private final WebErrorMapper errors;
    private final WebErrorResponseWriter writer;

    LocalRequestSecurityFilter(WebErrorMapper errors, WebErrorResponseWriter writer) {
        this.errors = Objects.requireNonNull(errors, "errors");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            requireLocalRequest(request);
            filterChain.doFilter(request, response);
        } catch (RequestRejectedException rejected) {
            writer.write(response, errors.map(rejected));
        } catch (QueryRejectedException rejected) {
            writer.write(response, errors.map(rejected));
        }
    }

    private void requireLocalRequest(HttpServletRequest request) {
        if (!"127.0.0.1".equals(request.getRemoteAddr())) {
            throw new RequestRejectedException();
        }
        if (request.getQueryString() != null) {
            throw new QueryRejectedException();
        }
        String expectedHost = "127.0.0.1:" + request.getLocalPort();
        requireSingle(request, "Host", expectedHost);
        if (Collections.list(request.getHeaderNames()).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(FORWARDED_HEADERS::contains)) {
            throw new RequestRejectedException();
        }
        if (!SAFE_METHODS.contains(request.getMethod())) {
            requireSingle(request, "Origin", "http://" + expectedHost);
        }
    }

    private void requireSingle(HttpServletRequest request, String name, String expected) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || !expected.equals(values.getFirst())) {
            throw new RequestRejectedException();
        }
    }

    static final class RequestRejectedException extends RuntimeException {
        private RequestRejectedException() {
            super("The local request boundary rejected a request");
        }
    }

    static final class QueryRejectedException extends RuntimeException {
        private QueryRejectedException() {
            super("The local request query boundary rejected a request");
        }
    }
}
