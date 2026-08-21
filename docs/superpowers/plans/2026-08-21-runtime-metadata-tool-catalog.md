# Deterministic Runtime Metadata and Persistent Tool Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate canonical framework-neutral runtime metadata and atomically publish successful hosted generations to an owner-scoped PostgreSQL Tool Catalog query API.

**Architecture:** Project the final `ToolDefinition` list into one immutable domain document, encode it with one strict canonical JSON codec, and include the resulting file in every generated project. The hosted runner transports that exact document to the worker, while the existing fenced job-completion transaction inserts artifacts, Catalog, Tool entries, job state, and event atomically.

**Tech Stack:** Java 21 generator, Java 17/21 generated targets, Jackson 2, Spring Boot MVC/Security, JDBC, Flyway, PostgreSQL 17.9-alpine, Testcontainers, Gradle 9.6.1, JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/2026-08-21-runtime-metadata-tool-catalog-design.md`

## Global Constraints

- The runtime metadata file is exactly `RUNTIME_METADATA.json`, compact UTF-8, one final LF, and at most 1,048,576 bytes.
- Metadata version is exactly `1.0`; checksum is lowercase SHA-256 over the canonical payload without its checksum field.
- Equivalent final Tool IR must produce identical metadata across Spring AI 1/2 and Java 17/21 profiles.
- Metadata must never contain environment variable names, secret values, local paths, object keys, worker details, timestamps, owner IDs, generation IDs, or profile IDs.
- `credentialSlot` is the `SecretBinding.propertyName()` lower-cased with `Locale.ROOT`, must match `[a-z][a-z0-9_-]{0,127}`, and conflicting targets fail closed.
- Only hosted `GENERATION` jobs completed as `SUCCEEDED` may publish a Catalog; successful imports and all failed/cancelled jobs must not publish one.
- Artifact rows, Catalog rows, Tool rows, job transition, and job event must commit in one existing fenced PostgreSQL transaction.
- Every Catalog query must include `owner_account_id`; foreign and absent resources return the same safe 404.
- Existing PostgreSQL remains the only database; migrations and integration tests use `postgres:17.9-alpine`.
- Dynamic Managed Runtime execution, `McpJavaSdkEmitter`, credential values, Catalog mutation, sharing, search, and backfill remain out of scope.
- Use TDD for every task. Do not weaken exact schema, checksum, ownership, fencing, cleanup, or secret-safety assertions to obtain GREEN.

---

### Task 1: Extract the Canonical Tool JSON Schema Projection

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/toolmodel/schema/ToolJsonSchemaFactory.java`
- Create: `modules/application/src/test/java/io/gen2spring/mcp/application/toolmodel/schema/ToolJsonSchemaFactoryTest.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/validation/ExpectedToolSchemaFactory.java`
- Modify: `modules/application/src/test/java/io/gen2spring/mcp/application/validation/ExpectedToolSchemaFactoryTest.java`

**Interfaces:**
- Consumes: `ToolInput`, `ToolOutput`, and `OpenApiDocument.ApiSchema`.
- Produces:

```java
public final class ToolJsonSchemaFactory {
    public Map<String, Object> inputSchema(List<ToolInput> inputs);
    public Map<String, Object> outputSchema(ToolOutput output);
    public Map<String, Object> schema(OpenApiDocument.ApiSchema schema);
}
```

- `ExpectedToolSchemaFactory` must delegate input schema creation to this class and retain its existing public `create(List<ToolDefinition>)` contract.

- [ ] **Step 1: Write independent schema projection tests**

Create tests with fixed expected maps for a representative Tool containing a required string, nullable nested object, exact decimal bounds, array `minItems`, and typed output. Add a raw output test expecting `Map.of()`.

```java
assertEquals(Map.of(
        "type", "object",
        "properties", Map.of(
                "city", Map.of("type", "string", "description", "City")),
        "required", List.of("city")), factory.inputSchema(inputs));
assertEquals(Map.of(), factory.outputSchema(new ToolOutput(RAW_JSON, null, null)));
```

Add a characterization assertion that `ExpectedToolSchemaFactory.create(tools)` returns the same exact input schema, but build the expected map independently rather than calling the new factory.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
mise exec -- ./gradlew \
  :modules:application:test \
  --tests 'io.gen2spring.mcp.application.toolmodel.schema.ToolJsonSchemaFactoryTest' \
  --tests 'io.gen2spring.mcp.application.validation.ExpectedToolSchemaFactoryTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: test compilation fails because `ToolJsonSchemaFactory` does not exist.

- [ ] **Step 3: Move schema conversion into the new factory**

Move the existing supported-schema validation and immutable map construction without changing JSON Schema semantics. Implement output handling exactly as follows:

```java
public Map<String, Object> outputSchema(ToolOutput output) {
    Objects.requireNonNull(output, "output");
    return output.kind() == OutputKind.RAW_JSON ? Map.of() : schema(output.resultSchema());
}
```

Inject the factory through a package-visible constructor while keeping the existing no-argument constructor:

```java
public ExpectedToolSchemaFactory() {
    this(new ToolJsonSchemaFactory());
}

ExpectedToolSchemaFactory(ToolJsonSchemaFactory schemas) {
    this.schemas = Objects.requireNonNull(schemas, "schemas");
}
```

