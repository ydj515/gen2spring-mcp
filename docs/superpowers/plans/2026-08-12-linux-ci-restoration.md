# Linux CI Restoration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the Linux GitHub Actions validation job with Node.js 24-based immutable Action pins while leaving Windows disabled.

**Architecture:** Keep the existing single workflow and activate only its Linux job. Preserve the Windows definition as comments, but synchronize its Action pins with Linux so later restoration starts from supported dependencies.

**Tech Stack:** GitHub Actions, Temurin 17/21, Gradle 9.6.1

## Global Constraints

- Activate exactly one job: `linux`.
- Keep the entire `windows` job commented.
- Use `actions/checkout` commit `3d3c42e5aac5ba805825da76410c181273ba90b1` (`v7.0.1`, Node.js 24).
- Use `actions/setup-java` commit `b6effb05e454b25005698d916606bdc6ffcbf961` (`v5.7.0`, Node.js 24).
- Preserve `contents: read` and the existing Gradle command.

---

### Task 1: Restore Linux CI

**Files:**
- Modify: `.github/workflows/ci.yml`
- Test: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: GitHub pull-request and `main` push events.
- Produces: One Linux validation job with captured Java 17/21 homes.

- [ ] **Step 1: Record the disabled workflow baseline**

Run:

```bash
sed -n '1,180p' .github/workflows/ci.yml
```

Expected: all workflow lines are comments and both Action versions are deprecated.

- [ ] **Step 2: Activate only the Linux workflow surface**

Uncomment `name`, `on`, `permissions`, and `jobs.linux`. Leave `jobs.windows` fully commented. Replace Action references in both blocks with:

```yaml
uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
```

- [ ] **Step 3: Validate workflow structure**

Run:

```bash
ruby -ryaml -e 'workflow=YAML.safe_load(File.read(".github/workflows/ci.yml"), aliases: true); abort unless workflow["jobs"].keys == ["linux"]'
git diff --check
```

Expected: both commands exit 0 and only `linux` is active.

- [ ] **Step 4: Commit the restoration**

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
