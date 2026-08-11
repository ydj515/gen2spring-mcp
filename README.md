# OpenAPI MCP Generator

로컬 OpenAPI 3.0 명세에서 선택한 operation을 Java 17 또는 Java 21 target의 Spring Boot 3.5.16/
Spring AI 1.1.8 또는 Spring Boot 4.1.0/Spring AI 2.0.0 기반 Streamable HTTP MCP 서버 프로젝트로
생성하는 Java 21 CLI다. Spring AI 1.1 generated source는 Jackson 2를 사용하며 Spring AI 2의
`McpToolParam` annotation을 생성하지 않는다.
생성된 프로젝트를 실제로 컴파일하고 Spring 애플리케이션을 기동한 뒤 MCP `initialize`,
`tools/list`, 대표 `tools/call`과 upstream HTTP binding을 검증한 경우에만 ZIP을 만든다.

## 요구 환경

- 생성기 실행용 Java 21. 이 저장소의 `mise.toml`은 Java 21.0.2를 고정하며 생성기와
  generator module test JVM은 Java 21을 사용한다.
- Java 17 profile을 검증하려면 Java 17 JDK를 별도로 설치하고 절대 경로를
  `GEN2SPRING_JAVA_17_HOME`에 지정해야 한다. Java 21 기본 profile은 현재
  `java.home`이 Java 21이면 이를 사용하며, 필요하면 `GEN2SPRING_JAVA_21_HOME`으로 명시한다.
- Gradle Wrapper 9.6.1. 시스템 Gradle 설치는 필요하지 않다.
- 물리 경로와 안정적인 filesystem `fileKey`, hard link를 지원하는 로컬 파일시스템
- POSIX 환경에서는 생성된 `gradlew`에 owner execute 권한을 기록하고 검증할 수 있어야 한다.
- 최초 빌드와 생성 프로젝트 검증 시 Gradle 배포본과 Maven Central dependency를 받을 수 있어야 한다.

```bash
mise install
mise install java@17
export GEN2SPRING_JAVA_17_HOME="$(mise where java@17)"
mise exec -- java -version
mise exec -- ./gradlew test --no-daemon --non-interactive
```

Gradle Wrapper JVM은 host의 Java 21로 시작될 수 있다. 다음 속성은 wrapper JVM 자체를
target JDK로 바꾸지 않고, generated compile/test toolchain 탐색만 verified target JDK로 제한한다.
ApplicationContext와 MCP 단계의 boot JAR는 verified target home의 `bin/java`로 실행한다.

```text
-Dorg.gradle.java.installations.auto-detect=false
-Dorg.gradle.java.installations.auto-download=false
-Dorg.gradle.java.installations.paths=<verified target home>
```

선택한 target JDK가 설치되지 않았거나 profile의 Java version과 다르면 검증은 안전한 고정
오류로 `UNVERIFIED` 처리하며 ZIP을 만들지 않는다. 오류와 검증 보고서에는 JDK 절대 경로,
probe 출력, 실행 command를 기록하지 않는다.

`/var`처럼 symbolic link를 거치는 경로나 stable file identity를 제공하지 않는 가상·공유
파일시스템은 안전 경계에서 거부될 수 있다. macOS 임시 경로는 `/private/tmp`처럼 물리
경로를 사용한다.

## 빌드, 테스트, 설치

전체 단위·통합 검증과 CLI/Web distribution 설치는 다음 명령으로 실행한다.

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew clean test integrationTest \
  :generator-cli:installDist :generator-web:installDist \
  --no-daemon --non-interactive
