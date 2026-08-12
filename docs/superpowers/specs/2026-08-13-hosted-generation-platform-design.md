# Hosted Generation Platform Design

## 1. 목적

현재 `apps/web`의 loopback 단일 사용자 구현을 보존하면서 PRD의 미완료 범위인 URL import,
작업 영속화, 다중 사용자 소유권, private artifact storage, sandboxed generation을 hosted mode로
추가한다.

이번 설계는 다음 원칙을 고정한다.

- Web control plane과 generation execution plane을 분리한다.
- hosted mode는 OIDC, PostgreSQL, private object storage, worker, sandbox 중 하나라도 빠지면
  기동하지 않는다.
- local mode의 loopback-only invariant를 완화하지 않는다.
- 외부 URL과 OpenAPI 입력, 생성된 project build는 서로 다른 격리 경계에서 처리한다.
- 사용자 데이터 조회, 취소, 다운로드는 모두 internal account UUID 소유권으로 제한한다.

시각 구조도는 [`docs/architecture/hosted-generation-platform.html`](../../architecture/hosted-generation-platform.html)에
보존한다.

## 2. 결정

### 2.1 권장 구조

초기 hosted 배포는 Linux 단일 host를 기준으로 한다.

- TLS reverse proxy
- `apps/web`: OIDC, Thymeleaf UI, same-origin API, authorization
- PostgreSQL `postgres:17.9-alpine`: account, specification, job, event, lease, artifact metadata
- private MinIO: specification 원본과 artifact
- `apps/worker`: PostgreSQL lease queue 소비와 rootless Docker lifecycle
- per-job `apps/import-runner` fetch container와 policy egress gateway
- digest-pinned generation runner image

PostgreSQL queue는 `FOR UPDATE SKIP LOCKED`와 lease/fencing token으로 구성한다. 초기 규모에서 별도
broker의 운영 비용을 피하면서 restart recovery와 중복 실행 방지를 제공한다.

### 2.2 실행 모드

`gen2spring.mode`는 다음 두 값만 허용한다.

- `local`: 현재 loopback filter, 임시 store, process-local job manager, 익명 단일 사용자
- `hosted`: OIDC, migration, PostgreSQL repository, private object storage, worker heartbeat,
  trusted proxy가 모두 필수

두 mode는 별도 Spring configuration graph로 조립한다. hosted dependency 누락 시 local로 fallback하지
않는다. local mode는 remote bind와 forwarded header를 계속 거부한다.

### 2.3 제외한 대안

- Web process 내부 worker: Docker capability, restart lifecycle, scale 단위가 Web과 결합된다.
- RabbitMQ를 첫 queue로 사용: 현재 처리량에서는 broker 운영 비용이 recovery 이점보다 크다.
- Kubernetes Job을 첫 sandbox로 사용: 단일 host 기준에 cluster 운영 복잡도를 추가한다.
- Web process의 직접 URL fetch: SSRF와 DNS rebinding 경계가 공개 control plane에 들어온다.

Worker가 8개를 초과하거나 queue wait p95가 60초를 초과하면 broker와 Kubernetes Job으로 전환을
검토한다.

## 3. Architecture and trust boundaries

```text
Browser ──TLS──> Reverse Proxy ──trusted headers──> apps/web
   │                                               │
   └────────────── external OIDC ──────────────────┘
                                                   │
                            ┌──────────────────────┼──────────────────────┐
                            │                      │                      │
                       PostgreSQL            private MinIO          import job
                            │                                             │
                            └──lease──> apps/worker                       │
                                           │                              v
                                  rootless Docker socket       fetch container
                                           │                              │
                                           v                              v
                                  isolated job container       policy egress gateway
```

Web은 Docker socket을 볼 수 없다. Worker의 rootless Docker socket과 workspace는 Web container에
mount하지 않는다. Generation container는 URL fetch gateway와도 network를 공유하지 않는다.

