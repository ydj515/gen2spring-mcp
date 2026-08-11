# P1 Follow-up Completion Design

## 1. 목적

PRD 20장의 명시적 P1 기능은 이미 구현되어 있다. 이 설계는 README에 후속 P1로 남은 다음 네 기능을
완료해 P1 경계를 닫는다.

- supported response schema 기반 typed output DTO
- operation별 bounded retry 실행
- operation별 bounded pagination 실행
- Windows validation host 지원

기존 configuration과 generated project는 동작과 산출물 의미를 유지한다. 새 기능은 operation별 opt-in이고,
모호하거나 안전하게 표현할 수 없는 계약은 생성 전에 fail-closed한다.

## 2. 범위

포함:

- OpenAPI 3.0 success response schema normalization
- generic JSON과 typed DTO output 선택
- GET operation의 status/network retry, exponential backoff, Retry-After
- query parameter와 RFC 6901 pointer 기반 cursor/page-value pagination
- retry와 pagination을 반영한 exact upstream validation sequence
- Spring AI 1.1 / 2.0, Java 17 / 21 parity
- Windows의 `gradlew.bat` build validation과 target JDK application boot
- emitter-neutral Tool source generation port와 Spring AI 1/2 adapter
- CLI, manifest, generated README, root README와 PRD 동기화

제외:

- response schema의 remote/recursive/composed/discriminator/OpenAPI 3.1 지원
- retry 가능한 mutation operation
- offset을 runtime이 임의 계산하는 provider-specific pagination
- streaming pagination, lazy iterator, partial/truncated success
- Windows container image build와 Windows container image 생성
- direct MCP Java SDK project/profile과 `McpJavaSdkToolEmitter` production 구현
- async/WebFlux/Maven/STDIO

## 3. 대안과 결정

### 3.1 선택: 명시적 operation policy

generation configuration이 output, retry, pagination 정책을 명시한다. OpenAPI는 response DTO shape와
기존 HTTP binding의 source of truth로만 사용한다. provider-specific 실행 의미를 description이나 parameter
이름으로 추론하지 않는다.

장점:

- 현재 strict parser와 Tool IR 경계에 자연스럽게 추가된다.
- 설정이 없는 operation의 runtime과 validation이 byte-level 의미까지 유지된다.
- retry/pagination 오판으로 인한 중복 provider 호출을 막는다.

단점:

- 사용자가 provider 문서를 보고 pointer와 bound를 입력해야 한다.

### 3.2 기각: OpenAPI parameter/description 자동 추론

`page`, `cursor`, `next` 같은 이름을 추론하면 초기값, 종료 조건, token 의미를 증명할 수 없다. OpenAPI
standard에는 일반 pagination 실행 의미가 없고 retry safety도 operation method만으로 충분하지 않다.

### 3.3 기각: 범용 expression/policy engine

JSONPath, expression language, custom retry predicate를 도입하면 configuration 공격 표면, 결정성, renderer
parity와 검증 oracle 복잡도가 크게 증가한다. P1에서는 bounded typed policy만 제공한다.

### 3.4 Tool emitter architecture

`McpToolDefinition`은 framework-neutral canonical IR로 유지한다. emitter는 Tool definition의 subtype이나
상속 계층이 아니라 `GenerationContext`가 가진 Tool 목록을 소비하는 output port다.

```text
McpToolDefinition
        |
        v
ToolEmitter
├── SpringAi2ToolEmitter
├── SpringAi1ToolEmitter
└── McpJavaSdkToolEmitter  (separate follow-up runtime family)
```

P1에서 domain port와 두 Spring AI adapter를 실제 구현한다.

```java
public interface ToolEmitter {
    GeneratedToolSources emit(GenerationContext context);
}

public record GeneratedToolSources(Map<String, byte[]> files) {}
```

`GeneratedToolSources`는 forward-slash 상대 경로만 허용하고 absolute path, empty segment, `.`, `..`,
backslash와 control character를 거부한다. path는 code-point order로 정렬하고 byte array는 construction과 accessor
양쪽에서 복제해 emitter와 project generator 사이에 mutable source buffer를 공유하지 않는다.

