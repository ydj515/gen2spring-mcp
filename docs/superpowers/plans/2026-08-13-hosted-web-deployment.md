# Hosted Web and Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the persistent platform through OIDC-authenticated Thymeleaf/API flows and a fail-closed single-host deployment.

**Architecture:** Keep current local configuration intact and add a separate hosted configuration graph. A TLS proxy is the only public endpoint; Web uses owner-scoped application services; Worker remains the only Docker-capable process.

**Tech Stack:** Spring Boot 3.5.16, Spring Security OAuth2 Login, Thymeleaf, PostgreSQL, MinIO, Docker Compose, Testcontainers/fake OIDC.

## Global Constraints

- `gen2spring.mode` accepts only `local` and `hosted`; default remains local.
- Hosted startup fails without issuer/client registration, migrations, private bucket validation, trusted proxy configuration, and recent Worker heartbeat.
- OIDC `(issuer, subject)` is the only external identity key; email/name never authorize.
- Mutation APIs require CSRF and generation creation requires `Idempotency-Key`.
- Cross-owner misses return a consistent 404 and never reveal object keys.
- Proxy removes inbound forwarded headers and creates trusted values; Web port is not publicly published.
- Hosted exposure happens only after the full Compose acceptance passes.

---

### Task 1: Explicit mode composition

**Files:**
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/config/WebRuntimeConfiguration.java`
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/config/HostedWebConfiguration.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/security/WebSecurityConfiguration.java`
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/security/HostedSecurityConfiguration.java`
- Modify: `apps/web/src/main/resources/application.yml`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/HostedModeStartupTest.java`

**Interfaces:** Local beans are conditional on mode `local`; hosted beans are conditional on mode `hosted`. No bean fallback crosses the boundary.

- [ ] Write ApplicationContext tests for default local, unknown mode, hosted missing each mandatory dependency, and no local store/job/filter beans in hosted mode.
- [ ] Confirm RED, split the configuration graph, and keep all current local tests unchanged.
- [ ] Run Web unit/integration tests GREEN and commit as `refactor(web): separate local and hosted modes`.

### Task 2: OIDC account mapping and owner authorization

**Files:**
- Modify: `apps/web/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/security/HostedAccountResolver.java`
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/security/HostedAccountPrincipal.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/security/HostedSecurityConfiguration.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/security/HostedOidcSecurityTest.java`

**Interfaces:** Resolves issuer+subject through `AccountStore`, exposes internal `AccountId`, and supplies it to controllers without exposing raw claims downstream.

- [ ] Write Spring Security tests for first login, repeat login, same subject/different issuer, missing subject, session fixation, CSRF, logout, trusted proxy headers, and safe failures.
- [ ] Confirm RED and add OAuth2 Client wiring plus the account resolver.
- [ ] Run security tests GREEN and commit as `feat(web): authenticate hosted users with OIDC`.

### Task 3: Persistent hosted API and UI

**Files:**
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedSpecificationController.java`
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedJobController.java`
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedArtifactController.java`
- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/page/DashboardController.java`
- Create: `apps/web/src/main/resources/templates/dashboard.html`
- Create: `apps/web/src/main/resources/templates/job-detail.html`
- Modify: `apps/web/src/main/resources/static/api.js`
- Modify: `apps/web/src/main/resources/static/styles.css`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/HostedWebMvcContractTest.java`

**Interfaces:** Implements the seven approved hosted endpoints and owner-scoped cursor views. Artifact response streams through `ObjectStorage` after repository authorization.

- [ ] Write MockMvc tests for upload, URL import, job creation/replay/conflict, pagination, cancellation, job detail, authorized download, quota 429, cross-owner 404, and fixed safe errors.
- [ ] Confirm RED, implement controllers/views with existing handler/error conventions, and preserve CSP/no-inline-resource rules.
- [ ] Run desktop and 400px browser journeys plus accessibility assertions.
- [ ] Commit as `feat(web): expose persistent hosted generation flows`.

### Task 4: Hosted Compose and operations

**Files:**
- Create: `deploy/hosted/compose.yml`
- Create: `deploy/hosted/compose.env.example`
- Create: `deploy/hosted/proxy/proxy.conf`
- Create: `deploy/hosted/README.md`
- Modify: `mise.toml`
- Modify: `README.md`

**Interfaces:** Compose manages proxy, Web, `postgres:17.9-alpine`, MinIO, and the private fetch gateway. Worker uses the dedicated host rootless Docker socket; only trusted import-runner containers join the gateway-only network.

- [ ] Add digest-pinned images, health/readiness checks, private networks/volumes, file-based secrets, backup examples, and no public PostgreSQL/MinIO/Web ports.
- [ ] Add `mise` tasks for hosted config validation, dependency start/stop, Worker start, acceptance, backup, and restore rehearsal.
- [ ] Validate Compose interpolation with missing-secret and insecure-port mutations.
- [ ] Commit as `feat(deploy): define hosted single-host platform`.

### Task 5: Full hosted acceptance and release gate

**Files:**
- Create: `apps/web/src/integrationTest/java/io/gen2spring/mcp/app/web/HostedPlatformIntegrationTest.java`
- Create: `apps/web/src/integrationTest/resources/oidc/test-provider.json`
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/architecture/hosted-generation-platform.html`
- Modify: `docs/superpowers/specs/2026-08-13-hosted-generation-platform-design.md`

**Interfaces:** Final two-user journey is independent of production test helpers and proves hosted readiness.

- [ ] Start fake OIDC, PostgreSQL 17.9, private MinIO, fetch gateway, Web, Worker, and rootless runner in a bounded Linux acceptance environment.
- [ ] User A imports a public URL, creates a job, Worker is killed, lease expires, a new Worker retries, and exactly one authorized artifact is published.
- [ ] User B receives consistent 404 for A's specification, job, events, and artifact; direct MinIO access fails.
- [ ] Restart Web/PostgreSQL connection and verify history/download persistence, quota, retention markers, and no secret/path/process-output leak.
- [ ] Run existing local and generator matrices, then enable hosted ingress workflow only after every gate is GREEN.
- [ ] Commit as `test(hosted): verify multi-user sandbox journey`.
