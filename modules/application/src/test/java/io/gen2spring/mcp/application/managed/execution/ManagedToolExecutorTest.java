package io.gen2spring.mcp.application.managed.execution;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ManagedToolExecutorTest {
    @Test
    void executesThePinnedToolAndRetriesOnlyConfiguredStatuses() {
        AtomicInteger calls = new AtomicInteger();
        ProviderCallClient client = (request, timeout) -> calls.incrementAndGet() == 1
                ? response(503, "{\"busy\":true}") : response(200, "{\"ok\":true}");
        RuntimeTool tool = tool(new RetryPolicy(List.of(503), false, 1, 1, 1, false));

        try (ManagedToolExecutor executor = new ManagedToolExecutor(
                client, new ManagedExecutionLimits(Duration.ofSeconds(2), 1, 1))) {
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
        try (ManagedToolExecutor executor = new ManagedToolExecutor(
                client, new ManagedExecutionLimits(Duration.ofMillis(50), 1, 1))) {
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
        try (ManagedToolExecutor executor = new ManagedToolExecutor(
                internal, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1))) {
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
        try (ManagedToolExecutor executor = new ManagedToolExecutor(
                fatalClient, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1))) {
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
        try (ManagedToolExecutor executor = new ManagedToolExecutor(
                client, new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1))) {
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

        try (ManagedToolExecutor executor = new ManagedToolExecutor(
                client, new ManagedExecutionLimits(Duration.ofSeconds(2), 1, 1))) {
            ManagedToolResult result = executor.call(binding(tool), tool.name(), Map.of());
            var json = new ObjectMapper().readTree(result.json());
            assertFalse(result.error());
            assertEquals(2, json.path("items").size());
            assertTrue(json.path("next").isNull());
            assertEquals(2, calls.get());
        }
    }

    private ManagedRuntimeBinding binding(RuntimeTool tool) {
        var artifact = new CanonicalRuntimeMetadataCodec().encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "b".repeat(64), List.of(tool)));
        ManagedRuntimeInstance instance = new ManagedRuntimeInstance(
                new RuntimeInstanceId(UUID.randomUUID()), new AccountId(UUID.randomUUID()), UUID.randomUUID(),
                artifact.checksum(), Optional.empty(), Instant.parse("2026-08-21T00:00:00Z"),
                Instant.parse("2026-08-22T00:00:00Z"), Optional.empty());
        return new ManagedRuntimeBinding(instance, artifact);
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
