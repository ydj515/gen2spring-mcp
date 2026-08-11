# Spring AI 2 Java 17 Compatibility Profiles 설계

## 1. 목적

PRD P1의 첫 미완료 수직 슬라이스로 compatibility profile을 단일 상수에서 immutable registry로
일반화하고, Spring AI 2.0.0 + Spring Boot 4.1.0 조합을 Java 17과 Java 21에서 모두 생성·검증한다.

이 슬라이스가 끝나면 다음 profile이 실제로 동작한다.

- `spring-ai-2.0-java17-mvc-streamable`
- `spring-ai-2.0-java21-mvc-streamable`

생성기 자체와 generator module test JVM은 Java 21을 유지한다. Java 17은 생성된 프로젝트의
toolchain, compile/test, boot, MCP validation에 적용한다.

## 2. 범위

### 포함

- 결정적 순서의 immutable compatibility registry
- profile ID 기반 generator resolution
- CLI `profiles`와 strict generation YAML의 두 profile 지원
- Spring AI 2 renderer의 Java 17·21 공용화
- 선택한 target JDK로 generated project compile/test/boot
- profile별 Gradle, manifest, README, Dockerfile 일치
- Java 17과 Java 21 각각의 initialize, `tools/list`, `tools/call` validation
- 기존 Java 21 P0/P1 계약의 backward compatibility

### 제외

- Spring AI 1.x emitter
- runtime metrics와 OpenTelemetry tracing
- Windows validation host
- Generator API와 operation editor UI
- Maven, WebFlux, async, SSE, STDIO

이 제외 항목은 승인된 P1 후속 순서에 따라 별도 design, plan, implementation cycle로 진행한다.

## 3. 고정 profile matrix

두 profile은 Java version을 제외한 Spring stack을 공유한다.

| 속성 | Java 17 profile | Java 21 profile |
|---|---|---|
| Spring Boot | 4.1.0 | 4.1.0 |
| Spring AI | 2.0.0 | 2.0.0 |
| Gradle | 9.6.1 | 9.6.1 |
| Web stack | MVC | MVC |
| Programming model | SYNC | SYNC |
| Transport | STREAMABLE_HTTP | STREAMABLE_HTTP |
| Generator module | generator-spring-ai-2 | generator-spring-ai-2 |
| Template | spring-ai-2-v2 | spring-ai-2-v2 |
| Runtime | 0.2.0 | 0.2.0 |

Java 21 profile의 ID는 변경하지 않는다. 기존 `CompatibilityProfile.p0()` 호출은 Java 21 profile을
반환하는 compatibility alias로 유지하되, production selection은 registry를 사용한다.

## 4. Profile model과 registry

`CompatibilityProfile`은 target 외에 생성 결과를 결정하는 다음 값을 보존한다.

```java
public record CompatibilityProfile(
        String id,
        TargetPlatform target,
        String generatorModule,
        String templateVersion,
        String runtimeVersion,
        String gradleVersion,
        String containerImage) {}
```

`containerImage`는 tag와 multi-platform index digest를 함께 고정한다.

```text
Java 17:
eclipse-temurin:17.0.19_10-jre-noble@sha256:543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8

Java 21:
eclipse-temurin:21.0.11_10-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64
```

Registry API는 다음과 같다.

```java
public final class CompatibilityProfileRegistry {
    public static CompatibilityProfileRegistry defaults();
    public static CompatibilityProfileRegistry of(List<CompatibilityProfile> profiles);
    public List<CompatibilityProfile> profiles();
    public Optional<CompatibilityProfile> find(String id);
}
```

- profile은 ID 오름차순으로 노출한다.
- null, blank, duplicate ID와 duplicate target 조합을 생성 시 거부한다.
- 반환 list와 profile은 immutable이다.
- unknown profile은 registry에서 empty를 반환하고 CLI/core boundary가 기존 safe error code로 변환한다.
- registry는 generator implementation에 의존하지 않는다.

## 5. Generator resolution

`GenerationPipeline`은 더 이상 하나의 고정 profile과 generator를 보존하지 않는다. 요청의
`targetProfileId`를 registry에서 먼저 resolve한 뒤 generator module로 emitter를 선택한다.

