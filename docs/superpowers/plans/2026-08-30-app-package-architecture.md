# App Package Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize flat application packages by responsibility and replace avoidable fully qualified Java types with imports without changing runtime contracts.

**Architecture:** Keep each Spring Boot or CLI entry point at the app package root. Move HTTP, configuration, security, execution, and transport responsibilities into cohesive subpackages, while keeping package-private collaborators together and exposing only the facade types consumed across packages.

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 5, Gradle Kotlin DSL, Mise, CodeRabbit CLI

**Spec:** `docs/superpowers/specs/2026-08-30-app-package-architecture-design.md`

## Global Constraints

- Preserve all HTTP paths, response status codes, error bodies, CLI behavior, environment variables, bean conditions, database schema, and generated artifact contracts.
- Keep `*Application` entry points and Gradle `mainClass` values unchanged.
- Create packages only for responsibilities approved in the spec; do not split files by size alone.
- Do not add dependencies.
- Do not rewrite protocol strings, system property names, documentation examples, or golden fixture values that merely resemble FQCNs.
- Run changes in the current checkout; do not create a worktree or branch without explicit user direction.
- Use meaningful commits: one for gateway/import app structure, one for runtime/worker structure, and one for FQCN cleanup.

---

### Task 1: Organize Fetch Gateway, Import Runner, and Provider Egress

**Files:**

- Create: `apps/fetch-gateway/src/main/java/io/gen2spring/mcp/app/fetch/fetching/FetchGatewayConfiguration.java`
- Create: `apps/provider-egress/src/main/java/io/gen2spring/mcp/app/provideregress/egress/ProviderEgressConfiguration.java`
- Create: `apps/provider-egress/src/main/java/io/gen2spring/mcp/app/provideregress/egress/ProviderEgressService.java`
- Create: `apps/fetch-gateway/src/test/java/io/gen2spring/mcp/app/fetch/FetchGatewayPackageArchitectureTest.java`
- Create: `apps/import-runner/src/test/java/io/gen2spring/mcp/app/importer/ImportRunnerPackageArchitectureTest.java`
- Create: `apps/provider-egress/src/test/java/io/gen2spring/mcp/app/provideregress/ProviderEgressPackageArchitectureTest.java`
- Move: Fetch Gateway production and test classes according to the mapping below
- Move: Import Runner production and test classes according to the mapping below
- Move: Provider Egress production and test classes according to the mapping below
- Modify: `apps/fetch-gateway/src/main/java/io/gen2spring/mcp/app/fetch/FetchGatewayApplication.java`
- Modify: `apps/provider-egress/src/main/java/io/gen2spring/mcp/app/provideregress/ProviderEgressApplication.java`

**Interfaces:**

- Consumes: Existing `ImportTarget`, `ProviderCallRequest`, `ProviderCallResponse`, Spring MVC, and Spring Security contracts.
- Produces: `BoundedFetcher.fetch(ImportTarget)`, `ProviderEgressService.execute(byte[])`, and unchanged `/internal/fetch` and `/internal/provider-call` endpoints.

- [ ] **Step 1: Add failing package architecture contracts**

Create the three architecture tests with the existing `Class.forName` pattern. The expected class sets are:

