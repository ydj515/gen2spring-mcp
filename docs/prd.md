# OpenAPI to Spring AI MCP Server Generator PRD

- 문서 버전: v0.1
- 작성일: 2026-08-07
- 문서 성격: 초기 제품 요구사항과 단계별 구현 이력
- 제품명: OpenAPI MCP Server Generator
- 대상 독자: Product Owner, Backend Engineer, Platform Engineer, DevOps Engineer, QA Engineer

> 현재 구조·사용법·지원 범위는 [문서 안내](README.md)와 [사용자 가이드](user-guide.md)를 기준으로 한다.
> 아래 MVP/P0/P1 비목표와 완료 표시는 단계별 기록이며 이후 확장으로 대체된 항목이 있다.
> 현재 결정과 변경 이유는 [설계와 의사결정](design-decisions.md)에서 확인한다.

---

## 1. 제품 개요

### 1.1 제품 한 줄 설명

OpenAPI 또는 Swagger 명세를 입력받아 사용자가 선택한 Java, Spring Boot, Spring AI 버전에 맞는 Remote MCP Server 프로젝트를 자동 생성하고, 컴파일 및 MCP 프로토콜 검증까지 수행하는 코드 생성 플랫폼이다.

### 1.2 배경

공공기관 및 외부 서비스는 대부분 REST API와 OpenAPI 명세를 보유하고 있지만, LLM 또는 AI Agent가 해당 API를 MCP Tool로 사용하려면 별도의 MCP Server를 개발해야 한다.

기관별로 MCP Server를 개별 개발하면 다음 문제가 발생한다.

- OpenAPI parameter와 MCP Tool input schema 간 변환 로직을 반복 구현해야 한다.
- API Key, OAuth, 기관별 인증 방식이 MCP Tool 코드에 중복된다.
- Spring AI 및 MCP Java SDK 버전에 따라 API와 설정 방식이 달라진다.
- Streamable HTTP, SSE, STDIO 등 transport별 설정이 달라진다.
- Tool description, input schema, response normalization 품질이 개발자 역량에 따라 달라진다.
- Spring AI 업그레이드 시 생성된 프로젝트마다 대응해야 한다.
- 수십 개 기관, 수백 개 API를 수작업으로 MCP Server화하기 어렵다.
- 파일 생성 성공만으로는 실제 MCP Client와의 호환성을 보장할 수 없다.

이 제품은 OpenAPI 명세를 버전 독립적인 중간 모델로 변환하고, 선택된 Target Platform에 맞는 프로젝트를 생성함으로써 이러한 반복 작업을 자동화한다.

---

## 2. 제품 비전

### 2.1 비전

OpenAPI 명세만 있으면 누구나 검증 가능한 Spring AI 기반 MCP Server를 생성할 수 있는 표준 도구를 제공한다.

### 2.2 장기 목표

장기적으로 동일한 OpenAPI-to-MCP 중간 모델을 기반으로 다음 산출물을 모두 지원한다.

1. 독립 실행형 Spring AI MCP Server 프로젝트
2. Managed MCP Runtime 등록용 Tool Metadata
3. MCP Gateway용 Tool Catalog
4. 기관별 Tool 정책 및 인증 설정
5. Java 외 Kotlin 및 기타 MCP SDK 기반 서버
6. OpenAPI 변경 감지 및 MCP Server 재생성
7. 기존 생성 프로젝트의 버전 업그레이드 및 마이그레이션

---

## 3. 문제 정의

### 3.1 핵심 문제

OpenAPI 문서는 HTTP API 계약을 표현하지만, MCP Tool로 직접 사용하기에는 다음 정보가 부족하거나 구조가 적합하지 않다.

- LLM이 Tool을 선택하기에 충분한 자연어 설명
- 사용자 입력과 시스템 입력의 구분
- API Key, 인증 토큰 등 비공개 입력의 제외
- Tool name 충돌 방지 규칙
- MCP input schema에 적합한 type, required, enum, constraint 변환
- 공공 API 특유의 응답 envelope 제거
- HTTP 200 안에 포함된 업무 오류 처리
- 결과 크기 제한 및 pagination 처리
- REST 오류를 MCP 표준 오류로 변환하는 규칙
- MCP transport 및 Spring AI 버전별 구현 차이

### 3.2 사용자가 현재 겪는 문제

사용자는 다음 작업을 수작업으로 수행해야 한다.

1. Swagger 문서를 분석한다.
2. operation별 MCP Tool을 설계한다.
3. Tool input DTO와 schema를 작성한다.
4. REST Client 코드를 작성한다.
5. 인증 정보를 주입한다.
6. 응답 구조를 파싱하고 정규화한다.
7. Spring AI MCP Server 설정을 작성한다.
8. MCP Tool을 등록한다.
9. 서버를 빌드한다.
10. MCP Client와 `initialize`, `tools/list`, `tools/call`을 테스트한다.
11. 버전 변경 시 코드를 수정한다.

### 3.3 해결해야 할 핵심 질문

- 어떤 Spring AI, Spring Boot, Java 조합이 실제로 호환되는가?
- OpenAPI operation 하나를 MCP Tool 하나로 자동 변환할 수 있는가?
- 어떤 parameter를 사용자 입력에서 제외해야 하는가?
- Spring AI 1.x와 2.x의 코드 차이를 어떻게 격리할 것인가?
- 생성된 코드가 실제로 컴파일되고 MCP Client와 호환되는지 어떻게 보장할 것인가?
- 생성 프로젝트와 Managed Runtime을 동일한 모델로 지원할 수 있는가?

---

## 4. 목표와 비목표

## 4.1 목표

### G1. OpenAPI 기반 프로젝트 자동 생성

OpenAPI 3.0.x 및 bounded OpenAPI 3.1.x 명세를 입력받아 실행 가능한 Spring AI Remote MCP Server
프로젝트를 생성한다.

### G2. Target Platform 선택

사용자는 다음 항목을 선택할 수 있다.

- Java 버전
- Spring Boot 버전
- Spring AI 버전
- Build Tool
- MCP Transport
- Programming Model
- Web Stack

### G3. 버전 호환성 보장

지원되지 않는 Java, Spring Boot, Spring AI 조합은 선택 단계 또는 생성 단계에서 차단한다.

### G4. MCP Tool 자동 생성

OpenAPI operation으로부터 다음 항목을 생성한다.

- Tool name
- Tool description
- Input DTO
- Input JSON Schema
- Output DTO 또는 표준 결과 타입
- REST 호출 metadata
- 인증 parameter mapping
- 오류 변환 규칙

### G5. 생성 결과 검증

생성된 프로젝트에 대해 다음 검증을 수행한다.

- 소스 코드 생성 검증
- Gradle 또는 Maven 컴파일
- Spring ApplicationContext 기동
- MCP initialize
- MCP tools/list
- 대표 tools/call 또는 mock upstream call

### G6. 재현 가능한 생성

같은 입력 명세와 같은 Generator Version, Target Profile을 사용하면 동일한 프로젝트를 생성해야 한다.

### G7. 확장 가능한 아키텍처

Spring AI 버전, Java 버전, transport, build tool을 추가할 때 OpenAPI parsing core를 수정하지 않는 구조를 제공한다.

---

## 4.2 비목표

MVP에서는 다음 항목을 제공하지 않는다.

- 모든 OpenAPI operation에 대한 완전 자동 비즈니스 의미 해석
- 여러 REST operation을 하나의 복합 MCP Tool로 자동 조합
- 생성된 프로젝트의 자동 클라우드 배포
- 기관별 네트워크 방화벽 자동 구성
- LLM을 이용한 Tool description 자동 생성 품질 보장
- 모든 Spring AI patch/minor 버전 지원
- OpenAPI 2.0 Swagger 문서의 완전 지원
- GraphQL, gRPC, SOAP 자동 변환
- Kotlin 소스 코드 생성
- Reactive programming model의 완전 지원
- OAuth authorization server 자동 구축
- 사용자 정의 비즈니스 로직 자동 생성

---

## 5. 대상 사용자

### 5.1 API 제공 기관 개발자

#### 목적

기존 REST API를 MCP Tool로 제공하고 싶다.

#### 기대

