# Linux CI Restoration Design

## Goal

Restore only the Linux GitHub Actions validation job while keeping the Windows job disabled, and remove Node.js 20/deprecated Action warnings from every retained job definition.

## Design

- Re-enable the workflow name, triggers, read-only permissions, and `linux` job.
- Keep the complete `windows` job commented so it cannot execute.
- Pin `actions/checkout` v7.0.1 to commit `3d3c42e5aac5ba805825da76410c181273ba90b1`.
- Pin `actions/setup-java` v5.7.0 to commit `b6effb05e454b25005698d916606bdc6ffcbf961`.
- Update both active Linux references and commented Windows references so Windows restoration cannot reintroduce deprecated Node.js 20 Actions.
- Preserve the existing Java 17/21 environment capture and full validation command.

Both selected Action releases declare the Node.js 24 runtime. Immutable commit pins preserve the repository's existing supply-chain policy.

## Verification

- Parse `.github/workflows/ci.yml` and confirm exactly one active job named `linux`.
- Confirm the active and commented Action references use the approved immutable SHAs.
- Run `git diff --check`.
- Open a pull request and require the Linux GitHub Actions job to complete successfully before restoring Windows CI.

## Out of Scope

- Enabling or modifying the Windows validation job behavior.
- Changing the Gradle validation command or test coverage.
- Fixing any Linux test failure that has not yet been reproduced on the restored workflow.
