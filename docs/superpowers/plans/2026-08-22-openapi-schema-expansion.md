# Bounded OpenAPI Schema Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the accepted GitHub issue #12 schema slices and make the root OpenAPI 3.0/3.1 fixtures executable parity contracts.

**Architecture:** The OpenAPI adapter normalizes bounded nullability, array constraints, composition, and reference intersections into one canonical schema model. A shared application validator protects generator and Managed Runtime execution, while both Spring AI emitters render equivalent generated validation and profile-specific composed JSON values.

**Tech Stack:** Java 21 generator, generated Java 17/21 projects, Swagger Parser, Jackson 2/3 profiles, Spring AI 1/2, MCP Java SDK, Jakarta Validation, Gradle 9.6.1.

**Spec:** `docs/superpowers/specs/2026-08-22-openapi-schema-expansion-design.md`

## Global Constraints

- Keep OpenAPI 3.0 and 3.1 fixture operations semantically paired.
- Optional nullable query/header null means omission; required nullable query/header and nullable path remain unsupported.
- Root body absence and explicit JSON null must remain distinct.
- Support `uniqueItems` only with explicit `maxItems <= 256`; never clamp a larger OpenAPI value.
- Bound each composition to 8 branches, schema depth to 16, and total composition branches per operation to 64.
- Keep recursive `$ref` and discriminator semantics unsupported with fixed reasons.
- Preserve Runtime Metadata version `1.0`; extend only its bounded JSON Schema keyword allow-list.
- Preserve Spring AI 1/2, generator validation, and Managed Runtime parity.
- Preserve secret masking, provider error, retry, pagination, timeout, tracing, interruption, and fatal-error contracts.
- Use meaningful vertical-slice feature commits and do not push without separate user authorization.

---

### Task 1: Extend the Canonical Schema and Normalizer

**Files:**
- Modify: `modules/domain/src/main/java/io/gen2spring/mcp/domain/specification/OpenApiDocument.java`
- Modify: `modules/domain/src/main/java/io/gen2spring/mcp/domain/specification/OperationSupport.java`
- Modify: `modules/adapters/openapi/src/main/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerSchemaNormalizer.java`
- Modify: `modules/adapters/openapi/src/main/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerOpenApiAnalyzer.java`
- Test: `modules/domain/src/test/java/io/gen2spring/mcp/domain/specification/OperationSupportTest.java`
- Test: `modules/adapters/openapi/src/test/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerSchemaNormalizerTest.java`
- Test: `modules/adapters/openapi/src/test/java/io/gen2spring/mcp/adapter/openapi/swagger/SwaggerOpenApiAnalyzerTest.java`

**Interfaces:**

```java
enum SchemaType { STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT, COMPOSED }
enum CompositionKind { ONE_OF, ANY_OF }
record SchemaComposition(CompositionKind kind, List<ApiSchema> branches) {}
```

`ApiSchema` gains `Integer maxItems`, `boolean uniqueItems`, and optional `SchemaComposition composition`; retain one
compatibility constructor while migrating call sites, then use the canonical full constructor in production paths.

- [x] **Step 1: Write normalizer RED tests**

  Cover 3.0/3.1 optional nullable query/header, required/path rejection, nullable root object, valid/invalid max and
  unique bounds, compatible/conflicting `allOf`, bounded `oneOf`/`anyOf`, multi-type union, semantic 3.1 `$ref`
  siblings, 3.0 equivalent `allOf`, recursion, discriminator, branch count, total branch budget, and depth.