- OpenAPI 문서를 업로드하고 서버 프로젝트를 받는다.
- 인증 정보는 코드에 포함하지 않고 환경변수로 관리한다.
- 생성된 프로젝트를 기관 환경에 직접 배포한다.

### 5.2 플랫폼 엔지니어

#### 목적

여러 기관의 OpenAPI를 표준 MCP Server로 변환하고 중앙 관리하고 싶다.

#### 기대

- 동일한 코드 구조와 표준 runtime을 사용한다.
- Tool name, logging, metrics, error handling 정책이 통일된다.
- 버전별 생성 결과를 재현할 수 있다.

### 5.3 백엔드 개발자

#### 목적

MCP Server의 초기 boilerplate 개발 시간을 줄이고 싶다.

#### 기대

- 생성 코드를 수정하고 확장할 수 있다.
- DTO와 REST Client가 명확하게 분리된다.
- 컴파일 가능한 프로젝트가 제공된다.

### 5.4 AI Agent 개발자

#### 목적

GPT, Claude, Gemini 등에서 사용할 수 있는 MCP Tool을 빠르게 제공하고 싶다.

#### 기대

- Tool description과 schema가 LLM이 이해하기 좋은 형태다.
- Streamable HTTP endpoint가 즉시 실행 가능하다.
- `tools/list`와 `tools/call`이 정상 동작한다.

---

## 6. 핵심 사용자 시나리오

### 6.1 기본 프로젝트 생성

1. 사용자가 OpenAPI YAML 또는 JSON 파일을 업로드한다.
2. 시스템이 문서를 파싱하고 오류를 검사한다.
3. 시스템이 API operation 목록을 표시한다.
4. 사용자가 MCP Tool로 노출할 operation을 선택한다.
5. 사용자가 Spring AI, Spring Boot, Java 버전을 선택한다.
6. 사용자가 transport와 build tool을 선택한다.
7. 시스템이 호환성을 검증한다.
8. 시스템이 Tool name, description, input schema preview를 표시한다.
9. 사용자가 생성 요청을 제출한다.
10. 시스템이 프로젝트를 생성한다.
11. 시스템이 컴파일 및 MCP 프로토콜 테스트를 수행한다.
12. 사용자가 ZIP 파일과 검증 보고서를 다운로드한다.

### 6.2 인증 parameter 제외

1. OpenAPI에 `serviceKey` query parameter가 포함되어 있다.
2. 시스템이 이름, security scheme, extension 정보를 기반으로 비공개 parameter 후보로 분류한다.
3. 사용자가 해당 parameter를 `SERVER_SECRET`으로 확정한다.
4. 생성된 MCP Tool input에서는 `serviceKey`가 제외된다.
5. 생성된 `application.yml`에는 환경변수 placeholder가 추가된다.
6. runtime은 REST 호출 시 secret 값을 query parameter 또는 header에 주입한다.

### 6.3 버전 비호환 처리

1. 사용자가 Spring AI 2.x를 선택한다.
2. 사용자가 지원되지 않는 Spring Boot 또는 Java 버전을 선택한다.
3. 시스템은 해당 조합을 생성하지 않는다.
4. 시스템은 호환 가능한 대안을 표시한다.
5. 사용자는 유효한 조합만 선택할 수 있다.

### 6.4 생성 실패 분석

1. 생성된 코드의 컴파일이 실패한다.
2. 시스템은 생성 작업을 실패 상태로 기록한다.
3. 사용자는 오류 단계, 파일, line, dependency 정보를 확인한다.
4. ZIP 다운로드는 비활성화되거나 `UNVERIFIED` 표시가 붙는다.
5. 재생성에 필요한 원인과 권장 조치가 제공된다.

---

## 7. 제품 범위

## 7.1 MVP 지원 범위

### OpenAPI

- OpenAPI 3.0.x
- OpenAPI 3.1.x 기본 dialect와 bounded `null` union
- YAML
- JSON
- Local file upload
- URL import
- Path, query, header parameter
- Request body
- JSON response
- Basic schema reference resolution

### Java

- Java 17
- Java 21

### Spring AI

- 검증된 Spring AI 1.x 대표 버전 1개 이상
- 검증된 Spring AI 2.x 대표 버전 1개 이상

### Spring Boot

- 각 Spring AI Target Profile과 호환되는 버전만 제공

### Build Tool

- Gradle Kotlin DSL

### MCP Transport

- Streamable HTTP
- 필요 시 Spring AI 1.x 호환 transport 제공

### Web Stack

- Spring MVC

### Programming Model

- Sync

### Generated Artifacts

- Spring Boot application
- MCP Tool classes
- Input DTO
- Output DTO 또는 generic result
- REST execution metadata
- Runtime dependency
- application.yml
- Gradle files
- Dockerfile
- README
- Unit test
- MCP smoke test
- Generation manifest
- Runtime metadata (`RUNTIME_METADATA.json`)
- Validation report

---

## 7.2 Phase 2 상태

완료된 수직 슬라이스:

- bounded OpenAPI 3.1 입력과 schema 정규화
- final Tool IR 기반 deterministic Managed Runtime metadata output
- 검증된 hosted generation의 immutable PostgreSQL Tool Catalog 게시와 owner-scoped 조회 API

남은 범위:

- Maven
- WebFlux
- Async MCP Server
- STDIO
- Stateless Streamable HTTP
- OpenAPI 2.0 변환
- Kotlin 생성
- OAuth2 client credentials
- API 변경 diff
- Tool description AI enhancement
- 동적 Managed Runtime `tools/list`·`tools/call`
- Gateway policy, sharing, authorization, credential routing, audit execution

---

## 8. 기능 요구사항

# FR-1. OpenAPI 입력

## FR-1.1 파일 업로드

시스템은 `.yaml`, `.yml`, `.json` 형식의 OpenAPI 문서를 입력받아야 한다.

### 수용 기준

- 최대 파일 크기를 설정할 수 있어야 한다.
- 지원하지 않는 파일 형식은 거부해야 한다.
- 파싱 오류는 line과 column 정보를 포함해야 한다.
- 원본 명세의 checksum을 저장해야 한다.

## FR-1.2 URL 입력

시스템은 HTTP 또는 HTTPS URL에서 OpenAPI 문서를 가져올 수 있어야 한다.

### 보안 요구사항

- Private IP, loopback, link-local address 접근을 차단해야 한다.
- redirect 횟수를 제한해야 한다.
- response size를 제한해야 한다.
- content type과 실제 내용을 검증해야 한다.
- allowlist 정책을 선택적으로 지원해야 한다.
- DNS rebinding을 방어해야 한다.

## FR-1.3 문서 검증

시스템은 다음 항목을 검증해야 한다.

- OpenAPI version
- paths 존재 여부
- operation 정의
- operationId 중복
- schema reference 순환
- unsupported media type
- unresolved `$ref`
- security scheme 정의
- request/response schema 유효성

---

# FR-2. OpenAPI 정규화

## FR-2.1 Operation 정규화

각 operation을 버전 독립적인 `ApiOperation` 모델로 변환해야 한다.

```text
ApiOperation
- operationId
- method
- path
- tags
- summary
- description
- parameters
- requestBody
- responses
- securityRequirements
- vendorExtensions
```

## FR-2.2 Schema 정규화

다음 OpenAPI schema를 내부 타입으로 변환해야 한다.

- string
- integer
- number
- boolean
- array
- object
- enum
- date
- date-time
- binary
- nullable
- required
- minimum
- maximum
- minLength
- maxLength
- pattern
- minItems
- maxItems
- uniqueItems
- default
- example

bounded schema 구현 상태: 완료. optional nullable query/header, required·optional nullable root body,
`maxItems <= 256`인 array, bounded structural `uniqueItems`, compatible object `allOf`, branch 8개 이하의
`oneOf`·`anyOf`·multi-type union, semantic OpenAPI 3.1 `$ref` sibling을 canonical Tool schema로 정규화한다.

## FR-2.3 미지원 schema 처리

지원 경계 안의 `allOf`는 compatible object schema로 flatten하고, `oneOf`·`anyOf`·multi-type union은 generic
JSON value schema로 보존한다. 다음 항목은 의미를 임의로 변경하지 않고 operation을 명시적인 이유와 함께
지원 불가로 처리한다.