```

설치된 실행 파일은 다음 경로에 생성된다.

```text
generator-cli/build/install/openapi-mcp/bin/openapi-mcp
```

이하 예시는 편의를 위해 해당 경로를 `OPENAPI_MCP` shell 변수로 둔다.

```bash
OPENAPI_MCP=generator-cli/build/install/openapi-mcp/bin/openapi-mcp
```

## 로컬 operation editor

브라우저에서 operation 선택, project/profile 설정, Tool schema preview, 실제 생성·검증과 artifact
다운로드를 완료하는 로컬 UI와 Generator API를 제공한다. 상태는 `UI operation editor complete`다.
설치와 실행은 다음과 같다.

```bash
mise exec -- ./gradlew :generator-web:installDist
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
generator-web/build/install/gen2spring-mcp-web/bin/gen2spring-mcp-web --port 0
```

`--port 0`은 사용 가능한 ephemeral port를 선택한다. 서버는 준비되면 stdout에
`{"status":"READY","url":"http://127.0.0.1:<port>/"}` 한 줄만 출력한다. 해당 URL을
브라우저에서 연다. binding은 `numeric loopback only`이고 hostname, wildcard, remote address를
허용하지 않는다. startup JSON에는 API token을 출력하지 않으며, token은 no-store HTML에만
주입된다. API는 remote address, exact `Host`, same-origin `Origin`, per-process token을 모두
검증한다.

작업 흐름은 다음 다섯 단계다.

1. Specification에서 로컬 `.yaml`, `.yml`, `.json` 파일을 선택하고 분석한다.
2. Operations에서 지원 operation을 선택하고 Tool 이름·설명·parameter source·response
   normalization을 편집한다.
3. Project and Target에서 project 좌표와 네 compatibility profile 중 하나를 선택한다.
4. Preview에서 대표 `tools/call` 인자를 입력하고 canonical Tool schema와 생성 파일 목록을 확인한다.
5. Generate and Download에서 실제 compile, ApplicationContext, MCP 검증을 실행하고 `VALIDATED`
   상태의 ZIP, manifest, validation report를 내려받은 뒤 job을 삭제한다.

입력 경계는 `local files only; no URL import`다. UI 자체는 외부 요청을 만들지 않고 같은
`127.0.0.1` origin의 고정 API route만 호출한다. OpenAPI는 최대 10 MiB, configuration JSON은
최대 1 MiB다. capacity는 `one running plus one queued job`이며 세 번째 active job은 거부한다.
완료된 specification/job은 최대 8개를 보존하고, terminal job은 마지막 접근 후 1시간이 지나면
정리한다. 종료 시 전용 임시 workspace를 정리한다.

manifest와 report는 terminal artifact로 제공하고, ZIP은 `VALIDATED`일 때만 제공한다. 모든
download는 생성 시 고정한 owned regular file의 identity, size, digest를 다시 확인한다. 대표 검증
인자, API token, target JDK 절대 경로는 preview, artifact, process output에 기록하지 않는다.

UI는 키보드 탐색, 오류 summary/focus, live status를 제공하고 400px viewport까지 가로 overflow 없이
동작한다. 실제 browser acceptance matrix는 최신 Chromium이다. Firefox와 Safari는 Fetch API,
ES modules, CSS Grid 지원이 필요하며 현재 자동 acceptance matrix에는 포함하지 않는다. 브라우저
extension이나 remote deployment는 신뢰 경계에 포함하지 않는다.

## CLI 사용법

### 지원 profile 조회

```bash
"$OPENAPI_MCP" profiles
```

`profiles`는 다음 네 항목을 ID 오름차순으로 항상 같은 JSON에 출력한다. Spring AI 1.1 profile은
Spring Boot 3.5.16, Spring AI 1.1.8, Gradle 9.6.1, Jackson 2와 explicit Tool schema를 사용한다.
Spring AI 2.0 profile은 Spring Boot 4.1.0, Spring AI 2.0.0, Gradle 9.6.1을 사용한다. 모든 profile은
Spring MVC Sync와 Streamable HTTP `/mcp`를 사용한다.

| ID | Java | Container image |
| --- | --- | --- |
| `spring-ai-1.1-java17-mvc-streamable` | 17 | `eclipse-temurin:17.0.19_10-jre-noble@sha256:543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8` |
| `spring-ai-1.1-java21-mvc-streamable` | 21 | `eclipse-temurin:21.0.11_10-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64` |
| `spring-ai-2.0-java17-mvc-streamable` | 17 | `eclipse-temurin:17.0.19_10-jre-noble@sha256:543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8` |
| `spring-ai-2.0-java21-mvc-streamable` | 21 | `eclipse-temurin:21.0.11_10-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64` |

Java 21 기본 profile은 `spring-ai-2.0-java21-mvc-streamable`이다. 기존 설정은 이 ID를
계속 사용하며, Java 17 target은 `targetProfileId`만 Java 17 ID로 변경한다.

### OpenAPI 분석

```bash
"$OPENAPI_MCP" inspect \
  --spec generator-cli/src/integrationTest/resources/openapi/weather-api.yaml \
  --output /private/tmp/gen2spring-weather-analysis.json