`SpringAi1ProjectGenerator`와 `SpringAi2ProjectGenerator`는 project scaffold를 소유하고 대응 `ToolEmitter`에
generated Java/runtime/test source 생성을 위임한다. 기존 `JavaSourceRenderer`는 각 adapter 내부 구현으로
남고 registry/profile 선택은 계속 `ProjectGenerator` 경계에서 수행한다. 이 분리는 framework-neutral IR에
Spring AI annotation/SDK type이 유입되는 것을 막는다.

`McpJavaSdkToolEmitter`는 같은 port의 세 번째 구현 방향으로 예약한다. 하지만 direct SDK server는 Spring Boot,
Spring AI, MVC를 필수로 가정하는 현재 `TargetPlatform`, project scaffold, application-context validation을
그대로 사용할 수 없다. SDK version, transport bootstrap, dependency/project layout, compatibility profile,
application readiness 계약이 별도 설계로 확정되기 전에는 빈 emitter, 거짓 profile, Spring AI scaffold 재사용을
추가하지 않는다.

## 4. Configuration 계약

기존 operation selection에 세 optional object를 추가한다.

```yaml
operations:
  - operationId: listForecasts
    enabled: true
    output:
      mode: TYPED
    retry:
      statusCodes: [429, 502, 503, 504]
      networkErrors: true
      maxRetries: 2
      initialBackoffMillis: 100
      maxBackoffMillis: 1000
      respectRetryAfter: true
    pagination:
      requestParameter: cursor
      initialValue: first
      itemsPath: /response/body/items
      nextValuePath: /response/body/nextCursor
      maxPages: 10
      maxItems: 1000
```

### 4.1 Output

`output.mode`은 `GENERIC_JSON` 또는 `TYPED`다. object가 없으면 `GENERIC_JSON`이다. unknown field/value,
explicit null, 중복 YAML key는 기존 configuration 오류로 거부한다.

### 4.2 Retry

retry object가 없으면 실행하지 않는다. 값의 계약은 다음과 같다.

| field | bound / default |
|---|---|
| `statusCodes` | unique integer 400..599, 최대 16개, default empty |
| `networkErrors` | boolean, default false |
| `maxRetries` | 1..3, required |
| `initialBackoffMillis` | 1..5000, required |
| `maxBackoffMillis` | initial 이상, 최대 10000, required |
| `respectRetryAfter` | boolean, default false |

status와 network error 조건이 모두 비어 있으면 거부한다. P1 retry는 GET operation에만 허용한다.
`Retry-After`는 delta-seconds만 허용하고 HTTP-date는 지원하지 않는다. 음수, overflow, invalid value는
무시하고 exponential backoff를 사용한다. Retry-After와 exponential backoff 중 큰 값을 선택하되
`maxBackoffMillis`와 남은 total timeout을 넘지 않는다.

### 4.3 Pagination

pagination object가 없으면 한 번만 요청한다.

| field | bound / default |
|---|---|
| `requestParameter` | 기존 query parameter 또는 query API-key가 아닌 parameter, required |
| `initialValue` | bounded string/integer JSON scalar, optional |
| `itemsPath` | non-empty RFC 6901 pointer, array target, required |
| `nextValuePath` | non-empty RFC 6901 pointer, string/integer/null target, required |
| `maxPages` | 2..20, required |
| `maxItems` | 1..2000, required |

pagination은 GET operation에만 허용한다. `requestParameter` schema는 supported string 또는 integer scalar여야
한다. OpenAPI required parameter면 `initialValue`도 반드시 있어야 하고, optional parameter에서 초기값이
없으면 첫 요청에서 parameter를 생략한다. 초기값과 모든 next value는 parameter schema/enum/bound를 만족해야
한다. parameter는 Tool input에서 제거하고 INTERNAL binding으로 전환한다. 사용자가 같은 parameter를 Tool
argument로 공급하거나 SERVER_SECRET override로 지정할 수 없다. 이후에는 response의 next value를 wire query
value로 사용한다.

다음 값이 missing/null/empty string이면 종료한다. number는 plain decimal wire value로 정규화한다. boolean,
object, array, 부동소수는 거부한다. 이전과 동일한 next value가 다시 나오거나 이미 사용한 값이 반복되면
`UPSTREAM_PROTOCOL`로 종료한다.

각 page의 `itemsPath`는 array여야 한다. 첫 response tree를 aggregate envelope로 사용하고 해당 array에 이후
item을 순서대로 append한다. aggregate의 `nextValuePath`는 마지막 page의 종료 값으로 갱신해 완료된 결과가
stale next token을 노출하지 않게 한다. page-local envelope의 다른 field는 첫 response 값을 유지한다. `maxPages`,
`maxItems`, aggregate 1 MiB 중 하나에 도달한 시점에 유효한 next value가 남아 있으면 partial success를
반환하지 않고 `LOCAL_RESOURCE` error로 종료한다.

