# Job Progress Server-Sent Events Design

- Status: approved
- Date: 2026-08-16
- Related specifications:
  - `docs/superpowers/specs/2026-08-16-stepwise-editor-redesign-design.md`
  - `docs/superpowers/specs/2026-08-13-hosted-generation-platform-design.md`

## 1. Purpose

Replace browser polling of `GET /api/jobs/{id}` with a server-sent event stream at `GET /api/jobs/{id}/events`, in both local and hosted modes, keeping the existing polling loop as an automatic fallback.

This design supersedes the progress transport decision in the stepwise editor design, which kept polling and listed SSE as a non-goal. It does not supersede that design's progress presentation, its stage labels, its failure ratio rule, or any wizard behavior.

## 2. Problem Statement

`pollJob()` issues a fresh request every 500 ms, widening to 2000 ms, for the entire life of a generation job. A local generation runs tens of seconds and a hosted one longer, so a single job costs tens of requests, each re-serializing the full snapshot. Progress appears up to one poll interval late, and the interval that bounds that latency is the same interval that sets the request cost.

The two modes reach job state differently, and this asymmetry drives the design:

| | Local | Hosted |
| --- | --- | --- |
| Job execution | Inside the web process, `GenerationJobManager` | Separate `worker` process |
| State location | In memory | PostgreSQL |
| Progress signal | `applyProgress()` already calls `job.notifyAll()` | Rows appended by `appendEvent` |

Local mode therefore has a real in-process change signal. Hosted mode has none: `persistence-postgres` contains no `LISTEN`, `NOTIFY`, or `pg_notify`. Server-sent events in hosted mode relocate polling from the browser to the web process rather than eliminating it. This design accepts that relocation; section 5.3 records the upgrade that would remove it.

## 3. Goals

- Serve `GET /api/jobs/{id}/events` as `text/event-stream` in both modes.
- Deliver local progress by waiting on the existing monitor rather than by polling.
- Give the browser one transport for both modes.
- Fall back to the existing polling loop when the stream cannot be established or cannot recover.
- Preserve the snapshot payload each mode already returns, so no presentation code changes.
- Enforce the same hosted ownership check the existing job endpoint applies.
- Keep the stream alive through an idle-timing proxy.

## 4. Non-goals

- PostgreSQL `LISTEN`/`NOTIFY`. Recorded as the upgrade path in section 5.3, not built.
- Changing the snapshot payload shape, the stage constants, or the progress presentation.
- Changing `GenerationProgress.STAGES`, the pipeline, the worker, or the job queue.
- Streaming anything other than generation job progress.
- Removing `pollJob`. It stays as the fallback.
- WebSocket or long-poll transports.

## 5. Considered Approaches

### 5.1 Selected: events in both modes, hosted backed by a server-side read loop

One endpoint and one client transport. Local waits on the in-process monitor; hosted re-reads the job and its events on a short interval and emits only on change.

Selected because the browser gets a single code path, which is the point of the change, and because the hosted read loop can run far tighter than a browser poll without adding network cost.

### 5.2 Rejected: events in local mode only

Smaller and lower deployment risk, but it leaves two transports in the client and leaves hosted — the mode with the longer jobs and the higher latency — unchanged.

### 5.3 Rejected for now: `LISTEN`/`NOTIFY` for hosted

End-to-end event driven. It needs the worker to issue `NOTIFY` or a trigger to do it, a dedicated listener connection outside the pooled datasource, and reconnection handling for that connection. It is a strict upgrade over 5.1 behind the same `JobEventSource` interface, so it can replace the hosted implementation later without touching the controller or the client. Adopt it if hosted progress latency exceeds two seconds or if concurrent streams make the read loop's database load a problem.

## 6. Stream Contract

### 6.1 Endpoint

`GET /api/jobs/{id}/events`, `Content-Type: text/event-stream`. The id must satisfy the existing `WebApiRoutes.requireIdentifier` pattern in local mode and parse as a `JobId` in hosted mode; a mismatch returns the existing route-not-found failure rather than opening a stream.

### 6.2 Events

```
event: snapshot
data: {…}

: ping

event: done
data: {}
```