- [ ] **Step 4: Run focused and application module tests**

Run:

```bash
mise exec -- ./gradlew :modules:application:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; existing tools/list schema tests remain byte/structure equivalent.

- [ ] **Step 5: Review and commit the schema extraction**

Verify no Spring, Jackson, filesystem, or persistence imports enter `ToolJsonSchemaFactory`.

```bash
git diff --check
git add modules/application/src/main/java/io/gen2spring/mcp/application/toolmodel/schema/ToolJsonSchemaFactory.java \
  modules/application/src/test/java/io/gen2spring/mcp/application/toolmodel/schema/ToolJsonSchemaFactoryTest.java \
  modules/application/src/main/java/io/gen2spring/mcp/application/validation/ExpectedToolSchemaFactory.java \
  modules/application/src/test/java/io/gen2spring/mcp/application/validation/ExpectedToolSchemaFactoryTest.java
git commit -m "refactor(application): share Tool JSON schema projection"
```

---

### Task 2: Define and Canonically Encode Runtime Metadata

**Files:**
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/runtime/RuntimeMetadataDocument.java`
- Create: `modules/domain/src/test/java/io/gen2spring/mcp/domain/runtime/RuntimeMetadataDocumentTest.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/runtime/metadata/RuntimeMetadataArtifact.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/runtime/metadata/RuntimeMetadataDocumentFactory.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/runtime/metadata/CanonicalRuntimeMetadataCodec.java`
- Create: `modules/application/src/test/java/io/gen2spring/mcp/application/runtime/metadata/RuntimeMetadataDocumentFactoryTest.java`
- Create: `modules/application/src/test/java/io/gen2spring/mcp/application/runtime/metadata/CanonicalRuntimeMetadataCodecTest.java`
- Modify: `modules/domain/src/main/java/io/gen2spring/mcp/domain/error/GeneratorErrorCode.java`

**Interfaces:**
- Consumes: final `ToolDefinition` list, specification checksum, and Task 1 `ToolJsonSchemaFactory`.
- Produces:

```java
public record RuntimeMetadataDocument(
        String metadataVersion,
        String specificationChecksum,
        List<RuntimeTool> tools) {
    public static final String VERSION = "1.0";
    public static final String FILE_NAME = "RUNTIME_METADATA.json";

    public record RuntimeTool(
            String operationId,
            String name,
            String description,
            Map<String, Object> inputSchema,
            String outputKind,
            Map<String, Object> outputSchema,
            RuntimeHttp http,
            ResponseNormalizationPolicy responseNormalization,
            RetryPolicy retry,
            PaginationPolicy pagination,
            List<RuntimeCredential> credentials) {}

    public record RuntimeHttp(
            HttpMethod method,
            String baseUrl,
            String path,
            List<ParameterBinding> bindings,
            boolean objectRequestBody,
            boolean requestBodyRequired) {}

    public record RuntimeCredential(
            String credentialSlot,
            ParameterLocation targetLocation,
            String targetName,
            boolean required) {}
}

public record RuntimeMetadataArtifact(
        RuntimeMetadataDocument document,
        String checksum,
        byte[] content) {
    public RuntimeMetadataArtifact {
        content = content.clone();
    }
    @Override public byte[] content() { return content.clone(); }
}

public final class RuntimeMetadataDocumentFactory {
    public RuntimeMetadataDocument create(String specificationChecksum, List<ToolDefinition> tools);
}

public final class CanonicalRuntimeMetadataCodec {
    public static final int MAX_BYTES = 1_048_576;
    public RuntimeMetadataArtifact encode(RuntimeMetadataDocument document);
    public RuntimeMetadataArtifact decode(byte[] content);
    public String encodeTool(RuntimeMetadataDocument.RuntimeTool tool);
}
```

- Add `RUNTIME_METADATA_INVALID` to `GeneratorErrorCode`.

- [ ] **Step 1: Write domain invariant and immutability tests**

Test invalid versions, hashes, blank names, unsafe URI user-info/fragments, malformed credential slots, duplicate Tool names, mutable input maps/lists, and normalized header target collisions. Verify all collection and byte-array accessors are defensive.

```java
assertThrows(IllegalArgumentException.class, () -> documentWithBaseUrl("https://user@example.test"));
assertThrows(IllegalArgumentException.class, () -> documentWithCredential("Service.Key"));
assertEquals("service-key", factoryCredential("Service-Key").credentialSlot());
```

- [ ] **Step 2: Write factory and codec RED tests**

Use fixed Tool fixtures and exact expected JSON text. Cover reordered Tool input, nullable nested schemas, `minItems`, exact high-precision decimals, typed output, raw output `{}`, retry, pagination, response normalization, and credential sharing/collision.

```java
assertEquals(expectedCanonicalJson + "\n", new String(artifact.content(), UTF_8));
assertEquals(expectedSha256, artifact.checksum());
RuntimeMetadataArtifact decoded = codec.decode(artifact.content());
assertEquals(artifact.document(), decoded.document());
assertEquals(artifact.checksum(), decoded.checksum());
assertArrayEquals(artifact.content(), decoded.content());
assertFalse(new String(artifact.content(), UTF_8).contains("KMA_SERVICE_KEY"));
```