```java
public final class ProjectGeneratorRegistry {
    public ProjectGenerator require(CompatibilityProfile profile);
}
```

- lookup key는 `generatorModule`이다.
- 동일 Spring AI 2 emitter가 Java 17과 21 context를 모두 렌더링한다.
- profile 미존재는 `TARGET_PROFILE_NOT_FOUND`다.
- profile은 존재하지만 emitter가 없거나 target 조합이 emitter 범위 밖이면
  `TARGET_COMBINATION_UNSUPPORTED`다.
- generator resolution은 Tool IR 생성 전에 끝내 fail-closed한다.

`GenerationContext.profile()`은 resolve된 canonical registry instance를 전달한다. 요청에서 profile
세부 값을 재구성하지 않는다.

## 6. CLI contract

`profiles` 명령은 두 profile을 ID 순으로 출력하고 각 항목에 기존 target metadata와 다음 필드를
추가한다.

```json
{
  "gradleVersion": "9.6.1",
  "containerImage": "eclipse-temurin:...@sha256:..."
}
```

`GenerationConfigurationReader`는 같은 registry instance를 주입받아 `targetProfileId`를 검증한다.
reader, profiles command, pipeline이 서로 다른 hard-coded allow-list를 갖지 않는다.

기존 기본 생성 fixture는 Java 21 ID를 유지한다. Java 17 fixture는 별도 파일로 추가하며 profile ID
외 Tool·response normalization·validation input은 동일하게 유지해 profile 차이만 검증한다.

## 7. Spring AI 2 renderer

Spring AI 2 renderer는 profile 전체 equality 대신 다음 family invariant를 검사한다.

- `generatorModule == generator-spring-ai-2`
- Spring AI version `2.0.0`
- Spring Boot version `4.1.0`
- Java version 17 또는 21
- Gradle `9.6.1`
- MVC, SYNC, STREAMABLE_HTTP

`build.gradle.kts`의 Java toolchain, generated README와 Dockerfile은 profile 값을 사용한다. 생성되는
Java source는 Java 17 language boundary를 지킨다. record, sealed type, text block과 pattern matching
`instanceof`는 허용하지만 Java 21 collection API나 unnamed pattern은 사용하지 않는다.

`SpringAi2ProjectGenerator`는 context의 canonical profile로 renderer를 구성한다. 기본 생성자는
Java 21 compatibility를 유지하지만 production pipeline은 registry-resolved context를 사용한다.

## 8. Target JDK validation

`ValidationRequest`는 canonical `CompatibilityProfile`을 포함한다. Validator는 target Java version을
현재 generator JVM에서 추정하지 않는다.

새 `JavaRuntimeResolver`는 target runtime을 다음 순서로 resolve한다.

1. `GEN2SPRING_JAVA_<version>_HOME`, 예: `GEN2SPRING_JAVA_17_HOME`
2. 현재 `java.home`의 feature version이 target과 같으면 현재 runtime
3. 없으면 fixed safe validation failure

Resolver는 absolute normalized home, `bin/java` regular executable, 실행 결과의
`java.specification.version` 일치를 검증한다. symlink swap과 잘못된 version을 fail-closed한다. 실제
path와 프로세스 output은 validation report, CLI output, generated source, manifest, ZIP에 기록하지
않는다.

Generated Gradle invocation에는 다음 system property를 전달한다.

```text
-Dorg.gradle.java.installations.auto-detect=false
-Dorg.gradle.java.installations.auto-download=false
-Dorg.gradle.java.installations.paths=<validated target home>
```

Gradle wrapper 자체는 generator host JVM에서 시작할 수 있지만 compile/test toolchain은 target home만
사용한다. Generated boot JAR는 `<target home>/bin/java -jar`로 실행한다. Java 17 profile은 실제 Java
17 process에서 ApplicationContext와 MCP validation을 통과해야 한다.

테스트 환경은 이미 설치된 `mise` Java 17 home을 명시적으로 주입한다. Production resolver는
`mise` command나 사용자 shell 초기화에 의존하지 않는다.

## 9. Manifest와 reproducibility

Manifest의 `gradleVersion` hard-code를 제거하고 profile 값을 기록한다. 다음 값은 source와 manifest가
항상 동일해야 한다.

