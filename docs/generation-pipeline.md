# 생성 파이프라인과 스키마 계약

## 하나의 Tool IR

[GenerationPlanner](../modules/application/src/main/java/io/gen2spring/mcp/application/planning/GenerationPlanner.java)는
호환 profile과 구현 방식을 검증하고 분석 결과에 operation 선택·이름·설명·secret·응답·실행 정책을 적용한다.
최종 Tool IR을 source generation, MCP input schema, 검증 expectation과 Runtime Metadata의 공통 기준으로 사용한다.
브라우저나 validator가 별도로 OpenAPI를 해석해 다른 Tool 계약을 만들지 않는다.

[GenerationPipeline](../modules/application/src/main/java/io/gen2spring/mcp/application/usecase/GenerationPipeline.java)의
흐름은 분석 → 계획·소스/metadata 기록 → 검증 workspace → 보고서 → 성공 시 ZIP이다.
Preview는 계획과 파일 목록을 제공하며 생성 프로젝트의 compile·기동·MCP 호출·ZIP 생성을 수행하지 않는다.
따라서 preview 성공은 배포 가능한 프로젝트 검증 성공이 아니다.

## OpenAPI 지원과 입력 의미

| 입력 | 현재 처리 |
| --- | --- |
| 3.0 nullable / 3.1 null union | 같은 canonical nullable 의미로 정규화 |
| optional nullable query/header | 생략과 명시적 null 모두 HTTP parameter를 생략. 문자열 `null`로 보내지 않음 |
| nullable root body | `body` 입력 하나로 보존. optional 생략은 body/content type 없음, 명시적 null은 JSON `null` 전송 |
| required nullable root body | null 허용, key 생략은 provider 호출 전에 거부 |
| non-null object body | 기존 property 평탄화 유지 |
| `maxItems` / `uniqueItems` | 입력 최대 256 범위. unique는 명시적인 지원 maxItems 필요 |
| object `allOf` / 3.1 `$ref` sibling | 호환되는 제약의 교집합. 충돌·빈 교집합은 지원 불가 |
| `oneOf` / `anyOf` | 각각 정확히 한 branch / 하나 이상 branch 만족. 조합 위치는 JSON 값으로 보존 |

조합은 한 composition 8 branch, 깊이 16, operation당 전체 64 branch로 제한한다.
구조적 uniqueness는 object key 순서를 무시하고 array 순서는 유지하며 `1`과 `1.0`을 같은 숫자로 취급한다.
Nullable path, required nullable query/header, remote/recursive ref, discriminator와 budget 초과는
endpoint별 안정적인 사유로 표시하고 생성 대상에서 제외한다. 제한을 조용히 버리거나 값을 강제 보정하지 않는다.

[3.0](../swagger-3.0.yml)과 [3.1](../swagger-3.1.yml) fixture는 같은 의미의 입력을 두 버전으로 재현한다.
개수만 비교하지 않고 support 판정, issue code, Tool schema, 원본 HTTP 요청 의미를 함께 비교한다.
전체 serialization 제한은 [사용자 가이드](user-guide.md#지원-범위와-제한)를 따른다.

## 응답과 실행 정책

정규화 미설정은 provider JSON을 유지한다. 정규화 설정 시 `data`와 선택적 `page`·`provider` envelope를 만든다.
2xx에서 지정 pointer가 없거나 타입이 틀리면 raw body fallback 대신 `UPSTREAM_PROTOCOL`로 실패한다.
Non-2xx 응답은 metadata 추출 실패 때문에 원래 HTTP 오류 분류를 잃지 않는다.
JSON Pointer는 최대 256자·32 token이며 동적 표현식이나 타입 coercion을 제공하지 않는다.

Typed output은 지원되는 JSON object 성공 응답에 한정한다. GET retry와 pagination은 설정한 횟수·페이지·항목
한도뿐 아니라 전체 시간 예산도 지켜야 한다. WebFlux Async에서도 cancellation, backpressure와 단일 종료
telemetry 계약을 유지한다. 정책 값과 오류 결과는 [사용자 가이드](user-guide.md#응답과-실행-정책)에 있다.

## 검증과 실패

1. COMPILE: 선택한 build tool과 검증된 target JDK로 생성 프로젝트 compile/test/package
2. APPLICATION_CONTEXT: 같은 target JDK로 Boot JAR 기동
3. MCP_INITIALIZE: 실제 `/mcp` 초기화
4. MCP_TOOLS_LIST: 최종 Tool 이름·설명·schema 비교
5. MCP_TOOL_CALL: 명시한 대표 argument로 호출하고 loopback upstream의 wire/result 검증

대표 argument는 임의 합성하지 않는다. 사용자가 지정한 값을 최종 schema로 검사하고 HTTP expectation은
Tool IR에서 파생한다. retry·pagination이 있으면 순서 있는 여러 upstream interaction을 검증할 수 있으므로
최초 P1 설계의 '항상 HTTP 한 번' 제한을 현재 전체 파이프라인에 적용하지 않는다.
실제 provider와 실 secret은 검증에 사용하지 않는다.

선행 실패는 후속 단계를 건너뛰게 하고 `UNVERIFIED` 보고서를 남긴다. `VALIDATED`일 때만 ZIP을 만든다.
검증용 build cache·process log는 배포 디렉터리에 포함하지 않는다. 기존 output/ZIP을 덮어쓰지 않는 것도 계약이다.

## 결정성과 Runtime Metadata

같은 입력·설정·profile에서 archive 경로 순서·timestamp·file mode와 source checksum을 결정적으로 기록한다.
보고서의 duration/stdout/stderr 측정값은 결정성 비교에서 정규화하지만 나머지 차이를 숨기지 않는다.
`RUNTIME_METADATA.json`은 최종 Tool IR에서 생성하고 canonical codec으로 검증한다. Framework 의존성과
secret 값·환경변수 이름·사용자/작업 식별자·로컬 경로를 Catalog 문서로 운반하지 않는다.
Hosted 게시와 소비 경계는 [Hosted 플랫폼](hosted-platform.md), [Managed Runtime](managed-runtime.md)을 따른다.
