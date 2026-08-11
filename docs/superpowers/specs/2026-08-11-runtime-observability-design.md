# Generated Runtime Metrics and OpenTelemetry Design

## 1. 목적

Spring AI 1.1과 2.0 generated project가 같은 의미의 runtime metric과 trace를 방출하도록 한다.
이번 범위는 generated MCP data-plane의 Tool 실행과 upstream provider 호출이다. PRD NFR-5의
`generation.request`, parse, compile, package 같은 control-plane 계측은 generation ID, queue,
worker 수명주기를 소유하는 Generator API slice에서 구현한다. generated runtime에 존재하지 않는
generation stage span을 합성하지 않는다.

## 2. 범위

포함:

- Spring AI 1.1.8 / Boot 3.5.16 generated runtime
- Spring AI 2.0.0 / Boot 4.1.0 generated runtime
- Java 17과 Java 21 target
- MCP Tool call, provider request, executor queue, response byte metric
- Micrometer Observation 기반 span과 metric
- active OpenTelemetry trace ID의 provider error envelope 재사용
- worker executor context propagation
- Prometheus와 OTLP의 안전한 opt-in 설정
- manifest/template/runtime capability version 갱신

제외:

- Generator API와 worker의 generation metric/span
- retry, pagination, typed output DTO
- collector 배포와 원격 backend 운영
- 사용자 정의 metric 이름, tag, sampling policy editor

## 3. 호환성 profile과 버전

기존 네 profile ID와 Boot, Spring AI, Java, Gradle, image는 유지한다. 관측성 추가는 generated
source와 dependency 계약을 바꾸므로 두 family의 metadata를 다음처럼 갱신한다.

| family | template | runtime |
|---|---|---|
| Spring AI 1.1 | `spring-ai-1-v2` | `0.3.0` |
| Spring AI 2.0 | `spring-ai-2-v3` | `0.3.0` |

`CompatibilityProfile.p0()`는 계속 canonical Spring AI 2 Java 21 profile을 반환한다. profile ID를
바꾸지 않으므로 기존 configuration은 같은 target을 선택하며, manifest와 MCP server version이
capability 변경을 명시한다.

## 4. dependency adapter

계측 source API는 두 family에서 동일하다. dependency mapping만 version-specific renderer가 소유한다.

### 4.1 Boot 3.5 / Spring AI 1.1

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("io.micrometer:micrometer-registry-prometheus")
implementation("io.micrometer:micrometer-registry-otlp")
implementation("io.micrometer:micrometer-tracing-bridge-otel")
implementation("io.opentelemetry:opentelemetry-exporter-otlp")
```

### 4.2 Boot 4.1 / Spring AI 2.0

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
implementation("io.micrometer:micrometer-registry-prometheus")
implementation("io.micrometer:micrometer-registry-otlp")
```

Boot BOM이 모든 version을 관리한다. generated build에 별도 latest, dynamic version 또는 중복 OTel
bridge/exporter version을 넣지 않는다.

## 5. canonical telemetry contract

`generator-domain`의 `RuntimeObservabilityContract`가 observation/meter/tag/value literal과 허용 조합을
한 번만 소유한다. 두 emitter는 이 canonical contract로 package만 다른 동일 의미의 `RuntimeTelemetry`
source를 생성한다. renderer 테스트의 기대값은 contract 상수나 production serializer를 재사용하지 않고
독립 literal로 고정한다. generated component는 `ObservationRegistry`, `MeterRegistry`, `Tracer`를
constructor injection으로 받는다.

### 5.1 Observation과 Timer

| observation / timer | low-cardinality tags |
|---|---|
| `gen2spring.runtime.mcp.tool.call` | `target.profile`, `outcome`, `error.category` |
| `gen2spring.runtime.provider.request` | `target.profile`, `outcome`, `error.category`, `http.status.class` |

`outcome`, `error.category`, `http.status.class`는 generated enum과 고정 allow-list 값만 사용한다.
`operationId`와 Tool name은 trace attribute에만 사용한다. 선택 operation은 artifact별로 유한하지만
여러 generated service를 합친 metric backend에서는 전역 cardinality가 제한되지 않으므로 meter tag로
사용하지 않는다.

