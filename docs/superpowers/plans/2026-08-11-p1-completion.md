# P1 Follow-up Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete typed output DTO, bounded retry, bounded pagination, and Windows validation host support without changing default generated-project behavior.

**Architecture:** Strict operation configuration flows through immutable domain policies into Tool IR. Both Spring AI emitters render family-specific source from the same policy semantics, while validation derives a deterministic ordered upstream interaction sequence and exact MCP result. Windows validation uses an injected platform adapter so POSIX behavior remains characterized and Windows wrapper/JDK invocation is fail-closed.

**Tech Stack:** Java 17/21, Spring Boot 3.5.16/4.1.0, Spring AI 1.1.8/2.0.0, Jackson 2/3, Swagger Parser, Gradle 9.6.1, JUnit 5, JDK HttpServer/HttpClient, GitHub Actions.

## Global Constraints

- Keep all four canonical profile IDs, Java/Boot/AI/Gradle/image pins, and `CompatibilityProfile.p0()` identity unchanged.
- New output, retry, and pagination configuration is operation-level opt-in; omitted policy preserves current generic JSON and exactly-one request behavior.
- Keep `McpToolDefinition` framework-neutral; Spring AI and MCP SDK types may exist only inside emitter modules.
- Implement the `ToolEmitter` port plus Spring AI 1/2 adapters in this P1 branch; reserve the direct `McpJavaSdkToolEmitter` implementation for a separately designed runtime family.
- Retry is GET-only, `maxRetries <= 3`, `initialBackoffMillis <= 5000`, `maxBackoffMillis <= 10000`, and total Tool-call timeout is the hard deadline.
- Pagination is GET-only, query-scalar driven, `maxPages <= 20`, `maxItems <= 2000`, and page plus aggregate JSON are each bounded to 1 MiB.
- Typed output accepts one structurally consistent supported `application/json` success schema and rejects ambiguous or unsupported response contracts before source generation.
- Do not expose pagination state, secrets, response bodies, exception messages, or Windows command strings in logs, reports, telemetry, or safe errors.
- Preserve fatal `Error` identity, interrupt flags, telemetry single-completion, response normalization, secret masking, archive determinism, and process-tree cleanup.
- Preserve POSIX `gradlew` execution and owner-executable checks byte-for-byte in behavior; select `gradlew.bat` only on Windows.
- Implement inline in this session; do not dispatch subagents.
- Use `feat:`, `refactor:`, or `docs:` commits; do not create `fix:` commits.

---

### Task 1: Establish the framework-neutral ToolEmitter port

**Files:**

- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/generation/GenerationContracts.java`
- Modify: `generator-domain/src/test/java/io/gen2spring/mcp/domain/generation/GenerationContractsTest.java`
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/SpringAi1ToolEmitter.java`
- Create: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/SpringAi1ToolEmitterTest.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/SpringAi1ProjectGenerator.java`
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/SpringAi2ToolEmitter.java`
- Create: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/SpringAi2ToolEmitterTest.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/SpringAi2ProjectGenerator.java`

**Interfaces:**

- Produces: `GenerationContracts.ToolEmitter.emit(GenerationContext)`.
- Produces: `GenerationContracts.GeneratedToolSources(Map<String, byte[]> files)` with safe immutable sorted paths and cloned byte arrays.
- Preserves: `ProjectGenerator.generate(GenerationContext)` and `ProjectGeneratorRegistry` profile/module selection.
- Reserves: future `McpJavaSdkToolEmitter` using the same port; this task does not create an SDK module, profile, dependency, or placeholder class.

- [ ] **Step 1: Write domain port tests that fail**

Add tests that require non-null forward-slash relative paths; reject absolute, empty segment, `.`, `..`, backslash, control,
null/blank paths and null bytes; sort paths; clone input byte arrays; and clone arrays returned from the accessor. The
contract assertion is:

```java
GeneratedToolSources sources = new GeneratedToolSources(Map.of(
        "src/main/java/example/B.java", new byte[] {2},
        "src/main/java/example/A.java", new byte[] {1}));
assertEquals(List.of(
        "src/main/java/example/A.java",
        "src/main/java/example/B.java"), new ArrayList<>(sources.files().keySet()));
```

- [ ] **Step 2: Run domain tests and verify RED**

```bash
mise exec -- ./gradlew :generator-domain:test \
  --tests io.gen2spring.mcp.domain.generation.GenerationContractsTest \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `ToolEmitter` and `GeneratedToolSources` do not exist.

- [ ] **Step 3: Add the neutral port and immutable result**

Add these nested contracts without importing Spring AI or MCP SDK classes:

```java
public interface ToolEmitter {
    GeneratedToolSources emit(GenerationContext context);
}

public record GeneratedToolSources(Map<String, byte[]> files) {
    public GeneratedToolSources {
        files = immutableFileBytes(files, "Generated Tool sources are invalid");
    }

    @Override
    public Map<String, byte[]> files() {
        return immutableFileBytes(files, "Generated Tool sources are invalid");
    }
}
```

Use a private `TreeMap` copy and clone each byte array on construction/access. A safe path is one or more non-empty
forward-slash segments, none equal to `.` or `..`, with no ISO control character or backslash. Do not change
`GeneratedProjectFiles` in this refactor.

- [ ] **Step 4: Write Spring AI adapter characterization tests that fail**

For each family, generate a representative current context and assert the emitter source map is exactly the Java/runtime/test
subset previously returned by `JavaSourceRenderer`. Assert the project generator output is byte-identical before and after
delegation using a checked-in literal path set and SHA-256 digest, not by invoking the old and new paths as both oracle sides.

```java
GeneratedToolSources sources = new SpringAi2ToolEmitter().emit(context);
assertEquals(EXPECTED_SOURCE_PATHS, sources.files().keySet());
assertEquals(EXPECTED_SOURCE_DIGEST, checksum(sources.files()));
```

- [ ] **Step 5: Run adapter tests and verify RED**

