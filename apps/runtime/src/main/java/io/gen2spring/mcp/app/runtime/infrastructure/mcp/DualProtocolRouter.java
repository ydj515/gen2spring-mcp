package io.gen2spring.mcp.app.runtime.infrastructure.mcp;

import io.gen2spring.mcp.adapter.mcp.ModernMcpProtocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/** Dispatches each request independently after the runtime bearer filter authenticates it. */
public final class DualProtocolRouter {
    private DualProtocolRouter() {}

    public static RouterFunction<ServerResponse> wrap(
            RouterFunction<ServerResponse> legacy, ModernMcpProtocol modern, ObjectMapper json) {
        return wrap(legacy, modern, json, version -> {});
    }

    public static RouterFunction<ServerResponse> wrap(RouterFunction<ServerResponse> legacy,
            ModernMcpProtocol modern, ObjectMapper json, Consumer<String> observedVersion) {
        return wrap(legacy, modern, json, observedVersion, "local");
    }

    public static RouterFunction<ServerResponse> wrap(RouterFunction<ServerResponse> legacy,
            ModernMcpProtocol modern, ObjectMapper json, Consumer<String> observedVersion, String owner) {
        return request -> Optional.of(ignored -> handle(request, legacy, modern, json, observedVersion, owner));
    }

    private static ServerResponse handle(ServerRequest request, RouterFunction<ServerResponse> legacy,
            ModernMcpProtocol modern, ObjectMapper json, Consumer<String> observedVersion, String owner) throws Exception {
        if (!"POST".equals(request.method().name())) {
            if (ModernMcpProtocol.VERSION.equals(request.headers().firstHeader("MCP-Protocol-Version"))) {
                var servlet = request.servletRequest();
                if (!ModernMcpProtocol.allowsOrigin(request.headers().firstHeader("Origin"), servlet.getScheme(),
                        servlet.getServerName(), servlet.getServerPort())) return ServerResponse.status(403).build();
                return ServerResponse.status(405).header("Allow", "POST").build();
            }
            return legacy.route(request).orElseThrow().handle(request);
        }
        byte[] bytes = request.servletRequest().getInputStream().readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) return ServerResponse.status(413).build();
        JsonNode body;
        try {
            body = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(bytes);
        } catch (IOException invalid) {
            return response(modern.error(null, 400, -32700, "Parse error", null));
        }
        if (body == null) return response(modern.error(null, 400, -32600, "Invalid request", null));
        observedVersion.accept(ModernMcpProtocol.observedVersion(body, request.headers().firstHeader("MCP-Protocol-Version")));
        if (ModernMcpProtocol.isModern(body, request.headers().firstHeader("MCP-Protocol-Version"))) {
            var servlet = request.servletRequest();
            if (!ModernMcpProtocol.allowsOrigin(request.headers().firstHeader("Origin"), servlet.getScheme(),
                    servlet.getServerName(), servlet.getServerPort())) return ServerResponse.status(403).build();
            return response(modern.handle(body, request.headers()::firstHeader, owner));
        }
        ServerRequest replay = ServerRequest.create(new HttpServletRequestWrapper(request.servletRequest()) {
            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }

            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException("Synchronous request replay only");
                    }
                };
            }
        }, request.messageConverters());
        return legacy.route(replay).orElseThrow().handle(replay);
    }

    private static ServerResponse response(ModernMcpProtocol.Reply reply) {
        if (reply.stream() != null) {
            return ServerResponse.status(reply.status()).header("Content-Type", "text/event-stream")
                    .header("Cache-Control", "private, no-store")
                    .build((request, response) -> { reply.stream().write(response.getOutputStream()); return null; });
        }
        if (reply.body() == null) return ServerResponse.status(reply.status()).build();
        return ServerResponse.status(reply.status()).contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "private, no-store").body(reply.body());
    }
}
