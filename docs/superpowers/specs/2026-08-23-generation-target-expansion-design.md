# Maven, WebFlux, Async 생성 대상 확장 설계

## 1. 목표

PRD P2의 다음 수직 슬라이스로 생성 대상을 Gradle Kotlin DSL 중심의 Spring MVC Sync 서버에서 Maven과
Spring WebFlux Async 서버까지 확장한다. 지원 조합은 추론하지 않고 exact compatibility profile로
등록하며, 생성된 모든 공식 profile은 compile, ApplicationContext, MCP initialize, `tools/list`,
`tools/call` 검증을 통과해야 한다.

이 설계는 다음 세 가지를 함께 다룬다.

- Gradle과 Maven 프로젝트 scaffold
- Spring MVC Sync와 Spring WebFlux Async runtime
- profile 선택 UI의 지원 범위 도움말

STDIO, Kotlin, Spring AI 1.1 WebFlux Async, public Gateway, OAuth2 credential acquisition, sharing,
billing은 포함하지 않는다.

## 2. 승인된 원칙

1. 선택 가능한 공식 profile은 실제 생성과 검증을 통과한 조합만 포함한다.
2. MVC와 WebFlux, Sync와 Async를 임의로 조합하지 않는다.
3. MVC는 Sync, WebFlux는 Async로만 제공한다.
4. 기존 Gradle MVC profile ID는 변경하지 않는다.
5. Spring AI family emitter와 build tool scaffold를 독립된 책임으로 분리한다.
6. WebFlux Async runtime은 기존 blocking runtime을 scheduler로 감싸지 않고 end-to-end reactive로 생성한다.
7. 긴 전체 matrix는 빠른 PR CI와 분리하되 공식 지원 선언 전 반드시 실행한다.
8. Spring AI 1.1 WebFlux Async의 upstream 결함은 UI와 문서에 명시한다.

## 3. 지원 행렬

### 3.1 활성 profile

활성 profile은 총 12개다.

| ID | Java | Build | Web | Model | Spring AI |
|---|---:|---|---|---|---|
| `spring-ai-1.1-java17-mvc-streamable` | 17 | Gradle Kotlin | MVC | Sync | 1.1.8 |
| `spring-ai-1.1-java21-mvc-streamable` | 21 | Gradle Kotlin | MVC | Sync | 1.1.8 |
| `spring-ai-1.1-java17-maven-mvc-streamable` | 17 | Maven | MVC | Sync | 1.1.8 |
| `spring-ai-1.1-java21-maven-mvc-streamable` | 21 | Maven | MVC | Sync | 1.1.8 |
| `spring-ai-2.0-java17-mvc-streamable` | 17 | Gradle Kotlin | MVC | Sync | 2.0.0 |
| `spring-ai-2.0-java21-mvc-streamable` | 21 | Gradle Kotlin | MVC | Sync | 2.0.0 |
| `spring-ai-2.0-java17-maven-mvc-streamable` | 17 | Maven | MVC | Sync | 2.0.0 |
| `spring-ai-2.0-java21-maven-mvc-streamable` | 21 | Maven | MVC | Sync | 2.0.0 |
| `spring-ai-2.0-java17-webflux-async-streamable` | 17 | Gradle Kotlin | WebFlux | Async | 2.0.0 |
| `spring-ai-2.0-java21-webflux-async-streamable` | 21 | Gradle Kotlin | WebFlux | Async | 2.0.0 |
| `spring-ai-2.0-java17-maven-webflux-async-streamable` | 17 | Maven | WebFlux | Async | 2.0.0 |
| `spring-ai-2.0-java21-maven-webflux-async-streamable` | 21 | Maven | WebFlux | Async | 2.0.0 |

Spring AI 1.1.8 profile은 Spring Boot 3.5.16, Spring AI 2.0.0 profile은 Spring Boot 4.1.0을 사용한다.
Spring Boot, Spring AI, wrapper, template, runtime, container image는 profile에서 exact version으로 고정한다.
기존 네 Gradle MVC profile ID는 backward compatibility를 위해 그대로 유지한다.

### 3.2 보류 조합

다음 네 target은 canonical profile로 등록하지 않는다.

```text
Spring AI 1.1.8 + Java 17 + Gradle Kotlin + WebFlux + Async + Streamable HTTP
Spring AI 1.1.8 + Java 21 + Gradle Kotlin + WebFlux + Async + Streamable HTTP
Spring AI 1.1.8 + Java 17 + Maven + WebFlux + Async + Streamable HTTP
Spring AI 1.1.8 + Java 21 + Maven + WebFlux + Async + Streamable HTTP
```