```bash
mise exec -- ./gradlew \
  :generator-spring-ai-1:test --tests io.gen2spring.mcp.springai1.SpringAi1ToolEmitterTest \
  :generator-spring-ai-2:test --tests io.gen2spring.mcp.springai2.SpringAi2ToolEmitterTest \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: emitter classes do not exist.

- [ ] **Step 6: Implement Spring AI ToolEmitter adapters**

Each public final adapter is package-isolated and delegates only source rendering:

```java
public final class SpringAi2ToolEmitter implements ToolEmitter {
    @Override
    public GeneratedToolSources emit(GenerationContext context) {
        CompatibilityProfile profile = context == null ? null : context.profile();
        return new GeneratedToolSources(new JavaSourceRenderer(profile).render(context));
    }
}
```

The AI1 class uses `SpringAi1ToolEmitter` and its package renderer. No adapter imports the other emitter module.

- [ ] **Step 7: Compose emitters in project generators**

Give each project generator a final `ToolEmitter`, a public default constructor with its canonical family adapter, and a
package-private test constructor. Replace the direct renderer call with:

```java
toolEmitter.emit(context).files().forEach(files::put);
```

Project scaffold, wrapper assets, README, YAML, Docker, and map immutability remain in the project generator.

- [ ] **Step 8: Run domain and both emitter modules**

```bash
mise exec -- ./gradlew \
  :generator-domain:test :generator-spring-ai-1:test :generator-spring-ai-2:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: BUILD SUCCESSFUL; generated project path sets and checksums remain unchanged.

- [ ] **Step 9: Commit Task 1**

```bash
git add generator-domain generator-spring-ai-1 generator-spring-ai-2
git diff --cached --check
git commit -m "refactor(generator): separate Tool emitters" \
  -m "- Add a framework-neutral ToolEmitter generation port" \
  -m "- Delegate Spring AI source rendering through family adapters" \
  -m "- Preserve project scaffolds and generated source bytes"
```

---

### Task 2: Normalize success schemas and create typed output IR

**Files:**

- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/tool/OutputDefinition.java`
- Create: `generator-domain/src/test/java/io/gen2spring/mcp/domain/tool/OutputDefinitionTest.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/openapi/OpenApiDocument.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/config/GenerationRequest.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/tool/McpToolDefinition.java`
- Modify: `generator-openapi/src/main/java/io/gen2spring/mcp/openapi/SwaggerOpenApiAnalyzer.java`
- Modify: `generator-openapi/src/test/java/io/gen2spring/mcp/openapi/SwaggerOpenApiAnalyzerTest.java`
- Modify: `generator-application/src/main/java/io/gen2spring/mcp/application/GenerationConfigurationParser.java`
- Modify: `generator-application/src/test/java/io/gen2spring/mcp/application/GenerationConfigurationParserTest.java`
- Create: `generator-policy/src/main/java/io/gen2spring/mcp/policy/OutputSchemaResolver.java`
- Create: `generator-policy/src/test/java/io/gen2spring/mcp/policy/OutputSchemaResolverTest.java`
- Modify: `generator-policy/src/main/java/io/gen2spring/mcp/policy/ToolModelFactory.java`
- Modify: `generator-policy/src/test/java/io/gen2spring/mcp/policy/ToolModelFactoryTest.java`

**Interfaces:**

- Produces: `OpenApiDocument.ApiOperation.successResponse()`.
- Produces: `GenerationRequest.OutputSelection(McpToolDefinition.OutputKind mode)`.
- Produces: `OutputDefinition(OutputKind kind, ApiSchema providerSchema, ApiSchema resultSchema)`.
- Produces: `McpToolDefinition.output()` and compatibility accessor `outputKind()`.
- Consumes: existing `ResponseNormalizationPolicy` RFC 6901 pointers and supported `ApiSchema` subset.

- [ ] **Step 1: Write response-schema analyzer tests that fail**

Add fixtures for identical `200`/`201` JSON schemas, conflicting schemas, bodyless `204`, `2XX`, missing media
schema, and unsupported composed response. The core assertion is independent of the production normalizer:

```java
ApiOperation operation = result.document().operations().getFirst();
assertEquals(SchemaType.OBJECT, operation.successResponse().type());
assertEquals(List.of("city"), operation.successResponse().requiredProperties());
assertEquals(SchemaType.STRING,
        operation.successResponse().properties().get("city").type());
```

- [ ] **Step 2: Run the analyzer tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-openapi:test \
  --tests io.gen2spring.mcp.openapi.SwaggerOpenApiAnalyzerTest \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails because `ApiOperation.successResponse()` does not exist.

- [ ] **Step 3: Normalize supported success schemas**

Add a nullable final component plus an old-signature compatibility constructor to `ApiOperation`. In
`SwaggerOpenApiAnalyzer`, normalize each body-bearing success response with the existing schema resolver and require
structural equality:

```java
private ApiSchema normalizeSuccessResponse(
        Operation operation,
        Map<String, Schema> components,
        List<String> warnings) {
    List<ApiSchema> schemas = bodyBearingSuccessResponses(operation).stream()
            .map(response -> normalizeSchema(responseSchema(response), components, new ArrayDeque<>()))
            .toList();
    if (schemas.isEmpty()) {
        return null;
    }
    ApiSchema first = schemas.getFirst();
    if (!first.supported() || schemas.stream().anyMatch(schema -> !first.equals(schema))) {
        warnings.add("Success response schemas must be supported and structurally identical");
        return null;
    }
    return first;
}
```

Keep the existing single-`application/json` media rule and operation warning behavior.

- [ ] **Step 4: Run analyzer tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass.

- [ ] **Step 5: Write strict output configuration tests that fail**

Add YAML/JSON tests for omitted output, `GENERIC_JSON`, `TYPED`, unknown mode, explicit null, unknown field, and
non-string mode:

```java
GenerationRequest request = parser.parseYaml(configurationWith("""
        output:
          mode: TYPED
        """));
assertEquals(McpToolDefinition.OutputKind.TYPED_DTO,
        request.operations().getFirst().output().mode());
