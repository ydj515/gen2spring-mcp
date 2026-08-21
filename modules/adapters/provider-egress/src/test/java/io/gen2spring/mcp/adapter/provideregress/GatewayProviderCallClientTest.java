package io.gen2spring.mcp.adapter.provideregress;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.POST;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.gen2spring.mcp.application.managed.execution.ProviderCallClient;
import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GatewayProviderCallClientTest {
    @Test
    void sendsTheExactBoundedWireContractAndDecodesPreciseResponseBytes() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"status\":200,\"headers\":{\"Content-Type\":[\"application/json\"]},"
                    + "\"body\":\"eyJ2YWx1ZSI6MC4xMDAwMDAwMDAwMDAwMDAwMX0=\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            GatewayProviderCallClient client = new GatewayProviderCallClient(
                    endpoint(server), HttpClient.newHttpClient());
            ProviderCallRequest request = new ProviderCallRequest(
                    POST,
                    URI.create("https://api.example/items?q=a%20b"),
                    Map.of("X-Request-Id", List.of("request-1")),
                    "{\"amount\":0.10000000000000001}".getBytes(StandardCharsets.UTF_8));

            var response = client.execute(request, Duration.ofSeconds(2));

            assertEquals(200, response.status());
            assertEquals("application/json", response.firstHeader("content-type"));
            assertArrayEquals(
                    "{\"value\":0.10000000000000001}".getBytes(StandardCharsets.UTF_8), response.body());
            assertEquals(
                    "{\"method\":\"POST\",\"uri\":\"https://api.example/items?q=a%20b\","
                            + "\"headers\":{\"X-Request-Id\":[\"request-1\"]},"
                            + "\"body\":\"eyJhbW91bnQiOjAuMTAwMDAwMDAwMDAwMDAwMDF9\",\"timeoutMillis\":2000}",
                    captured.get());
            for (String forbidden : List.of("runtimeId", "catalogId", "account", "g2s_rt_")) {
                assertFalse(captured.get().contains(forbidden));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsGatewayFailuresMalformedDuplicatesAndOversizedBodiesSafely() throws Exception {
        assertFailure(exchange -> respond(exchange, 503, "application/json", "{}"),
                ProviderCallClient.ProviderCallFailure.Kind.UNAVAILABLE);
        assertFailure(exchange -> respond(exchange, 429, "application/json", "{}"),
                ProviderCallClient.ProviderCallFailure.Kind.UNAVAILABLE);
        assertFailure(exchange -> respond(exchange, 400, "application/json", "{}"),
                ProviderCallClient.ProviderCallFailure.Kind.PROTOCOL);
        assertFailure(exchange -> respond(exchange, 200, "text/plain", "{}"),
                ProviderCallClient.ProviderCallFailure.Kind.PROTOCOL);
        assertFailure(exchange -> respond(exchange, 200, "application/json",
                        "{\"status\":200,\"status\":201,\"headers\":{},\"body\":\"\"}"),
                ProviderCallClient.ProviderCallFailure.Kind.PROTOCOL);
        assertFailure(exchange -> respond(exchange, 200, "application/json",
                        "x".repeat(ProviderEgressCodec.MAX_WIRE_BYTES)),
                ProviderCallClient.ProviderCallFailure.Kind.PROTOCOL);
    }

    @Test
    void encodesTheLargestValidProviderBodyWithinTheWireBound() {
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 64; index++) {
            headers.put("X-Bounded-" + index, List.of("v".repeat(8192)));
        }
        byte[] wire = new ProviderEgressCodec().encodeRequest(new ProviderCallRequest(
                POST, URI.create("https://api.example/items"), headers, new byte[1_048_576]),
                Duration.ofSeconds(2));

        assertTrue(wire.length <= ProviderEgressCodec.MAX_WIRE_BYTES);
    }

    @Test
    void mapsConnectionFailuresToOneUnavailableFailure() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, "application/json", "{}"));
        URI tlsEndpoint = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/internal/provider-call");
        server.stop(0);
        try {
            GatewayProviderCallClient client = new GatewayProviderCallClient(tlsEndpoint, HttpClient.newHttpClient());
            ProviderCallClient.ProviderCallFailure failure = assertThrows(
                    ProviderCallClient.ProviderCallFailure.class,
                    () -> client.execute(request(), Duration.ofSeconds(2)));
            assertEquals(ProviderCallClient.ProviderCallFailure.Kind.UNAVAILABLE, failure.kind());
        } finally {
            server.stop(0);
        }
    }

    private ProviderCallRequest request() {
        return new ProviderCallRequest(POST, URI.create("https://api.example/items"), Map.of(), new byte[0]);
    }

    private URI endpoint(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/internal/provider-call");
    }

    private HttpServer server(Responder responder) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/provider-call", exchange -> {
            try {
                responder.respond(exchange);
            } catch (Exception failure) {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private void assertFailure(Responder responder, ProviderCallClient.ProviderCallFailure.Kind expected)
            throws Exception {
        HttpServer server = server(responder);
        try {
            GatewayProviderCallClient client = new GatewayProviderCallClient(
                    endpoint(server), HttpClient.newHttpClient());
            ProviderCallClient.ProviderCallFailure failure = assertThrows(
                    ProviderCallClient.ProviderCallFailure.class,
                    () -> client.execute(request(), Duration.ofSeconds(2)));
            assertEquals(expected, failure.kind());
            assertFalse(failure.toString().contains(endpoint(server).toString()));
        } finally {
            server.stop(0);
        }
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, int status, String contentType, String body)
            throws Exception {
        exchange.getRequestBody().readAllBytes();
        byte[] value = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, value.length);
        exchange.getResponseBody().write(value);
        exchange.close();
    }

    @FunctionalInterface
    private interface Responder {
        void respond(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
    }
}