- nullable path parameter
- required nullable query/header parameter
- `maxItems`가 없거나 256을 초과한 `uniqueItems` array
- conflicting 또는 empty `allOf`
- branch 8개, 깊이 16, 전체 branch 64의 budget을 초과한 composition
- remote `$ref`, custom JSON Schema dialect, discriminator, recursive schema

시스템은 임의로 의미를 변경해서는 안 된다.

### GitHub issue #12 처리 기준

nullable parameter/root body, bounded array constraint, bounded composition·multi-type, OpenAPI 3.1 `$ref` sibling은
완료로 분류한다. nullable path, required nullable query/header, unbounded uniqueness, conflicting·empty·budget
overflow composition, recursion과 discriminator는 의도적으로 유지한 fail-closed 경계로 분류한다. issue의
체크리스트와 종료 코멘트는 이 구분을 그대로 사용하며, 미지원 경계를 완료 기능으로 표시하지 않는다.

---

# FR-3. Operation 선택

## FR-3.1 Tool 후보 표시

시스템은 모든 operation을 MCP Tool 후보로 표시해야 한다.

표시 정보:

- HTTP Method
- Path
- operationId
- summary
- tags
- security
- input complexity
- output complexity
- 지원 가능 여부
- 경고 목록

## FR-3.2 선택 및 제외

사용자는 operation별로 다음 값을 설정할 수 있어야 한다.

- 포함
- 제외
- 사용자 검토 필요
- Tool name override
- Tool description override
- response normalization override

---

# FR-4. MCP Tool 변환

## FR-4.1 Tool Name 생성

기본 Tool name은 다음 규칙으로 생성한다.

```text
{provider}_{domain}_{operation}
```

예:

```text
kma_weather_get_forecast
molit_bus_get_arrival
kostat_population_search
```

### 요구사항

- snake_case를 기본으로 사용한다.
- 서버 내부에서 중복되지 않아야 한다.
- Gateway 통합 시 충돌 가능성을 최소화해야 한다.
- 최대 길이 제한을 적용할 수 있어야 한다.
- 사용자가 override할 수 있어야 한다.
- 원본 operationId와 mapping 정보를 manifest에 남겨야 한다.

## FR-4.2 Tool Description 생성

다음 정보를 조합해 description을 생성한다.

- summary
- description
- tag
- request 목적
- response 의미
- 사용 시점
- 제한 사항
- 날짜 및 좌표 형식
- pagination 여부

### 품질 기준

Tool description은 다음 질문에 답해야 한다.

- 이 Tool은 무엇을 하는가?
- 언제 사용해야 하는가?
- 어떤 입력이 필요한가?
- 비슷한 Tool과 무엇이 다른가?
- 반환 결과는 무엇을 의미하는가?

## FR-4.3 Parameter 분류

각 parameter는 다음 중 하나로 분류해야 한다.

```text
USER_INPUT
SERVER_SECRET
SERVER_DEFAULT
CONTEXT_DERIVED
INTERNAL
UNSUPPORTED
```

### 기본 탐지 후보

다음 이름은 `SERVER_SECRET` 후보로 표시한다.

- apiKey
- api_key
- serviceKey
- accessToken
- clientSecret
- authorization
- x-api-key

자동 탐지 결과는 사용자에게 표시되어야 하며, 보안 parameter가 사용자 입력으로 노출되는 경우 경고해야 한다.

## FR-4.4 Input DTO 생성

operation별 input DTO를 생성해야 한다.

기본 규칙:

- Java 17 이상에서는 record 사용
- required field는 nullable하지 않게 표현
- optional field는 nullable 또는 Optional 정책에 따라 생성
- enum은 Java enum으로 생성 가능
- nested request body는 별도 record로 생성
- Bean Validation annotation 생성
- JSON property name 보존
- description은 annotation 또는 schema metadata에 반영

예시:

```java
public record GetForecastInput(
    @NotNull Integer nx,
    @NotNull Integer ny,
    @Pattern(regexp = "\\d{8}") String baseDate
) {
}
```

## FR-4.5 Input Schema 생성

Spring AI 및 MCP SDK에서 사용하는 JSON Schema가 OpenAPI 제약을 최대한 보존해야 한다.

검증 항목:

- required
- type
- format
- enum
- minimum
- maximum
- minLength
- maxLength
- pattern
- array items
- nested object

## FR-4.6 Output 생성

FR-4.6 구현 상태: 완료 (P1 지원 범위)

현재 구현은 기존 호환 기본값인 `GENERIC_JSON`과 operation별 opt-in `TYPED_DTO`를 지원한다. `TYPED_DTO`는
하나의 structurally consistent `application/json` object success schema에서 Java record를 생성한다. ambiguous
success response, composed/recursive success response schema, unsupported media type은 source 생성 전에
fail-closed로 거부한다.
response normalization은 두 output mode와 독립적으로 적용된다.

출력 전략은 operation별로 선택할 수 있어야 한다.

```text
TYPED_DTO
NORMALIZED_DTO
GENERIC_JSON
TEXT
```

P1의 실제 선택 규칙은 다음과 같다.

- output 설정 생략: `GENERIC_JSON`
- supported JSON object에 `output.mode: TYPED`: `TYPED_DTO`
- 깊은 공공 API envelope: `responseNormalization`과 `GENERIC_JSON` 또는 `TYPED_DTO` 조합
- `NORMALIZED_DTO`와 `TEXT` 독립 mode는 현재 지원 범위 밖이다.

---

# FR-5. REST 실행 메타데이터

각 Tool은 직접 작성된 HTTP 호출 코드 대신 또는 함께 실행 메타데이터를 생성할 수 있어야 한다.

```yaml
operation:
  id: kma.weather.getForecast
  method: GET
  base-url: ${provider.base-url}
  path: /forecast
  parameters:
    - source: input.nx
      target: query.nx
    - source: input.ny
      target: query.ny
    - source: secret.api-key
      target: query.serviceKey
```

## FR-5.1 Parameter Binding

지원 위치:

- path
- query
- header
- cookie
- JSON body
- form body

## FR-5.2 Timeout

operation별 또는 provider별로 설정 가능해야 한다.

- connect timeout
- response timeout
- total timeout

## FR-5.3 Retry

FR-5.3 구현 상태: 완료 (P1 지원 범위)

기본값은 비활성화한다.

활성화 시 다음 조건을 설정할 수 있어야 한다.

- 대상 status
- network exception
- 최대 retry 횟수
- backoff
- idempotent method만 허용
- Retry-After 존중

현재 구현은 GET operation만 허용하고 `maxRetries` 1..3, initial backoff 1..5000ms, max backoff
initial 이상 10000ms 이하로 제한한다. retry status는 400..599에서 최대 16개이며 network error 여부를
별도로 설정한다. delta-seconds `Retry-After`와 exponential backoff 중 큰 값을 사용하되 max backoff와
operation total timeout을 넘지 않는다. retry가 없는 기존 operation은 정확히 한 번만 요청한다.

## FR-5.4 Response Size Limit

FR-5.4 구현 상태: 완료 (P1 지원 범위)

대용량 응답으로 MCP Server 또는 LLM context가 과도하게 사용되지 않도록 응답 크기 제한을 제공해야 한다.

- byte limit
- item count limit
- truncation 여부
- pagination 안내
- 원본 응답 저장 금지 또는 제한

현재 생성 runtime은 각 provider response와 pagination aggregate JSON을 각각 1 MiB로 제한한다. pagination은
GET query string/integer cursor, RFC 6901 items/next pointer, `maxPages` 2..20, `maxItems` 1..2000을 요구한다.
page/aggregate 한계를 넘거나 cursor가 반복되면 partial result나 truncation 없이 `LOCAL_RESOURCE` 또는
`UPSTREAM_PROTOCOL`로 fail-closed하며 원본 provider response를 artifact나 log에 저장하지 않는다.

---

# FR-6. 인증 및 Secret 처리

## FR-6.1 지원 방식

MVP에서 다음 방식을 지원한다.

- API Key query
- API Key header
- Bearer token static injection
- Basic auth

Phase 2:

- OAuth2 client credentials
- 기관별 custom signer
- mTLS