### 5.2 추가 meter

| meter | type | tags |
|---|---|---|
| `gen2spring.runtime.provider.response.bytes` | DistributionSummary | `target.profile`, `http.status.class` |
| `gen2spring.runtime.provider.executor.active` | Gauge | `target.profile` |
| `gen2spring.runtime.provider.executor.queued` | Gauge | `target.profile` |

timeout count는 provider Timer의 `error.category=upstream_timeout`, executor saturation count는
`error.category=local_resource`인 Timer count로 계산한다.
response size는 bounded response body의 관측 byte 수만 기록한다.

### 5.3 Exact tag와 결과 mapping

정확한 값은 lowercase ASCII literal이다.

```text
outcome = success | expected_error | internal_error | fatal
error.category = none | provider_business | upstream_client | upstream_server |
                 upstream_timeout | upstream_unavailable | upstream_protocol |
                 local_resource | argument_conversion | result_conversion |
                 tool_execution | unexpected_runtime | fatal
http.status.class = 2xx | 4xx | 5xx | other | none
```

| 경로 | MCP/Java 결과 | outcome | error.category | span status |
|---|---|---|---|---|
| 정상 Tool/provider | `isError=false` | `success` | `none` | UNSET |
| `ProviderErrorException` | `isError=true` | `expected_error` | typed provider category | ERROR |
| argument 변환 실패 | JSON-RPC `-32603` | `internal_error` | `argument_conversion` | ERROR |
| result 변환 실패 | JSON-RPC `-32603` | `internal_error` | `result_conversion` | ERROR |
| 그 외 Tool 실행 실패 | JSON-RPC `-32603` | `internal_error` | `tool_execution` | ERROR |
| 예상 밖 runtime 실패 | JSON-RPC `-32603` | `internal_error` | `unexpected_runtime` | ERROR |
| fatal `Error` | 동일 객체 재throw | `fatal` | `fatal` | ERROR |

provider category는 기존 `ProviderErrorCategory` enum을 위 lowercase 값으로 일대일 mapping한다.
`ProviderError`는 payload와 함께 typed category와 nullable HTTP status를 보존해 telemetry가 JSON payload를
다시 parse하지 않도록 한다.

### 5.4 Span

Observation과 같은 고정 이름을 span 이름으로 사용한다.

```text
gen2spring.runtime.mcp.tool.call
gen2spring.runtime.provider.request
```

provider worker는 `ContextExecutorService`로 wrapping해 caller가 시작한 provider span context를 전파한다.
provider span은 Tool span의 child이고, async worker에서도 같은 trace ID를 유지한다. trace-only attribute는
`gen2spring.tool.name`, `gen2spring.operation.id`, `http.request.method`와 응답을 받은 경우의
`http.response.status_code`만 허용한다.

Tool name은 기존 64자리 lowercase snake-case 계약을 사용한다. operation ID는 emitter의
`[A-Za-z0-9][A-Za-z0-9_.-]{0,127}` source-name 계약을 통과한 값만 허용하고 `RuntimeTelemetry` 초기화에서도
같은 128자리 ASCII bound를 방어적으로 재검증한다. 검증 실패는 fixed non-leaking generated startup
failure이며 값을 자르거나 hash해서 서로 다른 operation을 합치지 않는다. 이 두 값은 trace-only 예외다.
meter tag, resource attribute, span name에는 사용하지 않는다.

### 5.5 Trace ID와 log correlation

provider error envelope의 `traceId`는 `Tracer.currentSpan()`의 유효한 32자리 lowercase hex trace ID를
우선 사용한다. active span이 없거나 context가 유효하지 않으면 기존 16-byte `SecureRandom` fallback을
사용한다. remote provider의 trace ID나 header 값을 신뢰하거나 재사용하지 않는다.

Boot tracing의 native MDC `traceId`와 `spanId`를 사용한다. generated safe error log에는 고정
`targetProfileId`, bounded `operationId`, bounded Tool name, `outcome`, `errorCategory`, exception class만
추가한다. runtime에 존재하지 않는 `generationId`와 `projectId`는 합성하지 않는다. 성공 호출은 로그를
추가하지 않는다.

