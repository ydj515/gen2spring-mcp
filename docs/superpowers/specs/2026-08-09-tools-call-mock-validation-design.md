# P1 `tools/call` Mock Validation 설계

- 상태: 승인됨
- 기준 문서: `docs/prd.md` v0.1, FR-13.5, 개발 우선순위 P1
- 작성일: 2026-08-09
- 선행 조건: P0 merge commit `d87c7f2`

## 1. 목표

P1의 첫 수직 슬라이스로 생성된 MCP 서버의 대표 Tool을 실제 `tools/call`로 호출하고,
generated runtime이 만든 HTTP 요청을 loopback mock upstream에서 검증한다. 컴파일,
ApplicationContext, `initialize`, `tools/list`만 통과한 프로젝트를 더 이상 완전 검증된 것으로
간주하지 않고, Tool input부터 upstream HTTP binding과 MCP 결과까지 연결된 경우에만 ZIP을
만든다.

완료 기준은 다음과 같다.

1. 생성 설정에서 검증할 operation 하나와 명시적 MCP argument를 선언한다.
2. 검증기는 외부 provider 대신 loopback mock upstream을 기동한다.
3. generated MCP server에 실제 `tools/call` JSON-RPC 요청을 전송한다.
4. mock upstream이 method, path, query, header, JSON body와 synthetic secret binding을 검증한다.
5. MCP call result가 mock response를 의미 변경 없이 반환하는지 검증한다.
6. `MCP_TOOL_CALL` stage가 실패하면 보고서는 `UNVERIFIED`이고 ZIP은 생성하지 않는다.

## 2. 범위

### 2.1 포함

- Spring AI 2.0, Java 21 P0 profile
- `MCP_PROTOCOL` validation level의 대표 `tools/call` 1회
- operation별 명시적 validation argument
- path, query, header, JSON body binding 검증
- query array의 반복 key 직렬화 검증
- API Key query/header synthetic secret 주입 검증
- 고정된 JSON mock response와 MCP text result 비교
- success, invalid result, upstream mismatch, timeout, response overflow 검증
- validation report의 `MCP_TOOL_CALL` stage
- 전체 CLI generation journey와 ZIP fail-closed 검증

### 2.2 제외

- Windows validation host 지원
- 실제 provider 또는 인터넷 endpoint 호출
- 여러 Tool을 한 generation에서 모두 호출하는 검증
- response envelope normalization과 HTTP 200 업무 오류 mapping
- typed output DTO
- retry, pagination, metrics, tracing
- Java 17과 Spring AI 1.x profile
- UI operation editor

## 3. 접근 방식

### 3.1 선택: 명시적 argument와 자동 파생 upstream expectation

사용자는 대표 operation과 MCP argument만 설정한다. HTTP method, path, parameter binding,
request body, secret target은 이미 검증된 `McpToolDefinition`에서 파생한다. mock response는
검증기 내부의 고정 JSON payload를 사용한다.

이 방식은 schema의 pattern이나 provider 업무 규칙을 추측하지 않으면서도 동일한 Tool IR을
generation과 validation의 단일 진실 공급원으로 유지한다. 사용자가 HTTP expectation을 별도로
중복 작성하지 않으므로 생성 코드와 검증 설정이 함께 잘못되는 위험을 줄인다.

### 3.2 제외한 대안

- schema 기반 argument 자동 합성: arbitrary regex와 업무 제약을 만족하는 값을 안정적으로
  만들 수 없고, 잘못된 sample 때문에 정상 프로젝트가 실패할 수 있다.
- OpenAPI `example` 또는 `default`만 사용: 실제 명세에서 값이 누락되는 경우가 많아 대표
  `tools/call`을 출시 기준으로 강제할 수 없다.
- HTTP expectation까지 사용자가 작성: binding 구현과 expectation 양쪽에 같은 오류가 들어가면
  검증이 통과할 수 있고 설정이 과도하게 중복된다.

### 3.3 전환 조건

operation별 회귀 검증이 필요해지거나 서로 다른 응답 shape를 검증해야 할 때 `toolCall`을 bounded
list로 확장한다. 이 슬라이스에서는 대표 1회만 지원해 validation 시간과 설정 복잡도를 제한한다.

## 4. 생성 설정 계약

최상위에 `validation`을 추가하고 `MCP_PROTOCOL`에서는 `toolCall`을 필수로 한다.

```yaml
validation:
  toolCall:
    operationId: getForecast
    arguments:
      stationId: STN01
      days: 3
      mode: brief
      tags:
        - public
      clientVersion: validator
      location:
        latitude: 37.5
        longitude: 127.0
      note: deterministic validation
      includeAlerts: false
```

규칙은 다음과 같다.

- `operationId`는 enabled operation 하나와 정확히 일치해야 한다.
- argument key는 최종 MCP input name과 일치해야 한다.
- 모든 required input을 제공하고 unknown input을 거부한다.
- 값은 null이 아닌 JSON string, integer, number, boolean, array, object만 허용한다.
- enum, min/max, length, pattern, array item, nested object와 required 제약을 Tool IR에 대해
  generation 전에 검증한다.