Add mutation tests for duplicate fields, trailing tokens, altered checksum, reordered object fields, missing LF, extra LF, 1 MiB exact/over boundary, and unknown fields.

- [ ] **Step 3: Run domain/application focused tests and verify RED**

Run:

```bash
mise exec -- ./gradlew \
  :modules:domain:test --tests 'io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocumentTest' \
  :modules:application:test --tests 'io.gen2spring.mcp.application.runtime.metadata.*' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails for the missing metadata types and error code.

- [ ] **Step 4: Implement immutable projection and strict canonical JSON**

Build every JSON node manually in the documented field order. Do not use generic record serialization. Compute the payload checksum before adding the checksum field, then serialize the complete document compactly and append one LF.

```java
byte[] payload = objectMapper.writeValueAsBytes(payloadNode(document));
String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
ObjectNode complete = completeNode(document, checksum);
byte[] json = objectMapper.writeValueAsBytes(complete);
byte[] content = Arrays.copyOf(json, json.length + 1);
content[content.length - 1] = '\n';
```

Decode with strict duplicate detection, trailing-token rejection, exact field validation, checksum verification, canonical re-encode, and `Arrays.equals(received, canonical.content())`.

Map every user-caused construction/encoding failure to:

```java
GeneratorException.user(
        RUNTIME_METADATA_INVALID,
        "RUNTIME_METADATA",
        "Runtime metadata could not be generated");
```

Rethrow fatal `Error`; never include the rejected input in the exception.

- [ ] **Step 5: Run focused and full domain/application tests**

Run:

```bash
mise exec -- ./gradlew :modules:domain:test :modules:application:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; exact codec and secret-safety tests pass.

- [ ] **Step 6: Review and commit the metadata contract**

```bash
git diff --check
rg -n 'environmentVariable|KMA_SERVICE_KEY|/Users/|/home/' \
  modules/domain/src/main/java/io/gen2spring/mcp/domain/runtime \
  modules/application/src/main/java/io/gen2spring/mcp/application/runtime
git add modules/domain/src/main/java/io/gen2spring/mcp/domain/error/GeneratorErrorCode.java \
  modules/domain/src/main/java/io/gen2spring/mcp/domain/runtime \
  modules/domain/src/test/java/io/gen2spring/mcp/domain/runtime \
  modules/application/src/main/java/io/gen2spring/mcp/application/runtime \
  modules/application/src/test/java/io/gen2spring/mcp/application/runtime
git commit -m "feat(runtime): define canonical Tool metadata"
```

The `rg` result may contain only test fixture assertions that prove forbidden values are absent; production metadata types must contain none.

---

### Task 3: Add Runtime Metadata to Generation, Preview, and Archives

**Files:**
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/usecase/GenerationPipeline.java`
- Modify: `modules/bootstrap/src/main/java/io/gen2spring/mcp/bootstrap/GeneratorRuntime.java`
- Modify: `modules/adapters/filesystem/src/test/java/io/gen2spring/mcp/adapter/filesystem/GenerationPipelineTest.java`
- Modify: `modules/adapters/filesystem/src/test/java/io/gen2spring/mcp/adapter/filesystem/GenerationPreviewTest.java`
- Modify: `modules/adapters/filesystem/src/test/java/io/gen2spring/mcp/adapter/filesystem/SourceTreeChecksumTest.java`
- Modify: `modules/bootstrap/src/test/java/io/gen2spring/mcp/bootstrap/GeneratorRuntimeTest.java`
- Modify: `apps/cli/src/integrationTest/java/io/gen2spring/mcp/app/cli/P1GenerationIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2 factory and codec.
- Produces: generated projects, previews, source checksums, and validated ZIPs containing exact `RUNTIME_METADATA.json` bytes.

- [ ] **Step 1: Write pipeline and preview RED assertions**

Assert that metadata exists before validation, participates in the source checksum, appears in preview paths, is byte-identical inside the validated ZIP, and contains no profile ID or environment variable name.

```java
byte[] projectMetadata = Files.readAllBytes(outcome.projectRoot().resolve("RUNTIME_METADATA.json"));
try (ZipFile zip = new ZipFile(outcome.archive().toFile())) {
    assertArrayEquals(projectMetadata,
            zip.getInputStream(zip.getEntry("RUNTIME_METADATA.json")).readAllBytes());
}
```

Generate equivalent Tool IR with Spring AI 1/2 and Java 17/21 profile fixtures and compare metadata bytes. Change one Tool semantic field and assert the source checksum changes.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
mise exec -- ./gradlew \
  :modules:adapters:filesystem:test --tests 'io.gen2spring.mcp.adapter.filesystem.GenerationPipelineTest' \
  --tests 'io.gen2spring.mcp.adapter.filesystem.GenerationPreviewTest' \
  :modules:bootstrap:test --tests 'io.gen2spring.mcp.bootstrap.GeneratorRuntimeTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: assertions fail because the metadata path is absent.

- [ ] **Step 3: Inject the metadata factory and codec into the pipeline**

