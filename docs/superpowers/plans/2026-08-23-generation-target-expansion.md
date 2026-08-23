# Maven, WebFlux, Async Generation Target Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate and fully validate Maven MVC Sync and Spring AI 2 WebFlux Async Streamable HTTP projects alongside the existing Gradle MVC Sync targets.

**Architecture:** Keep Spring AI family and programming-model source emission separate from Gradle/Maven project scaffolding. Route generated builds through build-tool drivers and reuse one application/MCP validation engine; expose only validated profiles while serving one canonical compatibility notice for the deferred Spring AI 1 WebFlux Async targets.

**Tech Stack:** Java 21 generator, generated Java 17/21, Gradle 9.6.1 Kotlin DSL, Maven 3.9.16, Maven Wrapper 3.3.4, Spring Boot 3.5.16/4.1.0, Spring AI 1.1.8/2.0.0, Spring MVC, Spring WebFlux, Reactor Netty, Thymeleaf, JUnit 5

**Spec:** `docs/superpowers/specs/2026-08-23-generation-target-expansion-design.md`

## Global Constraints

- Preserve the four existing Gradle MVC profile IDs exactly.
- Register only 12 validated profiles; do not register the four Spring AI 1.1 WebFlux Async targets.
- Keep Spring AI 1.1.8 on Spring Boot 3.5.16 and Spring AI 2.0.0 on Spring Boot 4.1.0.
- Pin Gradle to 9.6.1, Maven to 3.9.16, and Maven Wrapper to 3.3.4.
- Use Maven Wrapper `only-script`; do not add `maven-wrapper.jar`.
- Pin Maven distribution SHA-256 to `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`.
- Do not use a private MCP SDK fork, dependency shadowing, or copied Spring AI transport source.
- WebFlux Async generated code must not use `RestClient`, `Future.get`, `Thread.sleep`, or `.block()`.
- Preserve the existing MCP Tool schema, provider error payload, normalization, retry, pagination, secret, and telemetry contracts.
- Keep nested generated-project builds out of required PR CI.
- Do not mark Maven, WebFlux, or Async complete in the PRD until the specified acceptance matrix passes.

---

## File and Responsibility Map

### Domain and bootstrap

- `modules/domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityProfile.java`: exact target and build-tool metadata.
- `modules/domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityCatalog.java`: supported registry plus canonical notices.
- `modules/domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityNotice.java`: immutable deferred-target explanation.
- `modules/bootstrap/src/main/java/io/gen2spring/mcp/bootstrap/GeneratorRuntime.java`: compose the catalog, generators, and validator.

### Emitter support

- `modules/adapters/emitters/support/src/main/java/io/gen2spring/mcp/adapter/emitter/support/BuildProjectScaffold.java`: build-tool-neutral scaffold port.
- `modules/adapters/emitters/support/src/main/java/io/gen2spring/mcp/adapter/emitter/support/ProjectScaffoldModel.java`: immutable scaffold input.
- `modules/adapters/emitters/support/src/main/java/io/gen2spring/mcp/adapter/emitter/support/BuildProjectScaffoldRegistry.java`: exact build-tool selection.
- `modules/adapters/emitters/support/src/main/java/io/gen2spring/mcp/adapter/emitter/support/GradleKotlinProjectScaffold.java`: current Gradle output.
- `modules/adapters/emitters/support/src/main/java/io/gen2spring/mcp/adapter/emitter/support/MavenProjectScaffold.java`: Maven POM, wrapper, README, and Docker output.

### Validation

- `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/McpProjectValidator.java`: validation facade.
- `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/BuildToolDriver.java`: safe build and artifact contract.
- `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/GradleBuildToolDriver.java`: Gradle wrapper/build behavior.
- `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/MavenBuildToolDriver.java`: Maven wrapper/build behavior.
- `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/ApplicationRuntimeValidator.java`: boot and readiness lifecycle.
- `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/ServerEndpointDetector.java`: bounded Tomcat/Netty endpoint discovery.

### Spring AI 2 reactive emission

- `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ProgrammingModelSourceRenderer.java`: Sync/Async strategy.
- `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/AsyncToolClassRenderer.java`: reactive generated Tool methods.
- `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/AsyncToolSpecificationRenderer.java`: explicit async MCP specifications.
- `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveRuntimeSourceRenderer.java`: WebClient runtime source set.
- Focused reactive renderer classes split request execution, retry/pagination, and telemetry by generated-file responsibility.

### Web and delivery

- `apps/web/src/main/java/io/gen2spring/mcp/app/web/api/GenerationPreviewPresenter.java`: generic build metadata.
- `apps/web/src/main/java/io/gen2spring/mcp/app/web/api/ProfileController.java`: profiles plus canonical notices.
- `apps/web/src/main/resources/templates/editor.html`: accessible help trigger and content container.
- `apps/web/src/main/resources/static/app.js`: profile labels and notice rendering.
- `apps/web/src/main/resources/static/styles.css`: help popover layout.
- `mise.toml`: fast and acceptance tasks.
- `.github/workflows/ci.yml`: fast contract checks.
- `.github/workflows/generation-acceptance.yml`: manual Linux/full and Windows/representative acceptance.

---

### Task 1: Introduce Build Metadata and the Compatibility Catalog

