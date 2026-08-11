# Fast Cross-Platform CI Restoration Design

## Goal

Run fast Linux and Windows core validation jobs and remove Node.js 20/deprecated Action warnings from every job definition.

## Design

- Enable the workflow name, triggers, read-only permissions, and both `linux` and `windows` jobs.
- Pin `actions/checkout` v7.0.1 to commit `3d3c42e5aac5ba805825da76410c181273ba90b1`.
- Pin `actions/setup-java` v5.7.0 to commit `b6effb05e454b25005698d916606bdc6ffcbf961`.
- Use the same current Action pins in both active jobs.
- Resolve the Linux tool-cache `JAVA_HOME` symlinks to their canonical JDK directories before exposing Java 17/21 to the fail-closed runtime validator.
- Resolve Windows JDK homes with PowerShell `Resolve-Path` before exposing them to validation.
- Run the fast core contract suite for domain, OpenAPI analysis, policy, core, and application modules.
- Build the installed CLI distribution, which compiles both emitters and validation dependencies, and compile the web module.
- Exclude generated-project runtime smoke tests, installed CLI execution tests, and full integration tests from the required PR gate.

Both selected Action releases declare the Node.js 24 runtime. Immutable commit pins preserve the repository's existing supply-chain policy.

## Verification

- Parse `.github/workflows/ci.yml` and confirm exactly two active jobs named `linux` and `windows`.
- Confirm the active and commented Action references use the approved immutable SHAs.
- Run `git diff --check`.
- Require both Linux and Windows core jobs to complete successfully.
- If installed generation fails, print only validation status and the stage name, status, warning count, error count, and safe summary before the temporary project is removed.
- Confirm both canonical JDK homes satisfy the validator without weakening its symlink rejection policy.
- Require both fast core gates to pass before restoring any slower validation lane.

## Out of Scope

- Changing generated runtime or validation behavior before a failing validation stage is identified.
- Restoring full generated-project, installed CLI, and end-to-end validation; these belong in a separate manual or scheduled workflow.
