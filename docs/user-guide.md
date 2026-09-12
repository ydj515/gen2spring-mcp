# OpenAPI MCP Generator 사용자 가이드

이 문서는 설치, CLI와 local UI 사용법, 생성 설정, runtime 계약, 검증 단계와 지원 경계를 설명한다.
프로젝트 개요와 가장 짧은 시작 방법은 [루트 README](../README.md)를 먼저 참고한다.

## 요구 환경

- 생성기 실행용 Java 21. 저장소의 `mise.toml`은 Java 21.0.2를 고정한다.
- Java 17 profile 검증용 별도 Java 17 JDK와 `GEN2SPRING_JAVA_17_HOME` 절대 경로
- Gradle Wrapper 9.6.1. 시스템 Gradle 설치는 필요하지 않다.
- stable file identity와 hard link를 지원하는 로컬 파일시스템
- 최초 빌드와 generated project 검증에 필요한 Gradle 배포본과 Maven dependency 네트워크 접근

```bash
mise install
mise install java@17
export GEN2SPRING_JAVA_17_HOME="$(mise where java@17)"
mise exec -- java -version
```

Gradle Wrapper JVM은 host의 Java 21로 시작될 수 있다. 다음 속성은 wrapper JVM을 바꾸지 않고
generated compile/test toolchain 탐색만 verified target JDK로 제한한다. ApplicationContext와
MCP 단계의 boot JAR는 verified target home의 `bin/java`로 실행한다.

```text
-Dorg.gradle.java.installations.auto-detect=false
-Dorg.gradle.java.installations.auto-download=false
-Dorg.gradle.java.installations.paths=<verified target home>
```

target JDK가 없거나 profile과 version이 다르면 검증은 고정 오류로 `UNVERIFIED` 처리하며 ZIP을
만들지 않는다. 보고서에는 JDK 절대 경로, probe 출력과 실행 command를 기록하지 않는다.

## 빌드와 설치

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew clean test integrationTest \
  :apps:cli:installDist :apps:web:bootJar \
  --no-daemon --non-interactive
```

```text
apps/cli/build/install/openapi-mcp/bin/openapi-mcp
apps/web/build/libs/web.jar
```

## Local operation editor

Spring Boot 3.5 WebMVC + Thymeleaf UI에서 specification 분석, endpoint 선택, Tool 설정과 preview,
생성·검증, artifact download를 수행한다. 브라우저는 OpenAPI를 해석하지 않고 서버가 반환한 지원 판정을 표시한다.

```bash
mise run dev
mise run ui:test
mise run ui:build
```

포트를 고정하려면 `GEN2SPRING_UI_PORT=8080 mise run dev`로 실행한다. 직접 실행할 수도 있다.

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
GEN2SPRING_UI_PORT=0 \
mise exec -- ./gradlew :apps:web:bootRun --quiet --no-daemon --non-interactive

mise exec -- ./gradlew :apps:web:bootJar --no-daemon --non-interactive
GEN2SPRING_UI_PORT=0 mise exec -- java -jar apps/web/build/libs/web.jar
```

서버는 준비되면 `{"status":"READY","url":"http://127.0.0.1:<port>/"}`를 출력한다.
binding은 `numeric loopback only`다. 기본 local mode는 public multi-user service가 아니다.
remote address, 잘못된 `Host`·`Origin`, forwarded header를 거부하고 Spring Security session CSRF를 사용한다.

UI 작업 흐름은 OpenAPI 파일, API endpoint 선택, 생성 설정의 세 단계다. 파일 input과 drag-and-drop은
업로드·분석·완료·오류 상태를 표시하고, 파일을 교체하거나 제거하면 선택·override·preview·job 상태를 초기화한다.
모든 endpoint를 보여 주되 지원 불가 항목은 이유와 함께 비활성화하고, 경고 포함 지원 항목은 선택할 수 있다.
전체 선택은 선택 가능한 endpoint에만 적용하며, 선택한 endpoint별 Tool 설정을 접어서 편집한다.
입력 경계는 `local files only; no URL import`이며 OpenAPI는 10 MiB, configuration은 1 MiB로 제한한다.
capacity는 `one running plus one queued job`이고 세 번째 active job을 거부한다. terminal job은 마지막
접근 후 한 시간이 지나면 정리한다. ZIP은 `VALIDATED` 결과에만 제공한다.

## Hosted multi-user platform

