# Local Operation Editor and Generator API Design

## 1. 목적

PRD P1의 마지막 명시 항목인 UI operation editor를 로컬 단일 사용자 Web UI로 제공한다.
사용자는 브라우저에서 로컬 OpenAPI 3.0 YAML/JSON 명세를 업로드하고, operation을 선택·편집하고,
canonical target profile과 대표 검증 인자를 설정한 뒤 실제 생성·검증 상태를 확인하고 ZIP,
manifest, validation report를 다운로드할 수 있다.

UI는 기존 analyzer, Tool policy, compatibility registry, generator, validator, packager를 그대로
사용한다. 브라우저 또는 Web API가 독자적인 OpenAPI/Tool 변환 규칙을 구현하지 않는다.

## 2. 범위

### 2.1 포함

- loopback 전용 local Generator API
- 정적 HTML, CSS, ECMAScript module 기반 5단계 wizard
- 로컬 `.yaml`, `.yml`, `.json` 파일 upload와 bounded inspection
- operation 검색, method/tag/supported filter와 enable 설정
- Tool name, description, parameter source, secret environment variable name 편집
- response normalization pointer와 typed success value 편집
- canonical 4개 target profile 선택
- Tool/schema/environment/profile/validation preview
- 비동기 generation, progress polling, validation stage 표시
- validated ZIP, manifest, validation report 다운로드
- desktop와 400px viewport, keyboard-only, screen-reader 상태 알림
- 실제 GenerationPipeline과 target JDK를 사용하는 end-to-end acceptance

### 2.2 제외

- URL import
- remote listen address와 remote browser access
- 사용자 계정, 인증 DB, 권한 모델
- 다중 사용자와 동시 generation
- persistent job history와 server restart 복구
- cloud deployment, external artifact storage, job queue
- retry, pagination, typed output DTO
- Windows validation host

URL import는 SSRF, redirect, DNS rebinding, content validation 정책을 별도 구현한 뒤 추가한다.
원격·다중 사용자 요구가 생기면 이 local API를 그대로 공개하지 않고 별도 authenticated
control-plane 설계를 사용한다.

## 3. 접근 방식

### 3.1 선택: JDK HttpServer와 정적 UI

새 `generator-web` 모듈이 JDK 21 `com.sun.net.httpserver.HttpServer`를 사용하고, classpath의
HTML/CSS/ES module asset을 제공한다. Node.js, external CDN, Spring Boot control-plane 의존성을
추가하지 않는다.

선택 근거:

- 현재 repository의 generator와 테스트가 Java 21/Gradle로 완결된다.
- loopback single-user API에는 servlet container나 frontend bundler가 필요하지 않다.
- 기존 core pipeline과 같은 JVM에서 typed interface로 연결할 수 있다.
- static asset과 API response가 versioned repository source로 결정적으로 관리된다.

### 3.2 제외한 대안

- Spring Boot local API: controller 구현은 편하지만 generated runtime과 무관한 control-plane
  dependency와 기동 비용이 생긴다.
- React/Vite와 Spring API: 대규모 UI에는 유리하지만 Node toolchain, package lock, frontend build,
  두 배포 산출물을 추가한다.

화면이 6개를 넘거나 remote deployment, 동시 사용자 2명 이상, persistent job history가 필요해지면
React와 authenticated Spring API 분리를 재검토한다.

## 4. 모듈과 의존성

### 4.1 generator-application

CLI와 Web의 공통 application 경계를 새 `generator-application` 모듈로 분리한다.

- `GeneratorApplication`: canonical registry, analyzer, Tool policy, 두 emitter, validator, packager를
  한 번 조립하고 CLI/Web에 동일 instance graph를 제공한다.
- `GenerationConfigurationParser`: bounded YAML/JSON byte를 strict schema로 읽어
  `GenerationRequest`를 생성한다.

