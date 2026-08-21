# Managed Runtime Credential, Policy, Audit, and Scale Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the single-Catalog Managed Runtime P2 boundary with encrypted credentials, scoped Tool access, distributed rate limiting, safe audit history, and stateless multi-replica MCP transport.

**Architecture:** `apps/web` owns credential and grant control APIs, PostgreSQL owns shared policy state, and `apps/runtime` authenticates opaque grants and executes only grant-visible Tools. Credential plaintext is envelope-encrypted at rest, resolved per call, and sent only through the existing mTLS provider-egress boundary. MCP transport becomes stateless so local SDK handles are rebuildable caches rather than correctness state.

**Tech Stack:** Java 21 source, Spring Boot 3.5.16, PostgreSQL 17.9-alpine, Flyway, Spring JDBC, MCP Java SDK 0.18.3, Jackson 2.22.0, JUnit 5.13.4, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-21-managed-runtime-credential-policy-scale-design.md`

## Global Constraints

- Work on the single branch `feat/managed-runtime-p2-completion`; do not create worktrees or subagent branches.
- Preserve Runtime Metadata version `1.0` and existing generated Spring AI project behavior.
- Preserve the existing activation bearer as the full-Tool owner grant.
- Never place credential plaintext, encrypted payload fields, bearer tokens, arguments, provider bodies, paths, or stack traces in API reads, logs, metrics, MCP errors, or audit rows.
- Use fixed non-leaking failures; rethrow fatal `Error` identity and restore thread interruption.
- Require PostgreSQL 17.9-alpine migrations and owner-scoped composite foreign keys.
- Use TDD for every behavior: add an independent failing test, observe the intended RED, implement the minimum GREEN, then refactor.
- Do not add OAuth2 acquisition, Catalog sharing, cross-Catalog routing, billing, or version migration.

---

### Task 1: Credential and Grant Domain Contracts

**Files:**
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/credential/ManagedCredentialId.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/credential/ManagedCredentialKind.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/credential/ManagedCredential.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/runtime/RuntimeGrantId.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/runtime/ManagedRuntimeGrant.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/runtime/ToolExecutionAudit.java`
- Test: matching `modules/domain/src/test/java/...` test classes

**Interfaces:**
- `ManagedCredentialId(UUID value)` and `RuntimeGrantId(UUID value)` reject null and expose `parse(String)`.
- `ManagedCredential` carries ID, owner, safe label, kind, positive version, timestamps, and optional revocation.
- `ManagedRuntimeGrant` carries runtime/owner/grant identity, safe principal, immutable allowed Tool names, rate limit, expiry, and revocation.
- `ToolExecutionAudit` carries the safe fields listed in the design and enforces one-way `STARTED` completion semantics.

- [ ] **Step 1: Write failing immutable-domain tests**

  Add literal tests for valid construction, null/blank/control/length bounds, defensive Tool-set copies,
  invalid time order, rate limits outside `1..6000`, and forbidden audit combinations. Each test must name
  the production mutation it catches.

- [ ] **Step 2: Run the domain RED**

  Run:

  ```bash
  mise exec -- ./gradlew :modules:domain:compileTestJava --no-daemon --non-interactive
  ```

  Expected: compilation fails because the six new production types do not exist.

- [ ] **Step 3: Implement the minimum validated records**

  Use UUID value objects, `List.copyOf`/`Set.copyOf`, explicit safe regexes, and fixed
  `IllegalArgumentException` messages. Audit status is exactly `STARTED`, `SUCCEEDED`, `TOOL_ERROR`,
  `RATE_LIMITED`, or `INTERNAL_ERROR`.

- [ ] **Step 4: Run focused and full domain GREEN**

  ```bash
  mise exec -- ./gradlew :modules:domain:test --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 5: Commit Task 1**

  ```bash
  git add modules/domain
  git commit -m "feat(domain): model managed runtime policy"
  ```

---

### Task 2: Write-Only Credential Vault and Envelope Protection

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/credential/CredentialSecret.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/credential/ProtectedCredential.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/credential/CredentialProtector.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/credential/ManagedCredentialStore.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/credential/ManagedCredentialService.java`
- Create: `modules/adapters/cryptography/src/main/java/io/gen2spring/mcp/adapter/cryptography/AesGcmCredentialProtector.java`
- Test: matching application and cryptography tests

