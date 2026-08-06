# OpenAPI MCP Generator P0 설계

- 상태: 승인됨
- 기준 문서: `docs/prd.md` v0.1
- 작성일: 2026-08-07

## 1. 목표

로컬 OpenAPI 3.0 YAML 또는 JSON 문서를 분석하고, 선택한 operation을 Spring AI 2.0 기반 Remote MCP Tool로 변환한다. 생성 결과는 Java 21, Spring Boot 4.1.0, Spring AI 2.0.0, Spring MVC Sync, Streamable HTTP 조합으로 컴파일하고 실제 MCP `initialize`와 `tools/list`를 검증한 뒤 ZIP으로 패키징한다.

P0의 완료 기준은 다음과 같다.

1. CLI에서 profile 조회, 명세 분석, 프로젝트 생성을 실행할 수 있다.
2. OpenAPI operation과 schema를 버전 독립적인 Tool IR로 정규화한다.
3. API Key를 Tool 입력에서 제외하고 환경변수로 주입하는 코드를 생성한다.
4. 생성 프로젝트가 Gradle로 컴파일되고 Spring ApplicationContext가 기동된다.
5. 실제 Streamable HTTP endpoint에서 MCP `initialize`와 `tools/list`가 성공한다.
6. 검증된 프로젝트에 README, manifest, 검증 보고서와 ZIP을 제공한다.

## 2. P0 범위

### 2.1 포함

- OpenAPI 3.0.x 로컬 `.yaml`, `.yml`, `.json` 파일
- path, query, header parameter
- JSON request body와 JSON response
- primitive, enum, array, object, local `$ref`
- operation 선택과 Tool 이름·설명 override
- 이름과 OpenAPI security scheme 기반 secret 후보 탐지
- API Key query/header 환경변수 주입
- Java 21 record 기반 input DTO
- `GENERIC_JSON` 출력
- Spring AI 2.0 annotation 기반 Tool
- Gradle Kotlin DSL 및 Gradle Wrapper 9.6.1
- 컴파일, ApplicationContext, MCP `initialize`, `tools/list` 검증
- deterministic source checksum, manifest, validation report, ZIP

### 2.2 제외

- URL import와 SSRF 방어
- UI와 REST API
- Java 17과 Spring AI 1.x profile
- Maven, WebFlux, async, SSE, STDIO
- `tools/call`과 upstream mock 검증
- response envelope 정규화와 typed output DTO
- retry, pagination, metrics, tracing
- `oneOf`, `anyOf`, discriminator, recursive schema의 자동 변환
- OCI build sandbox와 network egress 제어
- 재생성 merge와 사용자 수정 코드 보존

## 3. 기술 기준

| 항목 | 고정 값 |
| --- | --- |
| Generator Java | 21 |
| Generated Java | 21 |
| Spring Boot | 4.1.0 |
| Spring AI | 2.0.0 |
| Gradle Wrapper | 9.6.1 |
| Build DSL | Gradle Kotlin DSL |
| Web stack | Spring MVC |
| Programming model | Sync |
| MCP transport | Streamable HTTP |
| MCP endpoint | `/mcp` |
| Generator version | `0.1.0` |
| Template version | `spring-ai-2-v1` |
| Runtime version | `0.1.0` |

Spring AI 2.0.x는 Spring Boot 4.0.x와 4.1.x를 지원한다. 생성 프로젝트는 `spring-ai-starter-mcp-server-webmvc`와 annotation scanner를 사용한다.

## 4. 접근 방식

### 4.1 선택: Gradle 멀티모듈 모듈러 모놀리스

하나의 CLI 프로세스 안에서 모듈 경계를 강제한다. OpenAPI 계층은 Spring AI를 알지 못하고, Spring AI emitter는 Tool IR만 소비한다. 실제 분산 worker가 필요해지기 전까지 queue, database, object storage를 도입하지 않는다.

### 4.2 제외한 대안

- 단일 Gradle 모듈: 초기 설정은 작지만 domain, parser, emitter, validator 경계가 쉽게 섞인다.
- API와 worker 선분리: 장기 아키텍처에는 맞지만 P0 검증 전에 운영 인프라와 비동기 상태 관리가 필요해진다.

### 4.3 전환 조건

독립 worker가 2개 이상 필요하거나 동시 generation job을 운영해야 할 때 `generator-core`의 port를 유지한 채 API와 worker adapter를 별도 프로세스로 분리한다.

