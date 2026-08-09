# P1 Tools Call Mock Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validate one configured generated MCP Tool with a real `tools/call` request against a loopback mock upstream before publishing the project ZIP.

**Architecture:** Extend the strict generation configuration with one representative call and validate its JSON-compatible arguments against the final Tool IR. Pass that immutable expectation to `generator-validation`, where a loopback-only mock upstream and the existing MCP client verify the HTTP binding and returned JSON as a new fail-closed `MCP_TOOL_CALL` stage.

**Tech Stack:** Java 21, Gradle 9.6.1, Jackson Databind/YAML, JDK `HttpClient`, JDK `HttpServer`, JUnit 5, Spring Boot 4.1.0, Spring AI 2.0.0

## Global Constraints

- Keep the generated target pinned to `spring-ai-2.0-java21-mvc-streamable`.
- Validate exactly one representative `tools/call` targeting one enabled operation per generation;
  other enabled operations may still be generated.
- Require explicit call arguments; never synthesize values from schema patterns.
- Derive method, path, query, header, body, and secret expectations only from the final `McpToolDefinition`.
- Bind the mock upstream only to loopback and never call the OpenAPI provider URL.
- Inject deterministic synthetic secrets only into the generated application process and never persist argument or secret values.
- Keep configuration at 1 MiB, argument depth at 16, object members and array items at 256, strings at 2,048 characters, and HTTP bodies at 1 MiB.
- Preserve fail-closed packaging: any `MCP_TOOL_CALL` failure produces `UNVERIFIED` and no ZIP.
- Add no external dependency; use the existing Jackson libraries and JDK HTTP APIs.
- Exclude Windows host support, response normalization, Java 17, Spring AI 1.x, UI, retry, metrics, and tracing.

## File Structure

- `generator-domain/.../config/GenerationRequest.java`: immutable configured validation call.
- `generator-domain/.../generation/GenerationContracts.java`: immutable validator-facing expected call.
- `generator-domain/.../error/GeneratorErrorCode.java`: P1 validation error vocabulary.
- `generator-cli/.../GenerationConfigurationReader.java`: strict YAML and bounded JSON argument parsing.
- `generator-core/.../ExpectedToolCallFactory.java`: operation lookup and recursive Tool IR argument validation.
- `generator-core/.../GenerationPipeline.java`: construct and pass the expected call to validation.
- `generator-validation/.../McpStreamableHttpClient.java`: issue and validate JSON-RPC `tools/call`.
- `generator-validation/.../MockUpstreamServer.java`: loopback HTTP observation and fixed response.
- `generator-validation/.../UpstreamCallExpectation.java`: derive independent wire expectations from Tool IR.
- `generator-validation/.../BoundedProcessRunner.java`: launch the generated application with a sanitized environment.
- `generator-validation/.../GradleMcpProjectValidator.java`: orchestrate mock, application, MCP call, cleanup, and stages.
- `generator-cli/src/integrationTest/.../P1GenerationIntegrationTest.java`: real generated-project P1 journey.
- `README.md`: P1 configuration, stage, and boundary documentation.

---

### Task 1: Add the Strict Representative Call Configuration

**Files:**
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/config/GenerationRequest.java`
- Modify: `generator-cli/src/main/java/io/gen2spring/mcp/cli/GenerationConfigurationReader.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/GenerationConfigurationReaderTest.java`
- Modify: `generator-cli/src/test/resources/config/weather-generation.yaml`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java`
- Modify: `generator-policy/src/test/java/io/gen2spring/mcp/policy/ToolModelFactoryTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GeneratedWeatherValidationSmokeTest.java`

**Interfaces:**
- Produces: `GenerationRequest.ValidationConfiguration(ToolCallValidation toolCall)`
- Produces: `GenerationRequest.ToolCallValidation(String operationId, Map<String, Object> arguments)`
- Consumes later: `request.validation().toolCall()`

- [ ] **Step 1: Write failing strict configuration tests**