```

분석 결과에는 원본 checksum, OpenAPI 버전, operation, security scheme, warning이 담긴다.
`--output` 파일이 이미 있으면 덮어쓰지 않고 실패한다.

### 프로젝트 생성

```bash
"$OPENAPI_MCP" generate \
  --spec generator-cli/src/integrationTest/resources/openapi/weather-api.yaml \
  --config generator-cli/src/integrationTest/resources/config/weather-generation.yaml \
  --output /private/tmp/gen2spring-weather-mcp
```

`--output` 디렉터리와 같은 이름의 sibling ZIP이 이미 있으면 덮어쓰지 않는다. 검증에
실패하면 생성 디렉터리와 `UNVERIFIED` 보고서는 보존하지만 ZIP은 만들지 않는다.

## 생성 설정 YAML

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

스키마는 strict하게 읽는다.

- 최상위 필드는 `project`, `provider`, `domain`, `targetProfileId`, `validationLevel`,
  `validation`, `operations`만 허용하며 모두 필요하다.
- `project`에는 `groupId`, `artifactId`, `packageName`만 허용하며 모두 필요하다.
- `MCP_PROTOCOL` 검증에는 enabled operation 하나를 가리키는 `validation.toolCall.operationId`와
  해당 Tool schema를 만족하는 명시적 `arguments`가 필요하다.
- operation에는 `operationId`, `enabled`가 필요하고 `toolName`, `toolDescription`,
  `responseNormalization`, `parameters`를 선택적으로 지정한다. 최소 하나의 operation이
  enabled여야 한다.
- `responseNormalization`에는 `dataPath`, `successCodePath`, `successValues`,
  `errorMessagePath`, `totalCountPath`만 허용한다. pointer는 `/`로 시작하는 RFC 6901
  JSON Pointer이며 최대 256자·32 token이다. `successCodePath`와 1~16개의 typed scalar
  `successValues`는 함께 지정해야 한다.
- parameter source는 `USER_INPUT` 또는 `SERVER_SECRET`만 지원한다.
  `SERVER_SECRET`에는 대문자로 시작하는 `environmentVariable`이 필요하고,
  `USER_INPUT`에는 environment variable을 지정할 수 없다. 생성 runtime과 JVM/Spring bootstrap이
  소유하는 `PROVIDER_BASE_URL`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`,
  `SPRING_APPLICATION_JSON`은 secret environment variable 이름으로 사용할 수 없다.
- 알 수 없는 필드, 중복 key, YAML anchor·alias, explicit tag, 잘못된 scalar type을
  거부한다. `enabled`만 boolean이며 나머지 scalar는 string이다. Tool description의
  literal·folded block scalar는 일반 문자열로 허용한다. 단, `successValues`의 원소는
  JSON string, number, boolean typed scalar를 허용한다.
- `toolName`은 최대 64자의 lower snake case다. package와 최상위 Tool input 이름은
  안전한 Java identifier로 생성 가능해야 한다.

설정에는 secret 값이 아니라 환경변수 이름만 기록한다. 생성 프로젝트 실행 전에 실제
값을 프로세스 환경에 주입한다.

```bash
export KMA_SERVICE_KEY='replace-with-local-secret'
export PROVIDER_BASE_URL='https://provider.example.test'
cd /private/tmp/gen2spring-weather-mcp
./gradlew bootRun
```

secret 값은 Tool input schema, 생성 source, manifest, validation report, ZIP에 저장하지
않는다. 생성 프로젝트의 Streamable HTTP MCP endpoint는 기본 서버 주소의 `/mcp`다.
예를 들어 기본 포트에서는 `http://127.0.0.1:8080/mcp`다.

생성 runtime의 upstream 호출 기본 경계는 connect 2초, response read 5초, 전체 10초다.
동시에 최대 16개 요청을 실행하고 64개를 대기시키며, 한도를 넘으면 안전한 포화 오류로
실패한다. 응답 본문은 최대 1 MiB까지만 읽는다. 이 값은 생성된 `application.yml`의
`provider` 설정에서 조정할 수 있으며 timeout은 1~300,000ms 범위의 양수여야 한다.

