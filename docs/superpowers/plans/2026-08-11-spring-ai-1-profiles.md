# Spring AI 1.1 Java 17·21 Generator Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an isolated Spring AI 1.1.8 emitter that generates, compiles, boots, and MCP-validates deterministic Spring MVC Streamable HTTP projects on Java 17 and Java 21 alongside the existing Spring AI 2 profiles.

**Architecture:** The canonical compatibility registry gains two Spring AI 1 profiles. A new `generator-spring-ai-1` module owns Boot 3.5/Jackson 2 project and source rendering while reusing the version-neutral Tool IR, policy, normalization, validator, and packaging contracts. The CLI composition root registers both emitters, and installed-CLI acceptance exercises all four profiles with a test-local raw MCP client and independent upstream recorder.

**Tech Stack:** Generator Java 21, generated Java 17/21, Spring AI 1.1.8, Spring Boot 3.5.16, MCP Java SDK 0.18.3 through the Spring AI BOM, Gradle Kotlin DSL and wrapper 9.6.1, Spring MVC Sync Streamable HTTP, Jackson 2 generated runtime, JUnit 5.

## Global Constraints

- Keep generator modules and their tests on Java 21; target Java 17/21 applies only to generated compile, generated tests, boot, and MCP validation.
- Expose exactly four default profiles in ascending ID order: `spring-ai-1.1-java17-mvc-streamable`, `spring-ai-1.1-java21-mvc-streamable`, `spring-ai-2.0-java17-mvc-streamable`, `spring-ai-2.0-java21-mvc-streamable`.
- Pin Spring AI 1 profiles to Boot 3.5.16, Spring AI 1.1.8, Gradle 9.6.1, MVC, SYNC, STREAMABLE_HTTP, template `spring-ai-1-v1`, runtime `0.2.0`, and module `generator-spring-ai-1`.
- Keep `CompatibilityProfile.p0()` as the canonical Spring AI 2 Java 21 alias.
- Keep Spring AI 1 and 2 generated code in separate modules; do not add version branches to `generator-spring-ai-2`.
- Spring AI 1 generated code uses Jackson 2 (`com.fasterxml.jackson.*`), does not emit `McpToolParam`, and retains exact explicit Tool schema plus Jakarta Bean Validation.
- Preserve response normalization, provider error mapping, secret safety, bounded runtime execution, fatal `Error` propagation, five-stage validation, exactly one representative `tools/call`, and exactly one upstream request.
- Disable Gradle toolchain auto-detection and auto-download during validation and boot with the verified target `bin/java`.
- Use the existing digest-pinned Java 17/21 images, `USER 10001:10001`, and deterministic `.dockerignore`.
- Add no generator runtime dependency beyond existing repository libraries. Generated project dependencies come only from the pinned Boot and Spring AI BOMs and starters.
- Do not leak absolute JDK paths, usernames, commands, process output, provider URLs, secrets, or stack traces into generated files, manifests, ZIPs, CLI output, or validation reports.
- Use TDD for every task. Commit only the files listed for that task and use `feat:`, `refactor:`, or `docs:` rather than `fix:`.
- Work in the current checkout as previously approved; do not create a worktree and do not push.

---

### Task 1: Register the Two Spring AI 1 Compatibility Profiles

**Files:**
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistry.java`
- Modify: `generator-domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistryTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/GenerationConfigurationReaderTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/CliApplicationTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`
- Create: `generator-cli/src/test/resources/config/weather-generation-spring-ai1-java17.yaml`
- Create: `generator-cli/src/test/resources/config/weather-generation-spring-ai1-java21.yaml`

**Interfaces:**
- Produces two canonical `CompatibilityProfile` values with module `generator-spring-ai-1`.
- Preserves canonical object identity from `CompatibilityProfileRegistry.find` and the legacy `p0()` alias.
- Tasks 2, 5, and 6 consume the new IDs and metadata.

- [ ] **Step 1: Write failing four-profile and configuration tests**

Change the exact default ID expectation to:

```java
assertEquals(List.of(
        "spring-ai-1.1-java17-mvc-streamable",
        "spring-ai-1.1-java21-mvc-streamable",
        "spring-ai-2.0-java17-mvc-streamable",
        "spring-ai-2.0-java21-mvc-streamable"),
        registry.profiles().stream().map(CompatibilityProfile::id).toList());
