# Local Operation Editor and Generator API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. This execution is explicitly inline; do not dispatch subagents. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a secure loopback-only Web operation editor that reuses the canonical generator pipeline from specification upload through validated artifact download.

**Architecture:** A new `generator-application` module owns shared composition and strict configuration parsing for CLI and Web. `generator-core` adds preview planning and bounded progress events. A new `generator-web` module serves framework-free assets and a loopback JDK `HttpServer` API with opaque workspaces, a single bounded generation worker, and exact artifact downloads.

**Tech Stack:** Java 21 generator, JDK `HttpServer` and `HttpClient`, Jackson 2.22.0, SnakeYAML through Jackson YAML, framework-free HTML/CSS/ES modules, Gradle 9.6.1, JUnit 5.13.4, existing Java 17/21 target toolchains.

## Global Constraints

- Work inline only. Do not dispatch subagents or external review agents.
- Keep CLI commands, stdout/stderr JSON, exit codes, path boundaries, and four-profile generation behavior backward compatible.
- Bind the Web server only to numeric `127.0.0.1`; do not add URL import or remote listen configuration.
- Keep raw specifications at 10 MiB maximum and configuration JSON/YAML at 1 MiB maximum.
- Use the same strict configuration semantics, canonical profile identity, Tool IR, emitters, validator, and packager for CLI and Web.
- Permit exactly one running generation and one queued generation; reject additional work with fixed `429` output.
- Do not return or log uploaded bytes, filesystem paths, validation arguments, tokens, secret values, process output, Throwable messages, or stacks.
- Do not add Node.js, frontend package managers, external CDN assets, analytics, fonts, service workers, Spring Boot control-plane dependencies, or dynamic versions.
- Use RED before production changes, fresh GREEN after each task, `git diff --check`, and one meaningful `feat:`, `refactor:`, or `docs:` commit per task. Do not push.
- Preserve the existing three macOS case-insensitive-filesystem assumption skips; no new skip is allowed.

---

### Task 1: Share Application Composition and Strict Configuration Parsing

**Files:**

- Modify: `settings.gradle.kts`
- Create: `generator-application/build.gradle.kts`
- Create: `generator-application/src/main/java/io/gen2spring/mcp/application/GenerationConfigurationException.java`
- Create: `generator-application/src/main/java/io/gen2spring/mcp/application/GenerationConfigurationParser.java`
- Create: `generator-application/src/main/java/io/gen2spring/mcp/application/GeneratorApplication.java`
- Create: `generator-application/src/test/java/io/gen2spring/mcp/application/GenerationConfigurationParserTest.java`
- Create: `generator-application/src/test/java/io/gen2spring/mcp/application/GeneratorApplicationTest.java`
- Modify: `generator-cli/build.gradle.kts`
- Modify: `generator-cli/src/main/java/io/gen2spring/mcp/cli/GenerationConfigurationReader.java`
- Modify: `generator-cli/src/main/java/io/gen2spring/mcp/cli/ApplicationFactory.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/GenerationConfigurationReaderTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/ApplicationFactoryTest.java`

**Interfaces:**

- Produces: `GenerationConfigurationParser.parseYaml(byte[])` and `parseJson(byte[])` returning `GenerationRequest`.
- Produces: `GeneratorApplication.defaults()` returning the one canonical profiles/analyzer/pipeline/parser graph.
- Preserves: `GenerationConfigurationReader.read(Path)` and `ApplicationFactory.create()`.

- [ ] **Step 1: Write parser and composition RED tests**

Create tests that prove YAML and JSON forms produce equal `GenerationRequest`, reject duplicate/unknown/non-finite/oversized input with one fixed exception, expose exactly four canonical profile objects, and resolve exactly the two generator modules.

