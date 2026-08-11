# Spring AI 2 Java 17 Compatibility Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single pinned compatibility profile with a deterministic registry and generate, compile, boot, and MCP-validate the Spring AI 2 server on both Java 17 and Java 21.

**Architecture:** The domain module owns immutable target profiles and one canonical registry. Core resolves a requested profile before OpenAPI analysis and dispatches through a generator-module registry. CLI parsing, `profiles`, rendering, manifests, Docker output, and validation all consume the same canonical profile. Validation resolves and verifies a target JDK without relying on shell initialization, constrains Gradle toolchain discovery to that JDK, and launches the generated boot JAR with the same runtime.

**Tech Stack:** Java 21 for the generator, Java 17 and 21 for generated targets, Gradle Kotlin DSL and wrapper 9.6.1, Spring Boot 4.1.0, Spring AI 2.0.0, Jackson 2 in the generator, Jackson 3 in generated projects, JUnit 5, Eclipse Temurin noble JRE images pinned by multi-platform digest.

## Global Constraints

- Keep the generator build and generator-module test JVM on Java 21; Java 17 applies only to the generated target toolchain, generated tests, boot process, and MCP validation.
- Expose exactly `spring-ai-2.0-java17-mvc-streamable` and `spring-ai-2.0-java21-mvc-streamable` in ascending ID order.
- Keep `CompatibilityProfile.p0()` as a Java 21 compatibility alias, but do not use it as a production allow-list.
- Resolve profile and generator before OpenAPI analysis or Tool IR construction. Use `TARGET_PROFILE_NOT_FOUND` for unknown profiles and `TARGET_COMBINATION_UNSUPPORTED` for missing emitters.
- Keep Spring Boot 4.1.0, Spring AI 2.0.0, Gradle 9.6.1, MVC, synchronous execution, and Streamable HTTP identical across both profiles.
- Use template `spring-ai-2-v2` and generated runtime `0.2.0` for both profiles.
- Add no external dependency. Do not put an absolute JDK path, username, environment dump, command, process output, provider URL, or secret in generated files, manifest, ZIP, CLI output, or validation report.
- Disable Gradle toolchain auto-detection and auto-download during validation. Launch the boot JAR with the verified target `bin/java`.
- Preserve five-stage validation and exactly one representative `tools/call` with exactly one verified upstream request.
- Generate a digest-pinned Dockerfile with `USER 10001:10001` and deterministic `.dockerignore`.
- Use TDD for every task. Commit only listed files and use `feat:` or `refactor:` rather than `fix:`.

---

### Task 1: Immutable Compatibility Profile Registry

**Files:**
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityProfile.java`
- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistry.java`
- Modify: `generator-domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileTest.java`
- Create: `generator-domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistryTest.java`

**Interfaces:**
- Produces `CompatibilityProfile(..., String gradleVersion, String containerImage)`.
- Produces `defaults()`, `of(List<CompatibilityProfile>)`, `profiles()`, and `find(String)`.
- Preserves `CompatibilityProfile.p0()` as the canonical Java 21 profile.
- Tasks 2 and 3 consume the registry.

- [ ] **Step 1: Write failing exact-value and registry tests**

Assert the two IDs in sorted order; template `spring-ai-2-v2`; runtime `0.2.0`; Gradle `9.6.1`; and these exact images:

```text
eclipse-temurin:17.0.19_10-jre-noble@sha256:543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8
eclipse-temurin:21.0.11_10-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64
```

Assert `CompatibilityProfile.p0()` equals the Java 21 registry item. Cover null list/member, blank metadata, duplicate ID, duplicate `TargetPlatform`, input mutation, returned-list mutation, known lookup, unknown lookup, and fixed value-free failure messages.

- [ ] **Step 2: Run focused tests to verify RED**

```bash
mise exec -- ./gradlew :generator-domain:test \
  --tests 'io.gen2spring.mcp.domain.profile.CompatibilityProfileTest' \
  --tests 'io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistryTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `compileTestJava` fails because the new profile fields and registry are absent.

- [ ] **Step 3: Implement the record and deterministic registry**

Use this shape:

```java
public record CompatibilityProfile(
        String id,
        TargetPlatform target,
        String generatorModule,
        String templateVersion,
        String runtimeVersion,
        String gradleVersion,
        String containerImage) {}