## Runtime metrics와 OpenTelemetry

네 profile은 runtime `0.3.0`에서 같은 canonical telemetry 계약을 사용한다. MCP Tool 전체와
provider 요청에는 각각 `gen2spring.runtime.mcp.tool.call`,
`gen2spring.runtime.provider.request` observation/span과 Timer를 기록한다. 추가 meter는 다음과 같다.

- `gen2spring.runtime.provider.response.bytes`: bounded provider response byte DistributionSummary
- `gen2spring.runtime.provider.executor.active`: 실행 중인 provider task Gauge
- `gen2spring.runtime.provider.executor.queued`: 대기 중인 provider task Gauge

전체 canonical metric에서 허용하는 tag key는 `target.profile`, `outcome`, `error.category`,
`http.status.class`뿐이며, 각 meter는 설계된 부분집합만 사용한다. `target.profile`은 위 표의 네
canonical profile ID 중 하나이고, 나머지 값은 다음 allow-list로 제한한다.

- `outcome`: `success`, `expected_error`, `internal_error`, `fatal`
- `error.category`: `none`, `provider_business`, `upstream_client`, `upstream_server`,
  `upstream_timeout`, `upstream_unavailable`, `upstream_protocol`, `local_resource`,
  `argument_conversion`, `result_conversion`, `tool_execution`, `unexpected_runtime`, `fatal`
- `http.status.class`: `2xx`, `4xx`, `5xx`, `other`, `none`

Tool name과 operation ID는 span attribute에만 기록하고 metric tag에는 넣지 않는다. secret,
provider URL·query, Tool argument, raw request/response, exception message와 stack trace도 telemetry에
기록하지 않는다. provider span은 Tool span의 child이며 upstream에는 현재 span에서 만든 W3C
`traceparent` 하나만 전송한다. 사용자 명세의 `traceparent`, `tracestate`, `baggage`, B3 계열
header는 operation 생성 단계에서 거부한다.

기본 설정은 health endpoint만 노출하고 Prometheus와 OTLP export를 비활성화한다. loopback 전용
Prometheus scrape를 명시적으로 켜려면 다음처럼 별도 management port를 사용한다. remote scrape가
필요하면 인증된 trusted network 안에서만 노출한다.

```bash
MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
MANAGEMENT_SERVER_PORT=9464 \
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,prometheus \
MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED=true \
./gradlew bootRun
```

Prometheus endpoint는 `/actuator/prometheus`이며 dot 이름을 underscore 이름으로 노출한다. Timer와
DistributionSummary에는 `_count`, `_sum`, `_max` 계열이 함께 생긴다. OTLP는 신뢰하는 collector의
endpoint와 credential을 먼저 설정한 뒤 Boot family에 맞는 exporter를 명시적으로 켠다.

Spring Boot 3.5 / Spring AI 1.1:

```bash
MANAGEMENT_OTLP_METRICS_EXPORT_URL="$OTLP_METRICS_URL" \
MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true \
MANAGEMENT_OTLP_TRACING_ENDPOINT="$OTLP_TRACES_URL" \
MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=true \
./gradlew bootRun
```

Spring Boot 4.1 / Spring AI 2.0:

```bash
MANAGEMENT_OTLP_METRICS_EXPORT_URL="$OTLP_METRICS_URL" \
MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true \
MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT="$OTLP_TRACES_URL" \
MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED=true \
./gradlew bootRun
```

provider error envelope는 active OpenTelemetry span의 trace ID를 재사용한다. 유효한 active span이
없을 때만 local random 16-byte 값의 32자리 lowercase hex를 fallback으로 사용한다. sampling은
`MANAGEMENT_TRACING_SAMPLING_PROBABILITY`로 조정하며 기본값은 `0.1`이다.

## 응답 정규화와 오류 계약

`responseNormalization`이 없는 operation의 성공 응답은 provider JSON을 그대로 반환하며,
빈 2xx body는 JSON `null`로 반환한다. 설정된 operation은 원본 provider envelope의 나머지
필드를 제외하고 다음 field 순서의 고정 success envelope를 반환한다.

```json
{
  "data": [],
  "page": {
    "totalCount": 0
  },
  "provider": {
    "code": "00",
    "message": "NORMAL_SERVICE"
  }
}
```

