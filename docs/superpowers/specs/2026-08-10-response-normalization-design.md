# P1 Response Normalization과 Upstream Error Mapping 설계

- 상태: 승인됨
- 작성일: 2026-08-10
- 기준 문서: `docs/prd.md` FR-7.1, FR-7.2, FR-7.3, 개발 우선순위 P1
- 선행 기능: P1 대표 `tools/call` mock validation

## 1. 목표

OpenAPI provider의 JSON 응답에서 실제 데이터와 provider metadata를 결정적으로 추출하고,
HTTP 200 업무 오류와 HTTP·transport 오류를 안전한 MCP 오류 결과로 변환한다. 동일한 응답
정책을 generator domain, generated runtime, validator가 공유해 생성 시점의 계약과 실행 시점의
동작이 어긋나지 않게 한다.

완료 기준은 다음과 같다.

- operation별 response normalization 설정을 strict YAML로 입력할 수 있다.
- 설정된 operation은 provider envelope를 고정된 success envelope로 변환한다.
- HTTP 200 업무 오류와 HTTP·transport 오류는 고정된 provider error envelope로 변환된다.
- 예상된 오류는 JSON-RPC transport 오류가 아니라 MCP Tool `isError=true` 결과가 된다.
- raw body, header, stack trace, secret은 MCP 결과와 로그에 포함되지 않는다.
- normalization을 설정하지 않은 operation의 성공 응답은 기존 raw JSON 동작을 유지한다.
- 생성 프로젝트의 compile, context, MCP 성공·오류 contract와 CLI journey가 검증된다.

## 2. 범위

### 2.1 포함

- operation별 `responseNormalization` 설정
- bounded RFC 6901 JSON Pointer 검증과 실행
- success code와 typed success value 비교
- data, total count, provider code와 message 추출
- HTTP 200 업무 오류 판정
- HTTP 4xx·5xx, timeout, 연결 실패, invalid response 분류
- 고정 success·error envelope
- MCP `isError` 변환
- 대표 `tools/call` mock의 raw provider response와 expected MCP result 분리
- manifest, generated README, root README 동기화
- 기존 P0 raw response 회귀 검증

### 2.2 제외

- typed output DTO
- retry 실행
- 자동 pagination 또는 page 반복 호출
- response schema 추론과 coercion
- JSONPath, JMESPath 같은 별도 query language
- runtime metrics와 OpenTelemetry span 생성
- Spring AI 1.x generator와 Java 17 profile
- UI operation editor

이번 슬라이스는 다음 P1 profile과 observability가 재사용할 공통 응답 계약까지만 만든다.

## 3. 설계 원칙

### 3.1 공통 IR 우선

응답 정책은 Spring AI renderer 안에 숨기지 않고 Tool IR에 포함한다. Spring AI profile별
adapter는 공통 결과를 해당 MCP runtime API로 변환하는 역할만 맡는다.

```text
YAML responseNormalization
        |
        v
GenerationRequest
        |
        v
Tool IR ResponseNormalizationPolicy
        |
        +------------------------+
        |                        |
        v                        v
generated metadata      validation expectation
        |                        |
        v                        v
ResponseNormalizer      raw mock response
        |
        v
NormalizedSuccess | ProviderError
        |
        v
Spring AI profile adapter
        |
        v
MCP Tool result
```

### 3.2 안정적인 외부 결과

Provider별 필드명을 MCP 최상위 필드에 그대로 노출하지 않는다. 정규화된 성공 결과는 항상
`data`, 선택적 `page`, 선택적 `provider`로 구성한다. 오류 결과는 항상 `error` 객체 하나를
최상위에 둔다.

### 3.3 Fail-closed

2xx response에서 설정한 pointer가 없거나 예상 타입과 다르면 원본 body를 대신 반환하지 않는다.
설정과 실제 provider 계약이 달라졌음을 나타내는 `UPSTREAM_PROTOCOL` 오류로 종료한다. 이미
실패가 확정된 non-2xx response는 HTTP status category를 유지하며 provider metadata 추출 실패로
오류 category를 덮어쓰지 않는다.

## 4. 설정 계약

```yaml
operations:
  - operationId: getForecast
    enabled: true
    responseNormalization:
      dataPath: /response/body/items/item
      successCodePath: /response/header/resultCode
      successValues: ["00"]
      errorMessagePath: /response/header/resultMsg
      totalCountPath: /response/body/totalCount
```

`responseNormalization`은 operation별 선택 항목이다. 내부 설정 모델은 다음 값을 보존한다.