Add assertions that nested JSON values survive parsing and defensive copying:

```java
var call = reader.read(configuration).validation().toolCall();
assertEquals("getForecast", call.operationId());
assertEquals(BigInteger.valueOf(3), call.arguments().get("days"));
assertEquals(List.of("public"), call.arguments().get("tags"));
assertEquals(Map.of("latitude", new BigDecimal("37.5"), "longitude", new BigDecimal("127.0")),
        call.arguments().get("location"));
assertThrows(UnsupportedOperationException.class,
        () -> call.arguments().put("days", 4));
```

Add table-driven invalid YAML cases for missing `validation`, unknown validation fields, null values,
depth 17, 257 members, 257 array items, 2,049-character strings, aliases/tags inside arguments, and
numeric/boolean scalars outside `arguments`.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-cli:test \
  --tests 'io.gen2spring.mcp.cli.GenerationConfigurationReaderTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails because `GenerationRequest.validation()` and the raw validation records do not exist.

- [ ] **Step 3: Add immutable domain records**

Change the request shape and recursively copy JSON-compatible values:

```java
public record GenerationRequest(
        ProjectCoordinates project,
        String provider,
        String domain,
        String targetProfileId,
        ValidationLevel validationLevel,
        ValidationConfiguration validation,
        List<OperationSelection> operations) {

    public record ValidationConfiguration(ToolCallValidation toolCall) {}

    public record ToolCallValidation(String operationId, Map<String, Object> arguments) {
        public ToolCallValidation {
            if (operationId == null || operationId.isBlank() || arguments == null) {
                throw new IllegalArgumentException("Tool call validation is incomplete");
            }
            arguments = immutableJsonMap(arguments);
        }
    }
}
```

The recursive copier must accept `String`, `Number`, `Boolean`, `Map<String, ?>`, and `List<?>`, reject
null or other types, reject blank keys, and return unmodifiable insertion-ordered collections.

- [ ] **Step 4: Parse only the `arguments` subtree as typed YAML**

Add root/validation field sets and raw records:

```java
private static final Set<String> ROOT_FIELDS = Set.of(
        "project", "provider", "domain", "targetProfileId", "validationLevel", "validation", "operations");
private static final Set<String> VALIDATION_FIELDS = Set.of("toolCall");
private static final Set<String> TOOL_CALL_FIELDS = Set.of("operationId", "arguments");

private record RawValidation(RawToolCall toolCall) {}
private record RawToolCall(String operationId, JsonNode arguments) {}
```

Extend `YamlContainer` with a `jsonValueMode` flag. Set it when entering the mapping value of the key
`arguments`, propagate it to descendants, and allow only implicit `STR`, `INT`, `FLOAT`, and `BOOL`
tags in that subtree. Continue rejecting `NULL`, timestamps, explicit tags, aliases, non-string keys,
and typed scalars everywhere else except the existing `enabled` boolean.

Convert the `arguments` `JsonNode` recursively with these exact bounds:

```java
private static final int MAX_ARGUMENT_DEPTH = 16;
private static final int MAX_ARGUMENT_MEMBERS = 256;
private static final int MAX_ARGUMENT_ITEMS = 256;
private static final int MAX_ARGUMENT_STRING_CHARACTERS = 2_048;
```

Use `bigIntegerValue()` for integral nodes and `decimalValue()` for floating nodes so precision is not
lost. Enable `USE_BIG_INTEGER_FOR_INTS` and `USE_BIG_DECIMAL_FOR_FLOATS` on the YAML mapper and require
`validation.toolCall` when `validationLevel == MCP_PROTOCOL`.

- [ ] **Step 5: Update every request fixture and run module tests GREEN**

Add the explicit weather validation block to CLI resources and pass a valid `ValidationConfiguration`
from every direct `new GenerationRequest(...)` test helper.

Run:

