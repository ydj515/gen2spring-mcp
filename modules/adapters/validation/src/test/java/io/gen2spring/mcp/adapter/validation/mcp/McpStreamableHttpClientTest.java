package io.gen2spring.mcp.adapter.validation.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.adapter.validation.mcp.McpStreamableHttpClient.McpStage;
import io.gen2spring.mcp.adapter.validation.support.McpTestServer.Scenario;
import io.gen2spring.mcp.adapter.validation.support.McpTestServer;
import io.gen2spring.mcp.application.generation.validation.ExpectedTool;
import io.gen2spring.mcp.application.generation.validation.ExpectedToolCall;
import io.gen2spring.mcp.application.generation.validation.ExpectedUpstreamResponse;
import io.gen2spring.mcp.domain.tool.OutputKind;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpStreamableHttpClientTest {
    private static final Map<String, ExpectedTool> EXPECTED = Map.of(
            "kma_weather_get_forecast",
            new ExpectedTool(
                    "Get the public weather forecast for a grid location.",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("nx", Map.of(
                                    "type", "integer",
                                    "format", "int32",
                                    "description", "Grid x coordinate")),
                            "required", List.of("nx"))));
    private static final ExpectedToolCall EXPECTED_CALL = new ExpectedToolCall(
            new ToolDefinition(
                    "getForecast",
                    "kma_weather_get_forecast",
                    "Get the public weather forecast for a grid location.",
                    List.of(),
                    null,
                    List.of(),
                    OutputKind.GENERIC_JSON),
            Map.of("nx", 60, "ny", 127));

    private final McpStreamableHttpClient client = new McpStreamableHttpClient(Duration.ofSeconds(2), 64 * 1024);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void selectsTheMatchingSseResponseAfterNotificationsAndUnrelatedMessages() throws Exception {
        try (var server = McpTestServer.start(Scenario.SSE_MULTIPLE_EVENTS)) {
            assertEquals(EXPECTED.keySet(), client.validate(server.uri(), EXPECTED).toolNames());
        }
    }

    @Test
    void preservesSseErrorsAndRejectsStreamsWithoutAMatchingResponse() throws Exception {
        for (Scenario scenario : List.of(Scenario.SSE_MATCHING_ERROR, Scenario.SSE_NO_MATCH, Scenario.SSE_NO_DATA)) {
            try (var server = McpTestServer.start(scenario)) {
                var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                        () -> client.validate(server.uri(), EXPECTED));
                assertEquals(McpStage.TOOLS_LIST, failure.stage());
                assertFalse(failure.getMessage().contains("private"));
                if (scenario == Scenario.SSE_MATCHING_ERROR) {
                    assertEquals("MCP JSON-RPC response reported an error", failure.getMessage());
                }
                if (scenario == Scenario.SSE_NO_DATA) {
                    assertEquals("MCP SSE response contains no data event", failure.getMessage());
                }
            }
        }
    }

    @Test
    void invokesTheExpectedToolWithTheInitializedSessionAndValidatesTheMockResult() throws Exception {
        List<McpStage> startedStages = new ArrayList<>();
        try (var server = McpTestServer.startWithJsonInitializeAndSseToolsList()) {
            var result = client.validate(server.uri(), EXPECTED, EXPECTED_CALL, startedStages::add);

            assertEquals("tools/call", server.request(3).path("method").textValue());
            assertEquals("kma_weather_get_forecast",
                    server.request(3).path("params").path("name").textValue());
            assertEquals(EXPECTED_CALL.arguments(),
                    mapper.convertValue(server.request(3).path("params").path("arguments"), Map.class));
            assertEquals(server.sessionId(), server.requestHeader(3, "Mcp-Session-Id"));
            assertTrue(result.toolsCallDurationMillis() >= 0);
            assertEquals(List.of(McpStage.INITIALIZE, McpStage.TOOLS_LIST, McpStage.TOOL_CALL), startedStages);
        }
    }

    @Test
    void validatesTheExpectedResultInsteadOfTheLegacyHardCodedPayload() throws Exception {
        ExpectedToolCall call = expectedCall(Map.of(
                "data", List.of(Map.of("id", 1)),
                "provider", Map.of("code", "00")));

        try (var server = McpTestServer.startWithToolResult(call.expectedResult(), false)) {
            assertDoesNotThrow(() -> client.validate(server.uri(), EXPECTED, call));
        }
    }

    @Test
    void treatsEquivalentJsonNumbersAsEqualInExpectedToolResults() throws Exception {
        ExpectedToolCall call = expectedCall(Map.of("data", Map.of("value", new BigDecimal("9E+1"))));

        try (var server = McpTestServer.startWithToolResult(
                Map.of("data", Map.of("value", 90)), false)) {
            assertDoesNotThrow(() -> client.validate(server.uri(), EXPECTED, call));
        }
    }

    @Test
    void rejectsAnActualExpectedResultMismatchAtTheToolCallStageWithoutLeakingValues() throws Exception {
        ExpectedToolCall call = expectedCall(Map.of(
                "data", Map.of("marker", "private-expected-marker")));

        try (var server = McpTestServer.startWithToolResult(
                Map.of("data", Map.of("marker", "different-private-marker")), false)) {
            var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                    () -> client.validate(server.uri(), EXPECTED, call));

            assertEquals(McpStage.TOOL_CALL, failure.stage());
            assertEquals("MCP Tool result does not match the mock upstream contract", failure.getMessage());
            assertFalse(failure.getMessage().contains("private"));
        }
    }

    @Test
    void rejectsToolCallResponsesThatViolateTheMockContract() throws Exception {
        for (Scenario scenario : List.of(
                Scenario.TOOLS_CALL_ERROR,
                Scenario.TOOLS_CALL_WRONG_ID,
                Scenario.TOOLS_CALL_OVERSIZED_ID,
                Scenario.TOOLS_CALL_MISSING_RESULT,
                Scenario.TOOLS_CALL_IS_ERROR,
                Scenario.TOOLS_CALL_EMPTY_CONTENT,
                Scenario.TOOLS_CALL_NON_TEXT_CONTENT,
                Scenario.TOOLS_CALL_INVALID_TEXT_JSON,
                Scenario.TOOLS_CALL_TRAILING_TEXT_JSON,
                Scenario.TOOLS_CALL_MISMATCHED_JSON,
                Scenario.TOOLS_CALL_MULTIPLE_TEXT)) {
            try (var server = McpTestServer.start(scenario)) {
                var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                        () -> client.validate(server.uri(), EXPECTED, EXPECTED_CALL), scenario.name());

                assertEquals(McpStage.TOOL_CALL, failure.stage(), scenario.name());
            }
        }
    }

    @Test
    void appliesTheCallDeadlineAndResponseSizeLimitToToolCalls() throws Exception {
        try (var slowServer = McpTestServer.start(Scenario.TOOLS_CALL_SLOW_BODY)) {
            var shortClient = new McpStreamableHttpClient(Duration.ofMillis(200), 64 * 1024);

            var failure = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertThrows(McpStreamableHttpClient.McpValidationException.class,
                            () -> shortClient.validate(slowServer.uri(), EXPECTED, EXPECTED_CALL)));

            assertEquals(McpStage.TOOL_CALL, failure.stage());
            assertTrue(failure.getMessage().contains("deadline"));
        }
        try (var oversizedServer = McpTestServer.start(Scenario.TOOLS_CALL_OVERSIZE)) {
            var smallClient = new McpStreamableHttpClient(Duration.ofSeconds(2), 1_024);

            var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                    () -> smallClient.validate(oversizedServer.uri(), EXPECTED, EXPECTED_CALL));

            assertEquals(McpStage.TOOL_CALL, failure.stage());
            assertTrue(failure.getMessage().contains("size limit"));
        }
    }

    @Test
    void initializesThenListsToolsAcrossJsonAndSseResponses() throws Exception {
        try (var server = McpTestServer.startWithJsonInitializeAndSseToolsList()) {
            var result = client.validate(server.uri(), EXPECTED);

            assertEquals(Set.of("kma_weather_get_forecast"), result.toolNames());
            assertTrue(result.tools().getFirst().inputSchemaPresent());
            assertTrue(server.receivedInitializedNotification());
            assertTrue(server.receivedSessionHeaderOnInitializedNotification());
            assertTrue(server.receivedSessionHeaderOnToolsList());
        }
    }

    @Test
    void rejectsJsonRpcErrorsWithoutExposingTheResponseBody() throws Exception {
        try (var server = McpTestServer.start(Scenario.INITIALIZE_ERROR)) {
            var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                    () -> client.validate(server.uri(), EXPECTED));

            assertEquals(McpStage.INITIALIZE, failure.stage());
            assertFalse(failure.getMessage().contains("private"));
        }
    }

    @Test
    void rejectsOversizeResponsesBeforeJsonParsing() throws Exception {
        try (var server = McpTestServer.start(Scenario.OVERSIZE)) {
            var smallClient = new McpStreamableHttpClient(Duration.ofSeconds(2), 256);

            var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                    () -> smallClient.validate(server.uri(), EXPECTED));

            assertEquals(McpStage.INITIALIZE, failure.stage());
            assertTrue(failure.getMessage().contains("size limit"));
        }
    }

    @Test
    void requestsOneBodyBatchAtATimeAndStopsDemandOnAnOversizedFirstBatch() {
        var subscriber = new McpStreamableHttpClient.BoundedBodySubscriber(4);
        var subscription = new RecordingSubscription();

        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[3]), ByteBuffer.wrap(new byte[2])));

        assertEquals(1, subscription.requested.get());
        assertEquals(1, subscription.cancelled.get());
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void preSubscribeCancellationCancelsWithoutRequestingData() {
        var handler = new McpStreamableHttpClient.BoundedBodyHandler(4);
        handler.cancel();
        var subscriber = handler.apply(new ResponseInfo());
        var subscription = new RecordingSubscription();

        subscriber.onSubscribe(subscription);

        assertEquals(0, subscription.requested.get());
        assertEquals(1, subscription.cancelled.get());
    }

    @Test
    void cancellationStopsDemandAfterASuccessfulBatch() {
        var subscriber = new McpStreamableHttpClient.BoundedBodySubscriber(4);
        var subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        assertEquals(1, subscription.requested.get());

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        assertEquals(2, subscription.requested.get());

        subscriber.cancel();
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));

        assertEquals(2, subscription.requested.get());
        assertEquals(1, subscription.cancelled.get());
    }

    @Test
    void appliesOneAbsoluteDeadlineToHeadersAndTheEntireResponseBody() throws Exception {
        try (var server = McpTestServer.start(Scenario.SLOW_BODY)) {
            var shortClient = new McpStreamableHttpClient(Duration.ofMillis(200), 64 * 1024);

            var failure = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertThrows(McpStreamableHttpClient.McpValidationException.class,
                            () -> shortClient.validate(server.uri(), EXPECTED)));

            assertEquals(McpStage.INITIALIZE, failure.stage());
            assertTrue(failure.getMessage().contains("deadline"));
        }
    }

    @Test
    void rejectsWrongJsonRpcIds() throws Exception {
        try (var server = McpTestServer.start(Scenario.WRONG_ID)) {
            var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                    () -> client.validate(server.uri(), EXPECTED));

            assertEquals(McpStage.INITIALIZE, failure.stage());
        }
    }

    @Test
    void rejectsOversizedIntegralJsonRpcIdsWithoutLongTruncation() throws Exception {
        try (var server = McpTestServer.start(Scenario.OVERSIZED_ID)) {
            var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                    () -> client.validate(server.uri(), EXPECTED));

            assertEquals(McpStage.INITIALIZE, failure.stage());
        }
    }

    @Test
    void rejectsInitializeResultsThatDoNotMeetTheServerContract() throws Exception {
        for (Scenario scenario : List.of(
                Scenario.MISSING_PROTOCOL_VERSION,
                Scenario.UNSUPPORTED_PROTOCOL_VERSION,
                Scenario.MISSING_CAPABILITIES,
                Scenario.INVALID_CAPABILITIES,
                Scenario.MISSING_SERVER_INFO,
                Scenario.INVALID_SERVER_INFO,
                Scenario.MISSING_TOOLS_CAPABILITY,
                Scenario.INVALID_TOOLS_CAPABILITY)) {
            try (var server = McpTestServer.start(scenario)) {
                var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                        () -> client.validate(server.uri(), EXPECTED), scenario.name());

                assertEquals(McpStage.INITIALIZE, failure.stage());
            }
        }
    }

    @Test
    void rejectsMissingToolInputSchema() throws Exception {
        try (var server = McpTestServer.start(Scenario.MISSING_SCHEMA)) {
            var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                    () -> client.validate(server.uri(), EXPECTED));

            assertEquals(McpStage.TOOLS_LIST, failure.stage());
            assertTrue(failure.getMessage().contains("input schema"));
        }
    }

    @Test
    void rejectsEverySchemaContractMismatchIncludingExtraProperties() throws Exception {
        for (Scenario scenario : List.of(
                Scenario.EMPTY_SCHEMA,
                Scenario.WRONG_SCHEMA_TYPE,
                Scenario.WRONG_PROPERTIES,
                Scenario.WRONG_REQUIRED,
                Scenario.EXTRA_PROPERTY)) {
            try (var server = McpTestServer.start(scenario)) {
                var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                        () -> client.validate(server.uri(), EXPECTED), scenario.name());
                assertEquals(McpStage.TOOLS_LIST, failure.stage());
                assertTrue(failure.getMessage().contains("input schema"));
            }
        }
    }

    @Test
    void treatsEquivalentJsonNumberRepresentationsAsTheSameSchemaConstraint() throws Exception {
        try (var server = McpTestServer.start(Scenario.NUMERIC_EQUIVALENT)) {
            var result = client.validate(server.uri(), numericExpected(new BigDecimal("9E+1")));

            assertEquals(Set.of("kma_weather_get_forecast"), result.toolNames());
        }
    }

    @Test
    void rejectsDifferentJsonNumberValuesInSchemaConstraints() throws Exception {
        try (var server = McpTestServer.start(Scenario.NUMERIC_MISMATCH)) {
            var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                    () -> client.validate(server.uri(), numericExpected(new BigDecimal("9E+1"))));

            assertEquals(McpStage.TOOLS_LIST, failure.stage());
            assertTrue(failure.getMessage().contains("input schema"));
        }
    }

    @Test
    void doesNotRoundDistinctHighPrecisionJsonNumbersIntoTheSameSchemaConstraint() throws Exception {
        try (var server = McpTestServer.start(Scenario.NUMERIC_PRECISION_MISMATCH)) {
            var failure = assertThrows(McpStreamableHttpClient.McpValidationException.class,
                    () -> client.validate(server.uri(), numericExpected(new BigDecimal("0.1"))));

            assertEquals(McpStage.TOOLS_LIST, failure.stage());
            assertTrue(failure.getMessage().contains("input schema"));
        }
    }

    @Test
    void validatesStatusAndContentType() throws Exception {
        try (var statusServer = McpTestServer.start(Scenario.WRONG_STATUS)) {
            assertThrows(McpStreamableHttpClient.McpValidationException.class,
                    () -> client.validate(statusServer.uri(), EXPECTED));
        }
        try (var contentServer = McpTestServer.start(Scenario.WRONG_CONTENT_TYPE)) {
            assertThrows(McpStreamableHttpClient.McpValidationException.class,
                    () -> client.validate(contentServer.uri(), EXPECTED));
        }
    }

    @Test
    void refusesNonLoopbackAndUserInfoUrisBeforeSending() {
        assertThrows(IllegalArgumentException.class,
                () -> client.validate(URI.create("http://192.0.2.1:8080/mcp"), EXPECTED));
        assertThrows(IllegalArgumentException.class,
                () -> client.validate(URI.create("http://user@127.0.0.1:8080/mcp"), EXPECTED));
        assertThrows(IllegalArgumentException.class,
                () -> client.validate(URI.create("http://127.0.0.1:8080/user-controlled"), EXPECTED));
    }

    private static Map<String, ExpectedTool> numericExpected(BigDecimal minimum) {
        return Map.of(
                "kma_weather_get_forecast",
                new ExpectedTool(
                        "Get the public weather forecast for a grid location.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("latitude", Map.of(
                                        "type", "number",
                                        "minimum", minimum)),
                                "required", List.of("latitude"))));
    }

    private static ExpectedToolCall expectedCall(Object expectedResult) {
        return new ExpectedToolCall(
                EXPECTED_CALL.tool(),
                EXPECTED_CALL.arguments(),
                new ExpectedUpstreamResponse(200, "application/json", Map.of()),
                expectedResult);
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private final AtomicInteger requested = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();

        @Override
        public void request(long count) {
            requested.addAndGet(Math.toIntExact(count));
        }

        @Override
        public void cancel() {
            cancelled.incrementAndGet();
        }
    }

    private static final class ResponseInfo implements HttpResponse.ResponseInfo {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(Map.of(), (left, right) -> true);
        }

        @Override
        public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_1_1;
        }
    }
}