- `snapshot` carries the job state. One is always sent immediately on connect so a late subscriber is never blank.
- `: ping` is an SSE comment sent every 15 seconds while idle. It keeps proxies and browsers from treating the connection as dead.
- `done` is sent once when the job reaches a terminal state, after the final `snapshot`, and the emitter completes.

### 6.3 Payload shape

Each mode sends exactly what its own `GET /api/jobs/{id}` already returns. Local sends the flat snapshot with `state`, `stages`, and `downloads`. Hosted sends its record with `status`, `events`, and `artifacts`.

`api.js` already normalizes the hosted shape into the local one before handing a snapshot to `renderJob`. Reusing that function for stream payloads means `app.js`, `progress.js`, and `wizard.js` need no change at all: `followJob` feeds them the same normalized objects `pollJob` did.

## 7. Server Design

### 7.1 The mode boundary

One interface isolates the only real difference:

```java
public interface JobEventSource {
    /**
     * Blocks until the job changes past sinceVersion, or the timeout elapses.
     * Returns empty on timeout so the caller can emit a heartbeat.
     */
    Optional<VersionedSnapshot> awaitChange(String jobId, long sinceVersion, Duration timeout);
}

public record VersionedSnapshot(long version, JsonNode payload, boolean terminal) {}
```

One SSE controller consumes this interface. Swapping the hosted implementation for a `LISTEN`/`NOTIFY` one later changes nothing above it.

### 7.2 Local implementation

`MutableJob` gains a `long version`, incremented at each site that already calls `job.notifyAll()` — the three branches in `applyProgress`, the terminal transition, and the failure path. `awaitChange` synchronizes on the job, and while `version <= sinceVersion` and the job is not terminal, calls `timedWait` against the remaining budget. It returns the snapshot as soon as the version advances.

This is genuine push: the waiting thread wakes on the same `notifyAll` the generation already issues. The existing `await(id, timeout)` cannot serve here because it waits for terminal state, not for the next change.

### 7.3 Hosted implementation

`awaitChange` re-reads `resources.job(owner, jobId)` and `resources.events(owner, jobId, 100)` every 400 ms until the version advances or the timeout elapses. It derives the version from the highest event sequence combined with the job status, and returns when that version exceeds `sinceVersion` or the timeout elapses. The interval is a constant in the implementation, not a configuration property: it is an internal latency choice, and exposing it would invite tuning a number that section 5.3 intends to delete. The owner is resolved by `HostedAccountResolver` exactly as the existing `GET /api/jobs/{id}` does, and a job the owner does not own yields the same not-found failure — the stream must never become an ownership side channel.

### 7.4 Threads

Local `awaitChange` blocks, so each open stream holds a thread. Local mode serves one operator and short jobs, so a bounded pool is adequate. When the pool is exhausted the controller completes the emitter immediately rather than queueing, which drops the client onto the polling fallback. Failing closed keeps a thread shortage from looking like a stalled job.

Hosted streams use the same bounded pool. If concurrent hosted streams approach the bound, that is the signal to adopt 5.3, whose listener design does not need a thread per stream.

### 7.5 Lifecycle

The emitter completes on terminal state, on client disconnect, on timeout, and on error. Every path releases its pool thread. `awaitChange` is called with a 15 second budget, so an idle stream wakes on schedule to emit its heartbeat. The emitter itself is constructed with a 30 minute timeout, comfortably above a generation that compiles and validates a Spring project, and `spring.mvc.async.request-timeout` is set to match so the container does not sever a healthy stream first.

## 8. Client Design

`api.js` gains `jobEvents(jobId, {onSnapshot, onDone, onFailure})`, wrapping `EventSource` and returning a close handle. It applies the existing hosted normalization to each payload before calling `onSnapshot`.

`app.js` replaces the `pollJob` call site with `followJob`, which:

1. opens the stream and starts a first-event deadline;
2. on each `snapshot`, does exactly what the poll loop did — `updateState({job})` then `renderJob(snapshot)`;
3. on `done` or terminal state, closes the stream and settles the delete button as today;
4. falls back to `pollJob` when the first event does not arrive within 5 seconds, or when `EventSource` reports an error with `readyState === EventSource.CLOSED`.