public final class CompatibilityProfileRegistry {
    public static CompatibilityProfileRegistry defaults();
    public static CompatibilityProfileRegistry of(List<CompatibilityProfile> profiles);
    public List<CompatibilityProfile> profiles();
    public Optional<CompatibilityProfile> find(String id);
}
```

Validate every profile and target before constructing an ID-sorted immutable list and immutable ID map. Reject repeated `TargetPlatform` even when IDs differ. `find(null)` and `find(blank)` return empty and never fall back. Implement `p0()` by resolving the Java 21 ID from `defaults()`.

- [ ] **Step 4: Run the full domain module and verify GREEN**

```bash
mise exec -- ./gradlew :generator-domain:test --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 5: Commit Task 1**

```bash
git add generator-domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityProfile.java \
  generator-domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistry.java \
  generator-domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileTest.java \
  generator-domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileRegistryTest.java
git commit -m "feat(domain): register Java compatibility profiles"
```

### Task 2: Profile-Aware Generator Resolution and Core Contracts

**Files:**
- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/ProjectGeneratorRegistry.java`
- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationPipeline.java`
- Modify: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationManifestWriter.java`
- Modify: `generator-domain/src/main/java/io/gen2spring/mcp/domain/generation/GenerationContracts.java`
- Create: `generator-core/src/test/java/io/gen2spring/mcp/core/ProjectGeneratorRegistryTest.java`
- Modify: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GradleMcpProjectValidatorTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GeneratedWeatherValidationSmokeTest.java`

**Interfaces:**
- Consumes Task 1 `CompatibilityProfileRegistry`.
- Produces `ProjectGeneratorRegistry.of(Map<String, ProjectGenerator>)` and `require(CompatibilityProfile)`.
- Adds the canonical `CompatibilityProfile profile` component to `ValidationRequest`; its existing five-argument constructor delegates with `p0()`.
- Adds a canonical `GenerationPipeline` constructor taking both registries; its existing constructor becomes a single-profile adapter.

- [ ] **Step 1: Write failing registry and early-resolution tests**

Verify both profiles resolve the same registered Spring AI 2 generator. Verify missing module throws `TARGET_COMBINATION_UNSUPPORTED` at `TARGET_VALIDATE`. In `GenerationPipelineTest`, use tracking analyzer/generator doubles to prove unknown profile and missing emitter fail before analyzer invocation, no fallback occurs, and canonical profile object identity reaches both `GenerationContext` and `ValidationRequest`.

- [ ] **Step 2: Run focused core tests to verify RED**

```bash
mise exec -- ./gradlew :generator-core:test \
  --tests 'io.gen2spring.mcp.core.ProjectGeneratorRegistryTest' \
  --tests 'io.gen2spring.mcp.core.GenerationPipelineTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: compilation fails because registry-aware pipeline and validation contracts are absent.

- [ ] **Step 3: Implement generator lookup and resolve before analysis**

```java
public final class ProjectGeneratorRegistry {
    public static ProjectGeneratorRegistry of(Map<String, ProjectGenerator> generators);
    public ProjectGenerator require(CompatibilityProfile profile);
}
```

Replace fixed pipeline `profile` and `projectGenerator` fields with registries. After safe output-path checks and before `analyzer.analyze`, resolve the request ID to a canonical profile, then require the profile's generator module. Use the same profile for generation, manifest, and validation. The compatibility constructor wraps one profile and one generator.

- [ ] **Step 4: Add failing manifest assertions and implement profile metadata**

For both profiles assert exact `targetProfileId`, `javaVersion`, `gradleVersion`, `containerImage`, `templateVersion`, and `runtimeVersion`. Remove the manifest Gradle constant and read all of them from the profile. Assert same input/profile produces byte-identical manifest content while Java 17 versus Java 21 changes the source checksum.

- [ ] **Step 5: Run affected compilation and tests**

```bash
mise exec -- ./gradlew :generator-domain:test :generator-core:test \
  :generator-validation:compileTestJava \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 6: Commit Task 2**