## 4. Identity and ownership

외부 OIDC의 `(issuer, subject)`를 내부 `account.id` UUID에 매핑한다. email, name, preferred username은
표시값이며 authorization key로 사용하지 않는다.

모든 specification, job, event 조회, cancellation, artifact download query는 `owner_account_id`를
조건에 포함한다. 다른 사용자의 opaque UUID를 제공한 요청은 존재 여부를 구분하지 않는 404로 응답한다.

Hosted browser flow는 Spring Security OAuth2 Login, CSRF, session fixation protection을 사용한다.
초기 single Web instance에서는 restart 후 재로그인을 허용한다. Web replica가 둘 이상이 되면 Spring
Session JDBC 또는 Redis 도입을 별도 결정한다.

Reverse proxy만 공개한다. Web network에는 proxy만 접근할 수 있으며 forwarded headers는 proxy가
새로 생성한 값만 신뢰한다. 외부 요청이 보낸 기존 forwarding headers는 proxy에서 제거한다.

## 5. Persistent model

### 5.1 account

- `id` UUID primary key
- `issuer`, `subject`, unique `(issuer, subject)`
- `created_at`, `last_seen_at`

### 5.2 specification

- `id`, `owner_account_id`
- source type `UPLOAD` or `URL`
- private object key, SHA-256, byte size
- sanitized display label
- parse state and timestamps

URL userinfo는 허용하지 않는다. URL query를 포함한 fetch target이 terminal 상태 이전에 필요하면
application-managed envelope encryption을 사용하고 terminal 전환 후 ciphertext를 제거한다. 원문 URL은
log, event, metric, trace, error에 기록하지 않는다.

### 5.3 generation_job

- `id`, `owner_account_id`, `specification_id`
- kind `SPEC_IMPORT` or `GENERATION`
- immutable request snapshot JSONB and request hash
- target profile, status, stage, attempt
- `lease_owner`, `lease_until`, monotonically increasing `fencing_token`
- `cancel_requested`, safe error code/summary
- timestamps and optimistic version

### 5.4 generation_job_event

Append-only event이며 job sequence, from/to status, stage, safe code/summary, timestamp만 저장한다. 입력값,
secret, path, process output은 저장하지 않는다.

### 5.5 artifact

- `id`, `job_id`, `owner_account_id`
- type, private object key, SHA-256, size, content type
- created and expiry timestamps

MinIO bucket은 public access와 anonymous listing을 거부한다. 초기 버전은 presigned public download를
제공하지 않고 Web이 owner authorization 후 stream한다.

## 6. Job lifecycle, idempotency, and recovery

Canonical state는 다음과 같다.

```text
QUEUED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED
```

`cancel_requested`는 state가 아니라 worker control flag다. Terminal state는 다시 열리지 않는다.

Job creation은 unique `(owner_account_id, operation, idempotency_key)`를 사용한다. 동일 key와 동일
request hash는 기존 job을 반환한다. payload가 다르면 409를 반환한다.

Worker claim은 `FOR UPDATE SKIP LOCKED`로 수행한다. claim마다 새 fencing token을 발급하고 heartbeat,
event append, terminal update, artifact publish는 현재 token과 lease가 일치할 때만 허용한다. Lease가
만료되면 attempt가 남은 job은 다시 `QUEUED`, 소진된 job은 fixed safe failure로 `FAILED`가 된다.

Container에는 job ID, attempt, fencing token label을 붙인다. Single-host recovery worker는 만료된 lease의
잔존 container를 찾아 bounded stop/remove한다. 이전 worker가 늦게 성공해도 stale token으로 DB state와
artifact를 publish할 수 없다.

## 7. URL import

Web은 URL을 fetch하지 않고 `SPEC_IMPORT` job만 만든다.