- [x] **Step 2: Run domain and OpenAPI tests and confirm RED**

  Run:

  ```bash
  mise exec -- ./gradlew :modules:domain:test \
    :modules:adapters:openapi:test \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [x] **Step 3: Implement bounded normalization and intersection**

  Separate reference resolution, composition-budget tracking, and schema intersection into focused package-private
  collaborators instead of enlarging `SwaggerSchemaNormalizer` with every responsibility. Normalize `allOf` and 3.1
  `$ref` siblings through one intersection engine; normalize multi-type declarations into `ANY_OF` branches; retain
  precise child and contextual issue codes.

- [x] **Step 4: Enforce location-specific nullability in the analyzer**

  Allow nullable optional query/header and request body roots, reject nullable path and required query/header with
  fixed codes, and preserve every unsupported operation in analysis results.

- [x] **Step 5: Run focused tests to GREEN**

  Run the Step 2 command and all `:modules:adapters:openapi:test` tests.

- [x] **Step 6: Commit canonical schema support**

  Commit title: `feat(openapi): normalize bounded schema composition`

---

### Task 2: Project and Validate the Canonical JSON Schema

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/toolmodel/schema/SchemaValueValidator.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/toolmodel/schema/CanonicalJsonValue.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/toolmodel/schema/ToolJsonSchemaFactory.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/validation/ExpectedToolCallFactory.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/usecase/GenerationPreview.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/toolmodel/output/OutputSchemaResolver.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/runtime/metadata/CanonicalRuntimeMetadataCodec.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/RuntimeHttpRequestFactory.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/toolmodel/schema/ToolJsonSchemaFactoryTest.java`
- Create: `modules/application/src/test/java/io/gen2spring/mcp/application/toolmodel/schema/SchemaValueValidatorTest.java`
- Modify: `modules/application/src/test/java/io/gen2spring/mcp/application/validation/ExpectedToolCallFactoryTest.java`
- Modify: `modules/application/src/test/java/io/gen2spring/mcp/application/toolmodel/output/OutputSchemaResolverTest.java`
- Modify: `modules/application/src/test/java/io/gen2spring/mcp/application/runtime/metadata/CanonicalRuntimeMetadataCodecTest.java`
- Modify: `modules/application/src/test/java/io/gen2spring/mcp/application/managed/execution/RuntimeHttpRequestFactoryTest.java`

**Interfaces:**

```java
public final class SchemaValueValidator {
    public void validate(ApiSchema schema, Object value);
    public void validate(Map<String, Object> jsonSchema, Object value);
}
```

`CanonicalJsonValue` produces bounded structural hash/equality values with object-order independence and numeric
`BigDecimal.compareTo` equality. It never renders raw values into an exception or log.

- [x] **Step 1: Write schema projection RED tests**

  Assert exact `maxItems`, `uniqueItems`, `oneOf`, `anyOf`, nullable wrappers, sorted properties/required values, and
  metadata codec round-trip. Reject unknown keywords, invalid type/keyword combinations, oversized branch lists, and
  non-canonical numbers.

- [x] **Step 2: Write value-validation RED tests**

  Cover min/max arrays, duplicate nested objects with different key order, `1` versus `1.0`, ordered nested arrays,
  exactly-one `oneOf`, at-least-one `anyOf`, nullability, branch budget, and fixed value-free failures.

- [x] **Step 3: Run application tests and confirm RED**

  Run:

  ```bash
  mise exec -- ./gradlew :modules:application:test \
    --tests '*ToolJsonSchemaFactoryTest' \
    --tests '*SchemaValueValidatorTest' \
    --tests '*ExpectedToolCallFactoryTest' \
    --tests '*CanonicalRuntimeMetadataCodecTest' \
    --tests '*RuntimeHttpRequestFactoryTest' \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [x] **Step 4: Implement projection, canonical uniqueness, and shared validation**

  Preserve immutable sorted maps/lists, treat an SDK validation pass as additional rather than authoritative, call
  the validator before representative and Managed Runtime provider request construction, and keep the failure type at
  the existing safe boundary.

- [x] **Step 5: Run application tests to GREEN**

  Run the Step 3 command and the complete `:modules:application:test` suite.

- [x] **Step 6: Commit schema projection and validation**

  Commit title: `feat(schema): enforce bounded JSON schema values`

---

### Task 3: Preserve Nullable Parameter and Root-body Wire Semantics

**Files:**
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/toolmodel/ToolModelFactory.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/toolmodel/ToolModelFactoryTest.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/RuntimeHttpRequestFactory.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/managed/execution/RuntimeHttpRequestFactoryTest.java`

**Interfaces:**

Keep the existing `HttpExecution.objectRequestBody` and `requestBodyRequired` metadata contract. A nullable root
object becomes one `body` binding with `objectRequestBody == false`; request builders use an internal
`RequestBodyValue(boolean present, Object value)` so absence and a present null do not require a metadata version
change.