```bash
mise exec -- ./gradlew :generator-domain:test :generator-policy:test \
  :generator-spring-ai-2:test :generator-validation:test :generator-cli:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: all five module test tasks pass.

- [ ] **Step 6: Commit Task 1**

```bash
git add generator-domain/src/main/java/io/gen2spring/mcp/domain/config/GenerationRequest.java \
  generator-cli/src/main/java/io/gen2spring/mcp/cli/GenerationConfigurationReader.java \
  generator-cli/src/test generator-policy/src/test generator-spring-ai-2/src/test \
  generator-validation/src/test generator-core/src/test
git commit -m "feat(config): add representative tool call validation input"
```

---

### Task 2: Validate Arguments Against the Final Tool IR

**Files:**
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/generation/GenerationContracts.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/error/GeneratorErrorCode.java`
- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/ExpectedToolCallFactory.java`
- Create: `generator-core/src/test/java/io/gen2spring/mcp/core/ExpectedToolCallFactoryTest.java`
- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationPipeline.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java`
- Modify: `generator-cli/src/main/java/io/gen2spring/mcp/cli/CliApplication.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/CliApplicationTest.java`

**Interfaces:**
- Consumes: `ValidationConfiguration` and final `List<McpToolDefinition>`
- Produces: `ExpectedToolCallFactory.create(List<McpToolDefinition>, ValidationConfiguration)`
- Produces: `GenerationContracts.ExpectedToolCall(McpToolDefinition tool, Map<String, Object> arguments)`
- Produces: `ValidationRequest(..., Map<String, ExpectedTool> expectedTools, ExpectedToolCall expectedToolCall)`

- [ ] **Step 1: Write failing semantic validation tests**

Build a Tool with required path/query/body inputs and test success plus one failure per rule:

```java
var result = factory.create(List.of(weatherTool()), validation("getForecast", Map.of(
        "stationId", "STN01",
        "days", BigInteger.valueOf(3),
        "mode", "brief",
        "tags", List.of("public"),
        "location", Map.of("latitude", new BigDecimal("37.5"), "longitude", new BigDecimal("127.0")))));

assertEquals("kma_weather_get_forecast", result.tool().name());
assertEquals(BigInteger.valueOf(3), result.arguments().get("days"));
```

Reject an unknown/disabled operation, unknown argument, missing required argument, wrong primitive type,
non-integral integer, enum mismatch, bound violation, length violation, pattern mismatch, invalid array
item, unknown nested property, and missing nested required property. Assert only safe field names appear in
the exception and never configured values.

- [ ] **Step 2: Run core tests and verify RED**

```bash
mise exec -- ./gradlew :generator-core:test \
  --tests 'io.gen2spring.mcp.core.ExpectedToolCallFactoryTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails because `ExpectedToolCallFactory` and `ExpectedToolCall` do not exist.

- [ ] **Step 3: Add the immutable validator-facing contract**

```java
public record ExpectedToolCall(McpToolDefinition tool, Map<String, Object> arguments) {
    public ExpectedToolCall {
        if (tool == null || arguments == null) {
            throw new IllegalArgumentException("Expected Tool call is incomplete");
        }
        arguments = immutableMap(arguments);
    }
}
```

Reuse the existing recursive immutable JSON copier in `GenerationContracts`; do not retain caller maps.
Append `ExpectedToolCall expectedToolCall` to `ValidationRequest` and reject null in validator request
validation.

- [ ] **Step 4: Implement recursive Tool IR validation**

Use one type-dispatch method:

```java
private void validate(ApiSchema schema, Object value, String inputName) {
    switch (schema.type()) {
        case STRING -> validateString(schema, requireType(value, String.class, inputName), inputName);
        case INTEGER -> validateNumber(schema, integral(value, inputName), inputName);
        case NUMBER -> validateNumber(schema, decimal(value, inputName), inputName);
        case BOOLEAN -> requireType(value, Boolean.class, inputName);
        case ARRAY -> validateArray(schema, requireList(value, inputName), inputName);
        case OBJECT -> validateObject(schema, requireMap(value, inputName), inputName);
    }
}
```

Compare numbers through exact `BigDecimal`, use `String.length()` to match generated Jakarta Validation,
compile the existing trusted OpenAPI regex with `Pattern.compile`, validate arrays recursively, and validate
object keys against `schema.properties()` and `requiredProperties()`.

Wrap every mismatch as:

```java
throw GeneratorException.user(
        VALIDATION_ARGUMENT_INVALID,
        "TOOL_MODEL_VALIDATE",
        "Validation argument does not match Tool input: " + safeInputName);
