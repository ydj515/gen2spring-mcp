# Spring AI 1.1 Java 17·21 Generator Profiles 설계

## 1. 목표

PRD P1의 다음 수직 슬라이스로 Spring AI 1.1.8 기반 Spring MVC Sync Streamable HTTP
MCP 서버를 Java 17과 Java 21 대상으로 생성한다. 기존 Spring AI 2.0 emitter와 공통 Tool IR,
보안 정책, response normalization, mock upstream validation을 재사용하되 버전별 생성 코드는 별도
`generator-spring-ai-1` 모듈에 격리한다.

완료 시 기본 compatibility registry는 다음 네 profile을 ID 오름차순으로 노출한다.

1. `spring-ai-1.1-java17-mvc-streamable`
2. `spring-ai-1.1-java21-mvc-streamable`
3. `spring-ai-2.0-java17-mvc-streamable`
4. `spring-ai-2.0-java21-mvc-streamable`

## 2. 승인된 범위

### 포함

- Spring AI 1.1.8, Spring Boot 3.5.16, Gradle 9.6.1
- Java 17 및 Java 21 generated target
- Gradle Kotlin DSL
- Spring MVC, Sync server, Streamable HTTP `/mcp`
- explicit MCP Tool schema와 manual `SyncToolSpecification` adapter
- 기존 provider HTTP runtime, secret injection, response normalization, provider error mapping
- 기존 5단계 validation과 대표 `tools/call` 1회, upstream request 정확히 1회
- profile별 manifest, README, Dockerfile, `.dockerignore`, deterministic ZIP
- installed CLI의 네 profile compile·test·boot·MCP matrix

### 제외

- Spring AI 1.0.x 또는 다른 1.x patch profile
- Spring AI 1.x SSE, stateless, STDIO, WebFlux, async
- 공통 renderer 모듈 추출 또는 기존 Spring AI 2 renderer 재구조화
- metrics와 OpenTelemetry tracing
- Windows validation host
- Generator API와 operation editor
- retry·pagination 실행과 typed output DTO

제외 항목은 각각 후속 P1 또는 P2 설계에서 다룬다.

## 3. 버전과 공식 호환성 근거

Spring AI 1.1.8은 공식 release에서 Spring Boot 3.5.15와 MCP Java SDK 0.18.3으로 갱신됐다.
이 프로젝트는 이미 승인된 같은 Boot minor의 최신 patch인 3.5.16을 고정한다. Spring Boot 3.5.16
Gradle plugin은 Gradle 8.14 이상과 9.x를 지원하므로 기존 wrapper 9.6.1을 그대로 사용한다.

사전 compile spike는 Boot 3.5.16, Spring AI 1.1.8, Gradle 9.6.1 조합에서 Java 17과
Java 21 toolchain 모두 성공했다. 다음 API도 실제 compile로 확인했다.

- `McpServerFeatures.SyncToolSpecification`
- `McpSchema.CallToolResult`
- `McpToolUtils.toSyncToolSpecification`
- `DefaultToolDefinition`
- `MethodToolCallback`
- Spring MVC Streamable HTTP starter

반면 Spring AI 2.0에서 사용하는 `org.springframework.ai.mcp.annotation.McpToolParam`은
1.1.8 classpath에 없다. Spring AI 1 emitter는 이 annotation을 생성하지 않고, explicit
`DefaultToolDefinition.inputSchema`와 Jakarta Bean Validation으로 schema·runtime validation 계약을
유지한다.

## 4. Profile 계약

두 신규 canonical profile은 다음 값을 사용한다.

| 필드 | Java 17 | Java 21 |
|---|---|---|
| ID | `spring-ai-1.1-java17-mvc-streamable` | `spring-ai-1.1-java21-mvc-streamable` |
| Java | 17 | 21 |
| Spring Boot | 3.5.16 | 3.5.16 |
| Spring AI | 1.1.8 | 1.1.8 |
| Build tool | `GRADLE_KOTLIN` | `GRADLE_KOTLIN` |
| Web stack | `MVC` | `MVC` |
| Programming model | `SYNC` | `SYNC` |
| Transport | `STREAMABLE_HTTP` | `STREAMABLE_HTTP` |
| Generator module | `generator-spring-ai-1` | `generator-spring-ai-1` |
| Template | `spring-ai-1-v1` | `spring-ai-1-v1` |
| Runtime | `0.2.0` | `0.2.0` |
| Gradle | `9.6.1` | `9.6.1` |
| Container | `eclipse-temurin:17.0.19_10-jre-noble@sha256:543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8` | `eclipse-temurin:21.0.11_10-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64` |

기존 `CompatibilityProfile.p0()`는 Spring AI 2.0 Java 21 alias를 유지한다. 신규 profile을
추가해도 default fallback이나 profile family 추론을 도입하지 않는다.

## 5. 모듈 경계

### 5.1 Domain과 core