```

Assert the fixed existing configuration error does not contain the rejected value.

- [ ] **Step 6: Run configuration tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-application:test \
  --tests io.gen2spring.mcp.application.GenerationConfigurationParserTest \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: output is rejected as an unknown operation property.

- [ ] **Step 7: Add output configuration and immutable Tool output**

Use these exact domain shapes:

```java
public record OutputSelection(McpToolDefinition.OutputKind mode) {
    public OutputSelection {
        Objects.requireNonNull(mode, "mode");
    }
}

public record OutputDefinition(
        McpToolDefinition.OutputKind kind,
        OpenApiDocument.ApiSchema providerSchema,
        OpenApiDocument.ApiSchema resultSchema) {
    public OutputDefinition {
        Objects.requireNonNull(kind, "kind");
        if (kind == McpToolDefinition.OutputKind.TYPED_DTO
                && (providerSchema == null || resultSchema == null)) {
            throw new IllegalArgumentException("Typed Tool output schema is incomplete");
        }
    }
}
```

Extend `OperationSelection` with `OutputSelection output`, route old constructors to
`new OutputSelection(GENERIC_JSON)`, and parse external `output.mode: TYPED` as internal `TYPED_DTO` while
`GENERIC_JSON` maps directly. Change `McpToolDefinition` to store
`OutputDefinition output`; preserve the seven-argument constructor and `outputKind()` by delegating to `output.kind()`.

- [ ] **Step 8: Write output schema resolution tests that fail**

Cover whole-response typed object, normalized `dataPath`, normalized `{data,page,provider}` result shape, root pointer,
escaped pointer tokens, missing pointer, incompatible total-count/provider-code schema, absent response schema, and generic
default. Assert exact `ApiSchema` trees rather than rendered Java source.

- [ ] **Step 9: Run policy tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-policy:test \
  --tests io.gen2spring.mcp.policy.OutputSchemaResolverTest \
  --tests io.gen2spring.mcp.policy.ToolModelFactoryTest \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: typed output still has no resolved schema or fails to compile.

- [ ] **Step 10: Resolve final result schemas and verify GREEN**

Implement strict RFC 6901 token traversal and fixed envelope construction. `ToolModelFactory` must call the resolver only
after operation selection and response-normalization validation, then create:

```java
OutputDefinition output = outputSchemaResolver.resolve(
        selection.output(), operation.successResponse(), normalization);
return new McpToolDefinition(
        operation.operationId(), toolName, description, inputs, execution, secrets, output);
```

Unsupported typed contracts throw `GeneratorException.user(OPERATION_UNSUPPORTED, "tool-policy",
"Typed Tool output is unsupported")` without including pointer/property/schema text.

- [ ] **Step 11: Run affected modules**

```bash
mise exec -- ./gradlew \
  :generator-domain:test :generator-openapi:test :generator-application:test :generator-policy:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: BUILD SUCCESSFUL and zero failures/errors.

- [ ] **Step 12: Commit Task 2**

```bash
git add generator-domain generator-openapi generator-application generator-policy
git diff --cached --check
git commit -m "feat(domain): model typed Tool outputs" \
  -m "- Normalize structurally consistent OpenAPI success schemas" \
  -m "- Parse strict operation output selection" \
  -m "- Resolve normalized typed result shapes in Tool IR"
```

---

### Task 3: Generate and execute typed output DTOs

**Files:**

- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/OutputRecordRenderer.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/JavaSourceRenderer.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/ToolClassRenderer.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/ToolCallbackConfigurationRenderer.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/RuntimeSourceRenderer.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/JavaSourceRendererTest.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/GeneratedProjectSmokeTest.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/GeneratedRuntimeRegressionTest.java`
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/OutputRecordRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/JavaSourceRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ToolClassRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ToolCallbackConfigurationRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/RuntimeSourceRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java`

**Interfaces:**

- Consumes: `McpToolDefinition.output().resultSchema()` from Task 2.
- Produces: `<Operation>Result` and nested response records/enums under `generated.model`.
- Produces: typed Tool methods while retaining JSON-equivalent MCP text content.

- [ ] **Step 1: Write renderer tests that fail**

Create independent source expectations for a nested typed response containing required/optional properties, enum wire
values, arrays, decimal constraints, raw JSON names with spaces/hyphens, normalization page/provider envelope, and Java
identifier collisions. Assert generic mode does not emit an output record and still declares `public JsonNode`.

```java
assertTrue(result.sources().get(".../GetForecastResult.java").contains(
        "public record GetForecastResult("));
assertTrue(toolSource.contains("public GetForecastResult getForecast("));
assertFalse(genericSources.keySet().stream().anyMatch(path -> path.endsWith("Result.java")));
```

- [ ] **Step 2: Run both source renderer suites and verify RED**

```bash
mise exec -- ./gradlew \
  :generator-spring-ai-1:test --tests io.gen2spring.mcp.springai1.JavaSourceRendererTest \
  :generator-spring-ai-2:test --tests io.gen2spring.mcp.springai2.JavaSourceRendererTest \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: result source and typed method assertions fail.

- [ ] **Step 3: Render response records for Jackson 2 and Jackson 3**

Implement `OutputRecordRenderer` with these guarantees:

```java
Map<String, String> render(
        String packageName,
        String packagePath,
        String operationClass,
        ApiSchema resultSchema)
```

- deterministic path/name ordering;
- `@JsonProperty` preserves every bounded non-control wire name;
- enum `fromWireValue` and serialization use original OpenAPI values;
- response properties do not emit Jakarta input validation annotations;
- AI1 imports `com.fasterxml.jackson.annotation.JsonProperty`;
- AI2 imports `tools.jackson.annotation.JsonProperty`;
- nullable/optional reference components accept null; primitive-like fields use boxed Java types.

- [ ] **Step 4: Return typed results from generated Tool methods**

Add a generated executor overload without changing generic execution:

```java
public <T> T execute(
        OperationDefinition operation,
        Map<String, Object> arguments,
        Class<T> resultType) {
    JsonNode result = execute(operation, arguments);
    try {
        return jsonMapper.treeToValue(result, resultType);
    } catch (JacksonException failure) {
        throw new IllegalStateException("Generated Tool result conversion failed");
    }
}
```

AI1 uses `JsonProcessingException` where Jackson 2 requires it. `ToolClassRenderer` calls this overload only for
`TYPED_DTO`. Keep adapter serialization through the Boot-managed `ObjectMapper`, so MCP content is the exact normalized
JSON shape.

- [ ] **Step 5: Run source tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass.

- [ ] **Step 6: Add real generated-project typed-output tests**

Generate one whole-response DTO Tool and one normalized-envelope DTO Tool. In each generated project, compile, start the
Spring context, invoke the Tool method, and assert Java type plus serialized JSON:

```java
GetForecastResult result = tools.getForecast("Seoul");
assertEquals("Seoul", result.data().city());
assertEquals("{\"data\":{\"city\":\"Seoul\"},\"page\":{\"totalCount\":1}}",
        objectMapper.writeValueAsString(result));