Add explicit fields and a full constructor while preserving current constructors through delegation:

```java
private final RuntimeMetadataDocumentFactory metadataFactory;
private final CanonicalRuntimeMetadataCodec metadataCodec;
```

After planning and before `projectWorkspace.write`, create the artifact and add it with `putIfAbsent`:

```java
RuntimeMetadataArtifact metadata = metadataCodec.encode(
        metadataFactory.create(analysis.document().checksum(), tools));
Map<String, byte[]> files = new LinkedHashMap<>(completeProject.files());
if (files.putIfAbsent(RuntimeMetadataDocument.FILE_NAME, metadata.content()) != null) {
    throw runtimeMetadataFailure();
}
```

Reject any emitter attempt to replace the reserved metadata path. Add the path to preview output. Wire one factory/codec pair in `GeneratorRuntime.defaults()`.

- [ ] **Step 4: Update exact CLI artifact contracts**

Add `RUNTIME_METADATA.json` to `P1GenerationIntegrationTest.REQUIRED_OUTPUTS`, archived entry comparisons, sensitive-value scans, and deterministic output assertions. Parse the document independently and assert version, checksum format, exact Tool count, and absence of target profile metadata.

- [ ] **Step 5: Run focused pipeline and CLI integration tests**

Run:

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew \
  :modules:adapters:filesystem:test \
  :modules:bootstrap:test \
  :apps:cli:integrationTest --tests 'io.gen2spring.mcp.app.cli.P1GenerationIntegrationTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; all four profile rows retain deterministic archives outside existing measured validation fields.

- [ ] **Step 6: Review and commit generation integration**

```bash
git diff --check
git add modules/application/src/main/java/io/gen2spring/mcp/application/usecase/GenerationPipeline.java \
  modules/bootstrap/src/main/java/io/gen2spring/mcp/bootstrap/GeneratorRuntime.java \
  modules/adapters/filesystem/src/test/java/io/gen2spring/mcp/adapter/filesystem/GenerationPipelineTest.java \
  modules/adapters/filesystem/src/test/java/io/gen2spring/mcp/adapter/filesystem/GenerationPreviewTest.java \
  modules/adapters/filesystem/src/test/java/io/gen2spring/mcp/adapter/filesystem/SourceTreeChecksumTest.java \
  modules/bootstrap/src/test/java/io/gen2spring/mcp/bootstrap/GeneratorRuntimeTest.java \
  apps/cli/src/integrationTest/java/io/gen2spring/mcp/app/cli/P1GenerationIntegrationTest.java
git commit -m "feat(generator): emit deterministic runtime metadata"
```

---

### Task 4: Transport Strict Metadata Through Runner Protocol 2

**Files:**
- Modify: `deploy/hosted/runner/Dockerfile`
- Modify: `deploy/hosted/runner/job-entrypoint.sh`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/worker/SandboxResult.java`
- Modify: `modules/adapters/container-runtime/src/main/java/io/gen2spring/mcp/adapter/container/SandboxOutputCollector.java`
- Modify: `modules/adapters/container-runtime/src/test/java/io/gen2spring/mcp/adapter/container/RunnerImageContractTest.java`
- Modify: `modules/adapters/container-runtime/src/test/java/io/gen2spring/mcp/adapter/container/DockerCliSandboxRuntimeTest.java`
- Modify: `apps/worker/src/main/java/io/gen2spring/mcp/app/worker/WorkerInfrastructureConfiguration.java`
- Modify: `apps/worker/src/test/java/io/gen2spring/mcp/app/worker/WorkerApplicationTest.java`

**Interfaces:**
- Consumes: Task 2 `CanonicalRuntimeMetadataCodec` and `RuntimeMetadataArtifact`.
- Produces:

```java
public record SandboxResult(
        List<SandboxArtifact> artifacts,
        Optional<RuntimeMetadataArtifact> runtimeMetadata,
        String outcome) implements AutoCloseable {
    public SandboxResult(List<SandboxArtifact> artifacts, String outcome) {
        this(artifacts, Optional.empty(), outcome);
    }
}
```

- Generation runner label is exactly `io.gen2spring.runner.protocol="2"`; import runner remains protocol `1`.

- [ ] **Step 1: Write runner and collector RED tests**

Update the fake generated project to contain a valid canonical metadata file. Assert the generation runner copies it atomically and the collector returns it separately from the three S3 artifacts.

Add explicit rejection tests for missing, unexpected, symlinked, empty, over-1-MiB, malformed, duplicate-key, trailing-token, checksum-mutated, reordered, and extra-LF metadata.

```java
assertEquals(3, result.artifacts().size());
RuntimeMetadataArtifact actual = result.runtimeMetadata().orElseThrow();
assertEquals(expectedMetadata.document(), actual.document());
assertEquals(expectedMetadata.checksum(), actual.checksum());
assertArrayEquals(expectedMetadata.content(), actual.content());
```

Assert generation image protocol 2, import image protocol 1, and worker readiness rejects either mismatch with a fixed configuration failure.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
mise exec -- ./gradlew \
  :modules:adapters:container-runtime:test --tests 'io.gen2spring.mcp.adapter.container.RunnerImageContractTest' \
  --tests 'io.gen2spring.mcp.adapter.container.DockerCliSandboxRuntimeTest' \
  :apps:worker:test --tests 'io.gen2spring.mcp.app.worker.WorkerApplicationTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: failures for the missing output and unchanged protocol label.

- [ ] **Step 3: Implement atomic runner copy and strict collector decode**

Extend the shell cleanup/staging sequence with `.staging-runtime-metadata` and `runtime-metadata.json`. Require the project file to be regular and not a symlink before copying.

In `SandboxOutputCollector`, include the metadata filename in the exact output allow-list, read at most 1 MiB, decode through the canonical codec, and exclude it from the ordinary artifact loop.

Change readiness to carry the expected protocol per image:

```java
verifyImage(docker, base, properties.docker().generationImage(), "2");
verifyImage(docker, base, properties.docker().importImage(), "1");
```

- [ ] **Step 4: Run container-runtime and worker module tests**

Run:

```bash
mise exec -- ./gradlew :modules:adapters:container-runtime:test :apps:worker:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; no workspace, stream, or container leak regression.

