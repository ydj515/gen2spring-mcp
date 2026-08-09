package io.gen2spring.mcp.validation.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class McpTestApplication {
    private static final Pattern QUOTED_NX = Pattern.compile("\\\"nx\\\":\\\"([^\\\"]*)\\\"");
    private static final Pattern NUMBER_NX = Pattern.compile("\\\"nx\\\":(-?[0-9]+)");

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
            } else if (request.contains("\"method\":\"tools/call\"")) {
                if (!"application-session".equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                handleToolCall(exchange, request, behavior);
            } else {
                exchange.sendResponseHeaders(400, -1);
            }
        }
    }

    private static void handleToolCall(HttpExchange exchange, String request, String behavior)
            throws java.io.IOException {
        if ("application-exit-during-call".equals(behavior)) {
            Runtime.getRuntime().halt(17);
        }
        if ("tool-call-timeout".equals(behavior)) {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        String operationResult = "{\"validated\":true,\"operationId\":\"getForecast\"}";
        if (!"no-upstream".equals(behavior)) {
            String path = "upstream-mismatch".equals(behavior) ? "/unexpected" : "/forecast";
            String query = "nx=" + encode(extractNx(request))
                    + "&serviceKey=" + encode(requiredEnvironment("VALIDATOR_SERVICE_KEY"));
            HttpRequest upstreamRequest = HttpRequest.newBuilder(
                            URI.create(requiredEnvironment("PROVIDER_BASE_URL") + path + "?" + query))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            try {
                HttpResponse<String> upstreamResponse = HttpClient.newHttpClient().send(
                        upstreamRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                Files.writeString(Path.of("upstream.status"), Integer.toString(upstreamResponse.statusCode()));
                if (!"upstream-mismatch".equals(behavior)) {
                    operationResult = upstreamResponse.body();
                }
                if ("duplicate-upstream".equals(behavior)) {
                    HttpResponse<String> duplicateResponse = HttpClient.newHttpClient().send(
                            upstreamRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    Files.writeString(
                            Path.of("duplicate-upstream.status"),
                            Integer.toString(duplicateResponse.statusCode()));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("test upstream call interrupted", exception);
            }
        }
        if ("mcp-result-mismatch".equals(behavior)) {
            operationResult = "{\"validated\":false,\"operationId\":\"getForecast\"}";
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        send(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"isError\":false,"
                + "\"content\":[{\"type\":\"text\",\"text\":" + jsonString(operationResult) + "}]}}");
    }

    private static String extractNx(String request) {
        Matcher quoted = QUOTED_NX.matcher(request);
        if (quoted.find()) {
            return quoted.group(1);
        }
        Matcher number = NUMBER_NX.matcher(request);
        if (number.find()) {
            return number.group(1);
        }
        throw new IllegalArgumentException("nx argument is required");
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required test environment is missing");
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String jsonString(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
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
