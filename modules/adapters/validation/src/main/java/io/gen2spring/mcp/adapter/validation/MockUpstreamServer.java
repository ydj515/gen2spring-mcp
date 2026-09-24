package io.gen2spring.mcp.adapter.validation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class MockUpstreamServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final byte[] MISMATCH_RESPONSE = "{\"error\":\"request_mismatch\"}"
            .getBytes(StandardCharsets.UTF_8);
    private static final Set<String> TRANSPORT_HEADERS = Set.of(
            "accept",
            "accept-encoding",
            "connection",
            "content-length",
            "host",
            "http2-settings",
            "transfer-encoding",
            "traceparent",
            "upgrade",
            "user-agent");
    private static final Pattern TRACEPARENT = Pattern.compile(
            "00-([0-9a-f]{32})-([0-9a-f]{16})-(00|01)");

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<UpstreamCallExpectation> expectations;
    private final ResponseWriter responseWriter;
    private final URI baseUri;
    private final ObjectMapper mapper;
    private final List<JsonNode> expectedBodies;
    private final List<byte[]> configuredResponses;
    private final Map<String, String> environmentOverrides;
    private final Object observationMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long observedRequests;
    private boolean requestInFlight;
    private int verifiedInteractions;
    private boolean observationSealed;
    private VerificationException observationFailure;
    private VerificationException lateRequestFailure;

    private MockUpstreamServer(
            HttpServer server,
            ExecutorService executor,
            List<UpstreamCallExpectation> expectations,
            ResponseWriter responseWriter) {
        this.server = server;
        this.executor = executor;
        this.expectations = List.copyOf(expectations);
        this.responseWriter = responseWriter;
        this.baseUri = baseUri(server.getAddress());
        this.mapper = new ObjectMapper(JsonFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .build())
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.expectedBodies = this.expectations.stream()
                .map(expectation -> expectation.body() == null
                        ? null
                        : canonicalJson(mapper.valueToTree(expectation.body())))
                .toList();
        this.configuredResponses = this.expectations.stream()
                .map(expectation -> expectation.outcome()
                        == io.gen2spring.mcp.application.generation.validation.ExpectedUpstreamOutcome.RESPONSE
                        ? configuredResponse(expectation.responseBody())
                        : null)
                .toList();
        this.environmentOverrides = mergeEnvironmentOverrides(this.expectations);
    }

    public static MockUpstreamServer start(UpstreamCallExpectation expectation) throws IOException {
        return start(expectation, MockUpstreamServer::writeResponse);
    }

    public static MockUpstreamServer start(List<UpstreamCallExpectation> expectations) throws IOException {
        return start(expectations, MockUpstreamServer::writeResponse);
    }

    static MockUpstreamServer start(
            UpstreamCallExpectation expectation,
            ResponseWriter responseWriter) throws IOException {
        return start(List.of(Objects.requireNonNull(expectation, "expectation")), responseWriter);
    }

    static MockUpstreamServer start(
            List<UpstreamCallExpectation> expectations,
            ResponseWriter responseWriter) throws IOException {
        if (expectations == null || expectations.isEmpty() || expectations.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Mock upstream expectations are invalid");
        }
        Objects.requireNonNull(responseWriter, "responseWriter");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mock-upstream-", 0).factory());
        MockUpstreamServer mock;
        try {
            mock = new MockUpstreamServer(server, executor, expectations, responseWriter);
        } catch (RuntimeException failure) {
            server.stop(0);
            executor.shutdownNow();
            throw failure;
        }
        server.createContext("/", mock::handle);
        server.setExecutor(executor);
        try {
            server.start();
            return mock;
        } catch (RuntimeException failure) {
            server.stop(0);
            executor.shutdownNow();
            throw failure;
        }
    }

    public URI baseUri() {
        return baseUri;
    }

    public Map<String, String> environmentOverrides() {
        return environmentOverrides;
    }

    public void sealAndAwaitVerified(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Verification timeout must be positive");
        }
        long deadline = deadline(timeout);
        synchronized (observationMonitor) {
            observationSealed = true;
            while (requestInFlight && observationFailure == null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    VerificationException failure = new VerificationException(
                            "Mock upstream request verification did not finish before the deadline");
                    recordFailure(failure);
                    throw failure;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(observationMonitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    VerificationException failure = new VerificationException(
                            "Mock upstream verification was interrupted", interrupted);
                    recordFailure(failure);
                    throw failure;
                }
            }
            if (observationFailure != null) {
                throw observationFailure;
            }
            if (observedRequests != expectations.size() || verifiedInteractions != expectations.size()) {
                VerificationException failure = new VerificationException(
                        "Mock upstream did not observe the exact request sequence");
                recordFailure(failure);
                throw failure;
            }
        }
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            RequestAdmission admission = beginRequest();
            if (admission == null) {
                respondQuietly(exchange, 409, MISMATCH_RESPONSE);
                return;
            }
            UpstreamCallExpectation expectation = expectations.get(admission.index());
            try {
                byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                if (body.length > MAX_REQUEST_BYTES) {
                    throw new RequestMismatch(413, "Mock upstream request exceeded the size limit");
                }
                verifyRequest(expectation, expectedBodies.get(admission.index()), exchange, body);
                if (expectation.outcome()
                        == io.gen2spring.mcp.application.generation.validation.ExpectedUpstreamOutcome.DISCONNECT) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 8);
                    exchange.getResponseBody().write('{');
                } else {
                    exchange.getResponseHeaders().set("Content-Type", expectation.responseContentType());
                    respond(exchange, expectation.responseStatus(), configuredResponses.get(admission.index()));
                    exchange.close();
                }
                completeRequest(admission.index(), null);
            } catch (RequestMismatch mismatch) {
                completeRequest(admission.index(), new VerificationException(mismatch.getMessage()));
                respondQuietly(exchange, mismatch.status(), MISMATCH_RESPONSE);
            } catch (RuntimeException | IOException failure) {
                completeRequest(admission.index(), new VerificationException("Mock upstream verification failed"));
                respondQuietly(exchange, 500, MISMATCH_RESPONSE);
            }
        }
    }

    private RequestAdmission beginRequest() {
        synchronized (observationMonitor) {
            if (observationSealed) {
                if (!closed.get() && verifiedInteractions == expectations.size() && observationFailure == null
                        && lateRequestFailure == null) {
                    lateRequestFailure = new VerificationException(
                            "Mock upstream observed a request after verification was sealed");
                }
                return null;
            }
            if (closed.get()) {
                return null;
            }
            observedRequests++;
            if (requestInFlight || observedRequests > expectations.size()) {
                recordFailure(new VerificationException("Mock upstream observed an unexpected request"));
                return null;
            }
            int index = Math.toIntExact(observedRequests - 1);
            requestInFlight = true;
            return new RequestAdmission(index);
        }
    }

    private void completeRequest(int index, VerificationException failure) {
        synchronized (observationMonitor) {
            if (failure == null) {
                if (index != verifiedInteractions) {
                    recordFailure(new VerificationException("Mock upstream request sequence is invalid"));
                } else {
                    verifiedInteractions++;
                }
            } else {
                recordFailure(failure);
            }
            requestInFlight = false;
            observationMonitor.notifyAll();
        }
    }

    private void recordFailure(VerificationException failure) {
        if (observationFailure == null) {
            observationFailure = failure;
        }
    }

    private void verifyRequest(
            UpstreamCallExpectation expectation,
            JsonNode expectedBody,
            HttpExchange exchange,
            byte[] body) {
        if (!expectation.method().equals(exchange.getRequestMethod())) {
            throw mismatch("Mock upstream request method does not match");
        }
        if (!expectation.rawPath().equals(exchange.getRequestURI().getRawPath())) {
            throw mismatch("Mock upstream request path does not match");
        }
        if (!expectation.query().equals(query(exchange.getRequestURI().getRawQuery()))) {
            throw mismatch("Mock upstream request query does not match");
        }
        verifyTraceparent(exchange);
        if (!contractHeaders(expectation, exchange).equals(expectation.headers())) {
            throw mismatch("Mock upstream request headers do not match");
        }
        verifyBody(expectedBody, body);
    }

    private void verifyTraceparent(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get("traceparent");
        if (values == null || values.size() != 1) {
            throw mismatch("Mock upstream trace context does not match");
        }
        var matcher = TRACEPARENT.matcher(values.get(0));
        if (!matcher.matches()
                || "00000000000000000000000000000000".equals(matcher.group(1))
                || "0000000000000000".equals(matcher.group(2))) {
            throw mismatch("Mock upstream trace context does not match");
        }
    }

    private Map<String, List<String>> contractHeaders(
            UpstreamCallExpectation expectation,
            HttpExchange exchange) {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            String folded = name.toLowerCase(Locale.ROOT);
            if (!transportHeader(expectation, folded)) {
                actual.computeIfAbsent(folded, ignored -> new ArrayList<>()).addAll(values);
            }
        });
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        actual.forEach((name, values) -> immutable.put(name, List.copyOf(values)));
        return immutable;
    }

    private boolean transportHeader(UpstreamCallExpectation expectation, String name) {
        if (expectation.headers().containsKey(name)) {
            return false;
        }
        if ("content-type".equals(name)) {
            return expectation.body() != null;
        }
        return TRANSPORT_HEADERS.contains(name);
    }

    private void verifyBody(JsonNode expectedBody, byte[] bytes) {
        if (expectedBody == null) {
            if (bytes.length != 0) {
                throw mismatch("Mock upstream request body does not match");
            }
            return;
        }
        if (bytes.length == 0) {
            throw mismatch("Mock upstream request body does not match");
        }
        JsonNode observed;
        try {
            observed = mapper.readTree(bytes);
        } catch (JsonProcessingException failure) {
            throw mismatch("Mock upstream request body is not valid JSON");
        } catch (IOException failure) {
            throw mismatch("Mock upstream request body cannot be read");
        }
        if (observed == null || !expectedBody.equals(canonicalJson(observed))) {
            throw mismatch("Mock upstream request body does not match");
        }
    }

    private Map<String, List<String>> query(String rawQuery) {
        if (rawQuery == null) {
            return Map.of();
        }
        if (rawQuery.isEmpty()) {
            throw mismatch("Mock upstream request query is invalid");
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                throw mismatch("Mock upstream request query is invalid");
            }
            int separator = pair.indexOf('=');
            String name = percentDecode(separator < 0 ? pair : pair.substring(0, separator));
            String value = percentDecode(separator < 0 ? "" : pair.substring(separator + 1));
            values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((name, entries) -> result.put(name, List.copyOf(entries)));
        return Collections.unmodifiableMap(result);
    }

    private String percentDecode(String value) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()) {
                    throw mismatch("Mock upstream request query encoding is invalid");
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw mismatch("Mock upstream request query encoding is invalid");
                }
                decoded.write((high << 4) + low);
                index += 3;
            } else {
                byte[] bytes = String.valueOf(current).getBytes(StandardCharsets.UTF_8);
                decoded.writeBytes(bytes);
                index++;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded.toByteArray()))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw mismatch("Mock upstream request query encoding is invalid");
        }
    }

    private JsonNode canonicalJson(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(field -> fields.put(field.getKey(), field.getValue()));
            fields.forEach((name, value) -> result.set(name, canonicalJson(value)));
            return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode();
            node.forEach(value -> result.add(canonicalJson(value)));
            return result;
        }
        if (node.isNumber()) {
            return mapper.getNodeFactory().numberNode(node.decimalValue().stripTrailingZeros());
        }
        return node.deepCopy();
    }

    private byte[] configuredResponse(Object responseBody) {
        byte[] response;
        try {
            response = mapper.writeValueAsBytes(responseBody);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Mock upstream response fixture is invalid");
        }
        if (response.length > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Mock upstream response fixture exceeded the size limit");
        }
        return response;
    }

    private static Map<String, String> mergeEnvironmentOverrides(
            List<UpstreamCallExpectation> expectations) {
        Map<String, String> result = new TreeMap<>();
        for (UpstreamCallExpectation expectation : expectations) {
            expectation.environmentOverrides().forEach((name, value) -> {
                String previous = result.putIfAbsent(name, value);
                if (previous != null && !previous.equals(value)) {
                    throw new IllegalArgumentException("Mock upstream environment overrides are inconsistent");
                }
            });
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        responseWriter.write(exchange, status, body);
    }

    private static void writeResponse(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (exchange.getResponseHeaders().getFirst("Content-Type") == null) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private void respondQuietly(HttpExchange exchange, int status, byte[] body) {
        try {
            respond(exchange, status, body);
        } catch (IOException ignored) {
            // Verification completion already carries the safe failure state.
        }
    }

    private RequestMismatch mismatch(String message) {
        return new RequestMismatch(400, message);
    }

    private static URI baseUri(InetSocketAddress address) {
        try {
            return new URI("http", null, address.getAddress().getHostAddress(), address.getPort(), null, null, null);
        } catch (URISyntaxException failure) {
            throw new IllegalStateException("Loopback mock upstream URI cannot be created", failure);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (observationMonitor) {
            if (!observationSealed || requestInFlight) {
                observationSealed = true;
                recordFailure(new VerificationException(
                        "Mock upstream server closed before request observation was sealed"));
            }
            observationMonitor.notifyAll();
        }
        server.stop(0);
        executor.shutdownNow();
        VerificationException lateFailure;
        synchronized (observationMonitor) {
            lateFailure = lateRequestFailure;
        }
        if (lateFailure != null) {
            throw lateFailure;
        }
    }

    private static long deadline(Duration timeout) {
        long now = System.nanoTime();
        long nanos = timeout.toNanos();
        return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
    }

    public static final class VerificationException extends RuntimeException {
        private VerificationException(String message) {
            super(message);
        }

        private VerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class RequestMismatch extends RuntimeException {
        private final int status;

        private RequestMismatch(int status, String message) {
            super(message);
            this.status = status;
        }

        private int status() {
            return status;
        }
    }

    private record RequestAdmission(int index) {}

    @FunctionalInterface
    interface ResponseWriter {
        void write(HttpExchange exchange, int status, byte[] body) throws IOException;
    }
}
