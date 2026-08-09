# Response Normalization and Upstream Error Mapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add strict operation-level response normalization and convert provider, HTTP, transport, and local execution failures into deterministic, secret-safe MCP Tool results.

**Architecture:** A shared immutable response policy enters through configuration, is revalidated before Tool IR construction, and is rendered as literal generated metadata. Generated runtime code produces profile-neutral `NormalizedSuccess` or `ProviderError` outcomes; a Spring AI 2 adapter registers low-level synchronous MCP Tool specifications so expected provider errors become `isError=true` while unexpected defects remain JSON-RPC errors. Validation derives one raw mock response and one expected normalized result from the final Tool IR, preserving the exactly-one upstream request gate.

**Tech Stack:** Java 21, Gradle Kotlin DSL, Jackson 2 in the generator, Jackson 3 in generated Spring Boot 4.1.0 projects, Spring AI 2.0.0, MCP Java SDK 0.17.0, JUnit 5.

## Global Constraints

- Keep `spring-ai-2.0-java21-mvc-streamable` pinned to Java 21, Spring Boot 4.1.0, Spring AI 2.0.0, Gradle 9.6.1, MVC, synchronous execution, and Streamable HTTP.
- Add no new external generator or generated-runtime dependency.
- Accept only RFC 6901 pointers starting with `/`, at most 256 characters and 32 tokens; reject controls, invalid `~` escapes, and `-` array lookup tokens. Reject a leading-zero numeric token only when runtime evaluation applies it to an array; the same token remains valid as an object property name.
- Accept 1 to 16 non-null scalar success values; strings are at most 128 characters and contain no controls; preserve string, number, and boolean types.
- Keep response bodies bounded to 1 MiB by default and provider messages bounded to 512 Unicode code points.
- Never expose raw response bodies, request headers, URI queries, environment values, exception messages, stack traces, or secrets in MCP results, validation reports, generated files, or CLI output.
- Preserve raw successful JSON and JSON null behavior for operations without `responseNormalization`; apply the new error contract to every operation.
- Preserve exactly one representative `tools/call` and exactly one verified upstream request during production validation.
- Use TDD for every task and commit only the files listed by that task.

---

### Task 1: Shared Response Policy and Semantic Validator

**Files:**
- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/response/ResponseNormalizationPolicy.java`
- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/response/ResponseNormalizationPolicyValidator.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/config/GenerationRequest.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/tool/McpToolDefinition.java`
- Create: `generator-domain/src/test/java/io/gen2spring/mcp/domain/response/ResponseNormalizationPolicyTest.java`
- Create: `generator-domain/src/test/java/io/gen2spring/mcp/domain/response/ResponseNormalizationPolicyValidatorTest.java`

**Interfaces:**
- Consumes: JSON-compatible scalar values represented as `String`, `Number`, or `Boolean`.
- Produces: `ResponseNormalizationPolicy(String dataPointer, String successCodePointer, List<Object> successValues, String errorMessagePointer, String totalCountPointer)`.
- Produces: `ResponseNormalizationPolicyValidator.requireValid(ResponseNormalizationPolicy policy)` returning the same immutable policy or throwing `IllegalArgumentException` with a fixed, value-free message.
- Produces: `OperationSelection(String operationId, boolean enabled, String toolName, String toolDescription, Map<String, ParameterOverride> parameters, ResponseNormalizationPolicy responseNormalization)` plus the existing five-argument compatibility constructor.
- Produces: `HttpExecutionDefinition(HttpMethod method, URI baseUrl, String path, List<ParameterBinding> bindings, boolean objectRequestBody, boolean requestBodyRequired, ResponseNormalizationPolicy responseNormalization)` plus existing compatibility constructors that pass `null`.

- [ ] **Step 1: Write the failing immutable-model tests**

```java
@Test
void defensivelyCopiesTypedSuccessValues() {
    List<Object> values = new ArrayList<>(List.of("00", new BigDecimal("1.50"), true));
    var policy = new ResponseNormalizationPolicy(
            "/response/body/items", "/response/header/code", values,
            "/response/header/message", "/response/body/totalCount");

    values.clear();

    assertEquals(List.of("00", new BigDecimal("1.50"), true), policy.successValues());
    assertThrows(UnsupportedOperationException.class, () -> policy.successValues().add("01"));
}

@Test
void canonicalizesSupportedNumericInputsAndRejectsMutableNumbers() {
    var policy = new ResponseNormalizationPolicy(null, "/code",
            List.of(7, 0.1d), null, null);

    assertEquals(List.of(BigInteger.valueOf(7), new BigDecimal("0.1")), policy.successValues());
    assertThrows(IllegalArgumentException.class, () -> new ResponseNormalizationPolicy(
            null, "/code", List.of(new AtomicInteger(7)), null, null));
}

@Test
void compatibilityConstructorsLeaveNormalizationAbsent() {
    var selection = new OperationSelection("getForecast", true, null, null, Map.of());
    var execution = new HttpExecutionDefinition(HttpMethod.GET, URI.create("https://example.test"), "/weather", List.of());

    assertNull(selection.responseNormalization());
    assertNull(execution.responseNormalization());
}
```

- [ ] **Step 2: Run the model tests to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-domain:test \
  --tests 'io.gen2spring.mcp.domain.response.ResponseNormalizationPolicyTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `compileTestJava` fails because `ResponseNormalizationPolicy` and the new record accessors do not exist.

- [ ] **Step 3: Add the immutable policy and compatibility constructors**

```java
package io.gen2spring.mcp.domain.response;

import java.util.List;

import java.math.BigDecimal;
import java.math.BigInteger;

public record ResponseNormalizationPolicy(
        String dataPointer,
        String successCodePointer,
        List<Object> successValues,
        String errorMessagePointer,
        String totalCountPointer) {
    public ResponseNormalizationPolicy {
        successValues = successValues == null ? List.of()
                : successValues.stream().map(ResponseNormalizationPolicy::immutableScalar).toList();
    }

    private static Object immutableScalar(Object value) {
        if (value instanceof String || value instanceof Boolean
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float number && Float.isFinite(number)) {
            return new BigDecimal(Float.toString(number));
        }
        if (value instanceof Double number && Double.isFinite(number)) {
            return BigDecimal.valueOf(number);
        }
        throw new IllegalArgumentException("Response normalization success value is invalid");
    }
}
```

Change `OperationSelection` to:

```java
public record OperationSelection(
        String operationId,
        boolean enabled,
        String toolName,
        String toolDescription,
        Map<String, ParameterOverride> parameters,
        ResponseNormalizationPolicy responseNormalization) {
    public OperationSelection(
            String operationId,
            boolean enabled,
            String toolName,
            String toolDescription,
            Map<String, ParameterOverride> parameters) {
        this(operationId, enabled, toolName, toolDescription, parameters, null);
    }
}
```