- [ ] **Step 5: Review and commit runner protocol 2**

```bash
git diff --check -- deploy/hosted/runner modules/adapters/container-runtime apps/worker modules/application
git add deploy/hosted/runner/Dockerfile deploy/hosted/runner/job-entrypoint.sh \
  modules/application/src/main/java/io/gen2spring/mcp/application/hosted/worker/SandboxResult.java \
  modules/adapters/container-runtime/src/main/java/io/gen2spring/mcp/adapter/container/SandboxOutputCollector.java \
  modules/adapters/container-runtime/src/test/java/io/gen2spring/mcp/adapter/container/RunnerImageContractTest.java \
  modules/adapters/container-runtime/src/test/java/io/gen2spring/mcp/adapter/container/DockerCliSandboxRuntimeTest.java \
  apps/worker/src/main/java/io/gen2spring/mcp/app/worker/WorkerInfrastructureConfiguration.java \
  apps/worker/src/test/java/io/gen2spring/mcp/app/worker/WorkerApplicationTest.java
git commit -m "feat(sandbox): transport canonical runtime metadata"
```

---

### Task 5: Bind Catalog Publication to Hosted Job Completion

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/catalog/ToolCatalogPublication.java`
- Create: `modules/application/src/test/java/io/gen2spring/mcp/application/hosted/catalog/ToolCatalogPublicationTest.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/job/JobQueue.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/worker/HostedWorker.java`
- Modify: `modules/application/src/test/java/io/gen2spring/mcp/application/hosted/worker/HostedWorkerTest.java`

**Interfaces:**
- Consumes: Task 4 `SandboxResult.runtimeMetadata()`.
- Produces:

```java
public record ToolCatalogPublication(
        RuntimeMetadataArtifact metadata) {
    public static ToolCatalogPublication from(RuntimeMetadataArtifact artifact);
}

boolean complete(
        JobLease lease,
        JobCompletion completion,
        List<JobArtifact> artifacts,
        Optional<ToolCatalogPublication> catalog);
```

- [ ] **Step 1: Write publication invariant RED tests**

Verify exact UTF-8 document retention, deterministic ordinal assignment, defensive copies, 1 MiB bound, 1,000 Tool bound, and fixed non-leaking failures.

In `HostedWorkerTest`, add these cases:

- generation success forwards exactly one Catalog and three artifacts
- generation success without metadata fails safely and stores neither artifacts nor Catalog
- import success forwards no Catalog
- failed/cancelled/stale jobs forward no Catalog
- Catalog completion exception triggers deletion of every uploaded object
- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :modules:application:test \
  --tests 'io.gen2spring.mcp.application.hosted.catalog.ToolCatalogPublicationTest' \
  --tests 'io.gen2spring.mcp.application.hosted.worker.HostedWorkerTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails for missing publication and completion overload.

- [ ] **Step 3: Implement publication and completion rules**

Keep existing `complete` overloads for source compatibility and delegate with `Optional.empty()`. The new overload validates the combination before calling the adapter:

```java
boolean generationSuccess = lease.kind() == JobKind.GENERATION
        && completion.status() == JobStatus.SUCCEEDED;
if (generationSuccess != catalog.isPresent()
        || completion.status() != JobStatus.SUCCEEDED && catalog.isPresent()
        || lease.kind() == JobKind.SPEC_IMPORT && catalog.isPresent()) {
    throw new IllegalArgumentException("Hosted Tool Catalog publication is invalid");
}
```

Have `HostedWorker.publish` convert metadata only after sandbox success, upload the three ordinary artifacts,
and pass the publication to the new completion overload. Preserve existing `Error`, interruption, stale lease,
cancellation, and S3 cleanup ordering.

- [ ] **Step 4: Run application tests**

Run:

```bash
mise exec -- ./gradlew :modules:application:test --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; every legacy import and failure path remains GREEN.

- [ ] **Step 5: Review and commit the completion contract**