**Interfaces:**
- `CredentialSecret` is a closeable defensive byte container with factories `opaque`, `bearer`, and `basic`.
- `CredentialProtector.protect(AccountId, ManagedCredentialId, long, CredentialSecret)` returns a
  `ProtectedCredential`; `reveal(...)` returns a new closeable `CredentialSecret`.
- `ManagedCredentialStore` provides owner-scoped `create`, `rotate`, `revoke`, `find`, `list`, and
  `countActive` operations. Secret-bearing reads are available only to the runtime resolver, not API DTOs.
- `ManagedCredentialService` enforces the 100-active-credential limit and maps all storage/protection failures
  to fixed `Invalid`, `NotFound`, or `Unavailable` exceptions.

- [ ] **Step 1: Write application RED tests**

  Test create/list/detail/rotate/revoke, foreign-owner indistinguishability, active limit, kind-specific input
  validation, version increment, and the absence of secret fields from public views.

- [ ] **Step 2: Verify application RED**

  ```bash
  mise exec -- ./gradlew :modules:application:compileTestJava --no-daemon --non-interactive
  ```

  Expected: missing credential application types.

- [ ] **Step 3: Implement the application ports and service**

  Keep plaintext in `CredentialSecret` only, close it in service `finally` blocks, and derive public response
  records solely from `ManagedCredential` metadata.

- [ ] **Step 4: Write cryptography RED tests**

  Cover round trip for all kinds, ciphertext/AAD mutation, wrong owner/ID/version, active and retired keys,
  missing/symlinked/wrong-size/overexposed files, exact byte boundaries, and fixed error text.

- [ ] **Step 5: Verify cryptography RED**

  ```bash
  mise exec -- ./gradlew :modules:adapters:cryptography:test --tests '*AesGcmCredentialProtectorTest' --no-daemon --non-interactive --rerun-tasks
  ```

  Expected: missing protector implementation.

- [ ] **Step 6: Implement AES-256-GCM envelope protection**

  Generate one random data key and two nonces per version. Bind owner ID, credential ID, credential version,
  key ID, and purpose into AAD. Clear plaintext and data-key byte arrays in `finally`.

- [ ] **Step 7: Run Task 2 GREEN**

  ```bash
  mise exec -- ./gradlew :modules:application:test :modules:adapters:cryptography:test --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 8: Commit Task 2**

  ```bash
  git add modules/application modules/adapters/cryptography
  git commit -m "feat(runtime): add encrypted credential vault"
  ```

---

### Task 3: PostgreSQL Credential, Binding, Grant, Rate, and Audit State

**Files:**
- Create: `modules/adapters/persistence-postgres/src/main/resources/db/migration/V6__managed_runtime_policy.sql`
- Create: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresManagedCredentialStore.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/policy/RuntimePolicyStore.java`
- Create: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresRuntimePolicyStore.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/ManagedRuntimeStore.java`
- Modify: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresManagedRuntimeStore.java`
- Test: persistence tests and migration test

**Interfaces:**
- `ManagedRuntimeStore.create` accepts the instance, owner token digest, and immutable slot bindings in one call.
- `RuntimePolicyStore` creates/lists/revokes grants, authenticates scoped digests, atomically acquires a DB-time
  minute window, starts/completes audit rows, and returns owner-scoped audit pages.
- Credential persistence returns `ProtectedCredential` only through its internal store port.

- [ ] **Step 1: Write migration and persistence RED tests**

  Assert V1-to-V6 migration, exact constraints, owner composite foreign keys, encrypted-field non-null bounds,
  atomic runtime+binding insertion, rotation CAS, revoke idempotency, and no secret columns in metadata queries.

- [ ] **Step 2: Run persistence RED**

  ```bash
  mise exec -- ./gradlew :modules:adapters:persistence-postgres:test --tests '*PostgresMigrationTest' --tests '*PostgresManagedCredentialStoreTest' --tests '*PostgresRuntimePolicyStoreTest' --no-daemon --non-interactive --rerun-tasks
  ```

  Expected: migration/tables/adapters are missing.

- [ ] **Step 3: Add V6 and JDBC adapters**

  Use `bytea` for envelope fields, `text[]` for sorted allowed Tool names, compare-and-set SQL for rotation and
  audit completion, and a single PostgreSQL-time upsert for rate acquisition. Never construct SQL from user
  values.