1. Web은 bounded URL length, `http`/`https`, no userinfo, no fragment를 확인한다.
2. Fetch container는 rootless, non-root, read-only, resource-bounded로 실행한다.
3. Fetch container의 유일한 egress는 policy gateway다.
4. Gateway가 DNS resolve, CIDR 검사, 검증한 IP로 connect를 한 경계에서 수행한다.
5. 각 redirect hop에 같은 검사를 반복한다.
6. 검증된 JSON/YAML OpenAPI만 private object storage에 저장한다.

초기 정책은 다음과 같다.

- scheme/port: HTTP/HTTPS, 80/443만
- deny: loopback, private, link-local, multicast, reserved, IPv6 ULA, cloud metadata
- redirect: 최대 3회, hop마다 전체 재검증, credential 전달 금지
- timeout: connect 5초, total 30초
- body: wire와 decompressed 각각 최대 10 MiB, bounded headers
- content: JSON/YAML 계열 media type과 실제 strict parse/OpenAPI validation
- retry: DNS/connect/5xx만 bounded retry, policy/4xx/invalid document는 재시도하지 않음

DNS rebinding은 pre-resolution 검사만으로 막지 않는다. Gateway가 resolve한 검증 주소로 직접 연결하고
fetch container가 gateway를 우회할 route를 갖지 않도록 한다.

## 8. Generation sandbox

Worker는 작업마다 digest-pinned runner image를 하나 실행한다.

- rootless Docker daemon under dedicated OS user
- numeric non-root user
- read-only root filesystem
- bounded tmpfs/workspace
- `network=none`
- `cap-drop=ALL`, `no-new-privileges`
- initial limits: CPU 2, memory 4 GiB, PID 256, wall timeout 10 minutes
- Docker socket and host secrets not mounted
- controlled input copy and allow-listed output collection

Runner image는 generator CLI, JDK 17/21, Gradle 9.6.1, canonical profile dependencies를 포함한다.
Generated build는 Gradle offline mode를 사용한다. Cache miss는 외부 download로 우회하지 않고 runner
image provisioning failure로 처리한다. MCP validation의 mock upstream은 같은 job container의 loopback을
사용하므로 external network가 필요 없다.

Worker는 output path, type, regular-file/symlink boundary, size, checksum을 검증한 뒤에만 private MinIO로
업로드한다. Container와 workspace는 성공/실패/취소 모든 경로에서 bounded cleanup한다.

## 9. Hosted Web/API

Thymeleaf UI는 dashboard, upload/URL import, generation form, job detail/event timeline, artifact download를
제공한다.

```text
POST /api/specifications/uploads
POST /api/specifications/imports
GET  /api/specifications
POST /api/jobs
GET  /api/jobs/{id}
POST /api/jobs/{id}/cancellation
GET  /api/artifacts/{id}/content
```

Mutation API는 same-origin CSRF를 요구한다. `POST /api/jobs`는 `Idempotency-Key`를 필수로 한다.
목록은 bounded cursor pagination을 사용한다. Artifact는 Web을 통해 authorization 후 stream하며 private
object key를 response에 노출하지 않는다.

초기 quota는 account별 `RUNNING=2`, `QUEUED=10`이다. 초과 요청은 기존 작업을 변경하지 않고 429를
반환한다. 기본 retention은 specification 원본/artifact 30일, job/event metadata 90일이다. 값은 hosted
operator configuration으로 더 짧게 조정할 수 있고 owner 삭제를 지원한다.

## 10. Deployment

Compose는 TLS proxy, Web, PostgreSQL, MinIO, private fetch gateway를 관리한다. PostgreSQL은 사용자 요구에 따라
`postgres:17.9-alpine`을 사용하며 구현 시 해당 tag의 정확한 multi-architecture manifest digest도 함께
고정한다.

Worker는 dedicated OS user의 rootless Docker socket을 사용한다. Worker를 container로 배포할 경우 그
rootless socket만 mount하며 host root Docker socket과 Web volume은 mount하지 않는다. Worker는
`SPEC_IMPORT` job마다 `apps/import-runner` image를 실행하고, 이 container는 private gateway network에만
연결한다. Generation container는 계속 `network=none`을 사용한다.