```java
// FetchGatewayPackageArchitectureTest
assertLoadable("io.gen2spring.mcp.app.fetch.api.FetchController");
assertLoadable("io.gen2spring.mcp.app.fetch.fetching.BoundedFetcher");
assertLoadable("io.gen2spring.mcp.app.fetch.fetching.FetchGatewayConfiguration");
assertLoadable("io.gen2spring.mcp.app.fetch.config.FetchGatewaySecurityConfiguration");
assertNotLoadable("io.gen2spring.mcp.app.fetch.FetchController");
assertNotLoadable("io.gen2spring.mcp.app.fetch.BoundedFetcher");

// ImportRunnerPackageArchitectureTest
assertLoadable("io.gen2spring.mcp.app.importer.job.ImportRunner");
assertLoadable("io.gen2spring.mcp.app.importer.job.ImportJobProtocol");
assertLoadable("io.gen2spring.mcp.app.importer.job.ImportGatewayClient");
assertLoadable("io.gen2spring.mcp.app.importer.job.ImportRunnerFailure");
assertNotLoadable("io.gen2spring.mcp.app.importer.ImportRunner");

// ProviderEgressPackageArchitectureTest
assertLoadable("io.gen2spring.mcp.app.provideregress.api.ProviderEgressController");
assertLoadable("io.gen2spring.mcp.app.provideregress.egress.ProviderEgressService");
assertLoadable("io.gen2spring.mcp.app.provideregress.egress.ProviderEgressConfiguration");
assertLoadable("io.gen2spring.mcp.app.provideregress.config.ProviderEgressSecurityConfiguration");
assertNotLoadable("io.gen2spring.mcp.app.provideregress.ProviderEgressController");
assertNotLoadable("io.gen2spring.mcp.app.provideregress.ApacheProviderTransport");
```

Each test uses these exact helpers:

```java
private void assertLoadable(String name) {
    assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
}

private void assertNotLoadable(String name) {
    assertThrows(ClassNotFoundException.class,
            () -> Class.forName(name, false, getClass().getClassLoader()));
}
```

- [ ] **Step 2: Run the new contracts and verify they fail**

Run:

```bash
./gradlew \
  :apps:fetch-gateway:test --tests '*FetchGatewayPackageArchitectureTest' \
  :apps:import-runner:test --tests '*ImportRunnerPackageArchitectureTest' \
  :apps:provider-egress:test --tests '*ProviderEgressPackageArchitectureTest' \
  --no-daemon --non-interactive
```

Expected: FAIL because the new package names are not loadable and the old root classes still exist.

- [ ] **Step 3: Move Fetch Gateway classes by responsibility**

Apply this exact package mapping:

```text
io.gen2spring.mcp.app.fetch.FetchController
  -> io.gen2spring.mcp.app.fetch.api.FetchController
io.gen2spring.mcp.app.fetch.BoundedFetcher
  -> io.gen2spring.mcp.app.fetch.fetching.BoundedFetcher
io.gen2spring.mcp.app.fetch.FetchTransport
  -> io.gen2spring.mcp.app.fetch.fetching.FetchTransport
io.gen2spring.mcp.app.fetch.ApacheFetchTransport
  -> io.gen2spring.mcp.app.fetch.fetching.ApacheFetchTransport
io.gen2spring.mcp.app.fetch.ValidatedDnsResolver
  -> io.gen2spring.mcp.app.fetch.fetching.ValidatedDnsResolver
io.gen2spring.mcp.app.fetch.FetchFailure
  -> io.gen2spring.mcp.app.fetch.fetching.FetchFailure
io.gen2spring.mcp.app.fetch.FetchGatewaySecurityConfiguration
  -> io.gen2spring.mcp.app.fetch.config.FetchGatewaySecurityConfiguration
```

Remove bean factories from `FetchGatewayApplication` so the root class contains only `main`. Add the bean factories next to package-private transport collaborators:

```java
@Configuration(proxyBeanMethods = false)
public class FetchGatewayConfiguration {
    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;

    @Bean(destroyMethod = "close")
    ApacheFetchTransport fetchTransport() {
        return new ApacheFetchTransport(new ValidatedDnsResolver(), Duration.ofSeconds(5));
    }

    @Bean
    public BoundedFetcher boundedFetcher(ApacheFetchTransport transport) {
        return new BoundedFetcher(
                transport, MAX_IMPORT_BYTES, MAX_IMPORT_BYTES, 3, Duration.ofSeconds(30));
    }
}
```

Make only `BoundedFetcher`, its constructor, `fetch`, `FetchResult`, and `FetchFailure` accessible to the `api` package. Keep transport interface and implementation package-private.