## FR-6.2 코드 비포함 원칙

생성 결과에 실제 secret 값을 포함해서는 안 된다.

예:

```yaml
provider:
  api-key: ${PROVIDER_API_KEY}
```

## FR-6.3 Logging Masking

다음 값은 log, validation report, error message에서 masking해야 한다.

- Authorization
- API Key
- serviceKey
- clientSecret
- cookie
- 사용자 지정 secret parameter

---

# FR-7. Response Normalization

## FR-7.1 Envelope 제거

다음과 같은 응답 구조에서 실제 데이터 영역을 추출할 수 있어야 한다.

```json
{
  "response": {
    "header": {
      "resultCode": "00",
      "resultMsg": "NORMAL_SERVICE"
    },
    "body": {
      "items": {
        "item": []
      }
    }
  }
}
```

정규화 결과 예:

```json
{
  "items": [],
  "page": {
    "number": 1,
    "size": 10,
    "totalCount": 0
  },
  "provider": {
    "resultCode": "00",
    "resultMessage": "NORMAL_SERVICE"
  }
}
```

## FR-7.2 업무 오류 처리

HTTP 200 응답이어도 body의 resultCode가 실패를 나타낼 수 있다.

사용자는 다음을 설정할 수 있어야 한다.

- success code path
- success values
- error message path
- data path
- total count path

## FR-7.3 MCP 결과 변환

REST 오류는 다음 정보로 정규화해야 한다.

- error category
- provider code
- provider message
- retryable
- HTTP status
- operation id
- trace id

secret 및 민감 데이터는 포함하지 않는다.

---

# FR-8. Target Platform 선택

## FR-8.1 선택 항목

```text
Language
Java Version
Spring Boot Version
Spring AI Version
Build Tool
Web Stack
Programming Model
MCP Transport
Packaging
```

## FR-8.2 Compatibility Profile

시스템은 코드에 분산된 조건문 대신 명시적인 Compatibility Profile을 사용해야 한다.

```yaml
id: spring-ai-2.0-java21-mvc-streamable
language: JAVA
java:
  min: 17
  supported:
    - 17
    - 21
springBoot:
  range: "[validated range]"
springAi:
  range: "[validated range]"
webStack:
  - MVC
programmingModel:
  - SYNC
transports:
  - STREAMABLE_HTTP
buildTools:
  - GRADLE_KOTLIN
generator:
  module: spring-ai-2
  templateVersion: 1
```

## FR-8.3 Invalid Combination 차단

지원되지 않는 조합은 다음 단계에서 차단한다.

1. UI 선택 단계
2. API request validation
3. generation pipeline 시작 전
4. compilation verification

## FR-8.4 버전 고정

생성 manifest에는 정확한 버전을 기록해야 한다.

- Generator version
- Template version
- Spring AI version
- Spring Boot version
- Java version
- Runtime library version
- OpenAPI checksum

---

# FR-9. Spring AI 버전별 코드 생성

## FR-9.1 Generator 분리

다음 emitter를 별도 구현해야 한다.

```text
SpringAi1ProjectGenerator
SpringAi2ProjectGenerator
```

공통 템플릿에 다수의 버전 조건문을 넣는 방식은 사용하지 않는다.

## FR-9.2 Spring AI 1.x

검증된 1.x profile에 맞는 다음 요소를 생성한다.

- dependency
- server property
- transport
- Tool registration
- Tool Callback 또는 해당 버전의 MCP registration API
- MCP server endpoint

## FR-9.3 Spring AI 2.x

검증된 2.x profile에 맞는 다음 요소를 생성한다.

- MCP annotation 기반 Tool
- Streamable HTTP 설정
- annotation scanner 설정
- input schema validation 대응
- 해당 버전의 starter dependency

## FR-9.4 Template Versioning

템플릿 변경은 semantic version 또는 monotonically increasing version으로 관리한다.

생성 manifest 예:

```json
{
  "generatorVersion": "0.1.0",
  "targetProfile": "spring-ai-2.0-java21-mvc-streamable",
  "templateVersion": "spring-ai-2-v1",
  "runtimeVersion": "0.1.0"
}
```

---

# FR-10. Java 코드 생성

## FR-10.1 Package Structure

생성 프로젝트 소스 구조:

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

### FR-10.1.1 MCP Server 설정 및 annotation-scanner 비활성화 설계

생성된 프로젝트의 `application.yml`에서는 `spring.ai.mcp.server.annotation-scanner.enabled: false`를 기본 적용한다.

1. **OpenAPI 스키마 무결성 보장 (Deterministic Tool Schema)**: Spring AI 리플렉션 스캐너의 동적 생성 스키마 대신, OpenAPI 명세에서 도출된 엄격한 JSON Schema 리터럴을 `DefaultToolDefinition.inputSchema`에 직접 주입하여 계약 무결성을 보장한다.
2. **도구 중복 등록 및 어노테이션 혼선 방지**: Spring AI MCP Server Starter의 `@McpTool` 요구사항 및 버전별(1.1 Community vs 2.0 Official) 어노테이션 패키지 파편화를 방지하고 `List<McpServerFeatures.SyncToolSpecification>` 빈으로 명시적 등록한다.
3. **런타임 파이프라인 및 안전한 에러 캡슐화**: 커스텀 `callHandler`를 통해 도구 호출 시 W3C 분산 추적 및 Micrometer 메트릭(`RuntimeTelemetry`)을 수집하고, 공급자 API 오류 시 원시 스택트레이스 대신 `isError=true` safe payload를 안전하게 캡슐화한다.

## FR-10.2 Naming

- Java type: PascalCase
- field: camelCase
- Tool name: snake_case
- operation metadata id: dot notation
- package: lowercase

예약어 및 중복 이름은 자동 변환한다.

## FR-10.3 코드 스타일

- Java 17+ style
- constructor injection
- immutable DTO
- record 우선
- field injection 금지
- static utility 남용 금지
- generated code marker 포함
- formatter 적용 가능
- warning 없이 컴파일하는 것을 목표로 함

## FR-10.4 Generated Code 수정 가능성

생성 코드 중 사용자 수정 가능 영역과 재생성 영역을 분리해야 한다.

권장 방식:

```text
generated/
custom/
```

또는 인터페이스와 extension point를 사용한다.

재생성 시 사용자 수정 코드를 덮어쓰지 않아야 한다.

---

# FR-11. 공통 Generated Runtime

생성 프로젝트는 공통 runtime 라이브러리를 사용할 수 있어야 한다.

## FR-11.1 주요 책임

- REST execution
- parameter binding
- credential resolution
- timeout
- retry
- response normalization
- error mapping
- logging
- metrics
- tracing
- payload size limit
- masking
- correlation id

## FR-11.2 Runtime Versioning

runtime은 생성 프로젝트와 독립적으로 버전 관리한다.

생성 프로젝트는 정확한 runtime version을 dependency로 가진다.

## FR-11.3 Breaking Change 정책

runtime breaking change 발생 시 다음 중 하나를 제공해야 한다.

- major version 증가
- migration guide
- project regeneration
- compatibility adapter

---

# FR-12. 프로젝트 생성 결과

생성 ZIP에는 다음 파일이 포함되어야 한다.

```text
project-root
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── Dockerfile
├── .gitignore
├── README.md
├── GENERATION_MANIFEST.json
├── RUNTIME_METADATA.json
├── VALIDATION_REPORT.json
├── openapi
│   └── source.yaml
└── src
    ├── main
    │   ├── java
    │   └── resources
    └── test
        └── java
```

## FR-12.1 README

README에는 다음 내용을 포함한다.

- 프로젝트 설명
- 요구 Java 버전
- 실행 방법
- 필요한 환경변수
- MCP endpoint
- MCP Client 연결 예
- Tool 목록
- 테스트 방법
- Docker 실행 방법
- 생성기 정보
- 알려진 제한 사항

---

# FR-13. 검증 파이프라인

## FR-13.1 검증 단계

```text
OPENAPI_PARSE
SEMANTIC_VALIDATE
TOOL_MODEL_VALIDATE
SOURCE_GENERATE
FORMAT
COMPILE
APPLICATION_CONTEXT
MCP_INITIALIZE
MCP_TOOLS_LIST
MCP_TOOL_CALL
PACKAGE
```

