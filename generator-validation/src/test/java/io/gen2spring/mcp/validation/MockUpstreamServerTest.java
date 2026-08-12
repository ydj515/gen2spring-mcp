package io.gen2spring.mcp.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.validation.ExpectedUpstreamOutcome;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MockUpstreamServerTest {
    private static final String SENSITIVE_OBSERVED_VALUE = "observed-private-value";
    private static final String TRACEPARENT =
            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
    private static final Duration WAIT = Duration.ofSeconds(2);
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void verifiesOrderedResponsesAndRejectsOutOfOrderRequests() throws Exception {
        UpstreamCallExpectation first = orderedExpectation(
                "first", 503, Map.of("retry", true), ExpectedUpstreamOutcome.RESPONSE);
        UpstreamCallExpectation second = orderedExpectation(
                "second", 200, Map.of("items", List.of(1)), ExpectedUpstreamOutcome.RESPONSE);
        try (var server = MockUpstreamServer.start(List.of(first, second))) {
            assertEquals(503, sendOrdered(server, "first").statusCode());
            assertEquals(200, sendOrdered(server, "second").statusCode());
            server.sealAndAwaitVerified(WAIT);
        }

        try (var server = MockUpstreamServer.start(List.of(first, second))) {
            assertEquals(400, sendOrdered(server, "second").statusCode());
            assertThrows(MockUpstreamServer.VerificationException.class,
                    () -> server.sealAndAwaitVerified(WAIT));
        }
    }

    @Test
    void disconnectsWithATruncatedBodyAndThenContinuesTheOrderedSequence() throws Exception {
        UpstreamCallExpectation disconnect = orderedExpectation(
                "first", 200, Map.of(), ExpectedUpstreamOutcome.DISCONNECT);
        UpstreamCallExpectation response = orderedExpectation(
                "first", 200, Map.of("items", List.of(1)), ExpectedUpstreamOutcome.RESPONSE);
        try (var server = MockUpstreamServer.start(List.of(disconnect, response))) {
            assertThrows(IOException.class, () -> sendOrdered(server, "first"));
            assertEquals(200, sendOrdered(server, "first").statusCode());
            server.sealAndAwaitVerified(WAIT);
        }
    }

    @Test
    void acceptsTheExactRequestAndReturnsOnlyTheFixedValidationResult() throws Exception {
        try (var server = MockUpstreamServer.start(expectation())) {
            HttpResponse<String> response = send(server, "POST", query("first", "second"),
                    "X-ToKeN", "validator", "{\"name\":\"sample\",\"count\":1}");

            assertEquals(200, response.statusCode());
            assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(Map.of("validated", true, "operationId", "submitItem"),
                    mapper.readValue(response.body(), Map.class));
            assertEquals(2, mapper.readTree(response.body()).size());
            server.sealAndAwaitVerified(WAIT);
        }
    }

    @Test
    void returnsTheConfiguredStatusContentTypeAndBodyAfterOneExactRequest() throws Exception {
        UpstreamCallExpectation expectation = expectation(
                429, "application/problem+json", Map.of("code", "LIMIT"));
        try (var server = MockUpstreamServer.start(expectation)) {
            HttpResponse<String> response = send(server, "POST", query("first", "second"),
                    "X-Token", "validator", expectedBody());

            assertEquals(429, response.statusCode());
            assertEquals("application/problem+json",
                    response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(mapper.valueToTree(Map.of("code", "LIMIT")), mapper.readTree(response.body()));
            server.sealAndAwaitVerified(WAIT);
        }
    }

    @Test
    void rejectsConfiguredResponsesLargerThanOneMebibyteBeforeServerStartWithoutLeakingTheBody() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> expectation(
                        200,
                        "application/json",
                        Map.of("private", SENSITIVE_OBSERVED_VALUE + "x".repeat(1024 * 1024))));

        assertEquals("Expected Tool call response fixture exceeded the size limit", failure.getMessage());
        assertFalse(failure.getMessage().contains(SENSITIVE_OBSERVED_VALUE));
    }

    @Test
    void acceptsCaseInsensitiveRelevantHeadersAndCanonicalJsonNumbersAndFieldOrder() throws Exception {
        try (var server = MockUpstreamServer.start(expectation())) {
            HttpResponse<String> response = send(server, "POST", query("first", "second"),
                    "X-TOKEN", "validator", "{\"count\":1.00,\"name\":\"sample\"}");

            assertEquals(200, response.statusCode());
            server.sealAndAwaitVerified(WAIT);
        }
    }

    @Test
    void rejectsWrongMethodAndPathWithoutExposingObservedValues() throws Exception {
        assertRejected("method", server -> send(server, "PUT", query("first", "second"),
                "X-Token", "validator", expectedBody()));
        assertRejected("path", server -> send(server, "POST", "/wrong/" + SENSITIVE_OBSERVED_VALUE
                        + "?tag=first&tag=second",
                "X-Token", "validator", expectedBody()));
    }

    @Test
    void rejectsMissingExtraAndReorderedQueryValues() throws Exception {
        assertRejected("missing query", server -> send(server, "POST", query("first"),
                "X-Token", "validator", expectedBody()));
        assertRejected("extra query", server -> send(server, "POST",
                query("first", "second") + "&extra=" + SENSITIVE_OBSERVED_VALUE,
                "X-Token", "validator", expectedBody()));
        assertRejected("reordered query", server -> send(server, "POST", query("second", "first"),
                "X-Token", "validator", expectedBody()));
    }

    @Test
    void acceptsValidPercentDecodedQueryValues() throws Exception {
        var expectation = expectation(List.of("validator"), Map.of("tag", List.of("snow day/+")));
        try (var server = MockUpstreamServer.start(expectation)) {
            HttpResponse<String> response = send(server, "POST", "/items/one?tag=snow%20day%2F%2B",
                    "X-Token", "validator", expectedBody());

            assertEquals(200, response.statusCode());
            server.sealAndAwaitVerified(WAIT);
        }
    }

    @Test
    void rejectsMalformedUtf8InsteadOfMatchingAReplacementCharacter() throws Exception {
        assertRejected(
                "malformed UTF-8",
                expectation(List.of("validator"), Map.of("tag", List.of("\uFFFD"))),
                server -> send(server, "POST", "/items/one?tag=%FF",
                        "X-Token", "validator", expectedBody()));
    }

    @Test
    void rejectsMissingOrMismatchedRelevantHeaders() throws Exception {
        assertRejected("missing header", server -> send(server, "POST", query("first", "second"),
                null, null, expectedBody()));
        assertRejected("wrong header", server -> send(server, "POST", query("first", "second"),
                "X-Token", SENSITIVE_OBSERVED_VALUE, expectedBody()));
    }

    @Test
    void rejectsUnexpectedAuthorizationAndCustomHeaders() throws Exception {
        assertRejected("authorization", server -> send(request(
                        server,
                        "POST",
                        query("first", "second"),
                        "X-Token",
                        "validator",
                        HttpRequest.BodyPublishers.ofString(expectedBody()))
                .header("Authorization", "Bearer " + SENSITIVE_OBSERVED_VALUE)));
        assertRejected("custom", server -> send(request(
                        server,
                        "POST",
                        query("first", "second"),
                        "X-Token",
                        "validator",
                        HttpRequest.BodyPublishers.ofString(expectedBody()))
                .header("X-Unexpected", SENSITIVE_OBSERVED_VALUE)));
    }

    @Test
    void rejectsMissingMalformedAndDuplicateTraceContextWithoutLeakingIt() throws Exception {
        assertRejected("missing traceparent", server -> send(HttpRequest.newBuilder(
                        server.baseUri().resolve(query("first", "second")))
                .timeout(WAIT)
                .header("X-Token", "validator")
                .POST(HttpRequest.BodyPublishers.ofString(expectedBody()))));
        assertRejected("malformed traceparent", server -> send(request(
                        server,
                        "POST",
                        query("first", "second"),
                        "X-Token",
                        "validator",
                        HttpRequest.BodyPublishers.ofString(expectedBody()))
                .setHeader("traceparent", SENSITIVE_OBSERVED_VALUE)));
        assertRejected("duplicate traceparent", server -> send(request(
                        server,
                        "POST",
                        query("first", "second"),
                        "X-Token",
                        "validator",
                        HttpRequest.BodyPublishers.ofString(expectedBody()))
                .header("traceparent", "00-abcdefabcdefabcdefabcdefabcdefab-abcdefabcdefabcd-00")));
    }

    @Test
    void acceptsStandardHttpTransportHeaders() throws Exception {
        try (var server = MockUpstreamServer.start(expectation())) {
            HttpResponse<String> response = send(request(
                            server,
                            "POST",
                            query("first", "second"),
                            "X-Token",
                            "validator",
                            HttpRequest.BodyPublishers.ofString(expectedBody()))
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "gzip")
                    .header("Content-Type", "application/json"));

            assertEquals(200, response.statusCode());
            server.sealAndAwaitVerified(WAIT);
        }
    }

    @Test
    void comparesRepeatedExpectedHeaderValuesInReceivedOrder() throws Exception {
        try (var server = MockUpstreamServer.start(expectation(List.of("first", "second")))) {
            HttpResponse<String> response = send(request(
                            server,
                            "POST",
                            query("first", "second"),
                            null,
                            null,
                            HttpRequest.BodyPublishers.ofString(expectedBody()))
                    .header("X-Token", "first")
                    .header("X-Token", "second"));

            assertEquals(200, response.statusCode());
            server.sealAndAwaitVerified(WAIT);
        }
        assertRejected(
                "reordered header",
                expectation(List.of("first", "second")),
                server -> send(request(
                                server,
                                "POST",
                                query("first", "second"),
                                null,
                                null,
                                HttpRequest.BodyPublishers.ofString(expectedBody()))
                        .header("X-Token", "second")
                        .header("X-Token", "first")));
    }

    @Test
    void rejectsJsonBodyMismatchWithoutExposingTheBody() throws Exception {
        assertRejected("body", server -> send(server, "POST", query("first", "second"),
                "X-Token", "validator", "{\"name\":\"" + SENSITIVE_OBSERVED_VALUE + "\",\"count\":1}"));
    }

    @Test
    void rejectsDuplicateJsonObjectFields() throws Exception {
        assertRejected("duplicate body field", server -> send(server, "POST", query("first", "second"),
                "X-Token", "validator", "{\"name\":\"" + SENSITIVE_OBSERVED_VALUE
                        + "\",\"name\":\"sample\",\"count\":1}"));
    }

    @Test
    void rejectsBodiesLargerThanOneMebibyte() throws Exception {
        byte[] oversized = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, (byte) 'a');
        try (var server = MockUpstreamServer.start(expectation())) {
            HttpRequest request = request(server, "POST", query("first", "second"),
                            "X-Token", "validator", HttpRequest.BodyPublishers.ofByteArray(oversized))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            var failure = assertThrows(MockUpstreamServer.VerificationException.class,
                    () -> server.sealAndAwaitVerified(WAIT));

            assertEquals(413, response.statusCode());
            assertFalse(failure.getMessage().contains(SENSITIVE_OBSERVED_VALUE));
        }
    }

    @Test
    void rejectsASecondRequestEvenAfterTheFirstRequestMatched() throws Exception {
        try (var server = MockUpstreamServer.start(expectation())) {
            HttpResponse<String> first = send(server, "POST", query("first", "second"),
                    "X-Token", "validator", expectedBody());
            HttpResponse<String> duplicate = send(server, "POST", query("first", "second"),
                    "X-Token", "validator", expectedBody());

            assertEquals(200, first.statusCode());
            assertEquals(409, duplicate.statusCode());
            assertThrows(MockUpstreamServer.VerificationException.class,
                    () -> server.sealAndAwaitVerified(WAIT));
        }
    }

    @Test
    void rejectsARequestThatArrivesAfterSuccessfulObservationSealing() throws Exception {
        var server = MockUpstreamServer.start(expectation());
        try {
            HttpResponse<String> first = send(server, "POST", query("first", "second"),
                    "X-Token", "validator", expectedBody());
            server.sealAndAwaitVerified(WAIT);

            HttpResponse<String> lateDuplicate = send(server, "POST", query("first", "second"),
                    "X-Token", "validator", expectedBody());

            assertEquals(200, first.statusCode());
            assertEquals(409, lateDuplicate.statusCode());
            assertThrows(MockUpstreamServer.VerificationException.class, server::close);
        } finally {
            server.close();
        }
    }

    @Test
    void observesAConcurrentDuplicateWhileThePrimaryResponseIsStillBeingWritten() throws Exception {
        CountDownLatch primaryWriteStarted = new CountDownLatch(1);
        CountDownLatch releasePrimaryWrite = new CountDownLatch(1);
        try (var server = MockUpstreamServer.start(expectation(), (exchange, status, body) -> {
            if (status == 200) {
                primaryWriteStarted.countDown();
                try {
                    if (!releasePrimaryWrite.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IOException("Primary response release timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Primary response write was interrupted", failure);
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        })) {
            HttpRequest primaryRequest = request(
                    server,
                    "POST",
                    query("first", "second"),
                    "X-Token",
                    "validator",
                    HttpRequest.BodyPublishers.ofString(expectedBody())).build();
            var primary = HttpClient.newHttpClient().sendAsync(
                    primaryRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertTrue(primaryWriteStarted.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));

            HttpRequest duplicateRequest = request(
                    server,
                    "POST",
                    query("first", "second"),
                    "X-Token",
                    "validator",
                    HttpRequest.BodyPublishers.ofString(expectedBody())).build();
            var duplicate = HttpClient.newHttpClient().sendAsync(
                    duplicateRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            try {
                assertEquals(409, duplicate.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode());
            } finally {
                releasePrimaryWrite.countDown();
            }

            assertEquals(200, primary.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode());
            assertThrows(MockUpstreamServer.VerificationException.class,
                    () -> server.sealAndAwaitVerified(WAIT));
        } finally {
            releasePrimaryWrite.countDown();
        }
    }

    @Test
    void responseWriteFailureCompletesVerificationExceptionally() throws Exception {
        try (var server = MockUpstreamServer.start(expectation(), (exchange, status, body) -> {
            throw new IOException(SENSITIVE_OBSERVED_VALUE);
        })) {
            assertThrows(IOException.class, () -> send(server, "POST", query("first", "second"),
                    "X-Token", "validator", expectedBody()));

            var failure = assertThrows(MockUpstreamServer.VerificationException.class,
                    () -> server.sealAndAwaitVerified(WAIT));
            assertFalse(failure.getMessage().contains(SENSITIVE_OBSERVED_VALUE));
        }
    }

    @Test
    void timesOutWhenNoRequestArrives() throws Exception {
        try (var server = MockUpstreamServer.start(expectation())) {
            var failure = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertThrows(MockUpstreamServer.VerificationException.class,
                            () -> server.sealAndAwaitVerified(Duration.ofMillis(100))));

            assertFalse(failure.getMessage().contains(SENSITIVE_OBSERVED_VALUE));
        }
    }

    @Test
    void closingBeforeARequestFailsVerificationAndReleasesTheServer() throws Exception {
        var server = MockUpstreamServer.start(expectation());
        URI endpoint = server.baseUri();

        server.close();

        var failure = assertThrows(MockUpstreamServer.VerificationException.class,
                () -> server.sealAndAwaitVerified(WAIT));
        assertFalse(failure.getMessage().contains(SENSITIVE_OBSERVED_VALUE));
        assertThrows(Exception.class, () -> client.send(
                HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.discarding()));
    }

    private UpstreamCallExpectation expectation() {
        return expectation(List.of("validator"));
    }

    private UpstreamCallExpectation orderedExpectation(
            String cursor,
            int responseStatus,
            Object responseBody,
            ExpectedUpstreamOutcome outcome) {
        return new UpstreamCallExpectation(
                "listItems",
                "GET",
                "/items",
                Map.of("cursor", List.of(cursor)),
                Map.of(),
                null,
                Map.of(),
                responseStatus,
                "application/json",
                responseBody,
                outcome);
    }

    private HttpResponse<String> sendOrdered(MockUpstreamServer server, String cursor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(server.baseUri().resolve("/items?cursor=" + cursor))
                .header("traceparent", TRACEPARENT)
                .timeout(WAIT)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private UpstreamCallExpectation expectation(List<String> tokenValues) {
        return expectation(tokenValues, Map.of("tag", List.of("first", "second")));
    }

    private UpstreamCallExpectation expectation(
            List<String> tokenValues,
            Map<String, List<String>> query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "sample");
        body.put("count", 1);
        return new UpstreamCallExpectation(
                "submitItem",
                "POST",
                "/items/one",
                query,
                Map.of("x-token", tokenValues),
                body,
                Map.of("ITEM_KEY", "mcp-validation-secret-1"));
    }

    private UpstreamCallExpectation expectation(int status, String contentType, Object responseBody) {
        UpstreamCallExpectation request = expectation();
        return new UpstreamCallExpectation(
                request.operationId(),
                request.method(),
                request.rawPath(),
                request.query(),
                request.headers(),
                request.body(),
                request.environmentOverrides(),
                status,
                contentType,
                responseBody);
    }

    private String query(String... values) {
        StringBuilder path = new StringBuilder("/items/one?");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                path.append('&');
            }
            path.append("tag=").append(values[index]);
        }
        return path.toString();
    }

    private String expectedBody() {
        return "{\"name\":\"sample\",\"count\":1}";
    }

    private HttpResponse<String> send(
            MockUpstreamServer server,
            String method,
            String pathAndQuery,
            String headerName,
            String headerValue,
            String body) throws Exception {
        HttpRequest.Builder request = request(
                server,
                method,
                pathAndQuery,
                headerName,
                headerValue,
                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder request(
            MockUpstreamServer server,
            String method,
            String pathAndQuery,
            String headerName,
            String headerValue,
            HttpRequest.BodyPublisher body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(server.baseUri().resolve(pathAndQuery))
                .timeout(WAIT)
                .header("traceparent", TRACEPARENT)
                .method(method, body);
        if (headerName != null) {
            request.header(headerName, headerValue);
        }
        return request;
    }

    private void assertRejected(String scenario, RequestAction action) throws Exception {
        assertRejected(scenario, expectation(), action);
    }

    private void assertRejected(
            String scenario,
            UpstreamCallExpectation expectation,
            RequestAction action) throws Exception {
        try (var server = MockUpstreamServer.start(expectation)) {
            HttpResponse<String> response = action.send(server);
            var failure = assertThrows(MockUpstreamServer.VerificationException.class,
                    () -> server.sealAndAwaitVerified(WAIT), scenario);

            assertEquals(400, response.statusCode(), scenario);
            assertFalse(failure.getMessage().contains(SENSITIVE_OBSERVED_VALUE), scenario);
        }
    }

    @FunctionalInterface
    private interface RequestAction {
        HttpResponse<String> send(MockUpstreamServer server) throws Exception;
    }
}