- [x] **Step 1: Write Tool-model and Managed Runtime RED tests**

  Require nullable root object to produce one `body` input, absent optional root to send no content type/body,
  explicit null to send JSON `null`, required nullable absence to fail, optional nullable query/header null to omit,
  and credential injection to remain authoritative after omission.

- [x] **Step 2: Run focused tests and confirm RED**

  Run:

  ```bash
  mise exec -- ./gradlew :modules:application:test \
    --tests '*ToolModelFactoryTest' \
    --tests '*RuntimeHttpRequestFactoryTest' \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [x] **Step 3: Implement explicit root-body mode**

  Preserve existing flattened bodies, model nullable roots as one input, use argument-key presence before value
  conversion, omit null query/header bindings, and serialize a present null root as exactly four UTF-8 bytes `null`.

- [x] **Step 4: Run application and emitter source tests to GREEN**

  Run the Step 2 command plus both emitter `JavaSourceRendererTest` suites.

- [x] **Step 5: Commit wire semantics**

  Commit title: `feat(runtime): preserve nullable HTTP argument semantics`

---

### Task 4: Render Equivalent Spring AI 1 and 2 Projects

**Files:**
- Modify: `modules/adapters/emitters/spring-ai-1/src/main/java/io/gen2spring/mcp/adapter/emitter/springai1/JavaSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-1/src/main/java/io/gen2spring/mcp/adapter/emitter/springai1/InputRecordRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-1/src/main/java/io/gen2spring/mcp/adapter/emitter/springai1/RuntimeSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-1/src/main/java/io/gen2spring/mcp/adapter/emitter/springai1/ToolClassRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-1/src/main/java/io/gen2spring/mcp/adapter/emitter/springai1/ToolCallbackConfigurationRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-1/src/main/java/io/gen2spring/mcp/adapter/emitter/springai1/OutputRecordRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/JavaSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/InputRecordRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/RuntimeSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ToolClassRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ToolCallbackConfigurationRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/OutputRecordRenderer.java`
- Test: `modules/adapters/emitters/spring-ai-1/src/test/java/io/gen2spring/mcp/adapter/emitter/springai1/JavaSourceRendererTest.java`
- Test: `modules/adapters/emitters/spring-ai-1/src/test/java/io/gen2spring/mcp/adapter/emitter/springai1/GeneratedRuntimeRegressionTest.java`
- Test: `modules/adapters/emitters/spring-ai-1/src/test/java/io/gen2spring/mcp/adapter/emitter/springai1/GeneratedProjectSmokeTest.java`
- Test: `modules/adapters/emitters/spring-ai-2/src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/JavaSourceRendererTest.java`
- Test: `modules/adapters/emitters/spring-ai-2/src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/GeneratedProjectSmokeTest.java`

**Interfaces:**

- `COMPOSED` inputs use the profile-compatible Jackson `JsonNode` type.
- Generated `SchemaValueValidator` exposes one bounded `validate(schema, rawValue)` entry point.
- Generated callback handlers validate the original presence map before typed callback invocation.

- [x] **Step 1: Add emitter and generated-runtime RED tests**

  Assert profile-correct `JsonNode` imports, exact MCP schemas, nullable body presence, query/header omission,
  max/unique validation, `oneOf`/`anyOf` validation, value-free errors, scope cleanup, and zero upstream calls for
  invalid values.

- [x] **Step 2: Run both emitter suites and confirm RED**

  Run:

  ```bash
  GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
  mise exec -- ./gradlew \
    :modules:adapters:emitters:spring-ai-1:test \
    :modules:adapters:emitters:spring-ai-2:test \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [x] **Step 3: Render the minimal equivalent validators and bindings**

  Keep version-specific imports inside their emitter. Share generator-side decisions through Tool IR and canonical
  schemas; do not copy Spring AI version conditionals into domain/application modules. Preserve the existing callback,
  telemetry, provider-error, retry, pagination, and fatal boundaries.

- [x] **Step 4: Run both generated-project suites to GREEN**

  Run the Step 2 command and record generated compile, context, `tools/list`, and `tools/call` evidence for Java 17
  and 21 where each profile supports them.

- [x] **Step 5: Commit emitter parity**

  Commit title: `feat(emitters): generate bounded composed schemas`

---