현재 `GenerationConfigurationReader`는 filesystem boundary만 소유하는 thin CLI adapter로 유지한다.
YAML event/type/field/semantic 검증은 `GenerationConfigurationParser`로 이동한다. CLI와 Web이 같은
configuration을 다르게 수락하지 못하도록 parser 테스트를 이동·공유하고, CLI 회귀 테스트를 유지한다.

### 4.2 generator-core

`GenerationPlanner`는 analyzed `OpenApiDocument`와 `GenerationRequest`를 canonical profile,
Tool IR, expected Tool schema, secret environment variable name으로 변환한다. `GenerationPreview`는
UI에 필요한 profile, Tool, schema, secret name, warning, expected artifact metadata의 immutable DTO다.
두 type은 `generator-core`가 소유한다.

`GenerationPipeline`은 `GenerationPlanner` 결과를 사용하고 progress listener를 받는 overload를 제공한다.
기존 3-argument `generate(Path, GenerationRequest, Path)`는 `NOOP` listener로 위임해 CLI 호환성을
유지한다.

`preview(Path, GenerationRequest)`는 analyzer와 planner를 실행하고 선택 emitter가 source를 memory에서
한 번 렌더링하도록 한다. filesystem write, compile, application boot, MCP call, package는 수행하지 않는다.
sorted generated file key와 exact Tool/schema metadata를 `GenerationPreview`로 반환한다. 실제 job은 source를
다시 analyze/render하므로 preview result를 generation input으로 신뢰하지 않는다.

progress stage는 다음 finite 순서만 사용한다.

```text
ANALYZE
GENERATE
COMPILE
APPLICATION_CONTEXT
MCP_INITIALIZE
MCP_TOOLS_LIST
MCP_TOOL_CALL
PACKAGE
```

