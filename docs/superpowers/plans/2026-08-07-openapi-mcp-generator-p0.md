# OpenAPI MCP Generator P0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java 21 CLI that turns a local OpenAPI 3.0 specification into a validated Spring AI 2.0 Streamable HTTP MCP server project and deterministic ZIP artifact.

**Architecture:** A Gradle multi-module modular monolith keeps normalized OpenAPI and Tool IR independent from Swagger Parser and Spring AI. The CLI composes parser, policy, Spring AI 2 emitter, deterministic packaging, process validation, and MCP protocol validation adapters.

**Tech Stack:** Java 21, Gradle Kotlin DSL 9.6.1, Swagger Parser 2.1.40, Jackson 2.22.0 for the generator, JUnit 5.13.4, Spring Boot 4.1.0 and Spring AI 2.0.0 for generated projects.

## Global Constraints

- Accept local OpenAPI 3.0.x `.yaml`, `.yml`, and `.json` files only.
- Generate Java 21, Spring Boot 4.1.0, Spring AI 2.0.0, Spring MVC Sync, Streamable HTTP projects only.
- Generate Gradle Kotlin DSL projects with Gradle Wrapper 9.6.1.
- Support path, query, header, JSON body, local `$ref`, primitive, enum, array, and object schemas.
- Exclude `oneOf`, `anyOf`, `allOf`, discriminator, recursive schema, URL import, Java 17, Spring AI 1.x, Maven, WebFlux, async, SSE, STDIO, response normalization, retry, and `tools/call`.
- Keep API Key values out of Tool input, source, logs, reports, and artifacts; inject them through environment variables.
- Do not overwrite an existing output directory.
- Compute deterministic source checksums without manifest, validation report, archives, or process logs.
- Do not add Picocli, a template engine, a Java source generation library, AssertJ, or mocking libraries.
- Do not stage or commit files because the user has not authorized Git writes beyond the working tree.

---

## File Structure

### Root build

- `settings.gradle.kts`: module inclusion and repository policy.
- `build.gradle.kts`: Java 21, compiler, test, and reproducible archive conventions.
- `gradle/libs.versions.toml`: exact dependency versions and aliases.
- `gradle.properties`: deterministic Gradle defaults.
- `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`: Gradle 9.6.1 wrapper.
- `.gitignore`: Gradle and generated test output exclusions.

### Module responsibilities

- `generator-domain`: immutable normalized models, Tool IR, profile, generation/validation ports, and error taxonomy.
- `generator-openapi`: safe local input loading, JSON/YAML preflight, Swagger Parser adapter, normalization, and analysis warnings.
- `generator-policy`: Tool naming, secret classification, selection overrides, and Tool IR construction.
- `generator-spring-ai-2`: deterministic project/source renderer and embedded wrapper assets.
- `generator-core`: pipeline, safe source-tree writing, checksums, manifests, reports, and deterministic ZIP packaging.
- `generator-validation`: bounded external process execution and real Streamable HTTP MCP contract validation.
- `generator-cli`: command parsing, YAML configuration reading, dependency composition, terminal output, and exit codes.

---