## FR-13.2 Validation Level

```text
SYNTAX_ONLY
COMPILE
APPLICATION_CONTEXT
MCP_PROTOCOL
UPSTREAM_INTEGRATION
```

MVP 기본값은 `MCP_PROTOCOL`이다.

## FR-13.3 Compile Validation

선택된 Java toolchain과 build tool로 실제 컴파일해야 한다.

검증 결과:

- success
- warning count
- error count
- duration
- failed task
- error summary

## FR-13.4 Application Context Validation

Spring Boot application context가 정상 기동되는지 확인해야 한다.

외부 API 호출은 mock 또는 stub으로 대체한다.

## FR-13.5 MCP Protocol Validation

다음 호출을 검증한다.

1. initialize
2. initialized notification
3. tools/list
4. 대표 tools/call

검증 항목:

- server info
- capabilities
- Tool name
- Tool description
- input schema
- call result
- error result
- transport compatibility

## FR-13.6 검증 실패 정책

검증 실패 시 프로젝트 상태는 다음 중 하나가 된다.

```text
VALIDATED
GENERATED_WITH_WARNINGS
UNVERIFIED
FAILED
```

`VALIDATED`가 아닌 경우 UI와 ZIP에 상태를 명확히 표시한다.

---

# FR-14. 생성 작업 관리

## FR-14.1 상태

```text
PENDING
PARSING
ANALYZING
GENERATING
COMPILING
TESTING
PACKAGING
SUCCEEDED
FAILED
CANCELLED
```

## FR-14.2 Idempotency

동일한 idempotency key로 중복 생성 요청이 발생하면 기존 작업을 반환해야 한다.

## FR-14.3 작업 이력

다음 정보를 저장한다.

- project id
- generation job id
- user id
- input checksum
- target profile
- selected operations
- generator version
- validation result
- generated artifact reference
- created at
- completed at

---

# FR-15. CLI

MVP 또는 Phase 2에서 CLI를 제공한다.

예:

```bash
openapi-mcp generate \
  --spec ./openapi.yaml \
  --profile spring-ai-2-java21-mvc-streamable \
  --artifact-id weather-mcp-server \
  --package kr.go.data.weather \
  --output ./generated
```

추가 명령:

```bash
openapi-mcp inspect
openapi-mcp validate
openapi-mcp profiles
openapi-mcp diff
```

---

# FR-16. API

## FR-16.1 OpenAPI 분석

```http
POST /api/v1/specifications/analyze
```

응답:

- document metadata
- operation list
- warnings
- security parameter candidates
- unsupported feature list

## FR-16.2 Target Profile 조회

```http
GET /api/v1/target-profiles
```

filter:

- springAiVersion
- javaVersion
- transport
- buildTool

## FR-16.3 프로젝트 생성

```http
POST /api/v1/generations
```

예시 request:

```json
{
  "specificationId": "spec-123",
  "project": {
    "groupId": "kr.go.data",
    "artifactId": "weather-mcp-server",
    "packageName": "kr.go.data.weather"
  },
  "targetProfileId": "spring-ai-2-java21-mvc-streamable",
  "operations": [
    {
      "operationId": "getForecast",
      "enabled": true,
      "toolName": "kma_weather_get_forecast"
    }
  ],
  "validationLevel": "MCP_PROTOCOL"
}
```

## FR-16.4 상태 조회

```http
GET /api/v1/generations/{generationId}
```

## FR-16.5 산출물 다운로드

```http
GET /api/v1/generations/{generationId}/artifact
```

## FR-16.6 검증 보고서 조회

```http
GET /api/v1/generations/{generationId}/validation-report
```

## FR-16.7 Tool Catalog 조회

Hosted mode에서 OIDC 인증 owner는 검증 완료된 자신의 generation Catalog만 조회할 수 있다.

```http
GET /api/tool-catalogs?limit=50&cursor=...
GET /api/tool-catalogs/{catalogId}
GET /api/tool-catalogs/{catalogId}/tools/{toolName}
```

- 성공한 generation만 artifact, Catalog, Tool row, job state, terminal event를 한 transaction에서 게시한다.
- import, failed, cancelled, stale, unverified generation은 Catalog를 만들지 않는다.
- absent resource와 다른 owner의 resource는 동일한 `404` 응답을 반환한다.
- metadata에는 secret 값, 환경변수 이름, owner/job 식별자, object key, filesystem path를 포함하지 않는다.
- 기존 generation은 metadata version `1.0`으로 backfill하지 않는다.

## FR-16.8 Catalog revision, diff와 Runtime migration

- generation은 optional `predecessorCatalogId`로 같은 owner의 현재 family head를 명시한다.
- Catalog family는 branch 없이 revision이 1씩 증가하며 stale-head 동시 게시 중 하나만 성공한다.
- 같은 family의 immutable Runtime Metadata를 `toolName` 기준으로 비교하고 정렬된 change와 deterministic
  diff checksum을 반환한다.
- Tool 추가, description 변경, optional output property 추가만 compatible로 분류하고 알 수 없는 변경은
  breaking으로 닫는다.
- active runtime은 expected current Catalog와 target checksum을 CAS 전제조건으로 compatible revision에만
  migration한다.
- migration은 runtime ID, bearer, credential version, grant, rate, audit을 보존하고 append-only transition을 남긴다.
- rollback은 가장 최근의 아직 되돌리지 않은 migration source만 허용하며 active grant의 Tool subset과 동일한
  credential slot 계약을 다시 검증한다.
- cross-owner·cross-family lookup은 `404`, stale CAS는 `409 CATALOG_VERSION_CONFLICT`, breaking diff는
  `409 CATALOG_MIGRATION_BREAKING`, grant/credential incompatibility는 `409 CATALOG_MIGRATION_BLOCKED`다.

---

## 9. 비기능 요구사항

# NFR-1. 보안

- 업로드 문서는 격리된 저장소에 보관한다.
- URL import는 SSRF 방어를 적용한다.
- 생성 및 빌드 작업은 sandbox에서 실행한다.
- build script의 임의 코드 실행을 방지한다.
- 외부 dependency repository 접근을 통제한다.
- secret은 입력, log, artifact에 저장하지 않는다.
- artifact 다운로드는 권한 검사를 수행한다.
- 작업별 filesystem을 격리한다.
- container privilege를 최소화한다.
- network egress를 제한한다.
- ZIP Slip을 방어한다.
- 생성 코드에 사용자 입력을 삽입할 때 escape 및 identifier validation을 적용한다.

# NFR-2. 성능

MVP 목표:

- 1MB 이하 OpenAPI 분석 p95 5초 이하
- 50 operation 프로젝트 소스 생성 p95 10초 이하
- compile 및 MCP validation을 제외한 generation p95 15초 이하
- `tools/list` validation 5초 이하
- 동시 생성 작업은 worker 수 기준으로 수평 확장 가능

# NFR-3. 확장성

- API server와 generation worker를 분리한다.
- 작업 큐를 통해 비동기 실행한다.
- worker는 stateless하게 구성한다.
- artifact는 object storage에 저장한다.
- compatibility profile은 배포 없이 추가 가능하거나 최소 변경으로 확장 가능해야 한다.
- generator plugin 단위 확장을 지원한다.

# NFR-4. 안정성

- 생성 작업은 단계별 checkpoint를 기록한다.
- worker 장애 시 재시도 가능해야 한다.
- 동일 작업의 중복 실행을 방지한다.
- compile timeout을 설정한다.
- process, memory, CPU limit을 적용한다.
- artifact 생성 전 모든 stream을 닫고 checksum을 검증한다.

# NFR-5. 관측성

필수 metric:

- generation request count
- generation success/failure count
- stage duration
- OpenAPI parse failure count
- compatibility rejection count
- compile failure count
- MCP validation failure count
- artifact size
- active worker count
- queue depth
- timeout count

필수 trace span:

```text
generation.request
openapi.fetch
openapi.parse
openapi.normalize
tool.transform
source.generate
project.compile
spring.startup
mcp.initialize
mcp.tools.list
mcp.tools.call
artifact.package
```

log에는 다음 correlation 정보를 포함한다.

