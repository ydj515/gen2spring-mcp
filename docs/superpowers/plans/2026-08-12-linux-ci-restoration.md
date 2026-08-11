# Linux CI Restoration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore a fast Linux core validation job with Node.js 24-based immutable Action pins while leaving Windows disabled.

**Architecture:** Keep the existing single workflow and activate only its Linux job. Run core module contracts plus a full production compilation through the installed CLI and web classes, while leaving generated-project and end-to-end tests for a later manual or scheduled lane. Preserve the Windows definition as comments, but synchronize its Action pins with Linux so later restoration starts from supported dependencies.

**Tech Stack:** GitHub Actions, Temurin 17/21, Gradle 9.6.1

## Global Constraints

- Activate exactly one job: `linux`.
- Keep the entire `windows` job commented.
- Use `actions/checkout` commit `3d3c42e5aac5ba805825da76410c181273ba90b1` (`v7.0.1`, Node.js 24).
- Use `actions/setup-java` commit `b6effb05e454b25005698d916606bdc6ffcbf961` (`v5.7.0`, Node.js 24).
- Preserve `contents: read`.
- Limit failed-generation diagnostics to validation status and safe stage metadata.
- Canonicalize Linux tool-cache JDK homes with `realpath` before passing them to validation.

---

### Task 1: Restore Linux CI

**Files:**
- Modify: `.github/workflows/ci.yml`
- Test: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: GitHub pull-request and `main` push events.
- Produces: One Linux validation job with captured Java 17/21 homes.

- [x] **Step 1: Record the disabled workflow baseline**

Run:

```bash
sed -n '1,180p' .github/workflows/ci.yml
```

Expected: all workflow lines are comments and both Action versions are deprecated.

- [x] **Step 2: Activate only the Linux workflow surface**

Uncomment `name`, `on`, `permissions`, and `jobs.linux`. Leave `jobs.windows` fully commented. Replace Action references in both blocks with:

```yaml
uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
```

- [x] **Step 3: Validate workflow structure**

Run:

```bash
ruby -ryaml -e 'workflow=YAML.safe_load(File.read(".github/workflows/ci.yml"), aliases: true); abort unless workflow["jobs"].keys == ["linux"]'
git diff --check
```

Expected: both commands exit 0 and only `linux` is active.

- [x] **Step 4: Commit the restoration**

```bash
git add .github/workflows/ci.yml docs/superpowers/specs/2026-08-12-linux-ci-restoration-design.md docs/superpowers/plans/2026-08-12-linux-ci-restoration.md
git commit -m "ci: restore Linux validation"
```

- [ ] **Step 5: Push, open a pull request, and verify GitHub Actions**

```bash
git push -u origin feat/restore-linux-ci
gh pr create --base main --head feat/restore-linux-ci
gh pr checks --watch
```

Expected: only the Linux GitHub Actions job runs and completes successfully.

### Task 2: Preserve Safe Validation Failure Evidence

**Files:**
- Modify: `generator-cli/src/test/java/io/gen2spring/mcp/cli/InstalledCliTest.java`
- Modify: `docs/superpowers/specs/2026-08-12-linux-ci-restoration-design.md`

**Interfaces:**
- Consumes: `VALIDATION_REPORT.json` written by a failed installed generation.
- Produces: A bounded JUnit failure message containing only safe validation-stage fields.

- [x] **Step 1: Add a failing safe-diagnostic regression**

Create a synthetic validation report containing allowed stage fields and unrelated private fields. Require the diagnostic to include `status`, `stage`, `warningCount`, `errorCount`, and `summary`, while excluding duration and private fields.

- [x] **Step 2: Verify RED**

Run:

```bash
./gradlew :generator-cli:test --tests 'io.gen2spring.mcp.cli.InstalledCliTest.failedGenerationDiagnosticsIncludeSafeValidationStages' --no-daemon --non-interactive --rerun-tasks
```

Expected: `compileTestJava` fails because `generationFailureMessage(Result, Path)` is absent.

- [x] **Step 3: Implement the bounded diagnostic**

Read the report only when an installed generation assertion fails. Copy only the allowed fields into a new JSON object, append that object to the assertion message, and return fixed `missing` or `unreadable` markers when report access fails.

- [x] **Step 4: Verify GREEN**

Run the diagnostic regression and the two installed-generation tests that failed on Linux.

Expected: all three tests pass locally; a subsequent Linux failure prints the exact safe validation stage.

### Task 3: Canonicalize GitHub Tool-Cache JDK Homes

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/superpowers/specs/2026-08-12-linux-ci-restoration-design.md`

**Interfaces:**
- Consumes: `JAVA_HOME` values returned by `actions/setup-java`.
- Produces: Canonical, non-symlink Java 17/21 homes accepted by the fail-closed runtime validator.

- [x] **Step 1: Identify the failing boundary**

Use the safe validation report to confirm both installed CLI failures stop at `COMPILE` with `Target Java runtime is unavailable or invalid`.

- [x] **Step 2: Confirm the hosted-runner layout**

Inspect the official `actions/runner-images` Ubuntu Java installation script and confirm its tool-cache `x64` entries are symbolic links to `/usr/lib/jvm/temurin-*`.

- [x] **Step 3: Canonicalize captured homes**

Use `realpath "$JAVA_HOME"` when writing each Linux `GEN2SPRING_JAVA_*_HOME` value to `GITHUB_ENV`. Keep Windows disabled and unchanged.

- [ ] **Step 4: Verify Linux CI**

Push the change and require the PR's Linux job to complete successfully.

### Task 4: Bound the Required PR Gate

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/superpowers/specs/2026-08-12-linux-ci-restoration-design.md`

**Interfaces:**
- Consumes: Production sources and fast core module tests.
- Produces: A required Linux result without generated-project boot or end-to-end MCP execution.

- [x] **Step 1: Capture the slow-suite boundary**

Confirm the full command spends most of its runtime in installed CLI, generated-project runtime, and integration tests after core compilation succeeds.

- [x] **Step 2: Define the fast core command**

Run domain, OpenAPI, policy, core, and application tests. Build `generator-cli:installDist` so both emitters and validation production sources compile, and compile `generator-web:classes`.

- [ ] **Step 3: Verify locally and in the PR**

Run the exact workflow command locally, then push and require the Linux job to pass. Keep the full suite out of this required workflow until it is restored as a separate manual or scheduled lane.