- optional input은 생략할 수 있다.
- 전체 설정은 기존 1 MiB 제한을 유지하고 argument depth 16, object member 256,
  array item 256, string 2,048자를 적용한다.
- validation argument와 synthetic secret 값은 manifest, validation report, ZIP metadata와
  오류 summary에 기록하지 않는다.

기존 P0 설정은 P1 계약으로 갱신한다. `MCP_PROTOCOL`인데 `validation.toolCall`이 없으면 CLI
configuration 오류로 fail-closed한다.

## 5. 도메인 계약

`GenerationRequest`에 immutable `ValidationConfiguration`을 추가한다.

```text
ValidationConfiguration
└── ToolCallValidation
    ├── operationId
    └── arguments: Map<String, JSON-compatible value>
```

Tool IR 생성 후 core가 operation ID로 `McpToolDefinition`을 찾고 argument를 schema에 대해
검증한다. 그 결과를 `ValidationRequest`의 `ExpectedToolCall`로 전달한다.

```text
ExpectedToolCall
├── tool: McpToolDefinition
└── arguments: immutable map
```

validator는 별도 OpenAPI parsing이나 source 분석을 하지 않고 이 계약만 소비한다. expected
Tool schema 생성과 call expectation 생성은 동일한 Tool IR을 사용한다.

## 6. 검증 파이프라인

```text
COMPILE
→ APPLICATION_CONTEXT
→ MCP_INITIALIZE
→ MCP_TOOLS_LIST
→ MCP_TOOL_CALL
→ REPORT
→ PACKAGE
```

1. compile 성공 후 loopback mock upstream을 먼저 기동한다.
2. generated application의 `PROVIDER_BASE_URL`을 mock upstream URI로 덮어쓴다.
3. 각 secret environment variable에 deterministic synthetic value를 주입한다.
4. application을 기동하고 `initialize`, `tools/list`를 기존 방식으로 검증한다.
5. `tools/call`을 전송하고 동시에 mock upstream의 관측 요청을 검증한다.
6. MCP result와 mock response가 일치하면 `MCP_TOOL_CALL=SUCCESS`를 기록한다.
7. application과 mock server를 항상 종료하고 임시 관측 데이터를 폐기한다.

선행 stage가 실패하면 뒤 stage는 `SKIPPED`다. `MCP_TOOL_CALL` 실패는 전체 상태를
`UNVERIFIED`로 만들고 packaging을 실행하지 않는다.

## 7. Mock upstream

`generator-validation`에 JDK `HttpServer` 기반 package-private mock server를 둔다.

- loopback address와 ephemeral port에만 bind한다.
- redirect와 outbound network를 사용하지 않는다.
- 요청은 한 건만 허용하고 두 번째 요청은 실패한다.
- request body를 기존 1 MiB 이하로 bounded read한다.
- method와 raw path를 OpenAPI serialization 계약에 따라 계산한 값과 exact 비교한다.
- query는 key와 ordered value list로 정규화해 반복 key를 보존한다.
- header name은 case-insensitive, value는 exact 비교한다.
- JSON body는 object field order와 숫자 표기 차이를 canonical JSON으로 비교한다.
- 예상하지 않은 query, generated header, body field는 실패시킨다. 단, HTTP client가 자동
  추가하는 표준 transport header는 allowlist로 제외한다.
- 성공 응답은 `{"validated":true,"operationId":"<id>"}`와
  `Content-Type: application/json`으로 고정한다.

upstream expectation은 Tool argument, parameter binding, object request body 여부와 synthetic
secret binding에서 계산한다. 실제 secret은 사용하지 않는다.

## 8. Generated application 환경

application launch API가 bounded environment override를 받도록 확장한다.

- `PROVIDER_BASE_URL`: loopback mock URI
- 각 configured secret environment variable: `mcp-validation-secret-<index>`

override key는 기존 environment variable allowlist를 통과해야 하고 value는 process argument가
아니라 `ProcessBuilder.environment()`로 전달한다. parent environment를 무제한 report하거나
복사하지 않으며, synthetic value를 process output과 오류 summary에서 masking한다.

## 9. MCP `tools/call` 계약