```

Add `VALIDATION_ARGUMENT_INVALID` to exit code 3 and `MCP_TOOL_CALL_FAILED` to exit code 5 in
`CliApplication` and its exhaustive error-code test.

- [ ] **Step 5: Wire the factory into `GenerationPipeline`**

Create the expectation after Tool IR validation and before source generation:

```java
ExpectedToolCall expectedCall = expectedToolCallFactory.create(tools, request.validation());
report = validator.validate(new ValidationRequest(
        workspace.root(),
        request.project().artifactId(),
        request.validationLevel(),
        expectedToolSchemaFactory.create(tools),
        expectedCall));
```

Update `GenerationPipelineTest` to capture the request and assert operation, final Tool name, defensive
arguments, and source-generation short-circuit on invalid arguments.

- [ ] **Step 6: Run domain/core/CLI tests GREEN and commit**

```bash
mise exec -- ./gradlew :generator-domain:test :generator-core:test :generator-cli:test \
  --no-daemon --non-interactive --rerun-tasks
git add generator-domain generator-core generator-cli/src/main generator-cli/src/test
git commit -m "feat(core): derive validated representative tool calls"
```

---

### Task 3: Extend the MCP Client Through `tools/call`

**Files:**
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/McpStreamableHttpClient.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/McpStreamableHttpClientTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/support/McpTestServer.java`

**Interfaces:**
- Consumes: `ExpectedToolCall`
- Produces: `validate(URI, Map<String, ExpectedTool>, ExpectedToolCall)`
- Produces: `Result(..., long toolsCallDurationMillis)`
- Produces: `McpStage.TOOL_CALL`

- [ ] **Step 1: Add failing client tests**

Capture the third request in `McpTestServer` and assert exact semantic JSON and session reuse:

```java
assertEquals("tools/call", server.request(3).path("method").textValue());
assertEquals("kma_weather_get_forecast",
        server.request(3).path("params").path("name").textValue());
assertEquals(expectedArguments,
        mapper.convertValue(server.request(3).path("params").path("arguments"), Map.class));
assertEquals(server.sessionId(), server.requestHeader(3, "Mcp-Session-Id"));
assertTrue(result.toolsCallDurationMillis() >= 0);
```

Add scenarios for JSON-RPC error, wrong arbitrary-precision id, missing result, `isError=true`, empty
content, non-text content, invalid text JSON, mismatched JSON, timeout, and response overflow.

- [ ] **Step 2: Run the client test and verify RED**

```bash
mise exec -- ./gradlew :generator-validation:test \
  --tests 'io.gen2spring.mcp.validation.McpStreamableHttpClientTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails because the client has no expected-call parameter or call duration.

- [ ] **Step 3: Serialize the call request with Jackson**

Build the request as nodes, never string interpolation:

```java
ObjectNode request = objectMapper.createObjectNode();
request.put("jsonrpc", "2.0");
request.put("id", 3);
request.put("method", "tools/call");
ObjectNode params = request.putObject("params");
params.put("name", expectedCall.tool().name());
params.set("arguments", objectMapper.valueToTree(expectedCall.arguments()));
```

Send it with the initialized session, parse response id `3` through the existing exact integer envelope
check, and record a separate call duration. Extend `McpValidationException.withDurations` to preserve all
three stage durations.

- [ ] **Step 4: Validate the Spring AI Tool result**

Require an object result, absent/false `isError`, and at least one text content entry. Parse exactly one
JSON text payload and compare it with the expected fixed mock JSON after recursive key ordering and exact
numeric normalization. Reject ambiguous multiple text payloads.

```java
JsonNode expected = objectMapper.createObjectNode()
        .put("validated", true)
        .put("operationId", expectedCall.tool().operationId());