retry는 page별 HTTP request에 적용한다. retry attempt는 page count와 item count를 증가시키지 않는다.

## 5. OpenAPI와 Tool IR

### 5.1 Success response schema

`OpenApiDocument.ApiOperation`에 nullable `successResponse` schema를 추가한다. analyzer는 다음 조건을 모두
만족하는 success response body만 정규화한다.

- explicit 2xx 또는 `2XX`
- body가 있는 success response가 모두 `application/json` 단일 media type
- body schema가 서로 structural equality
- 기존 supported schema subset

body 없는 success response만 존재하면 schema는 null이다. body schema가 여러 개이고 다르면 operation을
unsupported로 표시한다. default response를 success schema로 추론하지 않는다.

### 5.2 Output schema resolution

`OutputDefinition`은 mode, nullable provider response schema와 resolved result shape를 가진다. `TYPED` 선택 시:

1. operation success schema가 존재해야 한다.
2. response normalization `dataPath`가 있으면 schema tree에서 같은 RFC 6901 token을 따라간다.
3. pagination이 있으면 `itemsPath`와 `nextValuePath`도 schema tree에서 각각 array와 nullable scalar로
   확인한다.
4. normalization이 없으면 result shape는 success response schema 자체다.
5. normalization이 있으면 result shape는 runtime과 동일한 `{data, page?, provider?}` envelope다. `data`는
   resolved data schema, `page.totalCount`는 non-negative integer, `provider.code`는 success-code pointer의
   scalar schema, `provider.message`는 string이다.
6. pointer가 schema에 존재하지 않거나 runtime shape와 schema 계약이 모호하면 생성 전에 거부한다.

typed DTO record 이름은 기존 Tool name/operation ID sanitizer와 collision registry를 재사용한다. JSON property
이름은 `@JsonProperty`로 원본을 보존한다. nested object/array/enum/constraint 처리는 input DTO의 기존
supported subset과 같은 규칙을 사용한다. nullable과 required는 response deserialization 의미로 보존한다.

`McpToolDefinition.OutputKind`에는 `TYPED_DTO`를 추가하고 provider schema와 final result shape를 별도
component로 보존한다.
generic mode에서 schema는 manifest/preview 설명에는 사용할 수 있지만 generated callback 반환 타입은 기존
`JsonNode` 계약을 유지한다.

## 6. Generated runtime

### 6.1 실행 순서

```text
Tool argument validation
  -> initial request binding
  -> page request
       -> retry loop
       -> bounded raw response
       -> status/media/error mapping
       -> page shape validation
  -> aggregate response
  -> response normalization
  -> typed DTO conversion or generic JsonNode
  -> MCP result serialization
```

provider response body는 attempt마다 기존 1 MiB bound를 적용한다. aggregate tree도 serialization 결과 기준
1 MiB를 넘을 수 없다. raw response, retry message, cursor value는 log/metric/error envelope에 포함하지 않는다.

### 6.2 Retry timing

기존 provider total timeout이 전체 page journey가 아니라 각 Tool call 전체의 monotonic deadline이 된다.
connect/response timeout도 남은 deadline보다 길게 설정하지 않는다. backoff sleep은 interruption을 보존하고
deadline을 넘기지 않는다. timeout/cancel/fatal Error identity와 telemetry single-completion 계약을 유지한다.

metric과 span은 다음처럼 기록한다.

- Tool span: 전체 retry/pagination journey 한 번
- provider span/timer: 실제 HTTP attempt마다 한 번
- retry/page/cursor/operation을 meter tag로 추가하지 않음
- retry/page count는 bounded numeric span attribute만 허용

### 6.3 Typed callback

generated executor는 normalization 이후 final result tree를 `ObjectMapper.treeToValue`로 typed result에
변환한다. normalization이 있으면 generated root result record도 `data`, optional `page`, optional `provider`
component를 가져 기존 normalized envelope와 정확히 일치한다. 변환 실패는 기존 result conversion category의
safe JSON-RPC internal error다. callback은 typed result를 다시 안전하게 JSON serialize해 MCP text content를
만들므로 MCP wire result shape는 generic mode와 동일한 JSON semantic을 유지한다.

