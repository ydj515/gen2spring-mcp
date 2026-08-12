# Spring Boot Thymeleaf Web Migration Design

## 1. 목적

현재 `generator-web`의 JDK `HttpServer` 기반 로컬 operation editor를 Spring Boot 기반 Web
application으로 전환한다. 화면의 5단계 editor와 기존 Generator API 의미는 유지하면서 다음 단계의
배포·운영 확장을 수용할 수 있는 서버 경계를 만든다.

이번 변경은 hosting 기능 자체를 완성하지 않는다. 인증, 사용자별 데이터 격리, 영속 저장소, 분산 job
queue가 없는 상태에서 외부 network에 공개하면 안 된다. 기본 실행 모드는 계속 loopback 단일 사용자다.

## 2. 결정

### 2.1 선택한 stack

- Java 21
- Spring Boot 3.5.16
- Spring WebMVC
- Thymeleaf
- Spring Security
- repository의 기존 Jackson 2 계열
- 현재의 framework-free ECMAScript module과 CSS

Spring Boot 3.5를 선택해 현재 `generator-application`과 Web DTO의 Jackson 2 경계를 유지한다. Spring
Boot 4는 Jackson 3 전환과 API 차이를 동시에 가져오므로 이번 transport/view migration 범위를 넘는다.

Thymeleaf는 page shell, CSRF metadata, 향후 인증 사용자 정보와 server-rendered navigation을 담당한다.
operation 편집, preview, progress polling 같은 상호작용은 기존 JavaScript를 유지한다. 화면 전체를
server-side form으로 재작성하거나 React/Vite toolchain을 추가하지 않는다.

### 2.2 제외한 대안

- JDK `HttpServer` 유지: local tool에는 충분하지만 Spring Security, authentication, session, deployment
  lifecycle을 나중에 다시 설계해야 한다.
- React/Vite SPA + REST API: 복잡한 client state와 화면 수가 크게 늘면 유리하지만 현재는 Node build,
  별도 deployment artifact, duplicated validation 경계를 추가한다.
- Spring Boot 4: 새 application이라면 가능하지만 현 repository의 Jackson 2 application/core 경계와
  한 번에 바꾸면 migration 위험과 검증 범위가 커진다.

전환 조건은 authenticated 화면이 8개를 넘거나, 독립 frontend 배포가 필요하거나, UI release cadence가
backend와 분리될 때 React/Vite 분리를 재검토하는 것이다. Spring Boot 4는 repository control-plane이
Jackson 3로 전환되고 full Web/API regression이 확보된 뒤 검토한다.

## 3. 범위

### 3.1 포함

- `generator-web`을 executable Spring Boot application으로 전환
- WebMVC controller로 기존 `/api/**` route와 response 의미 보존
- Thymeleaf template으로 기존 `/` page shell 렌더링
- static CSS, JavaScript, favicon을 Spring resource convention으로 이동
- Spring Security CSRF와 기존 loopback/Host/Origin invariant 결합
- 기존 fixed, non-leaking Web error envelope 보존
- `SpecificationStore`, `GenerationJobManager`, `GeneratorApplication`을 Spring bean lifecycle로 관리
- exact machine-readable `READY` stdout 계약 보존
- `mise run ui`, `mise run ui:build`, `mise run ui:test`를 Boot lifecycle에 맞게 변경
- MockMvc와 real `bootJar` integration test

### 3.2 제외

- login, signup, password, OAuth/OIDC
- 사용자·조직·role·permission model
- 사용자별 specification, job, artifact 격리
- database와 migration
- shared/distributed artifact storage
- distributed queue, scheduler, multiple application replica
- reverse proxy와 forwarded-header trust policy
- remote listen address 또는 public deployment profile
- URL import
- current editor의 정보 구조 전면 재설계

## 4. Architecture

### 4.1 Application composition

`Gen2SpringWebApplication`이 `@SpringBootApplication` entry point가 된다. `WebApplicationFactory`의 수동
composition은 Spring configuration으로 옮긴다.

- `GeneratorApplication`: singleton bean, `GeneratorApplication.defaults()` 사용
- `SpecificationStore`: verified private temporary parent를 사용하는 singleton bean
- `GenerationJobManager`: 기존 single worker/queue/TTL bound를 유지하는 singleton bean
- `Clock`, TTL, capacity: configuration bean/value로 주입하되 external request로 변경할 수 없음
- `ObjectMapper`: Boot가 관리하는 Jackson 2 mapper 사용

`SpecificationStore`와 `GenerationJobManager`는 `@Bean(destroyMethod = "close")` 또는 동일한 lifecycle
hook으로 한 번만 닫힌다. interrupt, fatal `Error`, late callback, terminal state 계약은 기존 implementation을
변경하지 않는다.