Hosted mode는 OIDC `(issuer, subject)`를 account UUID에 매핑하고 PostgreSQL 17.9에 작업 상태를,
private MinIO에 specification과 artifact를 저장한다. URL import는 mTLS fetch gateway와 전용 import
runner를 통과하며, Worker는 rootless Docker의 non-root/read-only/network-none sandbox에서 생성한다.

TLS proxy만 host port를 publish한다. Web은 migration, private bucket policy, Worker heartbeat와
secure OIDC/session 설정이 유효하지 않으면 시작하지 않는다. 자세한 구성은
[Hosted 배포 가이드](../deploy/hosted/README.md)를 따른다.

```bash
mise run hosted:config
mise run hosted:up
mise run hosted:acceptance
```

### Managed Runtime

Hosted owner는 validation을 통과한 immutable Tool Catalog 하나를 runtime으로 활성화할 수 있다. credential이
필요한 Catalog는 먼저 owner-scoped OPAQUE·Bearer·Basic credential을 생성하고 activation의
`credentialBindings`에서 exact slot에 연결한다. plaintext credential과 runtime/grant token은 쓰기 응답에서만
사용하며, 조회 API와 audit에는 포함되지 않는다. token은 PostgreSQL에 HMAC digest로만 저장된다.

```text
POST /api/tool-catalogs/{catalogId}/runtimes
GET  /api/runtimes/{runtimeId}
POST /api/runtimes/{runtimeId}/revocation
POST /api/credentials
GET  /api/credentials
POST /api/credentials/{credentialId}/rotation
POST /api/credentials/{credentialId}/revocation
POST /api/runtimes/{runtimeId}/grants
GET  /api/runtimes/{runtimeId}/grants
POST /api/runtimes/{runtimeId}/grants/{grantId}/revocation
GET  /api/runtimes/{runtimeId}/audit
GET  /api/tool-catalogs/{catalogId}/diff?targetCatalogId={targetCatalogId}
POST /api/runtimes/{runtimeId}/migrations
GET  /api/runtimes/{runtimeId}/migrations?limit=50&before={sequence}
POST /api/runtimes/{runtimeId}/rollback
```

활성화 또는 grant 응답의 endpoint에 `Authorization: Bearer <one-time-token>`을 보내 MCP Streamable HTTP
`initialize`, `tools/list`, `tools/call`을 수행한다. transport는 stateless라 cookie, `Mcp-Session-Id`, sticky
routing이 필요 없다. 활성화 기본 수명은 24시간, 최대 수명은 30일이며 만료 또는 revoke 이후 요청은 동일한
401 응답으로 거부된다. grant의 `allowedTools`는 `tools/list`와 `tools/call`에 동일하게 적용된다.

Managed Runtime은 runtime ID, Catalog checksum, policy checksum별 immutable MCP Java SDK server handle을
사용한다. rate acquisition과 audit 상태 전이는 PostgreSQL에서 원자적으로 수행되어 replica가 달라도 동일하다.
credential은 audit 시작과 rate 허용 뒤 해당 Tool slot만 복호화하며 user argument가 header/query target을
덮어쓸 수 없다. provider 요청은
database와 외부 egress를 함께 가진 프로세스에서 실행하지 않고, mTLS 전용 `provider-egress`가 public
HTTP/HTTPS 80/443 destination만 resolve-and-connect한다. redirect, private/reserved/mixed DNS answer,
hop-by-hop header, 1 MiB 초과 body는 fail-closed로 거부한다.

runtime handle 용량이 가득 차면 활성 handle을 evict하지 않고 새 runtime 요청을 고정 503으로 거부한다.
typed output schema가 있는 Tool의 성공 응답은 text content와 동일한 normalized `structuredContent`를 함께 반환한다.

새 generation에 `predecessorCatalogId`를 지정하면 같은 owner의 현재 family head에서만 다음 immutable revision을
게시한다. 두 revision의 diff는 Tool 이름 기준으로 정렬되며 Tool 추가·description 변경·optional output property
추가만 compatible이다. active runtime migration은 현재 Catalog ID와 target checksum을 CAS 전제조건으로 받는다.

```json
{
  "expectedCurrentCatalogId": "<current-catalog-uuid>",
  "targetCatalogId": "<target-catalog-uuid>",
  "targetChecksum": "<target-runtime-metadata-sha256>"
}
```

