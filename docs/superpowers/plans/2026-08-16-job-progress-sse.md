# Job Progress Server-Sent Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream generation progress over `GET /api/jobs/{id}/events` in both modes, keeping the existing polling loop as an automatic fallback.

**Architecture:** A shared `JobEventStream` owns all SSE mechanics and receives the per-mode difference as a bound `ChangeFeed` closure. Local mode's feed waits on the monitor `applyProgress` already notifies, which makes it genuine push. Hosted mode's feed re-reads the job every 400 ms, which relocates polling into the web process rather than removing it. The payload is whatever each mode's existing `GET /api/jobs/{id}` returns, so no presentation code changes.

**Tech Stack:** Java 21, Spring Boot MVC `SseEmitter`, Gradle 9.6.1, JUnit 5, ECMAScript modules, nginx.

**Spec:** `docs/superpowers/specs/2026-08-16-job-progress-sse-design.md`

## Global Constraints

- Implement the approved design in `docs/superpowers/specs/2026-08-16-job-progress-sse-design.md`. Do not broaden it.
- No PostgreSQL `LISTEN`/`NOTIFY`. That is the documented upgrade path, not this work.
- Do not change the snapshot payload shape, `GenerationProgress.STAGES`, the pipeline, the worker, or the job queue.
- Do not remove `pollJob`. It is the fallback and must keep working.
- `progress.js`, `wizard.js`, `state.js`, `editor.html`, and `styles.css` are untouched by this plan.
- Hosted ownership is enforced exactly as `GET /api/jobs/{id}` enforces it. A stream must never reveal that another account's job exists.
- No `innerHTML` in `static/*.js`. Build DOM with `createElement` and `textContent`.
- Every color in `styles.css` still resolves through a token; this plan adds no CSS.
- UI copy stays Korean. Identifiers, logs, and commit messages stay English.
- Fixed values from the spec: heartbeat every **15 seconds**; `awaitChange` budget **15 seconds**; emitter timeout **30 minutes**; `spring.mvc.async.request-timeout` **30 minutes**; hosted re-read interval **400 ms**; client first-event deadline **5 seconds**; nginx `proxy_read_timeout 30m`.
- Complete each task with focused RED/GREEN evidence and one scoped Conventional Commit. Do not push unless the user explicitly requests it.

## Testing Reality

Java changes get real JUnit tests. Frontend changes get the source-text contract test this repository already uses (`StaticAssetContractTest`), because there is no JavaScript test runner and no headless browser — so every frontend task also carries manual browser verification.

Hosted mode is **not** run end to end. It needs a docker compose stack with PostgreSQL, MinIO, the worker, and nginx. Hosted work is covered by MVC contract tests and code reading only, and Task 6 records that boundary in `design-qa.md`.

Test commands:

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

The integration suite needs two JDK homes that are not exported by default. Without them `LocalOperationEditorIntegrationTest` fails with `required test JDK home is unavailable`, which is an environment gap, not a regression:

```bash
export GEN2SPRING_JAVA_17_HOME="$(mise where java@17)"
export GEN2SPRING_JAVA_21_HOME="$(mise where java@21)"
mise exec -- ./gradlew :apps:web:check --no-daemon --non-interactive --rerun-tasks
```

A running instance serves templates from a Thymeleaf cache, so restart it after changing markup. Start a verification instance on a fixed port instead of disturbing one the user already runs:

```bash
GEN2SPRING_UI_PORT=49500 mise exec -- ./gradlew :apps:web:bootRun --quiet --no-daemon --non-interactive
```

## File Structure

| File | Responsibility | Task |
| --- | --- | --- |
| `apps/web/.../job/GenerationJobManager.java` | `version` counter and `awaitChange` | 1 |
| `apps/web/.../job/JobEventStream.java` | SSE mechanics, `ChangeFeed`, `Change` | 2 |
| `apps/web/.../api/JobHandler.java` | Expose snapshot serialization | 2 |
| `apps/web/.../api/GenerationJobController.java` | Local events endpoint | 2 |
| `apps/web/.../config/JobEventStreamConfiguration.java` | Mode-neutral stream bean | 2 |
| `apps/web/src/main/resources/application.yml` | Async request timeout | 2 |
| `apps/web/.../hosted/HostedJobEventFeed.java` | Hosted read-loop feed | 3 |
| `apps/web/.../hosted/HostedJobController.java` | Hosted events endpoint | 3 |
| `apps/web/src/main/resources/static/api.js` | `jobEvents`, shared normalization | 4 |
| `apps/web/src/main/resources/static/app.js` | `followJob` and fallback | 4 |
| `deploy/hosted/proxy/nginx.conf` | Events location, buffering, timeout | 5 |
| `design-qa.md` | Manual verification record | 6 |

---

## Task 1: Version counter and `awaitChange`

The change feed primitive. `GenerationJobManager` already calls `job.notifyAll()` at every state change, so this task adds only a monotonic counter and a wait that returns on the next change rather than at terminal state.

**Files:**

- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/job/GenerationJobManager.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/job/GenerationJobManagerTest.java`

**Interfaces:**

- Consumes: nothing.
- Produces:
  - `public record VersionedSnapshot(long version, JobSnapshot snapshot)` nested in `GenerationJobManager`.
  - `public Optional<VersionedSnapshot> awaitChange(String id, long sinceVersion, Duration timeout) throws InterruptedException` — returns as soon as the job's version exceeds `sinceVersion`, or empty when the timeout elapses first. Throws the existing not-found failure for an unknown id.
  - `public VersionedSnapshot current(String id)` — the snapshot and its version without waiting, for the immediate first event.

**Steps:**

- [ ] **Step 1: Write the failing tests**

Add to `GenerationJobManagerTest`:

```java
@Test
void awaitChangeReturnsWhenAStageAdvances() throws Exception {
    GenerationJobManager manager = managerUnderTest();
    JobSnapshot accepted = submitSampleJob(manager);

    GenerationJobManager.VersionedSnapshot first = manager.current(accepted.id());
    GenerationJobManager.VersionedSnapshot next = manager
            .awaitChange(accepted.id(), first.version(), Duration.ofSeconds(30))
            .orElseThrow(() -> new AssertionError("expected a change before the timeout"));

    assertTrue(next.version() > first.version());
}

@Test
void awaitChangeReturnsEmptyOnTimeoutWithoutConsumingTheJob() throws Exception {
    GenerationJobManager manager = managerUnderTest();
    JobSnapshot accepted = submitSampleJob(manager);
    manager.await(accepted.id(), Duration.ofMinutes(2));

    GenerationJobManager.VersionedSnapshot settled = manager.current(accepted.id());

    assertTrue(manager.awaitChange(accepted.id(), settled.version(), Duration.ofMillis(150)).isEmpty());
    assertEquals(settled.version(), manager.current(accepted.id()).version());
}

@Test
void versionIncreasesMonotonicallyAcrossTheWholeRun() throws Exception {
    GenerationJobManager manager = managerUnderTest();
    JobSnapshot accepted = submitSampleJob(manager);

    long version = 0;
    int observed = 0;
    // Drain the feed until it goes quiet. A terminal job never changes again,
    // so the first empty result is the end of the run.
    while (true) {
        var change = manager.awaitChange(accepted.id(), version, Duration.ofSeconds(30));
        if (change.isEmpty()) break;
        assertTrue(change.get().version() > version, "version must strictly increase");
        version = change.get().version();
        observed++;
    }
    assertTrue(observed > 0, "expected at least one observed change");
    assertEquals(version, manager.current(accepted.id()).version());
}
```

Reuse whatever helper the existing test class already uses to build a manager and submit a job. If it has none, name them `managerUnderTest()` and `submitSampleJob(manager)` and factor them out of the existing test methods so all tests share one construction path.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks --tests '*GenerationJobManagerTest'
```

Expected: FAIL to compile — `awaitChange`, `current`, and `VersionedSnapshot` do not exist.

- [ ] **Step 3: Add the version counter**

In `MutableJob`, beside the existing fields:

```java
// Starts at 1, not 0. A stream opens with cursor 0, and a job that has not
// changed yet must still be greater than that cursor or the immediate first
// snapshot required by the stream contract would never be sent.
private long version = 1;
```

Add a helper on `MutableJob` that both bumps and notifies, so the two can never drift apart:

```java
private void markChanged() {
    version++;
    notifyAll();
}
```

Replace every `job.notifyAll();` inside a `synchronized (job)` block with `job.markChanged();`, and the bare `notifyAll();` at the manager-level synchronized block stays as it is — it wakes manager-level waiters, not job-change waiters. The job-level sites are the three branches in `applyProgress` and the terminal transition.

- [ ] **Step 4: Add `current` and `awaitChange`**

```java
public record VersionedSnapshot(long version, JobSnapshot snapshot) {}

public VersionedSnapshot current(String id) {
    MutableJob job;
    synchronized (this) {
        job = requireJob(id);
    }
    synchronized (job) {
        job.lastAccess = clock.instant();
        return new VersionedSnapshot(job.version, job.snapshot());
    }
}

public Optional<VersionedSnapshot> awaitChange(String id, long sinceVersion, Duration timeout)
        throws InterruptedException {
    Objects.requireNonNull(timeout, "timeout");
    MutableJob job;
    synchronized (this) {
        job = requireJob(id);
    }
    long remainingNanos = timeout.toNanos();
    long deadline = System.nanoTime() + remainingNanos;
    synchronized (job) {
        while (job.version <= sinceVersion) {
            if (remainingNanos <= 0) {
                return Optional.empty();
            }
            TimeUnit.NANOSECONDS.timedWait(job, remainingNanos);
            remainingNanos = deadline - System.nanoTime();
        }
        job.lastAccess = clock.instant();
        return Optional.of(new VersionedSnapshot(job.version, job.snapshot()));
    }
}
```