**Files:**
- Modify: `modules/domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityProfile.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/profile/BuildToolchain.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityNotice.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityCatalog.java`
- Modify: `modules/domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistry.java`
- Modify: `modules/domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileTest.java`
- Create: `modules/domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityCatalogTest.java`
- Modify: `modules/bootstrap/src/main/java/io/gen2spring/mcp/bootstrap/GeneratorRuntime.java`
- Modify: `modules/bootstrap/src/test/java/io/gen2spring/mcp/bootstrap/GeneratorRuntimeTest.java`

**Interfaces:**
- Produces: `BuildToolchain(String distributionVersion, String wrapperVersion)`.
- Produces: `CompatibilityCatalog.profiles()` and `CompatibilityCatalog.notices()`.
- Preserves: `GeneratorRuntime.profiles()` as a delegating compatibility accessor.

- [ ] **Step 1: Write failing catalog and build metadata tests**

```java
var catalog = CompatibilityCatalog.defaults();
assertEquals(4, catalog.profiles().profiles().size());
assertEquals("9.6.1", catalog.profiles().find(
        "spring-ai-2.0-java21-mvc-streamable").orElseThrow()
        .buildToolchain().distributionVersion());
assertEquals(List.of("SPRING_AI_1_WEBFLUX_ASYNC_DEFERRED"),
        catalog.notices().stream().map(CompatibilityNotice::code).toList());
```

- [ ] **Step 2: Run the focused tests and confirm the missing types fail compilation**

Run: `./gradlew :modules:domain:test :modules:bootstrap:test --tests '*CompatibilityCatalogTest' --tests '*GeneratorRuntimeTest' --no-daemon --non-interactive`

Expected: FAIL because `CompatibilityCatalog`, `CompatibilityNotice`, and `BuildToolchain` do not exist.

- [ ] **Step 3: Add immutable metadata and catalog types**

```java
public record BuildToolchain(String distributionVersion, String wrapperVersion) {}

public record CompatibilityNotice(
        String code,
        String severity,
        String summary,
        String reason,
        String referenceUrl,
        AffectedTarget affectedTarget) {
    public record AffectedTarget(
            String springAiFamily, String webStack, String programmingModel, String transport) {}
}
```

Make `CompatibilityCatalog.defaults()` own the current four-profile registry and the one immutable deferred notice. Make `CompatibilityProfileRegistry.defaults()` delegate to the catalog without constructing a second default list.

- [ ] **Step 4: Refactor `GeneratorRuntime` to own the catalog while preserving `profiles()` consumers**

```java
public CompatibilityProfileRegistry profiles() {
    return compatibilityCatalog.profiles();
}
```

- [ ] **Step 5: Run domain and bootstrap tests**

Run: `./gradlew :modules:domain:test :modules:bootstrap:test --no-daemon --non-interactive`

Expected: PASS with four active profiles and one deferred-target notice.

- [ ] **Step 6: Commit the catalog foundation**

```bash
git add modules/domain modules/bootstrap
git commit -m "refactor: add compatibility catalog metadata"
```

### Task 2: Separate Fast Tests from Nested Generated Builds

**Files:**
- Modify: `modules/adapters/emitters/spring-ai-1/build.gradle.kts`
- Modify: `modules/adapters/emitters/spring-ai-2/build.gradle.kts`
- Move: `modules/adapters/emitters/spring-ai-1/src/test/java/io/gen2spring/mcp/adapter/emitter/springai1/GeneratedProjectSmokeTest.java` to the same package under `src/integrationTest/java`
- Move: `modules/adapters/emitters/spring-ai-1/src/test/java/io/gen2spring/mcp/adapter/emitter/springai1/GeneratedRuntimeRegressionTest.java` to the same package under `src/integrationTest/java`
- Move: `modules/adapters/emitters/spring-ai-2/src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/GeneratedProjectSmokeTest.java` to the same package under `src/integrationTest/java`
- Create: `modules/adapters/emitters/spring-ai-1/src/test/java/io/gen2spring/mcp/adapter/emitter/springai1/GeneratedSourceContractTest.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/GeneratedSourceContractTest.java`

**Interfaces:**
- Produces: `integrationTest` suites that own all subprocess builds.
- Preserves: `test` as a source-rendering-only suite suitable for required CI.

- [ ] **Step 1: Add a failing fast-suite guard**

```java
@Test
void unitTestSourcesDoNotLaunchGeneratedBuilds() throws Exception {
    String tests = Files.readString(Path.of("src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/GeneratedSourceContractTest.java"));
    assertFalse(tests.contains("ProcessBuilder"));
}
```

- [ ] **Step 2: Register JUnit `integrationTest` suites in both emitter build files**

Use `JvmTestSuite`, depend on the current project and test libraries, and configure `shouldRunAfter(test)`. Do not attach these suites to required PR CI.

- [ ] **Step 3: Move subprocess-based tests to `src/integrationTest` and retain fast source assertions in `src/test`**

Keep package names unchanged. Confirm every remaining `src/test` file is free of `ProcessBuilder`, wrapper execution, and generated `gradlew`/`mvnw` invocation.

- [ ] **Step 4: Run only fast emitter tests**

Run: `./gradlew :modules:adapters:emitters:spring-ai-1:test :modules:adapters:emitters:spring-ai-2:test --no-daemon --non-interactive --rerun-tasks`

Expected: PASS without nested Gradle or Maven output.