```bash
git diff --check
git add modules/application/src/main/java/io/gen2spring/mcp/application/hosted/catalog \
  modules/application/src/test/java/io/gen2spring/mcp/application/hosted/catalog \
  modules/application/src/main/java/io/gen2spring/mcp/application/hosted/job/JobQueue.java \
  modules/application/src/main/java/io/gen2spring/mcp/application/hosted/worker/HostedWorker.java \
  modules/application/src/test/java/io/gen2spring/mcp/application/hosted/worker/HostedWorkerTest.java
git commit -m "feat(hosted): bind Catalog to generation completion"
```

---

### Task 6: Persist and Query Immutable Catalogs in PostgreSQL

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/catalog/ToolCatalogStore.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/catalog/ToolCatalogService.java`
- Create: `modules/application/src/test/java/io/gen2spring/mcp/application/hosted/catalog/ToolCatalogServiceTest.java`
- Create: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresToolCatalogStore.java`
- Create: `modules/adapters/persistence-postgres/src/main/resources/db/migration/V4__tool_catalog.sql`
- Create: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresToolCatalogStoreTest.java`
- Modify: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresJobQueue.java`
- Modify: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresJobQueueTest.java`
- Modify: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresMigrationTest.java`

**Interfaces:**
- Consumes: Task 5 `ToolCatalogPublication` through `JobQueue.complete`.
- Produces:

```java
public interface ToolCatalogStore {
    List<CatalogSummary> list(AccountId owner, int fetchLimit, Optional<CatalogCursor> cursor);
    Optional<CatalogDetails> find(AccountId owner, UUID catalogId);
    Optional<ToolDetails> findTool(AccountId owner, UUID catalogId, String toolName);

    record CatalogCursor(Instant createdAt, UUID id) {}
    record CatalogSummary(UUID catalogId, JobId generationId, String metadataVersion,
            String metadataChecksum, int toolCount, Instant createdAt) {}
    record CatalogPage(List<CatalogSummary> items, Optional<CatalogCursor> nextCursor) {}
    record CatalogDetails(CatalogSummary summary, String specificationChecksum,
            RuntimeMetadataArtifact metadata) {}
    record ToolDetails(CatalogSummary summary, RuntimeMetadataDocument.RuntimeTool tool) {}
}

public final class ToolCatalogService {
    public CatalogPage list(AccountId owner, int limit, Optional<CatalogCursor> cursor);
    public CatalogDetails require(AccountId owner, UUID catalogId);
    public ToolDetails requireTool(AccountId owner, UUID catalogId, String toolName);
}
```

- [ ] **Step 1: Write service validation RED tests**

Test null owner, limits 0/101, invalid cursor, malformed Tool name, missing/foreign resource equivalence, and store failures. The service must expose fixed `ToolCatalogNotFound`, `ToolCatalogQueryInvalid`, and `ToolCatalogReadFailure` exceptions without resource values.

- [ ] **Step 2: Write migration and atomic publication RED tests**

Add schema assertions for both tables, constraints, foreign keys, unique keys, owner pagination index, text bounds,
and absence of mutation triggers or update API.

Using PostgreSQL 17.9-alpine, claim a real generation lease and verify:

```java
assertTrue(jobs.complete(lease, JobCompletion.success(), artifacts, Optional.of(publication)));
assertEquals("SUCCEEDED", jdbc.queryForObject(
        "select status from generation_job where id = ?", String.class, lease.jobId().value()));
assertEquals(1, jdbc.queryForObject(
        "select count(*) from tool_catalog where generation_job_id = ?", Integer.class, lease.jobId().value()));
```

Inject invalid second Tool data and assert the transaction leaves job `RUNNING` with zero artifact, Catalog, Tool,
and terminal event rows. Verify successful imports reject Catalogs and successful generations reject missing Catalogs.

- [ ] **Step 3: Run persistence focused tests and verify RED**

Run:

```bash
mise exec -- ./gradlew \
  :modules:adapters:persistence-postgres:test \
  --tests 'io.gen2spring.mcp.adapter.persistence.PostgresMigrationTest' \
  --tests 'io.gen2spring.mcp.adapter.persistence.PostgresJobQueueTest' \
  --tests 'io.gen2spring.mcp.adapter.persistence.PostgresToolCatalogStoreTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: migration/catalog types are missing and the new tests fail to compile.

- [ ] **Step 4: Add V4 and atomic inserts to `PostgresJobQueue`**

Use the existing `TransactionTemplate` and live lease row lock. Generate `catalogId` and `createdAt` once, insert
the Catalog and ordered Tool entries before changing the job status, then append the terminal event. Do not open
a nested transaction or call a second repository transaction.

Use `CanonicalRuntimeMetadataCodec.encodeTool(tool)` for every entry document. Do not add a second Jackson
serialization path in the persistence adapter.

The migration must include:

```sql
constraint tool_catalog_generation_owner_fk
    foreign key (generation_job_id, owner_account_id)
    references generation_job(id, owner_account_id) on delete cascade,