전환은 runtime ID, bearer token, credential binding/version, provider override, expiry, 기존 grant, rate window와
audit을 보존하고 append-only history를 남긴다. 추가 Tool은 기존 scoped grant에 자동 부여되지 않는다. rollback은
가장 최근의 아직 되돌리지 않은 forward migration만 복원하며, active grant가 source에 없는 Tool을 허용하면
`CATALOG_MIGRATION_BLOCKED`로 거부한다. 해당 grant를 revoke한 뒤 같은 현재 Catalog ID로 재시도해야 한다.
rollback body는 `{"expectedCurrentCatalogId":"<current-catalog-uuid>"}`만 받으며 activation token을 다시
발급하거나 응답에 노출하지 않는다.

현재 완료 범위는 단일 Catalog activation, linear Catalog revision/diff/migration/rollback, exact credential slot
binding, owner/scoped grant, PostgreSQL rate·audit, stateless multi-replica transport다. 여러 Catalog를 한 runtime에
결합하는 공개 Gateway, OAuth2 credential acquisition, billing은 제공하지 않으며 생성 ZIP의 독립 MCP 서버 내용도
변경하지 않는다.

## CLI 사용법

설치된 실행 파일을 변수로 둔다.

```bash
OPENAPI_MCP=apps/cli/build/install/openapi-mcp/bin/openapi-mcp
```

### Profile 조회

```bash
"$OPENAPI_MCP" profiles
```

`profiles`는 다음 조합을 펼친 12개 항목을 ID 순서대로 항상 같은 JSON으로 출력한다. Spring AI 1.1.8은
Spring Boot 3.5.16과 Jackson 2를 사용하며 `McpToolParam` annotation을 생성하지 않는다. Spring AI 2.0.0은
Spring Boot 4.1.0을 사용한다.

| Spring AI | Java | Build tool | Web stack | Model | Transport | 개수 |
| --- | --- | --- | --- | --- | --- | ---: |
| 1.1.8 | 17, 21 | Gradle 9.6.1, Maven 3.9.16 | MVC | Sync | Streamable HTTP | 4 |
| 2.0.0 | 17, 21 | Gradle 9.6.1, Maven 3.9.16 | MVC | Sync | Streamable HTTP | 4 |
| 2.0.0 | 17, 21 | Gradle 9.6.1, Maven 3.9.16 | WebFlux | Async | Streamable HTTP | 4 |

Maven profile은 Maven Wrapper 3.3.4를 사용한다. Spring AI 1.1 WebFlux Async 조합은 upstream transport
결함이 해결되고 Java·build tool 전체 acceptance가 통과할 때까지 선택 항목으로 등록하지 않는다.

정확한 profile ID는 다음과 같다.

| Profile ID | Java | Build tool | Server model |
| --- | ---: | --- | --- |
| `spring-ai-1.1-java17-mvc-streamable` | 17 | Gradle | MVC Sync |
| `spring-ai-1.1-java17-maven-mvc-streamable` | 17 | Maven | MVC Sync |
| `spring-ai-1.1-java21-mvc-streamable` | 21 | Gradle | MVC Sync |
| `spring-ai-1.1-java21-maven-mvc-streamable` | 21 | Maven | MVC Sync |
| `spring-ai-2.0-java17-mvc-streamable` | 17 | Gradle | MVC Sync |
| `spring-ai-2.0-java17-maven-mvc-streamable` | 17 | Maven | MVC Sync |
| `spring-ai-2.0-java17-webflux-async-streamable` | 17 | Gradle | WebFlux Async |
| `spring-ai-2.0-java17-maven-webflux-async-streamable` | 17 | Maven | WebFlux Async |
| `spring-ai-2.0-java21-mvc-streamable` | 21 | Gradle | MVC Sync |
| `spring-ai-2.0-java21-maven-mvc-streamable` | 21 | Maven | MVC Sync |
| `spring-ai-2.0-java21-webflux-async-streamable` | 21 | Gradle | WebFlux Async |
| `spring-ai-2.0-java21-maven-webflux-async-streamable` | 21 | Maven | WebFlux Async |

Java 17 image는
`eclipse-temurin:17.0.19_10-jre-noble@sha256:543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8`,
Java 21 image는
`eclipse-temurin:21.0.11_10-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64`로 고정한다.

Java 21 기본 profile은 `spring-ai-2.0-java21-mvc-streamable`이다. 생성 Dockerfile은 위 image를
digest로 고정하고 `USER 10001:10001`로 실행한다. `.dockerignore`는 Dockerfile과 실행 JAR만 포함한다.