```java
GenerationRequest yaml = parser.parseYaml(validYaml());
GenerationRequest json = parser.parseJson(validJson());
assertEquals(yaml, json);
assertThrows(GenerationConfigurationException.class,
        () -> parser.parseJson("{\"project\":{},\"project\":{}}".getBytes(UTF_8)));

GeneratorApplication application = GeneratorApplication.defaults();
assertEquals(List.of(
        "spring-ai-1.1-java17-mvc-streamable",
        "spring-ai-1.1-java21-mvc-streamable",
        "spring-ai-2.0-java17-mvc-streamable",
        "spring-ai-2.0-java21-mvc-streamable"),
        application.profiles().profiles().stream().map(CompatibilityProfile::id).toList());
```

- [ ] **Step 2: Run RED**

```bash
mise exec -- ./gradlew :generator-application:compileTestJava :generator-cli:compileTestJava \
  --no-daemon --non-interactive
```

Expected: compilation fails because the new module, parser, and shared application types are absent.

- [ ] **Step 3: Extract the strict parser**

Implement this public boundary and move the existing YAML event, token type, field allow-list, regex, response-policy, argument-depth/member/item/string, and profile validation into it without weakening any limit.

```java
public final class GenerationConfigurationParser {
    public static final int MAX_BYTES = 1024 * 1024;

    public GenerationConfigurationParser(CompatibilityProfileRegistry profiles) { ... }

    public GenerationRequest parseYaml(byte[] bytes) {
        return parse(bytes, InputFormat.YAML);
    }

    public GenerationRequest parseJson(byte[] bytes) {
        return parse(bytes, InputFormat.JSON);
    }

    private enum InputFormat { YAML, JSON }
}
```

`GenerationConfigurationException` always uses `Generation configuration is invalid` as its public message. Preserve precise causes only inside the process.

- [ ] **Step 4: Make the CLI reader a filesystem adapter**

Keep `LocalPathBoundary.regularFile(...).readBounded(...)` in CLI, then delegate bytes to `parseYaml`. Map `GenerationConfigurationException` to the existing `CliConfigurationException` without changing the CLI-safe message.

```java
public GenerationRequest read(Path configuration) {
    try {
        return parser.parseYaml(readBoundedRegularFile(configuration));
    } catch (GenerationConfigurationException exception) {
        throw new CliConfigurationException(exception.getMessage(), exception);
    }
}
```

- [ ] **Step 5: Add the shared composition root**

```java
public record GeneratorApplication(
        CompatibilityProfileRegistry profiles,
        SpecificationAnalyzer analyzer,
        GenerationConfigurationParser configurationParser,
        GenerationPipeline pipeline) {
    public static GeneratorApplication defaults() { ... }
}
```

Construct `CompatibilityProfileRegistry.defaults()` once, register only `generator-spring-ai-1` and `generator-spring-ai-2`, and pass the same registry to parser and pipeline. Refactor CLI `ApplicationFactory` to consume this record.

- [ ] **Step 6: Run focused and regression GREEN**

```bash
mise exec -- ./gradlew \
  :generator-application:test \
  :generator-cli:test \
  :generator-core:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: all tests pass; installed CLI profile/config/generate behavior remains exact.

- [ ] **Step 7: Review and commit**

```bash
git diff --check
git add settings.gradle.kts generator-application generator-cli
git commit -m "refactor(app): share generator application services"
```

---

### Task 2: Add Canonical Planning and Preview

**Files:**

- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationPlanner.java`
- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationPreview.java`
- Create: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPlannerTest.java`
- Create: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPreviewTest.java`
- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationPipeline.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java`
- Modify: `generator-application/src/main/java/io/gen2spring/mcp/application/GeneratorApplication.java`
- Modify: `generator-application/src/test/java/io/gen2spring/mcp/application/GeneratorApplicationTest.java`

**Interfaces:**

- Produces: `GenerationPlanner.plan(OpenApiDocument, GenerationRequest)` and immutable `PlannedGeneration`.
- Produces: `GenerationPipeline.preview(Path, GenerationRequest)` returning `GenerationPreview`.
- Preserves: existing `GenerationPipeline.generate(Path, GenerationRequest, Path)`.

