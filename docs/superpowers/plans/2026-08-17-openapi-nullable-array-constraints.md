# OpenAPI Nullable Inputs and Array Bounds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve explicit null request-body values and enforce `minItems` while making the paired OpenAPI 3.0/3.1 fixtures fully selectable.

**Architecture:** The analyzer normalizes nullable body descendants and array lower bounds into the canonical schema. Generated MCP schemas publish `anyOf` null unions and `minItems`; a scoped generated `ToolArgumentContext` lets the existing MethodToolCallback path preserve raw argument presence without bypassing validation or error handling.

**Tech Stack:** Java 21 generator, generated Java 17/21 projects, Swagger Parser, Jackson 2/3, Spring AI 1/2, MCP Streamable HTTP, Jakarta Validation, Gradle 9.6.1.

## Global Constraints

- OpenAPI 3.0 `nullable: true` and OpenAPI 3.1 `type: [T, null]` must normalize identically.
- Nullable path/query/header parameters and nullable root request bodies remain unsupported.
- `minItems` is supported; `maxItems` and `uniqueItems` remain fail-closed.
- Existing provider error, secret masking, tracing, retry, pagination, and response normalization contracts must not change.
- Spring AI 1 and Spring AI 2 generated behavior must remain equivalent.
- Do not commit or push without separate user authorization.

---

### Task 1: Canonical Schema and Analyzer Boundary

**Files:**
- Modify: `modules/domain/src/main/java/io/gen2spring/mcp/domain/specification/OpenApiDocument.java`
- Modify: `modules/domain/src/main/java/io/gen2spring/mcp/domain/specification/OperationSupport.java`
- Modify: `modules/adapters/openapi/src/main/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerSchemaNormalizer.java`
- Modify: `modules/adapters/openapi/src/main/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerOpenApiAnalyzer.java`
- Test: `modules/adapters/openapi/src/test/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerSchemaNormalizerTest.java`
- Test: `modules/adapters/openapi/src/test/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerOpenApiAnalyzerTest.java`
- Test: `modules/domain/src/test/java/io/gen2spring/mcp/domain/specification/OperationSupportTest.java`

**Interfaces:**
- `ApiSchema` gains `Integer minItems` and retains an overload compatible with existing constructor call sites.
- `SwaggerSchemaNormalizer.normalizeRequestBody(...)` allows nullable descendants while parameter normalization remains unchanged.
- Root request-body nullability is added to operation issues as `SCHEMA_NULLABILITY_UNSUPPORTED`.

- [x] Add analyzer tests for equivalent 3.0/3.1 nullable body properties, required-nullable properties, nullable parameters, nullable root bodies, and `minItems`.
- [x] Run focused tests and confirm RED from nullability/minItems rejection and the old nested message.
- [x] Add canonical `minItems`, request-body-specific nullable normalization, root boundary validation, and the clarified nested issue message.
- [x] Run domain and OpenAPI focused tests to GREEN.

### Task 2: MCP Schema and Generated Validation

