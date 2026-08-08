package io.gen2spring.mcp.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedTool;
import io.gen2spring.mcp.validation.McpStreamableHttpClient.McpStage;
import io.gen2spring.mcp.validation.support.McpTestServer;
import io.gen2spring.mcp.validation.support.McpTestServer.Scenario;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
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

    private final McpStreamableHttpClient client = new McpStreamableHttpClient(Duration.ofSeconds(2), 64 * 1024);

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
