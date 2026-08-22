package io.gen2spring.mcp.app.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.adapter.mcp.McpJavaSdkEmitter;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore;
import io.gen2spring.mcp.application.managed.execution.ManagedExecutionLimits;
import io.gen2spring.mcp.application.managed.execution.ManagedRuntimeBinding;
import io.gen2spring.mcp.application.managed.execution.ManagedToolExecutor;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccessAuthenticator;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenCodec;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore.AuditCursor;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore.AuditPage;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore.StoredGrant;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ManagedRuntimeJourneyIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void servesExactToolsAndOneNormalizedProviderCallThenRejectsRevocation() throws Exception {
        RuntimeFixture fixture = fixture(1, "token-one");
        try {
            String endpoint = "/mcp/" + fixture.instance.id().value();

            var initialized = send(fixture.mvc, endpoint, "token-one", null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"raw-test","version":"1"}}}
                """);
            assertEquals(200, initialized.status());
            String session = initialized.session();
            assertEquals(null, session);
            JsonNode initialize = responseJson(initialized.body());
            assertEquals("2025-03-26", initialize.path("result").path("protocolVersion").asText());

            assertEquals(202, send(fixture.mvc, endpoint, "token-one", session,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").status());

            JsonNode list = responseJson(send(fixture.mvc, endpoint, "token-one", session,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}").body());
            JsonNode tools = list.path("result").path("tools");
            assertEquals(1, tools.size());
            assertEquals("weather", tools.get(0).path("name").asText());
            assertEquals("object", tools.get(0).path("inputSchema").path("type").asText());
            assertEquals("city", tools.get(0).path("inputSchema").path("required").get(0).asText());

            JsonNode call = responseJson(send(fixture.mvc, endpoint, "token-one", session, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"weather","arguments":{"city":"Seoul"}}}
                """).body());
            JsonNode result = call.path("result");
            assertTrue(result.path("isError").isBoolean(), call.toPrettyString());
            assertFalse(result.path("isError").booleanValue());
            assertEquals(json.readTree("{\"data\":{\"temperature\":12.50}}"),
                    json.readTree(result.path("content").get(0).path("text").asText()));
            assertEquals(1, fixture.providerCalls.get());

            fixture.stored.set(fixture.instance.revokeAt(NOW.plusSeconds(1)));
            assertEquals(401, send(fixture.mvc, endpoint, "token-one", session,
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\",\"params\":{}}").status());
        } finally {
            fixture.close();
        }
    }

    private RuntimeFixture fixture(int suffix, String token) {
        RuntimeTool tool = runtimeTool();
        RuntimeMetadataDocument document = new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "a".repeat(64), List.of(tool));
        var metadata = new CanonicalRuntimeMetadataCodec().encode(document);
        ManagedRuntimeInstance instance = new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.fromString("10000000-0000-0000-0000-00000000000" + suffix)),
                new AccountId(UUID.fromString("20000000-0000-0000-0000-00000000000" + suffix)),
                UUID.fromString("30000000-0000-0000-0000-00000000000" + suffix), metadata.checksum(),
                Optional.empty(), NOW.minusSeconds(1), NOW.plusSeconds(86_400), Optional.empty());
        AtomicReference<ManagedRuntimeInstance> stored = new AtomicReference<>(instance);
        AtomicInteger calls = new AtomicInteger();
        ManagedToolExecutor executor = new ManagedToolExecutor((request, timeout) -> {
            calls.incrementAndGet();
            assertEquals("https://api.example.com/weather?q=Seoul", request.uri().toASCIIString());
            return new ProviderCallResponse(200, Map.of("Content-Type", List.of("application/json")),
                    "{\"response\":{\"body\":{\"temperature\":12.50},\"raw\":\"excluded\"}}"
                            .getBytes(StandardCharsets.UTF_8));
        }, new ManagedExecutionLimits(Duration.ofSeconds(2), 2, 4), Clock.fixed(NOW, ZoneOffset.UTC));
        ManagedRuntimeBinding binding = new ManagedRuntimeBinding(instance, metadata);
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(access -> {
            JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(json);
            var transport = WebMvcStatelessServerTransport.builder()
                    .jsonMapper(mapper).messageEndpoint("/mcp/" + access.instance().id().value()).build();
            var server = McpServer.sync(transport).jsonMapper(mapper)
                    .serverInfo("managed-test", "1")
                    .tools(new McpJavaSdkEmitter().emitStateless(List.of(tool),
                            (name, arguments) -> executor.call(binding, name, arguments)))
                    .build();
            return RuntimeServerHandle.stateless(access.instance(), transport, server);
        }, 8, Clock.fixed(NOW, ZoneOffset.UTC));
        ManagedRuntimeStore store = new ManagedRuntimeStore() {
            @Override public void create(ManagedRuntimeInstance ignored, RuntimeTokenDigest digest,
                    Map<String, io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId>
                            credentialBindings) {}
            @Override public Optional<StoredRuntime> find(RuntimeInstanceId id) {
                return id.equals(instance.id())
                        ? Optional.of(new StoredRuntime(stored.get(), new RuntimeTokenDigest(new byte[32])))
                        : Optional.empty();
            }
            @Override public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant at) { return false; }
        };
        RuntimeTokenCodec tokens = new RuntimeTokenCodec() {
            @Override public io.gen2spring.mcp.application.managed.runtime.IssuedRuntimeToken issue() {
                throw new UnsupportedOperationException();
            }
            @Override public boolean matches(String presented, RuntimeTokenDigest digest) {
                return token.equals(presented);
            }
            @Override public RuntimeTokenDigest digest(String presented) {
                return new RuntimeTokenDigest(new byte[32]);
            }
        };
        var summary = new ToolCatalogStore.CatalogSummary(
                instance.catalogId(), new JobId(UUID.fromString("40000000-0000-0000-0000-000000000001")),
                RuntimeMetadataDocument.VERSION, metadata.checksum(), 1, NOW.minusSeconds(1));
        var details = new ToolCatalogStore.CatalogDetails(summary, document.specificationChecksum(), metadata);
        ToolCatalogService catalogs = new ToolCatalogService(new ToolCatalogStore() {
            @Override public List<CatalogSummary> list(AccountId owner, int limit, Optional<CatalogCursor> cursor) {
                return List.of(summary);
            }
            @Override public Optional<CatalogDetails> find(AccountId owner, UUID catalogId) {
                return owner.equals(instance.owner()) && catalogId.equals(instance.catalogId())
                        ? Optional.of(details) : Optional.empty();
            }
            @Override public Optional<ToolDetails> findTool(
                    AccountId owner, UUID catalogId, String toolName) { return Optional.empty(); }
        });
        RuntimeBearerFilter filter = new RuntimeBearerFilter(new RuntimeAccessAuthenticator(
                store, tokens, Clock.fixed(NOW, ZoneOffset.UTC), catalogs, new EmptyPolicy()));
        MockMvc mvc = MockMvcBuilders.routerFunctions(new ManagedMcpRouter(registry))
                .addFilters(filter).build();
        return new RuntimeFixture(instance, stored, calls, registry, executor, mvc);
    }

    private RuntimeTool runtimeTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("city", Map.of("type", "string", "description", "City name")));
        schema.put("required", List.of("city"));
        return new RuntimeTool(
                "getWeather", "weather", "Get weather", schema, "GENERIC_JSON", Map.of(),
                new RuntimeHttp(HttpMethod.GET, "https://api.example.com", "/weather",
                        List.of(new ParameterBinding("city", ParameterLocation.QUERY, "q")), false, false),
                new ResponseNormalizationPolicy("/response/body", null, List.of(), null, null),
                null, null, List.of());
    }

    private static final class EmptyPolicy implements RuntimePolicyStore {
        @Override public void createGrant(ManagedRuntimeGrant grant, RuntimeTokenDigest digest) {}
        @Override public Optional<StoredGrant> authenticateGrant(
                RuntimeInstanceId runtimeId, RuntimeTokenDigest digest) { return Optional.empty(); }
        @Override public List<ManagedRuntimeGrant> listGrants(AccountId owner, RuntimeInstanceId runtimeId) {
            return List.of();
        }
        @Override public boolean revokeGrant(AccountId owner, RuntimeInstanceId runtimeId,
                RuntimeGrantId grantId, Instant revokedAt) { return false; }
        @Override public boolean acquireRate(RuntimeInstanceId runtimeId, Optional<RuntimeGrantId> grantId,
                int requestsPerMinute) { return true; }
        @Override public void startAudit(ToolExecutionAudit audit) {}
        @Override public boolean completeAudit(ToolExecutionAudit audit) { return true; }
        @Override public AuditPage listAudits(AccountId owner, RuntimeInstanceId runtimeId, int limit,
                Optional<AuditCursor> cursor) { return new AuditPage(List.of(), Optional.empty()); }
    }

    private Exchange send(MockMvc mvc, String endpoint, String token, String session, String body) throws Exception {
        var request = post(endpoint).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json, text/event-stream")
                .contentType("application/json").content(body);
        if (session != null) request.header("Mcp-Session-Id", session);
        var response = mvc.perform(request).andReturn().getResponse();
        return new Exchange(response.getStatus(), response.getHeader("Mcp-Session-Id"),
                response.getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode responseJson(String body) throws Exception {
        if (body.contains("data:")) {
            int data = body.indexOf("data:");
            int end = body.indexOf('\n', data);
            body = body.substring(data + 5, end < 0 ? body.length() : end).trim();
        }
        return json.readTree(body);
    }

    private record Exchange(int status, String session, String body) {}

    private record RuntimeFixture(
            ManagedRuntimeInstance instance,
            AtomicReference<ManagedRuntimeInstance> stored,
            AtomicInteger providerCalls,
            RuntimeServerHandleRegistry registry,
            ManagedToolExecutor executor,
            MockMvc mvc) {
        void close() {
            registry.close();
            executor.close();
        }
    }
}
