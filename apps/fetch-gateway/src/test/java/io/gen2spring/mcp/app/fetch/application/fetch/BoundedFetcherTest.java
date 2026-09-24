package io.gen2spring.mcp.app.fetch.application.fetch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.app.fetch.application.fetch.port.out.FetchTransport;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

final class BoundedFetcherTest {
    @Test
    void followsOnlyThreeManuallyValidatedRedirects() {
        ScriptedTransport transport = new ScriptedTransport(
                redirect("https://second.example.com/openapi.json"),
                redirect("https://third.example.com/openapi.json"),
                redirect("https://fourth.example.com/openapi.json"),
                success("openapi: 3.1.0\n"));
        BoundedFetcher fetcher = new BoundedFetcher(transport, 64, 64, 3, Duration.ofSeconds(30));

        BoundedFetcher.FetchResult result = fetcher.fetch(
                ImportTarget.parse("https://first.example.com/openapi.json"));

        assertEquals(4, transport.requests.size());
        assertEquals("https://fourth.example.com/openapi.json", transport.requests.getLast());
        assertArrayEquals("openapi: 3.1.0\n".getBytes(StandardCharsets.UTF_8), result.body());

        ScriptedTransport tooMany = new ScriptedTransport(
                redirect("https://second.example.com/openapi.json"),
                redirect("https://third.example.com/openapi.json"),
                redirect("https://fourth.example.com/openapi.json"),
                redirect("https://fifth.example.com/openapi.json"));
        assertFetchFailure(() -> new BoundedFetcher(
                        tooMany, 64, 64, 3, Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://first.example.com/openapi.json")));

        ScriptedTransport privateRedirect = new ScriptedTransport(
                redirect("https://metadata.google.internal/openapi.json"));
        assertFetchFailure(() -> new BoundedFetcher(
                        privateRedirect, 64, 64, 3, Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://first.example.com/openapi.json")));
    }

    @Test
    void boundsWireDecodedAndHeaderBytesIndependently() throws Exception {
        byte[] expanded = "x".repeat(65).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzip(expanded);

        assertFetchFailure(() -> fetch(new FetchTransport.Response(
                200,
                Map.of("content-type", List.of("application/yaml")),
                new ByteArrayInputStream(new byte[65])), 64, 128));
        assertFetchFailure(() -> fetch(new FetchTransport.Response(
                200,
                Map.of(
                        "content-type", List.of("application/yaml"),
                        "content-encoding", List.of("gzip")),
                new ByteArrayInputStream(compressed)), 128, 64));
        assertFetchFailure(() -> fetch(new FetchTransport.Response(
                200,
                Map.of("x-private", List.of("x".repeat(16_385))),
                new ByteArrayInputStream(new byte[0])), 128, 128));
    }

    @Test
    void returnsFixedFailuresWithoutRetainingTargetsOrBodies() {
        ScriptedTransport transport = new ScriptedTransport(new FetchTransport.Response(
                500,
                Map.of("content-type", List.of("text/plain")),
                new ByteArrayInputStream("private response body".getBytes(StandardCharsets.UTF_8))));

        FetchFailure failure = assertThrows(FetchFailure.class, () -> new BoundedFetcher(
                        transport, 128, 128, 3, Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://private-marker.example.com/openapi.json")));

        assertEquals("URL import fetch failed", failure.getMessage());
        assertEquals(null, failure.getCause());
        assertFalse(failure.toString().contains("private-marker"));
        assertFalse(failure.toString().contains("private response body"));
    }

    @Test
    void retriesOnlyBoundedTransportAndServerFailures() {
        ScriptedTransport serverFailure = new ScriptedTransport(
                new FetchTransport.Response(
                        503,
                        Map.of("content-type", List.of("text/plain")),
                        new ByteArrayInputStream(new byte[0])),
                success("openapi: 3.0.3\n"));
        BoundedFetcher.FetchResult recovered = new BoundedFetcher(
                        serverFailure, 128, 128, 3, Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://api.example.com/openapi.yaml"));
        assertEquals(2, serverFailure.requests.size());
        assertEquals(200, recovered.status());

        AtomicInteger transientCalls = new AtomicInteger();
        FetchTransport transientFailure = (target, timeout) -> {
            if (transientCalls.getAndIncrement() == 0) {
                throw new FetchFailure(true);
            }
            return success("openapi: 3.0.3\n");
        };
        new BoundedFetcher(transientFailure, 128, 128, 3, Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://api.example.com/openapi.yaml"));
        assertEquals(2, transientCalls.get());

        AtomicInteger rejectedCalls = new AtomicInteger();
        assertFetchFailure(() -> new BoundedFetcher(
                        (target, timeout) -> {
                            rejectedCalls.incrementAndGet();
                            throw new FetchFailure(false);
                        },
                        128,
                        128,
                        3,
                        Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://api.example.com/openapi.yaml")));
        assertEquals(1, rejectedCalls.get());

        ScriptedTransport clientFailure = new ScriptedTransport(new FetchTransport.Response(
                404,
                Map.of("content-type", List.of("text/plain")),
                new ByteArrayInputStream(new byte[0])));
        assertFetchFailure(() -> new BoundedFetcher(
                        clientFailure, 128, 128, 3, Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://api.example.com/openapi.yaml")));
        assertEquals(1, clientFailure.requests.size());
    }

    @Test
    void enforcesTheTotalTimeoutBeforeTransportExecution() {
        AtomicInteger executions = new AtomicInteger();
        BoundedFetcher fetcher = new BoundedFetcher(
                (target, timeout) -> {
                    executions.incrementAndGet();
                    return success("openapi: 3.0.3\n");
                },
                128,
                128,
                3,
                Duration.ofNanos(1));

        assertFetchFailure(() -> fetcher.fetch(
                ImportTarget.parse("https://api.example.com/openapi.yaml")));
        assertEquals(0, executions.get());
    }

    @Test
    void enforcesTheTotalTimeoutWhileReadingAndDecodingTheBody() throws Exception {
        AtomicLong ticker = new AtomicLong();
        InputStream body = new ByteArrayInputStream("openapi: 3.1.0\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                ticker.addAndGet(Duration.ofSeconds(31).toNanos());
                return super.read(buffer, offset, length);
            }
        };
        BoundedFetcher fetcher = new BoundedFetcher(
                new ScriptedTransport(new FetchTransport.Response(
                        200, Map.of("content-type", List.of("application/yaml")), body)),
                128, 128, 3, Duration.ofSeconds(30), ticker::get);

        assertFetchFailure(() -> fetcher.fetch(
                ImportTarget.parse("https://api.example.com/openapi.yaml")));

        AtomicInteger ticks = new AtomicInteger();
        long expired = Duration.ofSeconds(31).toNanos();
        BoundedFetcher decoding = new BoundedFetcher(
                new ScriptedTransport(new FetchTransport.Response(
                        200,
                        Map.of(
                                "content-type", List.of("application/yaml"),
                                "content-encoding", List.of("gzip")),
                        new ByteArrayInputStream(gzip("openapi: 3.1.0\n".getBytes(StandardCharsets.UTF_8))))),
                128, 128, 3, Duration.ofSeconds(30),
                () -> ticks.incrementAndGet() <= 7 ? 0 : expired);

        assertFetchFailure(() -> decoding.fetch(
                ImportTarget.parse("https://api.example.com/openapi.yaml")));
    }

    private BoundedFetcher.FetchResult fetch(
            FetchTransport.Response response,
            int maxWireBytes,
            int maxDecodedBytes) {
        return new BoundedFetcher(
                        new ScriptedTransport(response),
                        maxWireBytes,
                        maxDecodedBytes,
                        3,
                        Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://api.example.com/openapi.json"));
    }

    private FetchTransport.Response redirect(String location) {
        return new FetchTransport.Response(
                302,
                Map.of("location", List.of(location)),
                new ByteArrayInputStream(new byte[0]));
    }

    private FetchTransport.Response success(String value) {
        return new FetchTransport.Response(
                200,
                Map.of("content-type", List.of("application/yaml")),
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] gzip(byte[] value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value);
        }
        return output.toByteArray();
    }

    private void assertFetchFailure(Runnable action) {
        FetchFailure failure = assertThrows(FetchFailure.class, action::run);
        assertEquals("URL import fetch failed", failure.getMessage());
        assertEquals(null, failure.getCause());
    }

    private static final class ScriptedTransport implements FetchTransport {
        private final Queue<Response> responses;
        private final List<String> requests = new ArrayList<>();

        private ScriptedTransport(Response... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Response execute(ImportTarget target, Duration timeout) {
            requests.add(target.uri().toString());
            if (timeout.isZero() || timeout.isNegative() || responses.isEmpty()) {
                throw new IllegalStateException("scripted transport failed");
            }
            return responses.remove();
        }
    }
}
