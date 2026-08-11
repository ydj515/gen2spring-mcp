# Linux CI Restoration Design

## Goal

Restore only the Linux GitHub Actions validation job while keeping the Windows job disabled, and remove Node.js 20/deprecated Action warnings from every retained job definition.

## Design

- Re-enable the workflow name, triggers, read-only permissions, and `linux` job.
- Keep the complete `windows` job commented so it cannot execute.
- Pin `actions/checkout` v7.0.1 to commit `3d3c42e5aac5ba805825da76410c181273ba90b1`.
- Pin `actions/setup-java` v5.7.0 to commit `b6effb05e454b25005698d916606bdc6ffcbf961`.
- Update both active Linux references and commented Windows references so Windows restoration cannot reintroduce deprecated Node.js 20 Actions.
- Resolve the Linux tool-cache `JAVA_HOME` symlinks to their canonical JDK directories before exposing Java 17/21 to the fail-closed runtime validator.
- Run the fast core contract suite for domain, OpenAPI analysis, policy, core, and application modules.
- Build the installed CLI distribution, which compiles both emitters and validation dependencies, and compile the web module.
- Exclude generated-project runtime smoke tests, installed CLI execution tests, and full integration tests from the required PR gate.

Both selected Action releases declare the Node.js 24 runtime. Immutable commit pins preserve the repository's existing supply-chain policy.

## Verification

- Parse `.github/workflows/ci.yml` and confirm exactly one active job named `linux`.
- Confirm the active and commented Action references use the approved immutable SHAs.
- Run `git diff --check`.
- Open a pull request and require the Linux GitHub Actions job to complete successfully before restoring Windows CI.
- If installed generation fails, print only validation status and the stage name, status, warning count, error count, and safe summary before the temporary project is removed.
- Confirm both canonical JDK homes satisfy the validator without weakening its symlink rejection policy.
- Require the fast Linux core gate to pass before restoring any slower validation lane.

## Out of Scope

- Enabling or modifying the Windows validation job behavior.
- Changing generated runtime or validation behavior before a failing validation stage is identified.
- Restoring full generated-project, installed CLI, and end-to-end validation; these belong in a separate manual or scheduled workflow.