Split `FetchGatewaySecurityTest` by existing test method names:

```text
fetching/FetchGatewaySecurityTest:
  validatesEveryDnsAnswerAndReturnsOnlyTheValidatedSnapshot
  followsOnlyThreeManuallyValidatedRedirects
  boundsWireDecodedAndHeaderBytesIndependently
  returnsFixedFailuresWithoutRetainingTargetsOrBodies
  retriesOnlyBoundedTransportAndServerFailures
  enforcesTheTotalTimeoutBeforeTransportExecution
  enforcesTheTotalTimeoutWhileReadingAndDecodingTheBody
  usesStrictTlsHostnameVerification
  closesTheResponseWhenEntityStreamingCannotStart

api/FetchControllerTest:
  controllerRequiresAContainerVerifiedClientCertificate
```

- [ ] **Step 4: Move Import Runner classes into the job package**

Apply this exact mapping and update imports in `ImportRunnerApplication` and `ImportRunnerTest`:

```text
ImportRunner -> job.ImportRunner
ImportJobProtocol -> job.ImportJobProtocol
ImportGatewayClient -> job.ImportGatewayClient
ImportRunnerFailure -> job.ImportRunnerFailure
ImportRunnerTest -> job.ImportRunnerTest
```

Make `ImportJobProtocol` and its `run(Path, Path, Path)` entry point accessible to `ImportRunnerApplication`. Keep parsing helpers and records package-private.

- [ ] **Step 5: Introduce the Provider Egress facade and move internals**

Apply this exact mapping:

```text
ProviderEgressController -> api.ProviderEgressController
ProviderEgressSecurityConfiguration -> config.ProviderEgressSecurityConfiguration
ApacheProviderTransport -> egress.ApacheProviderTransport
ProviderTransport -> egress.ProviderTransport
ValidatedProviderResolver -> egress.ValidatedProviderResolver
ProviderRequestPolicy -> egress.ProviderRequestPolicy
ProviderResponsePolicy -> egress.ProviderResponsePolicy
ProviderEgressFailure -> egress.ProviderEgressFailure
```

Move the request decoding, request-policy validation, transport call, and response encoding behind this facade:

```java
public final class ProviderEgressService {
    private final ProviderTransport transport;
    private final ProviderEgressCodec codec;

    ProviderEgressService(ProviderTransport transport, ProviderEgressCodec codec) {
        this.transport = transport;
        this.codec = codec;
    }

    public byte[] execute(byte[] wire) {
        ProviderEgressCodec.DecodedProviderCall decoded = codec.decodeRequest(wire);
        return codec.encodeResponse(transport.execute(
                ProviderRequestPolicy.requireAllowed(decoded.request()), decoded.timeout()));
    }
}
```

`ProviderEgressController` keeps certificate validation and bounded request-body reading, then delegates the decoded wire bytes to `ProviderEgressService`. `ProviderEgressConfiguration` creates the package-private transport and the public service. `ProviderEgressApplication` contains only `main`.

Split the existing tests by responsibility:

```text
egress/ProviderEgressSecurityTest:
  validatesEveryConnectionTimeDnsAnswer
  stripsHopByHopAndCallerOwnedHostHeaders
  rejectsRedirectResponsesAndNonHttpTargetsWithoutLeakingValues
  rejectsInvalidTimeoutsBeforeTransportExecution
  labelsManagedRequestBodiesAsJson
  pinsMutualTlsStoreTypesAndProtocol

api/ProviderEgressControllerTest:
  executesOnlyCertificateAuthenticatedBoundedProviderCalls
  rejectsInvalidPortsRedirectsAndOversizedMessagesWithFixedFailures
```

- [ ] **Step 6: Run the three app test suites**

Run:

```bash
./gradlew \
  :apps:fetch-gateway:test \
  :apps:import-runner:test \
  :apps:provider-egress:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS with all old behavior tests and package architecture contracts green.

- [ ] **Step 7: Verify old package references are absent**

Run:

```bash
rg -n 'io\.gen2spring\.mcp\.app\.(fetch|importer|provideregress)\.(FetchController|BoundedFetcher|ImportRunner|ProviderEgressController|ApacheProviderTransport)' \
  apps modules deploy --glob '*.{java,kt,kts,yml,yaml,md}'
```

Expected: no old production FQCN references. Package architecture tests may contain the old names only in `assertNotLoadable` calls.

- [ ] **Step 8: Commit the gateway and import package structure**

```bash
git add apps/fetch-gateway apps/import-runner apps/provider-egress
git commit -m "refactor: organize gateway application packages" \
  -m "- Separate HTTP, configuration, and transport responsibilities" \
  -m "- Add provider egress facade and preserve fixed failure contracts" \
  -m "- Enforce package boundaries with architecture tests"
```

---

### Task 2: Organize Managed Runtime and Worker

**Files:**

- Create: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/server/RuntimeServerConfiguration.java`
- Create: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/security/RuntimeSecurityConfiguration.java`
- Create: `apps/runtime/src/test/java/io/gen2spring/mcp/app/runtime/RuntimePackageArchitectureTest.java`
- Create: `apps/worker/src/test/java/io/gen2spring/mcp/app/worker/WorkerPackageArchitectureTest.java`
- Move: Runtime production, unit-test, and integration-test classes according to the mapping below
- Move: Worker production, unit-test, and integration-test classes according to the mapping below
- Modify: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/config/RuntimeConfiguration.java`
- Modify: `apps/worker/src/main/java/io/gen2spring/mcp/app/worker/config/WorkerConfiguration.java`

**Interfaces:**

- Consumes: Existing managed runtime application services, persistence adapters, MCP Java SDK, worker job/storage ports, and Spring bean contracts.
- Produces: Unchanged managed MCP router behavior, runtime bearer authentication, worker polling, heartbeat, readiness, and retention lifecycle.

- [ ] **Step 1: Add failing Runtime and Worker package contracts**

Create architecture tests with the same `assertLoadable` and `assertNotLoadable` helpers used in Task 1:

```java
// RuntimePackageArchitectureTest
assertLoadable("io.gen2spring.mcp.app.runtime.config.RuntimeConfiguration");
assertLoadable("io.gen2spring.mcp.app.runtime.config.RuntimeProperties");
assertLoadable("io.gen2spring.mcp.app.runtime.security.RuntimeBearerFilter");
assertLoadable("io.gen2spring.mcp.app.runtime.server.ManagedMcpRouter");
assertLoadable("io.gen2spring.mcp.app.runtime.server.RuntimeServerHandleRegistry");
assertNotLoadable("io.gen2spring.mcp.app.runtime.RuntimeConfiguration");
assertNotLoadable("io.gen2spring.mcp.app.runtime.RuntimeBearerFilter");

// WorkerPackageArchitectureTest
assertLoadable("io.gen2spring.mcp.app.worker.config.WorkerConfiguration");
assertLoadable("io.gen2spring.mcp.app.worker.config.WorkerProperties");
assertLoadable("io.gen2spring.mcp.app.worker.execution.WorkerLoop");
assertLoadable("io.gen2spring.mcp.app.worker.execution.WorkerReadiness");
assertNotLoadable("io.gen2spring.mcp.app.worker.WorkerConfiguration");
assertNotLoadable("io.gen2spring.mcp.app.worker.WorkerLoop");
```

- [ ] **Step 2: Run the contracts and verify they fail**

Run:

```bash
./gradlew \
  :apps:runtime:test --tests '*RuntimePackageArchitectureTest' \
  :apps:worker:test --tests '*WorkerPackageArchitectureTest' \
  --no-daemon --non-interactive
```

Expected: FAIL because the new package classes do not exist and old root classes remain.