Spring AI 1.1.8은 MCP Java SDK 0.18.3을 사용한다. 해당 WebFlux Streamable HTTP transport는 SSE
message ID가 null일 때 `ServerSentEvent.id(null)`을 호출해 Spring Framework 6.2에서 실패할 수 있다.
upstream issue는 <https://github.com/spring-projects/spring-ai/issues/6274>다.

다음 조건을 모두 만족할 때만 보류를 해제한다.

- upstream 수정이 포함된 stable Spring AI 1.1.x release
- Java 17과 Java 21 compile 및 generated tests 성공
- Gradle과 Maven ApplicationContext 기동 성공
- MCP initialize, `tools/list`, `tools/call` 성공
- mock upstream request와 응답 normalization 일치

비공식 MCP SDK fork, dependency shadowing, transport source 복제로 우회하지 않는다.

## 4. Profile과 UI 계약

`CompatibilityCatalog`는 생성 가능한 `CompatibilityProfileRegistry`와 immutable compatibility notice를
함께 소유한다. Generator와 CLI는 registry만 사용하고 profile API는 catalog 전체를 사용한다. Registry는
12개 profile만 ID 순으로 노출하며 dynamic Cartesian product나 unknown combination fallback을 도입하지
않는다. 보류 target ID가 요청되면 기존 safe `TARGET_PROFILE_NOT_FOUND` 계약을 사용한다.

Profile의 build metadata는 Gradle 전용 필드에서 build tool 공통 표현으로 확장한다. manifest와 API에는
build tool type, version, wrapper version을 canonical metadata로 제공한다. 기존 Gradle profile의
`gradleVersion`은 호환 alias로 유지하고 Maven profile에는 적용하지 않는다.

`GET /api/profiles`는 다음 두 종류의 데이터를 같은 canonical source에서 반환한다.

- 생성 가능한 profile 목록
- `compatibilityNotices`: 지원 범위와 보류 사유

보류 notice는 stable code `SPRING_AI_1_WEBFLUX_ASYNC_DEFERRED`, severity, 짧은 summary, 상세 reason,
upstream reference URL, 영향받는 Spring AI/web/programming/transport 축을 가진다. Java와 build tool은 영향
축에 포함하지 않아 하나의 notice가 네 deferred target을 설명한다.

Client는 compatibility notice 문구를 별도로 복제하지 않는다. profile 선택 목록에는 12개 활성 profile만
표시하고 option label은 Spring AI, Java, build tool, web stack, programming model을 사람이 읽기 쉬운
형태로 표현한다.

`Compatibility profile` label 옆에는 help button을 둔다. help content는 최소한 다음 의미를 전달한다.

> MVC + Sync는 Spring AI 1.1/2.0에서 지원합니다. WebFlux + Async는 Spring AI 2.0에서 지원합니다.
> Spring AI 1.1 WebFlux + Async는 upstream 전송 계층 문제로 일시 보류됩니다.

Help는 hover 전용 tooltip이 아니다. click과 keyboard로 열 수 있고 `aria-expanded`, `aria-controls`로
상태와 내용을 연결한다. `Escape`, 외부 click, help button 재선택으로 닫을 수 있으며 focus를 강제로
이동시키지 않는다.

## 5. 생성기 구조

### 5.1 책임 분리

```text
ProjectGenerator
├── SpringAi1Emitter / SpringAi2Emitter
│   └── Spring AI API, Java source, dependency requirement
└── BuildProjectScaffold
    ├── GradleKotlinScaffold
    └── MavenScaffold
```

Spring AI emitter는 다음만 책임진다.

- Spring AI family와 programming model 검증
- Tool, callback/specification, runtime Java source
- starter와 dependency requirement
- family별 Jackson 및 MCP API 차이

Build scaffold는 다음을 책임진다.

- build definition
- wrapper assets
- build tool별 README command
- build output 경로를 반영한 Dockerfile
- build tool별 ignore 파일

기존 `emitters:support` 모듈에 framework-neutral scaffold 계약과 검증된 wrapper assets를 둔다. Spring AI
1과 2 adapter는 support 모듈에 dependency definition을 전달한다. 한 renderer 안에서 Spring AI family와
build tool을 중첩 조건문으로 처리하지 않는다.

### 5.2 Gradle scaffold

기존 Gradle Kotlin DSL과 Gradle 9.6.1 wrapper 계약을 유지한다. 기존 Gradle profile에서 생성되는 Java
source와 runtime behavior는 변경하지 않는다. wrapper JAR와 scripts는 현재 검증된 assets를 사용한다.

### 5.3 Maven scaffold

Maven 3.9.16과 Maven Wrapper 3.3.4를 exact version으로 고정한다. Wrapper는 `only-script` distribution을
사용해 `maven-wrapper.jar`를 생성물에 포함하지 않는다.