```bash
git add generator-core/src/main/java/io/gen2spring/mcp/core/ProjectGeneratorRegistry.java \
  generator-core/src/main/java/io/gen2spring/mcp/core/GenerationPipeline.java \
  generator-core/src/main/java/io/gen2spring/mcp/core/GenerationManifestWriter.java \
  generator-domain/src/main/java/io/gen2spring/mcp/domain/generation/GenerationContracts.java \
  generator-core/src/test/java/io/gen2spring/mcp/core/ProjectGeneratorRegistryTest.java \
  generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/GradleMcpProjectValidatorTest.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/GeneratedWeatherValidationSmokeTest.java
git commit -m "feat(core): resolve profile-specific generators"
```

### Task 3: CLI Registry Wiring and Strict Profile Selection

**Files:**
- Modify: `generator-cli/src/main/java/io/gen2spring/mcp/cli/GenerationConfigurationReader.java`
- Modify: `generator-cli/src/main/java/io/gen2spring/mcp/cli/CliApplication.java`
- Modify: `generator-cli/src/main/java/io/gen2spring/mcp/cli/ApplicationFactory.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/GenerationConfigurationReaderTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/CliApplicationTest.java`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`
- Create: `generator-cli/src/test/resources/config/weather-generation-java17.yaml`

**Interfaces:**
- Consumes Tasks 1 and 2 registries.
- Produces `GenerationConfigurationReader(CompatibilityProfileRegistry)` and a registry-backed `CliApplication` while preserving current compatibility constructors.
- Task 6 consumes installed CLI behavior.

- [ ] **Step 1: Write failing YAML and `profiles` tests**

Add a Java 17 fixture identical to Java 21 except `targetProfileId`. Assert both parse, unknown profile remains a value-free `CliConfigurationException`, and `profiles` emits exactly two sorted entries with target metadata, Gradle version, and digest-pinned image.

- [ ] **Step 2: Run focused CLI tests to verify RED**

```bash
mise exec -- ./gradlew :generator-cli:test \
  --tests 'io.gen2spring.mcp.cli.GenerationConfigurationReaderTest' \
  --tests 'io.gen2spring.mcp.cli.CliApplicationTest' \
  --tests 'io.gen2spring.mcp.cli.InstalledCliTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: Java 17 is rejected and `profiles` returns one item.

- [ ] **Step 3: Inject one registry through all CLI boundaries**

Replace the `p0()` comparison in `GenerationConfigurationReader` with `profiles.find(id)`. Iterate `profiles.profiles()` in `CliApplication.profiles`. In `ApplicationFactory`, construct one default registry, one generator registry entry for `generator-spring-ai-2`, and inject them into reader, CLI, and pipeline. Keep default constructors by delegating to defaults; do not create another allow-list.

- [ ] **Step 4: Run all CLI unit tests and verify GREEN**

```bash
mise exec -- ./gradlew :generator-cli:test --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 5: Commit Task 3**

```bash
git add generator-cli/src/main/java/io/gen2spring/mcp/cli/GenerationConfigurationReader.java \
  generator-cli/src/main/java/io/gen2spring/mcp/cli/CliApplication.java \
  generator-cli/src/main/java/io/gen2spring/mcp/cli/ApplicationFactory.java \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/GenerationConfigurationReaderTest.java \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/CliApplicationTest.java \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java \
  generator-cli/src/test/resources/config/weather-generation-java17.yaml