```

For the two new entries assert Boot `3.5.16`, AI `1.1.8`, Gradle `9.6.1`, module
`generator-spring-ai-1`, template `spring-ai-1-v1`, runtime `0.2.0`, and these exact images:

```text
eclipse-temurin:17.0.19_10-jre-noble@sha256:543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8
eclipse-temurin:21.0.11_10-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64
```

The two YAML fixtures are byte-identical to the current Java 17/21 weather fixtures except for
`targetProfileId`. Assert both parse and unknown IDs still produce a fixed value-free configuration
failure. Update default CLI and installed `profiles` tests to four exact ordered entries and repeated
byte-identical stdout.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
mise exec -- ./gradlew :generator-domain:test :generator-cli:test \
  --tests 'io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistryTest' \
  --tests 'io.gen2spring.mcp.cli.GenerationConfigurationReaderTest' \
  --tests 'io.gen2spring.mcp.cli.CliApplicationTest' \
  --tests 'io.gen2spring.mcp.cli.InstalledCliTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: the exact profile-count/order and Spring AI 1 fixture assertions fail because defaults expose
only Spring AI 2.

- [ ] **Step 3: Add the canonical profiles**

Construct both Spring AI families in `CompatibilityProfileRegistry.Defaults` without changing registry
validation or lookup semantics. Use a helper whose inputs include family, Boot, AI, module, template,
Java feature, and image; do not infer a module from an arbitrary user ID. Keep `p0()` unchanged.

- [ ] **Step 4: Run domain and CLI unit suites**

```bash
mise exec -- ./gradlew :generator-domain:test :generator-cli:test \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 5: Commit Task 1**

```bash
git add generator-domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistry.java \
  generator-domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistryTest.java \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/GenerationConfigurationReaderTest.java \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/CliApplicationTest.java \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java \
  generator-cli/src/test/resources/config/weather-generation-spring-ai1-java17.yaml \
  generator-cli/src/test/resources/config/weather-generation-spring-ai1-java21.yaml
git commit -m "feat(domain): register Spring AI 1 profiles"
```

### Task 2: Scaffold the Isolated Spring AI 1 Project Renderer

**Files:**
- Modify: `settings.gradle.kts`
- Create: `generator-spring-ai-1/build.gradle.kts`
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/JavaIdentifier.java`
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/ProjectFileRenderer.java`
- Create: `generator-spring-ai-1/src/main/resources/wrapper/gradle-wrapper.jar`
- Create: `generator-spring-ai-1/src/main/resources/wrapper/gradle-wrapper.properties`
- Create: `generator-spring-ai-1/src/main/resources/wrapper/gradlew`
- Create: `generator-spring-ai-1/src/main/resources/wrapper/gradlew.bat`
- Create: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/JavaIdentifierTest.java`
- Create: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/ProjectFileRendererTest.java`

**Interfaces:**
- Produces `ProjectFileRenderer(CompatibilityProfile)` and project-file render methods consumed by Task 3 and Task 4.
- Accepts only an exact canonical Spring AI 1 profile from the default registry.
- Emits Boot 3.5/Jackson 2 dependencies and the existing provider/runtime configuration contract.

- [ ] **Step 1: Add the module and failing renderer contract tests**

The module build file must be:

```kotlin
dependencies {
    implementation(project(":generator-domain"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
}
```