Add `ResponseNormalizationPolicy responseNormalization` as the last `HttpExecutionDefinition` component. Route every existing four-, five-, and six-argument constructor to the canonical constructor with `null`.

- [ ] **Step 4: Write the failing semantic-validation matrix**

```java
@ParameterizedTest
@MethodSource("invalidPolicies")
void rejectsInvalidPoliciesWithoutEchoingConfiguredValues(ResponseNormalizationPolicy policy) {
    var failure = assertThrows(IllegalArgumentException.class,
            () -> new ResponseNormalizationPolicyValidator().requireValid(policy));

    assertEquals("Response normalization policy is invalid", failure.getMessage());
}

static Stream<ResponseNormalizationPolicy> invalidPolicies() {
    return Stream.of(
            policy("relative", null, List.of(), null, null),
            policy("/bad~2escape", null, List.of(), null, null),
            policy("/items/-", null, List.of(), null, null),
            policy("/" + "x".repeat(256), null, List.of(), null, null),
            policy("/" + String.join("/", Collections.nCopies(33, "x")), null, List.of(), null, null),
            policy(null, "/code", List.of(), null, null),
            policy(null, null, List.of("00"), null, null),
            policy(null, "/code", Collections.nCopies(17, "00"), null, null),
            policy(null, "/code", List.of("x".repeat(129)), null, null),
            policy("/meta", "/meta/code", List.of("00"), null, "/meta"));
}

@Test
void acceptsEscapedObjectNamesArrayIndexesAndTypedValues() {
    var policy = policy("/items/0/a~1b", "/meta/code", List.of("00", 0, false),
            "/meta/message", "/meta/total");

    assertSame(policy, new ResponseNormalizationPolicyValidator().requireValid(policy));
}

@Test
void acceptsLeadingZeroTokensAsPotentialObjectPropertyNames() {
    var policy = policy("/items/01", null, List.of(), null, null);

    assertSame(policy, new ResponseNormalizationPolicyValidator().requireValid(policy));
}
```

- [ ] **Step 5: Run the validator tests to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-domain:test \
  --tests 'io.gen2spring.mcp.domain.response.ResponseNormalizationPolicyValidatorTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `compileTestJava` fails because `ResponseNormalizationPolicyValidator` does not exist.

- [ ] **Step 6: Implement one bounded validator shared by CLI and policy layers**

Implement `requireValid` with these exact checks:

```java
public ResponseNormalizationPolicy requireValid(ResponseNormalizationPolicy policy) {
    Objects.requireNonNull(policy, SAFE_MESSAGE);
    List<Pointer> pointers = Stream.of(
                    pointer("data", policy.dataPointer(), false),
                    pointer("successCode", policy.successCodePointer(), true),
                    pointer("errorMessage", policy.errorMessagePointer(), true),
                    pointer("totalCount", policy.totalCountPointer(), true))
            .filter(Objects::nonNull)
            .toList();
    boolean hasCode = policy.successCodePointer() != null;
    if (hasCode != !policy.successValues().isEmpty()
            || policy.successValues().size() > 16
            || policy.successValues().stream().anyMatch(value -> !validScalar(value))) {
        throw invalid();
    }
    rejectScalarAncestorCollisions(pointers);
    return policy;
}
```

`pointer` must decode `~0` and `~1`, count at most 32 tokens, reject controls, and reject `-`. It must not reject numeric-looking object property tokens such as `01`; generated runtime evaluation performs the leading-zero check only when the current node is an array. `rejectScalarAncestorCollisions` must treat success code, error message, and total count pointers as scalar, allow `dataPointer` to be an ancestor container, and reject exact duplicate scalar pointers and scalar ancestors. `validScalar` must accept only the immutable canonical values produced by `ResponseNormalizationPolicy`; enforce the 128-character/control bound for strings. Every failure must use only `new IllegalArgumentException("Response normalization policy is invalid")`.

- [ ] **Step 7: Run all domain tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-domain:test --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL` and all domain tests pass.

- [ ] **Step 8: Commit Task 1**

```bash
git add generator-domain/src/main/java/io/gen2spring/mcp/domain/response/ResponseNormalizationPolicy.java \
  generator-domain/src/main/java/io/gen2spring/mcp/domain/response/ResponseNormalizationPolicyValidator.java \
  generator-domain/src/main/java/io/gen2spring/mcp/domain/config/GenerationRequest.java \
  generator-domain/src/main/java/io/gen2spring/mcp/domain/tool/McpToolDefinition.java \
  generator-domain/src/test/java/io/gen2spring/mcp/domain/response/ResponseNormalizationPolicyTest.java \
  generator-domain/src/test/java/io/gen2spring/mcp/domain/response/ResponseNormalizationPolicyValidatorTest.java
git commit -m "feat(domain): define response normalization policy"
```

### Task 2: Strict YAML, Tool IR Propagation, and Manifest Contract

**Files:**
- Modify: `generator-cli/src/main/java/io/gen2spring/mcp/cli/GenerationConfigurationReader.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/GenerationConfigurationReaderTest.java`
- Modify: `generator-policy/src/main/java/io/gen2spring/mcp/policy/ToolModelFactory.java`
- Modify: `generator-policy/src/test/java/io/gen2spring/mcp/policy/ToolModelFactoryTest.java`
- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationManifestWriter.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java`

**Interfaces:**
- Consumes: `ResponseNormalizationPolicyValidator.requireValid(ResponseNormalizationPolicy)` from Task 1.
- Produces: strict operation YAML field `responseNormalization` with fields `dataPath`, `successCodePath`, `successValues`, `errorMessagePath`, and `totalCountPath`.
- Produces: final Tool IR `tool.execution().responseNormalization()` independent of whether the CLI was used.
- Produces: deterministic `operationMappings[].responseNormalization` manifest object using the five public YAML field names.

- [ ] **Step 1: Write strict-reader tests before changing the parser**

```java
@Test
void readsTypedResponseNormalization() throws IOException {
    GenerationRequest request = reader.read(writeConfig("""
            responseNormalization:
              dataPath: /response/body/items/0
              successCodePath: /response/header/resultCode
              successValues: ["00", 0, false]
              errorMessagePath: /response/header/resultMsg
              totalCountPath: /response/body/totalCount
            """));

    assertEquals(new ResponseNormalizationPolicy(
            "/response/body/items/0", "/response/header/resultCode", List.of("00", BigInteger.ZERO, false),
            "/response/header/resultMsg", "/response/body/totalCount"),
            request.operations().getFirst().responseNormalization());
}

@Test
void rejectsUnknownNormalizationFieldsAndNonScalarSuccessValues() {
    assertThrows(CliConfigurationException.class,
            () -> reader.read(writeConfig("responseNormalization: {jsonPath: $.items}")));
    assertThrows(CliConfigurationException.class,
            () -> reader.read(writeConfig("responseNormalization: {successCodePath: /code, successValues: [[00]]}")));
}
```