Five seconds is chosen against the server contract in section 6.2: a healthy stream emits its first `snapshot` immediately on connect, so anything approaching a second already indicates the stream is not being delivered. The margin covers a cold container and a proxy handshake without leaving the user watching a dead panel.

A transient error where `EventSource` is still reconnecting is not a fallback trigger; the browser's own reconnection is allowed to do its job first. Falling back is one-way for the life of that job, so a flapping stream cannot thrash between transports.

## 9. Deployment Configuration

These are required; without them hosted streams do not work.

- `deploy/hosted/proxy/nginx.conf` gains a location matching the events path only, `location ~ ^/api/jobs/[a-f0-9-]{36}/events$`, carrying `proxy_buffering off`, `proxy_cache off`, and `proxy_read_timeout 30m`. nginx buffers proxied responses by default, which holds events until the buffer fills, and its 60 second default read timeout would cut an idle stream. The pattern matches UUIDs because `JobId` wraps a `java.util.UUID`; local mode's 64-character hex identifiers never reach this proxy. Scoping to the events path leaves artifact downloads on the existing buffered path.
- `application.yml` sets `spring.mvc.async.request-timeout` to 30 minutes, matching the emitter timeout in section 7.5.
- No change is needed for the local security filter: the stream is a `GET` with no query string, so `LocalRequestSecurityFilter` admits it, and it demands an `Origin` header only for unsafe methods.
- No change is needed for the Content Security Policy: `connect-src 'self'` governs `EventSource`, and the stream is same-origin.

## 10. Changed Files

| File | Change |
| --- | --- |
| `apps/web/.../job/GenerationJobManager.java` | `version` counter, `awaitChange` |
| `apps/web/.../job/JobEventSource.java` | New interface and `VersionedSnapshot` |
| `apps/web/.../job/LocalJobEventSource.java` | New, monitor-backed |
| `apps/web/.../api/JobEventStreamController.java` | New, local `/api/jobs/{id}/events` |
| `apps/web/.../hosted/HostedJobEventSource.java` | New, read-loop backed |
| `apps/web/.../hosted/HostedJobController.java` | Hosted `/api/jobs/{id}/events` |
| `apps/web/src/main/resources/application.yml` | Async request timeout |
| `apps/web/src/main/resources/static/api.js` | `jobEvents`, shared normalization |
| `apps/web/src/main/resources/static/app.js` | `followJob`, fallback to `pollJob` |
| `deploy/hosted/proxy/nginx.conf` | Events location, buffering and timeout |

`progress.js`, `wizard.js`, `state.js`, `editor.html`, and `styles.css` are untouched.

## 11. Testing

- `GenerationJobManagerTest`: `awaitChange` returns when a stage advances, returns empty on timeout without consuming the job, and the version increases monotonically across the whole stage sequence.
- A local MVC test: the endpoint returns `text/event-stream`, emits an immediate `snapshot`, and emits `done` at terminal state.
- `HostedControllerContractTest`: a stream for a job owned by another account fails exactly as the existing `GET /api/jobs/{id}` does.
- `StaticAssetContractTest`: `api.js` exposes `jobEvents` and uses `EventSource`; `app.js` contains `followJob` and retains `pollJob` as the fallback.
- Manual: one real generation observed over the stream with the network panel showing a single request; then the stream blocked to confirm the fallback drives the same progress bar.

## 12. Risks

- Constraint: hosted still polls, inside the web process. The client-visible behavior is push; the system-level read cost moves rather than disappears.
- Risk: a bounded thread pool exhausted by concurrent local streams drops clients to polling. This is the intended degradation, but it is silent to the user, so pool exhaustion must be logged.
- Risk: the nginx location pattern must match the hosted job id format. Hosted ids are UUIDs, unlike the 64-character hex identifiers local mode uses, so the pattern is written for UUIDs and a mismatch would silently leave buffering on. The manual hosted check must confirm events arrive incrementally, not in one burst at completion.
- Exception: `EventSource` cannot send custom headers, so any future auth scheme that depends on a header rather than the session cookie would need a different transport.
