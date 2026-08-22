# Catalog Version Diff and Runtime Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish linear immutable Catalog revisions, expose deterministic API-change diffs, and migrate or roll back an active single-Catalog Managed Runtime safely.

**Architecture:** A V7 PostgreSQL migration adds owner-scoped Catalog families, trusted predecessor state, and append-only Runtime transitions. Application services calculate conservative diffs from canonical Runtime Metadata, while one persistence transaction performs Runtime compare-and-set, grant validation, and transition recording.

**Tech Stack:** Java 21, Spring Boot MVC, PostgreSQL 17.9-alpine, Flyway, Spring JDBC, Jackson, JUnit 5, Testcontainers, MCP Java SDK.

**Spec:** `docs/superpowers/specs/2026-08-22-catalog-version-diff-migration-design.md`

## Global Constraints

- Keep Tool Catalogs and Runtime Metadata immutable.
- Keep Catalog families linear; never infer family identity from a label, URL, checksum, or OpenAPI version.
- Require owner-scoped composite foreign keys and transactional publication/migration.
- Preserve Runtime ID, tokens, grants, credential versions, provider override, expiry, rate state, and execution audits across compatible migration.
- Never expose bearer tokens, credential material, arguments, provider bodies, local paths, or stack traces.
- Do not add Gateway, OAuth2 acquisition, billing, or a Catalog dashboard.
- Use one RED/GREEN cycle per task and create feature commits only at the meaningful task boundaries below.
- Do not push without separate user authorization.

---

### Task 1: Persist Linear Catalog Families

**Files:**
- Create: `modules/adapters/persistence-postgres/src/main/resources/db/migration/V7__catalog_version_migration.sql`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/catalog/ToolCatalogStore.java`
- Modify: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresToolCatalogStore.java`
- Test: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresMigrationTest.java`
- Test: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresToolCatalogStoreTest.java`

**Interfaces:**

```java
record CatalogVersion(UUID familyId, long revision, Optional<UUID> predecessorCatalogId) {}

record CatalogSummary(
        UUID catalogId,
        JobId generationId,
        String metadataVersion,
        String metadataChecksum,
        int toolCount,
        Instant createdAt,
        CatalogVersion version) {}
```

- [x] **Step 1: Write migration RED tests**

  Assert V1-to-V7 migration, one family per existing Catalog, revision 1 backfill, owner composite keys, unique
  `(family_id, revision)`, legal predecessor shape, and append-only transition constraints. Assert foreign-owner and
  cross-family predecessor inserts fail.

- [x] **Step 2: Run the focused migration test and confirm RED**

  Run:

  ```bash
  mise exec -- ./gradlew :modules:adapters:persistence-postgres:test \
    --tests 'io.gen2spring.mcp.adapter.persistence.PostgresMigrationTest' \
    --no-daemon --non-interactive --rerun-tasks
  ```

  Expected: fail because V7 and version columns do not exist.

- [x] **Step 3: Add V7 and read-side version projection**

  Create `tool_catalog_family`; add trusted generation predecessor, Catalog family/revision/predecessor columns, and
  `managed_runtime_catalog_transition`. Backfill existing Catalog IDs as family IDs. Extend list/detail/tool queries
  to return the exact `CatalogVersion`; retain a compatibility constructor in `CatalogSummary` only while updating
  existing fixtures.

- [x] **Step 4: Run persistence tests to GREEN**

  Run:

  ```bash
  mise exec -- ./gradlew :modules:adapters:persistence-postgres:test \
    --tests 'io.gen2spring.mcp.adapter.persistence.PostgresMigrationTest' \
    --tests 'io.gen2spring.mcp.adapter.persistence.PostgresToolCatalogStoreTest' \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [x] **Step 5: Commit the Catalog family foundation**

  Commit title: `feat(catalog): persist immutable catalog revisions`

---

### Task 2: Carry the Explicit Predecessor into Atomic Publication

**Files:**
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/job/CreateJob.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/job/JobView.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/job/HostedJobService.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/job/JobQueue.java`
- Modify: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/worker/HostedWorker.java`
- Modify: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresJobQueue.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedSubmissionService.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedJobController.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/hosted/job/HostedJobServiceTest.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/hosted/worker/HostedWorkerTest.java`
- Test: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresJobQueueTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedWebMvcContractTest.java`

**Interfaces:**

```java
public CreateJobResult submitGeneration(
        AccountId owner,
        SpecificationId specificationId,
        Optional<UUID> predecessorCatalogId,
        String idempotencyKey,
        String requestHash,
        String requestSnapshot);