기존 session에서 JSON-RPC id `3`으로 다음 요청을 보낸다.

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "kma_weather_get_forecast",
    "arguments": {}
  }
}
```

검증 기준은 다음과 같다.

- HTTP status, content type, response size와 JSON-RPC envelope는 기존 경계를 재사용한다.
- response id는 arbitrary precision integer로 exact 비교한다.
- JSON-RPC `error`, missing result, `isError=true`를 실패로 처리한다.
- `content`는 비어 있지 않아야 하고 generated `JsonNode` 결과의 text content를 JSON으로
  parsing할 수 있어야 한다.
- parsed result는 mock response JSON과 canonical 비교한다.
- call과 upstream 관측 모두 제한 시간 안에 완료되어야 한다.

argument는 `ObjectMapper`로 JSON 직렬화하고 문자열 연결로 JSON을 만들지 않는다.

## 10. Validation report

stage 순서는 다음으로 고정한다.

```text
COMPILE
APPLICATION_CONTEXT
MCP_INITIALIZE
MCP_TOOLS_LIST
MCP_TOOL_CALL
```

성공 summary는 Tool name과 mock contract 검증 성공 여부만 포함한다. argument, query, header,
body, synthetic secret, raw MCP response는 report에 넣지 않는다. 실패 summary는 기존 bounded,
masked `GeneratorException` 규칙을 사용한다.

## 11. 오류 처리

- 설정 shape 또는 bounded value 위반: `CliConfigurationException`, exit code 2로 generation 시작 전 실패
- operation 또는 input semantic mismatch: 신규 `VALIDATION_ARGUMENT_INVALID`,
  `TOOL_MODEL_VALIDATE` stage와 exit code 3으로 source generation 전 실패
- mock server bind 실패: 신규 `MCP_TOOL_CALL_FAILED`, exit code 5
- application이 mock upstream을 호출하지 않음: timeout 후 `MCP_TOOL_CALL_FAILED`
- upstream request mismatch: generic safe summary로 `MCP_TOOL_CALL_FAILED`
- JSON-RPC 또는 result mismatch: `MCP_TOOL_CALL_FAILED`
- cleanup 실패: 주 실패에 suppressed로 보존하되 secret 없는 safe summary만 기록

실패한 output directory와 `UNVERIFIED` report는 기존 정책대로 보존하고 ZIP은 만들지 않는다.

## 12. 보안

- 검증 중 외부 provider를 호출하지 않고 loopback URI만 허용한다.
- user configuration으로 mock host나 port를 지정할 수 없다.
- 실제 environment secret을 읽거나 forwarding하지 않는다.
- synthetic secret은 매 validation process에만 주입하고 파일에 기록하지 않는다.
- validation argument는 code, build script, shell command에 삽입하지 않고 JSON으로 전송한다.
- argument depth, collection size, string length, request/response byte와 timeout을 제한한다.
- mock server와 application process는 성공·실패·interrupt 모든 경로에서 종료한다.

## 13. 테스트 전략

### 13.1 Configuration과 domain

- valid nested arguments parsing
- missing validation block와 disabled/unknown operation 거부
- unknown/missing required input 거부
- primitive, enum, bounds, length, pattern, array, nested object 검증
- depth, collection, string limit과 null 거부
- immutable defensive copy

### 13.2 MCP client

- valid `tools/call` JSON and session header
- JSON-RPC error, wrong id, missing result, `isError=true`
- missing/non-JSON/mismatched content
- timeout, response overflow, SSE/JSON content type 경계

### 13.3 Mock upstream과 validator

- path/query/header/body와 repeated query key 성공
- method, missing/extra binding, secret, body mismatch 실패
- stage success/failure/skip 순서와 duration
- application/mock process cleanup
- argument와 synthetic secret이 summary에 남지 않음

### 13.4 Generated project와 CLI

- weather fixture의 실제 generated project compile/test
- 실제 Spring AI endpoint에서 `initialize`, `tools/list`, `tools/call`
- mock upstream에서 path/query/header/body와 query/header API Key 확인
- success report의 5개 stage와 ZIP 생성
- call mismatch 시 `UNVERIFIED` report와 ZIP 미생성
- 동일 input의 source checksum과 canonical source reproducibility 유지

production behavior는 먼저 실패하는 테스트로 확인한 뒤 최소 구현을 추가한다.

## 14. 복잡도

validation argument JSON 크기를 `A`, binding 수를 `B`, upstream request 크기를 `Q`, MCP
response 크기를 `R`이라 하면 expectation 생성과 schema 검증은 `O(A + B)`, 요청 비교는
`O(Q)`, MCP 결과 비교는 `O(R)`이다. 추가 메모리는 bounded argument, request, response를
보관하므로 `O(A + Q + R)`이며 각 항목은 설정 또는 1 MiB byte limit을 적용한다. 전체
wall-clock 시간은 기존 compile과 Spring startup이 지배한다.

## 15. 주의사항과 후속 경계

- 제약: 이 슬라이스는 대표 Tool 하나만 검증하며 모든 operation의 runtime 정확성을 증명하지 않는다.
- 위험: Spring AI의 Tool result envelope가 patch version에서 바뀌면 protocol adapter 검증을
  profile별로 분리해야 한다.
- 위험: mock expectation과 generated runtime이 같은 Tool IR을 사용하므로 IR 자체의 의미 오류는
  별도 OpenAPI golden test가 계속 방어해야 한다.
- 예외: response normalization과 HTTP 200 업무 오류는 raw JSON round-trip 성공 후 다음 P1
  슬라이스에서 구현한다.
- 예외: Windows wrapper 실행은 별도 P1 이슈 #2에서 다룬다.

## 16. 다음 P1 순서

1. `tools/call` mock validation
2. response normalization과 upstream error mapping
3. Java 17 compatibility profile
4. Spring AI 1.x generator
5. runtime metrics와 tracing
6. UI operation editor

validation report와 Dockerfile은 P0에서 이미 제공하므로 별도 P1 신규 구현 항목에서 제외한다.
