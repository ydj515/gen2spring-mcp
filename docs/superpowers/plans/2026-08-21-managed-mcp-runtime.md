# Managed MCP Runtime v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Activate an immutable owner-scoped Tool Catalog as a bearer-authenticated Managed MCP Runtime that serves exact `tools/list` and bounded credential-free `tools/call` through isolated provider egress.

**Architecture:** `apps/web` owns runtime activation and revocation, while a separate `apps/runtime` owns MCP sessions and one immutable MCP Java SDK server handle per runtime instance. Provider HTTP traffic crosses an mTLS-only `apps/provider-egress` boundary so the runtime never combines platform database access with direct external egress.

**Tech Stack:** Java 21, Spring Boot 3.5.16, MCP Java SDK 0.18.3 with Jackson 2, PostgreSQL 17.9, Flyway, Spring MVC functional routing, JDK/Apache HTTP clients, Micrometer, Testcontainers, Docker Compose

**Spec:** `docs/superpowers/specs/2026-08-21-managed-mcp-runtime-design.md`

## Global Constraints

- Do not modify generated Spring AI 1 or Spring AI 2 download contents for this feature.
- `McpJavaSdkEmitter` consumes persisted `RuntimeTool`; it does not implement source-generation `ToolEmitter`.
- Pin MCP Java SDK to `0.18.3`; do not depend on Spring AI in Managed Runtime modules.
- One runtime instance maps to exactly one immutable Catalog checksum and one isolated SDK server handle.
- Reject Catalog activation when any Tool declares a credential slot.
- Accept provider destinations only through provider-egress; `apps/runtime` has no direct external egress.
- HTTP/HTTPS ports are limited to 80/443, redirects are disabled, and private/reserved destinations fail closed.
- Plaintext runtime tokens are returned once, never persisted, and never logged.
- Preserve fatal `Error`, provider-vs-internal failure semantics, exact schemas, bounded retry/pagination/response size, and canonical response normalization.
- Initial transport is session-based Streamable HTTP on one runtime replica; do not claim stateless or multi-replica support.
- PostgreSQL deployment remains `postgres:17.9-alpine` with the existing pinned digest.
- Keep production files focused by responsibility; split orchestration, transport, protocol, and persistence rather than growing one composite class.

---

### Task 1: Define Managed Runtime Domain Contracts

**Files:**
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/runtime/RuntimeInstanceId.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/runtime/ManagedRuntimeInstance.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/runtime/ProviderTarget.java`
- Test: `modules/domain/src/test/java/io/gen2spring/mcp/domain/platform/runtime/ManagedRuntimeInstanceTest.java`
- Test: `modules/domain/src/test/java/io/gen2spring/mcp/domain/platform/runtime/ProviderTargetTest.java`

**Interfaces:**

```java
public record RuntimeInstanceId(UUID value) {
    public static RuntimeInstanceId parse(String value);
}

public record ManagedRuntimeInstance(
        RuntimeInstanceId id,
        AccountId owner,
        UUID catalogId,
        String catalogChecksum,
        Optional<ProviderTarget> providerBaseUrl,
        Instant createdAt,
        Instant expiresAt,
        Optional<Instant> revokedAt) {
    public RuntimeState stateAt(Instant now);
    public ManagedRuntimeInstance revokeAt(Instant now);
}

public record ProviderTarget(URI uri) {
    public static ProviderTarget parse(String value);
}
```

- [ ] Write tests for invalid IDs, SHA-256 checksum, lifetime ordering, one-way revocation, derived ACTIVE/EXPIRED/REVOKED state, safe `toString`, and provider URL scheme/port/userinfo/query/fragment/control bounds.
- [ ] Run `mise exec -- ./gradlew :modules:domain:test --tests '*ManagedRuntimeInstanceTest' --tests '*ProviderTargetTest' --no-daemon --non-interactive --rerun-tasks`; expect compile RED because the types do not exist.
- [ ] Implement immutable value objects with fixed non-leaking validation messages; keep DNS/address policy outside `ProviderTarget` because connect-time resolution belongs to provider-egress.
- [ ] Re-run the focused tests and full `:modules:domain:test`; expect GREEN.
- [ ] Commit the Task 1 files with `feat(domain): define managed runtime instances`.

### Task 2: Add Activation, Authentication, and Revocation Use Cases

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/ManagedRuntimeStore.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/RuntimeTokenCodec.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/RuntimeTokenDigest.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/IssuedRuntimeToken.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/RuntimeActivation.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/RuntimeAccess.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/ManagedRuntimeService.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/RuntimeAccessAuthenticator.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/managed/runtime/ManagedRuntimeServiceTest.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/managed/runtime/RuntimeAccessAuthenticatorTest.java`