constraint tool_catalog_generation_unique unique (generation_job_id),
constraint tool_catalog_metadata_checksum_valid check (metadata_checksum ~ '^[a-f0-9]{64}$'),
constraint tool_catalog_tool_count_valid check (tool_count between 1 and 1000)
```

Bound both canonical text columns with `octet_length` and validate their JSON object shape through casts in
database constraints.

- [ ] **Step 5: Implement owner-scoped read adapter and service**

The service validates a public limit of 1-100, asks the store for `limit + 1`, returns at most `limit` items,
and derives `nextCursor` from the final returned item only when the extra row exists. The store accepts only a
bounded fetch limit of 2-101.

List with `(created_at, id) < (?, ?)` and `order by created_at desc, id desc`. Detail and Tool SQL must join or
filter through `tool_catalog.owner_account_id = ?`. Decode persisted canonical text through Task 2 codec before
returning it; never return raw text after a decode failure.

- [ ] **Step 6: Run application and full persistence tests**

Run:

```bash
mise exec -- ./gradlew :modules:application:test :modules:adapters:persistence-postgres:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; PostgreSQL tests use `postgres:17.9-alpine` and leave no partial rows.

- [ ] **Step 7: Review and commit persistence**

```bash
git diff --check
git add modules/application/src/main/java/io/gen2spring/mcp/application/hosted/catalog \
  modules/application/src/test/java/io/gen2spring/mcp/application/hosted/catalog \
  modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresJobQueue.java \
  modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresToolCatalogStore.java \
  modules/adapters/persistence-postgres/src/main/resources/db/migration/V4__tool_catalog.sql \
  modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresJobQueueTest.java \
  modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresToolCatalogStoreTest.java \
  modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresMigrationTest.java
git commit -m "feat(persistence): store immutable Tool Catalogs"
```

---

### Task 7: Expose Owner-Scoped Tool Catalog APIs

**Files:**
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedToolCatalogController.java`
- Create: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedToolCatalogControllerTest.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedCursorCodec.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedSpecificationController.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/config/HostedWebConfiguration.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/error/WebErrorMapper.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedCursorCodecTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedWebMvcContractTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedControllerContractTest.java`

**Interfaces:**
- Consumes: Task 6 `ToolCatalogService`.
- Produces the three exact `/api/tool-catalogs` GET routes from the design.

- [ ] **Step 1: Generalize the hosted cursor codec with RED tests**

Change the codec to own a neutral cursor:

```java
record Cursor(Instant createdAt, UUID id) {}
Optional<Cursor> decode(String value);
String encode(Cursor cursor);
```

Update `HostedSpecificationController` to translate between this cursor and `HostedResourceStore.ResourceCursor`.
Add tests proving existing cursor bytes remain unchanged so deployed links do not break.

- [ ] **Step 2: Write API RED tests**

Add Web MVC tests for:

- OIDC required
- list default/maximum limit and next cursor
- Catalog detail exact metadata/checksum
- Tool detail exact Tool document
- malformed UUID, cursor, and Tool name
- missing and cross-owner Catalog/Tool produce the same 404 body
- secret environment name, synthetic secret, object key, local path, and worker ID absent
- local mode does not register the hosted controller

Example assertions:

```java
mvc.perform(get("/api/tool-catalogs/{id}", catalogId).with(user()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.catalogId").value(catalogId.toString()))
        .andExpect(jsonPath("$.metadata.metadataVersion").value("1.0"))
        .andExpect(content().string(not(containsString("KMA_SERVICE_KEY"))));
```

- [ ] **Step 3: Run web focused tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :apps:web:test \
  --tests 'io.gen2spring.mcp.app.web.hosted.HostedCursorCodecTest' \
  --tests 'io.gen2spring.mcp.app.web.hosted.HostedToolCatalogControllerTest' \
  --tests 'io.gen2spring.mcp.app.web.hosted.HostedWebMvcContractTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails because the controller and Catalog beans do not exist.

- [ ] **Step 4: Implement controller, wiring, and safe error mapping**

Wire `PostgresToolCatalogStore` and `ToolCatalogService` in hosted configuration. Resolve the account before every
service call. Build response JSON explicitly through `ObjectMapper` so only approved fields are emitted.

Map service exceptions:

```java
if (failure instanceof ToolCatalogService.ToolCatalogNotFound) {
    return new WebFailure(404, "RESOURCE_NOT_FOUND", "CATALOG_LOOKUP",
            "The hosted resource was not found");
}
if (failure instanceof ToolCatalogService.ToolCatalogQueryInvalid) {
    return new WebFailure(400, "CATALOG_QUERY_INVALID", "CATALOG_LOOKUP",
            "The Tool Catalog query is invalid");
}
```

Unexpected decode/storage failures remain fixed `500 INTERNAL_ERROR`; never serialize stored raw text after failure.

- [ ] **Step 5: Run all Web tests**

Run:

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; existing specification/job/artifact APIs retain their contracts.

- [ ] **Step 6: Review and commit the API**

```bash
git diff --check
git add apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedToolCatalogController.java \
  apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedCursorCodec.java \
  apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedSpecificationController.java \
  apps/web/src/main/java/io/gen2spring/mcp/app/web/config/HostedWebConfiguration.java \
  apps/web/src/main/java/io/gen2spring/mcp/app/web/error/WebErrorMapper.java \
  apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedToolCatalogControllerTest.java \
  apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedCursorCodecTest.java \
  apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedWebMvcContractTest.java \
  apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedControllerContractTest.java
git commit -m "feat(web): expose owned Tool Catalog queries"
```