`awaitChange` returns empty rather than throwing on timeout, unlike the existing `await`, because a timeout here is the normal heartbeat path and not a failure.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS, zero failures across the whole `:apps:web:test` suite.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/main/java/io/gen2spring/mcp/app/web/job/GenerationJobManager.java apps/web/src/test/java/io/gen2spring/mcp/app/web/job/GenerationJobManagerTest.java
git commit -m "feat(web): expose a job change feed from the generation job manager"
```

---

## Task 2: Shared stream component and the local endpoint

**Files:**

- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/job/JobEventStream.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/api/JobHandler.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/api/GenerationJobController.java`
- Modify: `apps/web/src/main/resources/application.yml`
- Create: `apps/web/src/test/java/io/gen2spring/mcp/app/web/job/JobEventStreamTest.java`

**Interfaces:**

- Consumes: `GenerationJobManager.awaitChange`, `GenerationJobManager.current`, `GenerationJobManager.VersionedSnapshot` from Task 1.
- Produces:
  - `public final class JobEventStream` with `public SseEmitter open(ChangeFeed feed)`.
  - `@FunctionalInterface public interface ChangeFeed { Optional<Change> awaitChange(long sinceVersion, Duration timeout) throws InterruptedException; }`
  - `public record Change(long version, JsonNode payload, boolean terminal) {}`
  - `JobHandler.snapshotPayload(JobSnapshot snapshot)` returning `ObjectNode`, promoted from the existing private `snapshot(JobSnapshot)`.

**Steps:**

- [ ] **Step 1: Write the failing test**

Create `JobEventStreamTest`:

```java
package io.gen2spring.mcp.app.web.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JobEventStreamTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void emitsEverySnapshotThenCompletesAtTerminalState() throws Exception {
        JobEventStream stream = new JobEventStream(2);
        List<JobEventStream.Change> feed = new CopyOnWriteArrayList<>(List.of(
                new JobEventStream.Change(1, json.createObjectNode().put("state", "RUNNING"), false),
                new JobEventStream.Change(2, json.createObjectNode().put("state", "VALIDATED"), true)));
        RecordingEmitterSink sink = new RecordingEmitterSink();

        stream.pump(sink, (since, timeout) -> feed.stream()
                .filter(change -> change.version() > since)
                .findFirst());

        assertTrue(sink.awaitCompletion(5, TimeUnit.SECONDS));
        assertEquals(List.of("snapshot", "snapshot", "done"), sink.eventNames());
        stream.close();
    }

    @Test
    void emitsAHeartbeatWhenTheFeedTimesOut() throws Exception {
        JobEventStream stream = new JobEventStream(2);
        RecordingEmitterSink sink = new RecordingEmitterSink();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        stream.pump(sink, (since, timeout) -> {
            if (calls.incrementAndGet() <= 2) return Optional.empty();
            return Optional.of(new JobEventStream.Change(1, json.createObjectNode(), true));
        });

        assertTrue(sink.awaitCompletion(5, TimeUnit.SECONDS));
        assertTrue(sink.heartbeats() >= 2, "each feed timeout must produce one heartbeat");
        stream.close();
    }

    @Test
    void refusesToOpenWhenThePoolIsExhausted() throws Exception {
        JobEventStream stream = new JobEventStream(1);
        RecordingEmitterSink blocking = new RecordingEmitterSink();
        stream.pump(blocking, (since, timeout) -> {
            TimeUnit.MILLISECONDS.sleep(400);
            return Optional.empty();
        });

        RecordingEmitterSink rejected = new RecordingEmitterSink();
        boolean accepted = stream.pump(rejected, (since, timeout) -> Optional.empty());

        assertTrue(!accepted, "an exhausted pool must refuse rather than queue");
        assertTrue(rejected.completed(), "a refused stream must be completed so the client falls back");
        stream.close();
    }
}
```

`RecordingEmitterSink` is a test double capturing what the stream writes. Define it in the same file:

```java
final class RecordingEmitterSink implements JobEventStream.EmitterSink {
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    private volatile int heartbeats;

    @Override public void event(String name, com.fasterxml.jackson.databind.JsonNode payload) {
        events.add(name);
    }
    @Override public void heartbeat() { heartbeats++; }
    @Override public void complete() { done.countDown(); }
    @Override public void completeWithError(Throwable failure) { done.countDown(); }

    List<String> eventNames() { return List.copyOf(events); }
    int heartbeats() { return heartbeats; }
    boolean completed() { return done.getCount() == 0; }
    boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }
}
```

Testing against an `EmitterSink` rather than a real `SseEmitter` keeps these tests free of servlet async plumbing while still exercising the pump's ordering, heartbeat, and pool policy.