### Task 5: Turn the Root Swagger Files into Reproduction Contracts

**Files:**
- Modify: `swagger-3.0.yml`
- Modify: `swagger-3.1.yml`
- Modify: `modules/adapters/openapi/src/test/java/io/gen2spring/mcp/adapter/openapi/swagger/OpenApiVersionPairAcceptanceTest.java`
- Modify: `apps/cli/src/test/java/io/gen2spring/mcp/app/cli/InstalledCliTest.java`
- Modify: `apps/cli/src/integrationTest/java/io/gen2spring/mcp/app/cli/P1GenerationIntegrationTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedWebMvcContractTest.java`
- Modify: `apps/runtime/src/integrationTest/java/io/gen2spring/mcp/app/runtime/ManagedRuntimeJourneyIntegrationTest.java`

- [ ] **Step 1: Add paired fixture operations and RED parity assertions**

  Add stable operation IDs for optional nullable query/header, required and optional nullable root body,
  min/max/unique arrays, compatible/conflicting `allOf`, `oneOf`, `anyOf`, 3.1 `$ref` sibling with 3.0 equivalent,
  nullable path, and budget overflow. Do not remove or weaken existing 26 operations.

- [ ] **Step 2: Run paired analysis and confirm RED**

  Run:

  ```bash
  mise exec -- ./gradlew :modules:adapters:openapi:test \
    :apps:cli:test :apps:web:test \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 3: Complete analyzer, preview, and presenter parity**

  Require exact operation IDs, support state, primary/context issue codes, and canonical Tool schemas across both
  dialects. Supported and unsupported examples must remain visible in CLI, local Web, and hosted Web.

- [ ] **Step 4: Add generated and Managed Runtime wire journeys**

  For each new supported family, prove one valid and one invalid call, exact upstream request count, exact URI/header
  omission, exact JSON null body, structural duplicate rejection, and composition branch selection. Use both root
  fixtures and synthetic secrets only.

- [ ] **Step 5: Run fixture and end-to-end tests to GREEN**

  Run the Step 2 command plus the focused CLI and Managed Runtime integration tests.

- [ ] **Step 6: Commit executable fixtures**

  Commit title: `test(openapi): cover bounded schema contracts`

---

### Task 6: Synchronize Product Truth and Run Full Acceptance

**Files:**
- Modify: `README.md`
- Modify: `docs/prd.md`
- Modify: `docs/user-guide.md`
- Modify: `docs/architecture/hosted-generation-platform.html`
- Review: GitHub issue #12 against the implemented supported and intentionally unsupported checklist.

- [ ] **Step 1: Write documentation-contract RED assertions**

  Require accurate support for nullable optional query/header, nullable root body, bounded max/unique arrays,
  composition and 3.1 reference siblings. Keep nullable path, required nullable query/header, recursion, discriminator,
  and budget overflow explicit.

- [ ] **Step 2: Update docs and issue disposition notes**

  Mark only verified behavior complete. Prepare the exact GitHub issue #12 comment/checklist update but do not post or
  close it without the later publication/review authorization.

- [ ] **Step 3: Run affected acceptance**

  Run:

  ```bash
  GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
  mise exec -- ./gradlew \
    :modules:domain:test :modules:application:test :modules:adapters:openapi:test \
    :modules:adapters:filesystem:test \
    :modules:adapters:emitters:spring-ai-1:test \
    :modules:adapters:emitters:spring-ai-2:test \
    :modules:adapters:mcp-java-sdk:test \
    :apps:cli:test :apps:cli:integrationTest :apps:web:test \
    :apps:runtime:test :apps:runtime:integrationTest \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 4: Run full repository acceptance**

  Run:

  ```bash
  GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
  mise exec -- ./gradlew clean test integrationTest :apps:cli:installDist \
    --no-daemon --non-interactive --rerun-tasks
  git diff --check
  ```

- [ ] **Step 5: Perform security and determinism readback**

  Compare paired fixtures, parse every new Runtime Metadata artifact, scan generated files/reports/log captures for
  synthetic secret values, and re-read both approved specs. Report local, Docker, and remote-CI verification as
  separate evidence.

- [ ] **Step 6: Commit schema-expansion acceptance**

  Commit title: `feat(openapi): complete bounded schema expansion`