### OpenAPI 분석

```bash
"$OPENAPI_MCP" inspect \
  --spec apps/cli/src/integrationTest/resources/openapi/weather-api.yaml \
  --output /private/tmp/gen2spring-weather-analysis.json
```

분석 결과에는 원본 checksum, OpenAPI version, operation별 status·typed issue, security scheme과 warning이 담긴다.
기존 output 파일은 덮어쓰지 않는다.

### 프로젝트 생성

```bash
"$OPENAPI_MCP" generate \
  --spec apps/cli/src/integrationTest/resources/openapi/weather-api.yaml \
  --config apps/cli/src/integrationTest/resources/config/weather-generation.yaml \
  --output /private/tmp/gen2spring-weather-mcp
```

기존 output directory나 같은 이름의 sibling ZIP은 덮어쓰지 않는다. 검증 실패 시 생성 directory와
`UNVERIFIED` 보고서는 보존하지만 ZIP은 생성하지 않는다.

## 생성 설정

```yaml
project:
  groupId: com.example
  artifactId: weather-mcp-server
  packageName: com.example.weather
provider: kma
domain: weather
targetProfileId: spring-ai-2.0-java21-mvc-streamable
validationLevel: MCP_PROTOCOL
validation:
  toolCall:
    operationId: getForecast
    arguments:
      stationId: STN01
      days: 3
      location:
        latitude: 37.5
        longitude: 127.0
operations:
  - operationId: getForecast
    enabled: true
    toolName: kma_weather_get_forecast
    toolDescription: Get the public weather forecast for a grid location.
    responseNormalization:
      dataPath: /response/body/items/item
      successCodePath: /response/header/resultCode
      successValues: ["00"]
      errorMessagePath: /response/header/resultMsg
      totalCountPath: /response/body/totalCount
    parameters:
      serviceKey:
        source: SERVER_SECRET
        environmentVariable: KMA_SERVICE_KEY
```

설정은 알 수 없는 field, duplicate key, YAML anchor·alias, explicit tag와 잘못된 scalar type을 거부한다.
최소 하나의 operation이 enabled여야 하며 `MCP_PROTOCOL`에는 대표 operation과 Tool schema를 만족하는
arguments가 필요하다. JSON Pointer는 `/`로 시작하고 최대 256자·32 token이다.