- [ ] **Step 2: Run the test to verify it fails**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks --tests '*JobEventStreamTest'
```

Expected: FAIL to compile — `JobEventStream` does not exist.

- [ ] **Step 3: Write `JobEventStream`**

```java
package io.gen2spring.mcp.app.web.job;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class JobEventStream implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JobEventStream.class);
    private static final Duration FEED_BUDGET = Duration.ofSeconds(15);
    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final ThreadPoolExecutor workers;

    public JobEventStream(int maximumStreams) {
        this.workers = new ThreadPoolExecutor(
                // SynchronousQueue, not a bounded queue. Any queue capacity would
                // park an over-limit stream behind a running one, sending no events
                // while the client waits out its deadline instead of falling back.
                0, maximumStreams, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "job-event-stream");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @FunctionalInterface
    public interface ChangeFeed {
        Optional<Change> awaitChange(long sinceVersion, Duration timeout) throws InterruptedException;
    }

    public record Change(long version, JsonNode payload, boolean terminal) {}

    interface EmitterSink {
        void event(String name, JsonNode payload);
        void heartbeat();
        void complete();
        void completeWithError(Throwable failure);
    }

    public SseEmitter open(ChangeFeed feed) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        if (!pump(new SseEmitterSink(emitter), feed)) {
            // Completing immediately drops the client onto its polling fallback.
            emitter.complete();
        }
        return emitter;
    }

    boolean pump(EmitterSink sink, ChangeFeed feed) {
        try {
            workers.execute(() -> run(sink, feed));
            return true;
        } catch (RejectedExecutionException exhausted) {
            LOG.warn("Job event stream pool is exhausted; the client will fall back to polling");
            sink.complete();
            return false;
        }
    }

    private void run(EmitterSink sink, ChangeFeed feed) {
        long version = 0;
        try {
            while (true) {
                Optional<Change> change = feed.awaitChange(version, FEED_BUDGET);
                if (change.isEmpty()) {
                    sink.heartbeat();
                    continue;
                }
                Change observed = change.get();
                version = observed.version();
                sink.event("snapshot", observed.payload());
                if (observed.terminal()) {
                    sink.event("done", null);
                    sink.complete();
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sink.complete();
        } catch (RuntimeException failure) {
            sink.completeWithError(failure);
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    private record SseEmitterSink(SseEmitter emitter) implements EmitterSink {
        @Override public void event(String name, JsonNode payload) {
            try {
                emitter.send(SseEmitter.event().name(name).data(payload == null ? "{}" : payload.toString()));
            } catch (IOException | IllegalStateException disconnected) {
                throw new StreamClosed();
            }
        }
        @Override public void heartbeat() {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException disconnected) {
                throw new StreamClosed();
            }
        }
        @Override public void complete() { emitter.complete(); }
        @Override public void completeWithError(Throwable failure) {
            if (failure instanceof StreamClosed) emitter.complete();
            else emitter.completeWithError(failure);
        }
    }

    private static final class StreamClosed extends RuntimeException {
        StreamClosed() { super(null, null, false, false); }
    }
}
```

A client that disconnects makes `send` throw; `StreamClosed` turns that into a quiet completion rather than an error log, because a user closing the tab is not a fault.

- [ ] **Step 4: Promote the snapshot serializer**

In `JobHandler`, change `private ObjectNode snapshot(JobSnapshot snapshot)` to `public ObjectNode snapshotPayload(JobSnapshot snapshot)` and update its call site inside `status(...)`. Keep the body unchanged so the stream and the polling endpoint can never diverge.

- [ ] **Step 5: Add the local endpoint**

Register the stream as a bean. It must NOT go in `WebRuntimeConfiguration`, which carries
`@ConditionalOnProperty(havingValue = "local")` and would leave the hosted controller in
Task 3 with nothing to inject. Create a mode-neutral
`apps/web/src/main/java/io/gen2spring/mcp/app/web/config/JobEventStreamConfiguration.java`:

```java
package io.gen2spring.mcp.app.web.config;

import io.gen2spring.mcp.app.web.job.JobEventStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class JobEventStreamConfiguration {
    @Bean(destroyMethod = "close")
    JobEventStream jobEventStream() {
        return new JobEventStream(8);
    }
}
```

`WebMvcConfiguration` is the only existing mode-neutral configuration, but it is a
`WebMvcConfigurer` for MVC concerns; a stream thread pool does not belong there.

In `GenerationJobController`, inject `GenerationJobManager jobs`, `JobHandler handler`, and `JobEventStream streams`, then add:

```java
@GetMapping(path = "/api/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
SseEmitter events(@PathVariable String id) {
    String jobId = WebApiRoutes.requireIdentifier(id);
    GenerationJobManager.VersionedSnapshot initial = jobs.current(jobId);
    return streams.open((since, timeout) -> {
        if (since < initial.version()) {
            // The first event is always the state at connect time.
            return Optional.of(new JobEventStream.Change(
                    initial.version(),
                    handler.snapshotPayload(initial.snapshot()),
                    TERMINAL.contains(initial.snapshot().state())));
        }
        return jobs.awaitChange(jobId, since, timeout)
                .map(next -> new JobEventStream.Change(
                        next.version(),
                        handler.snapshotPayload(next.snapshot()),
                        TERMINAL.contains(next.snapshot().state())));
    });
}
```

Define the terminal set beside it, matching the client's `TERMINAL_STATES`:

```java
private static final Set<JobSnapshot.State> TERMINAL = EnumSet.of(
        JobSnapshot.State.VALIDATED, JobSnapshot.State.UNVERIFIED, JobSnapshot.State.FAILED);
```

Calling `jobs.current(jobId)` before opening also makes an unknown id fail with the existing not-found response instead of opening a stream that immediately dies.

- [ ] **Step 6: Raise the async timeout**

In `application.yml`, under `spring:`:

```yaml
  mvc:
    async:
      request-timeout: 30m
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS, zero failures.

- [ ] **Step 8: Verify the endpoint by hand**

Start a verification instance on port 49500, upload a spec and start a generation through the UI, then in another shell watch the stream. `curl` needs the same headers `LocalRequestSecurityFilter` demands — a `GET` needs only a matching `Host`:

```bash
curl -N -H 'Accept: text/event-stream' http://127.0.0.1:49500/api/jobs/<job-id>/events
```

Expected: an immediate `event: snapshot`, further `snapshot` events as stages advance, `: ping` comments during quiet periods, and a final `done` followed by the connection closing.

- [ ] **Step 9: Commit**

```bash
git add apps/web/src/main/java/io/gen2spring/mcp/app/web/job/JobEventStream.java apps/web/src/test/java/io/gen2spring/mcp/app/web/job/JobEventStreamTest.java apps/web/src/main/java/io/gen2spring/mcp/app/web/api/JobHandler.java apps/web/src/main/java/io/gen2spring/mcp/app/web/api/GenerationJobController.java apps/web/src/main/java/io/gen2spring/mcp/app/web/config/JobEventStreamConfiguration.java apps/web/src/main/resources/application.yml
git commit -m "feat(web): stream local generation progress over server-sent events"
```

---

## Task 3: Hosted endpoint

**Files:**

- Create: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedJobEventFeed.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/HostedJobController.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedControllerContractTest.java`

**Interfaces:**

- Consumes: `JobEventStream`, `JobEventStream.Change`, `JobEventStream.ChangeFeed` from Task 2.
- Produces: `HostedJobEventFeed.awaitChange(AccountId owner, JobId jobId, long sinceVersion, Duration timeout)` returning `Optional<JobEventStream.Change>`.

**Steps:**

- [ ] **Step 1: Write the failing test**

Add to `HostedControllerContractTest`, mirroring however that class already builds an authenticated request and a foreign-owner scenario for `GET /api/jobs/{id}`:

```java
@Test
void refusesAnEventStreamForAJobOwnedByAnotherAccount() throws Exception {
    when(resources.job(eq(OWNER), any())).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/jobs/" + UUID.randomUUID() + "/events").with(authenticated()))
            .andExpect(status().isNotFound());
}

@Test
void servesTheEventStreamAsAnEventStreamContentType() throws Exception {
    when(resources.job(eq(OWNER), any())).thenReturn(Optional.of(runningJob()));
    when(resources.events(eq(OWNER), any(), anyInt())).thenReturn(List.of());
    when(resources.artifacts(eq(OWNER), any())).thenReturn(List.of());

    mockMvc.perform(get("/api/jobs/" + JOB_ID + "/events").with(authenticated()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
}
```

Use the existing constants and stubs in that class rather than inventing new ones; `runningJob()` is whatever helper already produces a `RUNNING` hosted job record there.

- [ ] **Step 2: Run the test to verify it fails**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks --tests '*HostedControllerContractTest'
```

Expected: FAIL with 404 on the second test, because the route does not exist.

- [ ] **Step 3: Write `HostedJobEventFeed`**

```java
package io.gen2spring.mcp.app.web.hosted;

import com.fasterxml.jackson.databind.JsonNode;
import io.gen2spring.mcp.app.web.job.JobEventStream;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import io.gen2spring.mcp.domain.platform.job.JobStatus;

final class HostedJobEventFeed {
    private static final Duration INTERVAL = Duration.ofMillis(400);
    private static final Set<JobStatus> TERMINAL =
            EnumSet.of(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED);

    private final HostedJobPayloads payloads;

    HostedJobEventFeed(HostedJobPayloads payloads) {
        this.payloads = payloads;
    }

    Optional<JobEventStream.Change> awaitChange(long sinceVersion, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<JobEventStream.Change> change = read(sinceVersion);
            if (change.isPresent()) return change;
            if (System.nanoTime() >= deadline) return Optional.empty();
            TimeUnit.MILLISECONDS.sleep(INTERVAL.toMillis());
        }
    }

    private Optional<JobEventStream.Change> read(long sinceVersion) {
        HostedJobPayloads.Payload payload = payloads.read();
        // The highest event sequence advances on every worker transition, and
        // folding the status in catches a terminal move that appends no event.
        long version = payload.highestSequence() * 2 + (TERMINAL.contains(payload.status()) ? 1 : 0);
        if (version <= sinceVersion) return Optional.empty();
        return Optional.of(new JobEventStream.Change(
                version, payload.node(), TERMINAL.contains(payload.status())));
    }
}
```

`HostedJobPayloads` is a small functional holder the controller binds to one owner and one job:

```java
@FunctionalInterface
interface HostedJobPayloads {
    Payload read();
    record Payload(JsonNode node, long highestSequence, JobStatus status) {}
}
```

Binding owner and job into the closure is what keeps `JobEventStream` free of any authentication concept, as the design's section 7.1 requires.

- [ ] **Step 4: Extract the payload builder and add the endpoint**

In `HostedJobController`, extract the body of the existing `get(...)` into a private method that returns both the node and the facts the feed needs:

```java
private HostedJobPayloads.Payload payload(AccountId owner, JobId jobId) {
    var job = resources.job(owner, jobId).orElseThrow(HostedResourceNotFound::new);
    var result = json.createObjectNode()
            .put("id", job.id().value().toString())
            .put("kind", job.kind().name())
            .put("status", job.status().name())
            .put("attempt", job.attempt())
            .put("cancellationRequested", job.cancellationRequested())
            .put("createdAt", job.createdAt().toString())
            .put("updatedAt", job.updatedAt().toString());
    if (job.specificationId().isPresent()) {
        result.put("specificationId", job.specificationId().get().value().toString());
    } else {
        result.putNull("specificationId");
    }
    var events = result.putArray("events");
    long highest = 0;
    for (var event : resources.events(owner, jobId, 100)) {
        highest = Math.max(highest, event.sequence());
        events.addObject()
                .put("sequence", event.sequence()).put("status", event.toStatus().name())
                .put("stage", event.stage()).put("code", event.safeCode())
                .put("summary", event.safeSummary()).put("createdAt", event.createdAt().toString());
    }
    var artifacts = result.putArray("artifacts");
    resources.artifacts(owner, jobId).forEach(artifact -> artifacts.addObject()
            .put("id", artifact.id().toString()).put("type", artifact.type())
            .put("byteSize", artifact.byteSize()).put("contentType", artifact.contentType())
            .put("expiresAt", artifact.expiresAt().toString()));
    return new HostedJobPayloads.Payload(result, highest, job.status());
}
```

Rewrite `get(...)` as `return payload(accounts.resolve(authentication).accountId(), jobId(id)).node();` so the polling endpoint and the stream serialize through one path.

Then add:

```java
@GetMapping(path = "/api/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
SseEmitter events(Authentication authentication, @PathVariable String id) {
    var owner = accounts.resolve(authentication).accountId();
    JobId jobId = jobId(id);
    // Resolve once up front so an unowned job fails as a plain 404 rather than
    // opening a stream that would reveal the job exists by how it behaves.
    payload(owner, jobId);
    HostedJobEventFeed feed = new HostedJobEventFeed(() -> payload(owner, jobId));
    return streams.open(feed::awaitChange);
}
```

Inject `JobEventStream streams` through the constructor alongside the existing dependencies.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS, zero failures.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/main/java/io/gen2spring/mcp/app/web/hosted/ apps/web/src/test/java/io/gen2spring/mcp/app/web/hosted/HostedControllerContractTest.java
git commit -m "feat(web): stream hosted generation progress over server-sent events"
```

---

## Task 4: Client transport with polling fallback

**Files:**

- Modify: `apps/web/src/main/resources/static/api.js`
- Modify: `apps/web/src/main/resources/static/app.js`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`

**Interfaces:**

- Consumes: the endpoint from Tasks 2 and 3.
- Produces: `api.jobEvents(jobId, {onSnapshot, onDone, onFailure})` returning `{close}`.

**Steps:**

- [ ] **Step 1: Write the failing assertions**

Add to `StaticAssetContractTest`:

```java
@Test
void streamsJobProgressWithAPollingFallback() throws Exception {
    String api = resource("/static/api.js");
    String app = resource("/static/app.js");

    assertTrue(api.contains("export function jobEvents(jobId"));
    assertTrue(api.contains("new EventSource("));
    assertTrue(api.contains("/events"));
    // The hosted payload normalization must be shared, not duplicated per transport.
    assertTrue(api.contains("function normalizeJob(payload)"));
    assertTrue(app.contains("async function followJob(jobId)"));
    // The fallback is what keeps progress visible where a proxy blocks the stream.
    assertTrue(app.contains("await pollJob(jobId)"));
    assertTrue(app.contains("EventSource.CLOSED"));
    assertFalse(api.contains("innerHTML"));
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks --tests '*StaticAssetContractTest'
```

Expected: FAIL on `export function jobEvents(jobId`.

- [ ] **Step 3: Extract the normalization and add `jobEvents` in `api.js`**

The hosted-to-local shape conversion currently lives inline in `job(jobId)`. Lift it so both transports share it:

```javascript
function normalizeJob(payload) {
  if (!hostedMode) return payload;
  const stages = (payload.events ?? []).map(event => ({
    stage: event.stage,
    status: event.status,
    summary: event.summary
  }));
  const latest = stages.at(-1);
  return {
    id: payload.id,
    state: payload.status,
    currentStage: latest?.stage ?? null,
    stages,
    downloads: (payload.artifacts ?? []).map(artifact => ({
      name: artifact.type.toLowerCase(),
      artifactId: artifact.id
    })),
    error: payload.status === 'FAILED' ? {message: latest?.summary ?? 'Generation failed safely.'} : null
  };
}

export async function job(jobId) {
  return normalizeJob(await (await request(`/api/jobs/${jobId}`)).json());
}

export function jobEvents(jobId, {onSnapshot, onDone, onFailure}) {
  const source = new EventSource(`/api/jobs/${jobId}/events`);
  source.addEventListener('snapshot', message => {
    try {
      onSnapshot(normalizeJob(JSON.parse(message.data)));
    } catch {
      onFailure();
    }
  });
  source.addEventListener('done', () => {
    source.close();
    onDone();
  });
  source.addEventListener('error', () => {
    // A reconnecting EventSource is still healthy; only a closed one is fatal.
    if (source.readyState === EventSource.CLOSED) onFailure();
  });
  return {close: () => source.close()};
}
```

- [ ] **Step 4: Add `followJob` in `app.js`**

Replace the two `await pollJob(...)` call sites in `startGeneration` and `resumeRetainedJob` with `await followJob(...)`, and add:

```javascript
const FIRST_EVENT_DEADLINE_MILLIS = 5000;

async function followJob(jobId) {
  const streamed = await new Promise(resolve => {
    let settled = false;
    const settle = value => {
      if (settled) return;
      settled = true;
      clearTimeout(deadline);
      stream.close();
      resolve(value);
    };
    const stream = api.jobEvents(jobId, {
      onSnapshot: snapshot => {
        // The user deleted the job or started another one; stop without
        // handing off, because there is nothing left to follow.
        if (getState().jobId !== jobId) return settle(true);
        clearTimeout(deadline);
        updateState({job: snapshot});
        renderJob(snapshot);
        ui['delete-job-button'].disabled = TERMINAL_STATES.includes(snapshot.state)
          ? api.hostedMode : !api.hostedMode;
      },
      onDone: () => settle(true),
      // A closed stream always hands off to polling, whether it failed before
      // proving itself or lost the connection afterwards.
      onFailure: () => settle(false)
    });
    const deadline = setTimeout(() => settle(false), FIRST_EVENT_DEADLINE_MILLIS);
  });
  if (streamed) return;
  await pollJob(jobId);
}
```

`pollJob` stays exactly as it is. Falling back is one-way per job: `followJob` never reopens the stream after handing off.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS, zero failures.

- [ ] **Step 6: Verify both transports in the browser**

Restart the verification instance, then in the browser:

1. Run a full generation. In the network panel confirm exactly one `/events` request and no repeating `/api/jobs/{id}` requests. Confirm the progress bar advances and downloads appear.
2. Force the fallback by making the stream fail before its first event, then confirm the progress bar still advances and repeating `/api/jobs/{id}` requests appear:

```javascript
const RealEventSource = window.EventSource;
window.EventSource = function (url) {
  const source = new RealEventSource(url);
  setTimeout(() => source.dispatchEvent(new Event('error')), 10);
  Object.defineProperty(source, 'readyState', {get: () => RealEventSource.CLOSED});
  return source;
};
```

- [ ] **Step 7: Commit**

```bash
git add apps/web/src/main/resources/static/api.js apps/web/src/main/resources/static/app.js apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java
git commit -m "feat(web): follow generation progress over the event stream"
```

---

## Task 5: Proxy configuration

nginx buffers proxied responses by default, which holds events until a buffer fills, and its 60 second default read timeout severs an idle stream. Without this task hosted streams appear to work in tests and fail in deployment.

**Files:**

- Modify: `deploy/hosted/proxy/nginx.conf`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/HostedModeContractTest.java`

**Interfaces:**

- Consumes: the hosted endpoint from Task 3.
- Produces: nothing consumed by later tasks.

**Steps:**

- [ ] **Step 1: Write the failing assertion**

`HostedModeContractTest` already reads deployment files as text. Add:

```java
@Test
void proxyDeliversJobEventsUnbuffered() throws Exception {
    String nginx = java.nio.file.Files.readString(
            java.nio.file.Path.of("..", "..", "deploy", "hosted", "proxy", "nginx.conf"));

    assertTrue(nginx.contains("location ~ ^/api/jobs/[a-f0-9-]{36}/events$"));
    assertTrue(nginx.contains("proxy_buffering off;"));
    assertTrue(nginx.contains("proxy_cache off;"));
    assertTrue(nginx.contains("proxy_read_timeout 30m;"));
}
```

If that class resolves deployment paths differently, follow its existing convention rather than this literal path.

- [ ] **Step 2: Run the test to verify it fails**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks --tests '*HostedModeContractTest'
```

Expected: FAIL on the location assertion.

- [ ] **Step 3: Add the events location**

In `nginx.conf`, immediately before the existing `location / {`:

```nginx
    location ~ ^/api/jobs/[a-f0-9-]{36}/events$ {
      proxy_pass http://web:8080;
      proxy_http_version 1.1;
      proxy_buffering off;
      proxy_cache off;
      proxy_read_timeout 30m;
      proxy_set_header Host $host;
      proxy_set_header Forwarded "";
      proxy_set_header X-Forwarded-For $remote_addr;
      proxy_set_header X-Forwarded-Host $host;
      proxy_set_header X-Forwarded-Port 8443;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Real-IP "";
      proxy_set_header Connection "";
      proxy_hide_header Server;
    }
```

The pattern matches UUIDs because hosted `JobId` wraps a `java.util.UUID`. Every proxy header from `location /` is repeated because nginx does not inherit `proxy_set_header` into a sibling location, and dropping one would change what the application sees.

- [ ] **Step 4: Run the test to verify it passes**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS, zero failures.

- [ ] **Step 5: Check the config parses**

If docker is available:

```bash
docker run --rm -v "$PWD/deploy/hosted/proxy/nginx.conf:/etc/nginx/nginx.conf:ro" nginx:alpine nginx -t
```

Expected: `syntax is ok` and `test is successful`. If docker is unavailable, say so in the commit rather than claiming the config was validated.

- [ ] **Step 6: Commit**

```bash
git add deploy/hosted/proxy/nginx.conf apps/web/src/test/java/io/gen2spring/mcp/app/web/HostedModeContractTest.java
git commit -m "fix(deploy): deliver job event streams unbuffered through the proxy"
```

---

## Task 6: Verification record

**Files:**

- Modify: `design-qa.md`

**Interfaces:**

- Consumes: everything from Tasks 1 through 5.
- Produces: nothing.

**Steps:**

- [ ] **Step 1: Run the full check**

```bash
export GEN2SPRING_JAVA_17_HOME="$(mise where java@17)"
export GEN2SPRING_JAVA_21_HOME="$(mise where java@21)"
mise exec -- ./gradlew :apps:web:check --no-daemon --non-interactive --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Record the transport in `design-qa.md`**

Add a `## Progress transport` section stating: the endpoint and its event names; that a full local generation was observed over one `/events` request with no repeating job polls; that the forced-failure fallback drove the same progress bar; the measured request count before and after; and, under the existing `## Not covered` heading, that hosted streaming was verified by contract test and code reading only, because exercising it needs a docker compose stack with PostgreSQL, MinIO, the worker, and nginx, and that the nginx buffering fix is therefore unverified against a running proxy.

Do not write `final result: passed` unless every step above actually passed.

- [ ] **Step 3: Commit**

```bash
git add design-qa.md
git commit -m "docs: record job progress transport verification"
```

---

## Self-Review

**Spec coverage.** §6.1 endpoint → Tasks 2 and 3. §6.2 event names, heartbeat, done → Task 2 Step 3, asserted in Step 1. §6.3 payload shape → Task 2 Step 4 promotes one serializer, Task 3 Step 4 routes both hosted paths through one builder, Task 4 Step 3 shares one normalizer. §7.1 `ChangeFeed` closure → Task 2 Step 3, bound in Task 2 Step 5 and Task 3 Step 4. §7.2 local monitor → Task 1. §7.3 hosted read loop and ownership → Task 3. §7.4 bounded pool and fail-closed → Task 2 Steps 1 and 3. §7.5 lifecycle and timeouts → Task 2 Steps 3 and 6. §8 client → Task 4. §9 deployment → Task 2 Step 6 and Task 5. §11 testing → each task's Step 1 plus Task 6. §12 risks → the pool warning is logged in Task 2 Step 3; the nginx pattern risk is checked in Task 5 Step 5 and its limits recorded in Task 6 Step 2.

**Type consistency.** `JobEventStream.Change(long, JsonNode, boolean)` is declared in Task 2 Interfaces and used identically in Task 2 Step 5 and Task 3 Step 3. `GenerationJobManager.VersionedSnapshot(long, JobSnapshot)` is declared in Task 1 and consumed in Task 2 Step 5. `snapshotPayload(JobSnapshot)` is named the same in Task 2 Steps 4 and 5. `normalizeJob(payload)` is defined once in Task 4 Step 3 and asserted by that name in Step 1. `jobEvents(jobId, {onSnapshot, onDone, onFailure})` matches between Task 4 Interfaces, Step 1, Step 3, and Step 4.

**Three defects found and fixed during this review.** The local feed would never have sent its immediate first snapshot: a stream opens with cursor 0 and a fresh job's version was also 0, so `since < initial.version()` was false and the contract in spec section 6.2 was silently broken. `MutableJob.version` now starts at 1. The client's `onFailure` read `settle(sawEvent && getState().job !== null && false)`, which always evaluates to `false` — the intent was exactly `settle(false)`, and `sawEvent` was dead once the deadline is cleared inside `onSnapshot`. The monotonic-version test's loop mixed two termination conditions and could exit before observing anything; it now drains until the feed goes quiet and asserts the final version matches `current`.

**One correction carried back into the spec.** The spec originally declared `JobEventSource.awaitChange(String jobId, …)`, which has nowhere to carry the hosted account. Widening it would have forced local mode to pass an argument it does not have and taught a shared component about authentication. The spec now specifies a bound `ChangeFeed` closure, and this plan implements that; the changed-files table in the spec was updated to match.

**One risk this plan cannot retire.** Task 5 changes deployment configuration that no test in this repository can execute. The contract test proves the directives are present, not that nginx delivers events incrementally. Task 6 records that explicitly rather than implying coverage that does not exist.