**Interfaces:**

```java
public interface RuntimeTokenCodec {
    IssuedRuntimeToken issue();
    boolean matches(String presentedToken, RuntimeTokenDigest persistedDigest);
}

public interface ManagedRuntimeStore {
    void create(ManagedRuntimeInstance instance, RuntimeTokenDigest digest);
    Optional<StoredRuntime> find(RuntimeInstanceId id);
    boolean revoke(AccountId owner, RuntimeInstanceId id, Instant revokedAt);

    record StoredRuntime(ManagedRuntimeInstance instance, RuntimeTokenDigest tokenDigest) {}
}

public record RuntimeTokenDigest(byte[] value) {}
public record IssuedRuntimeToken(String plaintext, RuntimeTokenDigest digest) {}
public record RuntimeAccess(ManagedRuntimeInstance instance) {}
public record RuntimeActivation(ManagedRuntimeInstance instance, String plaintextToken, URI endpoint) {}

public final class ManagedRuntimeService {
    public RuntimeActivation activate(AccountId owner, UUID catalogId,
            Optional<String> providerBaseUrl, Duration lifetime);
    public ManagedRuntimeInstance require(AccountId owner, RuntimeInstanceId id);
    public void revoke(AccountId owner, RuntimeInstanceId id);
}

public final class RuntimeAccessAuthenticator {
    public RuntimeAccess authenticate(RuntimeInstanceId id, String bearerToken);
}
```

- [ ] Write service tests proving owner isolation, Catalog checksum revalidation, relative-base override requirement, absolute-base handling, credential-slot rejection, 24-hour default, 30-day maximum, create-before-return, one-time plaintext delivery, equivalent missing/foreign errors, expiry, revocation, and fatal `Error` propagation.
- [ ] Write authenticator tests proving one store lookup, constant contract for malformed/wrong/expired/revoked credentials, no cached authorization, digest comparison delegation, and no token text in messages.
- [ ] Run the two focused tests; expect compile RED.
- [ ] Implement the use cases against `ToolCatalogService`, `ManagedRuntimeStore`, `RuntimeTokenCodec`, and `Clock`; use fixed safe exception types for invalid request, not found, unavailable, and unauthorized.
- [ ] Re-run focused and full application tests; expect GREEN.
- [ ] Commit with `feat(application): manage runtime activation lifecycle`.

### Task 3: Implement Runtime Token Cryptography

**Files:**
- Create: `modules/adapters/cryptography/src/main/java/io/gen2spring/mcp/adapter/cryptography/HmacRuntimeTokenCodec.java`
- Test: `modules/adapters/cryptography/src/test/java/io/gen2spring/mcp/adapter/cryptography/HmacRuntimeTokenCodecTest.java`

**Interfaces:**

```java
public final class HmacRuntimeTokenCodec implements RuntimeTokenCodec {
    public HmacRuntimeTokenCodec(Path pepperFile);
}
```

- [ ] Write RED tests for a 32-byte owner-only regular pepper file, symlink/short/broad-permission rejection, 256-bit random URL-safe token format, deterministic HMAC digest, constant-time correct/wrong comparison, defensive byte copies, redacted `toString`, and fixed failures.
- [ ] Run `:modules:adapters:cryptography:test --tests '*HmacRuntimeTokenCodecTest'`; expect compile RED.
- [ ] Implement HMAC-SHA-256 with a domain-separated prefix and `MessageDigest.isEqual`; avoid `String` conversion for digest bytes and clear temporary key bytes.
- [ ] Re-run focused and full cryptography tests; expect GREEN on POSIX and Windows permission-view behavior.
- [ ] Commit with `feat(security): protect managed runtime tokens`.

### Task 4: Persist Runtime Instances in PostgreSQL