- traceId
- generationId
- projectId
- targetProfileId
- operationId

# NFR-6. 재현성

생성 결과는 다음 입력에 의해 결정되어야 한다.

- OpenAPI checksum
- user configuration
- target profile
- generator version
- template version
- runtime version

외부의 latest 또는 dynamic version을 사용하지 않는다.

# NFR-7. 유지보수성

- OpenAPI domain과 Spring AI implementation을 분리한다.
- version-specific generator를 별도 module로 유지한다.
- template snapshot test를 작성한다.
- 생성 프로젝트 compile test를 CI에 포함한다.
- profile별 golden project를 유지한다.

---

## 10. 권장 시스템 아키텍처

```text
Client / Web UI / CLI
        |
        v
Generator API
        |
        +---- Specification Service
        |       - Upload
        |       - URL Fetch
        |       - Parse
        |       - Analyze
        |
        +---- Profile Service
        |       - Compatibility Matrix
        |
        +---- Generation Orchestrator
                |
                v
             Job Queue
                |
                v
        Sandbox Generation Worker
        - OpenAPI Normalizer
        - Tool IR Builder
        - Policy Engine
        - Target Generator
        - Compiler
        - MCP Validator
        - Packager
                |
                +---- Artifact Storage
                +---- Owner-scoped Tool Catalog
                +---- Metadata DB
                +---- Metrics / Traces / Logs
```

---

## 11. 내부 모듈 구조

```text
gen2spring-mcp
├── modules
│   ├── domain
│   ├── application
│   ├── adapters
│   │   ├── configuration
│   │   ├── openapi
│   │   ├── filesystem
│   │   ├── validation
│   │   └── emitters
│   │       ├── support
│   │       ├── spring-ai-1
│   │       └── spring-ai-2
│   └── bootstrap
└── apps
    ├── cli
    └── web
```

---

## 12. 핵심 도메인 모델

### 12.1 Tool IR

```java
public record ToolDefinition(
    ToolId id,
    String name,
    String title,
    String description,
    List<ToolInput> inputs,
    ToolOutput output,
    HttpExecution execution,
    SecurityDefinition security,
    ResponseNormalizationDefinition responseNormalization,
    ErrorMappingDefinition errorMapping
) {
}
```

### 12.2 Target Platform

```java
public record TargetPlatform(
    Language language,
    int javaVersion,
    String springBootVersion,
    String springAiVersion,
    BuildTool buildTool,
    WebStack webStack,
    ProgrammingModel programmingModel,
    McpTransport transport
) {
}
```

### 12.3 Compatibility Profile

```java
public record CompatibilityProfile(
    String id,
    VersionRange springAiRange,
    VersionRange springBootRange,
    Set<Integer> javaVersions,
    Set<BuildTool> buildTools,
    Set<WebStack> webStacks,
    Set<ProgrammingModel> programmingModels,
    Set<McpTransport> transports,
    String generatorModule,
    String templateVersion
) {
}
```

### 12.4 Parameter Classification

```java
public enum ParameterSource {
    USER_INPUT,
    SERVER_SECRET,
    SERVER_DEFAULT,
    CONTEXT_DERIVED,
    INTERNAL,
    UNSUPPORTED
}
```

---

## 13. 생성 파이프라인

```text
1. OpenAPI Source Load
2. Syntax Validation
3. Reference Resolution
4. OpenAPI Normalization
5. Operation Analysis
6. Parameter Classification
7. Tool IR Generation
8. Policy Validation
9. Target Compatibility Validation
10. Project Model Generation
11. Source Emission
12. Formatting
13. Compilation
14. Spring Context Test
15. MCP Protocol Test
16. Artifact Packaging
17. Manifest and Report Generation
```

### 단계별 실패 원칙

- 각 단계는 명확한 입력과 출력을 가진다.
- 단계 실패는 다음 단계로 전파하지 않는다.
- 오류는 사용자 입력 오류와 시스템 오류로 분류한다.
- 재시도 가능한 오류와 불가능한 오류를 구분한다.
- 생성 중간 산출물은 디버깅 모드에서만 보존한다.

---

## 14. 데이터 모델

### Project

- id
- ownerId
- name
- groupId
- artifactId
- packageName
- createdAt
- updatedAt

### Specification

- id
- projectId
- sourceType
- originalFilename
- sourceUrl
- checksum
- openApiVersion
- parseStatus
- createdAt

### GenerationJob

- id
- projectId
- specificationId
- targetProfileId
- status
- validationLevel
- generatorVersion
- templateVersion
- runtimeVersion
- startedAt
- completedAt
- failureCode
- failureMessage

### SelectedOperation

- generationJobId
- operationId
- enabled
- toolName
- toolDescription
- inputPolicy
- outputPolicy

### GeneratedArtifact

- id
- generationJobId
- storagePath
- checksum
- size
- validationStatus
- expiresAt

### ValidationResult

- generationJobId
- stage
- status
- durationMs
- warningCount
- errorCount
- detailReference

### ToolCatalog

- id
- ownerAccountId
- generationJobId
- metadataVersion
- specificationChecksum
- metadataChecksum
- metadataDocument
- toolCount
- createdAt

Catalog entry는 `(catalogId, toolName)`을 식별자로 사용하고 canonical Tool metadata와 deterministic ordinal을
저장한다. Catalog는 수정·삭제·공유하지 않는 immutable 조회 모델이다.

---

## 15. 오류 코드

```text
SPEC_FILE_UNSUPPORTED
SPEC_FETCH_FAILED
SPEC_FETCH_BLOCKED
SPEC_TOO_LARGE
SPEC_PARSE_FAILED
SPEC_REFERENCE_UNRESOLVED
SPEC_VERSION_UNSUPPORTED
OPERATION_ID_DUPLICATED
OPERATION_UNSUPPORTED
SCHEMA_UNSUPPORTED
SECRET_EXPOSURE_DETECTED
TARGET_PROFILE_NOT_FOUND
TARGET_COMBINATION_UNSUPPORTED
SOURCE_GENERATION_FAILED
SOURCE_FORMAT_FAILED
COMPILE_TIMEOUT
COMPILE_FAILED
APPLICATION_CONTEXT_FAILED
MCP_INITIALIZE_FAILED
MCP_TOOLS_LIST_FAILED
MCP_TOOL_CALL_FAILED
ARTIFACT_PACKAGE_FAILED
INTERNAL_ERROR
```

---

## 16. UI 요구사항

### 16.1 Step 1: OpenAPI 입력

- 파일 업로드
- URL 입력
- parse 결과
- 문서 정보
- 오류 및 경고

### 16.2 Step 2: Operation 선택

- 검색
- tag filter
- method filter
- supported filter
- operation별 enable
- Tool preview
- parameter classification

### 16.3 Step 3: Target 선택

- Spring AI version
- Spring Boot version
- Java version
- Build Tool
- Transport
- Programming Model
- Web Stack

잘못된 조합은 표시하지 않는다.

### 16.4 Step 4: 생성 Preview

- 프로젝트 구조
- dependency
- Tool 목록
- environment variables
- warning
- 예상 validation level

### 16.5 Step 5: 생성 상태

- 현재 stage
- stage별 결과
- compile log summary
- MCP validation result
- artifact download

---

## 17. 테스트 전략

### 17.1 Unit Test

- OpenAPI primitive schema mapping
- naming policy
- parameter classification
- compatibility validation
- response path extraction
- template rendering

### 17.2 Golden File Test

입력 OpenAPI와 기대 생성 결과를 snapshot으로 관리한다.

대상:

- query parameter
- path parameter
- request body
- enum
- array
- nested object
- security scheme
- envelope response
- invalid schema

### 17.3 Compile Matrix Test

플랫폼 검증 상태: Linux와 Windows 완료

지원 profile별로 대표 프로젝트를 실제 컴파일한다. 빠른 PR CI와 전체 generation acceptance는 서로 다른
검증 수준으로 관리한다.

```text
Spring AI 1.x + Java 17
Spring AI 1.x + Java 21
Spring AI 2.x + Java 17
Spring AI 2.x + Java 21
```

실제 지원 matrix에 따라 조정한다.