### 4.2 MVC boundaries

controller는 다음 책임으로 분리한다.

- `EditorController`: `GET /`에서 `editor` Thymeleaf view 반환
- `ProfileController`: `GET /api/profiles`
- `SpecificationController`: upload와 preview
- `GenerationJobController`: job 생성, 조회, 삭제
- `ArtifactController`: archive, manifest, report download
- `WebApiExceptionHandler`: exception을 기존 `WebErrorMapper`의 fixed envelope/status로 변환
- `WebErrorController`: MVC 밖에서 발생한 `/api/**` 오류도 default Whitelabel body 대신 fixed envelope로 변환

controller는 OpenAPI 분석, Tool policy, source rendering, validation을 재구현하지 않는다. 모든 의미 처리는
기존 `GeneratorApplication`, `SpecificationStore`, `GenerationJobManager`, preview/job handler 경계에
위임한다. handler는 transport-neutral service로 축소하거나 controller에서 직접 application service를
호출한다.

### 4.3 Resource layout

- `src/main/resources/templates/editor.html`
- `src/main/resources/static/styles.css`
- `src/main/resources/static/app.js`
- `src/main/resources/static/api.js`
- `src/main/resources/static/state.js`
- `src/main/resources/static/editor.js`
- `src/main/resources/static/favicon.svg`
- `src/main/resources/application.yml`
- `src/main/resources/logback-spring.xml`

기존 5단계 markup, accessible labels, live region, desktop/400px responsive contract를 보존한다. template은
inline script, inline style, external CDN을 사용하지 않는다.

## 5. HTTP and API compatibility

다음 route와 성공 상태를 유지한다.

```text
GET    /api/profiles                              200
POST   /api/specifications                        201
POST   /api/specifications/{id}/preview           200
POST   /api/specifications/{id}/jobs              202
GET    /api/jobs/{id}                             200
DELETE /api/jobs/{id}                             204
GET    /api/jobs/{id}/archive                     200
GET    /api/jobs/{id}/manifest                    200
GET    /api/jobs/{id}/report                      200
```

upload는 기존 `Content-Type`, `X-Specification-Name`, 10 MiB bound를 유지한다. Servlet container가
header의 optional whitespace를 정규화할 수 있으므로 media type과 charset 의미를 고정하되 raw OWS는
byte equality 대상으로 삼지 않는다. configuration JSON은
1 MiB bound, duplicate field/trailing token/non-finite number rejection을 유지한다. opaque ID regex,
download allow-list, pinned private path, symlink/regular-file/size 검사도 유지한다.

error response는 다음 형태만 반환한다.

```json
{
  "error": {
    "code": "SPEC_PARSE_FAILED",
    "stage": "SPEC_PARSE",
    "message": "The specification could not be parsed"
  }
}
```

Spring binding, media type, route, CSRF, method 오류도 path, raw body, header value, exception message,
stack trace를 노출하지 않는 fixed error로 변환한다. `GeneratorException`은 기존 code, stage,
safeMessage만 사용한다.

## 6. Security model

### 6.1 Local mode invariant

`application.yml`의 기본값은 다음과 같다.

```yaml
server:
  address: 127.0.0.1
  port: ${GEN2SPRING_UI_PORT:0}
  forward-headers-strategy: none
  servlet:
    encoding:
      enabled: false
    session:
      timeout: 0
      cookie:
        http-only: true
        same-site: strict
spring:
  main:
    banner-mode: "off"
    log-startup-info: false
```

remote bind configuration은 이번 범위에서 제공하지 않는다. ordered
`WebServerFactoryCustomizer`가 다른 property source의 `server.address` 값과 무관하게 마지막에 numeric
IPv4 loopback을 강제한다. `SERVER_ADDRESS=0.0.0.0` mutation에서도 actual bind가 `127.0.0.1`인지 real
socket test로 고정한다. `OncePerRequestFilter`는 request body를 읽기 전에 다음을 검증한다.

- remote address가 exact IPv4 loopback `127.0.0.1`
- `Host`가 `127.0.0.1:<actual-port>`와 exact match
- `Forwarded`, `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Port`,
  `X-Forwarded-Proto`, `X-Real-IP` header가 모두 없음
- query string이 없음
- state-changing request의 `Origin`이 `http://127.0.0.1:<actual-port>`와 exact match

기존 process capability token은 제거하고 Spring Security session CSRF로 교체한다. `EditorController`는
CSRF token/header name을 Thymeleaf meta element에 렌더링하고 `api.js`가 state-changing request에 exact
header를 보낸다. missing/invalid token은 fixed `403 REQUEST_FORBIDDEN` envelope로 응답한다.

