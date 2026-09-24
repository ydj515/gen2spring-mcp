package io.gen2spring.mcp.application.managed.execution.service;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore;
import io.gen2spring.mcp.application.hosted.catalog.result.CatalogCursor;
import io.gen2spring.mcp.application.hosted.catalog.result.CatalogDetails;
import io.gen2spring.mcp.application.hosted.catalog.result.CatalogSummary;
import io.gen2spring.mcp.application.hosted.catalog.result.ToolDetails;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.application.managed.audit.result.AuditCursor;
import io.gen2spring.mcp.application.managed.audit.result.AuditPage;
import io.gen2spring.mcp.application.managed.credential.CredentialSecret;
import io.gen2spring.mcp.application.managed.credential.ProtectedCredential;
import io.gen2spring.mcp.application.managed.credential.port.out.CredentialProtector;
import io.gen2spring.mcp.application.managed.credential.port.out.ManagedCredentialStore;
import io.gen2spring.mcp.application.managed.credential.service.RuntimeCredentialResolver;
import io.gen2spring.mcp.application.managed.execution.ManagedExecutionLimits;
import io.gen2spring.mcp.application.managed.execution.ManagedRuntimeBinding;
import io.gen2spring.mcp.application.managed.execution.ManagedToolResult;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import io.gen2spring.mcp.application.managed.execution.port.out.ProviderCallClient;
import io.gen2spring.mcp.application.managed.execution.port.out.ManagedExecutionTasks;
import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore.StoredGrant;
import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;
import io.gen2spring.mcp.application.managed.runtime.port.out.ManagedRuntimeStore;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.platform.runtime.ToolExecutionAudit;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ManagedToolExecutorTest {
    private static final java.time.Clock TEST_CLOCK = java.time.Clock.fixed(
            Instant.parse("2026-08-21T00:01:00Z"), java.time.ZoneOffset.UTC);

    @Test
    void opensOnlyAllowedToolsAndKeepsAuditingAndAccessChecksOnInvocation() {
        RuntimeTool allowed = tool(null);
        RuntimeTool hidden = new RuntimeTool(
                "hiddenOperation", "hidden_tool", "Hidden Tool",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(GET, "https://api.example", "/hidden", List.of(), false, false),
                null, null, null, List.of());
        ManagedRuntimeBinding binding = binding(allowed, hidden);
        PolicyStore policy = new PolicyStore(true, true);
        AtomicInteger calls = new AtomicInteger();
        try (var executor = new ManagedToolExecutor(new TestManagedExecutionTasks(), (request, timeout) -> {
            calls.incrementAndGet();
            return response(200, "{}");
        }, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1), policy, TEST_CLOCK, UUID::randomUUID)) {
            var service = new ManagedToolSessionService(
                    catalogs(binding, Optional.of(catalogDetails(binding))), executor, resolver(binding.instance()));
            var session = service.open(access(binding, Set.of(allowed.name())));
            assertEquals(List.of(allowed), session.tools());
            assertThrows(UnsupportedOperationException.class, () -> session.tools().clear());
            assertEquals(0, calls.get());
            assertFalse(session.handler().call(allowed.name(), Map.of()).error());
            assertEquals(List.of("audit-start", "rate", "audit-complete:SUCCEEDED"), policy.events);
            assertThrows(ManagedToolExecutor.ManagedToolRequestInvalid.class,
                    () -> session.handler().call(hidden.name(), Map.of()));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void refusesMissingOrChangedCatalogBeforeCreatingASession() {
        ManagedRuntimeBinding binding = binding(tool(null));
        ManagedRuntimeBinding changed = binding(tool(new RetryPolicy(List.of(503), false, 1, 1, 1, false)));
        try (var executor = new ManagedToolExecutor(new TestManagedExecutionTasks(), (request, timeout) -> {
            throw new AssertionError("No provider call should occur while opening a session");
        }, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1), TEST_CLOCK)) {
            var missing = new ManagedToolSessionService(
                    catalogs(binding, Optional.empty()), executor, resolver(binding.instance()));
            assertThrows(ToolCatalogService.ToolCatalogNotFound.class,
                    () -> missing.open(access(binding, Set.of("managed_tool"))));
            var drifted = new ManagedToolSessionService(
                    catalogs(binding, Optional.of(catalogDetails(changed))), executor, resolver(binding.instance()));
            assertThrows(IllegalArgumentException.class,
                    () -> drifted.open(access(binding, Set.of("managed_tool"))));
        }
    }

    @Test
    void mapsWorkCancelledByInfrastructureToLocalResource() {
        ManagedExecutionTasks tasks = new ManagedExecutionTasks() {
            @Override
            public Future<ManagedToolResult> submit(Callable<ManagedToolResult> task) {
                var cancelled = new CompletableFuture<ManagedToolResult>();
                cancelled.cancel(true);
                return cancelled;
            }

            @Override
            public void close() {}
        };
        RuntimeTool tool = tool(null);
        try (var executor = new ManagedToolExecutor(tasks, (request, timeout) -> {
            throw new AssertionError("Cancelled work must not reach the provider");
        }, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1), TEST_CLOCK)) {
            ManagedToolResult result = executor.call(binding(tool), tool.name(), Map.of());
            assertEquals(ManagedToolResult.ErrorCategory.LOCAL_RESOURCE, result.category());
        }
    }

    @Test
    void mapsCapacityRejectionAndClosesInjectedExecutionResources() {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        ManagedExecutionTasks tasks = new ManagedExecutionTasks() {
            @Override
            public Future<ManagedToolResult> submit(Callable<ManagedToolResult> task) {
                throw new RejectedExecutionException();
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        RuntimeTool tool = tool(null);
        try (var executor = new ManagedToolExecutor(tasks, (request, timeout) -> {
            calls.incrementAndGet();
            return response(200, "{}");
        }, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1), TEST_CLOCK)) {
            ManagedToolResult result = executor.call(binding(tool), tool.name(), Map.of());
            assertEquals(ManagedToolResult.ErrorCategory.LOCAL_RESOURCE, result.category());
            assertEquals(0, calls.get());
        }
        assertEquals(1, closes.get());
    }

    @Test
    void executesThePinnedToolAndRetriesOnlyConfiguredStatuses() {
        AtomicInteger calls = new AtomicInteger();
        ProviderCallClient client = (request, timeout) -> calls.incrementAndGet() == 1
                ? response(503, "{\"busy\":true}") : response(200, "{\"ok\":true}");
        RuntimeTool tool = tool(new RetryPolicy(List.of(503), false, 1, 1, 1, false));

        try (ManagedToolExecutor executor = new ManagedToolExecutor(new TestManagedExecutionTasks(),
                client, new ManagedExecutionLimits(Duration.ofSeconds(2), 1, 1), TEST_CLOCK)) {
            ManagedToolResult result = executor.call(binding(tool), tool.name(), Map.of());
            assertFalse(result.error());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void mapsTimeoutAndSaturationToProviderSafeResultsWithoutLateMutation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProviderCallClient client = (request, timeout) -> {
            entered.countDown();
            try {
                release.await();
                return response(200, "{\"late\":true}");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw ProviderCallClient.ProviderCallFailure.timeout();
            }
        };
        RuntimeTool tool = tool(null);
        try (ManagedToolExecutor executor = new ManagedToolExecutor(new TestManagedExecutionTasks(),
                client, new ManagedExecutionLimits(Duration.ofMillis(50), 1, 1), TEST_CLOCK)) {
            ManagedToolResult timedOut = executor.call(binding(tool), tool.name(), Map.of());
            assertTrue(timedOut.error());
            assertEquals(ManagedToolResult.ErrorCategory.UPSTREAM_TIMEOUT, timedOut.category());
            release.countDown();
            Thread.sleep(20);
            assertEquals(ManagedToolResult.ErrorCategory.UPSTREAM_TIMEOUT, timedOut.category());
        } finally {
            release.countDown();
        }
    }

    @Test
    void separatesUnexpectedInternalFailuresAndPreservesFatalErrorIdentity() {
        RuntimeTool tool = tool(null);
        ProviderCallClient internal = (request, timeout) -> {
            throw new IllegalArgumentException("private-marker");
        };
        try (ManagedToolExecutor executor = new ManagedToolExecutor(new TestManagedExecutionTasks(),
                internal, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1), TEST_CLOCK)) {
            ManagedToolExecutor.ManagedToolInternalFailure failure = assertThrows(
                    ManagedToolExecutor.ManagedToolInternalFailure.class,
                    () -> executor.call(binding(tool), tool.name(), Map.of()));
            assertEquals("Managed Tool execution failed", failure.getMessage());
            assertEquals("IllegalArgumentException", failure.failureType());
            assertFalse(failure.toString().contains("private-marker"));
        }

        AssertionError fatal = new AssertionError("fatal-marker");
        ProviderCallClient fatalClient = (request, timeout) -> {
            throw fatal;
        };
        try (ManagedToolExecutor executor = new ManagedToolExecutor(new TestManagedExecutionTasks(),
                fatalClient, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1), TEST_CLOCK)) {
            assertSame(fatal, assertThrows(AssertionError.class,
                    () -> executor.call(binding(tool), tool.name(), Map.of())));
        }
    }

    @Test
    void rejectsUnknownToolsAndChecksumDriftBeforeProviderEgress() {
        AtomicInteger calls = new AtomicInteger();
        ProviderCallClient client = (request, timeout) -> {
            calls.incrementAndGet();
            return response(200, "{}");
        };
        RuntimeTool tool = tool(null);
        try (ManagedToolExecutor executor = new ManagedToolExecutor(new TestManagedExecutionTasks(),
                client, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1), TEST_CLOCK)) {
            assertThrows(ManagedToolExecutor.ManagedToolRequestInvalid.class,
                    () -> executor.call(binding(tool), "missing", Map.of()));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void aggregatesBoundedPaginationUsingOnlyTheRuntimeOwnedCursor() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ProviderCallClient client = (request, timeout) -> {
            int call = calls.getAndIncrement();
            assertEquals(call == 0 ? "cursor=start" : "cursor=n2", request.uri().getRawQuery());
            return response(200, call == 0
                    ? "{\"items\":[{\"id\":1}],\"next\":\"n2\"}"
                    : "{\"items\":[{\"id\":2}],\"next\":null}");
        };
        RuntimeTool tool = tool(null, new PaginationPolicy("cursor", "start", "/items", "/next", 3, 10));

        try (ManagedToolExecutor executor = new ManagedToolExecutor(new TestManagedExecutionTasks(),
                client, new ManagedExecutionLimits(Duration.ofSeconds(2), 1, 1), TEST_CLOCK)) {
            ManagedToolResult result = executor.call(binding(tool), tool.name(), Map.of());
            var json = new ObjectMapper().readTree(result.json());
            assertFalse(result.error());
            assertEquals(2, json.path("items").size());
            assertTrue(json.path("next").isNull());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void passesOnlyTheRemainingEndToEndDeadlineToRetries() {
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.CopyOnWriteArrayList<Duration> timeouts = new java.util.concurrent.CopyOnWriteArrayList<>();
        ProviderCallClient client = (request, timeout) -> {
            timeouts.add(timeout);
            return calls.getAndIncrement() == 0
                    ? response(503, "{\"busy\":true}")
                    : response(200, "{\"ok\":true}");
        };
        RuntimeTool tool = tool(new RetryPolicy(List.of(503), false, 1, 20, 20, false));

        try (ManagedToolExecutor executor = new ManagedToolExecutor(new TestManagedExecutionTasks(),
                client, new ManagedExecutionLimits(Duration.ofSeconds(2), 1, 1), TEST_CLOCK)) {
            ManagedToolResult result = executor.call(binding(tool), tool.name(), Map.of());

            assertFalse(result.error());
            assertEquals(2, calls.get());
            assertTrue(timeouts.get(1).compareTo(timeouts.get(0)) < 0);
        }
    }

    @Test
    void mapsBackoffInterruptionToLocalResourceWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch attempted = new CountDownLatch(1);
        ProviderCallClient client = (request, timeout) -> {
            worker.set(Thread.currentThread());
            calls.incrementAndGet();
            attempted.countDown();
            return response(503, "{\"busy\":true}");
        };
        RuntimeTool tool = tool(new RetryPolicy(List.of(503), true, 1, 5_000, 5_000, false));

        try (ManagedToolExecutor executor = new ManagedToolExecutor(new TestManagedExecutionTasks(),
                client, new ManagedExecutionLimits(Duration.ofSeconds(10), 1, 1), TEST_CLOCK)) {
            CompletableFuture<ManagedToolResult> call = CompletableFuture.supplyAsync(
                    () -> executor.call(binding(tool), tool.name(), Map.of()));
            assertTrue(attempted.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            worker.get().interrupt();

            ManagedToolResult result = call.get(2, TimeUnit.SECONDS);
            assertTrue(result.error());
            assertEquals(ManagedToolResult.ErrorCategory.LOCAL_RESOURCE, result.category());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void enforcesVisibilityAuditThenRateAndDoesNotResolveOrCallWhenDenied() throws Exception {
        RuntimeTool tool = tool(null);
        ManagedRuntimeBinding binding = binding(tool);
        RuntimeAccess access = access(binding, Set.of(tool.name()));
        PolicyStore policy = new PolicyStore(false, true);
        AtomicInteger providerCalls = new AtomicInteger();
        ManagedExecutionContext context = new ManagedExecutionContext(
                access, binding, resolver(binding.instance()));

        try (ManagedToolExecutor executor = new ManagedToolExecutor(new TestManagedExecutionTasks(),
                (request, timeout) -> {
                    providerCalls.incrementAndGet();
                    return response(200, "{}");
                }, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1), policy,
                java.time.Clock.fixed(Instant.parse("2026-08-21T00:01:00Z"), java.time.ZoneOffset.UTC),
                () -> UUID.fromString("77777777-7777-7777-7777-777777777777"))) {
            ManagedToolResult denied = executor.call(context, tool.name(), Map.of());
            assertTrue(denied.error());
            assertEquals(ManagedToolResult.ErrorCategory.RATE_LIMITED, denied.category());
            assertTrue(new ObjectMapper().readTree(denied.json()).path("error").path("retryable").booleanValue());
            assertEquals(List.of("audit-start", "rate", "audit-complete:RATE_LIMITED"), policy.events);
            assertEquals(0, providerCalls.get());

            policy.events.clear();
            assertThrows(ManagedToolExecutor.ManagedToolRequestInvalid.class,
                    () -> executor.call(new ManagedExecutionContext(
                            access(binding, Set.of("other_tool")), binding, resolver(binding.instance())),
                            tool.name(), Map.of()));
            assertEquals(List.of(), policy.events);
        }
    }

    @Test
    void doesNotRetryWhenAuditCompletionFailsAfterOneProviderCall() {
        RuntimeTool tool = tool(null);
        ManagedRuntimeBinding binding = binding(tool);
        PolicyStore policy = new PolicyStore(true, false);
        AtomicInteger providerCalls = new AtomicInteger();
        try (ManagedToolExecutor executor = new ManagedToolExecutor(new TestManagedExecutionTasks(),
                (request, timeout) -> {
                    providerCalls.incrementAndGet();
                    return response(200, "{}");
                }, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1), policy,
                java.time.Clock.fixed(Instant.parse("2026-08-21T00:01:00Z"), java.time.ZoneOffset.UTC),
                UUID::randomUUID)) {
            assertThrows(ManagedToolExecutor.ManagedToolInternalFailure.class, () -> executor.call(
                    new ManagedExecutionContext(access(binding, Set.of(tool.name())), binding, resolver(binding.instance())),
                    tool.name(), Map.of()));
            assertEquals(1, providerCalls.get());
            assertEquals(List.of("audit-start", "rate", "audit-complete:SUCCEEDED"), policy.events);
        }
    }

    @Test
    void preservesThePrimaryInternalFailureWhenAuditCompletionAlsoFails() {
        RuntimeTool tool = tool(null);
        ManagedRuntimeBinding binding = binding(tool);
        PolicyStore policy = new PolicyStore(true, false);
        try (ManagedToolExecutor executor = new ManagedToolExecutor(new TestManagedExecutionTasks(),
                (request, timeout) -> { throw new IllegalArgumentException("private-provider-marker"); },
                new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1), policy,
                java.time.Clock.fixed(Instant.parse("2026-08-21T00:01:00Z"), java.time.ZoneOffset.UTC),
                UUID::randomUUID)) {
            ManagedToolExecutor.ManagedToolInternalFailure failure = assertThrows(
                    ManagedToolExecutor.ManagedToolInternalFailure.class,
                    () -> executor.call(new ManagedExecutionContext(
                                    access(binding, Set.of(tool.name())), binding, resolver(binding.instance())),
                            tool.name(), Map.of()));

            assertEquals("IllegalArgumentException", failure.failureType());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals("AuditCompletionFailed",
                    ((ManagedToolExecutor.ManagedToolInternalFailure) failure.getSuppressed()[0]).failureType());
            assertFalse(failure.toString().contains("private-provider-marker"));
        }
    }

    private ManagedRuntimeBinding binding(RuntimeTool... tools) {
        var artifact = new CanonicalRuntimeMetadataCodec().encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "b".repeat(64), List.of(tools)));
        ManagedRuntimeInstance instance = new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.randomUUID()), new AccountId(UUID.randomUUID()), UUID.randomUUID(),
                artifact.checksum(), Optional.empty(), Instant.parse("2026-08-21T00:00:00Z"),
                Instant.parse("2026-08-22T00:00:00Z"), Optional.empty());
        return new ManagedRuntimeBinding(instance, artifact);
    }

    private CatalogDetails catalogDetails(ManagedRuntimeBinding binding) {
        var metadata = binding.metadata();
        return new CatalogDetails(new CatalogSummary(
                binding.instance().catalogId(), new JobId(UUID.randomUUID()), metadata.document().metadataVersion(),
                metadata.checksum(), metadata.document().tools().size(), TEST_CLOCK.instant()),
                metadata.document().specificationChecksum(), metadata);
    }

    private ToolCatalogService catalogs(ManagedRuntimeBinding binding, Optional<CatalogDetails> result) {
        return new ToolCatalogService(new ToolCatalogStore() {
            @Override
            public List<CatalogSummary> list(AccountId owner, int limit, Optional<CatalogCursor> cursor) {
                throw new AssertionError("Session preparation must load its pinned catalog");
            }

            @Override
            public Optional<CatalogDetails> find(AccountId owner, UUID id) {
                assertEquals(binding.instance().owner(), owner);
                assertEquals(binding.instance().catalogId(), id);
                return result;
            }

            @Override
            public Optional<ToolDetails> findTool(AccountId owner, UUID id, String name) {
                throw new AssertionError("Session preparation must use one catalog snapshot");
            }
        });
    }

    private RuntimeAccess access(ManagedRuntimeBinding binding, Set<String> tools) {
        return new RuntimeAccess(
                binding.instance(), Optional.of(new RuntimeGrantId(UUID.randomUUID())), "client-a",
                tools, 5, false, "c".repeat(64), binding.instance().expiresAt());
    }

    private RuntimeCredentialResolver resolver(ManagedRuntimeInstance instance) {
        ManagedRuntimeStore runtimes = new ManagedRuntimeStore() {
            @Override public void create(ManagedRuntimeInstance value, RuntimeTokenDigest digest,
                    Map<String, ManagedCredentialId> credentialBindings) {}
            @Override public Optional<StoredRuntime> find(RuntimeInstanceId id) {
                return Optional.of(new StoredRuntime(instance, new RuntimeTokenDigest(new byte[32])));
            }
            @Override public boolean revoke(AccountId owner, RuntimeInstanceId id, Instant at) { return false; }
        };
        ManagedCredentialStore credentials = new ManagedCredentialStore() {
            @Override public void create(ManagedCredential value, ProtectedCredential protectedValue) {}
            @Override public boolean rotate(AccountId owner, ManagedCredentialId id, long version,
                    ManagedCredential value, ProtectedCredential protectedValue) { return false; }
            @Override public boolean revoke(AccountId owner, ManagedCredentialId id, Instant at) { return false; }
            @Override public Optional<StoredCredential> find(AccountId owner, ManagedCredentialId id) { return Optional.empty(); }
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

    private static final class PolicyStore implements RuntimePolicyStore {
        private final boolean rate;
        private final boolean completion;
        private final List<String> events = new java.util.ArrayList<>();
        private PolicyStore(boolean rate, boolean completion) {
            this.rate = rate;
            this.completion = completion;
        }
        @Override public boolean createGrant(
                ManagedRuntimeGrant grant, RuntimeTokenDigest digest, UUID expectedCatalogId,
                String expectedCatalogChecksum, Instant observedAt) { return true; }
        @Override public Optional<StoredGrant> authenticateGrant(RuntimeInstanceId runtimeId, RuntimeTokenDigest digest) {
            return Optional.empty();
        }
        @Override public List<ManagedRuntimeGrant> listGrants(AccountId owner, RuntimeInstanceId runtimeId) {
            return List.of();
        }
        @Override public boolean revokeGrant(AccountId owner, RuntimeInstanceId runtimeId,
                RuntimeGrantId grantId, Instant revokedAt) { return false; }
        @Override public boolean acquireRate(RuntimeInstanceId runtimeId, Optional<RuntimeGrantId> grantId, int limit) {
            events.add("rate");
            return rate;
        }
        @Override public void startAudit(ToolExecutionAudit audit) { events.add("audit-start"); }
        @Override public boolean completeAudit(ToolExecutionAudit audit) {
            events.add("audit-complete:" + audit.status());
            return completion;
        }
        @Override public AuditPage listAudits(AccountId owner, RuntimeInstanceId runtimeId, int limit,
                Optional<AuditCursor> cursor) { return new AuditPage(List.of(), Optional.empty()); }
    }

    private RuntimeTool tool(RetryPolicy retry) {
        return tool(retry, null);
    }

    private RuntimeTool tool(RetryPolicy retry, PaginationPolicy pagination) {
        return new RuntimeTool(
                "operation", "managed_tool", "Managed Tool",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(GET, "https://api.example", "/items", List.of(), false, false),
                null, retry, pagination, List.of());
    }

    private ProviderCallResponse response(int status, String body) {
        return new ProviderCallResponse(
                status, Map.of("Content-Type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