**Files:**
- Create: `modules/adapters/persistence-postgres/src/main/resources/db/migration/V5__managed_runtime.sql`
- Create: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresManagedRuntimeStore.java`
- Test: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresManagedRuntimeStoreTest.java`
- Modify: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresMigrationTest.java`

**Interfaces:** Consumes `ManagedRuntimeStore`; produces owner-safe create/find/revoke persistence.

- [ ] Write Testcontainers RED tests for Catalog FK ownership, 32-byte digest, checksum pin, optional provider URL, lifetime checks, create uniqueness, equivalent lookup, one-way idempotent revocation, concurrent revoke, and cascade behavior.
- [ ] Run the focused persistence tests; expect migration/table RED.
- [ ] Add the V5 table, constraints, owner/catalog composite FK, expiry index, and store implementation using explicit columns and transaction-safe updates.
- [ ] Re-run focused and full persistence tests against PostgreSQL 17.9; expect GREEN.
- [ ] Commit with `feat(persistence): store managed runtime instances`.

### Task 5: Expose Owner-scoped Runtime Control APIs

**Files:**
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedManagedRuntimeController.java`
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/ManagedRuntimeResponse.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/config/HostedWebConfiguration.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/error/WebErrorMapper.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedManagedRuntimeControllerTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedManagedRuntimeMvcContractTest.java`

**HTTP contract:**

```text
POST /api/tool-catalogs/{catalogId}/runtimes
GET  /api/runtimes/{runtimeId}
POST /api/runtimes/{runtimeId}/revocation
```

- [ ] Write RED controller and MockMvc tests for authenticated owner resolution, CSRF, UUID/body bounds, optional provider base URL, one-time token response, token absence on GET, revocation, foreign/missing 404 equivalence, fixed 400/401/404/503 errors, and no input echo.
- [ ] Run the two focused Web tests; expect bean/controller RED.
- [ ] Implement DTO parsing and controller delegation only; keep token creation, ownership, lifetime, and Catalog validation in application services.
- [ ] Re-run focused and full Web unit tests; expect GREEN.
- [ ] Commit with `feat(web): manage catalog runtime activation`.

### Task 6: Build Canonical Managed HTTP Requests

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/ProviderCallRequest.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/ProviderCallResponse.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/ProviderCallClient.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/RuntimeHttpRequestFactory.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/managed/execution/RuntimeHttpRequestFactoryTest.java`

**Interfaces:**

```java
public interface ProviderCallClient {
    ProviderCallResponse execute(ProviderCallRequest request, Duration timeout);
}

public final class RuntimeHttpRequestFactory {
    public ProviderCallRequest create(RuntimeTool tool, Optional<ProviderTarget> override,
            Map<String, Object> arguments);
}
```

- [ ] Write independent RED fixtures for scalar/path encoding, repeated query arrays, header binding, object body absent/required behavior, exact raw JSON names, relative-base override, reserved headers, duplicate targets, unexpected arguments, null rejection, and immutable bounded transport values.
- [ ] Run the focused test; expect compile RED.
- [ ] Implement request construction without Spring or MCP SDK types; preserve decimal precision and the current OpenAPI wire-style support boundary.
- [ ] Re-run focused and full application tests; expect GREEN.
- [ ] Commit with `feat(runtime): bind managed provider requests`.

### Task 7: Execute and Normalize Managed Tool Calls

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/ManagedToolResult.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/ManagedRuntimeBinding.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/ManagedToolCallHandler.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/RuntimeResponseNormalizer.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/ManagedToolExecutor.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/ManagedExecutionLimits.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/managed/execution/RuntimeResponseNormalizerTest.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/managed/execution/ManagedToolExecutorTest.java`

**Interfaces:**

```java
public final class ManagedToolExecutor {
    public ManagedToolResult call(ManagedRuntimeBinding runtime, String toolName,
            Map<String, Object> arguments);
}

public record ManagedRuntimeBinding(
        ManagedRuntimeInstance instance,
        RuntimeMetadataArtifact metadata) {}

