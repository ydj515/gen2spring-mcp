package io.gen2spring.mcp.adapter.validation.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.adapter.validation.runtime.LoopbackPortAllocator;
import io.gen2spring.mcp.application.generation.validation.ExpectedTool;
import io.gen2spring.mcp.application.generation.validation.ExpectedToolCall;
import io.gen2spring.mcp.application.generation.validation.ObservedTool;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class McpStreamableHttpClient {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"openapi-mcp-generator-validator","version":"0.1.0"}}}
            """.strip();
    private static final String INITIALIZED = """
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            """.strip();
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
            """.strip();
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of("2025-03-26");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final int maxResponseBytes;

    public McpStreamableHttpClient() {
        this(DEFAULT_TIMEOUT, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public McpStreamableHttpClient(Duration requestTimeout, int maxResponseBytes) {
        this(HttpClient.newBuilder()
                        .connectTimeout(requirePositive(requestTimeout))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(), requestTimeout, maxResponseBytes);
    }

    McpStreamableHttpClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout,
            int maxResponseBytes) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.requestTimeout = requirePositive(requestTimeout);
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    public Result validate(URI endpoint, Map<String, ExpectedTool> expectedTools) {
        return validate(endpoint, expectedTools, null);
    }

    public Result validate(
            URI endpoint,
            Map<String, ExpectedTool> expectedTools,
            ExpectedToolCall expectedCall) {
        return validate(endpoint, expectedTools, expectedCall, stage -> {});
    }

    public Result validate(
            URI endpoint,
            Map<String, ExpectedTool> expectedTools,
            ExpectedToolCall expectedCall,
            Consumer<McpStage> stageStarted) {
        LoopbackPortAllocator.requireLoopback(endpoint);
        Map<String, ExpectedTool> expected = validatedExpectedTools(expectedTools);
        Objects.requireNonNull(stageStarted, "stageStarted");
        stageStarted.accept(McpStage.INITIALIZE);
        long initializeStarted = System.nanoTime();
        String sessionId;
        try {
            HttpResponse<byte[]> initializeResponse = send(endpoint, INITIALIZE, null, McpStage.INITIALIZE);
            JsonNode initializeJson = responseJson(initializeResponse, 1, McpStage.INITIALIZE);
            validateInitializeResult(requireResult(initializeJson, McpStage.INITIALIZE));
            sessionId = sessionId(initializeResponse);

            HttpResponse<byte[]> notification = send(endpoint, INITIALIZED, sessionId, McpStage.INITIALIZE);
            requireSuccessStatus(notification, McpStage.INITIALIZE);
            String updatedSession = sessionId(notification);
            if (updatedSession != null) {
                sessionId = updatedSession;
            }
        } catch (McpValidationException failure) {
            throw failure.withDurations(elapsedMillis(initializeStarted), 0, 0);
        }
        long initializeDuration = elapsedMillis(initializeStarted);

        stageStarted.accept(McpStage.TOOLS_LIST);
        long toolsStarted = System.nanoTime();
        List<ObservedTool> observed;
        try {
            HttpResponse<byte[]> toolsResponse = send(endpoint, TOOLS_LIST, sessionId, McpStage.TOOLS_LIST);
            JsonNode toolsJson = responseJson(toolsResponse, 2, McpStage.TOOLS_LIST);
            observed = validateTools(requireResult(toolsJson, McpStage.TOOLS_LIST), expected);
        } catch (McpValidationException failure) {
            throw failure.withDurations(initializeDuration, elapsedMillis(toolsStarted), 0);
        }
        long toolsDuration = elapsedMillis(toolsStarted);
        Set<String> names = new LinkedHashSet<>();
        observed.forEach(tool -> names.add(tool.name()));
        if (expectedCall == null) {
            return new Result(Collections.unmodifiableSet(names), List.copyOf(observed), initializeDuration, toolsDuration, 0);
        }

        stageStarted.accept(McpStage.TOOL_CALL);
        long toolCallStarted = System.nanoTime();
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", 3);
            request.put("method", "tools/call");
            ObjectNode params = request.putObject("params");
            params.put("name", expectedCall.tool().name());
            params.set("arguments", objectMapper.valueToTree(expectedCall.arguments()));
            HttpResponse<byte[]> response = send(
                    endpoint,
                    objectMapper.writeValueAsString(request),
                    sessionId,
                    McpStage.TOOL_CALL);
            JsonNode responseJson = responseJson(response, 3, McpStage.TOOL_CALL);
            validateToolCallResult(requireResult(responseJson, McpStage.TOOL_CALL), expectedCall);
        } catch (JsonProcessingException exception) {
            throw failure(McpStage.TOOL_CALL, "MCP Tool call request cannot be serialized", exception)
                    .withObservedTools(observed)
                    .withDurations(initializeDuration, toolsDuration, elapsedMillis(toolCallStarted));
        } catch (McpValidationException failure) {
            throw failure.withObservedTools(observed)
                    .withDurations(initializeDuration, toolsDuration, elapsedMillis(toolCallStarted));
        }
        long toolCallDuration = elapsedMillis(toolCallStarted);
        return new Result(
                Collections.unmodifiableSet(names),
                List.copyOf(observed),
                initializeDuration,
                toolsDuration,
                toolCallDuration);
    }

    public void validateDisabledProtocol(URI endpoint, String disabledVersion, List<String> enabledVersions) {
        LoopbackPortAllocator.requireLoopback(endpoint);
        String body = INITIALIZE;
        if ("2026-07-28".equals(disabledVersion)) {
            ObjectNode request = objectMapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1)
                    .put("method", "server/discover");
            var meta = request.putObject("params").putObject("_meta");
            meta.put("io.modelcontextprotocol/protocolVersion", disabledVersion);
            meta.putObject("io.modelcontextprotocol/clientCapabilities");
            body = request.toString();
        }
        HttpResponse<byte[]> response = send(endpoint, body, null, McpStage.INITIALIZE);
        JsonNode error = parseResponseJson(new String(response.body(), StandardCharsets.UTF_8), McpStage.INITIALIZE);
        JsonNode data = error.path("error").path("data");
        if (response.statusCode() != 400 || error.path("error").path("code").asInt() != -32022
                || !disabledVersion.equals(data.path("requested").asText())
                || !objectMapper.valueToTree(enabledVersions).equals(data.path("supported"))) {
            throw failure(McpStage.INITIALIZE, "Disabled MCP protocol was not rejected with the selected version list", null);
        }
    }

    public Result validateModern(URI endpoint, Map<String, ExpectedTool> expectedTools,
            ExpectedToolCall expectedCall) {
        LoopbackPortAllocator.requireLoopback(endpoint);
        Map<String, ExpectedTool> expected = validatedExpectedTools(expectedTools);
        long started = System.nanoTime();
        JsonNode discovery = modernRequest(endpoint, 1, "server/discover", Map.of(), McpStage.INITIALIZE);
        validateCacheHints(discovery, McpStage.INITIALIZE);
        boolean supported = false;
        for (JsonNode version : discovery.path("supportedVersions")) {
            supported |= "2026-07-28".equals(version.asText());
        }
        if (!supported || !discovery.path("capabilities").path("tools").isObject()) {
            throw failure(McpStage.INITIALIZE, "MCP discovery does not advertise modern tools support", null);
        }
        long discoveryMillis = elapsedMillis(started);
        started = System.nanoTime();
        JsonNode listed = modernRequest(endpoint, 2, "tools/list", Map.of(), McpStage.TOOLS_LIST);
        validateCacheHints(listed, McpStage.TOOLS_LIST);
        List<ObservedTool> observed = validateTools(listed, expected);
        long listMillis = elapsedMillis(started);
        started = System.nanoTime();
        if (expectedCall != null) {
            JsonNode called = modernRequest(endpoint, 3, "tools/call",
                    Map.of("name", expectedCall.tool().name(), "arguments", expectedCall.arguments()), McpStage.TOOL_CALL);
            validateToolCallResult(called, expectedCall);
        }
        Set<String> names = new LinkedHashSet<>();
        observed.forEach(tool -> names.add(tool.name()));
        return new Result(names, observed, discoveryMillis, listMillis,
                expectedCall == null ? 0 : elapsedMillis(started));
    }

    private JsonNode modernRequest(URI endpoint, long id, String method, Map<String, Object> values, McpStage stage) {
        ObjectNode request = objectMapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        ObjectNode params = request.putObject("params");
        values.forEach((key, value) -> params.set(key, objectMapper.valueToTree(value)));
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        meta.putObject("io.modelcontextprotocol/clientCapabilities");
        HttpResponse<byte[]> response = send(endpoint, request.toString(), null, stage);
        if (response.headers().firstValue("Mcp-Session-Id").isPresent()) {
            throw failure(stage, "Modern MCP response must not create a session", null);
        }
        JsonNode result = requireResult(responseJson(response, id, stage), stage);
        if (!"complete".equals(result.path("resultType").asText())) {
            throw failure(stage, "Modern MCP resultType must be complete", null);
        }
        return result;
    }

    private void validateCacheHints(JsonNode result, McpStage stage) {
        if (!result.path("ttlMs").isNumber() || result.path("ttlMs").decimalValue().signum() < 0
                || !Set.of("private", "public").contains(result.path("cacheScope").asText())) {
            throw failure(stage, "Modern MCP cache hints are invalid", null);
        }
    }

    private HttpResponse<byte[]> send(URI endpoint, String body, String sessionId, McpStage stage) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        JsonNode message = parseResponseJson(body, stage);
        if (message.path("params").path("_meta").has("io.modelcontextprotocol/protocolVersion")) {
            request.header("MCP-Protocol-Version", "2026-07-28");
            request.header("Mcp-Method", message.path("method").asText());
            if (message.path("params").has("name")) {
                String name = message.path("params").path("name").asText();
                String encoded = "=?base64?" + java.util.Base64.getEncoder()
                        .encodeToString(name.getBytes(StandardCharsets.UTF_8)) + "?=";
                request.header("Mcp-Name", encoded);
            }
        }
        long deadline = deadline(requestTimeout);
        BoundedBodyHandler bodyHandler = new BoundedBodyHandler(maxResponseBytes);
        CompletableFuture<HttpResponse<byte[]>> exchange;
        try {
            exchange = httpClient.sendAsync(request.build(), bodyHandler);
        } catch (RuntimeException exception) {
            bodyHandler.cancel();
            throw failure(stage, "MCP request failed", exception);
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("absolute response deadline elapsed");
            }
            return exchange.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            cancelAndJoin(exchange, bodyHandler);
            throw failure(stage, "MCP response deadline elapsed", exception);
        } catch (InterruptedException exception) {
            cancelAndJoin(exchange, bodyHandler);
            Thread.currentThread().interrupt();
            throw failure(stage, "MCP request was interrupted", exception);
        } catch (ExecutionException | CancellationException exception) {
            cancelAndJoin(exchange, bodyHandler);
            Throwable cause = rootCause(exception);
            if (cause instanceof ResponseTooLargeException) {
                throw failure(stage, "MCP response exceeded the size limit", cause);
            }
            if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
                throw failure(stage, "MCP response deadline elapsed", cause);
            }
            throw failure(stage, "MCP request failed", cause);
        }
    }

    private JsonNode responseJson(HttpResponse<byte[]> response, long expectedId, McpStage stage) {
        requireSuccessStatus(response, stage);
        String contentType = response.headers().firstValue("Content-Type").orElse("")
                .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!("application/json".equals(contentType) || "text/event-stream".equals(contentType))) {
            throw failure(stage, "MCP response content type is unsupported", null);
        }
        byte[] bytes = response.body();
        JsonNode root = "text/event-stream".equals(contentType)
                ? sseResponse(bytes, expectedId, stage)
                : parseResponseJson(new String(bytes, StandardCharsets.UTF_8), stage);
        if (root == null || !root.isObject()
                || !"2.0".equals(root.path("jsonrpc").asText())
                || !root.path("id").isIntegralNumber()
                || !root.path("id").bigIntegerValue().equals(BigInteger.valueOf(expectedId))) {
            throw failure(stage, "MCP JSON-RPC envelope is invalid", null);
        }
        if (root.hasNonNull("error")) {
            throw failure(stage, "MCP JSON-RPC response reported an error", null);
        }
        return root;
    }

    private JsonNode requireResult(JsonNode root, McpStage stage) {
        JsonNode result = root.get("result");
        if (result == null || !result.isObject()) {
            throw failure(stage, "MCP JSON-RPC result is missing", null);
        }
        return result;
    }

    private void validateInitializeResult(JsonNode result) {
        JsonNode protocolVersion = result.get("protocolVersion");
        if (protocolVersion == null || !protocolVersion.isTextual()
                || !SUPPORTED_PROTOCOL_VERSIONS.contains(protocolVersion.textValue())) {
            throw failure(McpStage.INITIALIZE, "MCP initialize protocol version is unsupported", null);
        }
        JsonNode capabilities = result.get("capabilities");
        if (capabilities == null || !capabilities.isObject()) {
            throw failure(McpStage.INITIALIZE, "MCP initialize capabilities are invalid", null);
        }
        JsonNode tools = capabilities.get("tools");
        if (tools == null || !tools.isObject()) {
            throw failure(McpStage.INITIALIZE, "MCP initialize tools capability is missing", null);
        }
        JsonNode listChanged = tools.get("listChanged");
        if (listChanged != null && !listChanged.isBoolean()) {
            throw failure(McpStage.INITIALIZE, "MCP initialize tools capability is invalid", null);
        }
        JsonNode serverInfo = result.get("serverInfo");
        if (serverInfo == null || !serverInfo.isObject()
                || !nonBlankText(serverInfo.get("name"))
                || !nonBlankText(serverInfo.get("version"))) {
            throw failure(McpStage.INITIALIZE, "MCP initialize server info is invalid", null);
        }
    }

    private static boolean nonBlankText(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank();
    }

    private List<ObservedTool> validateTools(JsonNode result, Map<String, ExpectedTool> expected) {
        JsonNode tools = result.get("tools");
        if (tools == null || !tools.isArray()) {
            throw failure(McpStage.TOOLS_LIST, "MCP tools result is missing", null);
        }
        Map<String, ObservedTool> observed = new TreeMap<>();
        for (JsonNode tool : tools) {
            if (!tool.isObject() || !tool.path("name").isTextual() || !tool.path("description").isTextual()) {
                throw failure(McpStage.TOOLS_LIST, "MCP tool metadata is incomplete", null);
            }
            String name = tool.path("name").textValue();
            ExpectedTool expectedTool = expected.get(name);
            if (expectedTool == null || !expectedTool.description().equals(tool.path("description").textValue())) {
                throw failure(McpStage.TOOLS_LIST, "MCP tool metadata does not match the generated contract", null);
            }
            JsonNode inputSchema = tool.get("inputSchema");
            if (inputSchema == null || !inputSchema.isObject()) {
                throw failure(McpStage.TOOLS_LIST, "MCP tool input schema is missing", null);
            }
            JsonNode expectedSchema = objectMapper.valueToTree(expectedTool.inputSchema());
            if (!canonicalSchema(inputSchema).equals(canonicalSchema(expectedSchema))) {
                throw failure(McpStage.TOOLS_LIST, "MCP tool input schema does not match the generated contract", null);
            }
            if (observed.put(name, new ObservedTool(name, expectedTool.description(), true)) != null) {
                throw failure(McpStage.TOOLS_LIST, "MCP tools result contains a duplicate name", null);
            }
        }
        if (!observed.keySet().equals(expected.keySet())) {
            throw failure(McpStage.TOOLS_LIST, "MCP tool names do not match the generated contract", null);
        }
        return List.copyOf(observed.values());
    }

    private void validateToolCallResult(JsonNode result, ExpectedToolCall expectedCall) {
        JsonNode isError = result.get("isError");
        if (isError != null && (!isError.isBoolean() || isError.booleanValue())) {
            throw failure(McpStage.TOOL_CALL, "MCP Tool result reported an error", null);
        }
        JsonNode content = result.get("content");
        if (content == null || !content.isArray()) {
            throw failure(McpStage.TOOL_CALL, "MCP Tool result content is missing", null);
        }
        List<String> textPayloads = new ArrayList<>();
        for (JsonNode entry : content) {
            if (entry.isObject() && "text".equals(entry.path("type").textValue())) {
                JsonNode text = entry.get("text");
                if (text == null || !text.isTextual()) {
                    throw failure(McpStage.TOOL_CALL, "MCP Tool text content is invalid", null);
                }
                textPayloads.add(text.textValue());
            }
        }
        if (textPayloads.size() != 1) {
            throw failure(McpStage.TOOL_CALL, "MCP Tool result must contain exactly one text payload", null);
        }
        JsonNode actual;
        try {
            actual = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(textPayloads.getFirst());
        } catch (JsonProcessingException exception) {
            throw failure(McpStage.TOOL_CALL, "MCP Tool text content is not valid JSON", exception);
        }
        if (actual == null) {
            throw failure(McpStage.TOOL_CALL, "MCP Tool text content is not valid JSON", null);
        }
        JsonNode expected = objectMapper.valueToTree(expectedCall.expectedResult());
        if (!canonicalJson(actual).equals(canonicalJson(expected))) {
            throw failure(McpStage.TOOL_CALL, "MCP Tool result does not match the mock upstream contract", null);
        }
    }

    private JsonNode parseResponseJson(String json, McpStage stage) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ignored) {
            throw failure(stage, "MCP response is not valid JSON", null);
        }
    }

    private JsonNode sseResponse(byte[] bytes, long expectedId, McpStage stage) {
        for (String event : sseData(bytes, stage)) {
            JsonNode candidate = parseResponseJson(event, stage);
            if (candidate != null && candidate.isObject()
                    && "2.0".equals(candidate.path("jsonrpc").asText())
                    && candidate.path("id").isIntegralNumber()
                    && candidate.path("id").bigIntegerValue().equals(BigInteger.valueOf(expectedId))
                    && (candidate.has("result") || candidate.has("error"))) {
                return candidate;
            }
        }
        throw failure(stage, "MCP SSE response contains no matching JSON-RPC response", null);
    }

    private List<String> sseData(byte[] bytes, McpStage stage) {
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<String> events = new ArrayList<>();
        List<String> data = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty() && !data.isEmpty()) {
                events.add(String.join("\n", data));
                data.clear();
            }
            if (line.startsWith("data:")) {
                String value = line.substring(5);
                data.add(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        if (!data.isEmpty()) {
            events.add(String.join("\n", data));
        }
        if (events.isEmpty()) {
            throw failure(stage, "MCP SSE response contains no data event", null);
        }
        return events;
    }

    private JsonNode canonicalSchema(JsonNode schema) {
        JsonNode definitions = schema.path("$defs");
        return canonicalSchema(schema, definitions, new java.util.HashSet<>());
    }

    private JsonNode canonicalJson(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(field -> fields.put(field.getKey(), field.getValue()));
            fields.forEach((name, value) -> canonical.set(name, canonicalJson(value)));
            return canonical;
        }
        if (node.isArray()) {
            var canonical = objectMapper.createArrayNode();
            node.forEach(value -> canonical.add(canonicalJson(value)));
            return canonical;
        }
        if (node.isNumber()) {
            return objectMapper.getNodeFactory().numberNode(node.decimalValue().stripTrailingZeros());
        }
        return node.deepCopy();
    }

    private JsonNode canonicalSchema(JsonNode node, JsonNode definitions, Set<String> resolving) {
        if (node.isObject() && node.path("$ref").isTextual()) {
            String reference = node.path("$ref").textValue();
            if (!reference.startsWith("#/$defs/") || !resolving.add(reference)) {
                throw failure(McpStage.TOOLS_LIST, "MCP tool input schema reference is invalid", null);
            }
            JsonNode resolved = definitions.at(reference.substring("#/$defs".length()));
            if (resolved.isMissingNode()) {
                throw failure(McpStage.TOOLS_LIST, "MCP tool input schema reference is unresolved", null);
            }
            JsonNode canonical;
            try {
                canonical = canonicalSchema(resolved, definitions, resolving);
            } finally {
                resolving.remove(reference);
            }
            var siblings = objectMapper.createObjectNode();
            node.properties().forEach(field -> {
                if (!("$ref".equals(field.getKey())
                        || "$schema".equals(field.getKey())
                        || "$defs".equals(field.getKey()))) {
                    siblings.set(field.getKey(), canonicalSchema(field.getValue(), definitions, resolving));
                }
            });
            if (siblings.isEmpty()) {
                return canonical;
            }
            if (!canonical.isObject()) {
                throw failure(McpStage.TOOLS_LIST, "MCP tool input schema reference has incompatible siblings", null);
            }
            var merged = ((com.fasterxml.jackson.databind.node.ObjectNode) canonical).deepCopy();
            siblings.properties().forEach(field -> {
                JsonNode prior = merged.get(field.getKey());
                if (prior != null && !prior.equals(field.getValue())) {
                    throw failure(
                            McpStage.TOOLS_LIST,
                            "MCP tool input schema reference has conflicting siblings",
                            null);
                }
                merged.set(field.getKey(), field.getValue());
            });
            return canonicalSchema(merged, definitions, resolving);
        }
        if (node.isObject()) {
            var canonical = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(field -> {
                if (!("$schema".equals(field.getKey()) || "$defs".equals(field.getKey()))) {
                    fields.put(field.getKey(), field.getValue());
                }
            });
            fields.forEach((name, value) -> canonical.set(name, canonicalSchema(value, definitions, resolving)));
            return canonical;
        }
        if (node.isArray()) {
            List<JsonNode> values = new ArrayList<>();
            node.forEach(value -> values.add(canonicalSchema(value, definitions, resolving)));
            if (values.stream().allMatch(JsonNode::isTextual)) {
                values.sort(java.util.Comparator.comparing(JsonNode::textValue));
            }
            var canonical = objectMapper.createArrayNode();
            values.forEach(canonical::add);
            return canonical;
        }
        if (node.isNumber()) {
            return objectMapper.getNodeFactory().numberNode(node.decimalValue().stripTrailingZeros());
        }
        return node.deepCopy();
    }

    private void requireSuccessStatus(HttpResponse<?> response, McpStage stage) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw failure(stage, "MCP response status is unsuccessful", null);
        }
    }

    private String sessionId(HttpResponse<?> response) {
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            if ("Mcp-Session-Id".equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
                String value = header.getValue().getFirst();
                if (value != null && !value.isBlank() && value.length() <= 512
                        && value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e)) {
                    return value;
                }
                throw failure(McpStage.INITIALIZE, "MCP session identifier is invalid", null);
            }
        }
        return null;
    }

    private static void cancelAndJoin(
            CompletableFuture<?> exchange,
            BoundedBodyHandler bodyHandler) {
        bodyHandler.cancel();
        exchange.cancel(true);
        boolean interrupted = Thread.interrupted();
        try {
            exchange.get(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            interrupted = true;
        } catch (ExecutionException | TimeoutException | CancellationException ignored) {
            // Cancellation is expected; the bounded wait only prevents a detached request.
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Map<String, ExpectedTool> validatedExpectedTools(Map<String, ExpectedTool> expectedTools) {
        if (expectedTools == null) {
            throw new IllegalArgumentException("expectedTools must not be null");
        }
        Map<String, ExpectedTool> copy = new TreeMap<>();
        expectedTools.forEach((name, expected) -> {
            if (name == null || name.isBlank() || expected == null || expected.description() == null) {
                throw new IllegalArgumentException("Expected tool metadata is incomplete");
            }
            copy.put(name, expected);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Duration requirePositive(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        return timeout;
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private static long deadline(Duration duration) {
        long now = System.nanoTime();
        long nanos = duration.toNanos();
        return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
    }

    private static McpValidationException failure(McpStage stage, String message, Throwable cause) {
        return new McpValidationException(stage, message, cause);
    }

    public enum McpStage { INITIALIZE, TOOLS_LIST, TOOL_CALL }

    public static final class McpValidationException extends RuntimeException {
        private final McpStage stage;
        private final long initializeDurationMillis;
        private final long toolsListDurationMillis;
        private final long toolsCallDurationMillis;
        private final List<ObservedTool> tools;

        private McpValidationException(McpStage stage, String message, Throwable cause) {
            this(stage, message, cause, 0, 0, 0, List.of());
        }

        private McpValidationException(
                McpStage stage,
                String message,
                Throwable cause,
                long initializeDurationMillis,
                long toolsListDurationMillis,
                long toolsCallDurationMillis,
                List<ObservedTool> tools) {
            super(message, cause);
            this.stage = stage;
            this.initializeDurationMillis = Math.max(0, initializeDurationMillis);
            this.toolsListDurationMillis = Math.max(0, toolsListDurationMillis);
            this.toolsCallDurationMillis = Math.max(0, toolsCallDurationMillis);
            this.tools = List.copyOf(tools);
        }

        public McpStage stage() {
            return stage;
        }

        public long initializeDurationMillis() {
            return initializeDurationMillis;
        }

        public long toolsListDurationMillis() {
            return toolsListDurationMillis;
        }

        public long toolsCallDurationMillis() {
            return toolsCallDurationMillis;
        }

        public List<ObservedTool> tools() {
            return tools;
        }

        private McpValidationException withObservedTools(List<ObservedTool> observedTools) {
            return new McpValidationException(
                    stage, getMessage(), getCause(), initializeDurationMillis, toolsListDurationMillis,
                    toolsCallDurationMillis, observedTools);
        }

        private McpValidationException withDurations(
                long initializeDuration,
                long toolsListDuration,
                long toolsCallDuration) {
            return new McpValidationException(
                    stage, getMessage(), getCause(), initializeDuration, toolsListDuration, toolsCallDuration,
                    tools);
        }
    }

    public record Result(
            Set<String> toolNames,
            List<ObservedTool> tools,
            long initializeDurationMillis,
            long toolsListDurationMillis,
            long toolsCallDurationMillis) {
        public Result {
            toolNames = Set.copyOf(toolNames);
            tools = List.copyOf(tools);
        }
    }

    static final class BoundedBodyHandler implements HttpResponse.BodyHandler<byte[]> {
        private final int maxBytes;
        private final AtomicReference<BoundedBodySubscriber> subscriber = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        BoundedBodyHandler(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo responseInfo) {
            BoundedBodySubscriber created = new BoundedBodySubscriber(maxBytes);
            if (!subscriber.compareAndSet(null, created)) {
                created.cancel();
                throw new IllegalStateException("MCP response body subscriber was created more than once");
            }
            if (cancelled.get()) {
                created.cancel();
            }
            return created;
        }

        void cancel() {
            cancelled.set(true);
            BoundedBodySubscriber current = subscriber.get();
            if (current != null) {
                current.cancel();
            }
        }
    }

    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean done;

        BoundedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
            this.output = new ByteArrayOutputStream(Math.min(maxBytes, 8_192));
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription candidate) {
            if (subscription != null || done) {
                candidate.cancel();
                return;
            }
            subscription = candidate;
            candidate.request(1);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (done) {
                return;
            }
            long remainingBytes = maxBytes - output.size();
            for (ByteBuffer buffer : buffers) {
                int incoming = buffer.remaining();
                if (incoming > remainingBytes) {
                    fail(new ResponseTooLargeException());
                    return;
                }
                remainingBytes -= incoming;
            }
            for (ByteBuffer buffer : buffers) {
                int incoming = buffer.remaining();
                byte[] bytes = new byte[incoming];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }

        @Override
        public synchronized void onError(Throwable failure) {
            if (!done) {
                done = true;
                body.completeExceptionally(failure);
            }
        }

        @Override
        public synchronized void onComplete() {
            if (!done) {
                done = true;
                body.complete(output.toByteArray());
            }
        }

        synchronized void cancel() {
            if (done) {
                return;
            }
            done = true;
            if (subscription != null) {
                subscription.cancel();
            }
            body.completeExceptionally(new CancellationException("MCP response body cancelled"));
        }

        private void fail(Throwable failure) {
            done = true;
            if (subscription != null) {
                subscription.cancel();
            }
            body.completeExceptionally(failure);
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException {}
}