```

Add malformed provider-shape coverage that reaches the existing fixed result-conversion JSON-RPC error, preserves fatal
`Error` identity, and logs no payload/property/secret value.

- [ ] **Step 7: Run both full emitter modules**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew :generator-spring-ai-1:test :generator-spring-ai-2:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: BUILD SUCCESSFUL and zero failures/errors.

- [ ] **Step 8: Commit Task 3**

```bash
git add generator-spring-ai-1 generator-spring-ai-2
git diff --cached --check
git commit -m "feat(runtime): generate typed Tool results" \
  -m "- Render deterministic response records for both Spring AI families" \
  -m "- Convert normalized result trees into typed Tool return values" \
  -m "- Preserve generic JSON and safe adapter behavior"
```

---

### Task 4: Add bounded retry policy and runtime execution

**Files:**

- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/execution/RetryPolicy.java`
- Create: `generator-domain/src/test/java/io/gen2spring/mcp/domain/execution/RetryPolicyTest.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/config/GenerationRequest.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/tool/McpToolDefinition.java`
- Modify: `generator-application/src/main/java/io/gen2spring/mcp/application/GenerationConfigurationParser.java`
- Modify: `generator-application/src/test/java/io/gen2spring/mcp/application/GenerationConfigurationParserTest.java`
- Modify: `generator-policy/src/main/java/io/gen2spring/mcp/policy/ToolModelFactory.java`
- Modify: `generator-policy/src/test/java/io/gen2spring/mcp/policy/ToolModelFactoryTest.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/OperationMetadataRenderer.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/RuntimeSourceRenderer.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/GeneratedRuntimeRegressionTest.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/OperationMetadataRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/RuntimeSourceRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java`

**Interfaces:**

- Produces: `RetryPolicy(List<Integer> statusCodes, boolean networkErrors, int maxRetries, long initialBackoffMillis, long maxBackoffMillis, boolean respectRetryAfter)`.
- Consumes: `HttpExecutionDefinition.retryPolicy()` and the existing total timeout/telemetry lifecycle.
- Produces: generated runtime `RetryPolicy` metadata and injected monotonic clock/sleeper seam.

- [ ] **Step 1: Write strict retry configuration and policy tests that fail**

Cover omitted policy, exact valid YAML, duplicate/out-of-range statuses, no trigger, zero/four retries, invalid backoff order,
type coercion, unknown fields, explicit null, and value-free error. The valid record assertion is:

```java
assertEquals(new RetryPolicy(
        List.of(429, 503), true, 2, 100, 1000, true),
        request.operations().getFirst().retry());
```

- [ ] **Step 2: Run domain/application tests and verify RED**

```bash
mise exec -- ./gradlew :generator-domain:test :generator-application:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `RetryPolicy` is missing and `retry` is an unknown property.

- [ ] **Step 3: Implement immutable bounded retry configuration**

The constructor enforces the design limits and sorts status codes:

```java
public RetryPolicy {
    statusCodes = statusCodes == null ? List.of() : statusCodes.stream().distinct().sorted().toList();
    if (statusCodes.size() > 16
            || statusCodes.stream().anyMatch(status -> status < 400 || status > 599)
            || !networkErrors && statusCodes.isEmpty()
            || maxRetries < 1 || maxRetries > 3
            || initialBackoffMillis < 1 || initialBackoffMillis > 5000
            || maxBackoffMillis < initialBackoffMillis || maxBackoffMillis > 10000) {
        throw new IllegalArgumentException("Retry policy is invalid");
    }
}
```

Parse exact booleans/integers, add the policy to `OperationSelection` and `HttpExecutionDefinition`, and route all old
constructors to null.

- [ ] **Step 4: Write policy fail-closed tests and verify RED**

Assert retry is accepted only for GET and that configured status codes/values are never present in the safe exception:

```java
GeneratorException failure = assertThrows(GeneratorException.class,
        () -> factory.create(postDocument, requestWithRetry));
assertEquals(OPERATION_UNSUPPORTED, failure.code());
assertEquals("tool-policy", failure.stage());
assertEquals("Retry policy is unsupported for this operation", failure.getMessage());
```

- [ ] **Step 5: Propagate GET retry policy and verify policy GREEN**

Keep null for omitted policy and attach the exact immutable record for GET. Run:

```bash
mise exec -- ./gradlew :generator-policy:test \
  --tests io.gen2spring.mcp.policy.ToolModelFactoryTest \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 6: Write generated retry runtime tests that fail**

Use an injected `RetryClock` and `RetrySleeper` in generated-test constructors. Cover 429/503 success, network exception,
unconfigured status, max retries, exponential sequence `100,200,400`, Retry-After larger/smaller/invalid/overflow,
remaining deadline, interrupt restoration, timeout cancellation, late completion, telemetry attempt count, safe logs, and
fatal `Error` identity.

- [ ] **Step 7: Render retry metadata and execute bounded attempts**

Generate a runtime record and a loop equivalent to:

```java
for (int attempt = 0; ; attempt++) {
    try {
        RawResponse response = executeAttempt(request, deadline);
        if (!retryPolicy.retryableStatus(response.status()) || attempt >= retryPolicy.maxRetries()) {
            return response;
        }
        sleepBeforeRetry(retryPolicy, response.retryAfter(), attempt, deadline);
    } catch (ResourceAccessException failure) {
        if (!retryPolicy.networkErrors() || attempt >= retryPolicy.maxRetries()) {
            throw failure;
        }
        sleepBeforeRetry(retryPolicy, null, attempt, deadline);
    }
}
```