parameter source는 `USER_INPUT`과 `SERVER_SECRET`만 지원한다. secret 값이 아니라 environment variable
이름만 설정에 기록한다. `PROVIDER_BASE_URL`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`,
`SPRING_APPLICATION_JSON`은 secret 이름으로 사용할 수 없다.

```bash
export KMA_SERVICE_KEY='replace-with-local-secret'
export PROVIDER_BASE_URL='https://provider.example.test'
cd /private/tmp/gen2spring-weather-mcp
./gradlew bootRun
```

secret 값은 Tool schema, source, manifest, report와 ZIP에 저장하지 않는다. runtime의 기본 upstream
경계는 connect 2초, read 5초, total 10초, 동시 실행 16개, queue 64개, body 1 MiB다.

## Runtime metrics와 OpenTelemetry

네 profile은 runtime `0.3.0`에서 같은 telemetry 계약을 사용한다.

- `gen2spring.runtime.mcp.tool.call`
- `gen2spring.runtime.provider.request`
- `gen2spring.runtime.provider.response.bytes`
- `gen2spring.runtime.provider.executor.active`
- `gen2spring.runtime.provider.executor.queued`

metric tag는 `target.profile`, `outcome`, `error.category`, `http.status.class`의 정해진 부분집합만
사용한다. Tool 이름과 operation ID는 span attribute에만 기록한다. secret, URL·query, Tool argument,
raw request/response, exception message와 stack trace는 telemetry에 기록하지 않는다.

기본값은 health endpoint만 노출하고 Prometheus와 OTLP export를 끈다. loopback Prometheus를 켤 때는
별도 management port를 사용한다.

```bash
MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
MANAGEMENT_SERVER_PORT=9464 \
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,prometheus \
MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED=true \
./gradlew bootRun
```

Spring Boot 3.5 / Spring AI 1.1의 trace exporter는
`MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=true`, Spring Boot 4.1 / Spring AI 2.0은
`MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED=true`로 명시적으로 활성화한다. provider error envelope는
active OpenTelemetry span의 trace ID를 사용하고, span이 없을 때만 32자리 local random hex를 사용한다.

## 응답과 실행 정책

`responseNormalization`이 없으면 provider JSON을 그대로 반환하고 빈 2xx body는 JSON `null`로 반환한다.
설정된 operation은 `data`, 선택적 `page`, 선택적 `provider` field만 포함하는 normalized envelope를 만든다.
예상된 provider·HTTP·timeout·availability·protocol·capacity 오류는 JSON-RPC transport 오류가 아니라
`isError=true`인 MCP Tool result로 반환한다.

`output.mode: TYPED`는 supported JSON object response에서 typed output DTO를 생성한다. ambiguous success
response schema, composed/recursive success response schema와 unsupported media type은 source 생성 전에 거부한다.

GET operation에 bounded retry를 실행할 수 있다. `maxRetries` 1..3, initial backoff 5000ms 이하,
max backoff 10000ms 이하이며 total timeout 안에서만 적용한다. GET operation에 bounded pagination을 실행할
수 있으며 `maxPages` 2..20, `maxItems` 1..2000과 items/next JSON Pointer를 요구한다.

## 검증과 종료 코드

생성 과정은 다음 단계를 순서대로 실행한다.

1. `COMPILE`: target JDK로 `classes test bootJar`
2. `APPLICATION_CONTEXT`: target JDK로 executable JAR 기동
3. `MCP_INITIALIZE`: `/mcp` initialize와 initialized notification
4. `MCP_TOOLS_LIST`: Tool name, description, input schema exact comparison
5. `MCP_TOOL_CALL`: 대표 Tool과 loopback mock upstream의 wire/result contract comparison

검증은 실제 provider가 아니라 loopback mock upstream만 호출한다. report는 `VALIDATED` 또는
`UNVERIFIED`와 각 stage의 status, duration, warning/error count, bounded summary를 기록한다.

| 종료 코드 | 의미 |
| ---: | --- |
| `0` | 성공 |
| `2` | CLI argument, config 또는 target profile 오류 |
| `3` | OpenAPI load, parse, semantic 또는 secret policy 오류 |
| `4` | source generation 또는 internal 오류 |
| `5` | compile, ApplicationContext 또는 MCP 검증 실패 |
| `6` | ZIP packaging 실패 |

## 생성 산출물

성공한 output에는 선택한 Gradle 또는 Maven project, generated source/test, `application.yml`, generated `README.md`,
`Dockerfile`, `.dockerignore`, 원본 OpenAPI, `GENERATION_MANIFEST.json`, `VALIDATION_REPORT.json`과
sibling ZIP이 생긴다.

ZIP entry는 path order, timestamp와 file mode를 결정적으로 기록한다. 같은 input/profile은 report를
제외한 모든 entry가 byte-exact하다. report는 `durationMillis`, `stdoutBytes`, `stderrBytes` 측정값만
정규화한 뒤 나머지가 같아야 한다. validation workspace의 `build/`, `.gradle/`, process log는 배포
directory와 ZIP에 포함하지 않는다.

### 생성 프로젝트 소스 구조

```text
src/main/java/{packageName}/
├── application/
│   └── {Domain}McpApplication.java       # Spring Boot Application 진입점
├── generated/
│   ├── dto/                              # 입력/출력 Record (Jakarta Validation 포함)
│   │   ├── {ToolName}Input.java
│   │   └── {ToolName}Result.java
│   ├── metadata/                         # API 엔드포인트 URL, HTTP Method, 바인딩 매핑 정보
│   │   └── {Domain}Operations.java
│   └── tool/                             # MCP 도구 진입점 및 ToolSpecification 빈 등록
│       ├── {Domain}McpTools.java
│       └── {Domain}McpToolCallbacks.java  # MVC Sync
│           또는 {Domain}McpToolSpecifications.java  # WebFlux Async
└── runtime/                              # 프로덕션 안정성 보장 엔진
    ├── OpenApiOperationExecutor.java    # MVC RestClient 또는 WebFlux WebClient 실행 경계
    ├── ResponseNormalizer.java           # 응답 정규화 및 에러 포맷팅
    ├── RuntimeTelemetry.java             # Micrometer 메트릭 및 W3C 분산 추적
    ├── ToolArgumentContext.java          # MVC Sync 파라미터 컨텍스트 전달
    └── RetryPolicy / PaginationPolicy    # 재시도 및 페이징 제어 (선택적 생성)