- [ ] **Step 5: Run one moved integration class directly**

Run: `./gradlew :modules:adapters:emitters:spring-ai-2:integrationTest --tests '*GeneratedProjectSmokeTest.generatedProjectBuilds' --no-daemon --non-interactive`

Expected: PASS and show one generated-project build.

- [ ] **Step 6: Commit the test boundary**

```bash
git add modules/adapters/emitters/spring-ai-1 modules/adapters/emitters/spring-ai-2
git commit -m "test: separate generated build acceptance suites"
```

### Task 3: Extract the Gradle Project Scaffold without Output Drift

**Files:**
- Create: `modules/adapters/emitters/support/src/main/java/io/gen2spring/mcp/adapter/emitter/support/BuildProjectScaffold.java`
- Create: `modules/adapters/emitters/support/src/main/java/io/gen2spring/mcp/adapter/emitter/support/ProjectScaffoldModel.java`
- Create: `modules/adapters/emitters/support/src/main/java/io/gen2spring/mcp/adapter/emitter/support/BuildProjectScaffoldRegistry.java`
- Create: `modules/adapters/emitters/support/src/main/java/io/gen2spring/mcp/adapter/emitter/support/GradleKotlinProjectScaffold.java`
- Create: `modules/adapters/emitters/support/src/test/java/io/gen2spring/mcp/adapter/emitter/support/GradleKotlinProjectScaffoldTest.java`
- Modify: both `SpringAi1ProjectGenerator.java` and `SpringAi2ProjectGenerator.java`
- Modify: both family `ProjectFileRenderer.java` files

**Interfaces:**
- Produces: `Map<String, byte[]> BuildProjectScaffold.render(ProjectScaffoldModel model)`.
- Produces: `BuildProjectScaffoldRegistry.require(String buildTool)`.
- Consumes: family-specific dependency and application metadata in `ProjectScaffoldModel`.

- [ ] **Step 1: Capture the exact existing Gradle file set and representative checksums in failing characterization tests**

```java
assertEquals(Set.of(
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
        "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties"), scaffoldBuildFiles(files));
assertArrayEquals(before.get("build.gradle.kts"), after.get("build.gradle.kts"));
```

- [ ] **Step 2: Add the scaffold interface, immutable model, and exact registry lookup**

```java
public interface BuildProjectScaffold {
    Map<String, byte[]> render(ProjectScaffoldModel model);
}

public record ProjectScaffoldModel(
        String groupId,
        String artifactId,
        String packageName,
        String applicationClassName,
        CompatibilityProfile profile,
        List<Dependency> dependencies,
        String applicationYaml,
        String projectDescription) {
    public record Dependency(String groupId, String artifactId, Scope scope) {}
    public enum Scope { IMPLEMENTATION, RUNTIME_ONLY, TEST_IMPLEMENTATION }
}
```

Reject null, blank, and unknown build tools before emitting files.

- [ ] **Step 3: Move Gradle build, wrapper, README command, Docker artifact path, and ignore rendering into `GradleKotlinProjectScaffold`**

Family renderers provide dependency coordinates and Spring-specific properties; they no longer load wrapper assets or write Gradle paths directly.

- [ ] **Step 4: Compose the scaffold from both project generators**

Merge scaffold files and Tool/runtime files with duplicate-path rejection. Do not silently overwrite a file from either side.

- [ ] **Step 5: Run support and both family fast tests**

Run: `./gradlew :modules:adapters:emitters:support:test :modules:adapters:emitters:spring-ai-1:test :modules:adapters:emitters:spring-ai-2:test --no-daemon --non-interactive`

Expected: PASS and byte-identical Gradle output for the existing four profiles.

- [ ] **Step 6: Commit the scaffold extraction**

```bash
git add modules/adapters/emitters
git commit -m "refactor: separate Gradle project scaffolding"
```

### Task 4: Implement the Maven Scaffold

**Files:**
- Create: `modules/adapters/emitters/support/src/main/java/io/gen2spring/mcp/adapter/emitter/support/MavenProjectScaffold.java`
- Create: `modules/adapters/emitters/support/src/main/resources/wrapper/maven/mvnw`
- Create: `modules/adapters/emitters/support/src/main/resources/wrapper/maven/mvnw.cmd`
- Create: `modules/adapters/emitters/support/src/main/resources/wrapper/maven/maven-wrapper.properties`
- Create: `modules/adapters/emitters/support/src/test/java/io/gen2spring/mcp/adapter/emitter/support/MavenProjectScaffoldTest.java`
- Modify: `modules/adapters/emitters/support/src/main/java/io/gen2spring/mcp/adapter/emitter/support/BuildProjectScaffoldRegistry.java`
- Modify: family project descriptor/rendering tests

**Interfaces:**
- Produces: Maven files for `buildTool=MAVEN`.
- Preserves: identical generated Java source for matching Gradle and Maven target profiles.

- [ ] **Step 1: Write failing exact-file and checksum tests**

```java
assertEquals(Set.of("pom.xml", "mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties"),
        scaffoldBuildFiles(files));
assertEquals("32ea207bd59f3a2f60392b6766b280dfbb17e60ab00663fb31bcd5015220c633",
        sha256(files.get("mvnw")));
assertEquals("b7a0db794b62ed5067a2c074e19c94d61af65c42eb32cb1408f0d589e192f4bf",
        sha256(files.get("mvnw.cmd")));
assertTrue(properties.contains("distributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce"));
```