- [ ] **Step 1: Write planner identity and preview RED tests**

Assert that preview and generation receive the same canonical profile instance, exact Tool list, exact expected schema, and selected emitter. Assert preview renders source in memory, returns sorted paths, and creates no output directory or ZIP. Assert validation argument values are absent from the preview DTO/string.

```java
GenerationPreview preview = pipeline.preview(specification, request);
assertSame(registry.find(request.targetProfileId()).orElseThrow(), preview.profile());
assertEquals(List.of("weather_get_forecast"),
        preview.tools().stream().map(GenerationPreview.Tool::name).toList());
assertFalse(Files.exists(outputRoot));
assertFalse(preview.toString().contains("representative-private-value"));
```

- [ ] **Step 2: Run RED**

```bash
mise exec -- ./gradlew :generator-core:test \
  --tests 'io.gen2spring.mcp.core.GenerationPlannerTest' \
  --tests 'io.gen2spring.mcp.core.GenerationPreviewTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails because planner/preview APIs are absent.

- [ ] **Step 3: Implement `GenerationPlanner`**

```java
public final class GenerationPlanner {
    public PlannedGeneration plan(OpenApiDocument document, GenerationRequest request) { ... }

    public record PlannedGeneration(
            CompatibilityProfile profile,
            ProjectGenerator projectGenerator,
            List<McpToolDefinition> tools,
            Map<String, ExpectedTool> expectedTools,
            ExpectedToolCall expectedToolCall) {}
}
```

Move profile resolution, generator resolution, target validation, ToolModelFactory, ExpectedToolSchemaFactory, and ExpectedToolCallFactory calls from `GenerationPipeline` into the planner. Preserve current fixed code/stage/safe messages and canonical object identity.

- [ ] **Step 4: Implement immutable preview**

`GenerationPreview` contains profile metadata, sorted Tool records with deep-immutable schemas, sorted secret environment variable names, analyzer warnings, normalized response policy summaries, and sorted expected file paths. Never include validation argument values or secret values.

`GenerationPipeline.preview` analyzes and plans, renders the selected emitter into memory, adds the original specification path plus manifest/report/archive names to the sorted preview list, and performs no filesystem mutation.

- [ ] **Step 5: Refactor generation to consume one plan**

Replace duplicated profile/Tool/schema/call resolution in `generate` with `planner.plan(...)`. Keep generated bytes, checksum, report, ZIP, error, and source-write order unchanged.

- [ ] **Step 6: Run GREEN and deterministic regression**

```bash
mise exec -- ./gradlew :generator-core:test :generator-application:test :generator-cli:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: all tests pass and existing determinism/source checksum tests remain green.

- [ ] **Step 7: Review and commit**

```bash
git diff --check
git add generator-core generator-application
git commit -m "feat(core): add canonical generation preview"
```

---

### Task 3: Publish Bounded Generation Progress

**Files:**

- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/generation/GenerationContracts.java`
- Modify: `generator-domain/src/test/java/io/gen2spring/mcp/domain/generation/GenerationContractsTest.java`
- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationPipeline.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java`
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/GradleMcpProjectValidator.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GradleMcpProjectValidatorTest.java`

**Interfaces:**

- Produces: `GenerationProgressListener`, `GenerationProgress`, `ProgressStatus`.
- Produces: `GenerationPipeline.generate(..., GenerationProgressListener)`.
- Produces: backward-compatible `GeneratedProjectValidator.validate(request, listener)` default overload.

- [ ] **Step 1: Write ordered progress RED tests**

Use a recording listener to assert exact successful sequence and a compile failure sequence whose remaining stages become `SKIPPED`. Add listener tests for validation timeout, interrupt flag, cleanup, late callback ignore at the eventual job boundary, and absence of summary/path/argument fields.

```java
assertEquals(List.of(
        event("ANALYZE", RUNNING), event("ANALYZE", SUCCESS),
        event("GENERATE", RUNNING), event("GENERATE", SUCCESS),
        event("COMPILE", RUNNING), event("COMPILE", SUCCESS),
        event("APPLICATION_CONTEXT", RUNNING), event("APPLICATION_CONTEXT", SUCCESS),
        event("MCP_INITIALIZE", RUNNING), event("MCP_INITIALIZE", SUCCESS),
        event("MCP_TOOLS_LIST", RUNNING), event("MCP_TOOLS_LIST", SUCCESS),
        event("MCP_TOOL_CALL", RUNNING), event("MCP_TOOL_CALL", SUCCESS),
        event("PACKAGE", RUNNING), event("PACKAGE", SUCCESS)), events);