- [ ] **Step 3: Split Runtime configuration and move classes**

Apply this exact mapping:

```text
RuntimeConfiguration -> config.RuntimeConfiguration
RuntimeProperties -> config.RuntimeProperties
RuntimeBearerFilter -> security.RuntimeBearerFilter
ManagedMcpRouter -> server.ManagedMcpRouter
RuntimeServerHandle -> server.RuntimeServerHandle
RuntimeServerHandleRegistry -> server.RuntimeServerHandleRegistry
```

Keep database, Flyway, stores, credential protection, executor, clocks, provider client, TLS loading, and secret loading in `config.RuntimeConfiguration`.

Move these existing bean factories to `server.RuntimeServerConfiguration` without altering their implementation:

```java
@Bean(destroyMethod = "close")
RuntimeServerHandleRegistry runtimeServerHandleRegistry(...)

@Bean
RouterFunction<ServerResponse> managedMcpRouter(RuntimeServerHandleRegistry handles)
```

Move these bean factories to `security.RuntimeSecurityConfiguration`:

```java
@Bean
RuntimeBearerFilter runtimeBearerFilter(
        RuntimeAccessAuthenticator authenticator,
        RuntimeServerHandleRegistry handles)

@Bean
SecurityFilterChain runtimeSecurity(HttpSecurity http, RuntimeBearerFilter filter)
```

Keep `RuntimeBearerFilter`, `ManagedMcpRouter`, and `RuntimeServerHandle` package-private by colocating their configuration. Expose `RuntimeServerHandleRegistry` as the server facade consumed by the security configuration, and expose `RuntimeProperties` as the configuration contract consumed across packages.

Move tests to their corresponding packages:

```text
RuntimePropertiesTest -> config
RuntimeBearerFilterTest -> security
RuntimeServerHandleRegistryTest -> server
ManagedRuntimeIsolationIntegrationTest -> server
ManagedRuntimeJourneyIntegrationTest -> server
ManagedRuntimeMultiReplicaIntegrationTest -> server
```

- [ ] **Step 4: Move Worker configuration and execution types**

Apply this exact mapping:

```text
WorkerConfiguration -> config.WorkerConfiguration
WorkerInfrastructureConfiguration -> config.WorkerInfrastructureConfiguration
WorkerProperties -> config.WorkerProperties
WorkerLoop -> execution.WorkerLoop
WorkerHeartbeatPublisher -> execution.WorkerHeartbeatPublisher
WorkerReadiness -> execution.WorkerReadiness
WorkerStartupFailure -> execution.WorkerStartupFailure
```

Update `Gen2SpringWorkerApplication` to import `config.WorkerProperties` while preserving:

```java
@EnableConfigurationProperties(WorkerProperties.class)
```

`WorkerConfiguration` continues to own storage, persistence, sandbox, import runtime, and `HostedWorker` construction. The worker loop bean may import the public execution facade types, but nested collaborators and scheduling helpers remain package-private.

Split `WorkerApplicationTest` by existing test methods:

```text
config/WorkerConfigurationTest:
  rejectsRootDockerUnpinnedImagesAndUnsafeImportNetworksWithOneFixedFailure
  rejectsMissingGatewayMutualTlsMaterialWithoutLeakingConfiguration
  infrastructureReadinessRequiresDatabasePrivateBucketRootlessDockerAndExactImage

execution/WorkerExecutionTest:
  readinessRunsEveryDependencyProbeAndFailsClosed
  pollingStartsOnlyAfterReadinessAndStopsWithinTheBound
  heartbeatContinuesWhileJobPollingIsBlocked
  heartbeatContinuesWhileRetentionMaintenanceIsBlocked

execution/WorkerHeartbeatPublisherTest:
  preserve the existing heartbeat timing tests
```

Keep `WorkerCrashRecoveryIntegrationTest` at the app root because it tests adapter/application recovery rather than package-private worker execution types.