```java
public record ResponseNormalizationPolicy(
        String dataPointer,
        String successCodePointer,
        List<Object> successValues,
        String errorMessagePointer,
        String totalCountPointer) {}
```

### 4.1 Pointer 규칙

- pointer는 RFC 6901 JSON Pointer 문법을 사용한다.
- 명시된 pointer는 `/`로 시작해야 한다. 문서 root는 pointer 생략으로 표현한다.
- 최대 길이는 256자다.
- 최대 token 수는 32개다.
- `~0`, `~1` 이외의 `~` escape와 ISO control character를 거부한다.
- pointer 평가는 object property와 array index를 모두 지원한다.
- array index는 선행 0이 없는 0 이상의 십진수만 허용한다.
- `-` array append token은 조회에서 거부한다.

### 4.2 필드 조합 규칙

- `successCodePath`와 `successValues`는 함께 존재하거나 함께 없어야 한다.
- `successValues`는 1개 이상 16개 이하다.
- success value는 null이 아닌 JSON string, number, boolean만 허용한다.
- string success value는 128자 이하이고 control character를 포함할 수 없다.
- number 비교는 JSON 숫자 동치성을 적용하되 string과 number는 서로 같지 않다.
- `dataPath`가 없으면 전체 parsed body를 `data`로 사용한다.
- `totalCountPath`가 있으면 값은 0 이상 `Long.MAX_VALUE` 이하의 integral JSON number여야 한다.
- success code는 원본 JSON scalar 타입을 유지한다.
- error message pointer의 값은 JSON string이어야 한다.
- 설정 property의 unknown field는 CLI parse 단계에서 거부한다.

### 4.3 구조 충돌 규칙

Validator가 raw mock response를 합성할 수 있도록 scalar mapping pointer의 구조 충돌을 생성
전에 거부한다. success code, error message, total count pointer가 다른 pointer의 ancestor이면
하나의 JSON 값이 scalar와 container여야 하므로 유효하지 않다. `dataPath`는 container ancestor가
될 수 있다.

## 5. Tool IR과 생성 metadata

`GenerationRequest.OperationSelection`에 nullable `ResponseNormalizationPolicy`를 추가한다.
Policy layer는 CLI를 통하지 않는 호출도 방어할 수 있도록 pointer, value, 조합 규칙을 다시
검증한 뒤 `McpToolDefinition.HttpExecutionDefinition`에 immutable policy를 연결한다.

Generated operation metadata는 다음 정보를 literal로 보존한다.

- data pointer
- success code pointer와 typed success values
- error message pointer
- total count pointer

동적 expression, reflection 기반 path lookup, runtime config override는 제공하지 않는다. Manifest는
operation별 normalization policy를 deterministic key order로 기록한다. 이 값들은 secret이 아니며
source checksum과 재현성 판단에 포함된다.

기존 Jackson과 JDK API만 사용하며 신규 외부 runtime·generator dependency를 추가하지 않는다.

## 6. 성공 결과 계약

설정된 operation의 성공 결과는 다음 형태다.

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

규칙은 다음과 같다.

- `data`는 항상 존재한다. `dataPath`가 없으면 전체 parsed body다.
- 빈 2xx body는 success code나 다른 pointer가 없을 때 `data: null`로 반환한다.
- `page`는 `totalCountPath`가 설정된 경우에만 존재한다.
- `provider`는 success code 또는 message pointer가 설정된 경우에만 존재한다.
- `provider.code`와 `provider.message`는 각 pointer가 설정된 경우에만 존재한다.
- object field 순서는 `data`, `page`, `provider`로 고정한다.
- 원본 response body의 나머지 필드는 결과에 포함하지 않는다.

Normalization policy가 없는 operation의 2xx JSON과 빈 body는 기존 raw `JsonNode`와 JSON null
동작을 유지한다. 다만 HTTP·transport 오류는 policy 유무와 관계없이 새 error contract를 따른다.

## 7. 오류 결과 계약

예상된 provider와 runtime 오류는 MCP Tool `isError=true`와 하나의 JSON text content로 반환한다.

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

`error`의 field는 항상 존재한다. `providerCode`는 원본 JSON string, number, boolean 타입을
유지한다. `providerMessage`는 string 또는 null, `httpStatus`는 integer 또는 null이다. 알 수 없는
값은 JSON null로 기록한다.

### 7.1 Category

```text
PROVIDER_BUSINESS
UPSTREAM_CLIENT
UPSTREAM_SERVER
UPSTREAM_TIMEOUT
UPSTREAM_UNAVAILABLE
UPSTREAM_PROTOCOL
LOCAL_RESOURCE
```