if (!canonicalJson(actual).equals(canonicalJson(expected))) {
    throw failure(McpStage.TOOL_CALL, "MCP Tool result does not match the mock upstream contract", null);
}
```

- [ ] **Step 5: Run validation tests GREEN and commit**

```bash
mise exec -- ./gradlew :generator-validation:test \
  --tests 'io.gen2spring.mcp.validation.McpStreamableHttpClientTest' \
  --no-daemon --non-interactive --rerun-tasks
git add generator-validation/src/main/java/io/gen2spring/mcp/validation/McpStreamableHttpClient.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/McpStreamableHttpClientTest.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/support/McpTestServer.java
git commit -m "feat(validation): validate MCP tool calls"
```

---

### Task 4: Build the Loopback Upstream Contract Verifier

**Files:**
- Create: `generator-validation/src/main/java/io/gen2spring/mcp/validation/UpstreamCallExpectation.java`
- Create: `generator-validation/src/main/java/io/gen2spring/mcp/validation/MockUpstreamServer.java`
- Create: `generator-validation/src/test/java/io/gen2spring/mcp/validation/UpstreamCallExpectationTest.java`
- Create: `generator-validation/src/test/java/io/gen2spring/mcp/validation/MockUpstreamServerTest.java`

**Interfaces:**
- Produces: `UpstreamCallExpectation.from(ExpectedToolCall)`
- Produces: `MockUpstreamServer.start(UpstreamCallExpectation)`
- Produces: `URI baseUri()`, `Map<String, String> environmentOverrides()`, and `void awaitVerified(Duration)`

- [ ] **Step 1: Write failing expectation tests**

For the weather Tool assert exact independent wire values:

```java
assertEquals("POST", expectation.method());
assertEquals("/stations/STN01/forecast", expectation.rawPath());
assertEquals(Map.of(
        "days", List.of("3"),
        "mode", List.of("brief"),
        "tags", List.of("public", "forecast"),
        "serviceKey", List.of("mcp-validation-secret-1")), expectation.query());
assertEquals(List.of("validator"), expectation.headers().get("clientversion"));
assertEquals(List.of("mcp-validation-secret-2"), expectation.headers().get("x-weather-key"));
assertEquals(expectedBody, expectation.body());
```

Test URI encoding for path values, repeated query order, case-folded header targets, optional omission,
required empty object body, deterministic secret ordering by environment variable/target, and duplicate
binding rejection.

- [ ] **Step 2: Run expectation tests and verify RED**

```bash
mise exec -- ./gradlew :generator-validation:test \
  --tests 'io.gen2spring.mcp.validation.UpstreamCallExpectationTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails because the expectation type does not exist.

- [ ] **Step 3: Derive the wire expectation from Tool IR**

Use `ParameterBinding.sourceName()` to read arguments and `targetName()` for wire names. Match generated
runtime behavior: path expansion with encoded values, repeated query values for lists, header values through
`String.valueOf`, and a `LinkedHashMap` object body. Create one synthetic secret per unique environment
variable and apply every `SecretBinding` target without consulting `System.getenv()`.

Return immutable maps and reject missing required path/secret values or a binding shape outside the P0
serialization contract.

- [ ] **Step 4: Write failing mock server tests**

Start the server and send matching/mismatching requests with JDK `HttpClient`. Cover wrong method/path,
missing/extra/reordered query values, case-insensitive headers, JSON field-order and numeric-equivalence
success, body mismatch, 1 MiB overflow, duplicate request, timeout, and close-before-request.

- [ ] **Step 5: Implement the bounded one-shot server**