`CompatibilityProfileRegistry.defaults()`가 네 canonical profile을 생성한다.
`ProjectGeneratorRegistry`와 `GenerationPipeline`의 인터페이스는 변경하지 않는다. profile의
`generatorModule` 값으로 emitter를 선택하며, profile과 emitter는 OpenAPI 분석 전에 fail-closed로
확정된다.

### 5.2 CLI composition root

`ApplicationFactory`는 동일 `CompatibilityProfileRegistry`를 configuration reader, `profiles` 명령,
pipeline에 주입한다. `ProjectGeneratorRegistry`에는 아래 두 entry만 등록한다.

```text
generator-spring-ai-1 -> SpringAi1ProjectGenerator
generator-spring-ai-2 -> SpringAi2ProjectGenerator
```

### 5.3 Spring AI 1 emitter

새 `generator-spring-ai-1` 모듈은 다음 책임을 가진다.

- Spring AI 1 canonical profile family 검증
- Boot 3.5/Jackson 2용 project files와 Java source 생성
- Spring AI 1.1.8 MCP adapter와 application properties 생성
- 두 Java target의 generated compile/runtime tests

Spring AI 2 renderer를 호출하거나 profile에 따라 한 파일에서 1.x/2.x를 분기하지 않는다.
두 모듈의 동일한 작은 helper는 이 슬라이스에서 명시적으로 복제한다. 공통 모듈 추출은 두
emitter가 안정된 뒤 실제 변경 빈도와 중복 결함을 근거로 판단한다.

## 6. Generated project 계약

### 6.1 Build와 dependency

생성 `build.gradle.kts`는 다음 BOM과 starter를 사용한다.

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.5.16"
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation(platform("org.springframework.ai:spring-ai-bom:1.1.8"))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

Java toolchain, bootJar 이름, `-parameters`, JUnit Platform, deterministic Gradle properties는
Spring AI 2 generated project와 동일한 계약을 유지한다.

### 6.2 MCP server configuration

`application.yml`은 다음 server 설정을 사용한다.

```yaml
spring:
  ai:
    mcp:
      server:
        type: SYNC
        protocol: STREAMABLE
        annotation-scanner:
          enabled: false
        streamable-http:
          mcp-endpoint: /mcp
```

annotation scanner는 끄고 generated `List<McpServerFeatures.SyncToolSpecification>` bean만
등록한다. 이 방식은 server starter가 Tool을 중복 노출하지 않으면서 exact Tool schema와 error
result를 emitter가 통제하게 한다.

### 6.3 Java와 Jackson 차이

Spring AI 1 generated source는 Jackson 2 package를 사용한다.

```text
com.fasterxml.jackson.core.JsonProcessingException
com.fasterxml.jackson.databind.JsonNode
com.fasterxml.jackson.databind.ObjectMapper
com.fasterxml.jackson.databind.json.JsonMapper
```

Spring AI 2의 `tools.jackson.*` import는 생성하지 않는다. Jackson 2 serialization은 checked
`JsonProcessingException`을 던지므로 adapter와 runtime은 argument/result conversion 경계에서 이를
명시적으로 처리하고 기존 fixed safe message를 유지한다.

`McpToolParam`은 생성하지 않는다. Tool input schema는 기존 `ExpectedToolSchemaFactory`와 동일한
literal schema를 `DefaultToolDefinition.inputSchema`에 넣는다. generated method parameter와 nested
record에는 기존 Jakarta Bean Validation annotation을 유지한다.

### 6.4 Runtime와 response contract

provider execution metadata, bounded concurrency/queue, timeout, 1 MiB response limit, URL resolution,
header/query/body binding, secret injection, null omission, response normalization, provider error envelope,
safe diagnostics, fatal `Error` propagation은 Spring AI 2 emitter와 동일한 observable contract를
제공한다.

API 차이로 구현은 분리되지만 MCP client가 관찰하는 다음 결과는 profile 간 동일해야 한다.

- exact `tools/list` name, description, input schema
- 성공 `tools/call` normalized JSON text와 `isError=false`
- provider 오류의 safe JSON text와 `isError=true`
- invalid input은 handler 호출 전 fail-closed
- unexpected internal failure는 private marker 없이 JSON-RPC internal error

## 7. Validation과 runtime selection

기존 `JavaRuntimeResolver`를 변경하지 않는다. profile의 Java feature로 verified target home을 선택하고
다음 세 Gradle property를 전달한다.

```text
-Dorg.gradle.java.installations.auto-detect=false
-Dorg.gradle.java.installations.auto-download=false
-Dorg.gradle.java.installations.paths=<verified target home>
```

generated compile/test 뒤 boot JAR는 같은 target home의 `bin/java`로 시작한다. Spring AI 1과 2는
동일한 initialize, tools/list, tools/call, mock upstream verification 경로를 사용한다.

## 8. Determinism과 보안

- canonical registry와 CLI `profiles`는 네 profile을 항상 ID 순서로 출력한다.
- 같은 input/profile의 files, manifest, checksum, ZIP은 byte-identical이어야 한다.
- Spring AI 1과 2 또는 Java 17과 21 profile checksum은 달라야 한다.
- renderer는 canonical registry의 exact profile equality를 요구해 image/template/runtime drift와
  Docker instruction injection을 거부한다.