```

- [ ] **Step 2: Run RED**

```bash
mise exec -- ./gradlew :generator-domain:compileTestJava :generator-core:compileTestJava \
  :generator-validation:compileTestJava --no-daemon --non-interactive
```

Expected: compilation fails because progress types and overloads are absent.

- [ ] **Step 3: Add finite progress types**

```java
public enum ProgressStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

public record GenerationProgress(String stage, ProgressStatus status) {
    public GenerationProgress {
        if (!STAGES.contains(stage) || status == null) {
            throw new IllegalArgumentException("Generation progress is invalid");
        }
    }
}

@FunctionalInterface
public interface GenerationProgressListener {
    GenerationProgressListener NOOP = progress -> {};
    void onProgress(GenerationProgress progress);
}
```

The exact stage allow-list is `ANALYZE`, `GENERATE`, `COMPILE`, `APPLICATION_CONTEXT`, `MCP_INITIALIZE`, `MCP_TOOLS_LIST`, `MCP_TOOL_CALL`, `PACKAGE`.

- [ ] **Step 4: Instrument pipeline and validator**

The existing three-argument pipeline method delegates to the new overload with `NOOP`. Emit `RUNNING` before each bounded stage and one terminal status after it. `GradleMcpProjectValidator` emits the existing validation stage names immediately before execution and from each final `ValidationStageResult`.

On failure, emit `FAILED` for the active stage and `SKIPPED` once for all later stages. Never include duration, summary, output, argument, path, or Throwable in the event.

- [ ] **Step 5: Run GREEN**

```bash
mise exec -- ./gradlew \
  :generator-domain:test \
  :generator-core:test \
  :generator-validation:test \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 6: Review and commit**

```bash
git diff --check
git add generator-domain generator-core generator-validation
git commit -m "feat(core): publish generation progress"
```

---

### Task 4: Serve the Secure Local Shell, Profiles, Upload, and Preview APIs

**Files:**