```java
static MockUpstreamServer start(UpstreamCallExpectation expectation) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    MockUpstreamServer mock = new MockUpstreamServer(server, expectation);
    server.createContext("/", mock::handle);
    server.setExecutor(Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("mock-upstream-", 0).factory()));
    server.start();
    return mock;
}
```

Bound the request body with `readNBytes(MAX_REQUEST_BYTES + 1)`. Compare raw path, decoded query multimap,
case-folded relevant headers, and canonical JSON. Return only
`{"validated":true,"operationId":"..."}` on success. Complete one `CompletableFuture<Void>` on match
or a safe mismatch exception; never include observed values in exception messages.

- [ ] **Step 6: Run mock tests GREEN and commit**

```bash
mise exec -- ./gradlew :generator-validation:test \
  --tests 'io.gen2spring.mcp.validation.UpstreamCallExpectationTest' \
  --tests 'io.gen2spring.mcp.validation.MockUpstreamServerTest' \
  --no-daemon --non-interactive --rerun-tasks
git add generator-validation/src/main/java/io/gen2spring/mcp/validation/UpstreamCallExpectation.java \
  generator-validation/src/main/java/io/gen2spring/mcp/validation/MockUpstreamServer.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/UpstreamCallExpectationTest.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/MockUpstreamServerTest.java
git commit -m "feat(validation): verify generated upstream requests"
```

---

### Task 5: Add Sanitized Application Environment Overrides

**Files:**
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/BoundedProcessRunner.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/BoundedProcessRunnerTest.java`
- Create: `generator-validation/src/test/java/io/gen2spring/mcp/validation/support/EnvironmentProbeProcess.java`

**Interfaces:**
- Produces: `start(List<String>, Path, int, Map<String, String>)`
- Preserves: existing `start(List<String>, Path, int)` and `run(...)` behavior for Gradle compilation

- [ ] **Step 1: Write failing sanitized-environment tests**

Launch `EnvironmentProbeProcess` with absolute Java executable and overrides:

```java
Map<String, String> overrides = Map.of(
        "PROVIDER_BASE_URL", "http://127.0.0.1:12345",
        "KMA_SERVICE_KEY", "mcp-validation-secret-1");
try (var process = runner.start(command, root, 8_192, overrides)) {
    assertTrue(process.awaitExit(Duration.ofSeconds(3)));
}
assertEquals(overrides, readProbeOutput(root));
assertFalse(readProbeOutput(root).containsKey("UNRELATED_PARENT_SECRET"));
```

Reject lowercase/oversized/control-character keys, null/oversized/control-character values, and caller map
mutation. Verify the existing three-argument overload still inherits the normal build environment.

- [ ] **Step 2: Run the process test and verify RED**

```bash
mise exec -- ./gradlew :generator-validation:test \
  --tests 'io.gen2spring.mcp.validation.BoundedProcessRunnerTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails because the environment overload is absent.

- [ ] **Step 3: Implement the application-only sanitized overload**

```java
public RunningProcess start(
        List<String> command,
        Path workingRoot,
        int maxBytes,
        Map<String, String> environmentOverrides) throws IOException {
    ProcessBuilder builder = processBuilder(command, workingRoot);
    Map<String, String> environment = builder.environment();
    environment.clear();
    environment.putAll(validatedEnvironment(environmentOverrides));
    return start(builder, maxBytes);
}
```

Refactor common process/output setup into private methods. Keep the existing overload on its current inherited
environment path so Gradle wrapper discovery and dependency resolution behavior do not change. Limit keys to
`[A-Z][A-Z0-9_]{0,127}` and values to 2,048 non-control characters.

- [ ] **Step 4: Run process and validator regression tests GREEN and commit**