각 stage는 `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `SKIPPED` 중 하나다. job 생성 시 전체 stage를
`PENDING`으로 만들고, terminal failure 뒤의 stage는 `SKIPPED`로 seal한다. validation 구간에서는
validation report의 기존 stage 이름과 결과가 authoritative하며, UI progress는 이를 축약하거나
재분류하지 않는다. analyze/generate/package stage는 pipeline listener가 기록한다.

### 4.3 generator-validation

`GeneratedProjectValidator`는 progress listener를 받는 호환 overload를 제공한다.
`GradleMcpProjectValidator`는 각 기존 validation stage의 시작과 terminal result를 동일 순서로
전달한다. listener가 결과, process output, secret, argument 또는 filesystem path를 받지 않으며,
fixed stage/status/count metadata만 받는다.

### 4.4 generator-cli

`ApplicationFactory`는 `GeneratorApplication.defaults()`를 사용한다. installed CLI command,
stdout/stderr JSON, exit code, path boundary, configuration semantics는 변경하지 않는다.

### 4.5 generator-web

- `WebApplication`: argument parsing, loopback bind, shutdown hook
- `WebApplicationFactory`: shared `GeneratorApplication`과 web adapters 조립
- `LocalWebServer`: route, security header, request/response bound
- `SpecificationStore`: private workspace의 bounded source copy와 immutable analysis
- `GenerationJobManager`: single worker, one queued job, progress snapshot, TTL cleanup
- `WebErrorMapper`: fixed non-leaking HTTP error envelope
- `StaticAssetHandler`: versioned HTML/CSS/JS resource와 CSP
- `src/main/resources/web/*`: framework-free UI

## 5. API 계약

모든 response는 `application/json; charset=utf-8`이고 unknown request field, duplicate JSON key,
trailing token, non-finite number를 거부한다. download만 고정 binary content type을 사용한다.

### 5.1 Profile

```text
GET /api/profiles
```

canonical registry의 네 profile을 ID 순으로 반환한다. CLI `profiles`와 field/value/order가 같다.

### 5.2 Specification upload와 analysis

```text
POST /api/specifications
Content-Type: application/yaml | application/json | application/octet-stream
X-Specification-Name: source.yaml
```

body는 최대 10 MiB이고 파일 이름은 basename과 `.yaml`, `.yml`, `.json` suffix만 사용한다.
response는 opaque specification ID, checksum, version, base URL, operations, security schemes,
warnings를 반환한다. raw specification body와 private workspace path는 반환하지 않는다.

### 5.3 Preview

```text
POST /api/specifications/{specificationId}/preview
Content-Type: application/json
```

body는 CLI configuration과 같은 field/schema의 JSON 표현이며 최대 1 MiB다.
`GenerationConfigurationParser`와 `GenerationPlanner`가 검증한다. response는 다음만 포함한다.

- canonical profile metadata
- enabled Tool name, description, exact input schema
- secret environment variable name
- normalized response policy summary
- analyzer/policy warning
- deterministic expected project path 목록

secret value와 representative validation argument value는 preview response에 echo하지 않는다.

### 5.4 Generation job

```text
POST /api/specifications/{specificationId}/jobs
Content-Type: application/json
```

preview와 같은 strict configuration body를 받는다. server는 private workspace 안에 random job
directory를 만들고 `GenerationPipeline`을 single worker에서 실행한다. response는 opaque job ID와
`QUEUED` state만 반환한다.

```text
GET /api/jobs/{jobId}
```

job response:

```json
{
  "id": "opaque-id",
  "state": "RUNNING",
  "currentStage": "COMPILE",
  "stages": [
    {"stage": "ANALYZE", "status": "SUCCESS"},
    {"stage": "GENERATE", "status": "SUCCESS"},
    {"stage": "COMPILE", "status": "RUNNING"}
  ],
  "validationStatus": null,
  "error": null,
  "downloads": []
}
```

terminal state는 `VALIDATED`, `UNVERIFIED`, `FAILED`다. `FAILED` error는 기존 Generator error code,
stage, safe message만 포함한다. `UNVERIFIED`는 final validation report를 다운로드할 수 있지만 ZIP은
제공하지 않는다.

```text
GET /api/jobs/{jobId}/archive
GET /api/jobs/{jobId}/manifest
GET /api/jobs/{jobId}/report
DELETE /api/jobs/{jobId}
```

server가 job record에 저장한 exact path만 열고 client path/query를 파일 경로로 해석하지 않는다.
archive는 `VALIDATED`에서만 제공한다. manifest/report는 존재하고 regular non-symlink file이며
size bound를 만족할 때만 제공한다.

## 6. Job 상태와 concurrency

`GenerationJobManager`는 `ThreadPoolExecutor` core/max 1, `ArrayBlockingQueue` capacity 1을 사용한다.
세 번째 unfinished 요청은 `429`와 fixed `GENERATION_CAPACITY_EXCEEDED` error로 거부한다.

job state 전이는 다음만 허용한다.

```text
QUEUED -> RUNNING -> VALIDATED
                  -> UNVERIFIED
                  -> FAILED
```

terminal state는 한 번만 publish한다. late progress callback은 무시한다. interrupt는 flag를 복원하고
pipeline/process cleanup 뒤 fixed failure로 기록한다. fatal `Error`는 state를 `FAILED`로 seal한 뒤
동일 instance를 worker uncaught handler로 전달하고 ordinary error response로 삼키지 않는다.

completed job/specification은 마지막 접근 후 1시간 TTL로 삭제한다. 최대 retained specification 8개와
job 8개를 허용하며, 한도를 넘으면 가장 오래된 terminal/unused 항목만 정리한다. running/queued job과
그 specification은 eviction하지 않는다. explicit DELETE와 server close도 동일 bounded cleanup을 사용한다.

## 7. Loopback security

- server는 `127.0.0.1`만 bind한다. wildcard, hostname resolution, configurable remote address를
  제공하지 않는다.
- accepted request의 remote address가 loopback이 아니면 body를 읽기 전에 거부한다.
- startup port가 0이면 OS가 선택한 실제 port를 stdout의 한 bounded JSON line으로 알린다.
- process startup마다 `SecureRandom` 256-bit capability token을 만든다.
- root HTML의 `<meta name="generator-api-token">` placeholder에 token을 HTML-escaped 주입한다.
- 모든 `/api` request는 exact `X-Gen2Spring-Token`을 요구한다. 값은 log/error에 기록하지 않는다.
- state-changing request는 exact `Origin: http://127.0.0.1:<port>`를 요구한다.
- `Host`는 `127.0.0.1:<port>`만 허용한다. `localhost`, alternate IP, forwarded host를 허용하지 않는다.
- API response와 root HTML은 `Cache-Control: no-store`를 사용한다.
- static response는 `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
  `X-Frame-Options: DENY`를 사용한다.
- CSP는 `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self';
  base-uri 'none'; form-action 'none'; frame-ancestors 'none'`으로 고정한다.
- inline script/style/event handler, external asset, analytics, font, service worker를 사용하지 않는다.
- operation/configuration에는 secret 값이 아니라 environment variable 이름만 받는다.
- actual provider secret, provider network 요청, arbitrary URL fetch를 UI/API에서 받거나 수행하지 않는다.

## 8. 오류 계약

API error envelope:

```json
{
  "error": {
    "code": "SPEC_PARSE_FAILED",
    "stage": "SPEC_PARSE",
    "message": "The specification could not be parsed"
  }
}
```

- `400`: malformed HTTP/JSON/configuration
- `403`: host/origin/token/loopback security mismatch
- `404`: unknown or expired opaque ID
- `409`: invalid job state or unavailable artifact
- `413`: request body bound exceeded
- `415`: content type or suffix mismatch
- `422`: analyzer/policy/generation user error
- `429`: active/queued capacity exceeded
- `500`: fixed internal failure

GeneratorException의 code/stage/safeMessage만 허용한다. Throwable message, stack, path, uploaded filename,
configuration value, validation argument, token, request body, process output을 response/log에 넣지 않는다.

## 9. UI

### 9.1 Step 1: Specification

drag-and-drop과 file picker를 제공한다. URL input은 렌더링하지 않는다. upload 완료 후 title/version,
checksum, operation/warning count를 표시한다.

### 9.2 Step 2: Operations

operation을 method, tag, supported 상태, text로 filter한다. 각 operation은 enable checkbox를 가지며,
선택 항목의 Tool name/description, parameter source, secret environment variable name,
response normalization pointer/success value를 오른쪽 editor에서 수정한다. 400px 이하에서는 list와
editor를 한 열로 쌓는다.

### 9.3 Step 3: Target

groupId, artifactId, packageName, provider, domain과 canonical profile을 설정한다. unsupported 조합을
직접 구성하는 field를 제공하지 않는다.

### 9.4 Step 4: Preview

enabled Tool/schema, secret environment variable 이름, profile dependency/version, warnings, expected
project tree를 표시한다. 대표 validation operation을 enabled operation 중 하나로 고르고 arguments를
strict JSON object editor로 입력한다. server preview가 성공해야 generation을 시작할 수 있다.

### 9.5 Step 5: Generate

polling은 500ms로 시작해 2초까지 bounded backoff한다. stage timeline을 `aria-live="polite"`로
갱신하고 terminal state에서 polling을 중지한다. error code/stage/safe message만 표시한다.
VALIDATED에서는 ZIP/manifest/report, UNVERIFIED에서는 manifest/report만 제공한다.

### 9.6 접근성과 browser state

- 모든 input은 visible label과 programmatic name을 가진다.
- keyboard만으로 step, filter, operation selection, editor, generation, download를 조작한다.
- validation error 발생 시 error summary로 focus를 이동하고 field와 `aria-describedby`로 연결한다.
- 색만으로 method, warning, status를 구분하지 않는다.
- 400px viewport에서 horizontal page overflow가 없어야 한다.
- raw specification, configuration, argument는 storage에 저장하지 않는다.
- `sessionStorage`에는 opaque specification ID와 job ID만 저장한다.

## 10. 검증

### 10.1 Unit

- shared configuration parser의 YAML/JSON parity와 기존 strict bounds
- planner와 pipeline이 동일 canonical profile/Tool/schema object를 사용
- legal/illegal progress transition과 late callback
- job capacity, terminal seal, interrupt, fatal, TTL/eviction/cleanup
- host/origin/token/loopback, body/content-type/suffix bound
- fixed error mapping과 sensitive-value non-leak
- static asset CSP, no external URL/inline handler, required labels/live regions

### 10.2 API integration

JDK HttpClient로 real loopback server에 다음 journey를 실행한다.

1. root HTML에서 token 획득
2. four profiles exact 조회
3. fixture upload/analysis
4. operation edit configuration preview
5. async job start와 ordered progress poll
6. terminal artifact download와 content/readback
7. missing/invalid token, origin, host, duplicate/oversized request rejection
8. one active + one queued capacity와 TTL cleanup

### 10.3 Real generation acceptance

현재 target JDK와 installed Java 17 home을 사용해 대표 profile 하나를 실제 생성·검증한다.
archive, manifest, report를 API로 다운로드하고 existing CLI journey와 source checksum/validation status를
대조한다. 기존 four-profile CLI acceptance는 모든 profile의 compile/boot/MCP matrix를 계속 소유한다.

### 10.4 Browser acceptance

실제 browser에서 desktop과 400px viewport를 각각 검증한다.

- upload부터 preview까지 keyboard journey
- operation filter/edit와 enabled state
- progress polling과 terminal download link
- no horizontal page overflow
- browser console error와 non-loopback/external network request 0건

### 10.5 Full regression

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew clean test integrationTest :generator-cli:installDist \
  --no-daemon --non-interactive --rerun-tasks
```

`generator-web` test/integrationTest와 distributable start script도 full regression에 포함한다.

## 11. 결정성, 복잡도, 위험

- specification analysis와 operation list/filter는 operation 수 `n`에 대해 시간 `O(n)`, 상태 `O(n)`이다.
- preview Tool/schema 생성은 selected operation 수 `m`에 대해 시간/공간 `O(m + schema size)`다.
- job orchestration은 metadata 기준 시간/공간 `O(1)`이고 generated source/archive는 기존 bounded
  pipeline 비용을 그대로 사용한다.
- active/queued job 수와 retained session/job 수는 상수 bound다.

주요 위험과 대응:

- DNS rebinding/local CSRF: fixed numeric loopback Host, exact Origin, per-process capability token
- browser memory leak: one active session model, terminal polling stop, bounded retained IDs
- UI/core semantic drift: shared parser/planner/pipeline만 사용하고 independent API fixture로 검증
- progress race: atomic transition, single terminal seal, late callback ignore
- path traversal/symlink: opaque ID to pinned private path mapping, client path input 부재
- secret/raw leakage: allow-list response DTO, fixed error mapper, result/log/browser scan
- slow generation: bounded single worker, queue 1, polling backoff, existing process timeout/cleanup

## 12. 완료 조건

- 로컬 browser에서 명세 upload, operation 편집, target 선택, preview, generation, artifact download가 완결된다.
- UI/API와 CLI가 같은 configuration, profile, Tool IR, generator, validator, packager를 사용한다.
- current stage와 final validation stage/result가 순서대로 표시된다.
- security header/token/origin/host/body/path/concurrency/cleanup 계약이 자동 검증된다.
- desktop와 400px browser acceptance가 통과한다.
- existing 4-profile CLI acceptance와 full repository regression이 실패 없이 통과한다.
- README에서 UI operation editor를 완료로 문서화하고 Windows validation host만 별도 잔여 경계로 유지한다.
