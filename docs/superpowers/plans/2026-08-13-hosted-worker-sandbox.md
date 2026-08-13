# Hosted Worker and Sandbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute each persistent generation job in a bounded rootless Docker container with offline dependencies and crash-safe lease recovery.

**Architecture:** A dedicated Worker claims PostgreSQL leases and delegates only fixed commands to a Docker runtime adapter. Generation jobs use the network-none runner; import jobs use the trusted gateway-only import-runner. Worker validates outputs and publishes artifacts under the active fencing token.

**Tech Stack:** Java 21, Spring Boot 3.5.16, fixed-argv Docker CLI, rootless Docker Engine, JDK 17/21, Gradle 9.6.1.

## Global Constraints

- Worker uses a dedicated rootless Docker socket; Web never mounts it.
- Never invoke a shell. Every Docker/process argument is a separate fixed or validated argv element.
- Job container uses network none, non-root, read-only rootfs, bounded tmpfs, cap-drop ALL, no-new-privileges, CPU 2, memory 4 GiB, PID 256, and 10-minute timeout.
- Generated build uses Gradle offline and the selected target JDK.
- Artifact publication and terminal completion require the active fencing token.
- Fatal `Error`, interruption, timeout, and cleanup precedence preserve existing generator semantics.

---

### Task 1: Sandbox contract and Worker loop

**Files:**
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/worker/SandboxRuntime.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/worker/SandboxInput.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/worker/SandboxLimits.java`
- Create: `modules/application/src/main/java/io/gen2spring/mcp/application/hosted/worker/HostedWorker.java`
- Test: `modules/application/src/test/java/io/gen2spring/mcp/application/hosted/worker/HostedWorkerTest.java`

**Interfaces:** Consumes `JobQueue`, `ObjectStorage`, `SandboxRuntime`, `ImportRuntime`, and `ImportTargetProtector`; owns claim/heartbeat/cancel/complete orchestration by job kind. Import target plaintext exists only in a bounded tmpfs file for the import-runner lifetime.

- [ ] Write clock-controlled tests for empty poll, claim, heartbeat, cancellation, retryable failure, fatal Error, interruption, stale token, upload failure, and exactly-one completion.
- [ ] Confirm RED, implement the framework-free Worker loop, then run application tests GREEN.
- [ ] Commit as `feat(application): orchestrate sandbox workers`.

### Task 2: Rootless Docker adapter

**Files:**
- Modify: `settings.gradle.kts`
- Create: `modules/adapters/container-runtime/build.gradle.kts`
- Create: `modules/adapters/container-runtime/src/main/java/io/gen2spring/mcp/adapter/container/DockerCliSandboxRuntime.java`
- Create: `modules/adapters/container-runtime/src/main/java/io/gen2spring/mcp/adapter/container/DockerCommandRunner.java`
- Create: `modules/adapters/container-runtime/src/main/java/io/gen2spring/mcp/adapter/container/SandboxOutputCollector.java`
- Test: `modules/adapters/container-runtime/src/test/java/io/gen2spring/mcp/adapter/container/DockerCliSandboxRuntimeTest.java`

**Interfaces:** Implements `SandboxRuntime`; uses validated UUID/token labels and returns only bounded structured result metadata.

- [ ] Write fake-process tests asserting exact create/start/wait/inspect/copy/remove argv and rejection of user-controlled image/name/path/options.
- [ ] Confirm RED before production adapter exists.
- [ ] Implement bounded output drain, interrupt-safe process-tree cleanup, labeled recovery, and fixed safe failures.
- [ ] Run adapter tests GREEN and commit as `feat(container): run bounded rootless sandboxes`.

### Task 3: Runner image and job protocol

**Files:**
- Create: `deploy/hosted/runner/Dockerfile`
- Create: `deploy/hosted/runner/job-entrypoint.sh`
- Create: `deploy/hosted/runner/job-result.schema.json`
- Create: `apps/worker/src/test/resources/runner/weather.yaml`
- Test: `modules/adapters/container-runtime/src/test/java/io/gen2spring/mcp/adapter/container/RunnerImageContractTest.java`

**Interfaces:** Input directory contains specification plus canonical generation configuration; output directory contains only allow-listed ZIP, manifest, validation report, result metadata, and bounded logs.

- [ ] Write image contract tests for numeric user, pinned bases/digests, JDK 17/21, Gradle 9.6.1 checksum, offline command, no package manager at runtime, and output schema.
- [ ] Confirm RED, add the multi-stage image and fixed entrypoint, then build it twice and compare declared content/digests.
- [ ] Run one Java 17 and one Java 21 generated project entirely offline.
- [ ] Commit as `feat(worker): build offline generation runner`.

### Task 4: Worker application and fenced publication

**Files:**
- Modify: `settings.gradle.kts`
- Create: `apps/worker/build.gradle.kts`
- Create: `apps/worker/src/main/java/io/gen2spring/mcp/app/worker/Gen2SpringWorkerApplication.java`
- Create: `apps/worker/src/main/java/io/gen2spring/mcp/app/worker/WorkerConfiguration.java`
- Create: `apps/worker/src/main/resources/application.yml`
- Test: `apps/worker/src/test/java/io/gen2spring/mcp/app/worker/WorkerApplicationTest.java`
- Test: `apps/worker/src/integrationTest/java/io/gen2spring/mcp/app/worker/WorkerCrashRecoveryIntegrationTest.java`

**Interfaces:** Wires persistence, storage, Worker, and Docker adapter. Readiness requires DB connectivity, private bucket access, rootless Docker identity, and pinned runner image identity.

- [ ] Write context tests that fail closed for root Docker socket, unpinned generation/import image, missing DB/bucket/gateway mTLS, and unsupported runner metadata.
- [ ] Write integration tests that kill Worker after container start, expire lease, remove survivor, retry with a new token, and publish one artifact.
- [ ] Implement application composition and readiness.
- [ ] Run focused/full Worker suites GREEN and commit as `feat(worker): execute persistent generation jobs`.

### Task 5: Sandbox acceptance

- [ ] Run real rootless Docker tests proving network egress fails and loopback MCP validation succeeds.
- [ ] Prove read-only rootfs, UID, capability, PID, memory, CPU, timeout, and output-size enforcement.
- [ ] Prove cache miss fails without network access and exposes no dependency URL.
- [ ] Prove cancellation and Worker crash leave no containers, volumes, or workspace files.
- [ ] Re-run existing generator acceptance inside and outside the runner image.