```bash
mise exec -- ./gradlew :generator-validation:test \
  --tests 'io.gen2spring.mcp.validation.BoundedProcessRunnerTest' \
  --tests 'io.gen2spring.mcp.validation.GradleMcpProjectValidatorTest' \
  --no-daemon --non-interactive --rerun-tasks
git add generator-validation/src/main/java/io/gen2spring/mcp/validation/BoundedProcessRunner.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/BoundedProcessRunnerTest.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/support/EnvironmentProbeProcess.java
git commit -m "feat(validation): isolate generated application environments"
```

---

### Task 6: Integrate the Fail-Closed `MCP_TOOL_CALL` Stage

**Files:**
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/GradleMcpProjectValidator.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GradleMcpProjectValidatorTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/support/McpTestApplication.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GeneratedWeatherValidationSmokeTest.java`

**Interfaces:**
- Consumes: `ExpectedToolCall`, `UpstreamCallExpectation`, `MockUpstreamServer`, sanitized process environment
- Produces: ordered stage list ending in `MCP_TOOL_CALL`

- [ ] **Step 1: Add failing validator stage tests**

Update the success expectation:

```java
assertEquals(List.of(
        "COMPILE",
        "APPLICATION_CONTEXT",
        "MCP_INITIALIZE",
        "MCP_TOOLS_LIST",
        "MCP_TOOL_CALL"),
        report.stages().stream().map(ValidationStageResult::stage).toList());
assertTrue(report.stages().stream().allMatch(stage -> stage.status() == SUCCESS));
```

Add cases for mock bind failure, missing upstream request, upstream mismatch, MCP result mismatch, application
exit during call, mock timeout, cleanup failure, and secret-like configured values absent from every summary.
Assert call failure leaves prior stages `SUCCESS`, call `FAILED`, report `UNVERIFIED`, and both application
and mock executor terminated.

- [ ] **Step 2: Run validator tests and verify RED**

```bash
mise exec -- ./gradlew :generator-validation:test \
  --tests 'io.gen2spring.mcp.validation.GradleMcpProjectValidatorTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: success test reports only four stages.

- [ ] **Step 3: Orchestrate mock and application lifecycles**

Before application launch:

```java
UpstreamCallExpectation expectation = UpstreamCallExpectation.from(request.expectedToolCall());
try (MockUpstreamServer upstream = MockUpstreamServer.start(expectation)) {
    Map<String, String> environment = new TreeMap<>(expectation.environmentOverrides());
    environment.put("PROVIDER_BASE_URL", upstream.baseUri().toString());
    application = processRunner.start(command, request.root(), maxProcessOutputBytes, environment);
    mcpResult = mcpClient.validate(
            readinessResult.endpoint(), request.expectedTools(), request.expectedToolCall());
    upstream.awaitVerified(startupTimeout);
}
```

Keep application cleanup in the outer `finally` and mock cleanup in try-with-resources. Preserve the primary
failure and attach cleanup failures without replacing it. Never include exception cause text from request
mismatch in a report summary.

- [ ] **Step 4: Map client results and failures to five stages**

Append success durations from `McpStreamableHttpClient.Result`. On `McpValidationException`, add success or
failure stages based on `exception.stage()` and append `SKIPPED` for remaining stages. Use only these safe
summaries:

```text
MCP initialize contract matched
MCP Tool metadata matched the generated contract
Representative MCP Tool call matched the mock upstream contract
MCP Tool call validation failed safely
```

Update `ORDERED_STAGES` and request validation to require `expectedToolCall` whose Tool name exists in
`expectedTools`.

- [ ] **Step 5: Extend the real generated weather smoke test**

Supply valid weather arguments and configure both query/header API Key bindings. The generated application
must call the real loopback mock and complete all five stages. Assert the Tool call stage succeeds without
argument or synthetic secret text in its summary.

- [ ] **Step 6: Run the full validation module GREEN and commit**

```bash
mise exec -- ./gradlew :generator-validation:test \
  --no-daemon --non-interactive --rerun-tasks
git add generator-validation/src
git commit -m "feat(validation): gate artifacts on representative tool calls"
```

---

### Task 7: Prove the P1 CLI Journey and Synchronize Documentation

