# Hosted Generation Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add PRD-compliant URL import, persistent multi-user jobs, private artifacts, and rootless sandbox execution without weakening the current local mode.

**Architecture:** Keep generator semantics in the existing domain/application/bootstrap modules. Add PostgreSQL, S3-compatible storage, URL-fetch, and Docker runtime as outbound adapters; add dedicated worker, import-runner, and fetch-gateway apps; expose hosted OIDC UI/API only after the complete vertical acceptance passes.

**Tech Stack:** Java 21 control plane, Spring Boot 3.5.16, Spring Security OIDC, Spring JDBC, Flyway, PostgreSQL 17.9 Alpine, S3-compatible MinIO, rootless Docker, Gradle 9.6.1, Testcontainers.

## Global Constraints

- Preserve `gen2spring.mode=local` loopback behavior and its existing tests.
- `gen2spring.mode=hosted` has no local or in-memory fallback.
- Use `postgres:17.9-alpine` and pin its exact multi-architecture digest in deployment files.
- Web never receives a Docker socket and never performs outbound URL fetches.
- Generation containers use `network=none`, numeric non-root, read-only rootfs, cap-drop ALL, no-new-privileges, CPU 2, memory 4 GiB, PID 256, and a 10-minute wall timeout by default.
- URL import accepts HTTP/HTTPS on ports 80/443, at most 3 redirects, connect timeout 5 seconds, total timeout 30 seconds, and at most 10 MiB for both wire and decompressed bodies.
- Owner ID, OIDC claims, URL, secrets, input, paths, and process output never appear in safe errors or observability tags.
- Every state mutation and artifact publish by a worker requires the current fencing token.
- Keep the current Java 17/21 by Spring AI 1/2 generator acceptance matrix green.

---

## File Structure

- Existing `modules/domain`: account/specification/job/artifact identifiers and invariant policies.
- Existing `modules/application`: hosted use cases and outbound ports.
- New `modules/adapters/persistence-postgres`: Flyway and Spring JDBC repositories.
- New `modules/adapters/object-storage-s3`: private S3-compatible object adapter.
- New `modules/adapters/cryptography`: envelope encryption for transient import targets.
- New `modules/adapters/url-fetch`: gateway client and strict import parsing boundary.
- New `modules/adapters/container-runtime`: fixed-argv rootless Docker adapter.
- New `apps/fetch-gateway`: internal SSRF-resistant HTTP fetch service.
- New `apps/import-runner`: trusted per-job parser with gateway-only network access.
- New `apps/worker`: DB lease consumer and sandbox lifecycle.
- Existing `apps/web`: explicit local/hosted security and UI/API composition.
- New `deploy/hosted`: Compose, proxy, runner image, and operational examples.

## Shared Interfaces

```java
public interface JobQueue {
    CreateJobResult create(CreateJob command);
    Optional<JobView> find(AccountId owner, JobId jobId);
    Optional<JobLease> claim(WorkerId worker, Instant now, Duration leaseDuration);
    boolean heartbeat(JobLease lease, Instant leaseUntil);
    boolean complete(JobLease lease, JobCompletion completion);
    int recoverExpired(Instant now);
}

public record CreateJob(
        AccountId owner, JobKind kind, String operation, String idempotencyKey,
        String requestHash, byte[] requestSnapshot, SpecificationId specificationId) {}
public record CreateJobResult(JobView job, boolean replayed) {}
public record JobView(JobId id, AccountId owner, JobKind kind, JobStatus status, int attempt) {}
public record JobLease(
        JobId jobId, WorkerId workerId, long fencingToken, Instant leaseUntil,
        byte[] requestSnapshot) {}
public record JobCompletion(JobStatus status, String safeCode, String safeSummary) {}
public record WorkerId(String value) {}

public interface ObjectStorage {
    StoredObject put(ObjectKey key, InputStream body, long size, String sha256, String contentType);
    StoredObjectContent get(ObjectKey key);
    void delete(ObjectKey key);
}

public record StoredObject(ObjectKey key, long size, String sha256, String contentType) {}
public interface StoredObjectContent extends AutoCloseable {
    InputStream body();
    long size();
    String sha256();
    String contentType();
}

public interface UrlFetchClient {
    FetchedSpecification fetch(ImportTarget target);
}

public interface FetchedSpecification extends AutoCloseable {
    InputStream body();
    long size();
    String mediaType();
}

public interface ImportTargetProtector {
    EncryptedImportTarget protect(ImportTarget target);
    ImportTarget reveal(EncryptedImportTarget encrypted);
}

public interface ImportRuntime {
    ImportResult run(JobLease lease, EncryptedImportTarget target, SandboxLimits limits)
            throws InterruptedException;
    void removeExpired(JobId jobId, long fencingToken);
}

public record ImportResult(SandboxArtifact specification) {}

public interface SandboxRuntime {
    SandboxResult run(JobLease lease, SandboxInput input, SandboxLimits limits) throws InterruptedException;
    void removeExpired(JobId jobId, long fencingToken);
}

public record SandboxInput(ObjectKey specification, byte[] requestSnapshot, String targetProfileId) {}
public record SandboxLimits(double cpus, long memoryBytes, int pids, Duration timeout) {}
public record SandboxResult(int exitCode, List<SandboxArtifact> artifacts, String safeFailureCategory) {}
public record SandboxArtifact(String name, long size, String sha256, String contentType) {}
```

## Execution Order

1. [Persistence core plan](2026-08-13-hosted-persistence-core.md)
2. [Private storage and URL import plan](2026-08-13-hosted-url-import-storage.md)
3. [Worker and sandbox plan](2026-08-13-hosted-worker-sandbox.md)
4. [Hosted Web and deployment plan](2026-08-13-hosted-web-deployment.md)

Do not expose hosted ingress after plans 1–3. Plan 4 owns the final startup and exposure gate.

## Final Verification

- [ ] Run fast unit suites for every touched module.
- [ ] Run PostgreSQL, MinIO, URL adversarial, and rootless Docker integration suites on Linux.
- [ ] Run two-user OIDC hosted Compose journey with worker crash/retry and exact-one artifact.
- [ ] Run existing `clean test integrationTest :apps:cli:installDist --rerun-tasks` acceptance with explicit Java 17/21 homes.
- [ ] Verify `git diff --check`, secret/path/process-output scans, migration checksums, container image digests, and no residual job containers.
- [ ] Update README and the architecture HTML only when implementation evidence matches the approved design.