Start/complete the existing provider telemetry call per actual attempt. Do not add status, attempt, operation, or cursor
meter tags. Any retry after the monotonic deadline maps through the existing timeout category.

- [ ] **Step 8: Run generated retry tests and both emitter modules**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
mise exec -- ./gradlew :generator-spring-ai-1:test :generator-spring-ai-2:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: BUILD SUCCESSFUL and zero failures/errors.

- [ ] **Step 9: Commit Task 4**

```bash
git add generator-domain generator-application generator-policy generator-spring-ai-1 generator-spring-ai-2
git diff --cached --check
git commit -m "feat(runtime): execute bounded provider retries" \
  -m "- Parse strict GET-only retry policies" \
  -m "- Honor bounded backoff and Retry-After within the Tool deadline" \
  -m "- Preserve interruption, fatal errors, and telemetry completion"
```

---

### Task 5: Execute pagination and validate ordered upstream interactions

**Files:**

- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/execution/PaginationPolicy.java`
- Create: `generator-domain/src/test/java/io/gen2spring/mcp/domain/execution/PaginationPolicyTest.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/config/GenerationRequest.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/tool/McpToolDefinition.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/generation/GenerationContracts.java`
- Modify: `generator-application/src/main/java/io/gen2spring/mcp/application/GenerationConfigurationParser.java`
- Modify: `generator-application/src/test/java/io/gen2spring/mcp/application/GenerationConfigurationParserTest.java`
- Modify: `generator-policy/src/main/java/io/gen2spring/mcp/policy/OutputSchemaResolver.java`
- Modify: `generator-policy/src/main/java/io/gen2spring/mcp/policy/ToolModelFactory.java`
- Modify: `generator-policy/src/test/java/io/gen2spring/mcp/policy/ToolModelFactoryTest.java`
- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/SchemaFixtureFactory.java`
- Create: `generator-core/src/test/java/io/gen2spring/mcp/core/SchemaFixtureFactoryTest.java`
- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/ExpectedToolResponseFactory.java`
- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/ExpectedToolCallFactory.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/ExpectedToolCallFactoryTest.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/OperationMetadataRenderer.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/RuntimeSourceRenderer.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/GeneratedRuntimeRegressionTest.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/OperationMetadataRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/RuntimeSourceRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java`
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/UpstreamCallExpectation.java`
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/MockUpstreamServer.java`
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/GradleMcpProjectValidator.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/MockUpstreamServerTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GradleMcpProjectValidatorTest.java`

**Interfaces:**

- Produces: `PaginationPolicy(String requestParameter, Object initialValue, String itemsPointer, String nextValuePointer, int maxPages, int maxItems)`.
- Produces: `ExpectedUpstreamInteraction(Map<String,Object> internalParameters, Outcome outcome, ExpectedUpstreamResponse response)` where outcome is `RESPONSE` or `DISCONNECT`.
- Changes: `ExpectedToolCall` stores `List<ExpectedUpstreamInteraction>` and preserves legacy constructors plus `upstreamResponse()` compatibility accessor.
- Consumes: retry policy from Task 4 and response/output schemas from Task 2.

- [ ] **Step 1: Write strict pagination configuration tests that fail**

Cover omitted pagination, valid string/integer initial values, null/boolean/decimal/container values, blank/non-pointer paths,
page/item bounds, unknown fields, and defensive immutable scalar copies.

```java
assertEquals(new PaginationPolicy(
        "cursor", "first", "/response/body/items", "/response/body/nextCursor", 10, 1000),
        request.operations().getFirst().pagination());
```

- [ ] **Step 2: Run domain/application tests and verify RED**

```bash
mise exec -- ./gradlew :generator-domain:test :generator-application:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: pagination type is missing and configuration rejects the field.

- [ ] **Step 3: Implement bounded pagination configuration**

Validate string length `1..2048`, integer as exact `BigInteger`, non-empty RFC 6901 pointers, `2..20` pages, and
`1..2000` items. Add null compatibility defaults to operation/execution constructors.

- [ ] **Step 4: Write Tool-policy pagination tests that fail**

Cover GET-only, existing query parameter, string/integer scalar schema, required-without-initial rejection,
USER_INPUT/SERVER_SECRET collision, API-key target collision, escaped pointers, items array schema, next nullable scalar
schema, typed result integration, and removal from `tool.inputs()`.

- [ ] **Step 5: Internalize pagination binding and verify policy GREEN**

`ToolModelFactory` removes the selected parameter from visible inputs, retains its wire target in `PaginationPolicy`, and
does not create a normal `ParameterBinding` for it. The generated runtime owns the query insertion. Use fixed error:

```text
Pagination policy is unsupported for this operation
```

Run:

```bash
mise exec -- ./gradlew :generator-policy:test --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 6: Write schema fixture and ordered interaction tests that fail**

`SchemaFixtureFactory` tests must independently pin minimal valid values for string enum/length, integer bounds/format,
number, boolean, array, object required fields, escaped keys, unsupported regex, impossible distinct values, and 1 MiB
bound. `ExpectedToolCallFactoryTest` must assert:

```java
assertEquals(2, call.upstreamInteractions().size());
assertEquals(Map.of("cursor", "first"),
        call.upstreamInteractions().get(0).internalParameters());
assertEquals(Map.of("cursor", "gen2spring-next"),
        call.upstreamInteractions().get(1).internalParameters());