- [ ] **Step 2: Run the CLI reader tests to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-cli:test \
  --tests 'io.gen2spring.mcp.cli.GenerationConfigurationReaderTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: tests fail because `responseNormalization` is an unknown operation property.

- [ ] **Step 3: Parse bounded typed values and call the shared validator**

Add `responseNormalization` to `OPERATION_FIELDS`, define:

```java
private static final Set<String> RESPONSE_NORMALIZATION_FIELDS = Set.of(
        "dataPath", "successCodePath", "successValues", "errorMessagePath", "totalCountPath");

private record RawResponseNormalization(
        String dataPath,
        String successCodePath,
        List<JsonNode> successValues,
        String errorMessagePath,
        String totalCountPath) {}
```

Extend `RawOperation` with `RawResponseNormalization responseNormalization`. In `validateTokenTypes`, require the normalization value to be an object, reject unknown properties, require each pointer to be textual when present, and require `successValues` to be an array of textual, numeric, or boolean nodes. Convert integral nodes with `bigIntegerValue()`, decimals with `decimalValue()`, and call:

```java
private ResponseNormalizationPolicy responseNormalization(RawResponseNormalization raw) {
    if (raw == null) {
        return null;
    }
    List<Object> successValues = raw.successValues() == null ? List.of()
            : raw.successValues().stream().map(this::scalarValue).toList();
    try {
        return new ResponseNormalizationPolicyValidator().requireValid(new ResponseNormalizationPolicy(
                raw.dataPath(), raw.successCodePath(), successValues,
                raw.errorMessagePath(), raw.totalCountPath()));
    } catch (IllegalArgumentException failure) {
        throw invalid("Response normalization policy is invalid");
    }
}
```

Pass the result as the sixth `OperationSelection` argument.

- [ ] **Step 4: Write policy-layer propagation and CLI-bypass tests**

```java
@Test
void attachesValidatedResponsePolicyToHttpExecution() {
    ResponseNormalizationPolicy policy = normalization();
    GenerationRequest request = request(new OperationSelection(
            "getForecast", true, null, null, Map.of(), policy));

    McpToolDefinition tool = factory.create(weatherDocument(), request).getFirst();

    assertSame(policy, tool.execution().responseNormalization());
}

@Test
void rejectsInvalidProgrammaticResponsePolicyBeforeRendering() {
    var invalid = new ResponseNormalizationPolicy("bad", null, List.of(), null, null);

    GeneratorException failure = assertThrows(GeneratorException.class,
            () -> factory.create(weatherDocument(), request(selection(invalid))));

    assertEquals(OPERATION_UNSUPPORTED, failure.code());
    assertFalse(failure.getMessage().contains("bad"));
}
```

- [ ] **Step 5: Run the policy tests to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-policy:test \
  --tests 'io.gen2spring.mcp.policy.ToolModelFactoryTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: propagation assertion fails because the factory still calls the six-argument execution constructor without a policy.

- [ ] **Step 6: Revalidate and attach the policy in `ToolModelFactory`**

Add one private method:

```java
private ResponseNormalizationPolicy responsePolicy(OperationSelection selection) {
    if (selection.responseNormalization() == null) {
        return null;
    }
    try {
        return new ResponseNormalizationPolicyValidator().requireValid(selection.responseNormalization());
    } catch (IllegalArgumentException failure) {
        throw GeneratorException.user(OPERATION_UNSUPPORTED, "tool-policy",
                "Response normalization policy is invalid");
    }
}
```

Pass `responsePolicy(selection)` as the last `HttpExecutionDefinition` constructor argument. Do not catch or echo configured pointer/value data anywhere else.

- [ ] **Step 7: Write the manifest RED assertion**

```java
JsonNode normalization = manifest.path("operationMappings").get(0).path("responseNormalization");
assertEquals("/response/body/items", normalization.path("dataPath").asText());
assertEquals("/response/header/code", normalization.path("successCodePath").asText());
assertEquals(List.of("00", 0, false),
        mapper.convertValue(normalization.path("successValues"), List.class));
assertFalse(rawOperation.path("responseNormalization").isObject());
```

The final assertion uses a second operation without a policy and verifies that the field is omitted, not emitted as JSON null.

- [ ] **Step 8: Run the pipeline test to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-core:test \
  --tests 'io.gen2spring.mcp.core.GenerationPipelineTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: the normalization manifest node is missing.

- [ ] **Step 9: Serialize the policy deterministically**

In `GenerationManifestWriter`, after `toolName`, add the object only when the policy is non-null:

```java
ResponseNormalizationPolicy policy = tool.execution().responseNormalization();
if (policy != null) {
    ObjectNode normalization = mapping.putObject("responseNormalization");
    putOptional(normalization, "dataPath", policy.dataPointer());
    putOptional(normalization, "successCodePath", policy.successCodePointer());
    if (!policy.successValues().isEmpty()) {
        normalization.set("successValues", objectMapper.valueToTree(policy.successValues()));
    }
    putOptional(normalization, "errorMessagePath", policy.errorMessagePointer());
    putOptional(normalization, "totalCountPath", policy.totalCountPointer());
}
```

Keep operation ordering by `operationId` and the field insertion order shown above.

- [ ] **Step 10: Run affected modules and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-cli:test :generator-policy:test :generator-core:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit Task 2**

```bash
git add generator-cli/src/main/java/io/gen2spring/mcp/cli/GenerationConfigurationReader.java \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/GenerationConfigurationReaderTest.java \
  generator-policy/src/main/java/io/gen2spring/mcp/policy/ToolModelFactory.java \
  generator-policy/src/test/java/io/gen2spring/mcp/policy/ToolModelFactoryTest.java \
  generator-core/src/main/java/io/gen2spring/mcp/core/GenerationManifestWriter.java \
  generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java
git commit -m "feat(config): propagate response normalization policy"
```

### Task 3: Generated Metadata and Pure Response Normalizer

