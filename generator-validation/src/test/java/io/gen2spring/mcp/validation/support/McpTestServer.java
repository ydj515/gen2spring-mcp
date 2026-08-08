package io.gen2spring.mcp.validation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpTestServer implements AutoCloseable {
    public enum Scenario {
        SUCCESS,
        INITIALIZE_ERROR,
        WRONG_ID,
        OVERSIZED_ID,
        MISSING_SCHEMA,
        WRONG_STATUS,
        WRONG_CONTENT_TYPE,
        OVERSIZE,
        SLOW_BODY,
        MISSING_PROTOCOL_VERSION,
        UNSUPPORTED_PROTOCOL_VERSION,
        MISSING_CAPABILITIES,
        INVALID_CAPABILITIES,
        MISSING_SERVER_INFO,
        INVALID_SERVER_INFO,
        MISSING_TOOLS_CAPABILITY,
        INVALID_TOOLS_CAPABILITY,
        EMPTY_SCHEMA,
        WRONG_SCHEMA_TYPE,
        WRONG_PROPERTIES,
        WRONG_REQUIRED,
        EXTRA_PROPERTY,
        NUMERIC_EQUIVALENT,
        NUMERIC_MISMATCH,
        NUMERIC_PRECISION_MISMATCH
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SESSION_ID = "test-session";

    private final HttpServer server;
    private final Scenario scenario;
    private final Runnable beforeToolsListResponse;
    private final AtomicBoolean initializedNotification = new AtomicBoolean();
    private final AtomicBoolean sessionHeaderOnInitializedNotification = new AtomicBoolean();
    private final AtomicBoolean sessionHeaderOnToolsList = new AtomicBoolean();

    private McpTestServer(HttpServer server, Scenario scenario, Runnable beforeToolsListResponse) {
        this.server = server;
        this.scenario = scenario;
        this.beforeToolsListResponse = beforeToolsListResponse;
    }

    public static McpTestServer startWithJsonInitializeAndSseToolsList() throws IOException {
        return start(Scenario.SUCCESS);
    }

    public static McpTestServer start(Scenario scenario) throws IOException {
        return start(scenario, 0);
    }

    public static McpTestServer start(Scenario scenario, int port) throws IOException {
        return start(scenario, port, () -> {});
    }

    public static McpTestServer start(Scenario scenario, Runnable beforeToolsListResponse) throws IOException {
        return start(scenario, 0, beforeToolsListResponse);
    }

    private static McpTestServer start(Scenario scenario, int port, Runnable beforeToolsListResponse) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        var result = new McpTestServer(server, scenario, beforeToolsListResponse);
        server.createContext("/mcp", result::handle);
        server.start();
        return result;
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    public boolean receivedInitializedNotification() {
        return initializedNotification.get();
    }

    public boolean receivedSessionHeaderOnToolsList() {
        return sessionHeaderOnToolsList.get();
    }

    public boolean receivedSessionHeaderOnInitializedNotification() {
        return sessionHeaderOnInitializedNotification.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if ("initialize".equals(method)) {
                initialize(exchange);
            } else if ("notifications/initialized".equals(method)) {
                initializedNotification.set(true);
                sessionHeaderOnInitializedNotification.set(
                        SESSION_ID.equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
                send(exchange, 202, "application/json", new byte[0]);
            } else if ("tools/list".equals(method)) {
                sessionHeaderOnToolsList.set(SESSION_ID.equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
                toolsList(exchange);
            } else {
                send(exchange, 400, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void initialize(HttpExchange exchange) throws IOException {
        if (scenario == Scenario.WRONG_STATUS) {
            send(exchange, 503, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (scenario == Scenario.WRONG_CONTENT_TYPE) {
            send(exchange, 200, "text/plain", "{}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        exchange.getResponseHeaders().add("mCp-SeSsIoN-Id", SESSION_ID);
        String response = switch (scenario) {
            case INITIALIZE_ERROR -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"message\":\"private\"}}";
            case WRONG_ID -> "{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{\"protocolVersion\":\"2025-03-26\"}}";
            case OVERSIZED_ID -> initializeResult(
                    "18446744073709551617", "\"2025-03-26\"", "{\"tools\":{\"listChanged\":false}}",
                    "{\"name\":\"test-server\",\"version\":\"1.0.0\"}");
            case OVERSIZE -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"padding\":\"" + "x".repeat(4_096) + "\"}}";
            case MISSING_PROTOCOL_VERSION -> initializeResult("", "{\"tools\":{}}", "{\"name\":\"test-server\",\"version\":\"1.0.0\"}");
            case UNSUPPORTED_PROTOCOL_VERSION -> initializeResult("\"2024-11-05\"", "{\"tools\":{}}", "{\"name\":\"test-server\",\"version\":\"1.0.0\"}");
            case MISSING_CAPABILITIES -> initializeResult("\"2025-03-26\"", "", "{\"name\":\"test-server\",\"version\":\"1.0.0\"}");
            case INVALID_CAPABILITIES -> initializeResult("\"2025-03-26\"", "\"tools\"", "{\"name\":\"test-server\",\"version\":\"1.0.0\"}");
            case MISSING_SERVER_INFO -> initializeResult("\"2025-03-26\"", "{\"tools\":{}}", "");
            case INVALID_SERVER_INFO -> initializeResult("\"2025-03-26\"", "{\"tools\":{}}", "{\"name\":true,\"version\":\"1.0.0\"}");
            case MISSING_TOOLS_CAPABILITY -> initializeResult("\"2025-03-26\"", "{}", "{\"name\":\"test-server\",\"version\":\"1.0.0\"}");
            case INVALID_TOOLS_CAPABILITY -> initializeResult("\"2025-03-26\"", "{\"tools\":true}", "{\"name\":\"test-server\",\"version\":\"1.0.0\"}");
            default -> initializeResult("\"2025-03-26\"", "{\"tools\":{\"listChanged\":false}}", "{\"name\":\"test-server\",\"version\":\"1.0.0\"}");
        };
        if (scenario == Scenario.SLOW_BODY) {
            slowSend(exchange, response);
            return;
        }
        send(exchange, 200, "application/json; charset=utf-8", response.getBytes(StandardCharsets.UTF_8));
    }

    private static String initializeResult(String protocolVersion, String capabilities, String serverInfo) {
        return initializeResult("1", protocolVersion, capabilities, serverInfo);
    }

    private static String initializeResult(
            String id, String protocolVersion, String capabilities, String serverInfo) {
        StringBuilder result = new StringBuilder("{\"result\":{");
        appendField(result, "protocolVersion", protocolVersion);
        appendField(result, "capabilities", capabilities);
        appendField(result, "serverInfo", serverInfo);
        return result.append("},\"id\":").append(id).append(",\"jsonrpc\":\"2.0\"}").toString();
    }

    private static void appendField(StringBuilder result, String name, String value) {
        if (value.isEmpty()) {
            return;
        }
        if (!result.toString().endsWith("{")) {
            result.append(',');
        }
        result.append('\"').append(name).append("\":").append(value);
    }

    private void toolsList(HttpExchange exchange) throws IOException {
        String inputSchema = switch (scenario) {
            case MISSING_SCHEMA -> "";
            case EMPTY_SCHEMA -> ",\"inputSchema\":{}";
            case WRONG_SCHEMA_TYPE -> ",\"inputSchema\":{\"type\":\"array\",\"properties\":{\"nx\":{\"type\":\"integer\",\"format\":\"int32\",\"description\":\"Grid x coordinate\"}},\"required\":[\"nx\"]}";
            case WRONG_PROPERTIES -> ",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"ny\":{\"type\":\"integer\",\"format\":\"int32\",\"description\":\"Grid x coordinate\"}},\"required\":[\"ny\"]}";
            case WRONG_REQUIRED -> ",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"nx\":{\"type\":\"integer\",\"format\":\"int32\",\"description\":\"Grid x coordinate\"}},\"required\":[]}";
            case EXTRA_PROPERTY -> ",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"nx\":{\"type\":\"integer\",\"format\":\"int32\",\"description\":\"Grid x coordinate\"},\"extra\":{\"type\":\"string\"}},\"required\":[\"nx\"]}";
            case NUMERIC_EQUIVALENT -> numericSchema(90);
            case NUMERIC_MISMATCH -> numericSchema(91);
            case NUMERIC_PRECISION_MISMATCH -> numericSchema("0.10000000000000001");
            default -> ",\"inputSchema\":{\"required\":[\"nx\"],\"properties\":{\"nx\":{\"description\":\"Grid x coordinate\",\"format\":\"int32\",\"type\":\"integer\"}},\"type\":\"object\",\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}";
        };
        String json = "{\"id\":2,\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[{"
                + "\"description\":\"Get the public weather forecast for a grid location.\","
                + "\"name\":\"kma_weather_get_forecast\"" + inputSchema + "}]}}";
        String response = "event: message\n" + "data: " + json + "\n\n";
        send(exchange, 200, "text/event-stream", response.getBytes(StandardCharsets.UTF_8));
        beforeToolsListResponse.run();
    }

    private static String numericSchema(int minimum) {
        return numericSchema(Integer.toString(minimum));
    }

    private static String numericSchema(String minimum) {
        return ",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"latitude\":{\"type\":\"number\",\"minimum\":"
                + minimum + "}},\"required\":[\"latitude\"]}";
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    private static void slowSend(HttpExchange exchange, String response) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(body, 0, 1);
        exchange.getResponseBody().flush();
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        }
        exchange.getResponseBody().write(body, 1, body.length - 1);
    }
}