- [ ] **Step 2: Add verified Maven Wrapper 3.3.4 `only-script` assets**

Use the Apache Maven Wrapper tag `maven-wrapper-3.3.4`. Store LF-normalized `mvnw`, the unmodified `mvnw.cmd`, and properties pointing to Maven 3.9.16 on Maven Central.

- [ ] **Step 3: Render deterministic `pom.xml`**

Set compiler release from the profile, import the exact Spring AI BOM, use the matching WebMVC starter for this milestone, configure JUnit/Surefire, configure the Spring Boot repackage goal, and set `<finalName>${project.artifactId}</finalName>`.

- [ ] **Step 4: Render Maven-specific README, Docker build command, target artifact path, and ignore entries**

Use `./mvnw test package` and `target/<artifactId>.jar`. Keep non-build instructions equal to the Gradle variant.

- [ ] **Step 5: Verify scaffold determinism and Java-source parity**

Run: `./gradlew :modules:adapters:emitters:support:test :modules:adapters:emitters:spring-ai-1:test :modules:adapters:emitters:spring-ai-2:test --no-daemon --non-interactive --rerun-tasks`

Expected: PASS; repeated Maven rendering has identical paths and bytes.

- [ ] **Step 6: Commit Maven scaffolding**

```bash
git add modules/adapters/emitters
git commit -m "feat: generate deterministic Maven projects"
```

### Task 5: Split the Validator and Add Maven Build Execution

**Files:**
- Create: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/BuildToolDriver.java`
- Create: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/BuildToolDriverRegistry.java`
- Create: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/GradleBuildToolDriver.java`
- Create: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/MavenBuildToolDriver.java`
- Create: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/McpProjectValidator.java`
- Create: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/ApplicationRuntimeValidator.java`
- Create: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/VerifiedWrapper.java`
- Modify: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/GradleMcpProjectValidator.java`
- Modify: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/ValidationHostPlatform.java`
- Create: `modules/adapters/validation/src/test/java/io/gen2spring/mcp/adapter/validation/BuildToolDriverRegistryTest.java`
- Create: `modules/adapters/validation/src/test/java/io/gen2spring/mcp/adapter/validation/MavenBuildToolDriverTest.java`
- Modify: `modules/adapters/validation/src/test/java/io/gen2spring/mcp/adapter/validation/GradleMcpProjectValidatorTest.java`
- Modify: `modules/adapters/validation/src/test/java/io/gen2spring/mcp/adapter/validation/ValidationHostPlatformTest.java`
- Create/Modify: focused validator tests for Gradle parity, Maven commands, wrapper pinning, and artifacts
- Modify: `modules/bootstrap/src/main/java/io/gen2spring/mcp/bootstrap/GeneratorRuntime.java`

**Interfaces:**
- Produces: `BuildToolDriver.Result BuildToolDriver.build(BuildToolDriver.Request request)`.
- Produces: `Path BuildToolDriver.resolveArtifact(Path root, String artifactId)`.
- Produces: `McpProjectValidator` as the only production `GeneratedProjectValidator`.

- [ ] **Step 1: Write failing driver contract tests**

```java
assertEquals(List.of("test", "package", "--batch-mode", "--no-transfer-progress"),
        maven.arguments());
assertEquals(Path.of("target/weather-mcp.jar"),
        maven.relativeArtifact("weather-mcp"));
assertEquals(Path.of("build/libs/weather-mcp.jar"),
        gradle.relativeArtifact("weather-mcp"));
```

Define the driver contract without external placeholder types:

```java
interface BuildToolDriver {
    Result build(Request request);
    Path resolveArtifact(Path root, String artifactId);