Add `generator-spring-ai-1` to `settings.gradle.kts`. Port the package/identifier validator used by the
Spring AI 2 renderer unchanged except for its package, with its complete regression test. Write tests for both Spring AI 1 profiles that
assert exact toolchain, Boot/AI BOMs, `spring-ai-starter-mcp-server-webmvc`,
`spring-boot-starter-web`, validation starter, `/mcp`, protocol `STREAMABLE`, type `SYNC`, disabled
annotation scanner, profile metadata, Docker image, numeric user, `.dockerignore`, and wrapper 9.6.1.
Assert Spring AI 2, noncanonical image, drifted template/runtime, wrong Boot/AI/transport/module all fail
before any file is returned with a fixed safe renderer message.

- [ ] **Step 2: Verify renderer RED**

```bash
mise exec -- ./gradlew :generator-spring-ai-1:test \
  --tests 'io.gen2spring.mcp.springai1.ProjectFileRendererTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `compileTestJava` fails because `ProjectFileRenderer` does not exist.

- [ ] **Step 3: Implement project rendering**

Use the Spring AI 2 project renderer as the version-neutral behavioral reference, but keep a separate
class and apply these exact Spring AI 1 values:

```text
renderer stage: spring-ai-1-render
module: generator-spring-ai-1
Boot: 3.5.16
AI: 1.1.8
template: spring-ai-1-v1
dependency replacing spring-boot-restclient: spring-boot-starter-web
Jackson in generated application: Boot 3 Jackson 2
```

Canonical acceptance requires `CompatibilityProfileRegistry.defaults().find(candidate.id())` to return
an equal record after the family fields match. Render the same deterministic provider timeouts, queue,
secret placeholders, response-policy README, Dockerfile, `.dockerignore`, gitignore, Gradle properties,
settings, and wrapper assets as Spring AI 2.

Copy the four wrapper assets byte-for-byte from `generator-spring-ai-2/src/main/resources/wrapper/`;
do not reference that module at runtime.

- [ ] **Step 4: Run focused and full module tests**

```bash
mise exec -- ./gradlew :generator-spring-ai-1:test \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 5: Commit Task 2**

```bash
git add settings.gradle.kts generator-spring-ai-1
git commit -m "feat(spring-ai): scaffold Spring AI 1 project rendering"
```

### Task 3: Render Spring AI 1 Java Sources and Adapter

**Files:**
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/InputRecordRenderer.java`
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/JavaSourceRenderer.java`
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/JavaStringLiteral.java`
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/OperationMetadataRenderer.java`
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/ResponseRuntimeRenderer.java`
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/RuntimeSourceRenderer.java`
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/ToolCallbackConfigurationRenderer.java`
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/ToolClassRenderer.java`
- Create: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/JavaSourceRendererTest.java`
- Create: `generator-spring-ai-1/src/test/resources/golden/weather/GetForecastInput.java`
- Create: `generator-spring-ai-1/src/test/resources/golden/weather/WeatherMcpTools.java`

**Interfaces:**
- Produces `JavaSourceRenderer(CompatibilityProfile).render(GenerationContext)` for Task 4.
- Produces exact generated MCP Tool schemas while omitting the unavailable `McpToolParam` annotation.
- Generated runtime preserves every existing Spring AI 2 observable contract using Jackson 2 APIs.

- [ ] **Step 1: Write failing source and golden tests**

Use the same Tool IR fixtures as `generator-spring-ai-2` and assert sorted source paths, Java-safe names,
hyphenated/raw JSON properties, enums, arrays, nested objects, body flattening, secrets, response policy,
numeric exponent preservation, exact Tool schema, and runtime feature test. Add explicit assertions that
generated source:

```java
assertFalse(allSources.contains("tools.jackson"));
assertFalse(allSources.contains("McpToolParam"));
assertTrue(allSources.contains("com.fasterxml.jackson.databind.JsonNode"));
assertTrue(allSources.contains("com.fasterxml.jackson.databind.json.JsonMapper"));
assertTrue(allSources.contains("com.fasterxml.jackson.databind.node.TextNode"));
assertTrue(allSources.contains("DefaultToolDefinition.builder()"));
assertTrue(allSources.contains("inputSchema("));
```

The golden Tool method must retain Jakarta validation annotations but no MCP parameter annotation.

- [ ] **Step 2: Verify Java source RED**

```bash
mise exec -- ./gradlew :generator-spring-ai-1:test \
  --tests 'io.gen2spring.mcp.springai1.JavaIdentifierTest' \
  --tests 'io.gen2spring.mcp.springai1.JavaSourceRendererTest' \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 3: Implement the isolated source renderers**