**Files:**
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ResponseRuntimeRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/JavaSourceRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/RuntimeSourceRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/OperationMetadataRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java`

**Interfaces:**
- Consumes: Tool IR `HttpExecutionDefinition.responseNormalization()` from Task 2.
- Produces generated runtime types `ResponseNormalizationPolicy`, `OperationOutcome`, `NormalizedSuccess`, `ProviderErrorCategory`, `ProviderError`, `ProviderErrorException`, and `ResponseNormalizer` under `<package>.runtime`.
- Produces generated `ResponseNormalizer.normalize(OperationDefinition operation, int status, MediaType contentType, byte[] body, List<String> secretNames, List<String> secretValues): OperationOutcome`.
- Produces generated `ResponseNormalizer.error(OperationDefinition operation, ProviderErrorCategory category, Integer status, JsonNode providerCode, String providerMessage, List<String> secretNames, List<String> secretValues): ProviderError`.

- [ ] **Step 1: Write renderer RED assertions for metadata and focused files**

```java
@Test
void emitsTypedNormalizationMetadataAndFocusedRuntimeSources() {
    var files = renderer.render(context(List.of(weatherTool(normalization()))));

    String metadata = utf8(files.get(
            "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java"));
    assertTrue(metadata.contains("new ResponseNormalizationPolicy("));
    assertTrue(metadata.contains("TextNode.valueOf(\"00\")"));
    assertTrue(metadata.contains("JsonNodeFactory.instance.numberNode(new BigDecimal(\"1.50\"))"));
    assertTrue(metadata.contains("BooleanNode.TRUE"));
    assertTrue(files.containsKey("src/main/java/com/example/weather/runtime/ResponseNormalizer.java"));
    assertTrue(files.containsKey("src/main/java/com/example/weather/runtime/ProviderErrorException.java"));
}
```

- [ ] **Step 2: Run the renderer test to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test \
  --tests 'io.gen2spring.mcp.springai2.JavaSourceRendererTest.emitsTypedNormalizationMetadataAndFocusedRuntimeSources' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: assertion fails because normalization metadata and runtime sources are absent.

- [ ] **Step 3: Render focused profile-neutral runtime types**

`ResponseRuntimeRenderer.render(packageName, packagePath)` must return these complete generated files:

```java
public record ResponseNormalizationPolicy(
        String dataPointer,
        String successCodePointer,
        List<JsonNode> successValues,
        String errorMessagePointer,
        String totalCountPointer) {
    public ResponseNormalizationPolicy {
        successValues = List.copyOf(successValues);
    }
}

public sealed interface OperationOutcome permits NormalizedSuccess, ProviderError {}

public record NormalizedSuccess(JsonNode payload) implements OperationOutcome {
    public NormalizedSuccess { Objects.requireNonNull(payload, "payload"); }
}

public enum ProviderErrorCategory {
    PROVIDER_BUSINESS,
    UPSTREAM_CLIENT,
    UPSTREAM_SERVER,
    UPSTREAM_TIMEOUT,
    UPSTREAM_UNAVAILABLE,
    UPSTREAM_PROTOCOL,
    LOCAL_RESOURCE
}

public record ProviderError(JsonNode payload) implements OperationOutcome {
    public ProviderError { Objects.requireNonNull(payload, "payload"); }
}

public final class ProviderErrorException extends RuntimeException {
    private final ProviderError error;
    public ProviderErrorException(ProviderError error) {
        super("Generated provider request failed");
        this.error = Objects.requireNonNull(error, "error");
    }
    public ProviderError error() { return error; }
}
```

Move only response-specific generated source templates into this new renderer. `RuntimeSourceRenderer` remains responsible for request binding, executor, application, and context test.

- [ ] **Step 4: Render literal policy values into `OperationDefinition`**

Add nullable `ResponseNormalizationPolicy responseNormalization` to the generated `OperationDefinition` record and route compatibility constructors to `null`. In `OperationMetadataRenderer`, import the generated policy and Jackson node types only if one or more tools use normalization. Render values by type:

```java
private void appendSuccessValue(StringBuilder source, Object value) {
    if (value instanceof String text) {
        source.append("TextNode.valueOf(").append(JavaStringLiteral.quote(text)).append(')');
    } else if (value instanceof Boolean bool) {
        source.append(bool ? "BooleanNode.TRUE" : "BooleanNode.FALSE");
    } else if (value instanceof Number number) {
        source.append("JsonNodeFactory.instance.numberNode(new BigDecimal(")
                .append(JavaStringLiteral.quote(new BigDecimal(number.toString()).toPlainString()))
                .append("))");
    } else {
        throw JavaSourceRenderer.invalid("Response success values must be JSON scalars");
    }
}
```

Use `JavaStringLiteral.quote` for every pointer. Use `null` for absent pointers/policy, `List.of()` for no success values, and a concrete expression such as `List.of(TextNode.valueOf("00"), BooleanNode.TRUE)` for configured values.

- [ ] **Step 5: Write generated normalizer contract tests before its implementation**

Add a generated JUnit source to `GeneratedProjectSmokeTest` and run it inside the temporary generated project. It must assert all of the following:

```java
@Test
void normalizesSuccessAndPreservesTypedMetadata() {
    OperationOutcome outcome = normalizer.normalize(operation(), 200,
            MediaType.parseMediaType("application/problem+json; charset=UTF-8"),
            json("""
                    {"response":{"header":{"code":"00","message":"NORMAL_SERVICE"},
                    "body":{"items":[{"id":1}],"totalCount":1}}}
                    """), List.of(), List.of());

    assertEquals(jsonNode("""
            {"data":[{"id":1}],"page":{"totalCount":1},
             "provider":{"code":"00","message":"NORMAL_SERVICE"}}
            """), ((NormalizedSuccess) outcome).payload());
}

