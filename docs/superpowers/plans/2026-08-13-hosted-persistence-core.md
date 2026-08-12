# Hosted Persistence Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist owner-scoped specifications and generation jobs with transactional idempotency, lease recovery, and stale-worker fencing.

**Architecture:** Put immutable identifiers and state rules in domain, orchestration ports in application, and all SQL/migrations in a new Spring JDBC adapter. This phase does not expose hosted routes.

**Tech Stack:** Java 21 records, Spring JDBC, Flyway, PostgreSQL 17.9 Alpine, Testcontainers PostgreSQL.

## Global Constraints

- Use UUID identifiers and UTC `Instant` values.
- Store only fixed safe error code/summary in jobs and events.
- Terminal states never reopen.
- Idempotency uniqueness is `(owner, operation, idempotency_key)` and payload mismatch returns conflict.
- All worker writes require lease owner, non-expired lease, and exact fencing token.
- Keep local in-memory `SpecificationStore` and `GenerationJobManager` unchanged in this phase.

---

### Task 1: Domain identifiers and state policy

**Files:**
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/identity/AccountId.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/specification/SpecificationId.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/job/JobId.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/job/JobKind.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/job/JobStatus.java`
- Create: `modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/job/JobTransitionPolicy.java`
- Test: `modules/domain/src/test/java/io/gen2spring/mcp/domain/platform/job/JobTransitionPolicyTest.java`

**Interfaces:** Produces validated UUID value types and `JobTransitionPolicy.requireAllowed(JobStatus from, JobStatus to)`.

- [ ] Write tests for canonical states, allowed transitions, terminal immutability, nulls, and fixed non-leaking failures.
- [ ] Run `mise exec -- ./gradlew :modules:domain:test --tests '*JobTransitionPolicyTest' --rerun-tasks` and confirm RED.
- [ ] Implement minimal immutable types and transition policy.
- [ ] Re-run focused and full `:modules:domain:test` GREEN.
- [ ] Commit as `feat(domain): define hosted job lifecycle`.

### Task 2: Application ports and use cases

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/account/AccountStore.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/specification/SpecificationCatalog.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/job/JobQueue.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/job/HostedJobService.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/job/WorkerLeaseService.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/hosted/job/HostedJobServiceTest.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/hosted/job/WorkerLeaseServiceTest.java`

**Interfaces:** Consumes Task 1 identifiers/states; produces the `JobQueue` contract from the master plan, owner-scoped queries, immutable request bytes/checksum, and lease commands.

- [ ] Write fake-store tests for idempotent replay, mismatched payload conflict, owner isolation, quota RUNNING 2/QUEUED 10, claim, heartbeat, completion, cancellation, and expiry recovery.
- [ ] Run focused application tests and confirm RED.
- [ ] Implement services without Spring/JDBC imports.
- [ ] Re-run focused and full `:modules:application:test` GREEN.
- [ ] Commit as `feat(application): orchestrate persistent hosted jobs`.

### Task 3: PostgreSQL adapter and migration

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `modules/adapters/persistence-postgres/build.gradle.kts`
- Create: `modules/adapters/persistence-postgres/src/main/resources/db/migration/V1__hosted_platform.sql`
- Create: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresAccountStore.java`
- Create: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresSpecificationCatalog.java`
- Create: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresJobQueue.java`
- Test: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresMigrationTest.java`
- Test: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresJobQueueTest.java`

**Interfaces:** Implements Task 2 ports with Spring JDBC transactions. Schema includes account, specification, generation_job, generation_job_event, and artifact metadata with owner and lease indexes.

- [ ] Write Testcontainers tests against `postgres:17.9-alpine` for migration repeatability, constraints, owner lookup, and safe persistence.
- [ ] Confirm RED before module production code exists.
- [ ] Add Boot-managed JDBC/Flyway/PostgreSQL/Testcontainers dependencies and the migration.
- [ ] Implement repositories with explicit SQL and transaction boundaries; do not use JPA.
- [ ] Re-run migration/repository tests GREEN and commit as `feat(persistence): store hosted generation state`.

### Task 4: Concurrent claim and fencing acceptance

**Files:**
- Test: `modules/adapters/persistence-postgres/src/test/java/io/gen2spring/mcp/adapter/persistence/PostgresLeaseConcurrencyTest.java`
- Modify: `modules/adapters/persistence-postgres/src/main/java/io/gen2spring/mcp/adapter/persistence/PostgresJobQueue.java`

**Interfaces:** Hardens `claim`, `heartbeat`, `complete`, and `recoverExpired` under concurrent transactions.

- [ ] Write concurrent tests proving exactly one `SKIP LOCKED` claimant, monotonically increasing tokens, stale completion rejection, terminal immutability, retry limit, and no duplicate events.
- [ ] Run the focused test to capture RED.
- [ ] Implement conditional SQL updates and transaction-scoped event append.
- [ ] Re-run the full persistence module and domain/application suites GREEN.
- [ ] Commit as `feat(persistence): fence concurrent job workers`.

### Task 5: Phase acceptance

- [ ] Run `mise exec -- ./gradlew :modules:domain:test :modules:application:test :modules:adapters:persistence-postgres:test --rerun-tasks`.
- [ ] Verify migration SQL contains no broad grants, public schema surprises, or raw OIDC/URL fields.
- [ ] Verify local Web tests remain GREEN without PostgreSQL environment variables.
- [ ] Record exact test counts and PostgreSQL image identity before starting the URL/storage plan.