public interface ManagedToolCallHandler {
    ManagedToolResult call(String toolName, Map<String, Object> arguments);
}
```

- [ ] Port independent expected fixtures, not renderer code, for empty success, JSON and `+json`, malformed media, status-first 4xx/5xx, typed/generic normalization, precise decimals, pagination, retry classification, timeout, response size, queue saturation, late completion, provider-safe errors, internal errors, interruption, and fatal `Error` identity.
- [ ] Run both focused tests; expect compile RED.
- [ ] Implement response normalization and bounded orchestration with one retry owner, one completion owner, bounded executor/queue, and caller-owned timeout semantics.
- [ ] Run mutation checks for status-first classification, high-precision decimals, duplicate completion, and fatal-cause ordering; each mutation must produce RED before restoration.
- [ ] Re-run focused and full domain/application tests; expect GREEN.
- [ ] Commit with `feat(runtime): execute managed tool calls`.

### Task 8: Add the Provider-egress Client Adapter

**Files:**
- Create: `modules/adapters/provider-egress/build.gradle.kts`
- Create: `modules/adapters/provider-egress/src/main/java/io/gen2spring/mcp/adapter/provideregress/GatewayProviderCallClient.java`
- Create: `modules/adapters/provider-egress/src/main/java/io/gen2spring/mcp/adapter/provideregress/ProviderEgressCodec.java`
- Test: `modules/adapters/provider-egress/src/test/java/io/gen2spring/mcp/adapter/provideregress/GatewayProviderCallClientTest.java`
- Modify: `settings.gradle.kts`

**Interfaces:** Implements `ProviderCallClient` over a private mTLS HTTP endpoint.

- [ ] Write RED tests for exact bounded request/response JSON, method/body/header preservation, timeout, TLS failure, non-2xx gateway failure, malformed/oversized response, interruption, fixed safe errors, and absence of runtime token/account/Catalog fields.
- [ ] Run the focused adapter test; expect missing module/type RED.
- [ ] Implement the codec and client with strict duplicate-field detection, exact integer/decimal handling, and bounded reads.
- [ ] Re-run focused and full adapter tests; expect GREEN.
- [ ] Commit with `feat(runtime): add provider egress client`.

### Task 9: Implement the Isolated Provider-egress Service

**Files:**
- Create: `apps/provider-egress/build.gradle.kts`
- Create: `apps/provider-egress/src/main/java/io/gen2spring/mcp/app/provideregress/ProviderEgressApplication.java`
- Create: `apps/provider-egress/src/main/java/io/gen2spring/mcp/app/provideregress/ProviderEgressController.java`
- Create: `apps/provider-egress/src/main/java/io/gen2spring/mcp/app/provideregress/ProviderTransport.java`
- Create: `apps/provider-egress/src/main/java/io/gen2spring/mcp/app/provideregress/ValidatedProviderResolver.java`
- Create: `apps/provider-egress/src/main/java/io/gen2spring/mcp/app/provideregress/ProviderEgressSecurityConfiguration.java`
- Test: `apps/provider-egress/src/test/java/io/gen2spring/mcp/app/provideregress/ProviderEgressContractTest.java`
- Test: `apps/provider-egress/src/test/java/io/gen2spring/mcp/app/provideregress/ProviderEgressSecurityTest.java`
- Modify: `settings.gradle.kts`

**Interfaces:** Private mTLS `POST /internal/provider-call`; no MCP, owner, Catalog, token, or database types.

- [ ] Write RED tests for public DNS success, loopback/private/link-local/multicast/reserved/metadata denial, mixed DNS answers, connect-address recheck, ports, redirects, host header ownership, hop-by-hop stripping, request/response bounds, slow body, decompression limits, response cleanup, and mTLS-only access.
- [ ] Run focused service tests; expect missing application RED.
- [ ] Implement resolve-and-connect ownership in one process, reusing the domain `NetworkAddressPolicy` classification while keeping provider method/body handling separate from URL-import logic.
- [ ] Re-run focused and full provider-egress tests; expect GREEN with no surviving server/client threads.
- [ ] Commit with `feat(security): isolate managed provider egress`.

### Task 10: Add the MCP Java SDK Emitter

**Files:**
- Modify: `gradle/libs.versions.toml`
- Create: `modules/adapters/mcp-java-sdk/build.gradle.kts`
- Create: `modules/adapters/mcp-java-sdk/src/main/java/io/gen2spring/mcp/adapter/mcp/McpJavaSdkEmitter.java`
- Create: `modules/adapters/mcp-java-sdk/src/main/java/io/gen2spring/mcp/adapter/mcp/McpToolResultMapper.java`
- Test: `modules/adapters/mcp-java-sdk/src/test/java/io/gen2spring/mcp/adapter/mcp/McpJavaSdkEmitterTest.java`
- Modify: `settings.gradle.kts`

**Interfaces:**

```java
public final class McpJavaSdkEmitter {
    public List<McpServerFeatures.SyncToolSpecification> emit(
            List<RuntimeTool> tools,
            ManagedToolCallHandler handler);
}
```

- [ ] Add a version-catalog RED test or dependency assertion fixing MCP Java SDK, Jackson 2 mapper, and WebMVC transport at `0.18.3` without Spring AI.
- [ ] Write an independent exact-literal RED test for Tool name, multiline description, full input schema, ordering, handler mapping, success content, provider `isError=true`, fixed internal failure, invalid inputs, and fatal `Error` propagation.
- [ ] Run the focused adapter test; expect missing module/type RED.
- [ ] Implement immutable specifications with `McpSchema.Tool` and `SyncToolSpecification`; do not call source renderer helpers or expose SDK types outside the adapter.
- [ ] Re-run focused and full adapter tests; expect GREEN.
- [ ] Commit with `feat(mcp): emit dynamic SDK tools`.

### Task 11: Assemble the Managed Runtime Application

**Files:**
- Create: `apps/runtime/build.gradle.kts`
- Create: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/ManagedRuntimeApplication.java`
- Create: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/RuntimeConfiguration.java`
- Create: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/RuntimeBearerFilter.java`
- Create: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/RuntimeServerHandle.java`
- Create: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/RuntimeServerHandleRegistry.java`
- Create: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/ManagedMcpRouter.java`
- Create: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/RuntimeProperties.java`
- Test: `apps/runtime/src/test/java/io/gen2spring/mcp/app/runtime/RuntimeBearerFilterTest.java`
- Test: `apps/runtime/src/test/java/io/gen2spring/mcp/app/runtime/RuntimeServerHandleRegistryTest.java`
- Test: `apps/runtime/src/test/java/io/gen2spring/mcp/app/runtime/ManagedMcpRouterTest.java`
- Modify: `settings.gradle.kts`