- [ ] **Step 4: Add concurrency RED/GREEN tests**

  Run two store instances against one PostgreSQL 17.9 container. Prove exactly the configured count succeeds
  in one minute window, completion transitions once, and cross-owner IDs cannot bind.

- [ ] **Step 5: Run full persistence GREEN**

  ```bash
  mise exec -- ./gradlew :modules:adapters:persistence-postgres:test --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 6: Commit Task 3**

  ```bash
  git add modules/application modules/adapters/persistence-postgres
  git commit -m "feat(persistence): store runtime credentials and policy"
  ```

---

### Task 4: Runtime Activation Bindings and Hosted Control APIs

**Files:**
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/ManagedRuntimeService.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/policy/RuntimeGrantService.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/audit/RuntimeAuditService.java`
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedCredentialController.java`
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedRuntimePolicyController.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedManagedRuntimeController.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/config/HostedWebConfiguration.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/config/HostedWebProperties.java`
- Modify: `apps/web/src/main/resources/application.yml`
- Test: application and Web MVC contract tests

**Interfaces:**
- `ManagedRuntimeService.activate` gains `Map<String, UUID> credentialBindings` and validates exact required
  slot coverage and credential-kind/target compatibility before one atomic runtime create.
- `RuntimeGrantService.create` returns one plaintext token and persists only its digest; list never returns it.
- `RuntimeAuditService.list` returns bounded owner-scoped pages using the existing opaque cursor style.

- [ ] **Step 1: Write activation and grant RED tests**

  Test credential-free compatibility, exact required/optional slot rules, foreign/revoked credentials,
  Authorization-only Bearer/Basic, unsupported PATH/BODY, grant Tool subset, expiry, and runtime revocation.

- [ ] **Step 2: Verify application RED**

  ```bash
  mise exec -- ./gradlew :modules:application:test --tests '*ManagedRuntimeServiceTest' --tests '*RuntimeGrantServiceTest' --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 3: Implement activation, grants, and audit query services**

  Keep owner/foreign not-found behavior identical and preserve owner-token activation response semantics.

- [ ] **Step 4: Write Web API RED tests**

  Exercise every endpoint with real MVC serialization, OIDC owner resolution, CSRF, cache-control, missing
  resources, invalid UUID/cursor/body, and recursive response scans proving no secret/cipher/key field exists.

- [ ] **Step 5: Implement hosted controllers and configuration**

  Accept credential plaintext only in POST bodies. Use dedicated request/response DTOs and fixed Web error
  mappings. Wire the same configured operator keys into web and runtime deployments.

- [ ] **Step 6: Run Task 4 GREEN**

  ```bash
  mise exec -- ./gradlew :modules:application:test :apps:web:test --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 7: Commit Task 4**

  ```bash
  git add modules/application apps/web
  git commit -m "feat(web): manage runtime credentials and grants"
  ```

---

### Task 5: Credential Injection, Distributed Policy, and Audit Completion

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/ManagedExecutionContext.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/credential/RuntimeCredentialResolver.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/RuntimeHttpRequestFactory.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/ManagedToolExecutor.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/execution/ManagedToolResult.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/RuntimeAccess.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/RuntimeAccessAuthenticator.java`
- Modify: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/RuntimeConfiguration.java`
- Test: focused application/runtime tests

**Interfaces:**
- `RuntimeAccess` exposes runtime, optional grant ID, safe principal, allowed Tools, per-minute limit, and
  deterministic policy checksum; it exposes no token or credential identity.
- `RuntimeCredentialResolver.resolve(runtime, RuntimeTool)` returns a closeable ordered set of wire credentials.
- `RuntimeHttpRequestFactory.create` accepts resolved credentials separately from user arguments and injects
  them only after argument binding.
- `ManagedToolExecutor.call(ManagedExecutionContext, toolName, arguments)` owns rate, audit, credential, and
  provider ordering.

- [ ] **Step 1: Write request-injection RED tests**

  Use literal expected URIs and headers for OPAQUE query, OPAQUE header, Bearer, and Basic. Test duplicate
  target rejection, case-insensitive Authorization collision, unbound optional slots, revoked credential,
  and absence of secret values in every failure string.