**Files:**
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/validation/ExpectedToolSchemaFactory.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/command/GenerationCommand.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/validation/ExpectedToolCall.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/validation/ExpectedToolCallFactory.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/usecase/GenerationPreview.java`
- Modify: `modules/adapters/configuration/src/main/java/io/gen2spring/mcp/adapter/configuration/GenerationConfigurationParser.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/validation/ExpectedToolSchemaFactoryTest.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/validation/ExpectedToolCallFactoryTest.java`
- Test: `modules/adapters/configuration/src/test/java/io/gen2spring/mcp/adapter/configuration/GenerationConfigurationParserTest.java`
- Test: `modules/adapters/filesystem/src/test/java/io/gen2spring/mcp/adapter/filesystem/GenerationPreviewTest.java`
- Modify: `modules/adapters/emitters/spring-ai-1/src/main/java/io/gen2spring/mcp/adapter/emitter/springai1/InputRecordRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/InputRecordRenderer.java`
- Test: `modules/adapters/emitters/spring-ai-1/src/test/java/io/gen2spring/mcp/adapter/emitter/springai1/JavaSourceRendererTest.java`
- Test: `modules/adapters/emitters/spring-ai-2/src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/JavaSourceRendererTest.java`

**Interfaces:**
- `ExpectedToolSchemaFactory.schema(ApiSchema)` emits `anyOf: [nonNullSchema, {type: null}]` when nullable.
- Array schemas emit `minItems` when present.
- Generation configuration preserves JSON null and semantic validation accepts it only for nullable Tool inputs.
- Generated validation emits `@NotNull` only for required non-nullable inputs and `@Size(min = n)` for bounded arrays.

- [x] Add exact independent JSON-schema tests for nullable scalar, nullable nested object, required-nullable, and array `minItems`.
- [x] Run application tests and confirm RED from missing `anyOf` and `minItems`.
- [x] Implement the bounded schema rendering and preview metadata.
- [x] Add emitter source assertions for nullable `@NotNull` suppression and array `@Size`.
- [x] Run both emitter source suites to GREEN.

### Task 3: Presence-Preserving Generated Runtime

**Files:**
- Modify: `modules/adapters/emitters/spring-ai-1/src/main/java/io/gen2spring/mcp/adapter/emitter/springai1/RuntimeSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/RuntimeSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-1/src/main/java/io/gen2spring/mcp/adapter/emitter/springai1/ToolCallbackConfigurationRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ToolCallbackConfigurationRenderer.java`
- Modify: both `InputRecordRenderer.java` files from Task 2.
- Test: both emitter `JavaSourceRendererTest.java` files.
- Test: `modules/adapters/emitters/spring-ai-1/src/test/java/io/gen2spring/mcp/adapter/emitter/springai1/GeneratedRuntimeRegressionTest.java`
- Test: `modules/adapters/emitters/spring-ai-2/src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/GeneratedProjectSmokeTest.java`

**Interfaces:**
- Generated `runtime.ToolArgumentContext` exposes `open(Map<String,Object>)`, `active()`, `contains(String)`, `value(String)`, and `Scope.close()`.
- Callback handlers open the scope around `callback.call(input)`.
- Generated input `toArguments()` uses raw values only while the context is active; direct typed calls include null only for nullable inputs.

- [x] Add generated-project tests that distinguish omitted body properties from explicit null at the mock upstream.
- [x] Add scope cleanup and nested-scope source/runtime tests; confirm RED because the context is absent.
- [x] Render `ToolArgumentContext`, wrap callback invocation, and make input map construction presence-aware.
- [x] Run focused Spring AI 1/2 generated runtime suites to GREEN, including existing provider/fatal/secret regressions.

### Task 4: Paired Fixture and End-to-End Contract

**Files:**
- Modify: `swagger-3.0.yml`
- Modify: `swagger-3.1.yml`
- Modify: `modules/adapters/openapi/src/test/java/io/gen2spring/mcp/adapter/openapi/swagger/OpenApiVersionPairAcceptanceTest.java`
- Modify: `apps/cli/src/test/java/io/gen2spring/mcp/app/cli/InstalledCliTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedWebMvcContractTest.java`
- Modify: `apps/cli/src/integrationTest/java/io/gen2spring/mcp/app/cli/P1GenerationIntegrationTest.java`

**Interfaces:**
- Paired fixture counts become `26 supported`, `0 supportedWithWarning`, `0 unsupported`.
- The selected nullable/minItems operation produces the same Tool schema and exact provider body for both OpenAPI versions.

- [x] Update paired acceptance expectations to require all 26 operations supported.
- [x] Add an independent CLI integration journey for omitted/null/minItems behavior and exactly-one upstream verification.
- [x] Run paired analyzer, CLI inspect, local Web, hosted Web, and representative generation tests.

### Task 5: Final Verification and Readback

**Files:**
- Review all files changed by Tasks 1-4.

- [x] Run affected domain, application, OpenAPI, filesystem, Spring AI 1, Spring AI 2, CLI, and Web tests with explicit mise Java 17/21 homes.
- [x] Run the focused CLI integration journey for both paired specifications.
- [x] Parse both YAML files, require no `*/*` success media declaration, and run `git diff --check`.
- [x] Re-read the approved design against the diff and report exact test evidence, remaining unsupported boundaries, and uncommitted status.