---

### Task 8: Complete Vertical-Slice Acceptance and Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/prd.md`
- Modify: `docs/superpowers/specs/2026-08-21-runtime-metadata-tool-catalog-design.md`
- Test: `apps/cli/src/integrationTest/java/io/gen2spring/mcp/app/cli/P1GenerationIntegrationTest.java`
- Test: `apps/worker/src/integrationTest/java/io/gen2spring/mcp/app/worker/WorkerCrashRecoveryIntegrationTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedWebMvcContractTest.java`

**Interfaces:**
- Consumes: Tasks 1-7 complete slice.
- Produces: synchronized product documentation and fresh repository-wide acceptance evidence.

- [ ] **Step 1: Add the end-to-end hosted publication acceptance**

Extend worker crash/retry integration coverage so one real claimed generation completion with canonical metadata
creates exactly one Catalog after retry and cannot create a duplicate. Assert three S3 artifacts, one Catalog,
the expected Tool count, one terminal event, and no partial records after the injected first completion failure.

- [ ] **Step 2: Run the vertical-slice integration gate**

Run:

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew \
  :modules:domain:test \
  :modules:application:test \
  :modules:adapters:filesystem:test \
  :modules:adapters:container-runtime:test \
  :modules:adapters:persistence-postgres:test \
  :modules:bootstrap:test \
  :apps:worker:test :apps:worker:integrationTest \
  :apps:web:test \
  :apps:cli:integrationTest --tests 'io.gen2spring.mcp.app.cli.P1GenerationIntegrationTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; no failure or error in fresh JUnit XML.

- [ ] **Step 3: Synchronize README and PRD truthfully**

Document:

- `RUNTIME_METADATA.json` in generated outputs
- Catalog list/detail/Tool endpoints and hosted authentication boundary
- Catalog publication only after `VALIDATED` generation
- no secret values or environment variable names
- no backfill and no dynamic Managed Runtime execution

In `docs/prd.md`, remove OpenAPI 3.1 from the unfinished P2 list, mark deterministic Managed Runtime metadata and
persistent Tool Catalog query as complete, and keep dynamic registry/Gateway execution, policy, sharing, and
credential routing explicitly unfinished. Align Phase 2 and priority-list terminology instead of claiming all
Managed Runtime or Gateway work is complete.

- [ ] **Step 4: Run security and determinism readback checks**

Run:

```bash
git diff --check
rg -n 'KMA_SERVICE_KEY|private marker|/Users/|/home/runner|BEGIN PRIVATE KEY' \
  README.md docs/prd.md modules/domain/src/main modules/application/src/main \
  modules/adapters/container-runtime/src/main modules/adapters/persistence-postgres/src/main \
  apps/web/src/main apps/worker/src/main
```

Expected: no secret value, private marker, private key, or absolute developer/runner path in production and docs.
Any fixture-only match must be inspected and recorded rather than ignored wholesale.

- [ ] **Step 5: Run the exact full repository gate**

Run:

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew clean test integrationTest :apps:cli:installDist \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; collect total tests, failures, errors, and intentional skips from fresh XML. Confirm
no generated application, Docker runner, Gradle 9.6.1 process, or temporary hosted object remains.

- [ ] **Step 6: Review the complete implementation against the design**

Read every design section and map it to a test or implementation path. Specifically recheck:

- one canonical codec at both generation and sandbox boundaries
- no profile-dependent metadata
- exact output schema semantics
- no environment variable exposure
- generation/import completion invariants
- one transaction and fencing predicates
- owner predicate in every query
- absent/foreign 404 equivalence
- runner generation protocol 2 and import protocol 1
- no P2 completion overclaim

- [ ] **Step 7: Commit acceptance and documentation**

```bash
git add README.md docs/prd.md \
  docs/superpowers/specs/2026-08-21-runtime-metadata-tool-catalog-design.md \
  apps/worker/src/integrationTest/java/io/gen2spring/mcp/app/worker/WorkerCrashRecoveryIntegrationTest.java
git commit -m "docs: publish Tool Catalog runtime contract"
```

If the integration test required production fixes, keep those fixes with the responsible earlier task commit rather
than hiding them in this documentation commit.

---

## Final Completion Criteria

- `RUNTIME_METADATA.json` is deterministic, bounded, strict, profile-neutral, and included in project/ZIP output.
- Exact input/output schemas and HTTP/policy/credential requirements come from final Tool IR.
- Environment variable names and secret values are absent from artifact, database, API, logs, and errors.
- Generation runner protocol 2 transports exact canonical metadata; import protocol remains 1.
- Successful hosted generation commits artifacts, Catalog, Tools, job state, and event atomically.
- Failed, cancelled, stale, unverified, and import paths create no Catalog.
- PostgreSQL 17.9-alpine migration and owner-scoped indexed queries pass.
- All three Tool Catalog endpoints enforce authentication, bounds, ownership, and safe error equivalence.
- README and PRD describe the bounded completed slice without claiming dynamic Managed Runtime completion.
- The exact Java 17/21 full repository gate is fresh and GREEN.
