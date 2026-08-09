# OpenAPI MCP Generator

로컬 OpenAPI 3.0 명세에서 선택한 operation을 Java 21, Spring Boot 4.1.0,
Spring AI 2.0.0 기반 Streamable HTTP MCP 서버 프로젝트로 생성하는 CLI다.
생성된 프로젝트를 실제로 컴파일하고 Spring 애플리케이션을 기동한 뒤 MCP `initialize`,
`tools/list`, 대표 `tools/call`과 upstream HTTP binding을 검증한 경우에만 ZIP을 만든다.

## 요구 환경

- Java 21. 이 저장소의 `mise.toml`은 Java 21.0.2를 고정한다.
- Gradle Wrapper 9.6.1. 시스템 Gradle 설치는 필요하지 않다.
- 물리 경로와 안정적인 filesystem `fileKey`, hard link를 지원하는 로컬 파일시스템
- POSIX 환경에서는 생성된 `gradlew`에 owner execute 권한을 기록하고 검증할 수 있어야 한다.
- 최초 빌드와 생성 프로젝트 검증 시 Gradle 배포본과 Maven Central dependency를 받을 수 있어야 한다.

```bash
mise install
mise exec -- java -version
mise exec -- ./gradlew test --no-daemon --non-interactive
```

`/var`처럼 symbolic link를 거치는 경로나 stable file identity를 제공하지 않는 가상·공유
파일시스템은 안전 경계에서 거부될 수 있다. macOS 임시 경로는 `/private/tmp`처럼 물리
경로를 사용한다.

## 빌드, 테스트, 설치

전체 단위·통합 검증과 CLI distribution 설치는 다음 명령으로 실행한다.

```bash
mise exec -- ./gradlew clean test integrationTest :generator-cli:installDist \
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

## CLI 사용법

### 지원 profile 조회

```bash
"$OPENAPI_MCP" profiles
```

P0는 `spring-ai-2.0-java21-mvc-streamable` 하나만 제공한다. 대상 버전은 Java 21,
Spring Boot 4.1.0, Spring AI 2.0.0, Gradle 9.6.1로 고정된다.

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
  `parameters`를 선택적으로 지정한다. 최소 하나의 operation이 enabled여야 한다.
- parameter source는 `USER_INPUT` 또는 `SERVER_SECRET`만 지원한다.
  `SERVER_SECRET`에는 대문자로 시작하는 `environmentVariable`이 필요하고,
  `USER_INPUT`에는 environment variable을 지정할 수 없다. 생성 runtime과 JVM/Spring bootstrap이
  소유하는 `PROVIDER_BASE_URL`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`,
  `SPRING_APPLICATION_JSON`은 secret environment variable 이름으로 사용할 수 없다.
- 알 수 없는 필드, 중복 key, YAML anchor·alias, explicit tag, 잘못된 scalar type을
  거부한다. `enabled`만 boolean이며 나머지 scalar는 string이다. Tool description의
  literal·folded block scalar는 일반 문자열로 허용한다.
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

## 검증과 종료 코드

`validationLevel`은 P0에서 `MCP_PROTOCOL`만 허용한다. 생성 과정은 다음 실제 검증을
순서대로 수행한다.

1. `COMPILE`: 생성 Gradle Wrapper로 `classes test bootJar` 실행
2. `APPLICATION_CONTEXT`: executable JAR를 사용 가능한 loopback port에서 기동
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

- Gradle Kotlin DSL, Gradle Wrapper 9.6.1, Java 21 source와 generated test
- `src/main/resources/application.yml`, generated project `README.md`, `Dockerfile`, `.gitignore`
- 원본 명세 byte를 보존한 `openapi/source.<extension>`
- `GENERATION_MANIFEST.json`: generator/template/runtime 및 target 버전, 원본·source
  checksum, operation-to-Tool mapping
- `VALIDATION_REPORT.json`: 전체 상태, 순서가 고정된 검증 stage와 관측 Tool
- output 디렉터리와 같은 이름의 sibling ZIP

source checksum은 manifest, validation report, `process-logs/`를 제외한 source tree를
경로 순으로 정규화해 계산한다. ZIP entry는 경로 순서, timestamp, 파일 mode를
결정적으로 기록한다. validation duration은 실행마다 달라질 수 있으므로 ZIP 전체 byte는
달라질 수 있지만 source checksum과 validation report를 제외한 canonical source entry는
같아야 한다. validation은 별도 workspace에서 수행하므로 배포 디렉터리와 ZIP에는
`build/`, `.gradle/`, process log가 포함되지 않는다.

생성기는 기존 output을 삭제하거나 merge하지 않는다. 실패 산출물과 더 이상 필요하지
않은 임시 디렉터리의 보관·삭제는 사용자가 명시적으로 수행해야 한다.

## 현재 지원 범위

- 로컬 `.yaml`, `.yml`, `.json` OpenAPI 3.0.x
- `GET`, `POST`, `PUT`, `PATCH`, `DELETE` operation 선택
- path, query, header parameter와 JSON request body
- primitive, enum, array, object, non-recursive local `$ref`
- required/optional, min/max, length, pattern의 generated Jakarta Validation
- API Key query/header를 `SERVER_SECRET` 환경변수로 주입
- Java record input, Spring MVC Sync, Streamable HTTP, generic JSON output
- 실제 compile, generated test, ApplicationContext, MCP `initialize`, `tools/list`, 대표
  `tools/call`과 loopback mock upstream 검증

## 알려진 제한과 후속 P1 경계

- OpenAPI parameter와 JSON body property는 Java-safe MCP key로 변환하고 원본 upstream
  JSON 이름은 binding에 보존한다. 예를 들어 `postal-code`는 MCP의 `postalCode` 입력으로
  노출하지만 provider JSON에는 `postal-code`로 전송한다.
- min/max, length, pattern, format, enum 제약은 명시적 Tool schema로 생성해
  `tools/list`와 generated Jakarta Validation에 함께 유지한다.
- parameter 직렬화는 path/header의 기본 `simple` scalar, query의 기본 `form` scalar와
  `form` + `explode=true`인 scalar-item query array만 지원한다. 비기본 style, object
  parameter, path/header array, nested array/object item은 operation 생성에서 제외한다.
- enum은 `tools/list`, JSON body, path/query/header 직렬화에서 원본 OpenAPI wire 값
  (예: `full-detail`)을 일관되게 사용한다.
- remote `$ref`, URL import, OpenAPI 3.1, `oneOf`, `anyOf`, `allOf`, discriminator,
  recursive schema는 지원하지 않는다.
- typed output DTO, response envelope 정규화, HTTP 200 업무 오류 mapping, retry, pagination,
  metrics, tracing은 후속 P1 범위다.
- Maven, WebFlux, async, SSE transport, STDIO는 지원하지 않는다. Java 17과 Spring AI 1.x
  compatibility profile은 후속 P1 범위다.
- UI operation editor와 Windows validation host 지원은 후속 P1 범위다.
- P0의 process isolation은 전용 임시 workspace, timeout, bounded output에 한정된다.
  OCI sandbox, dependency proxy, CPU/memory limit, network egress 통제는 제공하지 않는다.