- target profile ID
- Java version
- Spring Boot version
- Spring AI version
- Gradle version
- template version
- runtime version
- container image

Profile metadata 변경은 source checksum을 바꾼다. 같은 입력과 같은 profile은 canonical source entry,
source checksum, manifest, ZIP을 반복 생성해도 동일해야 한다.

## 10. Docker output

Dockerfile은 profile의 digest-pinned image를 사용한다. floating `17-jre`, `21-jre`, `latest`는
사용하지 않는다.

```dockerfile
FROM <profile.containerImage>
WORKDIR /app
COPY build/libs/<artifact>.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`.dockerignore`는 build context를 Dockerfile과 선택한 boot JAR로 제한한다. Docker image build 자체는
로컬 Docker daemon을 필수화하지 않으며 renderer/source contract와 optional container smoke를
분리한다. 전체 container smoke는 Docker가 있는 CI job에서 profile matrix acceptance로 실행한다.

## 11. Error handling과 security

- unknown profile은 safe user error이며 fallback profile을 선택하지 않는다.
- target JDK가 없거나 version이 다르면 validation은 `UNVERIFIED`, ZIP은 생성하지 않는다.
- profile/generator mismatch는 source rendering 전에 거부한다.
- JDK home, local username, environment dump, process command 전체를 report에 기록하지 않는다.
- target Java home environment 외 기존 sanitized process environment 정책을 완화하지 않는다.
- secret binding, provider URL, mock secret은 profile metadata에 포함하지 않는다.
- Docker는 numeric non-root user로 실행한다.

## 12. 테스트 전략

### Domain과 core

- 두 profile의 exact pinned values와 결정적 order
- duplicate ID/target, blank metadata, mutable input 거부
- `p0()` compatibility alias
- profile ID와 generator module resolution
- unknown profile과 missing emitter의 exact safe error
- manifest의 profile-derived Gradle/container metadata

### CLI

- `profiles` exact 2-item JSON과 order
- Java 17/21 strict YAML round trip
- unknown profile fail-closed
- Java 17 runtime home missing/wrong version safe failure와 no path leak

### Renderer와 generated project

- Java 17/21 build toolchain, README, Dockerfile, `.dockerignore`
- generated source의 Java 17 compile
- Java 17/21 각각 ApplicationContext, initialize, `tools/list`, representative `tools/call`
- normalized success, provider error, invalid argument, internal error 계약 동일성
- selected target JDK process identity 확인

### End-to-end

- 같은 OpenAPI/config를 두 profile로 생성해 각 archive가 `VALIDATED`
- manifest/source/ZIP determinism
- Java 21 기존 P0/P1 journey 회귀
- secret/absolute JDK path/process output 비노출

## 13. 복잡도

Profile 수를 `P`, emitter module 수를 `G`라 하면 registry 생성은 `O(P log P)`, profile lookup과
generator lookup은 평균 `O(1)`이다. 현재 `P=2`, `G=1`이다.

Generated project validation 비용은 profile별 Gradle compile/test와 MCP boot가 지배하며 전체 matrix는
`O(P)`이다. Registry의 추가 메모리는 `O(P)`이고 generated runtime 요청 경로에는 추가 비용이 없다.

## 14. 완료 조건

- 두 profile이 CLI, renderer, validator, manifest, README, Docker에서 동일하게 resolve된다.
- Java 17과 21 generated project가 각각 선택한 JDK로 compile/test/boot된다.
- 두 profile 모두 실제 MCP initialize, `tools/list`, `tools/call`을 통과한다.
- 기존 Java 21 기능과 security/error contract가 유지된다.
- exact full repository acceptance와 `git diff --check`가 성공한다.
- Critical/Important review finding이 남지 않는다.

## 15. 공식 참고 자료

- Spring Boot 4.1 system requirements: <https://docs.spring.io/spring-boot/system-requirements.html>
- Gradle Java toolchains: <https://docs.gradle.org/current/userguide/toolchains.html>
- Eclipse Temurin official images: <https://hub.docker.com/_/eclipse-temurin>
- Spring AI MCP Streamable HTTP: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html>