## 6. 보안과 cardinality

metric tag, span attribute, event, observation context에 다음 값을 넣지 않는다.

- argument, path variable, query, request/response body
- provider URL, provider code/message
- header, credential, secret name/value
- exception message, stack trace, raw process output
- trace ID, 사용자/프로젝트 자유 형식 값. 단, validated bounded Tool name/operation ID는 trace-only 허용

raw `Throwable`을 `Observation.error` 또는 `Span.error`에 넘기지 않는다. ERROR status가 필요한 경우
고정 category만 가진 cause-less/stackless telemetry sentinel을 사용하며 원본 예외를 연결하지 않는다.
fatal `Error`는 telemetry scope를 닫은 뒤 원래 identity로 재전파한다. 기존 masking, provider error,
fixed adapter message 계약을 변경하지 않는다.

공통 metric tag `target.profile`은 canonical 네 profile ID 중 하나만 사용하고 `RuntimeTelemetry`가
canonical meter에 직접 한 번 추가한다. YAML common tag와 자유 형식 artifact ID의 `application` tag는
만들지 않는다.

## 7. 안전한 기본 설정

Actuator와 exporter library는 포함하되 외부 전송과 unauthenticated metric endpoint는 기본 비활성이다.

공통 설정:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: ${MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE:health}
  endpoint:
    health:
      show-details: never
  tracing:
    propagation:
      type: W3C
    sampling:
      probability: ${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}
  otlp:
    metrics:
      export:
        enabled: ${MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED:false}