Use the complete Spring AI 2 renderer classes at the Task 3 base commit as the behavioral reference and
apply this exhaustive transformation table inside the new `springai1` package:

| Spring AI 2 generated source | Spring AI 1 generated source |
|---|---|
| `tools.jackson.core.JacksonException` | `com.fasterxml.jackson.core.JsonProcessingException` for serialization, `java.io.IOException` for `readTree(byte[])` |
| `tools.jackson.databind.*` | `com.fasterxml.jackson.databind.*` |
| `tools.jackson.databind.json.JsonMapper` | `com.fasterxml.jackson.databind.json.JsonMapper` |
| `tools.jackson.databind.node.StringNode` | `com.fasterxml.jackson.databind.node.TextNode` |
| `StringNode.valueOf` | `TextNode.valueOf` |
| `@McpToolParam(...)` and its import | omit entirely |
| stage `spring-ai-2-render` | stage `spring-ai-1-render` |

Keep generator-side schema serialization on repository Jackson 2. In `ToolCallbackConfigurationRenderer`,
catch `JsonProcessingException` at argument/result serialization boundaries, preserve provider
`CallToolResult(isError=true)`, rethrow the original fatal `Error`, and convert unexpected failures to the
same fixed safe JSON-RPC error path. In `ResponseRuntimeRenderer`, declare `parseJson` with `IOException`
and keep protocol mismatch separate from I/O/provider classification. Do not weaken duplicate-field,
precision, size, media-type, status-first, empty-response, pointer, or masking behavior.

- [ ] **Step 4: Run source renderer tests and diff guard**

```bash
mise exec -- ./gradlew :generator-spring-ai-1:test \
  --tests 'io.gen2spring.mcp.springai1.JavaIdentifierTest' \
  --tests 'io.gen2spring.mcp.springai1.JavaSourceRendererTest' \
  --no-daemon --non-interactive --rerun-tasks
git diff --check
```

- [ ] **Step 5: Commit Task 3**

```bash
git add generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1 \
  generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/JavaIdentifierTest.java \
  generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/JavaSourceRendererTest.java \
  generator-spring-ai-1/src/test/resources/golden/weather
git commit -m "feat(spring-ai): render Spring AI 1 sources"
```

### Task 4: Generate and Exercise Real Spring AI 1 Projects