**Handle contract:** One fixed `/mcp/{runtimeId}` WebMVC transport, one `McpSyncServer`, one router function, and one Catalog checksum per handle.

- [ ] Write RED tests proving authentication before cache access, equivalent 401 responses, no browser session acceptance, one handle build under concurrency, no handle sharing across runtime IDs, no global `addTool/removeTool`, exact router delegation, bounded cache/TTL, graceful session cleanup on expiry/revocation, active-session-preserving capacity rejection, failed-cleanup retention, and fixed startup bounds.
- [ ] Run focused application tests; expect missing app/types RED.
- [ ] Assemble Boot/JDBC/Micrometer/MCP SDK wiring and a dynamic delegating router. Build each SDK server once, close it on lifecycle removal paths, return fixed HTTP 503 instead of evicting a live handle at capacity, and keep SDK 0.18.3 types inside app/adapter boundaries.
- [ ] Re-run focused and full runtime tests; inspect for leftover threads and sessions; expect GREEN.
- [ ] Commit with `feat(runtime): serve catalog-backed MCP sessions`.

### Task 12: Verify the Live Managed Runtime Journey

**Files:**
- Create: `apps/runtime/src/integrationTest/java/io/gen2spring/mcp/app/runtime/ManagedRuntimeJourneyIntegrationTest.java`
- Create: `apps/runtime/src/integrationTest/java/io/gen2spring/mcp/app/runtime/ManagedRuntimeIsolationIntegrationTest.java`
- Modify: `apps/runtime/build.gradle.kts`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedWebMvcContractTest.java`

**Journey:** owner activation -> one-time token -> raw MCP initialize -> exact `tools/list` -> exact `tools/call` -> one provider-egress request -> normalized result -> revoke -> next request denied.

- [ ] Write the integration test first with an independent raw MCP client, literal schema/result fixtures, PostgreSQL 17.9, and a test provider-egress server; intentionally mutate one schema property to capture RED.
- [ ] Add cross-runtime isolation tests with two owners, two Catalogs, two tokens, concurrent sessions, wrong-token/runtime pairs, capacity rejection without active-session eviction, and no Tool leakage.
- [ ] Run `:apps:runtime:integrationTest`; expect RED before final wiring and GREEN after restoring the exact schema.
- [ ] Verify exact one upstream request, 250 ms late-request seal, safe provider/internal errors, revocation, expiry, process/thread cleanup, and token/argument/body leak scans.
- [ ] Commit with `test(runtime): verify managed MCP isolation`.

### Task 13: Wire Hosted Deployment and Operations

**Files:**
- Create: `deploy/hosted/runtime/Dockerfile`
- Create: `deploy/hosted/provider-egress/Dockerfile`
- Modify: `deploy/hosted/compose.yml`
- Modify: `deploy/hosted/compose.env.example`
- Modify: `deploy/hosted/proxy/nginx.conf`
- Modify: `deploy/hosted/bin/validate.sh`
- Modify: `deploy/hosted/README.md`
- Modify: `mise.toml`
- Test: `modules/adapters/container-runtime/src/test/java/io/gen2spring/mcp/adapter/container/HostedComposeContractTest.java`

- [ ] Write RED deployment assertions for runtime/provider-egress services, proxy route, exact network separation, secret scope, PostgreSQL dependency, mTLS, no OIDC/MinIO/Docker secrets, numeric users, read-only roots, tmpfs, dropped capabilities, healthchecks, and unchanged PostgreSQL 17.9 digest.
- [ ] Run the focused deployment test and `deploy/hosted/bin/validate.sh`; expect RED for missing services.
- [ ] Add Docker/Compose/proxy/secret wiring and `hosted:runtime` plus expanded `hosted:acceptance` mise tasks.
- [ ] Re-run deployment assertions, Compose config validation, and Dockerfile checks; expect GREEN without starting production services.
- [ ] Commit with `feat(deploy): run isolated managed MCP services`.

### Task 14: Publish Contracts and Run Final Acceptance

**Files:**
- Modify: `README.md`
- Modify: `docs/prd.md`
- Create: `docs/architecture/managed-mcp-runtime.html`
- Modify: `docs/architecture/hosted-generation-platform.html`
- Modify: `docs/user-guide.md`
- Test: relevant documentation contract tests in `apps/cli` and `apps/web`

- [ ] Write RED documentation assertions for exact completed scope: credential-free single-Catalog Managed Runtime, bearer activation/revocation, single-replica session transport, provider-egress policy, and explicit non-completion of Gateway/credentials/stateless/multi-replica features.
- [ ] Update README, PRD status, user guide, and both architecture diagrams without absolute local paths or external design-template references.
- [ ] Run affected module tests:

```bash
mise exec -- ./gradlew \
  :modules:domain:test :modules:application:test \
  :modules:adapters:cryptography:test :modules:adapters:persistence-postgres:test \
  :modules:adapters:provider-egress:test :modules:adapters:mcp-java-sdk:test \
  :apps:web:test :apps:provider-egress:test :apps:runtime:test :apps:runtime:integrationTest \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] Run the exact full repository gate:

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew clean test integrationTest :apps:cli:installDist \
  --no-daemon --non-interactive --rerun-tasks
```

- [ ] Confirm fresh JUnit XML has zero failures/errors, existing skips only; run `git diff --check`; scan successful reports/logs for tokens, digests, URLs, arguments, provider bodies, private markers, absolute paths, and stack traces.
- [ ] Run a final review of the complete feature diff; accept only independently verified findings and repeat the affected/full gates after fixes.
- [ ] Commit the documentation and acceptance updates with `docs: publish managed runtime contract`.

## Execution Order and Review Gates

Execute Tasks 1-14 inline in order. Each Task gets its own RED/GREEN cycle, scope-only commit, and focused
self-review before the next Task begins. Stop for a design ruling instead of guessing when any of these change:

- MCP Java SDK 0.18.3 cannot isolate a runtime instance with its own fixed transport/server handle
- exact generated-runtime semantics require a Runtime Metadata version change
- credential-free activation cannot support the selected fixture
- provider-egress requires private destination access
- single-replica Streamable HTTP cannot satisfy the raw MCP contract

Do not push, open a PR, resolve review threads, merge, or alter remote history without a later explicit user
instruction.