assertEquals(expectedAggregatedResult, call.expectedResult());
```

For retry-only validation, assert one failing interaction plus one successful interaction. Network-only retry starts with
`DISCONNECT`.

- [ ] **Step 7: Implement deterministic validation fixtures**

Use production schemas but not renderer/normalizer code to build JSON. Create exactly two pagination pages. First page
contains one valid item and next value; second contains a distinct valid item and null/missing termination. Normalize the
aggregate with an independent core tree builder. Reject constraints that cannot produce valid distinct values with
`VALIDATION_ARGUMENT_INVALID`, stage `TOOL_MODEL_VALIDATE`, fixed message.

- [ ] **Step 8: Write generated pagination runtime tests that fail**

Cover initial omitted/present, string/integer next values, query encoding, two-page aggregation, final next value clearing,
normalization envelope, typed result, repeated token, missing/wrong items shape, wrong next type, max pages, max items,
aggregate bytes, retry-on-second-page, deadline, interruption, fatal Error, telemetry attempt count, and leak markers.

- [ ] **Step 9: Render pagination metadata and aggregate pages**

Generate `PaginationPolicy` runtime metadata and isolate aggregation in a generated `PageAccumulator`:

```java
while (true) {
    RawResponse page = executeWithRetry(operation, arguments, pageValue, deadline);
    JsonNode root = parseSuccessfulPage(page);
    accumulator.append(root);
    JsonNode next = accumulator.nextValue(root);
    if (next == null || next.isNull() || next.isTextual() && next.textValue().isEmpty()) {
        return accumulator.finish(next);
    }
    pageValue = accumulator.requireNewWireValue(next);
}
```

Serialize after each page to enforce the exact aggregate byte limit. Never return partial data when a bound is reached
with a next value still present.

- [ ] **Step 10: Extend mock validation to ordered interactions**

Change `MockUpstreamServer` from one expectation to an immutable list and advance under its existing monitor. Each request
must match the current expectation; `DISCONNECT` closes the exchange before headers. `sealAndAwaitVerified` requires all
interactions exactly once and continues rejecting late requests:

```java
if (observedRequests != expectations.size() || verifiedInteractions != expectations.size()) {
    throw new VerificationException("Mock upstream did not observe the exact request sequence");
}
```

Merge all interaction secret environment overrides and reject inconsistent duplicate values at construction.

- [ ] **Step 11: Run domain through validation modules**

```bash
mise exec -- ./gradlew \
  :generator-domain:test :generator-application:test :generator-policy:test :generator-core:test \
  :generator-spring-ai-1:test :generator-spring-ai-2:test :generator-validation:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: BUILD SUCCESSFUL and zero failures/errors.

- [ ] **Step 12: Commit Task 5**

```bash
git add generator-domain generator-application generator-policy generator-core \
  generator-spring-ai-1 generator-spring-ai-2 generator-validation
git diff --cached --check
git commit -m "feat(runtime): execute bounded pagination" \
  -m "- Internalize explicit query pagination policies" \
  -m "- Aggregate pages within fixed item and byte limits" \
  -m "- Verify ordered retry and pagination interactions"
```

---

### Task 6: Support Windows validation hosts

**Files:**

- Create: `generator-validation/src/main/java/io/gen2spring/mcp/validation/ValidationHostPlatform.java`
- Create: `generator-validation/src/test/java/io/gen2spring/mcp/validation/ValidationHostPlatformTest.java`
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/JavaRuntimeResolver.java`
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/GradleMcpProjectValidator.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/JavaRuntimeResolverTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GradleMcpProjectValidatorTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GeneratedWeatherValidationSmokeTest.java`
- Create: `.github/workflows/ci.yml`

**Interfaces:**

- Produces: package-private `ValidationHostPlatform.current()`, `wrapperFileName()`, `javaExecutable(Path)`, `requiresOwnerExecutable()`, and `buildCommand(Path,Path)`.
- Consumes: existing verified runtime home, wrapper pinning hook, `BoundedProcessRunner`, and validation progress.
- Preserves: legacy validator/resolver constructors by delegating to `ValidationHostPlatform.current()`.

- [ ] **Step 1: Write platform selection and command tests that fail**

Assert case-insensitive known `os.name`, unknown host failure, POSIX names/command, Windows names/command, spaces, and each
rejected control/shell metacharacter. Expected Windows command prefix:

```java
assertEquals(List.of(
        cmd.toString(), "/D", "/E:OFF", "/V:OFF", "/S", "/C",
        "call .gradlew-validated-123.bat -Dorg.gradle.java.installations.auto-detect=false "
                + "-Dorg.gradle.java.installations.auto-download=false "
                + "-Dorg.gradle.java.installations.paths=\"C:\\Java 17\" "
                + "classes test bootJar --no-daemon --non-interactive"), command);
```

Do not assert or log the rejected path value in exception text.

- [ ] **Step 2: Run focused platform tests and verify RED**

```bash
mise exec -- ./gradlew :generator-validation:test \
  --tests io.gen2spring.mcp.validation.ValidationHostPlatformTest \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `ValidationHostPlatform` does not exist.

- [ ] **Step 3: Implement the injected platform adapter**

Use a sealed package-private interface with immutable POSIX and Windows implementations. Resolve Windows cmd from
`SystemRoot/System32/cmd.exe`, require an absolute physical regular file, and use only the generated relative snapshot
filename in `/C`. Reject `\r`, `\n`, NUL, `"`, `%`, `!`, `^`, `&`, `|`, `<`, and `>` in the target JDK path.

- [ ] **Step 4: Write Java runtime executable tests that fail**

Create fake physical runtime homes with `bin/java` and `bin/java.exe`; assert each platform probes only its exact filename,
rechecks stable physical identity, and never falls back across platforms. Characterize the Windows JDK's null native
file-key boundary with a physical-path/file-store/creation-time/bounded-metadata fallback test.

- [ ] **Step 5: Inject platform into JavaRuntimeResolver and verify GREEN**

Keep all current path identity/version/probe/descendant cleanup behavior. Change only executable selection and old constructor
delegation. Run `JavaRuntimeResolverTest` plus `ValidationHostPlatformTest`.

- [ ] **Step 6: Write wrapper pinning and invocation tests that fail**

Cover POSIX owner-executable characterization, Windows no-executable requirement, `gradlew.bat` selection, hard-link/stable
identity, replacement after snapshot, symlink, directory, outside root, unstable identity, unsafe target-home path, exact
`cmd.exe` argv, validation failure report, and no archive publication.