    record Request(Path root, CompatibilityProfile profile, Path javaHome) {}
    record Result(int exitCode, boolean timedOut, boolean processAlive, String safeSummary) {}
}
```

Add Windows assertions that wrapper names are `mvnw.cmd`/`gradlew.bat` and arguments are rejected when they contain shell metacharacters.

- [ ] **Step 2: Extract generic verified-wrapper pinning and host command construction**

Change `ValidationHostPlatform` from Gradle-specific command construction to a safe wrapper command accepting an exact argument list. Preserve stable `cmd.exe`, wrapper identity, physical path, and owner-executable checks.

- [ ] **Step 3: Move existing Gradle build and artifact behavior into `GradleBuildToolDriver`**

Run the complete existing `GradleMcpProjectValidatorTest` suite after each extraction step. Keep safe summaries and stage statuses byte-compatible.

- [ ] **Step 4: Implement Maven build and exact artifact resolution**

Use the target Java home as `JAVA_HOME`, pin `mvnw` or `mvnw.cmd`, execute batch/no-transfer-progress `test package`, and accept exactly one regular non-symlink `target/<artifactId>.jar`.

- [ ] **Step 5: Compose shared application and MCP validation behind `McpProjectValidator`**

Select the build driver from `request.profile().target().buildTool()`. Keep COMPILE, APPLICATION_CONTEXT, MCP_INITIALIZE, MCP_TOOLS_LIST, MCP_TOOL_CALL order and skip semantics unchanged.

- [ ] **Step 6: Run validation and bootstrap tests**

Run: `./gradlew :modules:adapters:validation:test :modules:bootstrap:test --no-daemon --non-interactive --rerun-tasks`

Expected: PASS for POSIX and simulated Windows command tests.

- [ ] **Step 7: Commit validator drivers**

```bash
git add modules/adapters/validation modules/bootstrap
git commit -m "feat: validate Gradle and Maven projects"
```

### Task 6: Activate and Accept the Four Maven MVC Profiles

**Files:**
- Modify: `modules/domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityCatalog.java`
- Modify: `modules/domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistryTest.java`
- Modify: `modules/adapters/filesystem/src/main/java/io/gen2spring/mcp/adapter/filesystem/GenerationManifestWriter.java`
- Modify: `modules/adapters/filesystem/src/test/java/io/gen2spring/mcp/adapter/filesystem/GenerationPipelineTest.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/api/GenerationPreviewPresenter.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`
- Modify: `apps/cli/src/test/java/io/gen2spring/mcp/app/cli/command/CliApplicationTest.java`
- Modify: `apps/cli/src/test/java/io/gen2spring/mcp/app/cli/command/GenerationConfigurationReaderTest.java`
- Modify: `apps/cli/src/test/java/io/gen2spring/mcp/app/cli/InstalledCliTest.java`
- Modify: `apps/cli/src/integrationTest/java/io/gen2spring/mcp/app/cli/P1GenerationIntegrationTest.java`
- Add: Maven MVC profile generation configurations under CLI integration resources

**Interfaces:**
- Produces: eight active profiles total.
- Produces: canonical `buildTool` manifest object with Gradle compatibility alias.

- [ ] **Step 1: Write failing exact eight-profile tests**

```java
assertEquals(List.of(
        "spring-ai-1.1-java17-maven-mvc-streamable",
        "spring-ai-1.1-java17-mvc-streamable",
        "spring-ai-1.1-java21-maven-mvc-streamable",
        "spring-ai-1.1-java21-mvc-streamable",
        "spring-ai-2.0-java17-maven-mvc-streamable",
        "spring-ai-2.0-java17-mvc-streamable",
        "spring-ai-2.0-java21-maven-mvc-streamable",
        "spring-ai-2.0-java21-mvc-streamable"), profileIds());
```

- [ ] **Step 2: Add the exact four Maven MVC profiles**

Use the existing Spring AI/Boot/container/template/runtime values and `BuildToolchain("3.9.16", "3.3.4")`. Keep the generator module keyed only by Spring AI family.

- [ ] **Step 3: Add generic build metadata to manifest, preview, CLI, and API**

Emit `type`, `distributionVersion`, and `wrapperVersion`. Emit legacy `gradleVersion` only for Gradle profiles.

- [ ] **Step 4: Add Maven profile end-to-end fixtures and assertions**

Assert `pom.xml`, wrapper files, manifest build metadata, `target` Docker path, archive determinism, and absence of Gradle files.

- [ ] **Step 5: Run the four Maven MVC compile/MCP journeys**

Run:

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
./gradlew :apps:cli:integrationTest --tests '*P1GenerationIntegrationTest*maven*' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: all four Maven profiles finish `VALIDATED` and produce ZIP artifacts.

- [ ] **Step 6: Run all fast generator tests**

Run: `./gradlew :modules:domain:test :modules:application:test :modules:adapters:emitters:support:test :modules:adapters:emitters:spring-ai-1:test :modules:adapters:emitters:spring-ai-2:test :modules:adapters:validation:test :modules:bootstrap:test :apps:cli:test --no-daemon --non-interactive`

Expected: PASS with eight active profiles.

- [ ] **Step 7: Commit the Maven MVC vertical slice**

```bash
git add modules apps/cli
git commit -m "feat: support Maven MVC generation profiles"
```

### Task 7: Establish the Spring AI 2 Programming-Model Boundary

**Files:**
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ProgrammingModelSourceRenderer.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ProgrammingModelRenderRequest.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/SyncProgrammingModelSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ToolClassRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ToolCallbackConfigurationRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/RuntimeSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/JavaSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/JavaSourceRendererTest.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/ProgrammingModelSourceRendererTest.java`

**Interfaces:**
- Produces: `Map<String, String> ProgrammingModelSourceRenderer.render(ProgrammingModelRenderRequest request)`.
- Preserves: exact Sync source output for existing Spring AI 2 MVC profiles.

- [ ] **Step 1: Write failing Sync byte-characterization tests**

Capture the generated path set and SHA-256 values for a representative Java 17 and Java 21 Spring AI 2 MVC project before the refactor.

- [ ] **Step 2: Introduce explicit Sync/Async renderer selection**

```java
ProgrammingModelSourceRenderer renderer = switch (profile.target().programmingModel()) {
    case "SYNC" -> syncRenderer;
    case "ASYNC" -> asyncRenderer;
    default -> throw unsupportedTarget();
};
```

`ProgrammingModelRenderRequest` contains the validated `GenerationContext`, package name/path, domain class, ordered
Tool list, explicit Tool schemas, and the existing typed-output/retry/pagination feature flags. Both strategies consume
the same immutable request.

Do not create the Async implementation in this task; unsupported profiles remain outside the catalog.

- [ ] **Step 3: Split existing Sync renderers only where a generated-file responsibility is already distinct**

Keep input/output/metadata renderers shared. Keep Sync Tool methods, Sync specifications, blocking executor, retry, pagination, and telemetry together under the Sync strategy.