- Modify: `settings.gradle.kts`
- Create: `generator-web/build.gradle.kts`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/Main.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/WebArguments.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/WebApplicationFactory.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/LocalWebServer.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/RequestGuard.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/BoundedBodyReader.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/JsonHttp.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/WebErrorMapper.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/SpecificationStore.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/StaticAssetHandler.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/PreviewHandler.java`
- Create: `generator-web/src/main/resources/web/index.html`
- Create: `generator-web/src/main/resources/web/styles.css`
- Create: `generator-web/src/main/resources/web/app.js`
- Create: `generator-web/src/test/java/io/gen2spring/mcp/web/RequestGuardTest.java`
- Create: `generator-web/src/test/java/io/gen2spring/mcp/web/BoundedBodyReaderTest.java`
- Create: `generator-web/src/test/java/io/gen2spring/mcp/web/SpecificationStoreTest.java`
- Create: `generator-web/src/test/java/io/gen2spring/mcp/web/LocalWebServerTest.java`

**Interfaces:**

- Produces: distributable `gen2spring-mcp-web` local application.
- Produces: `GET /api/profiles`, `POST /api/specifications`, `POST /api/specifications/{id}/preview`.
- Consumes: Task 1 `GeneratorApplication`; Task 2 `GenerationPipeline.preview`.

- [ ] **Step 1: Write transport/security RED tests**

Start the server on port 0 and assert numeric loopback binding, one bounded startup JSON line, token injection, exact CSP/security headers, four profiles, upload/analysis, preview, and fixed errors. Use a raw `Socket` request for wrong `Host`; use JDK HttpClient for same-origin/token tests.

```java
assertEquals("127.0.0.1", server.address().getAddress().getHostAddress());
assertEquals("no-store", root.headers().firstValue("Cache-Control").orElseThrow());
assertEquals("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "
        + "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        root.headers().firstValue("Content-Security-Policy").orElseThrow());
assertEquals(403, postWithoutToken.statusCode());
assertEquals(413, oversizedSpecification.statusCode());
assertFalse(error.body().contains("private-marker"));
```

- [ ] **Step 2: Run RED**

```bash
mise exec -- ./gradlew :generator-web:compileTestJava --no-daemon --non-interactive
```

Expected: compilation fails because `generator-web` and server types are absent.

- [ ] **Step 3: Implement bounded startup and request guards**

`WebArguments` permits only `--port <0..65535>` and defaults to 0. `LocalWebServer` binds `new InetSocketAddress("127.0.0.1", port)`. Generate a 32-byte SecureRandom token and require:

```text
Host: 127.0.0.1:<actual-port>
X-Gen2Spring-Token: <exact-process-token>
Origin: http://127.0.0.1:<actual-port>   # POST/DELETE only
```

Reject non-loopback remote addresses before reading a body. Apply the design's CSP, `nosniff`, `DENY`, `no-referrer`, and `no-store` headers.

- [ ] **Step 4: Implement bounded specification storage**

Create one private temporary root at startup. Validate `X-Specification-Name` as a basename with the three allowed suffixes. Stream at most 10 MiB plus one byte, write through a private temporary file, pin the regular non-symlink path, analyze, and publish an immutable record under a 256-bit opaque ID. Delete partial files on every failure.

- [ ] **Step 5: Implement profile/upload/preview routes**

Use allow-list route matching; never resolve a URI path to a filesystem path. Serialize explicit response DTOs rather than arbitrary domain records. Parse preview body with `configurationParser.parseJson`, call `pipeline.preview`, and omit representative argument values from the response.

- [ ] **Step 6: Run GREEN and leak scans**

```bash
mise exec -- ./gradlew :generator-web:test :generator-application:test :generator-cli:test \
  --no-daemon --non-interactive --rerun-tasks
rg -n "private-marker|X-Gen2Spring-Token|/Users/|Authorization:|Bearer " \
  generator-web/build/test-results --glob '*.xml'
```

Expected: tests pass; the scan has no response/log leak match. Test fixture constants may appear only in source.

- [ ] **Step 7: Review and commit**

```bash
git diff --check
git add settings.gradle.kts generator-web
git commit -m "feat(web): serve secure operation previews"
```

---

### Task 5: Run Bounded Generation Jobs and Download Exact Artifacts

**Files:**

- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/GenerationExecutor.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/GenerationJobManager.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/JobSnapshot.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/JobHandler.java`
- Create: `generator-web/src/main/java/io/gen2spring/mcp/web/ArtifactHandler.java`
- Create: `generator-web/src/test/java/io/gen2spring/mcp/web/GenerationJobManagerTest.java`
- Create: `generator-web/src/test/java/io/gen2spring/mcp/web/JobHandlerTest.java`
- Create: `generator-web/src/test/java/io/gen2spring/mcp/web/ArtifactHandlerTest.java`
- Modify: `generator-web/src/main/java/io/gen2spring/mcp/web/LocalWebServer.java`
- Modify: `generator-web/src/main/java/io/gen2spring/mcp/web/WebApplicationFactory.java`
- Modify: `generator-web/src/test/java/io/gen2spring/mcp/web/LocalWebServerTest.java`

**Interfaces:**

- Produces: `POST /api/specifications/{id}/jobs`, `GET /api/jobs/{id}`, three artifact GETs, and DELETE.
- Produces: `GenerationJobManager` with one running/one queued job, atomic terminal seal, TTL cleanup.
- Consumes: Task 3 progress overload.

- [ ] **Step 1: Write job lifecycle RED tests**

Cover success, UNVERIFIED, user failure, runtime failure, interrupt, fatal identity, duplicate/late progress, one active + one queued, third rejection, TTL, retained bound, explicit delete, close cleanup, and unavailable artifact.

```java
JobSnapshot first = jobs.submit(specification, request);
JobSnapshot second = jobs.submit(specification, request);
assertEquals(QUEUED, second.state());
assertThrows(GenerationCapacityException.class,
        () -> jobs.submit(specification, request));

releaseFirst.countDown();
assertEquals(List.of("ANALYZE", "GENERATE", "COMPILE", "APPLICATION_CONTEXT",
        "MCP_INITIALIZE", "MCP_TOOLS_LIST", "MCP_TOOL_CALL", "PACKAGE"),
        jobs.await(first.id()).stages().stream().map(JobStage::stage).toList());
```

- [ ] **Step 2: Run RED**

```bash
mise exec -- ./gradlew :generator-web:test \
  --tests 'io.gen2spring.mcp.web.GenerationJobManagerTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails because job types are absent.

- [ ] **Step 3: Implement the bounded manager**

Use `ThreadPoolExecutor(1, 1, 0, MILLISECONDS, new ArrayBlockingQueue<>(1), ...)` and a single daemon scheduled cleanup thread. Prepopulate eight `PENDING` stages. Progress callbacks apply synchronized legal transitions; terminal seal is one atomic operation and marks remaining stages `SKIPPED`.

Store only opaque ID, pinned specification/output paths, immutable request, stage/status values, safe error, validation status, and exact known artifact paths. Do not retain serialized request bodies or response logs.

- [ ] **Step 4: Preserve interrupt/fatal/cleanup semantics**

On `InterruptedException`, restore the flag after bounded workspace cleanup. On `Error`, seal a fixed failed snapshot, perform cleanup without self-suppression, then rethrow the identical instance. Ignore progress after terminal seal. `close()` stops accepting jobs, interrupts queued work, waits a finite grace period, force-closes the executor, and deletes only owned private workspaces.

- [ ] **Step 5: Implement job and artifact routes**

Parse job configuration through the shared JSON parser. Return `429` with `GENERATION_CAPACITY_EXCEEDED` for the third unfinished job. Status DTO includes only opaque ID, state, current stage, stage/status pairs, validation status, safe error, and available download names.

Artifact handlers open only stored regular non-symlink paths. ZIP is available only for VALIDATED. Manifest/report are available when present. Set a fixed sanitized `Content-Disposition` derived from the already validated artifact ID.

- [ ] **Step 6: Run GREEN and full Web regression**

```bash
mise exec -- ./gradlew :generator-web:test :generator-core:test :generator-validation:test \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 7: Review and commit**

```bash
git diff --check
git add generator-web
git commit -m "feat(web): run bounded generation jobs"
```

---

### Task 6: Build the Five-Step Operation Editor

**Files:**

- Modify: `generator-web/src/main/resources/web/index.html`
- Modify: `generator-web/src/main/resources/web/styles.css`
- Create: `generator-web/src/main/resources/web/api.js`
- Create: `generator-web/src/main/resources/web/state.js`
- Create: `generator-web/src/main/resources/web/editor.js`
- Modify: `generator-web/src/main/resources/web/app.js`
- Create: `generator-web/src/test/java/io/gen2spring/mcp/web/StaticAssetContractTest.java`
- Create: `generator-web/src/test/resources/browser/weather.yaml`

**Interfaces:**

- Consumes: Tasks 4/5 API exactly; makes no external request.
- Produces: accessible 5-step editor at `/` for desktop and 400px viewport.

- [ ] **Step 1: Write static UI contract RED tests**

Assert exact step headings, visible labels, `aria-live`, error summary target, file input accept list, operation filters, target/profile fields, preview and generation controls, no URL input, no inline event/style/script, no external URL, no service worker/localStorage use, and only opaque IDs in `sessionStorage`.

```java
assertTrue(index.contains("<h2>1. Specification</h2>"));
assertTrue(index.contains("aria-live=\"polite\""));
assertTrue(index.contains("accept=\".yaml,.yml,.json\""));
assertFalse(index.contains("type=\"url\""));
assertFalse(allAssets.contains("localStorage"));
assertFalse(allAssets.matches("(?s).*https?://.*"));
```

- [ ] **Step 2: Run RED**

```bash
mise exec -- ./gradlew :generator-web:test \
  --tests 'io.gen2spring.mcp.web.StaticAssetContractTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: tests fail because the complete wizard assets are absent.

- [ ] **Step 3: Implement HTML and CSS**

Build semantic nav/main/section/form markup for the five approved steps. Use one operation list and editor split above 720px and a single column at 400px. Add visible focus, status text plus color, minimum 44px primary controls, `overflow-wrap:anywhere`, and no page-level horizontal overflow.

- [ ] **Step 4: Implement bounded client state**

`state.js` owns one immutable-ish state object: current step, opaque specification/job IDs, analysis metadata, editable operation DTOs, selected profile/project fields, preview metadata, and progress. Raw uploaded bytes remain in the File object only until upload completes. Store only opaque IDs in `sessionStorage`.

- [ ] **Step 5: Implement API and editor modules**

`api.js` reads the token meta value and adds exact token/JSON headers. It exposes `profiles`, `upload`, `preview`, `startJob`, `job`, `downloadUrl`, and `deleteJob`. `editor.js` performs local required-field checks, converts typed success values and validation arguments to strict JSON, and renders operation filters/edit fields without using `innerHTML` for untrusted strings.

`app.js` binds listeners with `addEventListener`, disables generation until server preview succeeds, polls from 500ms to 2s, stops at terminal state, moves errors to the summary focus target, and renders only server-safe error fields.

- [ ] **Step 6: Run asset/server GREEN**

```bash
mise exec -- ./gradlew :generator-web:test --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 7: Run real browser smoke**

Start the installed Web application on port 0, open the printed numeric-loopback URL, upload `weather.yaml`, edit one operation, preview, and verify the job screen at desktop and 400px viewport. Confirm:

```text
browser console errors = 0
external/non-loopback requests = 0
400px horizontal overflow = 0
keyboard journey reaches Preview and Generate
```

- [ ] **Step 8: Review and commit**

```bash
git diff --check
git add generator-web/src/main/resources generator-web/src/test
git commit -m "feat(web): build the operation editor workflow"
```

---

### Task 7: Prove the Real Web Journey and Synchronize Documentation

**Files:**

- Modify: `generator-web/build.gradle.kts`
- Create: `generator-web/src/integrationTest/java/io/gen2spring/mcp/web/LocalOperationEditorIntegrationTest.java`
- Create: `generator-web/src/integrationTest/resources/openapi/weather.yaml`
- Create: `generator-web/src/integrationTest/resources/config/weather.json`
- Modify: `README.md`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`
- Create: `generator-web/src/test/java/io/gen2spring/mcp/web/ReadmeContractTest.java`

**Interfaces:**

- Produces: installed `gen2spring-mcp-web` start script and complete local browser journey.
- Preserves: installed `openapi-mcp` CLI and four-profile acceptance.

- [ ] **Step 1: Write real journey and README RED tests**

The integration test starts the installed Web distribution with explicit Java 17/21 homes, reads the bounded startup JSON, obtains the token, uploads the fixture, previews, starts one real generation, polls ordered stages, and downloads manifest/report/ZIP. Verify exact profile/runtime/template, `VALIDATED`, source checksum, ZIP entry contract, no raw argument/secret/path, and server process-tree cleanup.

README tests require:

```text
./gradlew :generator-web:installDist
generator-web/build/install/gen2spring-mcp-web/bin/gen2spring-mcp-web --port 0
numeric loopback only
local files only; no URL import
one running plus one queued job
UI operation editor complete
Windows validation host remains follow-up P1
```

- [ ] **Step 2: Run RED**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew :generator-web:integrationTest \
  --tests 'io.gen2spring.mcp.web.LocalOperationEditorIntegrationTest' \
  :generator-cli:test --tests 'io.gen2spring.mcp.cli.InstalledCliTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: integration resources/wiring and README contract fail before documentation is synchronized.

- [ ] **Step 3: Wire the integration suite and distribution**

Use Gradle `application` with main class `io.gen2spring.mcp.web.Main` and application name `gen2spring-mcp-web`. Register `integrationTest` as a JvmTestSuite, depend on `installDist`, forward only the two test JDK home variables, use a private test temp root, and include it in `check`.

- [ ] **Step 4: Document operation editor usage and boundaries**

Add exact startup, five-step workflow, security defaults, capacity/TTL, artifact rules, Java home requirements, and browser support. Remove UI operation editor and Generator API from unfinished P1. Keep Windows validation host as the only explicitly unfinished local-platform slice; keep P2 items unchanged.

- [ ] **Step 5: Run focused and affected GREEN**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew \
  :generator-application:test \
  :generator-core:test \
  :generator-validation:test \
  :generator-web:test \
  :generator-web:integrationTest \
  :generator-cli:test \
  :generator-cli:integrationTest \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 6: Run exact full acceptance**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew clean test integrationTest \
  :generator-cli:installDist :generator-web:installDist \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: all tests pass, only the three existing macOS filesystem assumption skips remain, and both installed executables exist.

- [ ] **Step 7: Perform inline completion review**

```bash
git diff --check
git status --short
rg -n "Authorization:|Bearer |secret|private-marker|exception.stacktrace|/Users/" \
  generator-*/build/test-results --glob '*.xml'
ps -Ao pid=,ppid=,command= | rg 'gen2spring-mcp-web|weather-mcp-server.jar|openapi-mcp' | rg -v 'rg '
```

Inspect the complete slice for route allow-listing, origin/host/token enforcement, bounded reads, path ownership, state transitions, fatal/interrupt/cleanup, exact DTO fields, external requests, high-cardinality/log leaks, accessibility, 400px layout, CLI compatibility, and PRD/README synchronization. Any Critical or Important finding gets a RED regression and a meaningful `feat:` or `refactor:` commit.

- [ ] **Step 8: Commit**

```bash
git add README.md generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java generator-web
git commit -m "docs: document the local operation editor"
```

## Completion Gate

- [ ] The shared parser accepts/rejects identical semantic configuration for CLI YAML and Web JSON.
- [ ] CLI and Web use one canonical profile/analyzer/planner/pipeline composition graph.
- [ ] Preview and generation use exact canonical Tool/schema/profile results without leaking validation values.
- [ ] Progress publishes the exact eight-stage finite sequence and seals every terminal path once.
- [ ] Server binds only numeric loopback and enforces remote/Host/Origin/token/body/content/path bounds.
- [ ] One running plus one queued job works; third work is rejected; TTL/delete/close cleanup is bounded.
- [ ] ZIP is downloadable only for VALIDATED; report/manifest follow exact owned path and state rules.
- [ ] Five-step UI is keyboard accessible, 400px responsive, and makes no external request.
- [ ] Real browser and real generation/download journeys pass.
- [ ] Existing four-profile CLI and full repository acceptance pass without new skips.
- [ ] README no longer lists Generator API/UI operation editor as unfinished; Windows validation host remains explicit.