- [ ] **Step 7: Make wrapper verification platform-aware**

Select the candidate with `platform.wrapperFileName()`, call `requireOwnerExecutable` only when requested, preserve the
same-root hard-link snapshot and launch-time `requireStable`, and pass `platform.buildCommand(snapshot, runtime.home())` to
`BoundedProcessRunner`. POSIX must still execute `snapshot.toString()` directly.

- [ ] **Step 8: Add real Windows CI**

Create two jobs in `.github/workflows/ci.yml`: `linux` and `windows`. Set top-level `permissions: contents: read`.
Pin checkout to `actions/checkout@11d5960a326750d5838078e36cf38b85af677262` and setup-java to
`actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3`, with comments identifying both as v4. Both jobs
install Temurin 17 then 21, capture exact homes, and run the full acceptance. The Windows job uses PowerShell:

```yaml
- name: Test Windows validation
  shell: pwsh
  run: >-
    .\gradlew.bat clean test integrationTest :generator-cli:installDist
    --no-daemon --non-interactive --rerun-tasks
```

Capture Java homes into `$GITHUB_ENV` immediately after each `actions/setup-java@v4` step. Pin checkout/setup-java action
major versions and Gradle distribution checksum remains enforced by wrapper properties.

- [ ] **Step 9: Run local POSIX validation regression**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew :generator-validation:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: BUILD SUCCESSFUL, current POSIX tests pass, Windows unit paths pass through injection.

- [ ] **Step 10: Commit Task 6**

```bash
git add generator-validation .github/workflows/ci.yml
git diff --cached --check
git commit -m "feat(validation): support Windows hosts" \
  -m "- Select and pin platform-specific Gradle wrappers" \
  -m "- Resolve target Java executables without cross-platform fallback" \
  -m "- Verify Windows build and MCP validation in CI"
```

---

### Task 7: Propagate policies to preview, editor, manifest, and generated docs

**Files:**

- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationPreview.java`
- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationPipeline.java`
- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationManifestWriter.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPreviewTest.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java`
- Modify: `generator-web/src/main/java/io/gen2spring/mcp/web/PreviewHandler.java`
- Modify: `generator-web/src/main/resources/web/index.html`
- Modify: `generator-web/src/main/resources/web/editor.js`
- Modify: `generator-web/src/main/resources/web/state.js`
- Modify: `generator-web/src/main/resources/web/styles.css`
- Modify: `generator-web/src/test/java/io/gen2spring/mcp/web/StaticAssetContractTest.java`
- Modify: `generator-web/src/test/java/io/gen2spring/mcp/web/LocalWebServerTest.java`
- Modify: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/ProjectFileRenderer.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/ProjectFileRendererTest.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ProjectFileRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java`

**Interfaces:**

- Consumes: immutable output/retry/pagination Tool IR from Tasks 2, 4, and 5.
- Produces: deterministic preview JSON and manifest policy objects.
- Produces: accessible local editor controls that serialize the exact CLI configuration shape.

- [ ] **Step 1: Write preview and manifest tests that fail**

Assert exact output/retry/pagination values and key order, typed schema checksum, omitted-policy field absence, no cursor/runtime
values, and deterministic repeated bytes. The manifest shape is pinned independently:

```json
{
  "output": {"mode": "TYPED", "schemaChecksum": "<64 lowercase hex>"},
  "pagination": {
    "itemsPath": "/items",
    "maxItems": 1000,
    "maxPages": 10,
    "nextValuePath": "/next",
    "requestParameter": "cursor"
  },
  "retry": {
    "initialBackoffMillis": 100,
    "maxBackoffMillis": 1000,
    "maxRetries": 2,
    "networkErrors": true,
    "respectRetryAfter": true,
    "statusCodes": [429, 503]
  }
}
```

- [ ] **Step 2: Run core tests and verify RED**

```bash
mise exec -- ./gradlew :generator-core:test --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 3: Propagate deterministic preview/manifest policy**

Add nested immutable preview records and render fields from Tool IR, never raw configuration. Compute schema checksum from a
canonical schema map with the existing SHA-256 utility or a focused deterministic helper. Omit null policies rather than
emitting ambiguous empty objects.

- [ ] **Step 4: Write local editor tests that fail**

Assert accessible controls, labels, numeric min/max, select values, per-operation state retention, safe JSON initial value,
preview round-trip, unknown input rejection, and mobile 400px layout. Required DOM IDs:

```text
output-mode
retry-enabled
retry-status-codes
retry-network-errors
retry-max-retries
retry-initial-backoff
retry-max-backoff
retry-respect-retry-after
pagination-enabled
pagination-request-parameter
pagination-initial-value
pagination-items-path
pagination-next-value-path
pagination-max-pages
pagination-max-items
```

- [ ] **Step 5: Implement editor serialization and preview rendering**

Keep all content in static first-party assets, use `textContent`, and serialize only enabled policy objects. Status codes are
comma-separated integers normalized to unique ascending JSON numbers. `initialValue` accepts one JSON string/integer scalar
through the existing safe JSON parser. Disabling a policy removes its object from generated configuration.

- [ ] **Step 6: Update generated READMEs and tests**

For each operation, document output mode, retry triggers/bounds, pagination pointers/bounds, and failure-on-limit behavior.
Keep omitted policies described as generic JSON, no retry, one request. Do not include provider URL, secret environment value,
or runtime cursor.

- [ ] **Step 7: Run core/web/emitter tests**

```bash
mise exec -- ./gradlew \
  :generator-core:test :generator-web:test :generator-spring-ai-1:test :generator-spring-ai-2:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: BUILD SUCCESSFUL and zero failures/errors.

- [ ] **Step 8: Commit Task 7**

```bash
git add generator-core generator-web generator-spring-ai-1 generator-spring-ai-2
git diff --cached --check
git commit -m "feat(web): configure P1 execution policies" \
  -m "- Expose typed output, retry, and pagination in preview and manifest" \
  -m "- Add accessible operation editor controls" \
  -m "- Document generated runtime behavior per operation"
```

---