local session timeout은 `0`으로 두어 실행 중인 단일 사용자 workflow가 idle timeout으로 끊기지 않게 한다.
session cookie는 비영속이고 server-side session도 process 종료와 함께 사라진다. hosted mode에서는 이
정책을 재사용하지 않고 인증·session expiry·state 복구 정책을 별도로 설계한다.

Spring Security는 local mode에서 모든 route를 `permitAll`로 두되 CSRF, session fixation protection,
security headers를 활성화한다. login page와 generated password는 만들지 않는다. 이는 인증을 구현한 것이
아니며 public deployment를 허용하지 않는다.

Servlet encoding filter는 request `Content-Type`에 암묵적으로 charset를 추가하지 않도록 비활성화한다.
upload와 JSON request body는 bounded byte stream으로 읽고, JSON response의 UTF-8 charset는 Jackson MVC
converter가 명시한다. 이 분리는 기존 exact request media-type allow-list와 응답 charset 계약을 함께
보존한다.

### 6.2 Headers and browser policy

다음 정책을 유지한다.

- `Cache-Control: no-store` for root and API
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: no-referrer`
- frame deny / `frame-ancestors 'none'`
- CSP `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self';
  base-uri 'none'; form-action 'none'; frame-ancestors 'none'`
- no external asset, analytics, service worker, arbitrary URL fetch

secret 값, representative arguments, raw specification, private path, process output은 template/model/session,
response, application log에 기록하지 않는다. `sessionStorage`에는 기존 opaque specification/job ID만 허용한다.

### 6.3 Hosted mode gate

다음 조건을 모두 구현하고 별도 threat review가 통과하기 전에는 `server.address`를 외부로 열지 않는다.

- authenticated account와 session policy
- owner/tenant ID가 포함된 specification/job/artifact model
- per-owner authorization on every API and download
- persistent database and migration
- durable artifact storage with retention policy
- distributed capacity control or single-replica deployment invariant
- reverse proxy and trusted forwarded-header configuration
- audit, rate limit, quota, operational secret management

## 7. Startup, packaging, and mise

`generator-web`은 Spring Boot executable `bootJar`를 만든다. 기존 `application` distribution과
`installDist` start script는 제거한다.

- development: `mise run ui` -> `:generator-web:bootRun --quiet` (READY 외 Gradle stdout 억제)
- package: `mise run ui:build` -> `:generator-web:bootJar`
- test: `mise run ui:test` -> `:generator-web:test`
- direct package run: `java -jar generator-web/build/libs/<boot-jar>.jar`

`GEN2SPRING_JAVA_17_HOME`, `GEN2SPRING_JAVA_21_HOME`, `GEN2SPRING_UI_PORT` 전달은 유지한다. 기본 port는
0이고 실제 port를 application-ready 시점에 다음 bounded JSON 한 줄로 stdout에 쓴다.

```json
{"status":"READY","url":"http://127.0.0.1:<port>/"}
```

Spring/Boot log는 Logback `System.err` target을 통해 stderr로 보낸다. stdout에는 READY line만 허용해 existing
automation이 URL을 안전하게 읽을 수 있게 한다. shutdown은 Boot context, job manager, specification store,
observed worker/process를 bounded cleanup한다.

`swagger-parser`가 전이시키는 legacy `commons-logging`은 `generator-web` runtime classpath에서 제외하고
Spring의 `spring-jcl` bridge만 사용한다. 두 logging implementation 충돌 경고가 stdout 계약을 깨지 않도록
packaged-server test에서 dependency와 실제 output을 함께 검증한다.

## 8. Test strategy

### 8.1 TDD order

1. build/resource contract RED: Boot plugin/dependencies, template/static path, `bootRun`/`bootJar` mise task
2. context RED: application starts with loopback address and no generated login password
3. MVC RED: root template, exact profiles, upload, preview, job, artifact route
4. security RED: remote/Host/forwarded/Origin/CSRF rejection and header contract
5. error RED: Spring exceptions map to fixed envelope without sensitive value
6. lifecycle RED: single bean ownership, queue/capacity/terminal/close compatibility
7. real boot RED: ephemeral port READY line and browser/API journey
8. old `HttpServer` removal RED: forbidden import/class/source assertion

### 8.2 Unit and MockMvc

- `@SpringBootTest` context and canonical bean identity
- MockMvc exact route/status/content type/error body
- Thymeleaf page has CSRF meta, required labels/live regions, no inline/external asset
- missing/textual/invalid ID, method, media type, body size failure
- state-changing call without CSRF rejected; template token succeeds
- loopback/Host/Origin/forwarded header filter fail-closed
- hostile `server.address` override에도 actual listener는 numeric loopback only
- security header exact values
- existing job state, concurrency, TTL, interrupt, fatal, cleanup tests remain framework-independent

### 8.3 Real boot integration

real `bootJar`를 target Java 21로 시작하고 JDK `HttpClient`로 검증한다.

1. stdout exact READY line과 stderr secret/path non-leak
2. root Thymeleaf HTML과 CSRF token/session 획득
3. four profiles exact 조회
4. specification upload, preview, job start/poll
5. terminal artifact download/readback
6. exactly one generation journey
7. invalid Host/Origin/CSRF/body rejection
8. process/descendant bounded shutdown and port release

기존 browser acceptance의 desktop/400px, keyboard navigation, horizontal overflow, console/network error 검사도
유지한다.

### 8.4 Regression

focused:

```bash
mise exec -- ./gradlew :generator-web:test :generator-web:integrationTest \
  --no-daemon --non-interactive --rerun-tasks
