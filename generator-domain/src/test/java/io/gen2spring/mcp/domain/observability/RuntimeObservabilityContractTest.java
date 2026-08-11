package io.gen2spring.mcp.domain.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeObservabilityContractTest {
    @Test
    void exposesTheCanonicalRuntimeTelemetryLiteralsAndAllowedMappings() {
        assertEquals("gen2spring.runtime.mcp.tool.call", RuntimeObservabilityContract.MCP_TOOL_CALL_OBSERVATION);
        assertEquals("gen2spring.runtime.provider.request", RuntimeObservabilityContract.PROVIDER_REQUEST_OBSERVATION);
        assertEquals("gen2spring.runtime.provider.response.bytes", RuntimeObservabilityContract.PROVIDER_RESPONSE_BYTES_METER);
        assertEquals("gen2spring.runtime.provider.executor.active", RuntimeObservabilityContract.PROVIDER_EXECUTOR_ACTIVE_METER);
        assertEquals("gen2spring.runtime.provider.executor.queued", RuntimeObservabilityContract.PROVIDER_EXECUTOR_QUEUED_METER);
        assertEquals(List.of("target.profile", "outcome", "error.category"),
                RuntimeObservabilityContract.MCP_TOOL_CALL_TAGS);
        assertEquals(List.of("target.profile", "outcome", "error.category", "http.status.class"),
                RuntimeObservabilityContract.PROVIDER_REQUEST_TAGS);
        assertEquals(List.of("target.profile", "http.status.class"),
                RuntimeObservabilityContract.PROVIDER_RESPONSE_BYTES_TAGS);
        assertEquals(List.of("target.profile"), RuntimeObservabilityContract.PROVIDER_EXECUTOR_GAUGE_TAGS);
        assertEquals(List.of(
                        "gen2spring.tool.name",
                        "gen2spring.operation.id",
                        "http.request.method",
                        "http.response.status_code"),
                RuntimeObservabilityContract.TRACE_ONLY_ATTRIBUTES);
        assertEquals(List.of("success", "expected_error", "internal_error", "fatal"),
                RuntimeObservabilityContract.OUTCOME_VALUES);
        assertEquals(List.of(
                        "none",
                        "provider_business",
                        "upstream_client",
                        "upstream_server",
                        "upstream_timeout",
                        "upstream_unavailable",
                        "upstream_protocol",
                        "local_resource",
                        "argument_conversion",
                        "result_conversion",
                        "tool_execution",
                        "unexpected_runtime",
                        "fatal"),
                RuntimeObservabilityContract.ERROR_CATEGORY_VALUES);
        assertEquals(List.of("2xx", "4xx", "5xx", "other", "none"),
                RuntimeObservabilityContract.HTTP_STATUS_CLASS_VALUES);

        assertTrue(RuntimeObservabilityContract.isAllowedOutcomeCategory("success", "none"));
        assertTrue(RuntimeObservabilityContract.isAllowedOutcomeCategory("expected_error", "upstream_timeout"));
        assertTrue(RuntimeObservabilityContract.isAllowedOutcomeCategory("internal_error", "argument_conversion"));
        assertTrue(RuntimeObservabilityContract.isAllowedOutcomeCategory("fatal", "fatal"));
        assertFalse(RuntimeObservabilityContract.isAllowedOutcomeCategory("success", "fatal"));
        assertFalse(RuntimeObservabilityContract.isAllowedOutcomeCategory("expected_error", "tool_execution"));
        assertFalse(RuntimeObservabilityContract.isAllowedOutcomeCategory("internal_error", "upstream_server"));
        assertFalse(RuntimeObservabilityContract.isAllowedOutcomeCategory(null, "none"));
    }

    @Test
    void recognizesOnlyReservedPropagationHeadersCaseInsensitively() {
        for (String header : List.of(
                "traceparent", "TrAcEsTaTe", "BAGGAGE", "b3", "X-B3-TraceId", "x-b3-custom")) {
            assertTrue(RuntimeObservabilityContract.isReservedPropagationHeader(header), header);
        }

        for (String header : List.of("authorization", "x-request-id", "x-b3", "x-b30-traceid", "")) {
            assertFalse(RuntimeObservabilityContract.isReservedPropagationHeader(header), header);
        }
        assertFalse(RuntimeObservabilityContract.isReservedPropagationHeader(null));
    }
}