`data`는 항상 존재한다. `page`는 `totalCountPath`가 있을 때만, `provider.code`와
`provider.message`는 대응 pointer가 있을 때만 존재한다. success code는 string, number,
boolean 원본 타입을 유지한다.

예상된 provider 업무 오류, HTTP·timeout·availability·protocol 오류, local capacity 오류는
JSON-RPC transport 오류가 아니라 `isError=true`인 MCP Tool 결과 하나로 반환한다.

```json
{
  "error": {
    "category": "PROVIDER_BUSINESS",
    "providerCode": "30",
    "providerMessage": "INVALID_REQUEST",
    "retryable": false,
    "httpStatus": 200,
    "operationId": "getForecast",
    "traceId": "4f7f2a510d6e47b3a0df47f1e4f036af"
  }
}
```

알 수 없는 `providerCode`, `providerMessage`, `httpStatus`는 JSON `null`이다. `traceId`는
active OpenTelemetry span의 유효한 trace ID를 우선 사용하고, span이 없으면 16-byte local random
value의 32자리 lowercase hex를 사용한다.

| category | retryable |
| --- | --- |
| `PROVIDER_BUSINESS` | `false` |
| `UPSTREAM_CLIENT` | HTTP 408, 425, 429만 `true` |
| `UPSTREAM_SERVER` | `true` |
| `UPSTREAM_TIMEOUT` | `true` |
| `UPSTREAM_UNAVAILABLE` | `true` |
| `UPSTREAM_PROTOCOL` | `false` |
| `LOCAL_RESOURCE` | `false` |

응답 처리는 JSON body만 지원한다. 이 슬라이스는 retry와 pagination을 자동 실행하지 않고,
typed output DTO를 생성하지 않는다.

## 검증과 종료 코드

`validationLevel`은 P0에서 `MCP_PROTOCOL`만 허용한다. 생성 과정은 다음 실제 검증을
순서대로 수행한다.

1. `COMPILE`: 선택한 target JDK와 생성 Gradle Wrapper로 `classes test bootJar` 실행
2. `APPLICATION_CONTEXT`: 같은 target JDK로 executable JAR를 사용 가능한 loopback port에서 기동
3. `MCP_INITIALIZE`: `/mcp`에 `initialize`와 initialized notification 전송
4. `MCP_TOOLS_LIST`: Tool 이름, 설명, input schema를 선택 operation과 정확히 대조
5. `MCP_TOOL_CALL`: 대표 Tool을 실제 호출하고 loopback mock upstream의 method, path,
   query, header, JSON body와 응답 계약을 대조

검증 중에는 OpenAPI provider URL에 접속하지 않고 loopback address의 ephemeral port에 기동한
mock upstream만 호출한다. 생성 애플리케이션은 loopback `PROVIDER_BASE_URL`과 deterministic
synthetic secret만 포함하는 sanitized environment에서 실행된다. 대표 인자와 synthetic secret은
검증 프로세스에서만 사용하고 source, manifest, validation report, ZIP, stdout, stderr에 저장하지 않는다.

보고서의 전체 상태는 현재 `VALIDATED` 또는 `UNVERIFIED`다. stage별로 `SUCCESS`,
`FAILED`, `SKIPPED`, duration, warning/error count, bounded summary를 기록한다. 원본
프로세스 출력과 stack trace는 저장하지 않는다.

| 종료 코드 | 의미 |
| --- | --- |
| `0` | 성공 |
| `2` | CLI 인자, 생성 설정 또는 target profile 오류 |
| `3` | OpenAPI load, parse, semantic 또는 secret 정책 오류 |
| `4` | source generation 또는 일반 내부 오류 |
| `5` | compile, ApplicationContext 또는 MCP 검증 실패 |
| `6` | ZIP packaging 실패 |

## 생성 산출물

성공한 output 디렉터리에는 다음 파일이 포함된다.

- Gradle Kotlin DSL, Gradle Wrapper 9.6.1, 선택한 Java 17 또는 Java 21 source와 generated test
- `src/main/resources/application.yml`, generated project `README.md`, `Dockerfile`, `.dockerignore`, `.gitignore`
- 원본 명세 byte를 보존한 `openapi/source.<extension>`
- `GENERATION_MANIFEST.json`: generator/template/runtime 및 target 버전, 원본·source
  checksum, operation-to-Tool mapping