```

### 설계 배경: annotation-scanner 비활성화 이유

생성 프로젝트의 `application.yml`에서 `spring.ai.mcp.server.annotation-scanner.enabled: false`를 기본 적용하는 이유는 다음과 같습니다.

1. **OpenAPI 스키마 무결성 보장 (Deterministic Tool Schema)**: Spring AI의 자동 리플렉션 스캔은 Java 파라미터 타입으로부터 JSON Schema를 동적 생성하므로 OpenAPI 원본의 세부 제약(포맷, required 순서, custom constraints 등)이 유실될 수 있습니다. 본 생성기는 OpenAPI 명세에서 도출된 엄격한 JSON Schema 리터럴을 `DefaultToolDefinition.inputSchema`에 직접 주입하여 MCP 클라이언트와의 계약을 완벽히 보장합니다.
2. **도구 중복 등록 및 어노테이션 혼선 방지**: Spring AI MCP Server Starter의 스캐너는 일반 `@Tool`이 아닌 `@McpTool`을 스캔하며, Spring AI 버전 간(1.1의 Community 패키지 vs 2.0의 공식 패키지) 어노테이션 네임스페이스가 상이합니다. 어노테이션 스캐너를 비활성화하고 profile에 맞는 Sync 또는 Async `ToolSpecification` 빈으로 명시적 등록해 도구 중복 등록과 스캔 누락을 차단합니다.
3. **런타임 파이프라인 및 안전한 에러 캡슐화**: 커스텀 `callHandler`를 통해 도구 호출 시 W3C 분산 추적 및 Micrometer 메트릭(`RuntimeTelemetry`)을 수집하고, 백엔드 API 오류 시 원시 스택트레이스 대신 정제된 safe error payload(`isError=true`)를 안전하게 반환합니다.

## 지원 범위와 제한

지원:

- 로컬 `.yaml`, `.yml`, `.json` OpenAPI 3.0.x와 3.1.x
- `GET`, `POST`, `PUT`, `PATCH`, `DELETE`
- path, query, header parameter와 JSON request body
- primitive, enum, array, object, non-recursive local `$ref`
- optional nullable query/header와 required 또는 optional nullable root request body
- `maxItems` 256 이하의 array와 bounded structural `uniqueItems`
- compatible object `allOf`, branch 8개 이하의 `oneOf`·`anyOf`·multi-type union
- compatible constraint를 결합하는 OpenAPI 3.1 `$ref` sibling
- Jakarta Validation, API key query/header의 `SERVER_SECRET` injection
- Java 17·21, Gradle·Maven, Spring MVC Sync, Spring AI 2.0 WebFlux Async
- Streamable HTTP, generic/typed JSON output

제한:

- path/header는 기본 `simple` scalar, query는 기본 `form` scalar와 scalar-item
  `form` + `explode=true` array만 지원한다.
- OpenAPI 3.1은 dialect 생략 또는 `https://spec.openapis.org/oas/3.1/dialect/base`만 허용하고,
  지원 type union의 단일 `null` member는 canonical nullable로, 나머지 bounded multi-type은 `anyOf`로 정규화한다.
- `uniqueItems`는 명시적인 지원 범위의 `maxItems`가 있어야 하며, composition은 branch 8개, 깊이 16,
  전체 branch 64를 넘지 않아야 한다.
- nullable path와 required nullable query/header, conflicting 또는 empty `allOf`, budget을 넘는 composition,
  remote `$ref`, custom JSON Schema dialect, discriminator와 recursive schema는 해당 endpoint를 이유와 함께
  지원 불가로 표시한다.
- Spring AI 1.1 WebFlux Async, SSE와 STDIO는 지원하지 않는다.
- local UI는 URL import를 제공하지 않는다. hosted URL import도 문서 내부 remote `$ref`는 거부한다.

