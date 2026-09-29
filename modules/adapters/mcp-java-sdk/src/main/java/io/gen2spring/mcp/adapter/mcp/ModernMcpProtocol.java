package io.gen2spring.mcp.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Stateless tools-only MCP 2026 adapter; legacy requests remain owned by the SDK. */
public final class ModernMcpProtocol {
    public static final String VERSION = "2026-07-28";
    public static final String VERSION_KEY = "io.modelcontextprotocol/protocolVersion";
    public static final String CAPABILITIES_KEY = "io.modelcontextprotocol/clientCapabilities";
    private final ObjectMapper json;
    private final List<String> supportedVersions;
    private final List<?> tools;
    private final BiFunction<String, Map<String, Object>, ?> call;

    public ModernMcpProtocol(ObjectMapper json, List<?> tools,
            BiFunction<String, Map<String, Object>, ?> call) {
        this(json, tools, call, List.of("2025-03-26", VERSION));
    }

    public ModernMcpProtocol(ObjectMapper json, List<?> tools,
            BiFunction<String, Map<String, Object>, ?> call, List<String> supportedVersions) {
        this.supportedVersions = List.copyOf(supportedVersions);
        this.json = json;
        this.tools = List.copyOf(tools);
        this.call = call;
    }

    public boolean supportsLegacy() {
        return supportedVersions.contains("2025-03-26");
    }

    public Reply rejectLegacy(JsonNode body, String headerVersion) {
        String requested = body.path("params").path("protocolVersion").asText(
                headerVersion == null ? "2025-03-26" : headerVersion);
        JsonNode id = body.path("id");
        return error(id.isTextual() || id.isIntegralNumber() ? id : null, 400, -32022,
                "Unsupported protocol version", Map.of("supported", supportedVersions, "requested", requested));
    }

    public static boolean allowsOrigin(String origin, String scheme, String serverName, int serverPort) {
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(serverName)
                    && scheme.equals(uri.getScheme()) && serverName.equals(uri.getHost()) && serverPort == port
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static String observedVersion(JsonNode body, String header) {
        String version = body.path("params").path("_meta").path(VERSION_KEY).asText(header);
        if (version == null && "initialize".equals(body.path("method").asText())) {
            version = body.path("params").path("protocolVersion").asText();
        }
        return List.of("2025-03-26", "2025-06-18", "2025-11-25", VERSION).contains(version == null ? "" : version)
                ? version : "unknown";
    }

    public static boolean isModern(JsonNode body, String version) {
        return (version != null && !List.of("2025-03-26", "2025-06-18", "2025-11-25").contains(version))
                || body.path("params").path("_meta").has(VERSION_KEY)
                || "server/discover".equals(body.path("method").asText());
    }

    public Reply handle(JsonNode body, Function<String, String> headers) {
        JsonNode id = body.path("id");
        if (!body.isObject() || !"2.0".equals(body.path("jsonrpc").asText())
                || !(id.isTextual() || id.isIntegralNumber()) || !body.path("method").isTextual()) {
            return error(id.isTextual() || id.isIntegralNumber() ? id : null, 400, -32600, "Invalid request", null);
        }
        JsonNode params = body.path("params");
        JsonNode meta = params.path("_meta");
        if (!meta.path(VERSION_KEY).isTextual() || !meta.path(CAPABILITIES_KEY).isObject()) {
            return error(id, 400, -32602, "Required request metadata is missing", null);
        }
        String version = meta.path(VERSION_KEY).asText();
        String method = body.path("method").asText();
        if (!version.equals(headers.apply("MCP-Protocol-Version"))
                || !method.equals(headers.apply("Mcp-Method"))) {
            return error(id, 400, -32020, "Header mismatch", null);
        }
        if (!VERSION.equals(version) || !supportedVersions.contains(version)) {
            return error(id, 400, -32022, "Unsupported protocol version",
                    Map.of("supported", supportedVersions, "requested", version));
        }
        try {
            return switch (method) {
                case "server/discover" -> complete(id, Map.of("supportedVersions", supportedVersions,
                        "capabilities", Map.of("tools", Map.of()), "ttlMs", 0, "cacheScope", "private"));
                case "tools/list" -> params.has("cursor")
                        ? error(id, 400, -32602, "Invalid cursor", null)
                        : complete(id, Map.of("tools", tools, "ttlMs", 0, "cacheScope", "private"));
                case "tools/call" -> call(id, params, headers);
                default -> error(id, 404, -32601, "Method not found", null);
            };
        } catch (RuntimeException failure) {
            return error(id, 500, -32603, "Tool execution failed", null);
        }
    }

    private Reply call(JsonNode id, JsonNode params, Function<String, String> headers) {
        if (!params.path("name").isTextual()
                || (params.has("arguments") && !params.path("arguments").isObject())) {
            return error(id, 400, -32602, "Invalid tool parameters", null);
        }
        String name = params.path("name").asText();
        if (!name.equals(decode(headers.apply("Mcp-Name")))) {
            return error(id, 400, -32020, "Header mismatch", null);
        }
        boolean known = tools.stream().anyMatch(tool -> name.equals(json.valueToTree(tool).path("name").asText()));
        if (!known) return error(id, 400, -32602, "Unknown tool", null);
        Map<String, Object> arguments = new LinkedHashMap<>();
        params.path("arguments").properties().forEach(entry ->
                arguments.put(entry.getKey(), json.convertValue(entry.getValue(), Object.class)));
        return complete(id, call.apply(name, arguments));
    }

    private String decode(String header) {
        if (header == null) return null;
        if (header.startsWith("=?base64?") && header.endsWith("?=")) {
            try {
                byte[] bytes = Base64.getDecoder().decode(header.substring(9, header.length() - 2));
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (IllegalArgumentException | CharacterCodingException invalid) {
                return null;
            }
        }
        return header.equals(header.strip()) && header.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)
                ? header : null;
    }

    private Reply complete(JsonNode id, Object value) {
        ObjectNode result = json.valueToTree(value);
        result.put("resultType", "complete");
        result.putObject("_meta").putObject("io.modelcontextprotocol/serverInfo")
                .put("name", "gen2spring-mcp").put("version", "0.1.0");
        ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return new Reply(200, response);
    }

    public Reply error(JsonNode id, int status, int code, String message, Object data) {
        ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
        if (id != null && !id.isMissingNode()) response.set("id", id);
        ObjectNode error = response.putObject("error").put("code", code).put("message", message);
        if (data != null) error.set("data", json.valueToTree(data));
        return new Reply(status, response);
    }

    public record Reply(int status, JsonNode body) {}
}
