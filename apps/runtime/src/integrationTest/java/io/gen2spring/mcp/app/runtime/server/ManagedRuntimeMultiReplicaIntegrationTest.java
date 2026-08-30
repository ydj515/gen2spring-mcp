package io.gen2spring.mcp.app.runtime.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.adapter.mcp.McpJavaSdkEmitter;
import io.gen2spring.mcp.adapter.persistence.PostgresManagedRuntimeStore;
import io.gen2spring.mcp.adapter.persistence.PostgresRuntimePolicyStore;
import io.gen2spring.mcp.adapter.persistence.PostgresRuntimeCatalogTransitionStore;
import io.gen2spring.mcp.adapter.persistence.PostgresToolCatalogStore;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.app.runtime.security.RuntimeBearerFilter;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeMigrationService;
import io.gen2spring.mcp.application.managed.credential.CredentialProtector;
import io.gen2spring.mcp.application.managed.credential.CredentialSecret;
import io.gen2spring.mcp.application.managed.credential.ManagedCredentialStore;
import io.gen2spring.mcp.application.managed.credential.ProtectedCredential;
import io.gen2spring.mcp.application.managed.credential.RuntimeCredentialResolver;
import io.gen2spring.mcp.application.managed.execution.ManagedExecutionContext;
import io.gen2spring.mcp.application.managed.execution.ManagedExecutionLimits;
import io.gen2spring.mcp.application.managed.execution.ManagedRuntimeBinding;
import io.gen2spring.mcp.application.managed.execution.ManagedToolExecutor;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccessAuthenticator;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenCodec;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit.AuditStatus;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ManagedRuntimeMultiReplicaIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-21T02:00:00Z");
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private static final RuntimeInstanceId RUNTIME_ID = new RuntimeInstanceId(
            UUID.fromString("20000000-0000-0000-0000-000000000001"));
    private static final RuntimeGrantId GRANT_ID = new RuntimeGrantId(
            UUID.fromString("30000000-0000-0000-0000-000000000001"));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.9-alpine");

    private final ObjectMapper json = new ObjectMapper();
    private DataSource dataSource;

    @BeforeEach
    void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void alternatesStatelessRequestsAcrossReplicasWithSharedRateAndAuditState() throws Exception {
        RuntimeTool tool = tool();
        var metadata = new CanonicalRuntimeMetadataCodec().encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "a".repeat(64), List.of(tool)));
        ManagedRuntimeInstance instance = seed(metadata);
        ManagedRuntimeGrant grant = new ManagedRuntimeGrant(
                GRANT_ID, RUNTIME_ID, OWNER, "client-a", Set.of(tool.name()), 1,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), Optional.empty());
        RuntimePolicyStore policies = new PostgresRuntimePolicyStore(dataSource);
        assertTrue(policies.createGrant(
                grant, new RuntimeTokenDigest(new byte[32]),
                instance.catalogId(), instance.catalogChecksum(), NOW));
        RuntimeAccess access = new RuntimeAccess(
                instance, Optional.of(GRANT_ID), "client-a", Set.of(tool.name()), 1, false,
                "b".repeat(64), grant.expiresAt());
        AtomicInteger providerCalls = new AtomicInteger();
        Replica first = replica(access, metadata, policies, providerCalls);
        Replica second = replica(access, metadata, policies, providerCalls);
        String endpoint = "/mcp/" + RUNTIME_ID.value();

        try {
            Exchange initialize = send(first.mvc(), access, endpoint, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"replica-test","version":"1"}}}
                    """);
            assertEquals(200, initialize.status());
            assertEquals(null, initialize.sessionId());

            JsonNode tools = response(send(second.mvc(), access, endpoint,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}").body())
                    .path("result").path("tools");
            assertEquals(1, tools.size());
            assertEquals("weather", tools.get(0).path("name").asText());

            JsonNode firstCall = response(send(first.mvc(), access, endpoint, call(3)).body()).path("result");
            assertFalse(firstCall.path("isError").asBoolean(true));
            assertEquals(json.readTree("{\"ok\":true}"),
                    json.readTree(firstCall.path("content").get(0).path("text").asText()));

            JsonNode denied = response(send(second.mvc(), access, endpoint, call(4)).body()).path("result");
            assertTrue(denied.path("isError").asBoolean(false));
            assertEquals("RATE_LIMITED", json.readTree(
                    denied.path("content").get(0).path("text").asText())
                    .path("error").path("category").asText());
            assertEquals(1, providerCalls.get());

            var audits = policies.listAudits(OWNER, RUNTIME_ID, 10, Optional.empty()).items();
            assertEquals(2, audits.size());
            assertEquals(Set.of(AuditStatus.SUCCEEDED, AuditStatus.RATE_LIMITED),
                    audits.stream().map(value -> value.status()).collect(java.util.stream.Collectors.toSet()));
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void cutsOverTwoWarmReplicasAndRollsBackOnlyAfterNewToolGrantsAreRevoked() throws Exception {
        RuntimeTool alpha = tool("alpha");
        RuntimeTool beta = tool("beta");
        var codec = new CanonicalRuntimeMetadataCodec();
        var sourceMetadata = codec.encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "a".repeat(64), List.of(alpha)));
        var targetMetadata = codec.encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "a".repeat(64), List.of(alpha, beta)));
        VersionedFixture fixture = seedVersioned(sourceMetadata, targetMetadata);
        ManagedRuntimeStore runtimeStore = new PostgresManagedRuntimeStore(dataSource);
        RuntimePolicyStore policies = new PostgresRuntimePolicyStore(dataSource);
        ManagedRuntimeGrant existingGrant = new ManagedRuntimeGrant(
                GRANT_ID, RUNTIME_ID, OWNER, "client-a", Set.of("alpha"), 60,
                NOW.minusSeconds(30), NOW.plusSeconds(1800), Optional.empty());
        assertTrue(policies.createGrant(
                existingGrant, new RuntimeTokenDigest(bytes(32, 0x21)),
                fixture.sourceCatalog(), sourceMetadata.checksum(), NOW));
        RuntimeTokenCodec tokens = tokens();
        ToolCatalogService catalogs = new ToolCatalogService(new PostgresToolCatalogStore(dataSource));
        AtomicInteger providerCalls = new AtomicInteger();
        Replica first = dynamicReplica(runtimeStore, catalogs, policies, tokens, providerCalls);
        Replica second = dynamicReplica(runtimeStore, catalogs, policies, tokens, providerCalls);
        String endpoint = "/mcp/" + RUNTIME_ID.value();

        try {
            assertEquals(Set.of("alpha"), toolNames(sendBearer(first.mvc(), endpoint, 10)));
            assertEquals(Set.of("alpha"), toolNames(sendBearer(second.mvc(), endpoint, 11)));

            ManagedRuntimeMigrationService migrations = new ManagedRuntimeMigrationService(
                    new PostgresToolCatalogStore(dataSource), runtimeStore,
                    new PostgresRuntimeCatalogTransitionStore(dataSource), Clock.fixed(NOW, ZoneOffset.UTC));
            var migrated = migrations.migrate(
                    OWNER, RUNTIME_ID, fixture.sourceCatalog(), fixture.targetCatalog(), targetMetadata.checksum());

            assertEquals(RUNTIME_ID, migrated.instance().id());
            assertEquals(Set.of("alpha", "beta"), toolNames(sendBearer(first.mvc(), endpoint, 12)));
            assertEquals(Set.of("alpha", "beta"), toolNames(sendBearer(second.mvc(), endpoint, 13)));
            assertEquals(Set.of("alpha"), policies.listGrants(OWNER, RUNTIME_ID).getFirst().allowedTools());
            assertEquals(fixture.credentialId(), runtimeStore.find(RUNTIME_ID).orElseThrow()
                    .credentialBindings().get("service_key"));
            assertEquals(7L, credentialVersion());

            RuntimeGrantId betaGrantId = new RuntimeGrantId(UUID.randomUUID());
            ManagedRuntimeGrant betaGrant = new ManagedRuntimeGrant(
                    betaGrantId, RUNTIME_ID, OWNER, "client-b", Set.of("beta"), 60,
                    NOW.minusSeconds(10), NOW.plusSeconds(1800), Optional.empty());
            assertTrue(policies.createGrant(
                    betaGrant, new RuntimeTokenDigest(bytes(32, 0x22)),
                    fixture.targetCatalog(), targetMetadata.checksum(), NOW));
            org.junit.jupiter.api.Assertions.assertThrows(
                    ManagedRuntimeMigrationService.CatalogMigrationBlocked.class,
                    () -> migrations.rollback(OWNER, RUNTIME_ID, fixture.targetCatalog()));
            assertEquals(fixture.targetCatalog(), runtimeStore.find(RUNTIME_ID).orElseThrow().instance().catalogId());
            assertEquals(1, migrations.history(OWNER, RUNTIME_ID, 10, Optional.empty()).items().size());

            assertTrue(policies.revokeGrant(OWNER, RUNTIME_ID, betaGrantId, NOW));
            var rolledBack = migrations.rollback(OWNER, RUNTIME_ID, fixture.targetCatalog());

            assertEquals(fixture.sourceCatalog(), rolledBack.instance().catalogId());
            assertEquals(Set.of("alpha"), toolNames(sendBearer(first.mvc(), endpoint, 14)));
            assertEquals(Set.of("alpha"), toolNames(sendBearer(second.mvc(), endpoint, 15)));
            assertEquals(List.of("ROLLBACK", "MIGRATION"), migrations.history(
                            OWNER, RUNTIME_ID, 10, Optional.empty()).items().stream()
                    .map(transition -> transition.kind().name()).toList());
            assertEquals(7L, credentialVersion());
        } finally {
            first.close();
            second.close();
        }
    }

    private Replica replica(
            RuntimeAccess access,
            io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact metadata,
            RuntimePolicyStore policies,
            AtomicInteger providerCalls) {
        ManagedToolExecutor executor = new ManagedToolExecutor((request, timeout) -> {
            providerCalls.incrementAndGet();
            return new ProviderCallResponse(200, Map.of("Content-Type", List.of("application/json")),
                    "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        }, new ManagedExecutionLimits(Duration.ofSeconds(2), 2, 4), policies,
                Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
        RuntimeCredentialResolver credentials = resolver();
        ManagedRuntimeBinding binding = new ManagedRuntimeBinding(access.instance(), metadata);
        ManagedExecutionContext context = new ManagedExecutionContext(access, binding, credentials);
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(value -> {
            JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(json);
            var transport = WebMvcStatelessServerTransport.builder().jsonMapper(mapper)
                    .messageEndpoint("/mcp/" + RUNTIME_ID.value()).build();
            var server = McpServer.sync(transport).jsonMapper(mapper)
                    .serverInfo("replica-test", "1")
                    .tools(new McpJavaSdkEmitter().emitStateless(List.of(tool()),
                            (name, arguments) -> executor.call(context, name, arguments)))
                    .build();
            return RuntimeServerHandle.stateless(value.instance(), transport, server);
        }, 8, Clock.fixed(NOW, ZoneOffset.UTC));
        MockMvc mvc = MockMvcBuilders.routerFunctions(new ManagedMcpRouter(registry)).build();
        return new Replica(mvc, registry, executor);
    }

    private Replica dynamicReplica(
            ManagedRuntimeStore runtimes,
            ToolCatalogService catalogs,
            RuntimePolicyStore policies,
            RuntimeTokenCodec tokens,
            AtomicInteger providerCalls) {
        ManagedToolExecutor executor = new ManagedToolExecutor((request, timeout) -> {
            providerCalls.incrementAndGet();
            return new ProviderCallResponse(200, Map.of("Content-Type", List.of("application/json")),
                    "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        }, new ManagedExecutionLimits(Duration.ofSeconds(2), 2, 4), policies,
                Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
        RuntimeCredentialResolver credentials = resolver();
        RuntimeAccessAuthenticator authenticator = new RuntimeAccessAuthenticator(
                runtimes, tokens, Clock.fixed(NOW, ZoneOffset.UTC), catalogs, policies);
        RuntimeServerHandleRegistry registry = new RuntimeServerHandleRegistry(access -> {
            var catalog = catalogs.require(access.instance().owner(), access.instance().catalogId());
            ManagedRuntimeBinding binding = new ManagedRuntimeBinding(access.instance(), catalog.metadata());
            ManagedExecutionContext context = new ManagedExecutionContext(access, binding, credentials);
            var visible = catalog.metadata().document().tools().stream()
                    .filter(value -> access.allowedTools().contains(value.name())).toList();
            JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(json);
            var transport = WebMvcStatelessServerTransport.builder().jsonMapper(mapper)
                    .messageEndpoint("/mcp/" + RUNTIME_ID.value()).build();
            var server = McpServer.sync(transport).jsonMapper(mapper)
                    .serverInfo("replica-test", "1")
                    .tools(new McpJavaSdkEmitter().emitStateless(visible,
                            (name, arguments) -> executor.call(context, name, arguments)))
                    .build();
            return RuntimeServerHandle.stateless(access.instance(), transport, server);
        }, 8, Clock.fixed(NOW, ZoneOffset.UTC));
        MockMvc mvc = MockMvcBuilders.routerFunctions(new ManagedMcpRouter(registry))
                .addFilters(new RuntimeBearerFilter(authenticator, registry::invalidate)).build();
        return new Replica(mvc, registry, executor);
    }

    private ManagedRuntimeInstance seed(
            io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact metadata) {
        UUID specificationId = UUID.fromString("40000000-0000-0000-0000-000000000001");
        UUID jobId = UUID.fromString("50000000-0000-0000-0000-000000000001");
        UUID catalogId = UUID.fromString("60000000-0000-0000-0000-000000000001");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Timestamp timestamp = Timestamp.from(NOW);
        jdbc.update("insert into account values (?, ?, ?, ?, ?)",
                OWNER.value(), "https://issuer.example", "subject", timestamp, timestamp);
        jdbc.update("""
                insert into specification values (?, ?, 'UPLOAD', ?, ?, 1, 'spec', 'READY', ?, ?)
                """, specificationId, OWNER.value(), "specifications/source", "c".repeat(64), timestamp, timestamp);
        jdbc.update("""
                insert into generation_job(
                    id, owner_account_id, specification_id, kind, operation, idempotency_key,
                    request_hash, request_snapshot, status, created_at, updated_at)
                values (?, ?, ?, 'GENERATION', 'generation', 'replica-test', ?, '{}'::jsonb, 'SUCCEEDED', ?, ?)
                """, jobId, OWNER.value(), specificationId, "d".repeat(64), timestamp, timestamp);
        jdbc.update("""
                insert into tool_catalog(
                    id, owner_account_id, generation_job_id, metadata_version,
                    specification_checksum, metadata_checksum, metadata_document,
                    tool_count, created_at)
                values (?, ?, ?, ?, ?, ?, ?, 1, ?)
                """, catalogId, OWNER.value(), jobId, RuntimeMetadataDocument.VERSION, "a".repeat(64),
                metadata.checksum(), new String(metadata.content(), StandardCharsets.UTF_8), timestamp);
        jdbc.update("insert into tool_catalog_entry values (?, 0, ?, ?, ?)",
                catalogId, "weather", "getWeather", new CanonicalRuntimeMetadataCodec().encodeTool(tool()));
        ManagedRuntimeInstance instance = new ManagedRuntimeInstance(
                RUNTIME_ID, OWNER, catalogId, metadata.checksum(), Optional.empty(),
                NOW.minusSeconds(60), NOW.plusSeconds(3600), Optional.empty());
        new PostgresManagedRuntimeStore(dataSource)
                .create(instance, new RuntimeTokenDigest(new byte[32]), Map.of());
        return instance;
    }

    private VersionedFixture seedVersioned(
            io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact sourceMetadata,
            io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact targetMetadata) {
        UUID specificationId = UUID.randomUUID();
        UUID sourceJob = UUID.randomUUID();
        UUID targetJob = UUID.randomUUID();
        UUID sourceCatalog = UUID.randomUUID();
        UUID targetCatalog = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Timestamp timestamp = Timestamp.from(NOW);
        jdbc.update("insert into account values (?, ?, ?, ?, ?)",
                OWNER.value(), "https://issuer.example", "subject", timestamp, timestamp);
        jdbc.update("""
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'UPLOAD', ?, ?, 1, 'spec', 'READY', ?, ?)
                """, specificationId, OWNER.value(), "specifications/source", "a".repeat(64), timestamp, timestamp);
        insertGeneration(jdbc, sourceJob, specificationId, Optional.empty());
        jdbc.update("""
                insert into tool_catalog(
                    id, owner_account_id, generation_job_id, metadata_version,
                    specification_checksum, metadata_checksum, metadata_document,
                    tool_count, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sourceCatalog, OWNER.value(), sourceJob, RuntimeMetadataDocument.VERSION, "a".repeat(64),
                sourceMetadata.checksum(), new String(sourceMetadata.content(), StandardCharsets.UTF_8),
                sourceMetadata.document().tools().size(), timestamp);
        insertEntries(jdbc, sourceCatalog, sourceMetadata.document().tools());

        insertGeneration(jdbc, targetJob, specificationId, Optional.of(sourceCatalog));
        jdbc.update("""
                insert into tool_catalog(
                    id, owner_account_id, generation_job_id, metadata_version,
                    specification_checksum, metadata_checksum, metadata_document,
                    tool_count, created_at, family_id, revision, predecessor_catalog_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 2, ?)
                """, targetCatalog, OWNER.value(), targetJob, RuntimeMetadataDocument.VERSION, "a".repeat(64),
                targetMetadata.checksum(), new String(targetMetadata.content(), StandardCharsets.UTF_8),
                targetMetadata.document().tools().size(), Timestamp.from(NOW.plusSeconds(1)),
                sourceCatalog, sourceCatalog);
        insertEntries(jdbc, targetCatalog, targetMetadata.document().tools());
        jdbc.update("""
                update tool_catalog_family
                   set head_catalog_id = ?, updated_at = ?
                 where id = ? and owner_account_id = ?
                """, targetCatalog, Timestamp.from(NOW.plusSeconds(1)), sourceCatalog, OWNER.value());

        jdbc.update("""
                insert into managed_credential(
                    id, owner_account_id, label, kind, credential_version, envelope_version, key_id,
                    wrapped_key_nonce, wrapped_key, payload_nonce, ciphertext, created_at, rotated_at)
                values (?, ?, 'provider', 'OPAQUE', 7, 1, 'key-1', ?, ?, ?, ?, ?, ?)
                """, credentialId, OWNER.value(), bytes(12, 1), bytes(48, 2), bytes(12, 3), bytes(32, 4),
                Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.minusSeconds(60)));
        ManagedRuntimeInstance instance = new ManagedRuntimeInstance(
                RUNTIME_ID, OWNER, sourceCatalog, sourceMetadata.checksum(), Optional.empty(),
                NOW.minusSeconds(60), NOW.plusSeconds(3600), Optional.empty());
        new PostgresManagedRuntimeStore(dataSource).create(
                instance, new RuntimeTokenDigest(new byte[32]),
                Map.of("service_key", new ManagedCredentialId(credentialId)));
        return new VersionedFixture(sourceCatalog, targetCatalog, new ManagedCredentialId(credentialId));
    }

    private void insertGeneration(
            JdbcTemplate jdbc,
            UUID jobId,
            UUID specificationId,
            Optional<UUID> predecessor) {
        jdbc.update("""
                insert into generation_job(
                    id, owner_account_id, specification_id, kind, operation, idempotency_key,
                    request_hash, request_snapshot, status, created_at, updated_at, predecessor_catalog_id)
                values (?, ?, ?, 'GENERATION', 'generation', ?, ?, '{}'::jsonb, 'SUCCEEDED', ?, ?, ?)
                """, jobId, OWNER.value(), specificationId, jobId.toString(), "d".repeat(64),
                Timestamp.from(NOW), Timestamp.from(NOW), predecessor.orElse(null));
    }

    private void insertEntries(JdbcTemplate jdbc, UUID catalogId, List<RuntimeTool> tools) {
        CanonicalRuntimeMetadataCodec codec = new CanonicalRuntimeMetadataCodec();
        for (int ordinal = 0; ordinal < tools.size(); ordinal++) {
            RuntimeTool value = tools.get(ordinal);
            jdbc.update("insert into tool_catalog_entry values (?, ?, ?, ?, ?)",
                    catalogId, ordinal, value.name(), value.operationId(), codec.encodeTool(value));
        }
    }

    private RuntimeCredentialResolver resolver() {
        ManagedRuntimeStore runtimes = new ManagedRuntimeStore() {
            @Override public void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest,
                    Map<String, io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId>
                            credentialBindings) {}
            @Override public Optional<StoredRuntime> find(RuntimeInstanceId id) { return Optional.empty(); }
            @Override public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant revokedAt) { return false; }
        };
        ManagedCredentialStore credentials = new ManagedCredentialStore() {
            @Override public void create(ManagedCredential value, ProtectedCredential protectedValue) {}
            @Override public boolean rotate(AccountId owner, ManagedCredentialId id, long version,
                    ManagedCredential value, ProtectedCredential protectedValue) { return false; }
            @Override public boolean revoke(AccountId owner, ManagedCredentialId id, Instant revokedAt) { return false; }
            @Override public Optional<StoredCredential> find(AccountId owner, ManagedCredentialId id) {
                return Optional.empty();
            }
            @Override public List<ManagedCredential> list(AccountId owner) { return List.of(); }
            @Override public long countActive(AccountId owner) { return 0; }
        };
        CredentialProtector protector = new CredentialProtector() {
            @Override public ProtectedCredential protect(AccountId owner, ManagedCredentialId id, long version,
                    CredentialSecret secret) { throw new UnsupportedOperationException(); }
            @Override public CredentialSecret reveal(AccountId owner, ManagedCredentialId id, long version,
                    ProtectedCredential value) { throw new UnsupportedOperationException(); }
        };
        return new RuntimeCredentialResolver(runtimes, credentials, protector);
    }

    private RuntimeTool tool() {
        return new RuntimeTool(
                "getWeather", "weather", "Get weather",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(), new RuntimeHttp(
                        HttpMethod.GET, "https://api.example", "/weather", List.of(), false, false),
                null, null, null, List.of());
    }

    private RuntimeTool tool(String name) {
        return new RuntimeTool(
                name + "Operation", name, "Invoke " + name,
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(), new RuntimeHttp(
                        HttpMethod.GET, "https://api.example", "/" + name, List.of(), false, false),
                null, null, null, List.of(new RuntimeCredential(
                        "service_key",
                        io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.HEADER,
                        "Authorization", true)));
    }

    private Exchange send(MockMvc mvc, RuntimeAccess access, String endpoint, String body) throws Exception {
        var response = mvc.perform(post(endpoint)
                        .requestAttr(RuntimeBearerFilter.RUNTIME_ACCESS, access)
                        .header("Accept", "application/json, text/event-stream")
                        .contentType("application/json").content(body))
                .andReturn().getResponse();
        return new Exchange(response.getStatus(), response.getHeader("Mcp-Session-Id"),
                response.getContentAsString(StandardCharsets.UTF_8));
    }

    private Exchange sendBearer(MockMvc mvc, String endpoint, int id) throws Exception {
        var response = mvc.perform(post(endpoint)
                        .header("Authorization", "Bearer g2s_rt_owner-token")
                        .header("Accept", "application/json, text/event-stream")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":" + id
                                + ",\"method\":\"tools/list\",\"params\":{}}"))
                .andReturn().getResponse();
        return new Exchange(response.getStatus(), response.getHeader("Mcp-Session-Id"),
                response.getContentAsString(StandardCharsets.UTF_8));
    }

    private Set<String> toolNames(Exchange exchange) throws Exception {
        assertEquals(200, exchange.status());
        JsonNode tools = response(exchange.body()).path("result").path("tools");
        java.util.Set<String> names = new java.util.TreeSet<>();
        tools.forEach(value -> names.add(value.path("name").asText()));
        return Set.copyOf(names);
    }

    private RuntimeTokenCodec tokens() {
        return new RuntimeTokenCodec() {
            @Override
            public io.gen2spring.mcp.application.managed.runtime.IssuedRuntimeToken issue() {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean matches(String presentedToken, RuntimeTokenDigest persistedDigest) {
                return "g2s_rt_owner-token".equals(presentedToken)
                        && java.util.Arrays.equals(new byte[32], persistedDigest.value());
            }

            @Override
            public RuntimeTokenDigest digest(String presentedToken) {
                return new RuntimeTokenDigest(bytes(32, 0x22));
            }
        };
    }

    private long credentialVersion() {
        return new JdbcTemplate(dataSource).queryForObject("""
                select credential_version
                  from managed_runtime_credential_binding
                 where runtime_id = ? and credential_slot = 'service_key'
                """, Long.class, RUNTIME_ID.value());
    }

    private byte[] bytes(int size, int value) {
        byte[] result = new byte[size];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private String call(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"weather\",\"arguments\":{}}}";
    }

    private JsonNode response(String body) throws Exception {
        return json.readTree(body);
    }

    private record Exchange(int status, String sessionId, String body) {}

    private record VersionedFixture(
            UUID sourceCatalog,
            UUID targetCatalog,
            ManagedCredentialId credentialId) {}

    private record Replica(MockMvc mvc, RuntimeServerHandleRegistry registry, ManagedToolExecutor executor)
            implements AutoCloseable {
        @Override public void close() {
            try { registry.close(); } finally { executor.close(); }
        }
    }
}