```

`CreateJob` and generation `JobView` carry `Optional<UUID> predecessorCatalogId`; import jobs require it to be empty.
`PostgresJobQueue` reads this trusted column during completion rather than accepting lineage from the worker artifact.

- [x] **Step 1: Write submission and publication RED tests**

  Cover root-family creation, valid next revision, foreign-owner predecessor masking, predecessor in idempotency hash,
  idempotent replay, stale-head collision, and two completions racing from the same predecessor. Assert a rejected
  success transaction publishes no Catalog or artifacts.

- [x] **Step 2: Run application, Web, and queue tests and confirm RED**

  Run:

  ```bash
  mise exec -- ./gradlew :modules:application:test \
    :modules:adapters:persistence-postgres:test :apps:web:test \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [x] **Step 3: Implement predecessor validation and family-head locking**

  Accept exactly the existing job fields plus optional `predecessorCatalogId`. Include the canonical UUID in the
  stored request snapshot/hash, validate same-owner existence, lock the family at publication, allocate head + 1,
  and update the head in the existing job-completion transaction. Add a fixed `CatalogLineageConflict` boundary that
  the worker can convert to safe failed completion without retaining artifacts.

- [x] **Step 4: Add concurrency GREEN coverage**

  Use two database connections and latches, not sleeps. Require exactly one published child and one fixed conflict;
  the family must have a single head and no duplicate revision.

- [x] **Step 5: Run affected suites to GREEN**

  Run the command from Step 2 with `:apps:worker:test` added to the Gradle task list.

- [x] **Step 6: Commit generation lineage**

  Commit title: `feat(catalog): publish linear catalog revisions`

---

### Task 3: Calculate Deterministic Compatibility Diffs

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/catalog/CatalogDiff.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/catalog/CatalogDiffService.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/catalog/CatalogDiffChecksum.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/hosted/catalog/CatalogDiffServiceTest.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedToolCatalogController.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/config/HostedWebConfiguration.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/error/WebErrorMapper.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedToolCatalogControllerTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedWebMvcContractTest.java`

**Interfaces:**

```java
public record CatalogDiff(
        CatalogEndpoint source,
        CatalogEndpoint target,
        Compatibility compatibility,
        List<ToolChange> changes,
        String checksum) {
    public enum Compatibility { COMPATIBLE, BREAKING }
    public enum ChangeKind { TOOL_ADDED, TOOL_REMOVED, DESCRIPTION_CHANGED, INPUT_CHANGED,
        OUTPUT_OPTIONAL_PROPERTY_ADDED, OUTPUT_CHANGED, HTTP_CHANGED, POLICY_CHANGED, CREDENTIAL_CHANGED }
}

public CatalogDiff compare(AccountId owner, UUID sourceCatalogId, UUID targetCatalogId);
```

- [x] **Step 1: Write compatibility-matrix RED tests**

  Add one test for each rule in spec section 6, including order-only equality, added optional output, schema keyword
  change, credential target case rules, invalid metadata, cross-family, and cross-owner lookup. Assert sorted changes
  and a stable SHA-256 diff checksum.

- [x] **Step 2: Run the focused application test and confirm RED**

  Run:

  ```bash
  mise exec -- ./gradlew :modules:application:test \
    --tests 'io.gen2spring.mcp.application.hosted.catalog.CatalogDiffServiceTest' \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [x] **Step 3: Implement canonical comparison**

  Index immutable Runtime Tools by `toolName`, compare explicit metadata fields without `toString()`, classify unknown
  values as breaking, and hash a fixed canonical representation. Do not persist diff results.

- [x] **Step 4: Expose the owner-scoped diff endpoint**

  Add `GET /api/tool-catalogs/{catalogId}/diff?targetCatalogId=...`. Return family/revision/checksums,
  compatibility, sorted changes, and diff checksum. Add fixed 400/404/503 mappings and preserve OIDC ownership and
  CSRF behavior.

- [x] **Step 5: Run application and Web tests to GREEN**

  Run the Step 2 command and the complete `:apps:web:test` task.

- [x] **Step 6: Commit deterministic diff**

  Commit title: `feat(catalog): expose deterministic API change diff`

---

### Task 4: Migrate and Roll Back an Active Runtime

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/RuntimeCatalogTransitionStore.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/ManagedRuntimeMigrationService.java`
- Create: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresRuntimeCatalogTransitionStore.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/managed/runtime/ManagedRuntimeMigrationServiceTest.java`
- Test: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresRuntimeCatalogTransitionStoreTest.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedManagedRuntimeController.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/config/HostedWebConfiguration.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/error/WebErrorMapper.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedManagedRuntimeControllerTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedManagedRuntimeMvcContractTest.java`

**Interfaces:**

```java
record MigrationCommand(
        AccountId owner,
        RuntimeInstanceId runtimeId,
        UUID expectedCurrentCatalogId,
        String expectedCurrentChecksum,
        UUID targetCatalogId,
        String targetChecksum,
        String diffChecksum,
        Set<String> targetTools,
        Instant observedAt) {}

enum TransitionOutcome { APPLIED, CONFLICT, BLOCKED }

record RuntimeCatalogTransition(
        long sequence,
        RuntimeInstanceId runtimeId,
        UUID sourceCatalogId,
        String sourceChecksum,
        UUID targetCatalogId,
        String targetChecksum,
        String diffChecksum,
        TransitionKind kind,
        Instant createdAt) {}

