package io.gen2spring.mcp.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModernMcpProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final ModernMcpProtocol protocol = new ModernMcpProtocol(json,
            List.of(Map.of("name", "weather", "inputSchema", Map.of("type", "object"))),
            (name, arguments) -> {
                calls.incrementAndGet();
                return Map.of("content", List.of(Map.of("type", "text", "text", "{}")), "isError", false);
            });

    @Test
    void advertisesOnlySelectedVersionsAndRejectsDisabledLifecycles() throws Exception {
        var legacy = new ModernMcpProtocol(json, List.of(), (name, arguments) -> Map.of(), List.of("2025-03-26"));
        var reply = legacy.handle(request("server/discover"), headers("server/discover")::get);
        assertEquals(-32022, reply.body().at("/error/code").asInt());
        assertEquals(json.readTree("[\"2025-03-26\"]"), reply.body().at("/error/data/supported"));
        var modern = new ModernMcpProtocol(json, List.of(), (name, arguments) -> Map.of(), List.of("2026-07-28"));
        assertFalse(modern.supportsLegacy());
        var discovery = modern.handle(request("server/discover"), headers("server/discover")::get);
        assertEquals(json.readTree("[\"2026-07-28\"]"), discovery.body().at("/result/supportedVersions"));
        assertEquals(-32022, modern.rejectLegacy(json.readTree("{\"id\":1}"), null).body().at("/error/code").asInt());
    }

    @Test
    void acceptsOnlyExplicitLoopbackOriginsAndBoundsMetricCardinality() throws Exception {
        assertTrue(ModernMcpProtocol.allowsOrigin(null, "https", "api.example.com", 443));
        assertTrue(ModernMcpProtocol.allowsOrigin("http://127.0.0.1:8080", "http", "127.0.0.1", 8080));
        for (String origin : List.of("null", "http://evil.example:8080", "http://127.0.0.1:8081",
                "http://evil@127.0.0.1:8080", "http://127.0.0.1:8080/path")) {
            assertFalse(ModernMcpProtocol.allowsOrigin(origin, "http", "127.0.0.1", 8080), origin);
        }
        assertEquals("unknown", ModernMcpProtocol.observedVersion(json.readTree("{}"), "arbitrary-version"));
        assertEquals("2026-07-28", ModernMcpProtocol.observedVersion(request("tools/list"), null));
    }

    @Test
    void discoversAndExecutesWithoutInitialization() {
        var discovery = send(request("server/discover"), headers("server/discover"));
        assertEquals(200, discovery.status());
        assertEquals("complete", discovery.body().at("/result/resultType").asText());
        assertEquals(List.of("2025-03-26", "2026-07-28"),
                json.convertValue(discovery.body().at("/result/supportedVersions"), List.class));
        var listed = send(request("tools/list"), headers("tools/list"));
        assertEquals("private", listed.body().at("/result/cacheScope").asText());
        assertEquals(0, listed.body().at("/result/ttlMs").asInt());
        var body = request("tools/call");
        ((ObjectNode) body.get("params")).put("name", "weather").putObject("arguments");
        var headers = headers("tools/call");
        headers.put("Mcp-Name", "=?base64?d2VhdGhlcg==?=");
        assertEquals(200, send(body, headers).status());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsVersionAndHeaderErrorsBeforeExecuting() {
        var body = request("tools/call");
        ((ObjectNode) body.get("params")).put("name", "weather");
        var headers = headers("tools/call");
        assertEquals(-32020, send(body, headers).body().at("/error/code").asInt());
        headers.put("Mcp-Name", "other");
        assertEquals(-32020, send(body, headers).body().at("/error/code").asInt());
        headers.put("Mcp-Name", "weather");
        headers.put("MCP-Protocol-Version", "2099-01-01");
        ((ObjectNode) body.at("/params/_meta")).put(ModernMcpProtocol.VERSION_KEY, "2099-01-01");
        var unsupported = send(body, headers);
        assertEquals(400, unsupported.status());
        assertEquals(-32022, unsupported.body().at("/error/code").asInt());
        assertEquals(2, unsupported.body().at("/error/data/supported").size());
        ((ObjectNode) body.at("/params/_meta")).remove(ModernMcpProtocol.CAPABILITIES_KEY);
        assertEquals(-32602, send(body, headers).body().at("/error/code").asInt());
        assertEquals(0, calls.get());
    }

    @Test
    void keepsLegacyRequestsSeparateAndRejectsUnknownMethods() throws Exception {
        assertFalse(ModernMcpProtocol.isModern(json.readTree("{\"method\":\"initialize\"}"), null));
        assertTrue(ModernMcpProtocol.isModern(request("tools/list"), null));
        var unknown = send(request("initialize"), headers("initialize"));
        assertEquals(404, unknown.status());
        assertEquals(-32601, unknown.body().at("/error/code").asInt());
        var invalid = request("ping");
        invalid.putNull("id");
        assertEquals(-32600, send(invalid, headers("ping")).body().at("/error/code").asInt());
    }

    @Test
    void generatedProtocolTemplateMatchesTheTestedManagedImplementation() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        String source = Files.readString(root.resolve(
                "modules/adapters/mcp-java-sdk/src/main/java/io/gen2spring/mcp/adapter/mcp/ModernMcpProtocol.java"));
        String template = Files.readString(root.resolve(
                "modules/adapters/emitters/mcp-runtime/src/main/resources/mcp/ModernMcpProtocol.java.template"));
        assertEquals(source.replace("package io.gen2spring.mcp.adapter.mcp;", "package ${package}.generated.tool;"), template);
    }

    private ObjectNode request(String method) {
        var body = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
        var meta = body.putObject("params").putObject("_meta");
        meta.put(ModernMcpProtocol.VERSION_KEY, ModernMcpProtocol.VERSION);
        meta.putObject(ModernMcpProtocol.CAPABILITIES_KEY);
        return body;
    }

    private Map<String, String> headers(String method) {
        return new HashMap<>(Map.of("MCP-Protocol-Version", ModernMcpProtocol.VERSION, "Mcp-Method", method));
    }

    private ModernMcpProtocol.Reply send(ObjectNode body, Map<String, String> headers) {
        return protocol.handle(body, headers::get);
    }
}