- [ ] **Step 4: Run Spring AI 2 fast and moved integration characterization tests**

Run: `./gradlew :modules:adapters:emitters:spring-ai-2:test :modules:adapters:emitters:spring-ai-2:integrationTest --tests '*GeneratedProjectSmokeTest.generatedProjectBuilds' --no-daemon --non-interactive`

Expected: PASS with no Sync source checksum drift.

- [ ] **Step 5: Commit the programming-model boundary**

```bash
git add modules/adapters/emitters/spring-ai-2
git commit -m "refactor: separate Spring AI programming models"
```

### Task 8: Generate the Reactive Provider HTTP Runtime

**Files:**
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveRuntimeSourceRenderer.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveExecutorSourceRenderer.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveHttpClientSourceRenderer.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveResponseSourceRenderer.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveRuntimeConfigurationRenderer.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveRuntimeSourceRendererTest.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/integrationTest/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveGeneratedRuntimeTest.java`

**Interfaces:**
- Produces generated `ReactiveOpenApiOperationExecutor` behavior through the existing `OpenApiOperationExecutor` class name.
- Consumes existing generated `OperationDefinition`, normalization policies, provider properties, and telemetry contracts.

- [ ] **Step 1: Write failing source-contract tests**

```java
assertTrue(executor.contains("WebClient"));
assertTrue(executor.contains("ConnectionProvider"));
assertTrue(executor.contains("Mono<"));
assertFalse(executor.contains("RestClient"));
assertFalse(executor.contains(".block("));
assertFalse(executor.contains("Future<"));
```

- [ ] **Step 2: Render bounded Reactor Netty and WebClient configuration**

Configure max connections, pending acquire count, connect timeout, response timeout, and codec max-in-memory bytes from the existing provider properties. Build all URIs and headers with the existing secret/redaction rules.

- [ ] **Step 3: Render one-attempt reactive execution and response mapping**

Return `Mono<OperationOutcome>`, map bounded response bytes and media type to the existing normalizer, and preserve the current ProviderError categories.

- [ ] **Step 4: Add generated runtime tests for success and bounded failures**

Cover GET/query/header/body binding, 4xx/5xx, connect/read/total timeout, response overflow, malformed JSON, and secret redaction. Use Reactor `StepVerifier` in generated tests and never call `.block()`.

- [ ] **Step 5: Compile and run the generated reactive runtime test project**

Run: `./gradlew :modules:adapters:emitters:spring-ai-2:integrationTest --tests '*ReactiveGeneratedRuntimeTest*' --no-daemon --non-interactive`

Expected: PASS with WebClient/Reactor Netty and no blocking API scan failures.

- [ ] **Step 6: Commit reactive HTTP execution**

```bash
git add modules/adapters/emitters/spring-ai-2
git commit -m "feat: generate reactive provider execution runtime"
```

### Task 9: Add Reactive Retry, Pagination, Cancellation, and Telemetry

**Files:**
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveExecutorSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveHttpClientSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveResponseSourceRenderer.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveRetrySourceRenderer.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactivePaginationSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/integrationTest/java/io/gen2spring/mcp/adapter/emitter/springai2/ReactiveGeneratedRuntimeTest.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/RuntimeTelemetryRenderer.java`

**Interfaces:**
- Produces: bounded sequential retry and pagination as `Mono<OperationOutcome>`.
- Produces: one terminal Tool telemetry outcome and per-attempt provider observations.

- [ ] **Step 1: Write failing `StepVerifier` tests**

```java
StepVerifier.create(executor.execute(RETRY_OPERATION, arguments))
        .expectNextMatches(result -> result.path("ok").booleanValue())
        .verifyComplete();
assertEquals(3, upstream.requestCount());
assertEquals(1, telemetry.toolTerminalCount());
```

Add separate tests for `Retry-After`, non-idempotent no-retry, max pages/items, repeated next token, cancellation during response, and cancellation during retry delay.

- [ ] **Step 2: Implement sequential retry with bounded `Mono.defer` recursion**

Carry attempt number and remaining total deadline explicitly. Use `Mono.delay` for backoff and cap it by the remaining total timeout.

- [ ] **Step 3: Implement sequential pagination with immutable page state**

Carry page count, item count, and seen next tokens. Stop at terminal next token; map limit or repeated-token violations to the existing pagination error category.

- [ ] **Step 4: Propagate cancellation and Reactor context**

Cancel the current HTTP subscription and pending delay. Record cancellation once without converting it to an MCP ProviderError. Carry trace/observation state through Reactor context rather than generated ThreadLocal state.

- [ ] **Step 5: Run reactive runtime tests and source scans**

Run: `./gradlew :modules:adapters:emitters:spring-ai-2:test :modules:adapters:emitters:spring-ai-2:integrationTest --tests '*ReactiveGeneratedRuntimeTest*' --no-daemon --non-interactive --rerun-tasks`

Expected: PASS for retry, pagination, timeout, cancellation, and telemetry terminal counts.

- [ ] **Step 6: Commit reactive policies**

```bash
git add modules/adapters/emitters/spring-ai-2
git commit -m "feat: add reactive retry and pagination policies"
```

### Task 10: Generate Async MCP Specifications and WebFlux Projects