## 5. 모듈 구조

```text
openapi-mcp-generator
├── generator-domain
├── generator-openapi
├── generator-policy
├── generator-core
├── generator-spring-ai-2
├── generator-validation
└── generator-cli
```

### 5.1 `generator-domain`

외부 프레임워크 의존성이 없는 record, enum, port를 보관한다.

- `ApiOperation`, `ApiParameter`, `ApiSchema`, `ApiSecurityScheme`
- `McpToolDefinition`, `McpInputDefinition`, `HttpExecutionDefinition`
- `TargetPlatform`, `CompatibilityProfile`
- `ParameterSource`, `ValidationLevel`, `GenerationStage`, `ValidationStatus`
- `ProjectGenerator`, `GeneratedProjectValidator` port
- `GeneratorErrorCode`, `GeneratorException`

### 5.2 `generator-openapi`

Swagger Parser v3를 경계 안에서 사용한다. 파서 모델을 외부로 노출하지 않고 domain model로 변환한다.

- 입력 크기와 확장자 확인
- 원본 byte의 SHA-256 계산
- OpenAPI 3.0 version 확인
- `$ref` resolution
- operationId 중복 확인
- schema와 operation 정규화
- 미지원 schema 경고 생성

### 5.3 `generator-policy`

- `{provider}_{domain}_{operation}` Tool 이름 생성
- snake_case, 길이, 중복, Java identifier 검증
- Tool description 구성과 override 적용
- 이름과 security scheme 기반 parameter 분류
- secret 노출 검출
- OpenAPI operation에서 Tool IR 생성

### 5.4 `generator-core`

pipeline stage를 순서대로 실행하고 stage result를 수집한다. 임시 workspace, source tree checksum, manifest, 검증 보고서, ZIP 생성을 담당한다.

### 5.5 `generator-spring-ai-2`

Spring AI 2.0 profile 전용 emitter다. 조건문으로 다른 Spring AI 버전을 처리하지 않는다.

- Gradle build와 wrapper 설정
- Spring Boot application
- `@McpTool` Tool class
- input record와 enum
- REST execution metadata
- bundled runtime source
- `application.yml`, README, Dockerfile, `.gitignore`
- 생성 프로젝트 unit test

### 5.6 `generator-validation`

외부 프로세스를 인자 배열로 실행한다.

- `./gradlew classes test` 컴파일·테스트
- 생성 애플리케이션을 사용 가능한 loopback port에서 기동
- readiness 확인
- MCP JSON-RPC `initialize`, `notifications/initialized`, `tools/list`
- timeout, stdout/stderr 제한, 종료 처리

### 5.7 `generator-cli`

표준 Java argument parsing만 사용한다. `profiles`, `inspect`, `generate` command와 종료 코드를 제공하고 다른 모듈을 조립한다.

## 6. 의존성

직접 추가하는 외부 라이브러리는 다음으로 제한한다.

- Swagger Parser v3 `2.1.40`: OpenAPI parsing과 reference resolution
- Jackson Databind와 YAML: 입력 설정, manifest, report 직렬화
- JUnit 5: unit, golden, integration test

Picocli, template engine, Java source generation library는 추가하지 않는다. emitter는 작은 deterministic renderer와 중앙 escape/identifier validator를 사용한다. 생성 프로젝트에는 Spring Boot와 Spring AI 공식 dependency만 둔다.

## 7. CLI 계약

```text
openapi-mcp profiles
openapi-mcp inspect --spec <file> --output <analysis.json>
openapi-mcp generate --spec <file> --config <generation.yaml> --output <directory>
```

### 7.1 분석 결과

`inspect` 결과에는 checksum, OpenAPI version, operation 목록, 지원 여부, 입력·출력 복잡도, security parameter 후보, 경고와 미지원 기능을 기록한다.

### 7.2 생성 설정