### 7.2 Retryable

- `PROVIDER_BUSINESS`: false
- `UPSTREAM_CLIENT`: 408, 425, 429만 true
- `UPSTREAM_SERVER`: true
- `UPSTREAM_TIMEOUT`: true
- `UPSTREAM_UNAVAILABLE`: true
- `UPSTREAM_PROTOCOL`: false
- `LOCAL_RESOURCE`: false

### 7.3 Trace ID

각 실행은 JDK `SecureRandom`의 16-byte value를 32-character lowercase hex로 표현한 trace ID를
가진다. 이후 OpenTelemetry 슬라이스에서 active span의 trace ID가 있으면 그것을 우선 사용하고,
없으면 동일한 fallback을 유지한다. Error 결과에는 원격 provider가 보낸 임의의 trace ID를 신뢰해
사용하지 않는다.

## 8. 실행 순서와 우선순위

Generated executor의 판정 순서는 다음으로 고정한다.

1. concurrency·queue admission 실패를 `LOCAL_RESOURCE`로 반환한다.
2. total timeout을 `UPSTREAM_TIMEOUT`으로 반환한다.
3. DNS, connect, I/O 실패를 `UPSTREAM_UNAVAILABLE`로 반환한다.
4. bounded body를 읽는다. 기본 한도는 1 MiB다.
5. HTTP 4xx를 `UPSTREAM_CLIENT`로 반환한다.
6. HTTP 5xx를 `UPSTREAM_SERVER`로 반환한다.
7. HTTP 3xx와 그 밖의 non-2xx를 `UPSTREAM_PROTOCOL`로 반환한다.
8. 2xx response의 content type과 JSON syntax를 검증한다.
9. success code pointer를 평가하고 typed success value와 비교한다.
10. 일치하지 않으면 `PROVIDER_BUSINESS`를 반환한다.
11. data, total count, provider metadata를 추출한다.
12. success envelope를 반환한다.

HTTP status 판정은 업무 code 판정보다 우선한다. Non-2xx JSON body는 provider code와 message를
추출하는 데만 사용할 수 있고 data extraction에는 사용하지 않는다. Non-2xx body가 비어 있거나
JSON이 아니면 status 기반 오류는 유지하고 provider metadata는 null로 둔다.

2xx response가 다음 조건을 만족하면 `UPSTREAM_PROTOCOL`이다.

- body가 있는데 JSON content type이 아니다.
- JSON이 아니거나 trailing token이 있다.
- 설정한 pointer가 없다.
- success code가 scalar가 아니거나 provider message가 string이 아니다.
- total count가 음수, 소수, string 또는 범위 밖 integer다.
- response body가 configured byte limit을 초과한다.

JSON content type은 `application/json`과 `application/*+json`을 허용하며 charset parameter를
허용한다.

예상하지 못한 generated runtime 결함은 provider error로 위장하지 않는다. Profile adapter는 고정된
안전 메시지의 JSON-RPC internal error를 만들고 stack trace를 client에 보내지 않는다.

## 9. Provider message와 secret 안전성

- Provider message는 최대 512 Unicode code point로 제한한다.
- NUL과 ISO control character가 있으면 고정된 안전 메시지로 대체한다.
- 현재 operation의 `SecretBinding`에서 실제로 읽은 secret value를 모두 masking한다.
- Authorization, API key, serviceKey, clientSecret, cookie와 사용자 지정 secret 이름을 masking한다.
- raw body, raw header, URI query, environment, exception message, stack trace는 결과에 넣지 않는다.
- Provider message를 log에 기록할 때도 동일한 sanitizer를 사용한다.
- Sanitizer가 실패하면 원문 대신 `Provider returned an unsafe error message`를 사용한다.

## 10. MCP profile adapter

Generated runtime은 profile-neutral 결과를 만든다.

```java
sealed interface OperationOutcome permits NormalizedSuccess, ProviderError {}
```

Spring AI 2.x adapter는 `NormalizedSuccess`를 `isError=false`, `ProviderError`를 `isError=true`인
단일 JSON text content로 변환한다. Tool business 오류를 Java exception message에 직렬화하지
않는다. 이후 Spring AI 1.x adapter도 동일한 JSON payload를 사용하고 profile API 차이만 흡수한다.

## 11. Validation contract

기존 `ExpectedToolCall`은 arguments뿐 아니라 raw mock response와 expected normalized MCP result를
보존하도록 확장한다. `UpstreamCallExpectation`은 request expectation과 response fixture를 함께
가지되 response fixture는 request 비교 결과에 영향을 주지 않는다.