git commit -m "feat(cli): expose Java 17 generation profile"
```

### Task 4: Profile-Aware Spring AI 2 Rendering and Docker Output

**Files:**
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/SpringAi2ProjectGenerator.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ProjectFileRenderer.java`
- Modify: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/JavaSourceRenderer.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedSecretSafetyTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/Task5ReviewRegressionTest.java`

**Interfaces:**
- Consumes canonical profile in `GenerationContext`.
- Produces one stateless Spring AI 2 generator for either supported profile, plus `.dockerignore`, profile Dockerfile/README/Gradle, and Java 17-compatible source.

- [ ] **Step 1: Write failing renderer family/output tests**

For both profiles assert selected Java toolchain, exact stack versions, README profile/Gradle/template/runtime/image metadata, exact image at Docker `FROM`, non-root `USER` before entrypoint, generated `.dockerignore`, and Gradle wrapper 9.6.1. Assert another module/version/transport fails before returning files.

- [ ] **Step 2: Run focused renderer tests to verify RED**

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test \
  --tests 'io.gen2spring.mcp.springai2.ProjectFileRendererTest' \
  --tests 'io.gen2spring.mcp.springai2.JavaSourceRendererTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: Java 17 is rejected by pinned-profile equality and Docker/ignore assertions fail.

- [ ] **Step 3: Implement the family invariant and per-context renderers**

Require module `generator-spring-ai-2`, Boot 4.1.0, AI 2.0.0, Gradle 9.6.1, Java 17 or 21, `GRADLE_KOTLIN`, MVC, SYNC, and STREAMABLE_HTTP. Construct renderers from `context.profile()` per generation. Add `JavaSourceRenderer(CompatibilityProfile)` and preserve its no-arg Java 21 path. Reject a context whose profile differs from the renderer profile.

- [ ] **Step 4: Render deterministic profile-specific files**

Use profile Java for Gradle, profile image for Docker, and profile metadata for README. Render:

```dockerfile
FROM <profile.containerImage>
WORKDIR /app
COPY build/libs/<artifact>.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Render `.dockerignore` as:

```text
**
!Dockerfile
!build/
!build/libs/
!build/libs/<artifact>.jar
```

- [ ] **Step 5: Add and run actual Java 17 generated compile smoke**

The generated application test asserts `Runtime.version().feature()` equals the profile feature. Supply the test JDK through `GEN2SPRING_JAVA_17_HOME` and execute generated Gradle with auto-detect/download false and installations path set to that home.

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew :generator-spring-ai-2:test \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: full module `BUILD SUCCESSFUL`; generated source compiles/tests on Java 17 and Java 21 regressions remain green.

- [ ] **Step 6: Commit Task 4**

```bash
git add generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/SpringAi2ProjectGenerator.java \
  generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ProjectFileRenderer.java \
  generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/JavaSourceRenderer.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedProjectSmokeTest.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedSecretSafetyTest.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/Task5ReviewRegressionTest.java
git commit -m "feat(spring-ai): render Java 17 target projects"
```

### Task 5: Verified Target Java Runtime for Validation

**Files:**
- Create: `generator-validation/src/main/java/io/gen2spring/mcp/validation/JavaRuntimeResolver.java`
- Modify: `generator-validation/src/main/java/io/gen2spring/mcp/validation/GradleMcpProjectValidator.java`
- Create: `generator-validation/src/test/java/io/gen2spring/mcp/validation/JavaRuntimeResolverTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GradleMcpProjectValidatorTest.java`
- Modify: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GeneratedWeatherValidationSmokeTest.java`

**Interfaces:**
- Consumes `ValidationRequest.profile()` and generated toolchain.
- Produces `JavaRuntimeResolver.resolve(CompatibilityProfile)` and stable verified home/executable identity.
- Produces restricted Gradle toolchain arguments and target-Java boot command.

- [ ] **Step 1: Write failing runtime-resolution tests**

Cover environment precedence, matching current runtime fallback, missing target, relative/symlink/non-directory home, missing/symlink/non-executable `bin/java`, wrong version, timeout, probe failure, fixed non-leaking errors, and file-key identity changes. Use an injected `RuntimeProbe`; assert Java 21 current-runtime fallback succeeds.

```java
final class JavaRuntimeResolver {
    JavaRuntimeResolver();
    JavaRuntimeResolver(Map<String, String> environment, Path currentJavaHome, RuntimeProbe probe);
    ResolvedJavaRuntime resolve(CompatibilityProfile profile);