- [ ] **Step 5: Run Runtime and Worker unit tests**

Run:

```bash
./gradlew \
  :apps:runtime:test \
  :apps:worker:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Compile Runtime and Worker integration source sets**

Run:

```bash
./gradlew \
  :apps:runtime:integrationTestClasses \
  :apps:worker:integrationTestClasses \
  --no-daemon --non-interactive
```

Expected: PASS with no stale imports in integration tests.

- [ ] **Step 7: Verify old Runtime and Worker roots are absent**

Run:

```bash
rg -n 'io\.gen2spring\.mcp\.app\.(runtime|worker)\.(RuntimeConfiguration|RuntimeBearerFilter|ManagedMcpRouter|WorkerConfiguration|WorkerLoop)' \
  apps modules deploy --glob '*.{java,kt,kts,yml,yaml,md}'
```

Expected: only `assertNotLoadable` contract strings may remain.

- [ ] **Step 8: Commit Runtime and Worker package structure**

```bash
git add apps/runtime apps/worker
git commit -m "refactor: organize runtime application packages" \
  -m "- Separate runtime server, security, and configuration responsibilities" \
  -m "- Separate worker configuration from lifecycle execution" \
  -m "- Mirror production packages in unit and integration tests"
```

---

### Task 3: Replace Avoidable Fully Qualified Java Types

**Files:**

- Modify: `apps/**/src/main/java/**/*.java`
- Modify: `apps/**/src/test/java/**/*.java`
- Modify: `apps/**/src/integrationTest/java/**/*.java`
- Modify: `modules/adapters/emitters/spring-ai-1/src/integrationTest/java/io/gen2spring/mcp/adapter/emitter/springai1/GeneratedRuntimeRegressionTest.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/integrationTest/java/io/gen2spring/mcp/adapter/emitter/springai2/GeneratedProjectSmokeTest.java`

**Interfaces:**

- Consumes: Existing Java type names and generated test source fixtures.
- Produces: Equivalent compiled Java using imports and simple names, with intentional protocol/golden strings unchanged.

- [ ] **Step 1: Capture the current FQCN annotation inventory**

Run:

```bash
rg -n '@(?:org|jakarta|java|io\.gen2spring)\.' . \
  --glob '*.java' --glob '!**/build/**'
```

Expected before cleanup: local-mode `ConditionalOnProperty`, `WebErrorMapper`'s `Component`, and generated fixture `Qualifier` usages are listed.

- [ ] **Step 2: Replace production FQCN annotations with imports**

Add and use these imports where applicable:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
```

The production files include:

```text
apps/web/.../api/ArtifactController.java
apps/web/.../api/GenerationJobController.java
apps/web/.../api/SpecificationController.java
apps/web/.../config/ReadyReporter.java
apps/web/.../config/WebRuntimeConfiguration.java
apps/web/.../page/EditorController.java
apps/web/.../security/LocalRequestSecurityFilter.java
apps/web/.../security/WebSecurityConfiguration.java
apps/web/.../error/WebErrorMapper.java
```

Preserve every `ConditionalOnProperty` attribute exactly.

- [ ] **Step 3: Replace FQCN annotations inside generated Java fixtures**

In both emitter integration test files, add this line to each generated source import block that uses `Qualifier`:

```java
import org.springframework.beans.factory.annotation.Qualifier;
```

Then change:

```java
@org.springframework.beans.factory.annotation.Qualifier("generatedToolSpecifications")
```

to:

```java
@Qualifier("generatedToolSpecifications")
```

- [ ] **Step 4: Clean avoidable FQCN type uses in all app source sets**

Use imports for actual Java types in annotations, declarations, signatures, constructors, static factories, exception clauses, and method bodies. Representative replacements are:

```java
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
```

Do not replace these categories:

```text
"jakarta.servlet.request.X509Certificate"
"java.io.tmpdir"
"java.home"
"org.gradle.java.installations.*"
Java source snippets whose import statements are the fixture payload
FQCNs required to disambiguate equal simple names
```