- [ ] **Step 2: Verify injection RED**

  ```bash
  mise exec -- ./gradlew :modules:application:test --tests '*RuntimeHttpRequestFactoryTest' --tests '*RuntimeCredentialResolverTest' --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 3: Implement resolver and post-binding injection**

  Resolve only selected Tool slots, format values by credential kind, close secret buffers in the executor,
  and keep provider request behavior unchanged for credential-free Tools.

- [ ] **Step 4: Write orchestration RED tests**

  Prove hidden Tool does no work; audit starts before rate; rate denial performs no credential/provider call;
  credential/audit-start failure performs no provider call; audit completion failure does not retry; fatal
  `Error` identity and interruption behavior remain exact.

- [ ] **Step 5: Implement executor policy/audit ordering**

  Add `RATE_LIMITED` to the safe result category. Complete every started audit exactly once when possible and
  preserve a `STARTED` row for failed completion.

- [ ] **Step 6: Run Task 5 GREEN**

  ```bash
  mise exec -- ./gradlew :modules:application:test :apps:runtime:test --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 7: Commit Task 5**

  ```bash
  git add modules/application apps/runtime
  git commit -m "feat(runtime): enforce credential and audit policy"
  ```

---

### Task 6: Stateless MCP Adapter and Multi-Replica Runtime

**Files:**
- Modify: `modules/adapters/mcp-java-sdk/src/main/java/io/gen2spring/mcp/adapter/mcp/McpJavaSdkEmitter.java`
- Modify: `modules/adapters/mcp-java-sdk/src/test/java/io/gen2spring/mcp/adapter/mcp/McpJavaSdkEmitterTest.java`
- Modify: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/RuntimeServerHandle.java`
- Modify: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/RuntimeServerHandleRegistry.java`
- Modify: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/RuntimeConfiguration.java`
- Modify: `apps/runtime/src/main/java/io/gen2spring/mcp/app/runtime/ManagedMcpRouter.java`
- Modify: `apps/runtime/src/test/java/io/gen2spring/mcp/app/runtime/RuntimeServerHandleRegistryTest.java`
- Create: `apps/runtime/src/integrationTest/java/io/gen2spring/mcp/app/runtime/ManagedRuntimeMultiReplicaIntegrationTest.java`

**Interfaces:**
- `McpJavaSdkEmitter.emitStateless` returns
  `List<McpStatelessServerFeatures.SyncToolSpecification>` and uses the same independent schema/result mapping.
- `RuntimeServerHandleRegistry` keys handles by runtime ID, Catalog checksum, and access policy checksum.
- `RuntimeServerHandle` wraps `WebMvcStatelessServerTransport` and `McpStatelessSyncServer` cleanup.

- [ ] **Step 1: Write stateless adapter RED tests**

  Test exact Tool schema/result, safe internal failure, fatal `Error`, and allowed Tool subset using the real
  stateless SDK types.

- [ ] **Step 2: Verify adapter RED**

  ```bash
  mise exec -- ./gradlew :modules:adapters:mcp-java-sdk:test --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 3: Implement `emitStateless` without changing `emit`**

  Share only private schema/result construction. Keep both public methods independently typed so generated
  project validation and the Managed Runtime cannot accidentally exchange SDK contracts.

- [ ] **Step 4: Write runtime stateless RED tests**

  Assert no session ID requirement, grant-policy cache separation, runtime/grant revocation on the next
  request, bounded cache eviction, and no bearer/credential content in cache keys or `toString` output.

- [ ] **Step 5: Replace the runtime transport**

  Use `WebMvcStatelessServerTransport.builder().messageEndpoint(endpoint)` and
  `McpServer.sync(statelessTransport)`. Filter Catalog Tools before emission using `RuntimeAccess.allowedTools`.

- [ ] **Step 6: Add two-replica integration RED/GREEN**

  Start two application contexts on different numeric-loopback ports against the same PostgreSQL and provider
  recorder. Alternate initialize, `tools/list`, and `tools/call` requests between them without cookies,
  `Mcp-Session-Id`, or sticky routing. Assert one provider call, shared rate denial, and shared audit completion.

