package io.gen2spring.mcp.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpExtensionsTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final List<?> tools = List.of(Map.of("name", "weather", "inputSchema", Map.of("type", "object")));

    @Test
    void readsResourcesPromptsTemplatesSkillsAndAppsWithoutFilesystemResolution() throws Exception {
        JsonNode config = json.readTree("""
                {
                  "resources":[{"uri":"resource://guide","name":"Guide","text":"안내"}],
                  "resourceTemplates":[{"uriTemplate":"resource://city/{city}","name":"City","text":"City: ${city}"}],
                  "prompts":[{"name":"greet","arguments":[{"name":"name","required":true}],
                    "messages":[{"role":"user","content":{"type":"text","text":"Hello ${name}"}}],
                    "completions":{"name":["Seoul","Seattle","London"]}}],
                  "skills":[{"uri":"skill://weather-guide/SKILL.md","frontmatter":{"name":"weather-guide","description":"Weather","license":"MIT"},
                    "instructions":"Use the weather tool.","files":[{"path":"references/usage.md","text":"Usage"}]}],
                  "apps":[{"uri":"ui://weather/view","name":"Weather","html":"<!doctype html><html><body>Weather</body></html>","tools":["weather"]}]
                }
                """);
        try (var protocol = protocol(config, null, (name, args) -> Map.of("content", List.of()))) {
            assertEquals("안내", send(protocol, "resources/read", Map.of("uri", "resource://guide"), "alice").at("/result/contents/0/text").asText());
            assertEquals("City: Seoul", send(protocol, "resources/read", Map.of("uri", "resource://city/Seoul"), "alice").at("/result/contents/0/text").asText());
            assertEquals(-32602, send(protocol, "resources/read", Map.of("uri", "file:///etc/passwd"), "alice").at("/error/code").asInt());
            assertEquals("Hello Seoul", send(protocol, "prompts/get", Map.of("name", "greet", "arguments", Map.of("name", "Seoul")), "alice")
                    .at("/result/messages/0/content/text").asText());
            assertEquals(-32602, send(protocol, "prompts/get", Map.of("name", "greet"), "alice").at("/error/code").asInt());
            assertEquals(2, send(protocol, "completion/complete", Map.of("ref", Map.of("type", "ref/prompt", "name", "greet"),
                    "argument", Map.of("name", "name", "value", "Se")), "alice").at("/result/completion/total").asInt());
            JsonNode skill = send(protocol, "skills/get", Map.of("uri", "skill://weather-guide/SKILL.md"), "alice").at("/result/skill");
            assertEquals("MIT", skill.at("/frontmatter/license").asText());
            for (JsonNode file : skill.path("resources")) {
                String text = send(protocol, "resources/read", Map.of("uri", file.path("uri").asText()), "alice").at("/result/contents/0/text").asText();
                assertEquals(file.path("size").asInt(), text.getBytes(StandardCharsets.UTF_8).length);
                assertEquals(file.path("digest").asText(), "sha256:" + McpFeatureCatalog.digest(text.getBytes(StandardCharsets.UTF_8)));
            }
            assertEquals("inode/directory", send(protocol, "resources/directory/read", Map.of("uri", "skill://weather-guide"), "alice")
                    .at("/result/resources/1/mimeType").asText());
            assertEquals(-32602, send(protocol, "resources/directory/read", Map.of("uri", "skill://weather-guide-other"), "alice").at("/error/code").asInt());
            assertEquals("text/html;profile=mcp-app", send(protocol, "resources/read", Map.of("uri", "ui://weather/view"), "alice")
                    .at("/result/contents/0/mimeType").asText());
            var app = request("tools/list", Map.of());
            ((ObjectNode) app.at("/params/_meta/" + ModernMcpProtocol.CAPABILITIES_KEY.replace("/", "~1")))
                    .putObject("extensions").putObject("io.modelcontextprotocol/ui");
            assertEquals("ui://weather/view", invoke(protocol, app, "alice").body().at("/result/tools/0/_meta/ui/resourceUri").asText());
        }
    }

    @Test
    void bindsInteractionStateToOwnerAndOriginalRequestAndNeverExecutesDeclinedInput() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var protocol = protocol(interactionConfig(), null, (name, args) -> {
            calls.incrementAndGet(); return Map.of("content", List.of(), "structuredContent", args);
        })) {
            var request = request("tools/call", Map.of("name", "weather", "arguments", Map.of()));
            assertEquals(-32021, invoke(protocol, request, "alice").body().at("/error/code").asInt());
            capabilities(request).putObject("elicitation").putObject("form");
            JsonNode first = invoke(protocol, request, "alice").body().path("result");
            assertEquals("input_required", first.path("resultType").asText());
            ObjectNode params = (ObjectNode) request.get("params");
            params.put("requestState", first.path("requestState").asText());
            params.set("inputResponses", json.valueToTree(Map.of("city", Map.of("action", "accept", "content", Map.of("city", "Seoul")))));
            assertEquals(-32602, invoke(protocol, request, "bob").body().at("/error/code").asInt());
            var tampered = request.deepCopy(); ((ObjectNode) tampered.at("/params/arguments")).put("other", 1);
            assertEquals(-32602, invoke(protocol, tampered, "alice").body().at("/error/code").asInt());
            assertEquals("Seoul", invoke(protocol, request, "alice").body().at("/result/structuredContent/city").asText());
            assertEquals(1, calls.get());
            ((ObjectNode) params.at("/inputResponses/city")).put("action", "decline");
            assertTrue(invoke(protocol, request, "alice").body().at("/result/isError").asBoolean());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void persistsTasksBeforeReplyAndRecoversCompletedResultsWithOwnerIsolation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String id;
        try (var store = store(); var protocol = protocol(taskConfig(), store, (name, args) -> {
            started.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return Map.of("content", List.of(), "isError", true);
        })) {
            var request = request("tools/call", Map.of("name", "weather"));
            capabilities(request).putObject("extensions").putObject(ModernMcpProtocol.TASKS);
            JsonNode created = invoke(protocol, request, "alice").body().path("result");
            assertEquals("task", created.path("resultType").asText());
            id = created.path("taskId").asText();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(Files.readString(directory.resolve("tasks.json")).contains(id));
            assertEquals("working", send(protocol, "tasks/get", Map.of("taskId", id), "alice").at("/result/status").asText());
            assertEquals(-32602, send(protocol, "tasks/get", Map.of("taskId", id), "bob").at("/error/code").asInt());
            release.countDown();
            JsonNode completed = awaitStatus(protocol, id, "completed");
            assertTrue(completed.at("/result/isError").asBoolean());
            send(protocol, "tasks/cancel", Map.of("taskId", id), "alice");
            assertEquals("completed", store.get("alice", id).path("status").asText());
        } finally { release.countDown(); }
        try (var reopened = store()) { assertEquals("completed", reopened.get("alice", id).path("status").asText()); }
    }

    @Test
    void resumesTaskInputAfterRestartAndIgnoresDuplicateUpdates() throws Exception {
        JsonNode config = interactionConfig(); ((ObjectNode) config).set("tasks", taskConfig().get("tasks"));
        String id;
        try (var store = store(); var protocol = protocol(config, store, (name, args) -> Map.of("content", List.of(), "structuredContent", args))) {
            var request = request("tools/call", Map.of("name", "weather", "arguments", Map.of()));
            capabilities(request).putObject("extensions").putObject(ModernMcpProtocol.TASKS);
            capabilities(request).putObject("elicitation").putObject("form");
            id = invoke(protocol, request, "alice").body().at("/result/taskId").asText();
            awaitStatus(protocol, id, "input_required");
        }
        AtomicInteger calls = new AtomicInteger();
        try (var reopened = store(); var protocol = protocol(config, reopened, (name, args) -> {
            calls.incrementAndGet(); return Map.of("content", List.of(), "structuredContent", args);
        })) {
            var update = Map.of("taskId", id, "inputResponses", Map.of("unknown", Map.of(), "city", Map.of("action", "accept", "content", Map.of("city", "Busan"))));
            assertFalse(send(protocol, "tasks/update", update, "alice").has("error"));
            assertEquals("Busan", awaitStatus(protocol, id, "completed").at("/result/structuredContent/city").asText());
            send(protocol, "tasks/update", update, "alice");
            assertEquals(1, calls.get());
        }
    }

    @Test
    void cancelsTaskAndPreservesTerminalStateAgainstLateCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var store = store(); var protocol = protocol(taskConfig(), store, (name, args) -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return Map.of("content", List.of());
        })) {
            var request = request("tools/call", Map.of("name", "weather")); capabilities(request).putObject("extensions").putObject(ModernMcpProtocol.TASKS);
            String id = invoke(protocol, request, "alice").body().at("/result/taskId").asText();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            send(protocol, "tasks/cancel", Map.of("taskId", id), "alice"); release.countDown();
            assertEquals("cancelled", store.get("alice", id).path("status").asText());
        } finally { release.countDown(); }
    }

    @Test
    void rejectsSecondWriterExpiresHandlesAndRecoversInterruptedWork() throws Exception {
        Clock initial = Clock.fixed(java.time.Instant.parse("2026-09-29T00:00:00Z"), java.time.ZoneOffset.UTC);
        String id;
        CountDownLatch release = new CountDownLatch(1);
        try (var store = new McpTaskStore(json, directory, initial, Duration.ofSeconds(1))) {
            assertThrows(IllegalStateException.class, this::store);
            id = store.create("alice", json.createObjectNode(), request -> {
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return json.createObjectNode();
            }).path("taskId").asText();
        } finally { release.countDown(); }
        try (var recovered = new McpTaskStore(json, directory, initial, Duration.ofSeconds(1))) {
            assertEquals("failed", recovered.get("alice", id).path("status").asText());
        }
        try (var expired = new McpTaskStore(json, directory, Clock.offset(initial, Duration.ofSeconds(2)), Duration.ofSeconds(1))) {
            assertThrows(IllegalArgumentException.class, () -> expired.get("alice", id));
        }
    }

    @Test
    void acknowledgesSubscriptionsBeforeEventsAndCleansUpOnDisconnect() throws Exception {
        try (var protocol = protocol(json.createObjectNode(), null, (name, args) -> Map.of())) {
            var request = request("subscriptions/listen", Map.of("notifications", Map.of("toolsListChanged", true)));
            var reply = invoke(protocol, request, "alice");
            assertNotNull(invoke(protocol, request, "alice").stream());
            ByteArrayOutputStream frames = new ByteArrayOutputStream();
            assertThrows(java.io.IOException.class, () -> reply.stream().write(new java.io.OutputStream() {
                @Override public void write(int value) { frames.write(value); }
                @Override public void flush() throws java.io.IOException { throw new java.io.IOException("Client disconnected"); }
            }));
            assertTrue(frames.toString(StandardCharsets.UTF_8).contains("notifications/subscriptions/acknowledged"));
            assertFalse(frames.toString(StandardCharsets.UTF_8).contains("toolsListChanged"));
            assertNotNull(invoke(protocol, request, "alice").stream());
        }
    }

    @Test
    void servesStdioWithoutHttpHeadersAndAcknowledgesHttpNotificationsWithoutBody() throws Exception {
        try (var protocol = protocol(json.createObjectNode(), null, (name, args) -> Map.of())) {
            var notification = request("notifications/cancelled", Map.of("requestId", 123)); notification.remove("id");
            var reply = invoke(protocol, notification, "alice"); assertEquals(202, reply.status()); assertNull(reply.body());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            String input = request("server/discover", Map.of()) + "\n" + request("resources/list", Map.of()) + "\n";
            McpStdioTransport.serve(protocol, json, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);
            assertEquals(2, output.toString(StandardCharsets.UTF_8).lines().count());
            assertEquals("complete", json.readTree(output.toString(StandardCharsets.UTF_8).lines().findFirst().orElseThrow()).at("/result/resultType").asText());
        }
    }

    private McpTaskStore store() { return new McpTaskStore(json, directory, Clock.systemUTC(), Duration.ofHours(1)); }
    private ModernMcpProtocol protocol(JsonNode config, McpTaskStore store, java.util.function.BiFunction<String, Map<String, Object>, ?> call) {
        return new ModernMcpProtocol(json, tools, call, List.of(ModernMcpProtocol.VERSION), config, store);
    }
    private ObjectNode taskConfig() {
        ObjectNode config = json.createObjectNode(); config.putObject("tasks").putArray("tools").add("weather"); return config;
    }
    private JsonNode interactionConfig() throws Exception {
        return json.readTree("""
                {"interactions":{"tools/call:weather":{"inputRequests":{"city":{"method":"elicitation/create","params":{
                "mode":"form","message":"City?","requestedSchema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}},
                "argumentBindings":{"city":"/city/content/city"}}}}
                """);
    }
    private ObjectNode request(String method, Map<String, ?> params) {
        ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
        ObjectNode parameters = json.valueToTree(params); request.set("params", parameters);
        var metadata = parameters.putObject("_meta").put(ModernMcpProtocol.VERSION_KEY, ModernMcpProtocol.VERSION);
        metadata.putObject(ModernMcpProtocol.CAPABILITIES_KEY); return request;
    }
    private ObjectNode capabilities(ObjectNode request) { return (ObjectNode) request.path("params").path("_meta").path(ModernMcpProtocol.CAPABILITIES_KEY); }
    private ModernMcpProtocol.Reply invoke(ModernMcpProtocol protocol, ObjectNode request, String owner) {
        Map<String, String> headers = new HashMap<>(Map.of("MCP-Protocol-Version", ModernMcpProtocol.VERSION, "Mcp-Method", request.path("method").asText()));
        headers.put("Mcp-Name", request.path("params").path("name").asText(request.path("params").path("uri").asText()));
        return protocol.handle(request, headers::get, owner);
    }
    private JsonNode send(ModernMcpProtocol protocol, String method, Map<String, ?> params, String owner) {
        return invoke(protocol, request(method, params), owner).body();
    }
    private JsonNode awaitStatus(ModernMcpProtocol protocol, String id, String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        JsonNode result;
        do {
            result = send(protocol, "tasks/get", Map.of("taskId", id), "alice").path("result");
            if (result.path("status").asText().equals(status)) return result;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Expected " + status + ", got " + result); return result;
    }
}