Policy가 있는 대표 호출의 raw success response는 pointer 구조를 따라 결정적으로 합성한다.

- success code는 첫 번째 configured success value다.
- error message는 `NORMAL_SERVICE`다.
- total count는 `1`이다.
- data leaf는 `{"validated": true, "operationId": "..."}`다.
- data pointer가 다른 metadata pointer의 ancestor이면 container를 유지하고 expected result를 실제
  합성 tree에서 계산한다.

MCP client는 hard-coded payload를 비교하지 않고 `ExpectedToolCall`의 expected result와 canonical
JSON equality를 비교한다. 이 representative call은 기존 exactly-one upstream request gate를
유지한다.

오류 분기는 생성 프로젝트 test와 profile contract fixture에서 검증한다. Production validation의
대표 호출을 여러 번 실행하지 않으므로 기존 exactly-one 의미와 validation stage 이름은 바뀌지
않는다.

## 12. 테스트 전략

### 12.1 Domain과 CLI

- immutable policy와 typed success values
- unknown property
- pointer 최대 길이와 최대 token 수
- invalid escape, array append, control character
- success code/value presence mismatch
- success value count와 string bound
- scalar pointer 구조 충돌

### 12.2 Policy와 renderer

- operation selection에서 Tool IR로 정확한 policy 전달
- CLI 우회 호출에 대한 semantic 재검증
- generated metadata의 deterministic literal
- policy가 없는 기존 source golden 유지
- manifest와 generated README의 policy 기록

### 12.3 Generated runtime

- normalized success와 optional section omission
- raw success backward compatibility
- HTTP 200 business error
- HTTP 400, 408, 425, 429, 500
- timeout, connection refusal, executor saturation
- empty body, invalid content type, invalid JSON, trailing token
- missing pointer와 type mismatch
- escaped object property와 array index pointer
- large integer와 exact decimal success value
- provider message bound, control character, secret masking
- unknown internal exception redaction

### 12.4 MCP와 CLI journey

- success result의 `isError=false`와 exact normalized JSON
- provider error의 `isError=true`와 exact safe JSON
- JSON-RPC internal error와 provider error의 구분
- 대표 raw mock response에서 expected normalized result 검증
- generated project compile, ApplicationContext, MCP initialize, tools/list, tools/call
- validation report, manifest, ZIP checksum 정합성
- 기존 P0 raw JSON fixture 회귀

최종 검증 명령은 다음과 같다.

```bash
mise exec -- ./gradlew clean test integrationTest :generator-cli:installDist \
  --no-daemon --non-interactive --rerun-tasks
git diff --check
```

## 13. 복잡도와 한계

Response byte 수를 `B`, 평가하는 pointer token 합을 `P`, success value 수를 `V`라 하면 parsing과
normalization 시간은 `O(B + P + V)`, 추가 메모리는 JSON tree와 result tree 때문에 `O(B)`다.
기본 `B`는 1 MiB, 각 pointer는 32 token, `V`는 16으로 제한한다.

- 제약: JSON body만 지원한다.
- 제약: normalization은 단일 response 안의 값만 추출하며 계산식이나 field rename DSL은 제공하지 않는다.
- 위험: provider message가 실제 secret을 변형해 반환하면 exact value masking만으로 탐지하지 못할 수 있다.
- 위험: data pointer가 넓은 ancestor이면 provider metadata가 data 안에도 포함될 수 있다.
- 예외: retry와 pagination은 오류의 `retryable` 표시와 total count 노출까지만 하고 실행하지 않는다.
- 예외: active OpenTelemetry span 연동은 후속 metrics와 tracing 슬라이스에서 구현한다.

## 14. P1 후속 순서

1. Response normalization과 upstream error mapping — 이 설계
2. Compatibility registry와 Spring AI 2.x Java 17 profile
3. Spring AI 1.1.8, Spring Boot 3.5.16 기반 Java 17·21 generator profile
4. Runtime metrics와 OpenTelemetry tracing
5. Windows validation host, GitHub issue #2
6. Stateless local Web UI와 Generator API adapter
7. 전체 4-profile compile·MCP matrix와 P1 인수 검증

Validation report와 Dockerfile은 P0에서 이미 제공하므로 신규 P1 구현 항목에서 제외한다.

## 15. 공식 참고 자료

- RFC 6901 JSON Pointer: <https://www.rfc-editor.org/rfc/rfc6901>
- MCP Tool result: <https://modelcontextprotocol.io/specification/2025-06-18/server/tools>
- Spring AI MCP server: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html>