Windows validation host는 repository `gradlew.bat`와 trusted `cmd.exe`, verified target의
`bin/java.exe`를 사용한다. 불안정한 wrapper/JDK identity와 command metacharacter는 fail-closed로
거부한다. 구현 경계는 [Windows 지원 issue #2](https://github.com/ydj515/gen2spring-mcp/issues/2)에서
추적했다.

CLI와 local UI의 process isolation은 전용 workspace, timeout과 bounded output에 한정된다.
Hosted mode만 rootless OCI sandbox에 network-none, read-only rootfs, non-root identity와 resource
limit을 적용한다.

## Spring 조립 경계

- Local Web과 Managed Runtime의 보안 필터는 `FilterRegistrationBean`의 servlet 자동 등록을 끄고
  `SecurityFilterChain`에서만 실행한다.
- 생성된 MVC·WebFlux executor는 Spring이 주입한 `RestClient.Builder`·`WebClient.Builder`를 복제해
  공통 필터와 설정을 보존한다. 공급자 전용 transport, timeout, 응답 크기 제한은 복제본에 적용한다.
- 자동 HTTP observation의 `NOOP` 설정은 원본 URL·쿼리·예외 유출 방지 계약이다.
  `RuntimeTelemetry`는 관리되는 `Tracer`·`MeterRegistry`를 사용하지만 observation registry는 격리한다.
  공용 registry로 바꾸려면 수동 타이머와 Boot meter handler의 중복 기록부터 해소해야 한다.
- 공급자 설정은 현재 시작 시 URI·timeout·응답 크기·동시 실행 한도를 검증한다. 설정 바인딩을
  `@ConfigurationProperties`로 옮길 때도 동적 secret binding과 기존 오류 코드를 유지해야 한다.
  전용 Jackson mapper의 null 직렬화·응답 정규화 계약도 호스트 mapper 설정과 별도로 유지한다.
- Web의 전역 오류 처리는 페이지를 포함해 민감 정보를 제거한 고정 JSON 응답을 제공한다.
  mTLS의 `client-auth: NEED`, 워커의 bounded queue·종료 처리, JDBC의 명시적 트랜잭션 경계는 유지한다.

## MCP 구현 방식 선택

생성 설정 화면에서 **MCP 구현 방식**을 선택한다. 두 방식 모두 Spring Boot를 사용한다.

| 방식 | 도구 등록 | 지원 대상 |
| --- | --- | --- |
| Spring AI 애노테이션 | `@McpTool`을 Spring AI annotation provider가 탐색 | Spring AI 1.1 MVC와 Spring AI 2.0 MVC·WebFlux, Java 17·21, Gradle·Maven |
| MCP Java SDK | SDK 0.18.3으로 서버와 도구를 직접 등록 | Spring Boot 3.5 MVC Sync, Java 17·21, Gradle·Maven |

SDK를 선택하면 지원되는 프로필만 표시하며 Spring AI 버전은 표시하지 않는다. 생성 프로젝트에서
Spring AI BOM, starter, import를 제외한다. manifest도 `springAiVersion` 대신 `mcpJavaSdkVersion`을 기록한다.
`targetProfileId`는 Java·Spring Boot·빌드 도구의 기존 호환 기준을 가리키며, 실제 등록 방식은
`mcpImplementation`으로 구분한다. SDK의 WebFlux·Spring Boot 4 조합은 현재 지원하지 않으며 API와 CLI도
해당 조합을 생성 전에 거부한다.

CLI 설정 파일에도 다음 필드를 지정할 수 있다. 나머지 프로젝트·operation·검증 설정은 동일하다.

```yaml
mcpImplementation: SPRING_AI_ANNOTATIONS
```

SDK 생성 시에는 Spring Boot 3 MVC 기준 프로필을 선택한다.

```yaml
mcpImplementation: MCP_JAVA_SDK
targetProfileId: spring-ai-1.1-java21-mvc-streamable
```

화면의 기본값은 `SPRING_AI_ANNOTATIONS`이다. 기존 설정 파일에서 필드를 생략하면
`SPRING_AI_EXPLICIT`로 처리하여 기존 Spring AI 명시적 등록 방식을 유지한다. 이 호환 값은 CLI/API에서
명시할 수도 있지만 화면의 새 생성 선택지에는 표시하지 않는다.

애노테이션 방식의 생성 메서드는 `CallToolRequest`를 받아 원본 인수를 유지한다. annotation provider로
`@McpTool`을 실제 탐색하고, 생성된 등록 설정이 OpenAPI 입력 스키마를 보존한다. 전역 scanner는 중복 등록을
막기 위해 꺼 둔다. 두 방식 모두 공통 `GeneratedToolCalls`에서 입력 검증, 안전한 provider 오류 변환,
단일 종료 관측성을 적용한다. JSON 속성명과 Java 식별자가 다른 입력도 원본 스키마대로 처리한다.