enum TransitionKind { MIGRATION, ROLLBACK }

record TransitionPage(List<RuntimeCatalogTransition> items, Optional<Long> nextBefore) {}

record MigrationResult(
        ManagedRuntimeInstance instance,
        RuntimeCatalogTransition transition,
        String diffChecksum) {}

public MigrationResult migrate(
        AccountId owner,
        RuntimeInstanceId runtimeId,
        UUID expectedCurrentCatalogId,
        UUID targetCatalogId,
        String targetChecksum);

public TransitionPage history(AccountId owner, RuntimeInstanceId runtimeId, int limit, Optional<Long> before);

public MigrationResult rollback(
        AccountId owner,
        RuntimeInstanceId runtimeId,
        UUID expectedCurrentCatalogId);
```

`MigrationResult` contains the applied `ManagedRuntimeInstance`, transition, and diff checksum. The transition port
returns `APPLIED`, `CONFLICT`, or `BLOCKED` and performs Runtime lock, active-grant Tool-subset validation,
compare-and-set, and transition insert in one transaction.

- [ ] **Step 1: Write service RED tests**

  Cover compatible forward migration, breaking diff, wrong target checksum, cross-family/owner target, expired or
  revoked Runtime, stale expected Catalog, identical credential contract, credential mismatch, history bounds, valid
  rollback, and rollback blocked by an active grant using a newly added Tool.

- [ ] **Step 2: Write persistence and concurrency RED tests**

  Assert one of two concurrent CAS migrations wins, the loser records no transition, token/bindings/grants/rate/audit
  rows stay byte-for-byte unchanged, and rollback appends rather than edits history.

- [ ] **Step 3: Run focused tests and confirm RED**

  Run:

  ```bash
  mise exec -- ./gradlew :modules:application:test \
    :modules:adapters:persistence-postgres:test \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 4: Implement the service and atomic adapter**

  Keep migration outside `ManagedRuntimeService` so activation/revocation and version transition remain separate
  responsibilities. Lock the Runtime and current active grants, compare source ID/checksum, validate target Tool
  subset and slot contract, update Catalog ID/checksum, and append the transition. Restore interruption and rethrow
  fatal errors unchanged.

- [ ] **Step 5: Add migration/history/rollback HTTP contracts**

  Implement the three spec endpoints, bounded history parsing, fixed 409 codes, generic 404 ownership masking, and
  503 failure mapping. Never repeat activation tokens in any response.

- [ ] **Step 6: Run application, persistence, and Web tests to GREEN**

  Run the Step 3 command with `:apps:web:test` added to the Gradle task list.

- [ ] **Step 7: Commit Runtime transition behavior**

  Commit title: `feat(runtime): migrate compatible catalog revisions`

---

### Task 5: Prove Multi-replica Cutover and Publish the Contract

**Files:**
- Modify: `apps/runtime/src/integrationTest/java/io/gen2spring/mcp/app/runtime/ManagedRuntimeMultiReplicaIntegrationTest.java`
- Modify: `modules/adapters/container-runtime/src/test/java/io/gen2spring/mcp/adapter/container/HostedComposeContractTest.java`
- Modify: `README.md`
- Modify: `docs/prd.md`
- Modify: `docs/user-guide.md`
- Modify: `deploy/hosted/README.md`
- Modify: `docs/architecture/managed-mcp-runtime.html`

- [ ] **Step 1: Add the end-to-end RED journey**

  Publish revisions 1 and 2, authenticate the same Runtime bearer across two replicas, migrate with CAS, require both
  replicas to expose the target Tool list, verify existing scoped grants and credential versions, create a grant for
  a newly added Tool, prove rollback is blocked, revoke that grant, and prove rollback succeeds.

- [ ] **Step 2: Run the Runtime journey and confirm RED**

  Run:

  ```bash
  mise exec -- ./gradlew :apps:runtime:integrationTest \
    --tests 'io.gen2spring.mcp.app.runtime.ManagedRuntimeMultiReplicaIntegrationTest' \
    --no-daemon --non-interactive --rerun-tasks
  ```

- [ ] **Step 3: Complete wiring and documentation**

  Update API examples, operational failure handling, V7 backup/restore expectations, PRD P2 status, and the existing
  HTML architecture. Correct the stale PRD phase-status list while keeping Gateway/OAuth2/billing unfinished.

- [ ] **Step 4: Run hosted acceptance**

  Run:

  ```bash
  GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
  mise exec -- mise run hosted:acceptance
  ```

- [ ] **Step 5: Run repository acceptance and readback**

  Run:

  ```bash
  GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
  mise exec -- ./gradlew clean test integrationTest :apps:cli:installDist \
    --no-daemon --non-interactive --rerun-tasks
  git diff --check
  ```

  Re-read the spec against the diff, scan API/log/test output for synthetic tokens and credential values, and report
  Docker or JDK prerequisites separately from code failures.

- [ ] **Step 6: Commit Catalog lifecycle acceptance**

  Commit title: `feat(catalog): complete version migration lifecycle`