```

repository acceptance는 기존 fast CI와 full local acceptance 경계를 유지한다. Boot migration 때문에
generator domain/policy/emitter semantics를 변경하지 않는다.

## 9. Migration sequence

1. Boot dependency와 resource contract test 추가
2. Spring application/configuration/lifecycle bean 추가
3. security filter와 `SecurityFilterChain` 추가
4. root Thymeleaf controller/template migration
5. API controller와 exception handler migration
6. JavaScript capability token을 CSRF metadata로 교체
7. MockMvc/security/error/lifecycle test migration
8. real `bootJar` integration과 browser acceptance migration
9. `LocalWebServer`, `RequestGuard`, `StaticAssetHandler`, `Main`, `WebArguments`와 obsolete manual HTTP
   transport code 제거
10. mise와 README를 Boot commands로 동기화

각 단계에서 API/core service test를 green으로 유지한다. old transport는 new MVC parity와 real boot journey가
green이 된 뒤 제거한다.

## 10. Complexity and trade-offs

- operation analysis/filter: operation 수 `n`에 대해 시간 `O(n)`, 상태 `O(n)` 유지
- preview/schema: selected operation 수 `m`과 schema size `s`에 대해 시간/공간 `O(m + s)` 유지
- request routing/security: request당 시간/공간 `O(1)`이며 body parsing은 bounded payload size에 비례
- current job manager: active 1 + queued 1의 상수 공간 bound 유지
- Thymeleaf page rendering: fixed shell 기준 `O(1)`; operation data는 기존 API에서 client가 가져옴

Spring Boot는 executable size, startup time, dependency 수를 늘린다. 대신 standard MVC/security/session,
deployment lifecycle, test tooling을 얻는다. 현재 화면 규모에서는 SPA 분리를 하지 않아 frontend 독립
배포와 client-side component ecosystem은 포기한다.

## 11. 주의사항

- 제약: 이번 migration 뒤에도 owner/tenant 경계가 없으므로 public multi-user service가 아니다.
- 위험: Spring의 default error/login/CSRF behavior가 기존 fixed API 계약을 우회하면 path, exception 또는
  HTML error body가 노출될 수 있다. controller advice와 security entry point를 independent test로 고정한다.
- 위험: Boot가 자동으로 `localhost`, forwarded header, generic bind를 허용하지 않도록 local filter와
  configuration을 함께 검증한다.
- 예외: reverse proxy, container replica, authentication provider, database를 붙이는 배포는 이번 설계로
  지원하지 않으며 hosted-mode 설계가 별도로 필요하다.
- 예외: existing `installDist` executable path는 제거되므로 automation은 `mise run ui` 또는 `bootJar`로
  전환해야 한다.

## 12. 완료 조건

- `generator-web`이 Spring Boot 3.5.16 WebMVC + Thymeleaf + Spring Security로 시작한다.
- 기존 5단계 editor와 `/api/**` 성공/error 의미가 유지된다.
- capability token이 Spring Security CSRF로 교체되고 loopback/Host/Origin/forwarded-header invariant가
  자동 검증된다.
- stdout READY, stderr logging, ephemeral/fixed port, shutdown/cleanup 계약이 통과한다.
- MockMvc, real boot, desktop, 400px browser acceptance가 통과한다.
- JDK `HttpServer`와 manual transport classes가 제거된다.
- mise task와 README가 `bootRun`, `bootJar`, Boot direct run을 정확히 문서화한다.
- README가 local-only와 hosted-mode 미완료 경계를 과장 없이 표시한다.