OIDC provider는 외부 서비스다. Production credential은 Compose environment literal이 아니라 file/secret
provider에서 주입한다. PostgreSQL과 MinIO는 외부 port를 publish하지 않는다.

## 11. Verification and release gate

구현 순서는 다음과 같다.

1. Persistence core: migration, ownership, idempotency, lease/fencing
2. Private storage and URL import
3. Worker and sandbox
4. Hosted OIDC Web and Compose acceptance

중간 단계는 local mode만 제공한다. Hosted ingress는 전체 vertical journey가 통과한 뒤 공개한다.

필수 검증은 다음과 같다.

- unit/contract: state transition, terminal immutability, fencing, idempotency conflict, CIDR classification,
  redaction, owner predicate
- PostgreSQL Testcontainers using `postgres:17.9-alpine`: migration, concurrent claim, lease expiry,
  stale token rejection
- MinIO: private access, restart persistence, cross-owner denial
- URL import adversarial: redirect-to-private, dual-stack private, rebinding fixture, compressed oversize,
  timeout, invalid OpenAPI
- sandbox: no egress, non-root, read-only, capability drop, resource kill, offline cache miss, orphan cleanup
- OIDC end-to-end: two users, import, generation, worker crash/retry, exact-one artifact, cross-user denial
- existing local compatibility and Java 17/21 by Spring AI 1/2 generation matrix
- backup/restore rehearsal for PostgreSQL and MinIO

Hosted startup fails when OIDC, migration, private storage policy, or worker heartbeat readiness is invalid.

## 12. Observability and safe failures

Metric and trace tags use finite categories only: job kind, canonical profile, stage, outcome, safe failure category.
Owner ID, issuer/subject, hostname, URL path/query, OpenAPI content, secret, filesystem path, process output are not
tag values.

Job events and HTTP errors use fixed safe code/summary. Operator diagnostics may contain exception type and internal
correlation ID but not cause message or user input. Import bytes, queue wait, run duration, lease recovery, sandbox kill,
artifact bytes, quota rejection are measured without high-cardinality identifiers.

## 13. Complexity

- Indexed owner/job lookup and lease candidate selection: expected `O(log n + k)` for page/claim size `k`
- Idempotency lookup: expected `O(log n)` via unique index
- URL and artifact streaming validation: `O(b)` time and bounded `O(1)` streaming memory for byte count `b`, with
  parser memory bounded by the 10 MiB document limit
- Persistent space: `O(s + a + e)` for specification bytes `s`, artifact bytes `a`, and job events `e`; retention
  bounds long-term growth

## 14. 주의사항

- 제약: 초기 deployment는 single Linux host와 동일 rootless Docker daemon에서의 recovery를 전제로 한다.
- 위험: gateway를 우회하는 network route가 생기면 DNS rebinding 방어가 무효화된다.
- 위험: stale worker write에 fencing token 조건이 빠지면 duplicate artifact와 terminal state corruption이 생긴다.
- 예외: private/internal URL import, arbitrary ports, online Gradle dependency download는 지원하지 않는다.
- 예외: MinIO single-node와 PostgreSQL single instance는 reference deployment이며 HA를 제공하지 않는다.

## 15. References

- [Spring Security OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)
- [Docker rootless mode](https://docs.docker.com/engine/security/rootless/)
- [Docker resource constraints](https://docs.docker.com/engine/containers/run/#runtime-constraints-on-resources)
- [Docker none network](https://docs.docker.com/engine/network/drivers/none/)
- [PostgreSQL 17.9 Alpine image](https://hub.docker.com/layers/library/postgres/17.9-alpine/images/sha256-6846159e2fb85832790f8f01594a7f79d8308d78e61b5d9b9406b08f59e82679)