**Files:**
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/AsyncToolClassRenderer.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/AsyncToolSpecificationRenderer.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/AsyncProgrammingModelSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/JavaSourceRenderer.java`
- Modify: `modules/adapters/emitters/spring-ai-2/src/main/java/io/gen2spring/mcp/adapter/emitter/springai2/ProjectFileRenderer.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/test/java/io/gen2spring/mcp/adapter/emitter/springai2/AsyncProgrammingModelSourceRendererTest.java`
- Create: `modules/adapters/emitters/spring-ai-2/src/integrationTest/java/io/gen2spring/mcp/adapter/emitter/springai2/AsyncGeneratedProjectTest.java`

**Interfaces:**
- Produces: generated Tool methods returning `Mono<JsonNode>` or `Mono<ResultDto>`.
- Produces: `List<McpServerFeatures.AsyncToolSpecification>`.
- Produces: WebFlux starter with `protocol=STREAMABLE` and `type=ASYNC`.

- [ ] **Step 1: Write failing async source tests**

```java
assertTrue(toolSource.contains("Mono<JsonNode>"));
assertTrue(specSource.contains("McpServerFeatures.AsyncToolSpecification"));
assertFalse(specSource.contains("toAsyncToolSpecification"));
assertTrue(applicationYaml.contains("type: ASYNC"));
assertTrue(applicationYaml.contains("protocol: STREAMABLE"));
```

- [ ] **Step 2: Generate typed async Tool methods and immutable raw-argument conversion**

Convert and validate raw arguments before entering the reactive provider chain. Preserve omitted versus explicit-null semantics without carrying `ToolArgumentContext` across reactive signals.

- [ ] **Step 3: Generate explicit `AsyncToolSpecification` handlers**

Reuse the exact input schema, safe ProviderError result, fatal error propagation, and serialization contract from Sync specifications. Return `Mono<McpSchema.CallToolResult>` directly.

- [ ] **Step 4: Render WebFlux/Async dependency and configuration requirements for both scaffolds**

Use `spring-ai-starter-mcp-server-webflux`; remove MVC-only dependencies and Tomcat assumptions. Keep Streamable HTTP endpoint and application metadata unchanged.

- [ ] **Step 5: Build one Gradle and one Maven Java 21 WebFlux Async project**

Run the two focused generated integration tests and assert application context starts with Netty, `tools/list` returns exact schemas, and `tools/call` reaches the mock upstream.

- [ ] **Step 6: Commit WebFlux Async emission**

```bash
git add modules/adapters/emitters/spring-ai-2
git commit -m "feat: generate Spring AI WebFlux async servers"
```

### Task 11: Detect Netty Readiness and Activate Four WebFlux Async Profiles

**Files:**
- Create: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/ServerEndpointDetector.java`
- Create: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/TomcatServerEndpointDetector.java`
- Create: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/NettyServerEndpointDetector.java`
- Modify: `modules/adapters/validation/src/main/java/io/gen2spring/mcp/adapter/validation/ApplicationRuntimeValidator.java`
- Create: `modules/adapters/validation/src/test/java/io/gen2spring/mcp/adapter/validation/ServerEndpointDetectorTest.java`
- Modify: `modules/domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityCatalog.java`
- Modify: `modules/domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistryTest.java`
- Modify: `apps/cli/src/integrationTest/java/io/gen2spring/mcp/app/cli/P1GenerationIntegrationTest.java`
- Modify: `modules/adapters/filesystem/src/test/java/io/gen2spring/mcp/adapter/filesystem/GenerationPipelineTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`

**Interfaces:**
- Produces: `Optional<URI> ServerEndpointDetector.detect(String boundedOutput)`.
- Produces: final 12-profile registry.

- [ ] **Step 1: Write failing bounded endpoint detection tests**

Cover exactly one valid Tomcat or Netty startup line, no line, duplicate lines, invalid port, ANSI/control text, and output beyond the bounded buffer.

- [ ] **Step 2: Select the endpoint detector from `profile.target().webStack()`**

Keep `--server.address=127.0.0.1 --server.port=0`. Reject unknown stacks before application launch.

- [ ] **Step 3: Add the four Spring AI 2 WebFlux Async profiles**

Add Java 17/21 for Gradle Kotlin and Maven using the exact IDs in the spec. Assert the one deferred notice remains and no Spring AI 1 WebFlux profile exists.

- [ ] **Step 4: Expand CLI acceptance to the four WebFlux Async profiles**

Assert compile, generated tests, Netty context, MCP initialize, exact `tools/list`, representative `tools/call`, upstream request, manifest, archive, and source determinism.

- [ ] **Step 5: Run the four WebFlux Async journeys**

Run:

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
./gradlew :apps:cli:integrationTest --tests '*P1GenerationIntegrationTest*webflux*' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: all four profiles finish `VALIDATED`.

- [ ] **Step 6: Commit final profile activation**

```bash
git add modules apps/cli
git commit -m "feat: activate WebFlux async generation profiles"
```

### Task 12: Expose Canonical Profile Notices in the UI