```yaml
project:
  groupId: com.example
  artifactId: weather-mcp-server
  packageName: com.example.weather
provider: kma
domain: weather
targetProfileId: spring-ai-2.0-java21-mvc-streamable
validationLevel: MCP_PROTOCOL
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

operation은 설정에 명시적으로 포함된 경우에만 생성한다. `enabled: false`는 제외한다. override가 없으면 정책의 deterministic 기본값을 사용한다.

### 7.3 종료 코드

| 코드 | 의미 |
| --- | --- |
| 0 | 성공 |
| 2 | CLI 또는 사용자 설정 오류 |
| 3 | OpenAPI parsing 또는 semantic validation 실패 |
| 4 | source generation 실패 |
| 5 | compile 또는 MCP validation 실패 |
| 6 | packaging 실패 |

## 8. 생성 파이프라인

```text
SOURCE_LOAD
→ OPENAPI_PARSE
→ SEMANTIC_VALIDATE
→ OPERATION_ANALYZE
→ TOOL_MODEL_BUILD
→ TOOL_MODEL_VALIDATE
→ TARGET_VALIDATE
→ SOURCE_GENERATE
→ COMPILE
→ APPLICATION_CONTEXT
→ MCP_INITIALIZE
→ MCP_TOOLS_LIST
→ REPORT
→ PACKAGE
```

각 stage는 immutable 입력과 출력 또는 구조화된 실패를 반환한다. 실패한 stage 이후의 검증 stage는 실행하지 않는다.

## 9. Tool 변환

### 9.1 이름

기본 이름은 `{provider}_{domain}_{operation}`이며 snake_case로 정규화한다. 최대 길이는 64자다. 중복은 숫자를 자동 부여하지 않고 생성 전 오류로 처리해 mapping이 명시적으로 유지되도록 한다.

### 9.2 입력

- path, query, header parameter와 JSON body property를 평탄한 MCP Tool argument로 노출한다.
- required 여부와 type, format, enum, min/max, length, pattern을 가능한 범위에서 보존한다.
- Java Tool method는 Tool argument를 받아 input record를 만들고 runtime에 전달한다.
- nested object는 별도 record로 생성한다.
- 충돌하는 JSON property는 `@JsonProperty`로 원본 이름을 보존한다.

### 9.3 secret

OpenAPI API Key security scheme과 `apiKey`, `api_key`, `serviceKey`, `accessToken`, `clientSecret`, `authorization`, `x-api-key` 이름을 후보로 탐지한다. `SERVER_SECRET`으로 확정된 값은 Tool schema와 input record에서 제외하고 환경변수로만 읽는다.

### 9.4 REST runtime

생성 프로젝트 안에 안정된 runtime package를 source로 포함하되 Tool/DTO 생성 영역과 분리한다. runtime은 Spring `RestClient`를 사용해 path, query, header, JSON body를 binding하고 secret을 주입한다. timeout과 최대 response byte를 적용하며 반환값은 Jackson `JsonNode`로 정규화한다.

## 10. 생성 프로젝트 구조

```text
project-root
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/wrapper
├── Dockerfile
├── .gitignore
├── README.md
├── GENERATION_MANIFEST.json
├── VALIDATION_REPORT.json
├── openapi/source.<original-extension>
└── src
    ├── main
    │   ├── java/<package>/application
    │   ├── java/<package>/generated/tool
    │   ├── java/<package>/generated/model
    │   ├── java/<package>/generated/metadata
    │   ├── java/<package>/runtime
    │   └── resources/application.yml
    └── test/java