**Files:**
- Create: `generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/SpringAi1ProjectGenerator.java`
- Create: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/GeneratedProjectSmokeTest.java`
- Create: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/GeneratedSecretSafetyTest.java`
- Create: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/GeneratedRuntimeRegressionTest.java`
- Create: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/ManagedTestProcess.java`
- Create: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/ManagedTestProcessTest.java`

**Interfaces:**
- Produces public `SpringAi1ProjectGenerator implements ProjectGenerator` for Task 5.
- Produces a deterministic complete file map with project files before sorted Java sources.
- Proves Boot 3.5.16 + AI 1.1.8 on both target JVMs before CLI registration.

- [ ] **Step 1: Write failing full-project and runtime tests**

`SpringAi1ProjectGenerator.generate` must create the exact project-file set emitted by Spring AI 2, with
Spring AI 1 contents. Generated tests must assert `Runtime.version().feature()` equals the target feature.
Add real generated-project tests for:

- Java 17 and Java 21 `compileJava` and `test` with toolchain auto-detect/download disabled;
- Spring application context and exact single `SyncToolSpecification` registration;
- Streamable HTTP initialize, tools/list, successful tools/call, provider error, invalid missing/type/range input;
- high-precision decimal normalization and optional nested null omission;
- malformed content type status priority, empty 2xx policy, 1 MiB response boundary;
- safe type-only diagnostics, secret absence, unexpected RuntimeException JSON-RPC internal, original Error identity;
- bounded test subprocess descendant cleanup.

- [ ] **Step 2: Verify generator RED**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew :generator-spring-ai-1:test \
  --tests 'io.gen2spring.mcp.springai1.GeneratedProjectSmokeTest' \
  --tests 'io.gen2spring.mcp.springai1.GeneratedSecretSafetyTest' \
  --tests 'io.gen2spring.mcp.springai1.GeneratedRuntimeRegressionTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails because `SpringAi1ProjectGenerator` and the generated-project test harness do
not exist.

- [ ] **Step 3: Implement the complete generator and bounded harness**

Use this exact generator shape:

```java
public final class SpringAi1ProjectGenerator implements ProjectGenerator {
    @Override
    public GeneratedProjectFiles generate(GenerationContext context) {
        CompatibilityProfile profile = context == null ? null : context.profile();
        var project = new ProjectFileRenderer(profile);
        var java = new JavaSourceRenderer(profile);
        // Insert the fixed project files, then java.render(context), and return an unmodifiable map.
    }
}
```

The test process helper must observe root and descendant identities, use deadline-based waits, close output
streams, terminate children first graceful then forcible, and assert no survivor. It must not use unbounded
`waitFor()`.

- [ ] **Step 4: Run full Spring AI 1 module on both targets**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew :generator-spring-ai-1:test \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 5: Run Spring AI 2 regression beside it**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew :generator-spring-ai-1:test :generator-spring-ai-2:test \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 6: Commit Task 4**

```bash
git add generator-spring-ai-1/src/main/java/io/gen2spring/mcp/springai1/SpringAi1ProjectGenerator.java \
  generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1
git commit -m "feat(spring-ai): validate Spring AI 1 runtime"
```

### Task 5: Register the Spring AI 1 Emitter in the CLI Pipeline

**Files:**
- Modify: `generator-cli/build.gradle.kts`
- Modify: `generator-cli/src/main/java/io/gen2spring/mcp/cli/ApplicationFactory.java`
- Create: `generator-cli/src/test/java/io/gen2spring/mcp/cli/ApplicationFactoryTest.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/ProjectGeneratorRegistryTest.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`

**Interfaces:**
- Registers both generator module keys against one canonical profile registry.
- Preserves early generator resolution before OpenAPI analysis and source writes.
- Makes installed CLI generation available for both Spring AI 1 profile IDs.

- [ ] **Step 1: Write failing composition and early-resolution tests**

Assert the composition root `profiles` output lists four profiles and that a minimal valid Spring AI 1
generation reaches `SpringAi1ProjectGenerator` rather than `TARGET_COMBINATION_UNSUPPORTED`. In core tests,
register two tracking generators and prove each family resolves its exact module while an omitted Spring AI 1
entry fails before analyzer invocation. Assert canonical profile object identity reaches generation and
validation for `spring-ai-1.1-java17-mvc-streamable`.

- [ ] **Step 2: Verify composition RED**

```bash
mise exec -- ./gradlew :generator-core:test :generator-cli:test \
  --tests 'io.gen2spring.mcp.core.ProjectGeneratorRegistryTest' \
  --tests 'io.gen2spring.mcp.core.GenerationPipelineTest' \
  --tests 'io.gen2spring.mcp.cli.ApplicationFactoryTest' \
  --tests 'io.gen2spring.mcp.cli.InstalledCliTest' \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 3: Register the emitter once**

Add `implementation(project(":generator-spring-ai-1"))` to CLI. Construct the generator map exactly as:

```java
ProjectGeneratorRegistry.of(Map.of(
        "generator-spring-ai-1", new SpringAi1ProjectGenerator(),
        "generator-spring-ai-2", new SpringAi2ProjectGenerator()))
```

Do not construct a second compatibility registry and do not add profile-ID switches in CLI or pipeline.

- [ ] **Step 4: Run affected tests**