## 7. Exact validation

`ExpectedToolCall`은 ordered `ExpectedUpstreamCall` list와 exact expected MCP result를 가진다. compatibility
constructor는 기존 한 건 expectation을 singleton list로 변환한다.

정책 미사용 operation은 현재 exactly-one contract를 그대로 검증한다. pagination/retry validation fixture는
각 expected request를 순서대로 명시한다.

- retry: 같은 page request가 정확한 횟수로 반복되고 configured intermediate response를 받는다.
- pagination: next value가 다음 query에 정확히 반영되고 aggregate result가 literal expectation과 일치한다.
- unexpected, missing, reordered, duplicate, late request는 validation failure다.
- 모든 expected response는 status/header/body/size bound를 construction 시 검증한다.

retry timing 자체는 wall-clock exact 값으로 검증하지 않는다. injected sleeper/clock unit test로 backoff,
Retry-After, deadline을 고정하고 end-to-end에서는 request order와 bounded completion을 검증한다.

fixture는 production renderer나 response normalizer를 oracle로 재사용하지 않고 supported schema에서 최소
유효 JSON 값을 생성하는 `SchemaFixtureFactory`로 파생한다. string pattern처럼 일반적으로 만족값을 구성할 수
없는 constraint는 typed/pagination validation fixture를 생성할 수 없으므로 generation validation 전에
fixed error로 거부한다.

- typed output fixture는 provider response schema와 final result shape를 독립 literal tree로 만든다.
- retry fixture는 configured status 중 가장 작은 값을 첫 response로 사용하고 다음 response를 성공으로 만든다.
- status가 없고 network error만 있으면 첫 interaction은 response 전 connection을 닫고 다음 interaction은
  성공한다.
- pagination fixture는 정확히 두 page를 만든다. 첫 page는 valid item과 non-empty next value, 두 번째 page는
  다른 valid item과 null next value를 가진다. string next value는 fixed bounded literal, integer next value는
  schema bound 안의 서로 다른 값으로 만든다.
- schema constraint 때문에 두 distinct page/token/item을 만들 수 없으면 validation fixture를 약화하지 않고
  configuration을 unsupported로 거부한다.

## 8. Windows validation host

### 8.1 Platform adapter

`ValidationHostPlatform`을 `POSIX`와 `WINDOWS`로 분리하고 production은 `os.name`을 한 번 정규화해 선택한다.
unknown host는 fixed value-free validation failure다. 테스트는 platform을 주입한다.

POSIX는 기존 계약을 그대로 사용한다.

- `gradlew`
- owner executable required
- hard-link snapshot/file-key/physical containment
- snapshot 직접 실행

Windows는 다음 계약을 사용한다.

- `gradlew.bat`
- executable bit 미검사
- regular file, non-symbolic/non-reparse, physical parent, stable file-key required
- 같은 validation root 안의 unpredictable ASCII filename으로 hard-link snapshot
- `%SystemRoot%\\System32\\cmd.exe`의 physical regular-file identity 확인
- current directory를 validation root로 두고 safe relative snapshot filename만 command에 사용
- `/D /E:OFF /V:OFF /S /C`로 environment expansion과 delayed expansion을 제한

Gradle argument는 generator가 소유한 fixed literal만 허용한다. target JDK home을 command string에 넣기 전에
Windows command metacharacter, control character, quote, percent를 거부한다. space는 quoted argument로
지원한다. validation workspace absolute path는 command string에 포함하지 않는다.

wrapper original/snapshot identity는 launch 직전 다시 확인한다. snapshot cleanup은 verified file-key가 같은
경우에만 수행한다. hard link 또는 stable file-key를 제공하지 않는 Windows filesystem은 fail-closed한다.

application boot는 shell을 사용하지 않고 verified target `bin/java.exe`를 `ProcessBuilder` argv로 직접
실행한다. Java runtime resolver는 Windows에서 `bin/java.exe`, POSIX에서 `bin/java`를 선택한다.

### 8.2 CI

Windows job은 Java 17과 21 target home을 명시하고 다음을 실제 수행한다.

- generated project compile/test/bootJar
- target `java.exe` application boot
- MCP initialize, tools/list, tools/call
- exact upstream request
- unsafe/replaced/symlinked wrapper fail-closed
- validation failure artifact 미발행