- [ ] **Step 7: Run Task 6 GREEN**

  ```bash
  mise exec -- ./gradlew :modules:adapters:mcp-java-sdk:test :apps:runtime:test :apps:runtime:integrationTest --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 8: Commit Task 6**

  ```bash
  git add modules/adapters/mcp-java-sdk apps/runtime
  git commit -m "feat(runtime): support stateless multi-replica MCP"
  ```

---

### Task 7: Hosted Deployment, Documentation, and End-to-End Acceptance

**Files:**
- Modify: `deploy/hosted/compose.yml`
- Modify: `deploy/hosted/compose.env.example`
- Modify: `deploy/hosted/proxy/nginx.conf`
- Modify: `deploy/hosted/README.md`
- Modify: `modules/adapters/container-runtime/src/test/java/io/gen2spring/mcp/adapter/container/HostedComposeContractTest.java`
- Modify: `apps/runtime/src/integrationTest/java/io/gen2spring/mcp/app/runtime/ManagedRuntimeJourneyIntegrationTest.java`
- Modify: `README.md`
- Modify: `docs/user-guide.md`
- Modify: `docs/prd.md`
- Modify: `docs/architecture/managed-mcp-runtime.html`

**Interfaces:**
- Hosted secret configuration provides credential encryption keys to web and runtime as read-only files.
- `runtime` remains horizontally scalable with `docker compose up --scale runtime=2` and no host-published port.
- Documentation keeps public Gateway, OAuth2 acquisition, sharing, billing, and Catalog migration explicitly unfinished.

- [ ] **Step 1: Write hosted contract RED tests**

  Assert PostgreSQL 17.9-alpine remains pinned, credential key files are mounted only in web/runtime, runtime
  has no direct egress, provider-egress has no DB/key mounts, proxy has no sticky-session directive, and the
  runtime service has no `container_name` or fixed host port preventing scaling.

- [ ] **Step 2: Implement hosted configuration**

  Add active/retired credential key variables and secrets, shared runtime settings, scale instructions, audit
  retention operation, and safe key rotation procedure.

- [ ] **Step 3: Extend the managed runtime journey**

  Create one OPAQUE, one Bearer, and one Basic credential, activate a Catalog with exact slot bindings, issue
  two disjoint grants, alternate requests across replicas, rotate then revoke a credential, revoke a grant and
  runtime, and query exact safe audit rows. Use synthetic secrets and scan all captured output/artifacts.

- [ ] **Step 4: Update product documentation and HTML architecture**

  Mark credential binding, scoped grants, audit, stateless transport, and multi-replica completion in the PRD.
  Keep cross-Catalog public Gateway, OAuth2 acquisition, billing, and Catalog migration in P2 remaining scope.
  Update the HTML diagram with control-plane credential/grant flows and shared PostgreSQL correctness state.

- [ ] **Step 5: Run affected verification**

  ```bash
  GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
  mise exec -- ./gradlew \
    :modules:domain:test \
    :modules:application:test \
    :modules:adapters:cryptography:test \
    :modules:adapters:persistence-postgres:test \
    :modules:adapters:mcp-java-sdk:test \
    :modules:adapters:container-runtime:test \
    :apps:web:test \
    :apps:runtime:test \
    :apps:runtime:integrationTest \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 6: Run hosted configuration validation**

  ```bash
  GEN2SPRING_HOSTED_ENV_FILE=deploy/hosted/compose.env.example mise run hosted:config
  ```

- [ ] **Step 7: Run full acceptance**

  ```bash
  GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
  mise exec -- ./gradlew clean test integrationTest :apps:cli:installDist \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 8: Run final safety readback**

  ```bash
  git diff --check
  git status --short --branch
  ```

  Search generated reports and captured logs for every synthetic secret/token fixture, `Authorization:`,
  ciphertext fields, key paths, raw provider markers, and stack-trace prefixes. Expected: zero leak matches;
  only pre-existing documented skips may remain.

- [ ] **Step 9: Commit Task 7**

  ```bash
  git add deploy README.md docs modules/adapters/container-runtime apps/runtime/src/integrationTest
  git commit -m "docs(runtime): complete managed runtime P2 contract"
  ```

---

## Final Review Gate

- [ ] Confirm every spec requirement maps to one task and no excluded Gateway/OAuth/versioning work entered the diff.
- [ ] Confirm every new behavior has observed RED and GREEN evidence.
- [ ] Confirm the feature branch contains only scoped commits and is not pushed without explicit user authorization.
- [ ] Run `git diff --check` and compare `HEAD` with `main` by file and commit summary.
- [ ] Use `superpowers:verification-before-completion`, then `superpowers:finishing-a-development-branch`.
