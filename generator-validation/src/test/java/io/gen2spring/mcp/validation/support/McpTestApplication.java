package io.gen2spring.mcp.validation.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class McpTestApplication {
    private McpTestApplication() {}

    public static void main(String[] args) throws Exception {
        int port = argument(args, "--server.port=");
        String behavior = Files.exists(Path.of("test-behavior"))
                ? Files.readString(Path.of("test-behavior")).trim()
                : "";
        Files.writeString(Path.of("app.pid"), Long.toString(ProcessHandle.current().pid()));
        Files.writeString(Path.of("app.args"), String.join("\n", args));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/mcp", exchange -> handle(exchange, behavior, server.getAddress().getPort()));
        server.start();
        System.out.println("Tomcat started on port " + server.getAddress().getPort()
                + " (http) with context path '/'");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        Thread.sleep(60_000);
    }

    private static int argument(String[] args, String prefix) {
        for (String argument : args) {
            if (argument.startsWith(prefix)) {
                return Integer.parseInt(argument.substring(prefix.length()));
            }
        }
        throw new IllegalArgumentException("server port is required");
    }

    private static void handle(HttpExchange exchange, String behavior, int port) throws java.io.IOException {
        try (exchange) {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.contains("\"method\":\"initialize\"")) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Mcp-Session-Id", "application-session");
                send(exchange, 200,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\","
                                + "\"capabilities\":{\"tools\":{\"listChanged\":false}},"
                                + "\"serverInfo\":{\"name\":\"test-server\",\"version\":\"1.0.0\"}}}");
            } else if (request.contains("notifications/initialized")) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, -1);
            } else if (request.contains("\"method\":\"tools/list\"")) {
                if (!"application-session".equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                send(exchange, 200, "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{"
                        + "\"name\":\"kma_weather_get_forecast\","
                        + "\"description\":\"Get the public weather forecast for a grid location.\","
                        + "\"inputSchema\":{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                        + "\"type\":\"object\",\"properties\":{\"nx\":{\"type\":\"integer\","
                        + "\"format\":\"int32\",\"description\":\"Grid x coordinate\"}},"
                                + "\"required\":[\"nx\"]}}]}}\n\n");
                applyToolsListBehavior(behavior, port);
            } else {
                exchange.sendResponseHeaders(400, -1);
            }
        }
    }

    private static void applyToolsListBehavior(String behavior, int port) {
        if (behavior.isEmpty()) {
            return;
        }
        try {
            Thread.sleep(20);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        }
        if ("duplicate-startup".equals(behavior)) {
            System.out.println("Tomcat started on port " + port + " (http) with context path '/'");
            System.out.flush();
        } else if ("overflow-output".equals(behavior)) {
            System.out.print("x".repeat(16 * 1024));
            System.out.flush();
        }
    }

    private static void send(HttpExchange exchange, int status, String text) throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