생성 파일은 다음을 포함한다.

```text
pom.xml
mvnw
mvnw.cmd
.mvn/wrapper/maven-wrapper.properties
```

`maven-wrapper.properties`는 Maven 3.9.16 binary distribution URL과 공식 SHA-256을 고정한다. `pom.xml`은
target Java compiler release, Spring Boot plugin, Spring AI BOM/dependencies, test plugin을 명시하고
executable artifact 이름을 `<artifactId>.jar`로 결정적으로 고정한다.

Gradle과 Maven의 동일 Spring AI/web/programming profile은 build files, README build command, Dockerfile
artifact path를 제외한 생성 Java source가 byte-identical해야 한다.

## 6. Generated project validator

현재 Gradle wrapper 실행, artifact 탐색, application 기동, MCP 검증을 함께 담당하는 validator를 세
책임으로 분리한다.

```text
GeneratedProjectValidator
├── BuildToolDriverRegistry
│   ├── GradleBuildDriver
│   └── MavenBuildDriver
├── ApplicationRuntimeValidator
└── McpRuntimeValidationEngine
```

Build driver는 profile의 exact build tool로 선택한다. 다음을 공통 계약으로 제공한다.

- wrapper identity 고정과 실행 직전 재검증
- target Java home을 사용하는 bounded build command
- build timeout과 bounded process output
- executable artifact의 safe exact resolution

Gradle driver는 `classes test bootJar`와 `build/libs/<artifactId>.jar`를 사용한다. Maven driver는 batch 및
no-transfer-progress mode로 `test package`를 실행하고 `target/<artifactId>.jar`를 사용한다. validation
workspace 밖 path, symlink, ambiguous artifact, changed wrapper identity는 fail-closed로 거부한다.

Application validator는 random server port를 유지한다. MVC의 Tomcat startup output과 WebFlux의 Netty
startup output을 profile에 맞는 bounded endpoint detector로 해석한다. MCP runtime validation은 build
tool과 web stack에 의존하지 않고 동일한 initialize, `tools/list`, `tools/call` contract를 사용한다.

## 7. WebFlux Async runtime

### 7.1 Runtime path

```text
POST /mcp
-> McpAsyncServer
-> McpServerFeatures.AsyncToolSpecification
-> raw argument schema validation
-> generated async Tool
-> ReactiveOpenApiOperationExecutor
-> WebClient
-> response normalization
-> Mono<CallToolResult>
```

Spring AI 2 Async profile은 `spring-ai-starter-mcp-server-webflux`,
`spring.ai.mcp.server.protocol=STREAMABLE`, `spring.ai.mcp.server.type=ASYNC`를 사용한다.

`SpringAi2Emitter`는 Sync와 Async runtime renderer를 분리한다. Sync profile의 existing renderer와 runtime을
characterization test로 보호한다. Async Tool은 `Mono<JsonNode>` 또는 `Mono<ResultDto>`를 반환한다.

Async specification은 explicit JSON schema와 manual call handler를 사용한다. Spring AI의
`McpToolUtils.toAsyncToolSpecification`으로 blocking callback을 bounded-elastic scheduler에 감싸지 않는다.
Async profile의 generated runtime은 `RestClient`, `Future.get`, `Thread.sleep`, `.block()`을 사용하지 않는다.

### 7.2 Provider HTTP

Reactive executor는 Reactor Netty 기반 `WebClient`를 사용한다.

- connection pool의 최대 active request와 pending request를 제한한다.
- connect timeout, read timeout, total Tool timeout을 분리한다.
- response body는 `provider.response-max-bytes`까지만 집계한다.
- retry와 pagination은 순차적인 `Mono` chain으로 실행한다.
- total timeout과 cancellation은 retry delay와 진행 중 HTTP request에 전파한다.
- retry, pagination, response normalization의 externally visible result는 Sync runtime과 동일하다.

Tool argument presence와 explicit null 구분을 async boundary 밖의 immutable input으로 확정한다. 기존
ThreadLocal scope를 reactive signal 간 context 전달에 사용하지 않는다. trace와 telemetry context는 Reactor
context에서 전파한다.

### 7.3 오류와 telemetry

예상된 provider 오류는 기존과 동일한 bounded payload와 `isError=true` 결과를 반환한다. schema conversion,
result serialization, unexpected Tool execution failure는 path, command output, request payload, secret을
노출하지 않는 safe internal error로 변환한다. cancellation은 provider error로 변환하지 않고 진행 중
작업을 중단한다.