```bash
mise exec -- ./gradlew :generator-domain:test :generator-core:test \
  :generator-spring-ai-1:test :generator-spring-ai-2:test :generator-cli:test \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 5: Commit Task 5**

```bash
git add generator-cli/build.gradle.kts \
  generator-cli/src/main/java/io/gen2spring/mcp/cli/ApplicationFactory.java \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/ApplicationFactoryTest.java \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java \
  generator-core/src/test/java/io/gen2spring/mcp/core/ProjectGeneratorRegistryTest.java \
  generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java
git commit -m "feat(cli): register Spring AI 1 generator"
```

### Task 6: Expand Installed CLI Acceptance to Four Profiles

**Files:**
- Create: `generator-cli/src/integrationTest/resources/config/weather-generation-spring-ai1-java17.yaml`
- Create: `generator-cli/src/integrationTest/resources/config/weather-generation-spring-ai1-java21.yaml`
- Modify: `generator-cli/src/integrationTest/java/io/gen2spring/mcp/cli/P1GenerationIntegrationTest.java`
- Modify: `generator-cli/build.gradle.kts`

**Interfaces:**
- Produces release evidence for all four canonical profiles using the installed CLI.
- Reuses the test-local raw JSON-RPC client, independent JDK `HttpServer` recorder, and observed process-tree cleanup.
- Does not call production `McpStreamableHttpClient`, `MockUpstreamServer`, or expectation factories.

- [ ] **Step 1: Add two failing Spring AI 1 matrix rows**

The new fixtures differ from the existing Spring AI 2 Java 17/21 fixtures only in profile ID. Extend the
profile case value with exact module, template, Boot, AI, Java feature, image, and resource. Assert all four
IDs in order and each profile twice. For each first artifact boot the JAR with the selected target `bin/java`
and run the independent literal MCP/upstream contract.

Before implementation, intentionally omit `clientVersion` from the Spring AI 1 literal expected schema and
capture `MCP tool input schema does not match` or the raw oracle's fixed schema-mismatch assertion. Restore
the exact schema for GREEN.

- [ ] **Step 2: Keep test-only target JDK propagation bounded**

Forward `GEN2SPRING_JAVA_17_HOME` and, when explicitly configured, `GEN2SPRING_JAVA_21_HOME` to the installed
CLI subprocess. The production CLI must not invoke `mise`. CLI, generated build, and application waits all
use `ObservedProcess` with bounded descendant cleanup.

- [ ] **Step 3: Run the four-profile matrix**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew :generator-cli:integrationTest \
  --tests 'io.gen2spring.mcp.cli.P1GenerationIntegrationTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected GREEN evidence for every profile: `VALIDATED`, five successful stages, exact output set, exact
manifest and runtime feature, exact Tool schema/result, one exact provider request, late duplicate absence,
determinism, distinct cross-profile checksum, and no leak markers.

- [ ] **Step 4: Run both emitter modules and CLI integration**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew :generator-spring-ai-1:test :generator-spring-ai-2:test \
  :generator-cli:test :generator-cli:integrationTest \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 5: Commit Task 6**

```bash
git add generator-cli/build.gradle.kts \
  generator-cli/src/integrationTest/java/io/gen2spring/mcp/cli/P1GenerationIntegrationTest.java \
  generator-cli/src/integrationTest/resources/config/weather-generation-spring-ai1-java17.yaml \
  generator-cli/src/integrationTest/resources/config/weather-generation-spring-ai1-java21.yaml
git commit -m "feat(cli): validate Spring AI profile matrix"
```

### Task 7: Synchronize Documentation and Run Full Acceptance

**Files:**
- Modify: `README.md`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`
- Modify: `generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/ProjectFileRendererTest.java`

**Interfaces:**
- Documents Spring AI 1.1 support without claiming metrics, Windows, or UI/API complete.
- Produces final acceptance evidence for the next P1 metrics/tracing slice.

- [ ] **Step 1: Write failing documentation contracts**