    interface RuntimeProbe {
        int feature(Path javaExecutable) throws IOException, InterruptedException;
    }
}
```

- [ ] **Step 2: Run resolver tests to verify RED**

```bash
mise exec -- ./gradlew :generator-validation:test \
  --tests 'io.gen2spring.mcp.validation.JavaRuntimeResolverTest' \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 3: Implement bounded non-leaking resolution**

Resolve `GEN2SPRING_JAVA_<feature>_HOME`, then matching current `java.home`, else fixed failure. The default probe runs absolute `bin/java -XshowSettings:properties -version`, caps merged output at 32 KiB, times out in five seconds, parses exactly one `java.specification.version`, kills on failure, and discards captured text. Record home/executable file keys and revalidate before use. Never include a path or raw probe output in the exception.

- [ ] **Step 4: Write failing validator command/report tests**

Assert compile arguments include exactly:

```text
-Dorg.gradle.java.installations.auto-detect=false
-Dorg.gradle.java.installations.auto-download=false
-Dorg.gradle.java.installations.paths=<verified target home>
```

Assert application command begins with verified target `bin/java`; stability is checked before Gradle and boot. Resolution failure returns `UNVERIFIED`, marks COMPILE failed, skips later stages, yields no pipeline ZIP, and does not expose the path.

- [ ] **Step 5: Implement validator routing and run full module**

Resolve once at validation start, store runtime in validated request, check stability before each process, add three Gradle properties, and replace current `java.home` boot selection. Preserve interruption, fatal `Error`, cleanup, mock, and exactly-one behavior.

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew :generator-validation:test \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 6: Commit Task 5**

```bash
git add generator-validation/src/main/java/io/gen2spring/mcp/validation/JavaRuntimeResolver.java \
  generator-validation/src/main/java/io/gen2spring/mcp/validation/GradleMcpProjectValidator.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/JavaRuntimeResolverTest.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/GradleMcpProjectValidatorTest.java \
  generator-validation/src/test/java/io/gen2spring/mcp/validation/GeneratedWeatherValidationSmokeTest.java
git commit -m "feat(validation): run projects on target Java"
```

### Task 6: Dual-Profile Installed CLI and MCP Matrix

**Files:**
- Create: `generator-cli/src/integrationTest/resources/config/weather-generation-java17.yaml`
- Modify: `generator-cli/src/integrationTest/resources/config/weather-generation.yaml`
- Modify: `generator-cli/src/integrationTest/java/io/gen2spring/mcp/cli/P1GenerationIntegrationTest.java`
- Modify: `generator-cli/build.gradle.kts`

**Interfaces:**
- Consumes Tasks 1-5 as an installed CLI journey.
- Produces release evidence for actual Java 17/21 compile, test, boot, MCP, manifest, archive, Docker, and determinism.

- [ ] **Step 1: Add failing Java 17 matrix row**

The Java 17 fixture differs only in profile ID. Parameterize the journey with profile ID, Java feature, resource, and image. Each row runs twice and asserts exit 0/VALIDATED, exact profile files, five successful stages, exact Tool schema, representative normalized `tools/call`, exactly one upstream request, selected target runtime, per-profile determinism, cross-profile checksum difference, and no secret/JDK path/username/command/output leaks.

- [ ] **Step 2: Forward the test-only JDK home**

Forward `GEN2SPRING_JAVA_17_HOME` from Gradle to the integration test and installed CLI subprocess only when present. Fail with a clear test assertion when missing. Production code must never invoke `mise`.

- [ ] **Step 3: Run matrix RED then GREEN**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew :generator-cli:integrationTest \
  --tests 'io.gen2spring.mcp.cli.P1GenerationIntegrationTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Before Tasks 1-5 integration, Java 17 must fail. After them, both rows pass twice. Only adjust harness/resource/profile expectations; do not weaken schema, normalization, provider-error, invalid-argument, secret, or exactly-one assertions.

- [ ] **Step 4: Commit Task 6**