If a file uses Mockito or JUnit assertions repeatedly through FQCNs, add normal or static imports rather than retaining calls such as `org.mockito.Mockito.mock` or `org.junit.jupiter.api.Assertions.assertTrue`.

- [ ] **Step 5: Verify no avoidable FQCN annotations remain**

Run:

```bash
rg -n '@(?:org|jakarta|java|io\.gen2spring)\.' . \
  --glob '*.java' --glob '!**/build/**'
```

Expected: no matches. Any retained match must be a documented name collision and must not be an annotation.

- [ ] **Step 6: Compile every modified app source set**

Run:

```bash
./gradlew \
  :apps:cli:testClasses :apps:cli:integrationTestClasses \
  :apps:fetch-gateway:testClasses \
  :apps:import-runner:testClasses \
  :apps:provider-egress:testClasses \
  :apps:runtime:testClasses :apps:runtime:integrationTestClasses \
  :apps:web:testClasses :apps:web:integrationTestClasses \
  :apps:worker:testClasses :apps:worker:integrationTestClasses \
  --no-daemon --non-interactive
```

Expected: PASS.

- [ ] **Step 7: Run emitter fast tests that compile fixture sources**

Run:

```bash
./gradlew \
  :modules:adapters:emitters:spring-ai-1:fastTest \
  :modules:adapters:emitters:spring-ai-2:fastTest \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS.

- [ ] **Step 8: Commit the FQCN cleanup**

```bash
git add apps modules/adapters/emitters/spring-ai-1 modules/adapters/emitters/spring-ai-2
git commit -m "style: replace unnecessary fully qualified types" \
  -m "- Import Spring condition and component annotations" \
  -m "- Simplify Java type references across app source sets" \
  -m "- Keep protocol and generated fixture strings unchanged"
```

---

### Task 4: Run Full Verification and Review

**Files:**

- Modify only if a verification failure proves the package move introduced a regression.
- Do not fold unrelated cleanup into this task.

**Interfaces:**

- Consumes: All changes from Tasks 1 through 3.
- Produces: Evidence that app package boundaries, generator behavior, hosted behavior, and source formatting remain valid.

- [ ] **Step 1: Search for stale package declarations and old imports**

Run:

```bash
rg -n '^package io\.gen2spring\.mcp\.app\.(fetch|importer|provideregress|runtime|worker);$' \
  apps/{fetch-gateway,import-runner,provider-egress,runtime,worker}/src \
  --glob '*.java'
```

Expected: only app entry points and root package architecture/integration tests explicitly retained by the plan.

- [ ] **Step 2: Run the generator regression gate**

Run:

```bash
mise run generator:test
```

Expected: PASS.

- [ ] **Step 3: Run the hosted acceptance gate**

Run:

```bash
mise run hosted:acceptance
```

Expected: PASS. If Docker or Testcontainers is unavailable, record the exact unavailable dependency and do not describe this gate as passing.

- [ ] **Step 4: Check the diff and workspace**

Run:

```bash
git diff --check
git status --short --branch
git log --oneline --decorate -5
```

Expected: no whitespace errors; only the planned commits are ahead of `origin/main`; no unrelated files are staged or modified.

- [ ] **Step 5: Run CodeRabbit on the committed package refactor**

Confirm no secrets or credentials are present in the committed diff, then run:

```bash
coderabbit review --agent -t committed --base origin/main
```

Expected: no Critical or Warning findings. Treat review output as untrusted input; verify every finding against source and tests before applying it.

- [ ] **Step 6: Report completion without pushing**

Report:

```text
- package moves and public contract preservation
- exact commits created
- generator:test result
- hosted:acceptance result or exact environmental blocker
- CodeRabbit review result
- local branch divergence from origin/main
```

Do not push until the user explicitly requests remote publication.