- `VALIDATION_REPORT.json`: 전체 상태, 순서가 고정된 검증 stage와 관측 Tool
- output 디렉터리와 같은 이름의 sibling ZIP

source checksum은 manifest, validation report, `process-logs/`를 제외한 source tree를
경로 순으로 정규화해 계산한다. ZIP entry의 경로 순서, canonical timestamp, 파일 mode는
결정적으로 기록한다. 같은 input/profile의 archive entry는 `VALIDATION_REPORT.json`을 제외하고
byte-exact해야 한다. validation report는 stage의 `durationMillis`와 summary 안의 측정값
`stdoutBytes`·`stderrBytes`만 정규화한 뒤 정확히 같아야 한다. 따라서 측정값을 보존하는 raw ZIP
전체 byte가 같다고 주장하지 않는다. validation은 별도 workspace에서 수행하므로 배포 디렉터리와 ZIP에는
`build/`, `.gradle/`, process log가 포함되지 않는다.

profile별 Dockerfile은 위 표의 digest-pinned image를 사용하고 `USER 10001:10001`로
애플리케이션을 실행한다. `.dockerignore`는 Dockerfile과 빌드된 실행 JAR만 context에 포함한다.

생성기는 기존 output을 삭제하거나 merge하지 않는다. 실패 산출물과 더 이상 필요하지
않은 임시 디렉터리의 보관·삭제는 사용자가 명시적으로 수행해야 한다.

## 현재 지원 범위

- 로컬 `.yaml`, `.yml`, `.json` OpenAPI 3.0.x
- `GET`, `POST`, `PUT`, `PATCH`, `DELETE` operation 선택
- path, query, header parameter와 JSON request body
- primitive, enum, array, object, non-recursive local `$ref`
- required/optional, min/max, length, pattern의 generated Jakarta Validation
- API Key query/header를 `SERVER_SECRET` 환경변수로 주입
- Java 17·21 record input, Spring MVC Sync, Streamable HTTP, generic JSON output
- 실제 compile, generated test, ApplicationContext, MCP `initialize`, `tools/list`, 대표
  `tools/call`과 loopback mock upstream 검증

## 알려진 제한과 후속 P1 경계

- OpenAPI parameter와 JSON body property는 Java-safe MCP key로 변환하고 원본 upstream
  JSON 이름은 binding에 보존한다. 예를 들어 `postal-code`는 MCP의 `postalCode` 입력으로
  노출하지만 provider JSON에는 `postal-code`로 전송한다.
- min/max, length, pattern, format, enum 제약은 명시적 Tool schema로 생성해
  `tools/list`와 generated Jakarta Validation에 함께 유지한다.
- 대표 검증 인자의 pattern 평가는 CLI를 비정상적으로 오래 점유하지 않도록 길이·중첩·문자 접근
  예산을 적용한다. 중첩 quantifier처럼 안전하게 제한할 수 없는 Java regex는 fail-closed로 거부한다.
- parameter 직렬화는 path/header의 기본 `simple` scalar, query의 기본 `form` scalar와
  `form` + `explode=true`인 scalar-item query array만 지원한다. 비기본 style, object
  parameter, path/header array, nested array/object item은 operation 생성에서 제외한다.
- enum은 `tools/list`, JSON body, path/query/header 직렬화에서 원본 OpenAPI wire 값
  (예: `full-detail`)을 일관되게 사용한다.
- remote `$ref`, URL import, OpenAPI 3.1, `oneOf`, `anyOf`, `allOf`, discriminator,
  recursive schema는 지원하지 않는다.
- typed output DTO, retry 실행, pagination 실행은 후속 P1 범위다.
- Maven, WebFlux, async, SSE transport, STDIO는 지원하지 않는다.
- `Windows validation host remains follow-up P1`; 현재 설치 배포본과 validation process 경계는
  macOS/Linux 로컬 host를 대상으로 한다.
- P0의 process isolation은 전용 임시 workspace, timeout, bounded output에 한정된다.
  OCI sandbox, dependency proxy, CPU/memory limit, network egress 통제는 제공하지 않는다.