```

Boot 3 trace export:

```yaml
management.otlp.tracing.export.enabled: ${MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED:false}
```

Boot 4 trace export:

```yaml
management.tracing.export.otlp.enabled: ${MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED:false}
```

Spring AI 2에서만 지원되는 content observation property는
`spring.ai.tools.observations.include-content=false`로 고정한다. Spring AI 1에는 존재하지 않는 property를
렌더링하지 않고 custom telemetry가 content를 기록하지 않는 계약으로 보장한다.
Prometheus opt-in 문서는 `MANAGEMENT_SERVER_ADDRESS=127.0.0.1`, 별도
`MANAGEMENT_SERVER_PORT=9464`, `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,prometheus`를 함께
사용하고 remote scrape 시 인증된 trusted network가 필요하다고 경고한다. OTLP endpoint는
Boot version별 공식 management property/env로만 설정하며 generated default endpoint를 만들지 않는다.
export disabled 상태에서 exporter bean과 outbound connection이 생기지 않아야 한다.

## 8. generated source integration

1. `ToolCallbackConfigurationRenderer`가 `RuntimeTelemetry`를 주입하고 callback 실행 전체를
   `gen2spring.runtime.mcp.tool.call` scope로 감싼다.
2. `RuntimeSourceRenderer`가 raw `ThreadPoolExecutor`와 context-propagating `ExecutorService`를 분리한다.
3. executor active/queue gauge는 raw pool에 한 번만 등록한다.
4. caller는 submit 전에 Tool observation의 child provider observation을 시작하고 그 context 안에서 task를
   submit한다. worker는 전파된 scope에서 transport만 수행하고 status/body byte/result metadata를 immutable
   result로 반환하며 observation을 stop하거나 meter를 직접 기록하지 않는다.
5. caller-owned `ProviderCall`이 `AtomicBoolean` 단일 종료 gate를 가진다. 정상/provider error는 Future 결과로,
   rejection은 submit catch에서 `local_resource`로, total timeout은 cancel 직후 `upstream_timeout`으로 정확히
   한 번 complete한다. interruption과 기존 failure mapping도 caller에서 complete한 뒤 기존 재전파 계약을
   유지한다. timeout/cancel 뒤 도착한 worker result는 outcome, status, byte summary, span을 변경하거나 두 번
   stop할 수 없다. response byte summary는 단일 complete에 채택된 bounded response의 byte 수만 기록한다.
6. `ResponseRuntimeRenderer`는 `RuntimeTelemetry.currentTraceIdOrFallback()`을 사용한다.
7. `RestClient.Builder`의 automatic client observation은 NOOP registry로 비활성화한다. 표준 observation은
   raw transport `Throwable`, URI/query/host를 기록할 수 있어 query API key와 exception message 유출을
   완전하게 sanitize할 수 없다.
8. `RuntimeTelemetry`가 current sanitized custom provider span의 trace ID, span ID, sampled flag만으로
   version `00` W3C `traceparent`를 직접 구성한다. trace ID는 32자리, span ID는 16자리 lowercase hex이고
   all-zero 값이 아니어야 하며 flags는 sampled 여부에 따라 `01` 또는 `00`만 사용한다. 모든 OpenAPI
   header binding을 완료한 뒤 case-insensitive로 기존 `traceparent`, `tracestate`, `baggage`, `b3`,
   `x-b3-*`를 먼저 제거하고, 유효한 current context일 때만 정확히 하나의 generated `traceparent`를
   `HttpHeaders.set`으로 기록한다. `tracestate`, baggage, B3 및 사용자 제공 propagation 값은 복사하거나
   재생성하지 않는다. current context가 유효하지 않으면 propagation header를 하나도 전송하지 않는다.
   raw URL과 failure를 기록하는 client span은 만들지 않는다.
9. `RuntimeObservabilityContract`는 propagation header 이름을 예약한다. OpenAPI operation이 이들 header를
   parameter 또는 API-key target으로 요구하면 policy 단계에서 case-insensitive fail-closed한다. runtime
   제거는 defense-in-depth이며 사용자 입력을 조용히 무시하는 수단이 아니다.
10. custom provider span kind는 `INTERNAL`로 고정한다. scope close와 meter 기록이 기존
    timeout/interruption/fatal Error semantics를 바꾸지 않아야 한다.

## 9. 검증

- AI1/AI2 source golden과 forbidden high-cardinality token test
- 두 family, Java 17/21 generated compile/test/context
- `ObservationRegistry`, `MeterRegistry`, `Tracer` bean 존재
- export-off에서 OTLP exporter bean과 outbound request 부재
- default Actuator exposure는 health만, opt-in Prometheus scrape는 exact metric 이름/값 노출
- 성공/provider error/timeout/rejected/internal/fatal 경로의 Timer/DistributionSummary/Gauge 검증
- caller-owned provider lifecycle의 submit rejection, queued timeout, running timeout, cancel-late-success를
  deterministic executor fixture로 검증하고 각 경로가 exact 한 Timer/span stop과 outcome만 기록함을 검증
- test-only in-memory OTel exporter와 always-on sampler로 exact 두 custom span, parent/child, status/attribute 검증
- mock upstream `traceparent`의 trace ID가 exported provider/Tool trace ID와 일치
- malicious 기존 `traceparent`, `tracestate`, `baggage`, B3 header는 operation 생성에서 거부되고 runtime
  defense-in-depth fixture에서도 제거되며 upstream에는 정확히 하나의 generated `traceparent`만 도착
- provider error envelope trace ID가 active trace와 일치하고 no-span fallback은 32-hex
- meter ID/span attribute/log/exported span과 outbound trace header에 secret, raw marker, URL, query,
  argument, exception message/stack 부재
- four-profile installed CLI를 profile별 두 번 실행해 deterministic source/manifest/archive contract 유지
- current full repository acceptance와 독립 whole-slice review

## 10. 복잡도와 위험

- Tool/provider 호출당 observation 처리: 시간 `O(1)`, 호출 중 scope 메모리 `O(1)`
- meter cardinality: 고정 profile/category/status 조합이므로 service fleet 기준 `O(C)`
- response summary는 이미 읽은 bounded byte length만 기록하므로 추가 payload copy가 없다.
- 주 위험은 Boot 3/4 property/API drift, context propagation 누락, exporter 기본 network 호출,
  기본 HTTP client observation 또는 propagation의 query credential 유출, exception/secret telemetry 유출이다. 실제 네
  profile context와 live MCP acceptance로만 완료 판정한다.