**Files:**
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/api/ProfileController.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/api/GenerationPreviewPresenter.java`
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/static/app.js`
- Modify: `apps/web/src/main/resources/static/styles.css`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`
- Modify: `apps/web/src/integrationTest/java/io/gen2spring/mcp/app/web/LocalOperationEditorIntegrationTest.java`

**Interfaces:**
- Produces: `/api/profiles` with 12 profiles and one `compatibilityNotices` entry.
- Produces: keyboard-accessible help popover beside the profile label.

- [ ] **Step 1: Write failing API and static accessibility tests**

```java
mockMvc.perform(get("/api/profiles").with(localRequest()))
        .andExpect(jsonPath("$.profiles.length()").value(12))
        .andExpect(jsonPath("$.compatibilityNotices.length()").value(1))
        .andExpect(jsonPath("$.compatibilityNotices[0].code")
                .value("SPRING_AI_1_WEBFLUX_ASYNC_DEFERRED"));
```

Assert the template contains a button with `aria-expanded="false"`, `aria-controls`, and a hidden controlled region.

- [ ] **Step 2: Serialize profiles and notices from `GeneratorRuntime.compatibilityCatalog()`**

Do not place Spring AI 1.1 or issue text in JavaScript constants. Keep the API free of local paths and internal exception details.

- [ ] **Step 3: Render readable option labels and the help content**

Use `Spring AI 2.0 · Java 21 · Maven · WebFlux Async` style labels. Render the canonical summary/reason and upstream link as text/attributes created with DOM APIs, not `innerHTML`.

- [ ] **Step 4: Implement accessible open/close behavior**

Support button click, keyboard activation, `Escape`, outside click, and repeated trigger selection. Keep `aria-expanded` synchronized and do not move focus automatically.

- [ ] **Step 5: Run web tests**

Run: `./gradlew :apps:web:test :apps:web:integrationTest --tests '*LocalOperationEditorIntegrationTest*profiles*' --no-daemon --non-interactive --rerun-tasks`

Expected: PASS with 12 selectable options and one deferred-target notice.

- [ ] **Step 6: Commit UI guidance**

```bash
git add apps/web
git commit -m "feat: explain profile compatibility in the editor"
```

### Task 13: Add Fast and Full Validation Tasks, Run the Matrix, and Close the PRD Slice

**Files:**
- Modify: `mise.toml`
- Modify: `.github/workflows/ci.yml`
- Create: `.github/workflows/generation-acceptance.yml`
- Modify: `README.md`
- Modify: `docs/prd.md`
- Modify: `apps/cli/src/integrationTest/java/io/gen2spring/mcp/app/cli/P1GenerationIntegrationTest.java`
- Modify: `modules/adapters/emitters/spring-ai-1/build.gradle.kts`
- Modify: `modules/adapters/emitters/spring-ai-2/build.gradle.kts`

**Interfaces:**
- Produces: `mise run generator:test`.
- Produces: `mise run generator:acceptance`.
- Produces: manually dispatched Linux full and Windows representative GitHub acceptance jobs.

- [ ] **Step 1: Add `generator:test` and prove it runs no nested generated build**

The task must run domain, application, configuration, emitter support, Spring AI 1/2 fast tests, validation, bootstrap, CLI unit tests, and Web profile/static contract tests. Add an assertion or build log check that no generated `gradlew` or `mvnw` subprocess starts.

- [ ] **Step 2: Add host-aware `generator:acceptance`**

On POSIX, run all 12 CLI generation profiles sequentially with Java 17/21 homes. On Windows, run these exact representative profiles:

```text
spring-ai-1.1-java17-mvc-streamable
spring-ai-2.0-java21-maven-webflux-async-streamable
```

- [ ] **Step 3: Keep required CI fast on Linux and Windows**

Add emitter support/family fast tests, validator tests, and the narrow Web profile/static tests to `.github/workflows/ci.yml`. Do not invoke emitter integration suites, CLI integration suites, or `generator:acceptance`.

- [ ] **Step 4: Add manual generation acceptance workflow**

Use `workflow_dispatch`, install Temurin 17 and 21 with the same captured-home contract as required CI, and run Linux full/Windows representative acceptance. Keep it non-required for pull requests.

- [ ] **Step 5: Run fast validation locally**

Run: `mise run generator:test`

Expected: PASS; report this as fast local verification only.

- [ ] **Step 6: Run the full local POSIX matrix**

Run: `mise run generator:acceptance`

Expected: 12 profiles each report successful compile, context, initialize, `tools/list`, and `tools/call` stages.

- [ ] **Step 7: Verify repository-wide non-hosted regressions**

Run:

```bash
./gradlew :modules:domain:test :modules:application:test \
  :modules:adapters:configuration:test :modules:adapters:openapi:test \
  :modules:adapters:filesystem:test :modules:adapters:emitters:support:test \
  :modules:adapters:emitters:spring-ai-1:test \
  :modules:adapters:emitters:spring-ai-2:test \
  :modules:adapters:validation:test :modules:bootstrap:test \
  :apps:cli:test :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS.

- [ ] **Step 8: Update README and move Maven/WebFlux/Async to P2 complete only after Steps 5-7 pass**

Keep the README support table concise and link to the PRD. Preserve the deferred Spring AI 1.1 WebFlux Async explanation and activation criteria in the PRD.

- [ ] **Step 9: Commit delivery configuration and completion status**

```bash
git add mise.toml .github README.md docs/prd.md apps modules
git commit -m "test: add generation profile acceptance matrix"
```

- [ ] **Step 10: Perform final clean-state verification**

Run:

```bash
git diff --check
git status --short
git log --oneline --decorate -12
```

Expected: `git diff --check` exits 0, `git status --short` is empty, and commits are grouped by the responsibilities defined in this plan.