Tool call과 provider attempt telemetry는 success, expected error, internal error, cancellation 중 하나의
terminal outcome을 호출당 정확히 한 번 기록한다. retry와 pagination page는 parent Tool call 아래에서
관찰 가능하되 secret과 high-cardinality input을 tag로 기록하지 않는다.

## 8. 테스트와 CI

### 8.1 빠른 PR CI

Linux와 Windows 필수 CI는 nested generated build를 실행하지 않는다. 다음 fast contract를 검증한다.

- exact 12-profile order, target uniqueness, immutability
- 네 deferred target을 설명하는 하나의 canonical compatibility notice와 API serialization
- Gradle/Maven scaffold file set, versions, checksums
- build tool별 safe command와 artifact resolution
- MVC/Sync와 WebFlux/Async source contract
- Gradle/Maven generated Java source equality
- WebFlux runtime success, error, timeout, retry, pagination, size limit, cancellation
- Tomcat/Netty readiness detection
- profile selector와 accessible help behavior

중첩 build를 수행하는 emitter smoke test는 unit test source set에서 generation acceptance source set으로
이동한다.

### 8.2 Generation acceptance

`mise run generator:acceptance`는 Java 17과 Java 21 home을 설정하고 Linux에서 12개 profile을 순차적으로
생성한다. 각 profile은 다음을 통과해야 한다.

1. compile과 generated tests
2. ApplicationContext와 HTTP endpoint 기동
3. MCP initialize
4. exact `tools/list`
5. representative `tools/call`
6. exact mock upstream request와 normalized response
7. manifest, source checksum, deterministic archive contract

Windows acceptance는 다음 대표 조합으로 host-specific execution을 검증한다.

- Spring AI 1.1, Java 17, Gradle, MVC Sync
- Spring AI 2.0, Java 21, Maven, WebFlux Async

두 조합은 두 target JDK, 두 build tool, 두 web stack, 두 programming model을 함께 덮는다. 지원 선언 전
Linux 전체 matrix와 Windows representative acceptance를 모두 실행한다. PR 필수 check와 분리된 수동
GitHub Actions workflow는 같은 Linux/Windows acceptance set을 재현할 수 있어야 한다.

### 8.3 Mise task

- `mise run generator:test`: generator, scaffold, validator, profile API/UI fast contract
- `mise run generator:acceptance`: POSIX host에서는 12-profile full matrix, Windows에서는 두 representative profile

PR CI 성공과 generation acceptance 성공은 결과 보고에서 구분한다.

## 9. 결정성, 복잡도, 운영 제한

Profile 수를 `P`, generated file 수를 `F`, Tool 수를 `T`라 하면 registry 구성은 `O(P log P)` 시간과
`O(P)` 공간, profile 조회는 `O(1)`, 한 프로젝트 생성은 `O(F + T log T)`다.

Async Tool의 최대 attempt를 `A`, 최대 page를 `G`, 최대 response bytes를 `B`, 누적 item limit을 `M`이라
하면 호출 시간은 최대 `O(A * G)` HTTP operation, 메모리는 `O(B + M)`으로 제한된다. connection pool
메모리는 active와 pending request 설정값으로 제한한다.

전체 generation acceptance는 profile별 build 비용을 `C`라 할 때 `O(P * C)`다. 기본 task는 resource
경쟁과 flaky port/startup 간섭을 피하기 위해 순차 실행한다.

## 10. 완료 조건

- 기본 registry가 exact 12 profile을 결정적 순서로 노출한다.
- 보류 target이 generation fallback 또는 selectable option으로 노출되지 않는다.
- profile API와 UI help가 canonical compatibility notice를 사용한다.
- Gradle과 Maven MVC profile이 Java 17·21에서 전체 validation을 통과한다.
- Gradle과 Maven Spring AI 2 WebFlux Async profile이 Java 17·21에서 전체 validation을 통과한다.
- Async runtime에 blocking HTTP path가 없고 Sync와 동일한 observable Tool contract를 제공한다.
- Linux 12-profile과 Windows representative acceptance가 성공한다.
- fast Linux와 Windows PR CI가 nested generated build 없이 성공한다.
- PRD의 Maven, WebFlux, Async는 위 조건이 충족된 뒤에만 P2 완료로 이동한다.

## 11. 참고 자료

- Spring AI MCP Server Boot Starter: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html>
- Spring AI 1.1 WebFlux Streamable issue: <https://github.com/spring-projects/spring-ai/issues/6274>
- Spring AI 1.1.8 release: <https://github.com/spring-projects/spring-ai/releases/tag/v1.1.8>
- Maven 3.9.16 release notes: <https://maven.apache.org/docs/3.9.16/release-notes.html>
- Maven Wrapper 3.3.4: <https://maven.apache.org/tools/wrapper/maven-wrapper-plugin/wrapper-mojo.html>