Linux와 Windows의 필수 PR CI는 profile registry, 생성 source/scaffold, wrapper checksum, build command,
artifact 탐색, UI/API 계약을 빠르게 검증한다. 생성 프로젝트 내부에서 다시 Gradle 또는 Maven을 실행하는
전체 matrix는 필수 PR CI에서 제외한다.

별도 generation acceptance는 Linux에서 지원 profile 전체의 compile, generated test, ApplicationContext,
MCP initialize/tools/list/tools/call을 실행한다. Windows acceptance는 build tool, web stack, programming model,
target Java 축을 덮는 대표 profile을 실행한다. Windows command는 trusted
`%SystemRoot%\System32\cmd.exe`의 고정 argument만 사용하고 wrapper/runtime identity를 기동 직전 재검증한다.
native file key를 제공하지 않는 Windows JDK에서는 physical path, file store, creation time과 bounded file
metadata를 사용하고 hard-link 관계는 별도로 확인한다. CLI private staging은 owner-only Windows ACL을
요구한다. 관련 구현 경계는 [issue #2](https://github.com/ydj515/gen2spring-mcp/issues/2)와 연결한다.

지원 matrix는 Spring AI 1.1 MVC Sync 4개, Spring AI 2.0 MVC Sync 4개, Spring AI 2.0 WebFlux Async 4개로
총 12개다. `mise run generator:test`는 생성 프로젝트 wrapper를 실행하지 않는 빠른 계약 검증이며,
`mise run generator:acceptance`는 POSIX 전체 12개 또는 Windows 대표 2개를 실행한다. 동일한 경계는 수동
`Generation Acceptance` GitHub Actions workflow에도 반영한다.

### 17.4 MCP Contract Test

- initialize
- tools/list
- tools/call
- invalid input
- upstream error
- timeout
- response size overflow

### 17.5 Security Test

- SSRF
- ZIP Slip
- path traversal
- malicious package name
- malicious OpenAPI description
- shell injection
- oversized schema
- recursive `$ref`
- secret leakage
- generated build script injection

### 17.6 성능 테스트

- 10 operation
- 50 operation
- 200 operation
- 대형 nested schema
- 동시 generation job
- compile worker saturation

---

## 18. 성공 지표

### 제품 지표

- OpenAPI upload 대비 generation 완료율 90% 이상
- 지원 범위 내 generation compile 성공률 98% 이상
- compile 성공 프로젝트의 MCP initialize 성공률 99% 이상
- 수동 boilerplate 개발 시간 80% 이상 단축
- operation당 수동 수정 필요 비율 20% 이하
- secret parameter 자동 탐지 precision 95% 이상

### 운영 지표

- generation system availability 99.9%
- generation 실패 원인 분류율 95% 이상
- 동일 input 재생성 결과 checksum 일치율 100%
- worker timeout 비율 1% 이하
- artifact corruption 0건

### 품질 지표

- 지원 profile별 CI compile success 100%
- golden test regression 0건
- critical security finding 0건
- Tool schema mismatch 주요 결함 0건

---

## 19. MVP 출시 기준

다음 조건을 모두 만족하면 MVP 출시 가능하다.

1. OpenAPI 3.0 YAML/JSON 입력 가능
2. operation 선택 가능
3. Java 17, 21 선택 가능
4. Spring AI 1.x, 2.x 대표 profile 지원
5. Gradle Kotlin DSL 프로젝트 생성
6. Spring MVC Sync Remote MCP Server 생성
7. Streamable HTTP 또는 profile별 권장 transport 지원
8. API Key query/header 주입 지원
9. Tool name, description, input DTO 생성
10. 공통 runtime을 통한 REST 호출
11. 실제 compile 검증
12. Spring context 기동 검증
13. initialize, tools/list, tools/call 검증
14. ZIP, README, manifest, validation report 제공
15. SSRF, secret leakage, build sandbox 기본 방어 적용

---

## 20. 개발 우선순위

### P0

- OpenAPI parsing
- Tool IR
- compatibility profile
- Spring AI 2.x generator
- Java 21
- Gradle Kotlin DSL
- Spring MVC Sync
- Streamable HTTP
- API Key injection
- compile validation
- tools/list validation
- ZIP packaging

### P1

- Java 17
- Spring AI 1.x generator
- response normalization
- tools/call mock validation
- UI operation editor
- validation report
- Dockerfile
- runtime metrics and tracing
- typed output DTO
- bounded retry
- bounded pagination
- Windows validation host

### P2 완료

- bounded OpenAPI 3.1
- deterministic Managed Runtime metadata
- persistent owner-scoped Tool Catalog query
- dynamic Managed Runtime 실행: 완료 (P2 v1 지원 범위)
  - 단일 Catalog activation/revocation과 exact credential slot binding
  - OPAQUE·Bearer·Basic encrypted credential create/rotate/revoke
  - owner token과 scoped Tool grant, PostgreSQL distributed rate limit, safe execution audit
  - bearer token digest persistence와 stateless multi-replica Streamable HTTP
  - exact MCP Java SDK `tools/list`·bounded `tools/call`
- mTLS provider-egress와 public HTTP/HTTPS 80/443 destination policy
- linear Catalog family/revision publication과 deterministic compatible/breaking diff
- active Runtime CAS migration, append-only history, grant-aware rollback, multi-replica cutover
- Maven 기반 Spring AI 1.1·2.0 MVC Sync 생성
- Spring AI 2.0 WebFlux Async 생성
- Gradle·Maven, Java 17·21을 조합한 12개 Streamable HTTP profile

### P2 남은 범위

- STDIO
- Kotlin
- cross-Catalog public Gateway, sharing, OAuth2 credential acquisition, billing
- AI description enhancement

### P2 생성 대상 확장: 완료

Maven, WebFlux, Async를 하나의 생성 대상 확장 슬라이스로 구현했다. 빠른 계약 검증과 POSIX 전체 12개
generation acceptance, 저장소 비-hosted 회귀 검증을 통과한 뒤 세 항목을 `P2 완료`로 이동했다.

공식 지원 대상으로 등록할 조합은 12개다.

| Spring AI | Java | Build tool | Web stack | Programming model | Transport | 상태 |
|---|---:|---|---|---|---|---|
| 1.1.8 | 17, 21 | Gradle Kotlin DSL, Maven | MVC | Sync | Streamable HTTP | 지원 |
| 2.0.0 | 17, 21 | Gradle Kotlin DSL, Maven | MVC | Sync | Streamable HTTP | 지원 |
| 2.0.0 | 17, 21 | Gradle Kotlin DSL, Maven | WebFlux | Async | Streamable HTTP | 지원 |
| 1.1.8 | 17, 21 | Gradle Kotlin DSL, Maven | WebFlux | Async | Streamable HTTP | 보류 |

Spring AI 1.1.8의 WebFlux Streamable HTTP transport가 사용하는 MCP SDK 0.18.3에는 null SSE message ID를
처리하지 못하는 upstream 결함이 남아 있다. 이 조합은 profile로 등록하거나 생성하지 않고, UI의 profile
도움말과 문서에서 보류 사유를 안내한다. 수정된 Spring AI 1.1.x에서 Java 17·21, Gradle·Maven의 compile,
ApplicationContext, MCP initialize/tools/list/tools/call이 모두 통과한 뒤에만 지원 대상으로 전환한다.

Profile 선택 UI는 생성 가능한 12개만 선택 항목으로 제공한다. `Compatibility profile` label 옆의 접근
가능한 도움말 버튼은 MVC+Sync 지원 범위, Spring AI 2.0 WebFlux+Async 지원 범위, Spring AI 1.1
WebFlux+Async 보류 사유를 간략히 설명한다. 문구는 client에 별도로 하드코딩하지 않고 profile API의
canonical compatibility notice를 사용한다.

세부 설계는
[생성 방식과 호환성](design-decisions.md#생성-방식과-호환성)를 따른다.

---

## 21. 주요 리스크와 대응

### R1. Spring AI 버전 변화

#### 위험

Spring AI 및 MCP Java SDK API가 빠르게 변경될 수 있다.

#### 대응

- 지원 버전을 제한한다.
- profile별 exact version을 고정한다.
- 버전별 generator module을 분리한다.
- CI에서 실제 compile matrix를 실행한다.
- 문서상의 호환성이 아니라 검증된 조합만 공개한다.

### R2. OpenAPI 품질 편차

#### 위험

operationId 누락, description 부족, schema 오류가 많을 수 있다.

#### 대응

- 자동 보정과 사용자 review를 구분한다.
- 생성 전 warning을 표시한다.
- unsupported operation을 제외할 수 있게 한다.
- Tool name과 description override를 제공한다.

### R3. MCP Tool 품질 저하

#### 위험

REST operation을 1:1 변환하면 LLM이 사용하기 어려운 Tool이 생성될 수 있다.

#### 대응

- description 품질 규칙을 적용한다.
- parameter를 semantic group으로 정리한다.
- Phase 2에서 여러 operation을 복합 Tool로 조합한다.
- 생성 후 LLM-based tool selection evaluation을 추가한다.

### R4. 생성 코드의 유지보수 문제

#### 위험

사용자가 수정한 코드가 재생성 시 덮어써질 수 있다.

#### 대응

- generated/custom 영역을 분리한다.
- extension interface를 제공한다.
- manifest를 기반으로 변경 파일을 추적한다.
- merge 또는 patch 기반 재생성을 Phase 2에서 제공한다.

### R5. Build Sandbox 보안

#### 위험

악성 OpenAPI 또는 생성 설정으로 임의 코드 실행이 발생할 수 있다.

#### 대응

- build worker를 container sandbox로 격리한다.
- network egress를 제한한다.
- read-only base image를 사용한다.
- CPU, memory, process, time limit을 적용한다.
- 사용자 입력을 build script 코드로 직접 삽입하지 않는다.

### R6. 외부 dependency 다운로드 불안정

#### 위험

컴파일 검증이 Maven Central 또는 사내 repository 상태에 영향을 받을 수 있다.

#### 대응

- dependency proxy/cache를 사용한다.
- 지원 profile dependency를 사전 warm-up한다.
- artifact checksum을 검증한다.
- offline-compatible build cache를 고려한다.

---

## 22. 의사결정 사항

### D1. OpenAPI를 직접 템플릿에 전달하지 않는다

반드시 버전 독립적인 Tool IR을 거친다.

### D2. 모든 버전 조합을 허용하지 않는다

검증된 Compatibility Profile만 제공한다.

### D3. 생성 완료와 검증 완료를 구분한다

파일 생성 성공만으로 `SUCCEEDED` 처리하지 않는다.

### D4. Runtime 공통 기능을 생성 코드에서 분리한다

인증, REST 호출, 오류 변환, 관측성은 공통 runtime에서 담당한다.

### D5. Spring AI 버전별 generator를 분리한다

하나의 템플릿에 버전 조건문을 누적하지 않는다.

### D6. Secret을 MCP Tool input으로 노출하지 않는다

사용자 입력과 서버 secret을 명시적으로 구분한다.

### D7. MVP는 Spring MVC Sync를 우선한다

Reactive와 Async는 필요성과 복잡도를 검증한 뒤 확장한다.

---

## 23. 미결정 사항

1. MVP에서 정확히 지원할 Spring AI 1.x 버전
2. MVP에서 정확히 지원할 Spring AI 2.x 버전
3. Spring Boot version 선택을 직접 노출할지 profile에 포함할지
4. Spring AI 1.x에서 기본 transport를 무엇으로 할지
5. 생성 코드가 공통 runtime dependency를 필수로 사용할지, standalone mode도 제공할지
6. output DTO를 기본으로 생성할지 generic JSON을 기본으로 할지
7. operation별 Tool 생성과 metadata-driven generic Tool 사이의 기본 전략
8. 생성 artifact의 보존 기간
9. AI 기반 description enhancement를 언제 도입할지
10. 사용자 수정 코드와 재생성 merge 전략
11. Managed MCP Runtime과 독립 프로젝트 생성 기능의 우선순위
12. UI와 CLI 중 MVP 주 인터페이스

---

## 24. 권장 MVP 기술 스택

### Backend

- Java 21
- Spring Boot
- PostgreSQL
- Object Storage
- Job Queue
- OpenTelemetry

### OpenAPI

- swagger-parser 또는 검증된 OpenAPI parser
- 별도 normalization layer

### Code Generation

- KotlinPoet 또는 JavaPoet 계열 검토
- Mustache/Freemarker는 프로젝트 파일 및 설정 생성에 제한적으로 사용
- Java source는 AST 또는 structured code generation 우선

### Build Sandbox

- OCI container
- JDK 17/21 base image
- Gradle dependency cache
- CPU/memory/time limit
- restricted network

### Validation

- Gradle Tooling API 또는 isolated process
- MCP test client
- WireMock 또는 MockWebServer
- Testcontainers는 worker 환경 비용을 고려해 선택

---

## 25. 권장 구현 원칙

1. OpenAPI parsing, Tool design, Spring AI code generation을 별도 계층으로 분리한다.
2. 생성 코드보다 Tool IR의 정확성을 우선한다.
3. 버전 호환성은 추론하지 말고 검증된 matrix로 관리한다.
4. 생성 프로젝트는 실제 선택 JDK로 컴파일한다.
5. MCP protocol test를 자동화한다.
6. secret과 사용자 입력을 구조적으로 분리한다.
7. 공공 API의 HTTP 200 업무 오류를 기본 고려한다.
8. 응답 크기와 pagination을 MCP context 관점에서 제한한다.
9. 생성 결과에는 재현 가능한 manifest를 포함한다.
10. 생성기와 runtime의 버전을 독립적으로 관리한다.

---

## 26. 향후 확장 방향

### 26.1 Managed MCP Runtime

```text
OpenAPI
  -> Tool IR
  -> Runtime Metadata
  -> Dynamic Tool Registry
  -> Managed MCP Runtime
```

기관별 MCP Server를 새로 배포하지 않고 immutable Catalog 하나를 bearer token으로 활성화해 `tools/list`와
bounded `tools/call`을 처리한다. Runtime은 exact credential slot binding, scoped Tool grant, PostgreSQL rate·audit,
stateless multi-replica SDK handle을 제공하고 provider 요청은 mTLS provider-egress를 통해서만 실행한다. 이
Managed Runtime은 여러 Catalog를 공유·중개하는 공개 Gateway가 아니고 생성된 다운로드 프로젝트에도 포함되지 않는다.

### 26.2 Composite Tool Designer

여러 REST operation을 하나의 업무 의미 Tool로 묶는다.

예:

```text
여행 일정 생성
- 관광지 검색
- 날씨 조회
- 교통 조회
```

### 26.3 Version Migration

기존 Spring AI 1.x 프로젝트를 2.x로 재생성하거나 migration report를 제공한다.

### 26.4 Evaluation

생성된 Tool에 대해 다음 평가를 자동화한다.

- Tool selection accuracy
- argument generation accuracy
- schema validation failure rate
- response usefulness
- token consumption
- duplicate Tool ambiguity

### 26.5 Gateway Integration

- 여러 Catalog를 결합한 사용자별 Tool discovery와 sharing
- 외부 조직·tenant federation authorization
- OAuth2 credential acquisition
- billing and usage settlement
- execution trace

linear Tool Catalog family/versioning과 단일 active Runtime의 migration/rollback은 완료됐다. 여러 Catalog를
결합·공유하는 공개 Gateway, tenant federation, OAuth2 acquisition과 billing은 완료되지 않았다.

---

## 27. 최종 권장 제품 방향

이 제품은 단순한 Swagger 코드 생성기가 아니라 다음 세 역할을 수행해야 한다.

```text
OpenAPI Compiler
+ MCP Tool Design Engine
+ Target Platform Code Generator
```

가장 중요한 제품 차별점은 파일을 생성하는 기능이 아니라 다음을 보장하는 것이다.

- OpenAPI와 MCP Tool 간 의미 있는 변환
- 버전별 호환성
- secret 안전성
- 실제 컴파일 성공
- MCP Client 호환성
- 재현 가능한 생성 결과

MVP는 지원 범위를 좁게 잡고, 검증된 Spring AI 1.x 및 2.x profile에 대해 높은 생성 성공률을 확보하는 방향으로 추진한다.