```bash
git add generator-cli/src/integrationTest/resources/config/weather-generation-java17.yaml \
  generator-cli/src/integrationTest/resources/config/weather-generation.yaml \
  generator-cli/src/integrationTest/java/io/gen2spring/mcp/cli/P1GenerationIntegrationTest.java \
  generator-cli/build.gradle.kts
git commit -m "feat(cli): validate Java profile matrix"
```

### Task 7: Documentation, Review, and Full Acceptance

**Files:**
- Modify: `README.md`
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`
- Modify: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java`

**Interfaces:**
- Consumes complete dual-profile behavior.
- Produces user prerequisites and final acceptance evidence for the next P1 slice.

- [ ] **Step 1: Write failing documentation contracts**

Assert root/generated READMEs document both profile IDs, deterministic `profiles`, generator Java 21, Java 17 target home variable, disabled auto-download, exact stack versions, digest-pinned non-root Docker, `.dockerignore`, Java 21 default, and remaining P1 slices without claiming them complete.

- [ ] **Step 2: Run focused docs tests to verify RED, then synchronize docs**

```bash
mise exec -- ./gradlew :generator-cli:test :generator-spring-ai-2:test \
  --tests 'io.gen2spring.mcp.cli.InstalledCliTest' \
  --tests 'io.gen2spring.mcp.springai2.ProjectFileRendererTest' \
  --no-daemon --non-interactive --rerun-tasks
```

Update README examples with exact identifiers and safe missing/mismatched JDK behavior. Do not document Spring AI 1.x, observability, Windows, or UI/API as complete.

- [ ] **Step 3: Run affected modules**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew :generator-domain:test :generator-core:test \
  :generator-spring-ai-2:test :generator-validation:test \
  :generator-cli:test :generator-cli:integrationTest \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] **Step 4: Run exact repository acceptance**

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew clean test integrationTest :generator-cli:installDist \
  --no-daemon --non-interactive --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, zero test failures/errors, and both profile journeys `VALIDATED`.

- [ ] **Step 5: Perform deterministic/security readback**

```bash
git diff --check
rg -n '/Users/|/home/|mcp-validation-secret|configured-invalid-representative-value|Exception:|at io\.gen2spring' \
  generator-*/build/test-results generator-*/build/reports || true
git status --short
```

Inspect matches, enumerate JUnit test/failure/error/skip counts, and confirm generated files, ZIP, manifest, report, stdout, and stderr contain no real JDK path, secret, stack frame, or process output.

- [ ] **Step 6: Request scoped review and address verified findings**

Use the `code-review` skill on the complete slice diff. Critical/Important findings block completion. Add a RED regression before every accepted change and use `feat:` or `refactor:`. Reject suggestions that expand into Spring AI 1.x, observability, Windows, or UI/API.

- [ ] **Step 7: Commit Task 7**

```bash
git add README.md \
  generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java \
  generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java
git commit -m "docs: document Java compatibility profiles"
```

## Completion Gate

- [ ] Default registry exposes exactly both profiles in deterministic order.
- [ ] CLI, pipeline, renderer, manifest, validator, README, Dockerfile, and ZIP consume the same canonical profile.
- [ ] Both targets compile, test, boot, initialize MCP, list tools, call one representative tool, and verify one upstream request on the selected JDK.
- [ ] Unknown profile, missing emitter, absent/wrong/swapped JDK, and renderer mismatch fail closed with safe errors and no ZIP.
- [ ] Docker is digest pinned, numeric non-root, and paired with deterministic `.dockerignore`.
- [ ] Existing Java 21 P0/P1 behavior remains green.
- [ ] Full repository acceptance succeeds with zero failures/errors and no Critical/Important review finding.

## Complexity and Operational Notes

- Registry construction is `O(P log P)` time and `O(P)` space; profile and generator lookup are average `O(1)`.
- Matrix validation is `O(P)` and dominated by generated Gradle compile/test and application startup. Runtime MCP request complexity is unchanged.
- Target JDKs must be preinstalled and explicitly discoverable; auto-download stays disabled for reproducibility and supply-chain control.
- A future Spring AI 1.x emitter registers a second generator module without changing CLI/core resolution.