POSIX full acceptance는 기존 명령으로 유지한다.

## 9. Manifest, preview, README

manifest operation mapping은 다음 optional metadata를 deterministic key order로 포함한다.

- output mode와 typed schema checksum
- retry policy
- pagination policy

secret, cursor runtime value, response body는 포함하지 않는다. preview와 local operation editor는 같은 immutable
policy를 읽고 표시한다. generated README는 enabled policy, bounds, failure behavior를 operation별로 설명한다.

root README와 PRD는 네 기능을 완료된 P1로 이동하고 다음 제한을 명시한다.

- typed output supported schema subset
- retry GET-only와 최대 횟수
- pagination pointer/query/value/bounds
- Windows host의 NTFS-style hard-link/file-key와 safe path requirement

Windows issue #2는 구현 PR에 연결하고 Windows CI가 통과한 뒤 merge 시 닫는다.

## 10. 보안과 실패 계약

- configuration parser는 unknown/null/duplicate/type mismatch를 거부한다.
- retry status/body/header와 pagination token은 safe error/log에 포함하지 않는다.
- Retry-After는 bounded numeric delta만 사용한다.
- pagination parameter는 user input/secret과 중복될 수 없다.
- typed output property는 generated Java source를 탈출할 수 없고 raw JSON name만 annotation에 보존한다.
- Windows shell에는 user-controlled command, environment name, absolute workspace path를 넣지 않는다.
- 모든 새 semantic/configuration error는 fixed non-leaking message와 기존 stage/code를 사용한다.
- fatal `Error`, interruption flag, executor saturation, late completion, telemetry single-stop 계약을 유지한다.

## 11. 성능과 bound

`P`를 page 수, `R`을 retry attempt 수, `B`를 page response bytes, `I`를 aggregate item 수라 하면:

- network attempt: 최대 `P * (1 + R)`, P <= 20, R <= 3
- runtime 시간: `O(P * (1 + R) + aggregate serialization)`
- runtime 공간: `O(B + I)`, page와 aggregate 모두 1 MiB hard bound
- DTO generation: supported response schema node 수에 선형이며 기존 OpenAPI/config 1 MiB bound 안에서 동작

상한은 configuration으로 늘릴 수 없다. 더 큰 workload는 P2 streaming/lazy pagination 설계가 필요하다.

## 12. 검증 전략

### 12.1 Domain/application/openapi/policy

- config default/strict typing/bounds/unknown field
- response schema equality, unsupported success variants
- output pointer schema resolution
- retry GET-only/status/backoff bounds
- pagination query binding/internalization/pointer/token/collision
- immutable IR와 compatibility constructors

### 12.2 Renderer/runtime

- AI1/Jackson 2와 AI2/Jackson 3 generated source parity
- generic output source byte compatibility characterization
- typed nested object/array/enum/nullable/constraints real compile/context/call
- retry status/network/Retry-After/deadline/interruption/fatal Error
- cursor/page value, aggregation, repetition, bound failures
- secret/log/trace/cardinality regression

### 12.3 Validation/CLI

- ordered request sequence and late duplicate rejection
- retry and pagination exact normalized/typed result
- four compatibility profiles
- POSIX and Windows wrapper identity/replacement tests
- Windows CI real wrapper/build/application/MCP journey
- deterministic output, manifest, README, ZIP contract

### 12.4 Completion gate

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
GEN2SPRING_JAVA_21_HOME="$(mise where java@21)" \
mise exec -- ./gradlew clean test integrationTest :generator-cli:installDist \
  --no-daemon --non-interactive --rerun-tasks
```

Windows CI는 같은 Gradle task set을 PowerShell에서 실행하고 generated validation이 `gradlew.bat`를 실제로
선택했음을 별도 assertion으로 고정한다.

## 13. 구현 단위

1. ToolEmitter port와 Spring AI 1/2 adapter extraction
2. response schema와 output policy domain/config/OpenAPI
3. typed output IR, renderer, generated runtime
4. retry policy와 generated executor
5. pagination policy, aggregation, ordered mock validation
6. Windows runtime/wrapper platform adapter와 CI
7. four-profile CLI journey, manifest/preview/editor/docs
8. full acceptance, review, meaningful feature commits, remote branch push

각 단위는 test-first로 진행하며 production 변경과 대응 회귀 테스트를 같은 의미 단위 커밋에 포함한다.