- 생성 파일, manifest, ZIP, validation report, stdout/stderr에 JDK path, username, command,
  process output, provider URL, secret, stack trace를 남기지 않는다.
- Dockerfile은 기존 digest-pinned image와 `USER 10001:10001`을 사용하고 deterministic
  `.dockerignore`를 생성한다.

## 9. 테스트와 인수 조건

### 9.1 Module tests

- registry exact four-profile value/order/immutability tests
- Spring AI 1 renderer family와 canonical equality tests
- golden source tests for query/path/header/body/enum/array/nested/security/normalization
- generated Java 17·21 compile and `Runtime.version().feature()` tests
- Jackson 2 precision, null omission, provider error, invalid input, secret safety tests
- Spring AI 2 full regression suite

### 9.2 Installed CLI matrix

Java 17과 Java 21 JDK home을 명시한 상태에서 네 profile을 각각 두 번 생성한다. 각 행은 다음을
검증한다.

- exit 0과 `VALIDATED`
- exact output file set와 manifest/profile metadata
- compile, application context, initialize, tools/list, tools/call 다섯 stage 성공
- test-only raw JSON-RPC client의 exact Tool schema와 normalized result
- independent loopback recorder의 exact provider request와 exactly one call
- selected target `bin/java` boot
- 동일 profile 결정성과 profile 간 checksum 차이
- generated artifacts와 process output의 secret/JDK path 누출 부재

### 9.3 Failure cases

- unknown profile 또는 missing emitter는 analyzer 호출 전 fail-closed
- Spring AI 1 renderer에 Spring AI 2/noncanonical profile을 전달하면 생성 전 거부
- Java target home 부재·version mismatch·file identity drift는 `UNVERIFIED`, ZIP 미생성
- schema/result/upstream mismatch와 duplicate request는 validation 실패

## 10. 오류 처리

새 사용자 오류 코드는 추가하지 않는다. 기존 오류 vocabulary를 재사용한다.

- unknown ID: `TARGET_PROFILE_NOT_FOUND`
- emitter 미등록 또는 family mismatch: `TARGET_COMBINATION_UNSUPPORTED` 또는 기존 generation-stage
  `SOURCE_GENERATION_FAILED`
- target JDK resolution/runtime 실패: 기존 `UNVERIFIED` validation report
- generated adapter의 internal failure: fixed JSON-RPC error와 type-only safe diagnostic

오류 메시지는 profile input, 절대 경로, process output, secret을 포함하지 않는다.

## 11. 대안과 결정

### 권장: 독립 Spring AI 1 emitter 모듈

PRD FR-9.1을 직접 충족하고 1.x Jackson/MCP SDK 차이를 2.x 코드에서 격리한다. 기존 2.x
regression surface를 최소화하며 module registry가 이미 emitter 추가를 지원한다.

### 비권장: 공통 renderer 모듈을 먼저 추출

중복은 줄지만 안정화된 Spring AI 2 renderer 전체를 이동해야 해 이번 profile의 검증 범위를 크게
늘린다. 두 구현에서 실제 공통 경계가 확인된 후 별도 refactor로 판단한다.

### 거부: Spring AI 2 renderer에 version 조건문 추가

PRD의 emitter 분리 요구와 충돌하고 Jackson 2/3, annotation, exception API 차이를 한 템플릿에
누적한다.

## 12. 복잡도와 주의사항

profile 수를 `P`, generated file 수를 `F`, Tool 수를 `T`라 하면 registry 구성은
`O(P log P)` 시간과 `O(P)` 공간, 한 profile 생성은 기존과 동일한 `O(F + T log T)` 수준이다.
네 profile acceptance는 profile별 Gradle build와 boot가 지배하므로 전체 실행 시간은 `O(P)`다.

- 제약: generator 자체와 module tests는 Java 21에서 실행한다.
- 위험: Spring AI 1.1.8은 공식적으로 Boot 3.5.15와 release됐으므로 Boot 3.5.16 조합은 compile뿐
  아니라 context, MCP, provider error까지 이 계획의 live matrix로 검증해야 한다.
- 위험: 별도 emitter는 코드 중복을 만든다. 버전별 동작을 잘못 공유하는 위험보다 낮으며 최종
  four-profile review에서 중복 drift를 비교한다.
- 예외: `McpToolParam` annotation metadata에 의존하는 기능은 1.x에서 제공하지 않는다. exact
  schema와 Bean Validation이 외부 계약을 대신한다.

## 13. 공식 참고 자료

- Spring AI 1.1.8 release: <https://spring.io/blog/2026/06/12/spring-ai-1-1-8-1-0-9-avaialble-now/>
- Spring AI 1.1 MCP server starter: <https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-server-boot-starter-docs.html>
- Spring AI 1.1 stateless/streamable server docs: <https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-stateless-server-boot-starter-docs.html>
- Spring Boot 3.5.16 Gradle plugin: <https://docs.spring.io/spring-boot/3.5/gradle-plugin/introduction.html>