```

P0는 새 디렉터리에만 생성하며 기존 디렉터리를 덮어쓰지 않는다.

## 11. 재현성

- 원본 명세 checksum은 입력 byte의 SHA-256이다.
- source checksum은 파일 경로 정렬, UTF-8, LF line ending을 적용한 생성 source tree의 SHA-256이다.
- self-reference와 실행 환경 차이를 피하기 위해 source checksum 계산에서 `GENERATION_MANIFEST.json`, `VALIDATION_REPORT.json`, ZIP과 프로세스 로그를 제외한다.
- ZIP entry 순서와 timestamp를 고정한다.
- dependency에 dynamic version과 `latest`를 사용하지 않는다.
- manifest에는 generator, template, runtime, Spring AI, Spring Boot, Java, Gradle 버전과 두 checksum을 기록한다.

## 12. 오류 처리

`GeneratorException`은 `code`, `stage`, 사용자 안전 메시지, 내부 cause를 가진다. 보고서에는 cause 전체 stack trace 대신 secret이 제거된 요약만 기록한다.

- parsing 또는 semantic 실패: source generation을 실행하지 않는다.
- generation 실패: 부분 ZIP을 만들지 않는다.
- compile 또는 MCP 실패: ZIP을 만들지 않고 생성 디렉터리와 `UNVERIFIED` 보고서를 보존한다.
- 성공: `VALIDATED` 보고서와 ZIP을 생성한다.
- 프로세스 timeout: 하위 프로세스를 종료하고 `COMPILE_TIMEOUT` 또는 해당 MCP stage 오류를 기록한다.

## 13. 보안

- 명세 기본 크기 제한은 10MiB이며 CLI에서 더 작은 값으로 조정할 수 있다.
- 입력 확장자와 실제 JSON/YAML 구조를 함께 검증한다.
- groupId, artifactId, packageName, Tool name과 환경변수 이름은 allowlist 정규식으로 검증한다.
- 모든 출력 경로를 normalize한 뒤 지정 output root 내부인지 확인한다.
- 명세의 문자열을 build script나 shell command로 실행 가능한 위치에 삽입하지 않는다.
- 하위 프로세스는 shell 없이 `ProcessBuilder(List<String>)`로 실행한다.
- Authorization, API Key, serviceKey, clientSecret, cookie와 사용자 지정 secret을 로그와 보고서에서 마스킹한다.
- 실제 secret 값은 configuration, generated source, manifest, report, ZIP에 저장하지 않는다.

P0의 build isolation은 전용 임시 디렉터리, process timeout, output 제한으로 한정한다. OCI sandbox, CPU/memory limit, dependency proxy와 egress restriction은 운영 worker 단계에서 추가한다.

## 14. 검증 전략

### 14.1 TDD 단위 검증

- primitive, enum, array, object, `$ref` mapping
- operationId 중복과 미지원 schema 처리
- Tool naming과 duplicate detection
- secret candidate와 security scheme 분류
- compatibility profile 검증
- path/query/header/body metadata binding
- identifier validation, escaping, path containment, masking

각 production behavior는 먼저 실패하는 테스트로 확인한 뒤 최소 구현을 추가한다.

### 14.2 Golden 검증

hand-authored OpenAPI fixture에서 생성되는 파일 목록과 핵심 파일 내용을 snapshot으로 비교한다. query, path, JSON body, enum, nested object, API Key query/header fixture를 포함한다.

### 14.3 실제 통합 검증

1. 생성 프로젝트에서 `./gradlew classes test --no-daemon --non-interactive`를 실행한다.
2. 생성된 executable jar를 loopback의 사용 가능한 port로 기동한다.
3. loopback port가 연결을 수락하는지 제한 시간 동안 확인한다.
4. `/mcp`에 MCP `initialize`를 전송한다.
5. `notifications/initialized`를 전송한다.
6. `tools/list`를 전송하고 선택 operation의 이름, description, input schema를 검증한다.
7. 프로세스를 정상 종료하고 필요하면 강제 종료한다.

## 15. 복잡도

OpenAPI node 수를 `N`, `$ref` edge 수를 `R`, 선택 operation 수를 `O`, 생성 파일 수를 `F`라 하면 parsing과 normalization은 `O(N + R)`, Tool IR 생성은 `O(N)`, source emission과 checksum은 `O(F + N)`이다. 메모리는 정규화 모델과 생성 파일 내용 때문에 `O(N + F)`다. 전체 wall-clock 시간은 dependency resolution, Java compilation, Spring startup이 지배한다.

## 16. 주의사항과 후속 경계

- 제약: P0 profile은 Spring AI 2.0.0, Spring Boot 4.1.0, Java 21 조합 하나다.
- 위험: 최초 Gradle 실행은 Maven Central과 Gradle distribution 다운로드 상태에 영향을 받는다.
- 위험: 로컬 process isolation은 악성 build를 방어하는 운영 sandbox와 동일하지 않다.
- 예외: 의미를 안전하게 보존할 수 없는 schema가 포함된 operation은 경고 후 제외한다.
- 예외: HTTP 200 업무 오류, envelope normalization, retry와 `tools/call` 검증은 P1 범위다.

## 17. 공식 참고 자료

- Spring AI 2.0 호환 범위: <https://docs.spring.io/spring-ai/reference/getting-started.html>
- Spring AI MCP annotations: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html>
- Spring AI WebMVC MCP starter: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html>
- Spring Boot Gradle plugin: <https://docs.spring.io/spring-boot/gradle-plugin/>
- Gradle Wrapper와 9.6.1: <https://docs.gradle.org/current/userguide/installation.html>