Assert root README and generated Spring AI 1 README contain all four IDs, Java 21 generator versus target
JDK distinction, Boot 3.5.16/AI 1.1.8/Gradle 9.6.1, Jackson 2 and no `McpToolParam`, Streamable HTTP `/mcp`,
verified target toolchain properties, both image digests, non-root Docker, `.dockerignore`, Java 21 default,
and deterministic `profiles`. Assert Spring AI 1 is no longer listed as unfinished while metrics/tracing,
Windows validation host, and UI operation editor remain explicit follow-up P1.

- [ ] **Step 2: Verify docs RED and synchronize README**

```bash
mise exec -- ./gradlew :generator-cli:test :generator-spring-ai-1:test :generator-spring-ai-2:test \
  --tests 'io.gen2spring.mcp.cli.InstalledCliTest' \
  --tests 'io.gen2spring.mcp.springai1.ProjectFileRendererTest' \
  --tests 'io.gen2spring.mcp.springai2.ProjectFileRendererTest' \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 3: Run all affected modules**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew :generator-domain:test :generator-openapi:test :generator-policy:test \
  :generator-core:test :generator-spring-ai-1:test :generator-spring-ai-2:test \
  :generator-validation:test :generator-cli:test :generator-cli:integrationTest \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 4: Run exact repository acceptance**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew clean test integrationTest :generator-cli:installDist \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 5: Perform deterministic and security readback**

```bash
git diff --check
rg -n '/Users/|/home/|mcp-validation-secret|configured-invalid-representative-value|Exception:|at io\.gen2spring' \
  generator-*/build/test-results generator-*/build/reports || true
git status --short
```

Enumerate JUnit tests/failures/errors/skips. Inspect every match; the only allowed stack traces are existing
filesystem-assumption skips. Confirm generated files, manifest, ZIP, validation report, stdout, and stderr
contain no real JDK path, username, secret, command, provider URL, process output, or application stack.

- [ ] **Step 6: Request scoped and whole-slice review**

Use `code-review` on the complete slice. Critical and Important findings block completion. Add a RED
regression before every accepted change. Record but do not silently discard Minor test-harness findings.
Reject changes that expand into metrics/tracing, Windows, or UI/API.

- [ ] **Step 7: Commit Task 7**

```bash
git add README.md \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java \
  generator-spring-ai-1/src/test/java/io/gen2spring/mcp/springai1/ProjectFileRendererTest.java
git commit -m "docs: document Spring AI 1 profiles"
```

## Completion Gate

- [ ] The default registry and installed `profiles` command expose exactly four canonical profiles in deterministic order.
- [ ] Spring AI 1 generated source contains Jackson 2 imports, no `McpToolParam`, and exact explicit Tool schemas.
- [ ] Both Spring AI 1 targets compile, test, boot, initialize MCP, list exact tools, call one representative Tool, and verify exactly one upstream request on the selected JDK.
- [ ] Spring AI 2 Java 17/21 behavior remains green and byte-deterministic.
- [ ] Unknown profile, missing emitter, noncanonical renderer profile, absent/wrong JDK, schema/result mismatch, and duplicate upstream request fail closed.
- [ ] All four profiles render correct manifest, README, Dockerfile, `.dockerignore`, wrapper, and target runtime metadata.
- [ ] Exact full repository acceptance succeeds with zero failures/errors and no Critical/Important review finding.
- [ ] Metrics/tracing, Windows validation host, and Generator API/UI remain documented as unfinished P1 slices.

## Complexity and Operational Notes

- Registry construction remains `O(P log P)` time and `O(P)` space for `P=4`; lookup remains average `O(1)`.
- One generation remains `O(F + T log T)` for generated files `F` and Tools `T`.
- Acceptance is `O(P)` and dominated by four generated Gradle builds and boot/MCP journeys.
- The Spring AI 1 emitter intentionally duplicates version-specific renderers. Extract a shared module only after both emitters are stable and a measured maintenance pattern justifies the migration risk.
- Boot 3.5.16 is one patch newer than the Spring AI 1.1.8 release baseline of Boot 3.5.15; compile spike passed, but context and live MCP matrix are mandatory release evidence.