### Task 1: Bootstrap the build and domain contracts

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`
- Create: `.gitignore`
- Create: `generator-domain/build.gradle.kts`
- Create: `generator-openapi/build.gradle.kts`
- Create: `generator-policy/build.gradle.kts`
- Create: `generator-core/build.gradle.kts`
- Create: `generator-spring-ai-2/build.gradle.kts`
- Create: `generator-validation/build.gradle.kts`
- Create: `generator-cli/build.gradle.kts`
- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/openapi/OpenApiDocument.java`
- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/tool/McpToolDefinition.java`
- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/config/GenerationRequest.java`
- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/profile/CompatibilityProfile.java`
- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/generation/GenerationContracts.java`
- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/error/GeneratorErrorCode.java`
- Create: `generator-domain/src/main/java/io/gen2spring/mcp/domain/error/GeneratorException.java`
- Test: `generator-domain/src/test/java/io/gen2spring/mcp/domain/profile/CompatibilityProfileTest.java`

**Interfaces:**
- Produces: `OpenApiDocument`, `McpToolDefinition`, `GenerationRequest`, `CompatibilityProfile`, `GenerationContracts.ProjectGenerator`, and `GenerationContracts.GeneratedProjectValidator` for all later tasks.

- [ ] **Step 1: Write the failing profile behavior test**

The production change caught by this test is accepting any version that is not the single verified P0 profile.

```java
package io.gen2spring.mcp.domain.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompatibilityProfileTest {
    @Test
    void acceptsOnlyThePinnedP0Target() {
        var profile = CompatibilityProfile.p0();

        assertTrue(profile.supports(new CompatibilityProfile.TargetPlatform(
                21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")));
        assertFalse(profile.supports(new CompatibilityProfile.TargetPlatform(
                17, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")));
    }
}
```

- [ ] **Step 2: Add the Gradle multi-module build and wrapper**

Use exact versions in `gradle/libs.versions.toml`:

```toml
[versions]
swagger-parser = "2.1.40"
jackson = "2.22.0"
junit = "5.13.4"

[libraries]
swagger-parser = { module = "io.swagger.parser.v3:swagger-parser", version.ref = "swagger-parser" }
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
jackson-yaml = { module = "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", version.ref = "jackson" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
```

Bootstrap the wrapper with the configured mise runtime:

```bash
mise x gradle@9.6.1 -- gradle wrapper --gradle-version 9.6.1 --distribution-type bin
```

Set module dependencies in one direction: `generator-openapi -> generator-domain`, `generator-policy -> generator-domain`, `generator-spring-ai-2 -> generator-domain`, `generator-validation -> generator-domain`, `generator-core -> generator-domain + generator-openapi + generator-policy`, and `generator-cli -> all modules`. Jackson belongs only to modules that serialize or parse; Swagger Parser belongs only to `generator-openapi`.

- [ ] **Step 3: Run the profile test and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-domain:test --tests '*CompatibilityProfileTest' --no-daemon --non-interactive
```

Expected: compilation fails because `CompatibilityProfile` does not exist.

- [ ] **Step 4: Add immutable domain contracts and the pinned profile**

Use nested records and enums only where they form one cohesive model. The central signatures are:

```java
public record CompatibilityProfile(
        String id,
        TargetPlatform target,
        String generatorModule,
        String templateVersion,
        String runtimeVersion) {
    public static CompatibilityProfile p0() {
        return new CompatibilityProfile(
                "spring-ai-2.0-java21-mvc-streamable",
                new TargetPlatform(21, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP"),
                "generator-spring-ai-2", "spring-ai-2-v1", "0.1.0");
    }

    public boolean supports(TargetPlatform candidate) {
        return target.equals(candidate);
    }

    public record TargetPlatform(
            int javaVersion,
            String springBootVersion,
            String springAiVersion,
            String buildTool,
            String webStack,
            String programmingModel,
            String transport) {}
}
```

Use these exact normalized model shapes:

```java
public record OpenApiDocument(
        String openApiVersion,
        String checksum,
        String sourceExtension,
        URI baseUrl,
        List<ApiOperation> operations,
        Map<String, ApiSecurityScheme> securitySchemes,
        List<AnalysisWarning> warnings) {
    public enum HttpMethod { GET, POST, PUT, PATCH, DELETE }
    public enum ParameterLocation { PATH, QUERY, HEADER, BODY }
    public enum SchemaType { STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT }

    public record ApiOperation(
            String operationId,
            HttpMethod method,
            String path,
            String summary,
            String description,
            List<ApiParameter> parameters,
            ApiSchema requestBody,
            boolean requestBodyRequired,
            List<String> securityRequirements,
            boolean supported,
            List<String> warnings) {}

    public record ApiParameter(
            String name,
            ParameterLocation location,
            boolean required,
            String description,
            ApiSchema schema) {}

    public record ApiSchema(
            SchemaType type,
            String format,
            boolean nullable,
            List<String> enumValues,
            BigDecimal minimum,
            BigDecimal maximum,
            Integer minLength,
            Integer maxLength,
            String pattern,
            Object defaultValue,
            Map<String, ApiSchema> properties,
            List<String> requiredProperties,
            ApiSchema items,
            boolean supported,
            List<String> warnings) {}

    public record ApiSecurityScheme(
            String name,
            String type,
            ParameterLocation location,
            String parameterName) {}

    public record AnalysisWarning(String code, String message, String operationId) {}
}
```

```java
public record McpToolDefinition(
        String operationId,
        String name,
        String description,
        List<McpInputDefinition> inputs,
        HttpExecutionDefinition execution,
        List<SecretBinding> secretBindings,
        OutputKind outputKind) {
    public enum ParameterSource { USER_INPUT, SERVER_SECRET, SERVER_DEFAULT, CONTEXT_DERIVED, INTERNAL, UNSUPPORTED }
    public enum OutputKind { GENERIC_JSON }

    public record McpInputDefinition(
            String name,
            String jsonName,
            String description,
            boolean required,
            OpenApiDocument.ApiSchema schema) {}

    public record HttpExecutionDefinition(
            OpenApiDocument.HttpMethod method,
            URI baseUrl,
            String path,
            List<ParameterBinding> bindings) {}

    public record ParameterBinding(
            String sourceName,
            OpenApiDocument.ParameterLocation targetLocation,
            String targetName) {}

    public record SecretBinding(
            String environmentVariable,
            String propertyName,
            OpenApiDocument.ParameterLocation targetLocation,
            String targetName,
            boolean required) {}
}
```

```java
public record GenerationRequest(
        ProjectCoordinates project,
        String provider,
        String domain,
        String targetProfileId,
        ValidationLevel validationLevel,
        List<OperationSelection> operations) {
    public enum ValidationLevel { MCP_PROTOCOL }
    public record ProjectCoordinates(String groupId, String artifactId, String packageName) {}
    public record OperationSelection(
            String operationId,
            boolean enabled,
            String toolName,
            String toolDescription,
            Map<String, ParameterOverride> parameters) {}
    public record ParameterOverride(
            McpToolDefinition.ParameterSource source,
            String environmentVariable) {}
}
```

Put the generation ports and their complete data contract in `GenerationContracts`:

```java
public final class GenerationContracts {
    private GenerationContracts() {}

    public interface ProjectGenerator {
        GeneratedProjectFiles generate(GenerationContext context);
    }

    public interface GeneratedProjectValidator {
        ValidationReport validate(ValidationRequest request);
    }

    public record GenerationContext(
            OpenApiDocument document,
            List<McpToolDefinition> tools,
            GenerationRequest request,
            CompatibilityProfile profile,
            byte[] originalSpecification) {}

    public record GeneratedProjectFiles(Map<String, byte[]> files) {}

    public record ValidationRequest(
            Path projectRoot,
            String artifactId,
            GenerationRequest.ValidationLevel level,
            Map<String, ExpectedTool> expectedTools) {}

    public record ExpectedTool(String description) {}
    public enum ValidationStatus { VALIDATED, UNVERIFIED }
    public enum StageStatus { SUCCESS, FAILED, SKIPPED }

    public record ValidationStageResult(
            String stage,
            StageStatus status,
            long durationMillis,
            int warningCount,
            int errorCount,
            String summary) {}

    public record ObservedTool(String name, String description, boolean inputSchemaPresent) {}

    public record ValidationReport(
            ValidationStatus status,
            List<ValidationStageResult> stages,
            List<ObservedTool> tools) {}

    public record GenerationOutcome(
            Path projectRoot,
            Path archive,
            ValidationStatus validationStatus,
            String sourceChecksum) {}
}
```

`GeneratorErrorCode` contains exactly the P0 codes `SPEC_FILE_UNSUPPORTED`, `SPEC_TOO_LARGE`, `SPEC_PARSE_FAILED`, `SPEC_REFERENCE_UNRESOLVED`, `SPEC_VERSION_UNSUPPORTED`, `OPERATION_ID_DUPLICATED`, `OPERATION_UNSUPPORTED`, `SECRET_EXPOSURE_DETECTED`, `TARGET_PROFILE_NOT_FOUND`, `TARGET_COMBINATION_UNSUPPORTED`, `SOURCE_GENERATION_FAILED`, `COMPILE_TIMEOUT`, `COMPILE_FAILED`, `APPLICATION_CONTEXT_FAILED`, `MCP_INITIALIZE_FAILED`, `MCP_TOOLS_LIST_FAILED`, `ARTIFACT_PACKAGE_FAILED`, and `INTERNAL_ERROR`. `GeneratorException` carries a code, stage, safe message, and optional cause, with factory methods for user and system failures.

- [ ] **Step 5: Run the domain tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-domain:test --no-daemon --non-interactive
```

Expected: all domain tests pass with Java 21.

- [ ] **Step 6: Review the task diff without staging**

Run `git diff -- . ':(exclude)docs/prd.md'` and `git status --short`. Confirm only Task 1 files and pre-existing untracked files are present.

---

### Task 2: Load and normalize OpenAPI safely

**Files:**
- Create: `generator-openapi/src/main/java/io/gen2spring/mcp/openapi/SpecificationAnalyzer.java`
- Create: `generator-openapi/src/main/java/io/gen2spring/mcp/openapi/LocalSpecificationLoader.java`
- Create: `generator-openapi/src/main/java/io/gen2spring/mcp/openapi/ExternalReferenceGuard.java`
- Create: `generator-openapi/src/main/java/io/gen2spring/mcp/openapi/SwaggerOpenApiAnalyzer.java`
- Create: `generator-openapi/src/main/java/io/gen2spring/mcp/openapi/SwaggerSchemaNormalizer.java`
- Create: `generator-openapi/src/test/resources/openapi/simple-weather.yaml`
- Create: `generator-openapi/src/test/resources/openapi/duplicate-operation-id.yaml`
- Create: `generator-openapi/src/test/resources/openapi/external-reference.yaml`
- Create: `generator-openapi/src/test/resources/openapi/unsupported-schema.yaml`
- Test: `generator-openapi/src/test/java/io/gen2spring/mcp/openapi/LocalSpecificationLoaderTest.java`
- Test: `generator-openapi/src/test/java/io/gen2spring/mcp/openapi/SwaggerOpenApiAnalyzerTest.java`

**Interfaces:**
- Consumes: `OpenApiDocument` and `GeneratorException` from Task 1.
- Produces: `SpecificationAnalyzer.analyze(Path, long): AnalysisResult` for core and CLI modules.

- [ ] **Step 1: Write failing loader and analyzer tests**

The loader tests catch size-limit bypass and external `$ref` network access. The analyzer tests catch loss of required/query/schema information and duplicate operation IDs.

```java
@Test
void rejectsExternalReferencesBeforeSwaggerParserCanResolveThem() {
    var exception = assertThrows(GeneratorException.class,
            () -> analyzer.analyze(resource("openapi/external-reference.yaml"), 10 * 1024 * 1024));

    assertEquals(GeneratorErrorCode.SPEC_REFERENCE_UNRESOLVED, exception.code());
}

@Test
void normalizesARequiredQueryParameterAndApiKeyScheme() {
    var document = analyzer.analyze(resource("openapi/simple-weather.yaml"), 10 * 1024 * 1024).document();

    var operation = document.operations().getFirst();
    assertEquals("getForecast", operation.operationId());
    assertEquals("GET", operation.method().name());
    assertEquals("nx", operation.parameters().getFirst().name());
    assertTrue(operation.parameters().getFirst().required());
    assertEquals("apiKey", document.securitySchemes().get("serviceKeyAuth").type());
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-openapi:test --tests '*LocalSpecificationLoaderTest' --tests '*SwaggerOpenApiAnalyzerTest' --no-daemon --non-interactive
```

Expected: compilation fails because the analyzer classes do not exist.

- [ ] **Step 3: Implement bounded local loading and preflight parsing**

`LocalSpecificationLoader.load` must validate the regular file, lower-case extension, byte size before allocation, and SHA-256 of the exact bytes.

```java
public LoadedSpecification load(Path path, long maxBytes) {
    Path normalized = path.toAbsolutePath().normalize();
    if (!Files.isRegularFile(normalized)) {
        throw GeneratorException.user(SPEC_FILE_UNSUPPORTED, SOURCE_LOAD, "Specification must be a regular file");
    }
    long size = Files.size(normalized);
    if (size > maxBytes) {
        throw GeneratorException.user(SPEC_TOO_LARGE, SOURCE_LOAD, "Specification exceeds " + maxBytes + " bytes");
    }
    byte[] bytes = Files.readAllBytes(normalized);
    return new LoadedSpecification(bytes, extension(normalized), sha256(bytes));
}
```

Parse the bytes into a Jackson tree before Swagger Parser. Recursively reject every `$ref` whose value does not start with `#/` so URL and filesystem references never reach the parser.

- [ ] **Step 4: Implement Swagger normalization**

Use `OpenAPIV3Parser.readContents` with `resolve=true`, `resolveFully=false`, and `resolveCombinators=false`. Reject non-3.0 versions. Map path-level and operation-level parameters in stable path/method order. Mark operations without `operationId`, duplicate IDs, recursive schemas, `oneOf`, `anyOf`, `allOf`, or discriminator as unsupported with explicit warnings.

```java
public interface SpecificationAnalyzer {
    AnalysisResult analyze(Path specification, long maxBytes);

    record AnalysisResult(OpenApiDocument document, byte[] originalSpecification) {}
}
```

Constraints must map into the domain schema: type, format, nullable, required, enum values, minimum, maximum, minLength, maxLength, pattern, default, properties, and array items.

- [ ] **Step 5: Run analyzer tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-openapi:test --no-daemon --non-interactive
```

Expected: all fixtures parse deterministically; external refs and duplicate IDs produce the expected codes or unsupported analysis results.

- [ ] **Step 6: Review the task diff without staging**

Confirm Swagger types appear only under `generator-openapi` and no parser dependency leaks into `generator-domain`.

---

### Task 3: Build Tool IR with naming and secret policies

**Files:**
- Create: `generator-policy/src/main/java/io/gen2spring/mcp/policy/ToolNamingPolicy.java`
- Create: `generator-policy/src/main/java/io/gen2spring/mcp/policy/SecretParameterPolicy.java`
- Create: `generator-policy/src/main/java/io/gen2spring/mcp/policy/ToolDescriptionPolicy.java`
- Create: `generator-policy/src/main/java/io/gen2spring/mcp/policy/ToolModelFactory.java`
- Test: `generator-policy/src/test/java/io/gen2spring/mcp/policy/ToolNamingPolicyTest.java`
- Test: `generator-policy/src/test/java/io/gen2spring/mcp/policy/SecretParameterPolicyTest.java`
- Test: `generator-policy/src/test/java/io/gen2spring/mcp/policy/ToolModelFactoryTest.java`

**Interfaces:**
- Consumes: normalized operations and `GenerationRequest.OperationSelection`.
- Produces: `ToolModelFactory.create(OpenApiDocument, GenerationRequest): List<McpToolDefinition>`.

- [ ] **Step 1: Write failing naming and secret tests**

The naming test catches unstable punctuation/case conversion. The secret test catches `serviceKey` leaking into visible Tool inputs.

```java
@Test
void generatesAStableSnakeCaseToolName() {
    assertEquals("kma_weather_get_forecast",
            policy.generate("KMA", "weather-api", "getForecast"));
}

@Test
void classifiesServiceKeyAsServerSecret() {
    assertEquals(ParameterSource.SERVER_SECRET,
            policy.classify("serviceKey", ParameterLocation.QUERY, false));
}
```

- [ ] **Step 2: Run focused policy tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-policy:test --no-daemon --non-interactive
```

Expected: compilation fails because policy classes do not exist.

- [ ] **Step 3: Implement naming, description, and classification**

`ToolNamingPolicy` must normalize Unicode input to ASCII-safe lower snake case, collapse separators, enforce `[a-z][a-z0-9_]{0,63}`, and reject empty or duplicate names. `ToolDescriptionPolicy` must prefer an override, then combine nonblank summary and description without inventing domain meaning.

`SecretParameterPolicy` must classify explicit config first, then matching OpenAPI API Key schemes, then case-insensitive normalized names from the approved candidate list. A classified secret requires an environment variable matching `[A-Z][A-Z0-9_]{0,127}`.

- [ ] **Step 4: Write the failing Tool IR selection test**

```java
@Test
void exposesOnlySelectedUserInputsAndCreatesASecretBinding() {
    var tools = factory.create(weatherDocument(), generationRequest());

    assertEquals(1, tools.size());
    assertEquals(List.of("nx", "ny"), tools.getFirst().inputs().stream().map(McpInputDefinition::name).toList());
    assertEquals("KMA_SERVICE_KEY", tools.getFirst().secretBindings().getFirst().environmentVariable());
}
```

Run the focused test and confirm it fails because `ToolModelFactory` is missing.

- [ ] **Step 5: Implement Tool IR construction and verify GREEN**

Fail generation when a configured operation is absent, unsupported, duplicated after override, or exposes a confirmed secret. Preserve original operation IDs and JSON names in bindings. Return tools ordered by operation ID.

Run:

```bash
mise exec -- ./gradlew :generator-policy:test --no-daemon --non-interactive
```

Expected: all naming, secret, selection, override, and duplicate tests pass.

- [ ] **Step 6: Review the task diff without staging**

Confirm the policy module imports domain types only and does not import Swagger or Spring AI types.

---

### Task 4: Emit deterministic project structure and configuration

**Files:**
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/SpringAi2ProjectGenerator.java`
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ProjectFileRenderer.java`
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/JavaIdentifier.java`
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/JavaStringLiteral.java`
- Create: `generator-spring-ai-2/src/main/resources/wrapper/gradlew`
- Create: `generator-spring-ai-2/src/main/resources/wrapper/gradlew.bat`
- Create: `generator-spring-ai-2/src/main/resources/wrapper/gradle-wrapper.jar`
- Create: `generator-spring-ai-2/src/main/resources/wrapper/gradle-wrapper.properties`
- Test: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/ProjectFileRendererTest.java`
- Test: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaIdentifierTest.java`

**Interfaces:**
- Consumes: `GenerationContext` and the pinned `CompatibilityProfile`.
- Produces: the non-Java portion of `GeneratedProjectFiles` and safe Java naming helpers for Task 5.

- [ ] **Step 1: Write failing rendering and injection tests**

The rendering test catches dependency drift. The injection test catches package or Java string content escaping into generated source/build scripts.

```java
@Test
void rendersPinnedBuildVersions() {
    String build = renderer.buildGradle(projectCoordinates());

    assertTrue(build.contains("id(\"org.springframework.boot\") version \"4.1.0\""));
    assertTrue(build.contains("spring-ai-bom:2.0.0"));
    assertTrue(build.contains("JavaLanguageVersion.of(21)"));
    assertFalse(build.contains("SNAPSHOT"));
    assertFalse(build.contains("latest"));
}

@Test
void rejectsAPathBreakingPackageName() {
    assertThrows(GeneratorException.class,
            () -> JavaIdentifier.requirePackage("com.example.../../escape"));
}
```

- [ ] **Step 2: Run renderer tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test --no-daemon --non-interactive
```

Expected: compilation fails because renderer classes do not exist.

- [ ] **Step 3: Implement safe project file rendering**

Render exact Gradle Kotlin DSL with `java`, Spring Boot `4.1.0`, Java toolchain `21`, Spring AI BOM `2.0.0`, `spring-ai-starter-mcp-server-webmvc`, validation, and Spring Boot test dependencies. Set `bootJar.archiveFileName` to `<artifactId>.jar` and `tasks.test { useJUnitPlatform() }`.

Render `application.yml` with:

```yaml
spring:
  application:
    name: weather-mcp-server
  ai:
    mcp:
      server:
        name: weather-mcp-server
        version: 0.1.0
        type: SYNC
        protocol: STREAMABLE
        annotation-scanner:
          enabled: true
        streamable-http:
          mcp-endpoint: /mcp
provider:
  base-url: ${PROVIDER_BASE_URL:https://api.example.test}
  response-max-bytes: 1048576
```

Append one `provider.secrets.<key>: ${ENVIRONMENT_VARIABLE:}` entry per secret. YAML scalar values originating in user input must be quoted through Jackson, not string concatenation.

- [ ] **Step 4: Embed verified wrapper assets**

Copy the root Gradle 9.6.1 wrapper scripts, JAR, and properties into module resources. Preserve executable mode for generated `gradlew` when writing the project.

- [ ] **Step 5: Run renderer tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test --no-daemon --non-interactive
```

Expected: exact-version, injection, application configuration, wrapper, README, Dockerfile, and `.gitignore` tests pass.

- [ ] **Step 6: Review the task diff without staging**

Confirm no user-derived text is inserted into Kotlin DSL or unescaped Java/YAML syntax.

---

### Task 5: Emit Tool, DTO, metadata, and REST runtime Java sources

**Files:**
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/JavaSourceRenderer.java`
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/InputRecordRenderer.java`
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/ToolClassRenderer.java`
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/OperationMetadataRenderer.java`
- Create: `generator-spring-ai-2/src/main/java/io/gen2spring/mcp/springai2/RuntimeSourceRenderer.java`
- Create: `generator-spring-ai-2/src/test/resources/golden/weather/WeatherMcpTools.java`
- Create: `generator-spring-ai-2/src/test/resources/golden/weather/GetForecastInput.java`
- Test: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/JavaSourceRendererTest.java`
- Test: `generator-spring-ai-2/src/test/java/io/gen2spring/mcp/springai2/GeneratedSecretSafetyTest.java`

**Interfaces:**
- Consumes: ordered `McpToolDefinition` instances.
- Produces: all Java source entries in `GeneratedProjectFiles`.

- [ ] **Step 1: Write failing golden and secret-safety tests**

The golden test catches annotation/schema/parameter mapping regressions. The safety test catches secret names or values appearing as Tool parameters.

```java
@Test
void rendersAFlatMcpToolAndInputRecord() {
    var files = renderer.render(contextWithWeatherTool());

    assertArrayEquals(golden("weather/WeatherMcpTools.java"),
            files.get("src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));
    assertArrayEquals(golden("weather/GetForecastInput.java"),
            files.get("src/main/java/com/example/weather/generated/model/GetForecastInput.java"));
}

@Test
void doesNotExposeServiceKeyAsAToolParameter() {
    String source = utf8(renderer.render(contextWithWeatherTool())
            .get("src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));

    assertFalse(source.contains("@McpToolParam(description = \"serviceKey\""));
    assertFalse(source.contains("KMA_SERVICE_KEY"));
}
```

- [ ] **Step 2: Run source renderer tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test --tests '*JavaSourceRendererTest' --tests '*GeneratedSecretSafetyTest' --no-daemon --non-interactive
```

Expected: compilation fails because Java source renderers do not exist.

- [ ] **Step 3: Implement input and Tool rendering**

Generate one immutable input record per operation. Preserve JSON names with Jackson 3 `@JsonProperty` only when Java and JSON names differ. Apply Jakarta Validation constraints supported by the normalized schema.

The generated Tool shape must be:

```java
@Component
public final class WeatherMcpTools {
    private final OpenApiOperationExecutor executor;

    public WeatherMcpTools(OpenApiOperationExecutor executor) {
        this.executor = executor;
    }

    @McpTool(name = "kma_weather_get_forecast", description = "Get the public weather forecast for a grid location.", generateOutputSchema = true)
    public JsonNode getForecast(
            @McpToolParam(description = "Grid x coordinate", required = true) Integer nx,
            @McpToolParam(description = "Grid y coordinate", required = true) Integer ny) {
        var input = new GetForecastInput(nx, ny);
        return executor.execute(WeatherOperations.GET_FORECAST, input.toArguments());
    }
}
```

Optional values must be inserted into `toArguments()` only when non-null. Required primitive values use boxed Java types plus validation so MCP schema and runtime validation agree.

- [ ] **Step 4: Implement metadata and bundled runtime rendering**

Generate immutable `OperationDefinition`, `ParameterBinding`, and `SecretBinding` runtime records. Generate one metadata constant per Tool with method, path, visible parameter bindings, and environment-backed secret property bindings.

`OpenApiOperationExecutor` must:

1. Build a URI from configured provider base URL and normalized path.
2. Bind path variables, query values, and headers from the input map.
3. Resolve required secrets from Spring `Environment` properties generated in `application.yml`.
4. Attach a JSON request body for POST, PUT, PATCH, and DELETE when defined.
5. Execute with Spring `RestClient`.
6. Read at most `provider.response-max-bytes + 1` bytes and fail on overflow.
7. Parse the body with Jackson 3 `JsonMapper` into `tools.jackson.databind.JsonNode`.
8. Return a structured JSON error without echoing credentials for non-2xx responses.

- [ ] **Step 5: Implement generated application and context test sources**

Generate a `@SpringBootApplication` main class and a `@SpringBootTest(webEnvironment = RANDOM_PORT)` context test. The context test must not call the upstream provider.

- [ ] **Step 6: Run source renderer tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-spring-ai-2:test --no-daemon --non-interactive
```

Expected: golden output, escaping, secret exclusion, metadata order, and runtime source tests pass.

- [ ] **Step 7: Review the task diff without staging**

Confirm generated sources import Spring AI 2 annotation packages and Jackson 3 `tools.jackson` packages, while generator modules continue using Jackson 2 `com.fasterxml.jackson` packages.

---

### Task 6: Write source trees, checksums, manifests, reports, and ZIPs

**Files:**
- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/SafeProjectWriter.java`
- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/SourceTreeChecksum.java`
- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationManifestWriter.java`
- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/ValidationReportWriter.java`
- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/DeterministicZipPackager.java`
- Create: `generator-core/src/main/java/io/gen2spring/mcp/core/GenerationPipeline.java`
- Test: `generator-core/src/test/java/io/gen2spring/mcp/core/SafeProjectWriterTest.java`
- Test: `generator-core/src/test/java/io/gen2spring/mcp/core/SourceTreeChecksumTest.java`
- Test: `generator-core/src/test/java/io/gen2spring/mcp/core/DeterministicZipPackagerTest.java`
- Test: `generator-core/src/test/java/io/gen2spring/mcp/core/GenerationPipelineTest.java`

**Interfaces:**
- Consumes: analyzer, policy factory, profile, project generator, and validator ports.
- Produces: `GenerationPipeline.generate(Path, GenerationRequest, Path): GenerationOutcome`.

- [ ] **Step 1: Write failing path containment and checksum tests**

The path test catches ZIP-slip-style output entries. The checksum test catches map iteration order or execution reports changing the reproducible source checksum.

```java
@Test
void rejectsAProjectEntryOutsideTheOutputRoot() {
    var files = new GeneratedProjectFiles(Map.of("../../escape.txt", "x".getBytes(UTF_8)));

    assertThrows(GeneratorException.class, () -> writer.write(tempDir.resolve("project"), files));
}

@Test
void checksumIgnoresReportsAndMapOrder() {
    var firstFiles = new LinkedHashMap<String, byte[]>();
    firstFiles.put("b.txt", "b".getBytes(UTF_8));
    firstFiles.put("a.txt", "a".getBytes(UTF_8));
    firstFiles.put("VALIDATION_REPORT.json", "first run".getBytes(UTF_8));
    var secondFiles = new LinkedHashMap<String, byte[]>();
    secondFiles.put("a.txt", "a".getBytes(UTF_8));
    secondFiles.put("b.txt", "b".getBytes(UTF_8));
    secondFiles.put("VALIDATION_REPORT.json", "second run".getBytes(UTF_8));

    String first = checksum.calculate(new GeneratedProjectFiles(firstFiles));
    String second = checksum.calculate(new GeneratedProjectFiles(secondFiles));

    assertEquals(first, second);
}
```

- [ ] **Step 2: Run core safety tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-core:test --tests '*SafeProjectWriterTest' --tests '*SourceTreeChecksumTest' --no-daemon --non-interactive
```

Expected: compilation fails because writer and checksum classes do not exist.

- [ ] **Step 3: Implement safe writing and deterministic checksums**

Normalize every relative path, reject absolute paths and paths escaping the root, create parent directories, and write exact bytes. Set generated `gradlew` executable on POSIX filesystems. Reject a pre-existing output root.

Hash sorted UTF-8 relative path bytes, a zero delimiter, normalized file bytes, and a second zero delimiter. Exclude `GENERATION_MANIFEST.json`, `VALIDATION_REPORT.json`, `.zip`, and process logs by exact path/category, not substring matching.

- [ ] **Step 4: Write failing deterministic ZIP test and verify RED**

```java
@Test
void packagesEntriesInOrderWithFixedTimestamps() {
    byte[] first = packager.packageProject(projectRoot, tempDir.resolve("first.zip"));
    byte[] second = packager.packageProject(projectRoot, tempDir.resolve("second.zip"));

    assertArrayEquals(first, second);
}
```

Implement ZIP entries in lexical path order with timestamp `1980-01-01T00:00:00Z`, normalized separators, no symlink following, and no output archive included in itself.

- [ ] **Step 5: Write the failing pipeline state test**

Use small deterministic test fakes that return real domain values; assert observable files and outcome rather than fake call counts.

```java
@Test
void validationFailureKeepsAnUnverifiedDirectoryAndDoesNotCreateAZip() {
    var validator = (GeneratedProjectValidator) validationRequest -> new ValidationReport(
            ValidationStatus.UNVERIFIED,
            List.of(new ValidationStageResult("COMPILE", StageStatus.FAILED, 10, 0, 1, "Compilation failed")),
            List.of());
    var pipeline = pipelineWithValidator(validator);
    var outcome = pipeline.generate(
            resource("openapi/simple-weather.yaml"),
            weatherGenerationRequest(),
            tempDir.resolve("weather"));

    assertEquals(ValidationStatus.UNVERIFIED, outcome.validationStatus());
    assertTrue(Files.exists(outcome.projectRoot().resolve("VALIDATION_REPORT.json")));
    assertFalse(Files.exists(tempDir.resolve("weather.zip")));
}
```

Define `pipelineWithValidator(GeneratedProjectValidator)` in the same test with the real analyzer, policies, profile, writer, checksum, JSON writers, and ZIP packager. Its only fake is a `ProjectGenerator` that returns `README.md`, `settings.gradle.kts`, and `build.gradle.kts` byte entries. Define `weatherGenerationRequest()` with the exact `com.example:weather-mcp-server`, `com.example.weather`, `kma`, `weather`, pinned profile, `MCP_PROTOCOL`, and enabled `getForecast` values used by the fixture.

- [ ] **Step 6: Implement pipeline, manifest, and report writing**

The pipeline must analyze, build Tool IR, validate the exact profile, generate files, write a new root, calculate source checksum, write the manifest, validate, write the report, and package only `VALIDATED` results. Stage errors must retain their PRD code and safe message.

`GENERATION_MANIFEST.json` records generator `0.1.0`, template `spring-ai-2-v1`, runtime `0.1.0`, profile ID, Spring Boot `4.1.0`, Spring AI `2.0.0`, Java `21`, Gradle `9.6.1`, original specification checksum, source checksum, and original-operation-to-Tool mappings. `VALIDATION_REPORT.json` records overall status, ordered stage results, bounded summaries, observed Tools, and measured durations; it never records raw process output.

- [ ] **Step 7: Run all core tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-core:test --no-daemon --non-interactive
```

Expected: path safety, checksums, ZIP determinism, successful pipeline, and unverified failure policy pass.

- [ ] **Step 8: Review the task diff without staging**

Confirm tests assert filesystem outcomes, no production cleanup API exists only for tests, and no pre-existing output is removed.

---

### Task 7: Validate compilation, startup, and MCP protocol

**Files:**
- Create: `generator-validation/src/main/java/io/gen2spring/mcp/validation/BoundedProcessRunner.java`
- Create: `generator-validation/src/main/java/io/gen2spring/mcp/validation/LoopbackPortAllocator.java`
- Create: `generator-validation/src/main/java/io/gen2spring/mcp/validation/McpStreamableHttpClient.java`
- Create: `generator-validation/src/main/java/io/gen2spring/mcp/validation/GradleMcpProjectValidator.java`
- Test: `generator-validation/src/test/java/io/gen2spring/mcp/validation/BoundedProcessRunnerTest.java`
- Test: `generator-validation/src/test/java/io/gen2spring/mcp/validation/McpStreamableHttpClientTest.java`
- Test: `generator-validation/src/test/java/io/gen2spring/mcp/validation/GradleMcpProjectValidatorTest.java`
- Test support: `generator-validation/src/test/java/io/gen2spring/mcp/validation/support/SleepingProcess.java`
- Test support: `generator-validation/src/test/java/io/gen2spring/mcp/validation/support/McpTestServer.java`

**Interfaces:**
- Consumes: `ValidationRequest`.
- Produces: `GradleMcpProjectValidator implements GeneratedProjectValidator`.

- [ ] **Step 1: Write failing real-process tests**

The timeout test catches orphaned subprocesses. Use the current Java executable and a test helper class instead of shell commands.

```java
@Test
void terminatesAProcessAfterTheConfiguredTimeout() {
    var result = runner.run(javaCommand(SleepingProcess.class), tempDir, Duration.ofMillis(200), 64 * 1024);

    assertTrue(result.timedOut());
    assertFalse(result.processAlive());
}
```

Define the test command without a shell:

```java
private static List<String> javaCommand(Class<?> mainClass) {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    return List.of(java.toString(), "-cp", System.getProperty("java.class.path"), mainClass.getName());
}
```

`SleepingProcess.main` calls `Thread.sleep(10_000)` and has no other behavior.

- [ ] **Step 2: Run process tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-validation:test --tests '*BoundedProcessRunnerTest' --no-daemon --non-interactive
```

Expected: compilation fails because `BoundedProcessRunner` does not exist.

- [ ] **Step 3: Implement bounded process execution**

Use `ProcessBuilder(List<String>)`, a fixed working directory, separate bounded stdout/stderr collectors, `waitFor(timeout)`, graceful destroy, and forcible destroy fallback. Never use `sh -c`, `bash -c`, or command-string concatenation.

- [ ] **Step 4: Write failing MCP HTTP contract tests**

Use JDK `HttpServer` as a real local server. Return one initialize response as `application/json` and tools/list as `text/event-stream`. Require the client to propagate `Mcp-Session-Id`.

```java
@Test
void initializesThenListsToolsAcrossJsonAndSseResponses() {
    try (var server = McpTestServer.startWithJsonInitializeAndSseToolsList()) {
        var result = client.validate(server.uri(), Map.of(
                "kma_weather_get_forecast", new ExpectedTool("Get the public weather forecast for a grid location.")));

        assertEquals(Set.of("kma_weather_get_forecast"), result.toolNames());
        assertTrue(server.receivedInitializedNotification());
        assertTrue(server.receivedSessionHeaderOnToolsList());
    }
}
```

`McpTestServer` owns a JDK `HttpServer`, exposes `startWithJsonInitializeAndSseToolsList()`, `uri()`, the two observed-state accessors, and `close()`. It parses each request body with Jackson and returns fixed literal protocol responses rather than calling production response builders.

- [ ] **Step 5: Implement Streamable HTTP MCP validation**

Send these JSON-RPC messages with `Content-Type: application/json` and `Accept: application/json, text/event-stream`:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"openapi-mcp-generator-validator","version":"0.1.0"}}}
```

```json
{"jsonrpc":"2.0","method":"notifications/initialized"}
```

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
```

Support both a JSON body and SSE `data:` lines. Read `Mcp-Session-Id` case-insensitively and propagate it. Validate Tool name, description, and input schema presence without depending on response field order.

- [ ] **Step 6: Implement the Gradle and application validator**

Run generated `./gradlew classes test bootJar --no-daemon --non-interactive` with a five-minute timeout. Start `java -jar build/libs/<artifactId>.jar --server.port=<allocatedPort>` with a one-minute startup timeout. Poll only loopback, validate MCP, and terminate the application in a `finally` block. Return stage durations and bounded safe summaries.

- [ ] **Step 7: Run validation tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-validation:test --no-daemon --non-interactive
```

Expected: timeout cleanup, JSON/SSE parsing, session propagation, Tool validation, and failed-build reporting tests pass.

- [ ] **Step 8: Review the task diff without staging**

Confirm all outbound validator traffic targets a computed loopback URI and no secret-bearing process output is persisted unmasked.

---

### Task 8: Wire the CLI and configuration contract

**Files:**
- Create: `generator-cli/src/main/java/io/gen2spring/mcp/cli/Main.java`
- Create: `generator-cli/src/main/java/io/gen2spring/mcp/cli/CliApplication.java`
- Create: `generator-cli/src/main/java/io/gen2spring/mcp/cli/CommandLine.java`
- Create: `generator-cli/src/main/java/io/gen2spring/mcp/cli/GenerationConfigurationReader.java`
- Create: `generator-cli/src/main/java/io/gen2spring/mcp/cli/ApplicationFactory.java`
- Test: `generator-cli/src/test/java/io/gen2spring/mcp/cli/CommandLineTest.java`
- Test: `generator-cli/src/test/java/io/gen2spring/mcp/cli/GenerationConfigurationReaderTest.java`
- Test: `generator-cli/src/test/java/io/gen2spring/mcp/cli/CliApplicationTest.java`
- Test resource: `generator-cli/src/test/resources/config/weather-generation.yaml`

**Interfaces:**
- Consumes: analyzer, profile, Tool policy, pipeline, emitter, and validator implementations.
- Produces: `openapi-mcp profiles`, `inspect`, and `generate` executable commands.

- [ ] **Step 1: Write failing argument and configuration tests**

The argument tests catch accepting missing/duplicate flags. The configuration test catches an invalid package, environment variable, profile, or empty operation selection before generation.

```java
@Test
void parsesInspectWithoutPermittingUnknownFlags() {
    var parsed = commandLine.parse(new String[] {
            "inspect", "--spec", "weather.yaml", "--output", "analysis.json"
    });

    assertEquals("inspect", parsed.command());
    assertEquals(Path.of("weather.yaml"), parsed.specification());
    assertThrows(CliUsageException.class,
            () -> commandLine.parse(new String[] {"inspect", "--unknown", "x"}));
}
```

- [ ] **Step 2: Run CLI tests and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-cli:test --no-daemon --non-interactive
```

Expected: compilation fails because CLI classes do not exist.

- [ ] **Step 3: Implement strict CLI parsing and config reading**

Return code `0` for success, `2` for usage/config, `3` for parsing/semantic validation, `4` for source generation, `5` for compile/MCP validation, and `6` for packaging. `CliApplication.run(String[], PrintWriter, PrintWriter)` returns the code; only `Main.main` calls `System.exit`.

Use Jackson YAML with unknown-property rejection. Validate project coordinates, profile ID, operation IDs, Tool names, and environment variable names before pipeline work. Never deserialize polymorphic types or enable default typing.

- [ ] **Step 4: Implement command behavior**

- `profiles`: print the single pinned profile as JSON.
- `inspect`: analyze the specification and write stable pretty JSON to a new output file.
- `generate`: read YAML configuration, invoke the pipeline, print project/report/archive paths as JSON, and return `5` for `UNVERIFIED`.

Refuse to overwrite every inspect output, project directory, report, and archive.

- [ ] **Step 5: Run CLI tests and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-cli:test --no-daemon --non-interactive
```

Expected: command parsing, YAML strictness, exit-code mapping, new-file policy, and output JSON tests pass.

- [ ] **Step 6: Build the CLI distribution**

Run:

```bash
mise exec -- ./gradlew :generator-cli:installDist --no-daemon --non-interactive
```

Expected: `generator-cli/build/install/openapi-mcp/bin/openapi-mcp` exists and `profiles` exits `0`.

- [ ] **Step 7: Review the task diff without staging**

Confirm `Main` is the only code path that exits the JVM and tests exercise `CliApplication` without global stream replacement.

---

### Task 9: Prove the complete P0 journey and document usage

**Files:**
- Create: `generator-cli/src/integrationTest/java/io/gen2spring/mcp/cli/P0GenerationIntegrationTest.java`
- Create: `generator-cli/src/integrationTest/resources/openapi/weather-api.yaml`
- Create: `generator-cli/src/integrationTest/resources/config/weather-generation.yaml`
- Create: `README.md`
- Modify: `generator-cli/build.gradle.kts`

**Interfaces:**
- Consumes: the complete CLI composition and actual external Gradle/MCP validators.
- Produces: verified end-to-end evidence for the P0 release criteria.

- [ ] **Step 1: Write the failing end-to-end integration test**

The production changes caught by this test are missing generated files, generated code that does not compile, an application that does not start, or MCP tools/list that does not match the selected operation.

```java
@Test
void generatesCompilesStartsAndListsTheSelectedTool() {
    var stdoutBytes = new ByteArrayOutputStream();
    var stderrBytes = new ByteArrayOutputStream();
    var stdout = new PrintWriter(stdoutBytes, true, UTF_8);
    var stderr = new PrintWriter(stderrBytes, true, UTF_8);
    var application = ApplicationFactory.create();
    Path output = tempDir.resolve("weather-mcp-server");
    int exitCode = application.run(new String[] {
            "generate",
            "--spec", resource("openapi/weather-api.yaml").toString(),
            "--config", resource("config/weather-generation.yaml").toString(),
            "--output", output.toString()
    }, stdout, stderr);

    assertEquals(0, exitCode, stderrBytes.toString(UTF_8));
    assertTrue(Files.exists(output.resolve("GENERATION_MANIFEST.json")));
    assertTrue(Files.exists(output.resolve("VALIDATION_REPORT.json")));
    assertTrue(Files.exists(output.resolveSibling("weather-mcp-server.zip")));
    assertEquals("VALIDATED", readReport(output).path("status").asText());
    assertEquals(List.of("kma_weather_get_forecast"), readReport(output).path("tools").findValuesAsText("name"));
}
```

Declare `@TempDir Path tempDir`. Define `resource(String)` by resolving a classpath URI to a `Path`. Define `readReport(Path)` with a test-local Jackson 2 `ObjectMapper` reading `VALIDATION_REPORT.json`; do not call a production report writer to construct expectations.

Register a real Gradle JVM integration test suite in `generator-cli/build.gradle.kts`:

```kotlin
testing {
    suites {
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter("5.13.4")
            dependencies {
                implementation(project())
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.test)
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("integrationTest"))
}
```

- [ ] **Step 2: Run the integration test and verify RED**

Run:

```bash
mise exec -- ./gradlew :generator-cli:integrationTest --no-daemon --non-interactive
```

Expected before final wiring: failure at the first incomplete generation or validation boundary, not a fixture/configuration error.

- [ ] **Step 3: Complete only the wiring exposed by the failing journey**

Make the minimum pipeline, generated runtime, validator, or packaging correction required by the observed failure. Do not expand into P1 features.

- [ ] **Step 4: Re-run the integration test and verify GREEN**

Run:

```bash
mise exec -- ./gradlew :generator-cli:integrationTest --no-daemon --non-interactive
```

Expected: the generated Boot 4.1.0/Spring AI 2.0.0 project compiles, starts, returns the expected Tool from MCP tools/list, and packages a validated ZIP.

- [ ] **Step 5: Add root usage documentation**

Document Java 21 and mise prerequisites, wrapper usage, the three CLI commands, the generation YAML schema, API Key environment variables, generated MCP endpoint, validation stages, output files, P0 limitations, and cleanup responsibility. Use the repository's existing Korean documentation language.

- [ ] **Step 6: Run the complete verification suite**

Run:

```bash
mise exec -- ./gradlew clean test integrationTest :generator-cli:installDist --no-daemon --non-interactive
```

Then run the installed CLI against the integration fixture in a new temporary directory and inspect its report and archive:

```bash
generator-cli/build/install/openapi-mcp/bin/openapi-mcp profiles
generator-cli/build/install/openapi-mcp/bin/openapi-mcp inspect --spec generator-cli/src/integrationTest/resources/openapi/weather-api.yaml --output /tmp/gen2spring-weather-analysis.json
generator-cli/build/install/openapi-mcp/bin/openapi-mcp generate --spec generator-cli/src/integrationTest/resources/openapi/weather-api.yaml --config generator-cli/src/integrationTest/resources/config/weather-generation.yaml --output /tmp/gen2spring-weather-mcp
unzip -t /tmp/gen2spring-weather-mcp.zip
```

Expected: every command exits `0`, the report status is `VALIDATED`, the Tool list contains `kma_weather_get_forecast`, and `unzip -t` reports no errors.

- [ ] **Step 7: Re-read the PRD P0 list and inspect the final diff**

Map every P0 item to an implementation file and test. Record any unsupported behavior as a documented P1 boundary. Run `git diff --check`, `git status --short`, and inspect all untracked files. Do not stage or commit.