### Task 8: Prove four-profile journeys and close P1 documentation

**Files:**

- Modify: `generator-cli/src/test/resources/config/weather-generation.yaml`
- Modify: `generator-cli/src/test/resources/config/weather-generation-java17.yaml`
- Modify: `generator-cli/src/test/resources/config/weather-generation-spring-ai1-java17.yaml`
- Modify: `generator-cli/src/test/resources/config/weather-generation-spring-ai1-java21.yaml`
- Modify: `generator-cli/src/integrationTest/java/io/gen2spring/mcp/cli/P1GenerationIntegrationTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`
- Modify: `README.md`
- Modify: `docs/prd.md`
- Modify: `generator-web/src/test/java/io/gen2spring/mcp/web/ReadmeContractTest.java`

**Interfaces:**

- Consumes: installed CLI, four canonical profiles, target Java homes, raw MCP JSON-RPC oracle, and ordered test-local
  upstream recorder.
- Produces: end-to-end proof for typed DTO plus retry plus pagination and completion documentation.

- [ ] **Step 1: Write four-profile acceptance assertions that fail**

Extend the test-local JDK `HttpServer` recorder to return three interactions: retryable 503, first page, second page. The raw
JDK MCP client must assert exact Tool schema, `isError` field presence/type/false, exact aggregated normalized result with no
raw envelope/next token, and exactly three ordered requests per profile. Do not import production validation helpers.

- [ ] **Step 2: Run the focused CLI integration and verify RED**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew :generator-cli:integrationTest \
  --tests io.gen2spring.mcp.cli.P1GenerationIntegrationTest \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: existing fixture/result/request-count assertions fail before fixture updates.

- [ ] **Step 3: Update exact CLI fixtures**

Use the same operation policy for all four profiles, changing only `targetProfileId`. Include `output.mode=TYPED`, one 503
retry, cursor pagination with two pages, and exact validation arguments that do not include the internal cursor parameter.

- [ ] **Step 4: Verify focused four-profile GREEN**

Run the Step 2 command. Expected: all profile rows generate twice, compile/test/boot with selected target JDK, pass raw
tools/list/tools/call, and observe exactly three ordered upstream requests.

- [ ] **Step 5: Write documentation regression assertions that fail**

Assert root README and PRD describe completed typed output, GET retry limits, pagination limits, Windows host boundary, all
four profiles, and issue #2 relationship. Assert these strings are absent:

```text
typed output DTO, retry 실행, pagination 실행은 후속 P1 범위다
Windows validation host remains follow-up P1
```

- [ ] **Step 6: Update README and PRD**

Move the four follow-up items into completed P1. Document exact YAML, defaults, bounds, safe failures, generic compatibility,
Windows filesystem/path requirements, and the Windows CI evidence. Keep WebFlux, async, Maven, STDIO, OpenAPI 3.1, Kotlin,
managed runtime metadata, description enhancement, and migration in P2.

- [ ] **Step 7: Run affected suites**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew \
  :generator-domain:test :generator-openapi:test :generator-application:test :generator-policy:test \
  :generator-core:test :generator-spring-ai-1:test :generator-spring-ai-2:test \
  :generator-validation:test :generator-cli:test :generator-cli:integrationTest :generator-web:test \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 8: Run exact full acceptance**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew clean test integrationTest :generator-cli:installDist \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: BUILD SUCCESSFUL, zero failures/errors, and only documented filesystem-assumption skips if the current host is
case-sensitive.

- [ ] **Step 9: Perform completion readback**

```bash
git diff --check
git status --short
rg -n "typed output DTO, retry 실행, pagination 실행은 후속 P1|Windows validation host remains follow-up P1" \
  README.md docs/prd.md generator-* || true
rg -n "mcp-validation-secret-|Authorization:|ghp_|Exception:|at [A-Za-z0-9_.$]+\(" \
  generator-*/build/test-results || true
ps -Ao pid,ppid,command | rg "openapi-mcp|GradleDaemon 9\.6\.1" || true
```

Inspect every match; test fixture literals are allowed only when assertion failures do not echo the value. No generated app,
mock server, target Java process, or Gradle 9.6.1 daemon may remain.

- [ ] **Step 10: Review inline and commit documentation/integration**

Run `coderabbit review --agent -t uncommitted` only if authenticated and seat-assigned; otherwise record the unavailable
external review and complete an inline diff/security review. Address Critical/Warning findings with RED/GREEN evidence.

```bash
git add README.md docs/prd.md generator-cli generator-web/src/test/java/io/gen2spring/mcp/web/ReadmeContractTest.java
git diff --cached --check
git commit -m "docs: complete P1 runtime support" \
  -m "- Verify typed retrying pagination across all four profiles" \
  -m "- Document Windows validation and bounded execution contracts" \
  -m "- Remove completed features from follow-up P1 scope"
```

- [ ] **Step 11: Push the feature branch**

```bash
git status --short --branch
git log --oneline origin/main..HEAD
git push -u origin feat/complete-p1-followups
```

Expected: clean branch, meaningful commits only, and remote tracking branch created. Do not open or merge a PR unless the
user requests it after push.

## Completion Gate

- [ ] Omitted policy produces generic JSON, no retry, and exactly one upstream request.
- [ ] Typed output compiles and runs on Spring AI 1/2 and Java 17/21 with exact normalized JSON semantics.
- [ ] Retry executes only configured GET failures, honors bounded backoff/Retry-After/deadline, and preserves error safety.
- [ ] Pagination internalizes its query value, aggregates exactly, rejects repeated/invalid tokens, and never truncates silently.
- [ ] Validation proves the exact ordered retry/page request sequence and rejects missing/duplicate/reordered/late requests.
- [ ] POSIX wrapper behavior remains green and Windows `gradlew.bat` plus target `java.exe` pass real CI validation.
- [ ] Preview, editor, manifest, generated README, root README, and PRD expose the same deterministic policy contract.
- [ ] Full repository acceptance reports zero failures/errors and leaves no process or sensitive diagnostic leak.
- [ ] Feature branch is clean and pushed to `origin/feat/complete-p1-followups`.