@Test
void failsClosedForBusinessAndProtocolFailures() {
    ProviderError business = assertInstanceOf(ProviderError.class,
            normalizer.normalize(operation(), 200, MediaType.APPLICATION_JSON,
                    json("{\"response\":{\"header\":{\"code\":30,\"message\":\"INVALID\"}}}"),
                    List.of(), List.of()));
    assertEquals("PROVIDER_BUSINESS", business.payload().at("/error/category").textValue());
    assertTrue(business.payload().at("/error/providerCode").isIntegralNumber());

    for (byte[] body : List.of(json("{}"), json("{\"response\":{\"header\":{\"code\":\"00\"}}}"))) {
        ProviderError protocol = assertInstanceOf(ProviderError.class,
                normalizer.normalize(operation(), 200, MediaType.APPLICATION_JSON, body, List.of(), List.of()));
        assertEquals("UPSTREAM_PROTOCOL", protocol.payload().at("/error/category").textValue());
    }
}
```

Add separate test methods for: no-policy raw JSON and empty 2xx body; escaped property `/a~1b`; object property `/items/01`; array index `/items/0`; leading-zero array lookup `/items/01` rejected when `items` is an array; exact decimal comparison `9E+1` versus `90`; invalid JSON and trailing tokens; missing/non-JSON content type; negative/fractional/out-of-range total count; HTTP 400/408/425/429/500/302; and a body larger than the configured executor limit handled by Task 4.

- [ ] **Step 6: Run the generated normalizer tests to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test \
  --tests 'io.gen2spring.mcp.springai2.GeneratedProjectSmokeTest.generatedResponseNormalizerEnforcesTheContract' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: generated-project `compileJava` fails until `ResponseNormalizer` is implemented.

- [ ] **Step 7: Implement strict JSON parsing, pointer evaluation, and stable envelopes**

`ResponseNormalizer` must:

1. Generate a 32-character lowercase trace ID using one `SecureRandom` and 16 random bytes per error.
2. Classify non-2xx status before parsing provider metadata: 400-499 as `UPSTREAM_CLIENT`, 500-599 as `UPSTREAM_SERVER`, all other non-2xx as `UPSTREAM_PROTOCOL`.
3. Parse non-empty JSON only for `application/json` or `application/*+json`, using `FAIL_ON_TRAILING_TOKENS`.
4. Preserve the status category if non-2xx metadata parsing or pointer evaluation fails.
5. For 2xx, compare numbers with `observed.decimalValue().compareTo(expected.decimalValue()) == 0`, strings only to strings, and booleans only to booleans.
6. Return `PROVIDER_BUSINESS` when a valid scalar code does not match.
7. Require configured data/message/count pointers and their exact types on the success path.
8. Build object fields in `data`, `page`, `provider` order; build error fields in `category`, `providerCode`, `providerMessage`, `retryable`, `httpStatus`, `operationId`, `traceId` order.

Use this lookup contract:

```java
private JsonNode requiredAt(JsonNode root, String pointer) {
    JsonNode value = root.at(pointer);
    if (value.isMissingNode()) {
        throw new ProtocolMismatch();
    }
    return value;
}
```

Do not use `JsonNode.at` to decide validity alone; Task 1 already validates syntax, while generated runtime still treats `MissingNode` as a protocol mismatch. Construct `providerCode`, `providerMessage`, and `httpStatus` as explicit JSON null when unavailable.

- [ ] **Step 8: Run focused and full renderer tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit Task 3**

```bash
git add generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ResponseRuntimeRenderer.java \
  generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/JavaSourceRenderer.java \
  generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/RuntimeSourceRenderer.java \
  generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/OperationMetadataRenderer.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java
git commit -m "feat(runtime): generate response normalization contract"
```

### Task 4: Executor Failure Mapping and Secret-Safe Provider Messages

**Files:**
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/RuntimeSourceRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ResponseRuntimeRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java`

**Interfaces:**
- Consumes: generated `ResponseNormalizer` and outcome types from Task 3.
- Produces: `OpenApiOperationExecutor.execute(OperationDefinition, Map<String,Object>): JsonNode`, returning success payloads and throwing `ProviderErrorException` only for expected failures.
- Produces: fixed category mapping for rejected execution, timeout, interruption, connection/I/O failure, response bounds, HTTP status, and protocol mismatch.

- [ ] **Step 1: Add generated HTTP and transport RED tests**

Extend the generated-project smoke server with bounded endpoints and assert:

```java
assertError(call("/business"), "PROVIDER_BUSINESS", false, 200);
assertError(call("/client/400"), "UPSTREAM_CLIENT", false, 400);
assertError(call("/client/408"), "UPSTREAM_CLIENT", true, 408);
assertError(call("/client/425"), "UPSTREAM_CLIENT", true, 425);
assertError(call("/client/429"), "UPSTREAM_CLIENT", true, 429);
assertError(call("/server/500"), "UPSTREAM_SERVER", true, 500);
assertError(call("/redirect"), "UPSTREAM_PROTOCOL", false, 302);
assertError(call("/invalid-json"), "UPSTREAM_PROTOCOL", false, 200);
assertError(call("/oversize"), "UPSTREAM_PROTOCOL", false, 200);
assertError(callRefusedPort(), "UPSTREAM_UNAVAILABLE", true, null);
assertError(callSlowEndpoint(), "UPSTREAM_TIMEOUT", true, null);
assertError(saturateExecutor(), "LOCAL_RESOURCE", false, null);
```

For every error, assert a lowercase `[0-9a-f]{32}` trace ID, exact operation ID, and absence of response marker `private-body-marker`, header/query markers, exception class names, and stack text.

- [ ] **Step 2: Run the generated executor test to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test \
  --tests 'io.gen2spring.mcp.springai2.GeneratedProjectSmokeTest.generatedExecutorMapsUpstreamFailures' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: existing `SafeExecutionException` messages or raw HTTP error JSON do not match the new category envelope.

- [ ] **Step 3: Route all expected executor failures through `ResponseNormalizer.error`**

The public method retains its `JsonNode` return type for generated Tool methods:

```java
public JsonNode execute(OperationDefinition operation, Map<String, Object> arguments) {
    List<String> secretNames = new ArrayList<>();
    List<String> secretValues = new ArrayList<>();
    try {
        OperationOutcome outcome = await(operation, arguments, secretNames, secretValues);
        if (outcome instanceof NormalizedSuccess success) {
            return success.payload();
        }
        throw new ProviderErrorException((ProviderError) outcome);
    } catch (ProviderErrorException failure) {
        throw failure;
    } catch (RejectedExecutionException failure) {
        throw providerFailure(operation, LOCAL_RESOURCE, null, secretNames, secretValues);
    }
}
```

Use `UPSTREAM_TIMEOUT` for total timeout and `ResourceAccessException` whose cause chain contains `SocketTimeoutException` or `HttpTimeoutException`. Use `UPSTREAM_UNAVAILABLE` for other `ResourceAccessException` and I/O/connect/DNS failures, `LOCAL_RESOURCE` for queue rejection and interruption, and `UPSTREAM_PROTOCOL` for oversize response. Restore the thread interrupt flag before returning `LOCAL_RESOURCE`. Never pass the caught exception message into `ResponseNormalizer`.

When resolving each secret binding, add the property name, target name, and actual nonblank value to the sanitizer lists before issuing the request. Do not retain these lists outside the current execution.

- [ ] **Step 4: Write provider-message sanitization RED tests**

```java
@ParameterizedTest
@ValueSource(strings = {
        "secret-value", "Authorization failed", "serviceKey invalid", "clientSecret invalid", "cookie invalid"
})
void masksSecretValuesAndNames(String message) {
    ProviderError error = normalizer.error(operation(), PROVIDER_BUSINESS, 200,
            TextNode.valueOf("30"), message,
            List.of("Authorization", "serviceKey", "clientSecret", "cookie"),
            List.of("secret-value"));

    String serialized = error.payload().toString();
    assertFalse(serialized.contains(message));
    assertTrue(serialized.contains("***"));
}

@Test
void replacesControlMessagesAndBoundsByUnicodeCodePoint() {
    assertEquals("Provider returned an unsafe error message",
            providerMessage("unsafe\u0000value"));
    assertEquals(512, providerMessage("가".repeat(600)).codePointCount(0, providerMessage.length()));
}
```

- [ ] **Step 5: Run the sanitizer test to verify RED**

Run the same focused generated-project test. Expected: raw provider messages still appear.

- [ ] **Step 6: Implement deterministic sanitization**

Sanitize in this order:

1. If the message is null, return null.
2. If it contains NUL or any ISO control character, return `Provider returned an unsafe error message`.
3. Replace every nonblank actual secret value with `***`, longest first.
4. Replace case-insensitive occurrences of `authorization`, `api key`, `api_key`, `apikey`, `servicekey`, `clientsecret`, `cookie`, and every bound property/target name with `***`, longest first using `Pattern.quote`.
5. Truncate the resulting string to 512 Unicode code points with `offsetByCodePoints`.
6. If any sanitizer runtime failure occurs, return the fixed unsafe-message string.

Do not log the pre-sanitized message. `ProviderErrorException.getMessage()` remains the fixed message from Task 3.

- [ ] **Step 7: Assert the generated Tool keeps its public success type**

Add a renderer assertion that the generated weather method remains:

```java
public JsonNode getForecast(Integer nx, Integer ny) {
    var input = new GetForecastInput(nx, ny);
    return executor.execute(WeatherOperations.GET_FORECAST, input.toArguments());
}
```

The generated source retains its existing validation and `@McpToolParam` annotations around these concrete parameter types. Do not expose `OperationOutcome` in generated Tool signatures.

- [ ] **Step 8: Run full Spring AI 2 tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL` with response, transport, concurrency, timeout, and redaction cases passing.

- [ ] **Step 9: Commit Task 4**

```bash
git add generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/RuntimeSourceRenderer.java \
  generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ResponseRuntimeRenderer.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java
git commit -m "feat(runtime): map provider failures safely"
```

### Task 5: Spring AI 2 MCP Result Adapter

**Files:**
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ToolCallbackConfigurationRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/JavaSourceRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ToolClassRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ProjectFileRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java`
- Modify: `generator-spring-ai-2/src/test/resources/golden/weather/WeatherMcpTools.java`

**Interfaces:**
- Consumes: generated `ProviderErrorException` and `ProviderError.payload()` from Tasks 3-4.
- Produces: generated bean `List<McpServerFeatures.SyncToolSpecification> generatedToolSpecifications(JsonMapper jsonMapper)` for every generated Tool.
- Produces: success `CallToolResult` with one JSON text content and `isError=false`; expected provider failure with one safe JSON text content and `isError=true`; unexpected failure rethrown with fixed message for JSON-RPC error handling.

- [ ] **Step 1: Write source-contract RED assertions**

```java
@Test
void registersLowLevelSpecificationsForEveryTool() {
    var files = renderer.render(contextWithUnconstrainedWeatherTool());
    String callbacks = utf8(files.get(
            "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java"));
    String tools = utf8(files.get(
            "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));

    assertTrue(callbacks.contains("List<McpServerFeatures.SyncToolSpecification>"));
    assertTrue(callbacks.contains("McpToolUtils.toSyncToolSpecification(callback).tool()"));
    assertTrue(callbacks.contains("instanceof ProviderErrorException"));
    assertFalse(callbacks.contains("ToolCallbackProvider"));
    assertFalse(tools.contains("@McpTool("));
}
```

- [ ] **Step 2: Run the source test to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test \
  --tests 'io.gen2spring.mcp.springai2.JavaSourceRendererTest.registersLowLevelSpecificationsForEveryTool' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: unconstrained tools have no callback source and constrained tools still return `ToolCallbackProvider`.

- [ ] **Step 3: Generate direct synchronous specifications**

Always emit the callback configuration, set the renderer's explicit-schema path to true for every Tool, and compute explicit input schemas. Remove generated `@McpTool` annotations so the annotation scanner cannot register duplicates. Keep `@McpToolParam` on method parameters for descriptions and required flags; the explicit schema remains authoritative.

Generate one `MethodToolCallback` per method, then wrap it:

```java
private static McpServerFeatures.SyncToolSpecification specification(
        MethodToolCallback callback,
        JsonMapper jsonMapper) {
    McpSchema.Tool tool = McpToolUtils.toSyncToolSpecification(callback).tool();
    return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    String input = jsonMapper.writeValueAsString(request.arguments());
                    String output = callback.call(input);
                    return new McpSchema.CallToolResult(
                            List.of(new McpSchema.TextContent(output)), false);
                } catch (ToolExecutionException failure) {
                    if (failure.getCause() instanceof ProviderErrorException providerFailure) {
                        try {
                            String output = jsonMapper.writeValueAsString(providerFailure.error().payload());
                            return new McpSchema.CallToolResult(
                                    List.of(new McpSchema.TextContent(output)), true);
                        } catch (JacksonException serializationFailure) {
                            throw new IllegalStateException("Generated Tool result conversion failed");
                        }
                    }
                    throw new IllegalStateException("Generated Tool execution failed", failure);
                } catch (JacksonException failure) {
                    throw new IllegalStateException("Generated Tool argument conversion failed");
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("Generated Tool execution failed", failure);
                }
            })
            .build();
}
```

Use the MCP SDK `SyncToolSpecification` and `McpSchema` types already supplied transitively by the pinned starter. Do not call the converted specification's handler because `McpToolUtils` catches every `Exception` and would collapse internal failures into `isError=true`.

- [ ] **Step 4: Disable annotation scanning in generated configuration**

In `ProjectFileRenderer.applicationYaml`, always emit:

```yaml
spring:
  ai:
    mcp:
      server:
        annotation-scanner:
          enabled: false
```

Change the renderer test to assert `enabled: false` for constrained and unconstrained tools.

- [ ] **Step 5: Add real MCP success, expected-error, and internal-error tests**

In a generated application smoke test, call Streamable HTTP MCP and assert:

```java
assertFalse(successResult.path("isError").booleanValue());
assertEquals(expectedSuccess, parseOnlyText(successResult));

assertTrue(providerFailure.path("isError").booleanValue());
assertEquals("PROVIDER_BUSINESS",
        parseOnlyText(providerFailure).at("/error/category").textValue());

assertTrue(internalFailure.has("error"));
assertFalse(internalFailure.has("result"));
assertFalse(internalFailure.toString().contains("private-internal-marker"));
```

Induce the internal case with a test-only generated Tool method that throws `IllegalStateException("private-internal-marker")`; verify the response is a JSON-RPC error and client-visible text uses only the fixed adapter message.

- [ ] **Step 6: Run the MCP adapter test to verify RED then GREEN**

Run before implementation and expect the provider case to be a JSON-RPC error or all failures to be `isError=true`. Run again after implementation:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test \
  --tests 'io.gen2spring.mcp.springai2.GeneratedProjectSmokeTest.generatedMcpAdapterSeparatesExpectedAndInternalFailures' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected after implementation: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the complete Spring AI 2 module**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, with tools/list schemas unchanged and no duplicate registrations.

- [ ] **Step 8: Commit Task 5**

```bash
git add generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ToolCallbackConfigurationRenderer.java \
  generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/JavaSourceRenderer.java \
  generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ToolClassRenderer.java \
  generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ProjectFileRenderer.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java \
  generator-spring-ai-2/src/test/resources/golden/weather/WeatherMcpTools.java
git commit -m "feat(spring-ai): adapt normalized MCP results"
```

### Task 6: Tool-IR-Derived Validation Response Fixture

**Files:**
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/generation/GenerationContracts.java`
- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/ExpectedToolCallFactory.java`
- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/ExpectedToolResponseFactory.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/ExpectedToolCallFactoryTest.java`
- Create: `generator-core/src/test/java/io/gen2spring/mcp/core/ExpectedToolResponseFactoryTest.java`
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/UpstreamCallExpectation.java`
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/MockUpstreamServer.java`
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/McpStreamableHttpClient.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/UpstreamCallExpectationTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/MockUpstreamServerTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/McpStreamableHttpClientTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/support/McpTestServer.java`

**Interfaces:**
- Consumes: final Tool IR policy from Task 2 and normalized success contract from Task 3.
- Produces: `ExpectedUpstreamResponse(int status, String contentType, Object body)`.
- Produces: `ExpectedToolCall(McpToolDefinition tool, Map<String,Object> arguments, ExpectedUpstreamResponse upstreamResponse, Object expectedResult)` plus a two-argument compatibility constructor with the legacy validation body/result.
- Produces: nested record `ExpectedToolResponseFactory.ExpectedToolResponse(ExpectedUpstreamResponse upstreamResponse, Object expectedResult)` and `ExpectedToolResponseFactory.create(McpToolDefinition tool): ExpectedToolResponse`.
- Produces: `UpstreamCallExpectation` response status/content type/body fields and mock responses that do not affect request comparison.

- [ ] **Step 1: Write response-fixture derivation RED tests**

```java
@Test
void derivesRawProviderEnvelopeAndNormalizedExpectedResult() {
    ExpectedToolResponse result = factory.create(tool(normalization()));

    assertEquals(200, result.upstreamResponse().status());
    assertEquals("application/json", result.upstreamResponse().contentType());
    assertEquals(Map.of("validated", true, "operationId", "getForecast"),
            at(result.upstreamResponse().body(), "/response/body/items/0"));
    assertEquals("00", at(result.upstreamResponse().body(), "/response/header/resultCode"));
    assertEquals(1, at(result.upstreamResponse().body(), "/response/body/totalCount"));
    assertEquals(Map.of(
            "data", List.of(Map.of("validated", true, "operationId", "getForecast")),
            "page", Map.of("totalCount", 1),
            "provider", Map.of("code", "00", "message", "NORMAL_SERVICE")),
            result.expectedResult());
}

@Test
void supportsArrayPointersAndDataAncestorsDeterministically() {
    ExpectedToolResponse first = factory.create(tool(policy("/items/0", "/code", List.of("00"), null, null)));
    ExpectedToolResponse second = factory.create(tool(policy("/items/0", "/code", List.of("00"), null, null)));
    assertEquals(first, second);
}
```

- [ ] **Step 2: Run the core fixture tests to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-core:test \
  --tests 'io.gen2spring.mcp.core.ExpectedToolResponseFactoryTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `compileTestJava` fails because the response factory and contracts do not exist.

- [ ] **Step 3: Add immutable expected-response contracts**

In `GenerationContracts`, deep-copy JSON-compatible maps/lists/scalars for both response body and expected result. Permit explicit JSON null only in expected result error fields by representing it as Jackson-independent `null` map values; update `immutableJsonValue` with an `allowNull` flag rather than allowing null in Tool arguments or schemas.

Use:

```java
public record ExpectedUpstreamResponse(int status, String contentType, Object body) {}

public record ExpectedToolCall(
        McpToolDefinition tool,
        Map<String, Object> arguments,
        ExpectedUpstreamResponse upstreamResponse,
        Object expectedResult) {
    public ExpectedToolCall(McpToolDefinition tool, Map<String, Object> arguments) {
        this(tool, arguments, legacyResponse(tool), legacyResult(tool));
    }
}
```

The compatibility constructor must produce HTTP 200, `application/json`, and `{"validated":true,"operationId":"getForecast"}` for a Tool whose operation ID is `getForecast`, using the actual Tool operation ID for other Tools.

- [ ] **Step 4: Implement deterministic pointer-tree synthesis**

`ExpectedToolResponseFactory` must decode Task 1-validated RFC 6901 tokens, create `LinkedHashMap` for object tokens and `ArrayList` for numeric next tokens, grow arrays with null placeholders only until the exact configured index, and reject impossible collisions with the fixed message `Expected response fixture cannot be derived`.

Insertion order:

1. Start with `{"validated":true,"operationId":tool.operationId()}` when `dataPointer` is absent; otherwise insert that marker at `dataPointer`.
2. Insert the first success value at `successCodePointer`.
3. Insert `NORMAL_SERVICE` at `errorMessagePointer`.
4. Insert integer `1` at `totalCountPointer`.
5. Re-evaluate the completed tree to construct `data`, optional `page.totalCount`, and optional `provider.code/message` in the exact runtime field order.

`ExpectedToolCallFactory.create` must call this factory after argument normalization and return the four-argument `ExpectedToolCall`.

- [ ] **Step 5: Run core tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-core:test --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Write mock upstream RED tests for configured responses**

```java
@Test
void returnsTheConfiguredStatusContentTypeAndBodyAfterOneExactRequest() throws Exception {
    UpstreamCallExpectation expectation = expectationWithResponse(
            429, "application/problem+json", Map.of("code", "LIMIT"));
    try (MockUpstreamServer server = MockUpstreamServer.start(expectation)) {
        HttpResponse<String> response = sendExpectedRequest(server.baseUri());

        assertEquals(429, response.statusCode());
        assertEquals("application/problem+json", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(mapper.valueToTree(Map.of("code", "LIMIT")), mapper.readTree(response.body()));
        server.sealAndAwaitVerified(Duration.ofSeconds(1));
    }
}
```

- [ ] **Step 7: Run validation mock tests to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-validation:test \
  --tests 'io.gen2spring.mcp.validation.UpstreamCallExpectationTest' \
  --tests 'io.gen2spring.mcp.validation.MockUpstreamServerTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: assertions fail because the mock always returns its hard-coded success body.

- [ ] **Step 8: Carry response fixtures without weakening request verification**

Add `responseStatus`, `responseContentType`, and `responseBody` to `UpstreamCallExpectation`; derive them only from `ExpectedToolCall.upstreamResponse()`. Keep `verifyRequest` unchanged. Replace `successResponse()` with serialization of `expectation.responseBody()` and set the configured status/content type in `writeResponse`. Bound serialized response fixture to 1 MiB and reject null/invalid status/content type during expectation construction.

- [ ] **Step 9: Write MCP result comparison RED tests**

```java
@Test
void validatesTheExpectedResultInsteadOfTheLegacyHardCodedPayload() throws Exception {
    ExpectedToolCall call = new ExpectedToolCall(tool, Map.of("nx", 60), response,
            Map.of("data", List.of(Map.of("id", 1)), "provider", Map.of("code", "00")));
    try (var server = McpTestServer.startWithToolResult(call.expectedResult(), false)) {
        assertDoesNotThrow(() -> client.validate(server.uri(), EXPECTED, call));
    }
}
```

Also verify numeric JSON equality (`9E+1` equals `90`) and an actual mismatch still fails at `MCP_TOOL_CALL`.

- [ ] **Step 10: Compare canonical expected JSON and update the MCP fixture server**

In `validateToolCallResult`, replace the hard-coded object with:

```java
JsonNode expected = objectMapper.valueToTree(expectedCall.expectedResult());
if (!canonicalJson(actual).equals(canonicalJson(expected))) {
    throw failure(McpStage.TOOL_CALL,
            "MCP Tool result does not match the mock upstream contract", null);
}
```

`McpTestServer` must accept an arbitrary JSON-compatible result and explicit `isError` flag but never include that value in failure messages.

- [ ] **Step 11: Run all core and validation tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-core:test :generator-validation:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, including exactly-one and duplicate/late-request tests.

- [ ] **Step 12: Commit Task 6**

```bash
git add generator-domain/src/main/java/io/gen2spring/mcp/domain/generation/GenerationContracts.java \
  generator-core/src/main/java/io/gen2spring/mcp/core/ExpectedToolCallFactory.java \
  generator-core/src/main/java/io/gen2spring/mcp/core/ExpectedToolResponseFactory.java \
  generator-core/src/test/java/io/gen2spring/mcp/core/ExpectedToolCallFactoryTest.java \
  generator-core/src/test/java/io/gen2spring/mcp/core/ExpectedToolResponseFactoryTest.java \
  generator-validation/src/main/java/io/gen2spring/mcp/validation/UpstreamCallExpectation.java \
  generator-validation/src/main/java/io/gen2spring/mcp/validation/MockUpstreamServer.java \
  generator-validation/src/main/java/io/gen2spring/mcp/validation/McpStreamableHttpClient.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/UpstreamCallExpectationTest.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/MockUpstreamServerTest.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/McpStreamableHttpClientTest.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/support/McpTestServer.java
git commit -m "feat(validation): verify normalized Tool results"
```

### Task 7: CLI Journey, Generated Documentation, and Full Regression

**Files:**
- Modify: `generator-cli/src/integrationTest/resources/config/weather-generation.yaml`
- Modify: `generator-cli/src/integrationTest/java/io/gen2spring/mcp/cli/P1GenerationIntegrationTest.java`
- Modify: `generator-cli/src/test/resources/config/weather-generation.yaml`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ProjectFileRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: the complete configuration, IR, runtime, adapter, and validation contracts from Tasks 1-6.
- Produces: one installed-CLI journey proving normalized result validation, deterministic manifest/archive output, no secret leakage, and unchanged P0 raw-response compatibility.
- Produces: generated README and root README documentation for the exact YAML and result/error envelopes.

- [ ] **Step 1: Add normalization to the installed CLI fixture and assertions**

Use this exact fixture under the selected operation:

```yaml
responseNormalization:
  dataPath: /response/body/items/item
  successCodePath: /response/header/resultCode
  successValues: ["00"]
  errorMessagePath: /response/header/resultMsg
  totalCountPath: /response/body/totalCount
```

Extend `REQUIRED_OUTPUTS` with all generated runtime files from Task 3 and `WeatherMcpToolCallbacks.java`. Assert manifest normalization values, source checksum determinism, validation stage order, `MCP_TOOL_CALL=SUCCESS`, and absence of raw fixture-only envelope fields from the expected normalized result.

- [ ] **Step 2: Run the installed CLI test to verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-cli:integrationTest \
  --tests 'io.gen2spring.mcp.cli.P1GenerationIntegrationTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected before all preceding tasks are integrated: exit code 5 with `MCP_TOOL_CALL_FAILED` or required output/manifest assertions failing. Do not weaken the expected normalized JSON.

- [ ] **Step 3: Add a raw-response backward-compatibility installed fixture assertion**

Keep `generator-cli/src/test/resources/config/weather-generation.yaml` without `responseNormalization`. In `InstalledCliTest`, assert generation succeeds and generated runtime tests still return the raw provider JSON body for an HTTP 200 JSON response and JSON null for an empty 2xx response.

- [ ] **Step 4: Update generated README content and tests**

Replace `Known P0 limits` with `Response handling` and document:

```markdown
## Response handling

- Operations without response normalization return the provider's successful JSON body unchanged.
- Configured operations return `data`, optional `page.totalCount`, and optional `provider` metadata.
- Expected provider, HTTP, timeout, availability, protocol, and local-capacity failures return one MCP Tool error JSON payload with a local trace ID.
- Provider responses remain bounded to 1 MiB. Retry and pagination are not executed automatically.
```

If any operation is configured, render its operation ID, pointer settings, and typed success values in deterministic operation-ID order. Response policy values are public contract metadata and must match the manifest representation.

- [ ] **Step 5: Update the root README with strict YAML and stable envelopes**

Add the approved YAML example, success envelope, error envelope, category/retryability table, and explicit limitations: JSON only, no retry execution, no pagination execution, no typed output DTO, and no OpenTelemetry trace reuse in this slice. State that `traceId` is locally generated until the observability slice.

- [ ] **Step 6: Run focused CLI and renderer suites and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test :generator-cli:test :generator-cli:integrationTest \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the exact full acceptance command**

Run:

```bash
mise exec -- ./gradlew clean test integrationTest :generator-cli:installDist \
  --no-daemon --non-interactive --rerun-tasks
git diff --check
```

Expected: Gradle `BUILD SUCCESSFUL`; `git diff --check` prints no output and exits 0.

- [ ] **Step 8: Perform the security and contract readback**

Run:

```bash
rg -n "private-body-marker|mcp-validation-secret|stack trace|Exception:" \
  generator-*/build/test-results generator-*/build/reports || true
rg -n "responseNormalization|PROVIDER_BUSINESS|UPSTREAM_PROTOCOL|isError" \
  README.md generator-cli/src/integrationTest generator-spring-ai-2/src/test
git status --short
```

Expected: the first search finds no leaked fixture values in test results/reports; the second finds the new contract in docs and acceptance tests; status contains only Task 7 files before commit.

- [ ] **Step 9: Commit Task 7**

```bash
git add generator-cli/src/integrationTest/resources/config/weather-generation.yaml \
  generator-cli/src/integrationTest/java/io/gen2spring/mcp/cli/P1GenerationIntegrationTest.java \
  generator-cli/src/test/resources/config/weather-generation.yaml \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java \
  generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ProjectFileRenderer.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java \
  README.md
git commit -m "feat(cli): verify normalized response journey"
```

## Completion Gate

The slice is complete only when all seven task commits exist, the exact full acceptance command passes from a clean checkout, the installed CLI representative call observes exactly one upstream request, expected provider failures are MCP `isError=true`, unexpected failures are JSON-RPC errors with fixed client-visible text, and no configured secret or raw provider body marker appears in generated artifacts or validation output.