**Files:**
- Rename: `generator-cli/src/integrationTest/java/io/gen2spring/mcp/cli/P0GenerationIntegrationTest.java` to `generator-cli/src/integrationTest/java/io/gen2spring/mcp/cli/P1GenerationIntegrationTest.java`
- Modify: `generator-cli/src/integrationTest/resources/config/weather-generation.yaml`
- Modify: `generator-cli/src/test/resources/config/weather-generation.yaml`
- Modify: `README.md`

**Interfaces:**
- Validates: installed CLI generation, real Spring AI endpoint, loopback upstream, report, checksum, and ZIP

- [ ] **Step 1: Make the CLI integration expectation fail on P0 behavior**

Rename the class to `P1GenerationIntegrationTest`, add the exact validation YAML from the approved design,
and require the report to contain five successful stages:

```java
assertEquals(List.of(
        "COMPILE",
        "APPLICATION_CONTEXT",
        "MCP_INITIALIZE",
        "MCP_TOOLS_LIST",
        "MCP_TOOL_CALL"), stageNames(report));
assertEquals("SUCCESS", stage(report, "MCP_TOOL_CALL").path("status").textValue());
assertTrue(Files.isRegularFile(result.archive()));
```

Add a failure fixture with an invalid representative argument and assert exit code 3, no output source, no
ZIP, and no configured value in stdout/stderr. Keep call-mismatch fail-closed coverage in
`GradleMcpProjectValidatorTest` and `GenerationPipelineTest`; do not add a production test hook to the CLI.

- [ ] **Step 2: Run the focused integration test and verify RED**

```bash
mise exec -- ./gradlew :generator-cli:integrationTest \
  --tests 'io.gen2spring.mcp.cli.P1GenerationIntegrationTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: the report lacks `MCP_TOOL_CALL` until Tasks 1-6 are integrated.

- [ ] **Step 3: Synchronize report assertions and README**

Document:

```yaml
validation:
  toolCall:
    operationId: getForecast
    arguments:
      stationId: STN01
      days: 3
      location:
        latitude: 37.5
        longitude: 127.0
```

Update the validation sequence to five stages, state that only loopback mock upstream is contacted, explain
that values are not persisted, and remove `tools/call` mock validation from the known P1 boundary. Keep
response normalization, Java 17, Spring AI 1.x, UI, metrics, and Windows support listed as future work.

- [ ] **Step 4: Run the focused journey GREEN**

```bash
mise exec -- ./gradlew :generator-cli:integrationTest \
  --tests 'io.gen2spring.mcp.cli.P1GenerationIntegrationTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; the generated project compiles, starts, lists Tools, calls the representative
Tool, matches the upstream request/response, writes `VALIDATED`, and publishes the ZIP.

- [ ] **Step 5: Run the complete repository verification**

```bash
mise exec -- ./gradlew clean test integrationTest :generator-cli:installDist \
  --no-daemon --non-interactive --rerun-tasks
git diff --check
```

Expected: all Gradle tasks pass, no whitespace errors, and `git status --short` contains only intentional P1
changes.

- [ ] **Step 6: Commit Task 7**

```bash
git add README.md generator-cli/src/integrationTest generator-cli/src/test/resources/config/weather-generation.yaml
git commit -m "test: prove the P1 tool call generation journey"
```

---

## Final Review Checklist

- [ ] Compare every changed behavior with `docs/superpowers/specs/2026-08-09-tools-call-mock-validation-design.md`.
- [ ] Confirm argument and synthetic secret values do not appear in generated source, manifest, report, ZIP metadata, stdout, or stderr.
- [ ] Confirm the mock binds to loopback and the generated application receives only the sanitized environment.
- [ ] Confirm every failure after generation leaves no ZIP and reports `UNVERIFIED` with five ordered stages.
- [ ] Run `code-review` on the complete branch diff and address only technically valid findings.
- [ ] Re-run the complete repository verification after review fixes.
