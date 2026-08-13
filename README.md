# OpenAPI MCP Generator

OpenAPI 3.0.x와 3.1.x operation을 실행 가능한 Spring AI Streamable HTTP MCP 서버 프로젝트로 변환한다.
생성 결과는 선택한 Java target으로 컴파일하고, 애플리케이션 기동과 MCP
`initialize`·`tools/list`·대표 `tools/call`까지 검증한 뒤 ZIP으로 제공한다.

## 주요 기능

- Spring AI 1.1 / 2.0과 Java 17 / 21 조합 지원
- Spring Boot + Thymeleaf 기반 로컬 operation editor와 CLI 제공
- Tool schema, request binding, response normalization, retry·pagination 생성
- server secret 분리, bounded runtime, OpenTelemetry·Micrometer 기본 계약 제공
- compile, ApplicationContext, MCP protocol, loopback upstream을 포함한 fail-closed 검증
- PostgreSQL 17.9, private object storage, URL import, rootless sandbox 기반 hosted mode 제공

## 5분 안에 시작하기

생성기는 Java 21을 사용한다. Java 17 target을 검증하려면 Java 17도 설치해야 한다.

```bash
mise install
mise install java@17
```

### 로컬 UI

```bash
mise run ui
```

서버가 출력하는 `READY` JSON의 `url`을 브라우저에서 연다. 기본 local mode는
`numeric loopback only`이며 public multi-user service가 아니다.

```bash
mise run ui:test
mise run ui:build
```

직접 실행할 때는 다음 Gradle task와 Boot JAR를 사용한다.

```bash
./gradlew :apps:web:bootRun --quiet --no-daemon --non-interactive
./gradlew :apps:web:bootJar --no-daemon --non-interactive
java -jar apps/web/build/libs/web.jar
```

현재 UI는 파일 업로드, API endpoint 선택, 생성 설정의 세 단계로 동작한다. 지원하지 않는 endpoint도
이유와 함께 표시하지만 preview와 generation에는 선택 가능한 endpoint만 전달한다. local UI 입력 경계는
`local files only; no URL import`, capacity는 `one running plus one queued job`이다.

### CLI

```bash
export GEN2SPRING_JAVA_17_HOME="$(mise where java@17)"
mise exec -- ./gradlew :apps:cli:installDist --no-daemon --non-interactive

OPENAPI_MCP=apps/cli/build/install/openapi-mcp/bin/openapi-mcp
"$OPENAPI_MCP" profiles
"$OPENAPI_MCP" inspect \
  --spec apps/cli/src/integrationTest/resources/openapi/weather-api.yaml \
  --output /private/tmp/gen2spring-weather-analysis.json
"$OPENAPI_MCP" generate \
  --spec apps/cli/src/integrationTest/resources/openapi/weather-api.yaml \
  --config apps/cli/src/integrationTest/resources/config/weather-generation.yaml \
  --output /private/tmp/gen2spring-weather-mcp
```

CLI 설정, 검증 단계, 종료 코드, 생성 산출물과 runtime 계약은
[사용자 가이드](docs/user-guide.md)에서 설명한다.

## 지원 profile

`profiles`는 아래 네 항목을 ID 순서대로 항상 같은 JSON으로 출력한다.

| Profile ID | Java | Spring Boot | Spring AI |
| --- | ---: | ---: | ---: |
| `spring-ai-1.1-java17-mvc-streamable` | 17 | 3.5.16 | 1.1.8 |
| `spring-ai-1.1-java21-mvc-streamable` | 21 | 3.5.16 | 1.1.8 |
| `spring-ai-2.0-java17-mvc-streamable` | 17 | 4.1.0 | 2.0.0 |
| `spring-ai-2.0-java21-mvc-streamable` | 21 | 4.1.0 | 2.0.0 |

Java 21 기본 profile은 `spring-ai-2.0-java21-mvc-streamable`이다. 모든 profile은
Gradle 9.6.1, Spring MVC Sync, Streamable HTTP `/mcp`를 사용한다.

## Hosted mode

Hosted mode는 외부 OIDC, PostgreSQL 17.9, private MinIO, 격리된 URL import와
rootless Docker Worker를 사용하는 Linux 단일-host 배포 경계다.

```bash
mise run hosted:config
mise run hosted:up
mise run hosted:acceptance
```

secret 준비, 백업·복구와 운영 절차는 [Hosted 배포 가이드](deploy/hosted/README.md)를 따른다.

## 저장소 구조

```text
apps/                         실행 가능한 CLI, Web, Worker, import 서비스
modules/domain/               생성 및 runtime의 핵심 모델과 정책
modules/application/          유스케이스와 포트
modules/adapters/             OpenAPI, 저장소, 검증, emitter 구현
modules/bootstrap/            애플리케이션 조립
deploy/hosted/                hosted Compose와 운영 스크립트
docs/                         사용자·제품·아키텍처 문서
```

## 개발과 검증

전체 단위·통합 검증과 배포 산출물을 만들려면 다음 명령을 사용한다.

```bash
GEN2SPRING_JAVA_17_HOME="$(mise where java@17)" \
  mise exec -- ./gradlew clean test integrationTest \
  :apps:cli:installDist :apps:web:bootJar \
  --no-daemon --non-interactive
```

Gradle Wrapper JVM은 host의 Java 21로 시작될 수 있다. generated compile/test toolchain
탐색만 verified target JDK로 제한하며, 애플리케이션은 target home의 Java로 기동한다.
세부 JDK 경계와 Windows 동작은 사용자 가이드에 정리되어 있다.

## 문서

- [사용자 가이드](docs/user-guide.md): 설치, CLI, 설정, runtime, 검증, 지원 범위
- [제품 요구사항](docs/prd.md): 기능 요구와 구현 상태
- [Hosted 플랫폼 구조도](docs/architecture/hosted-generation-platform.html): 배포·데이터 흐름 시각화
- [Hosted 배포 가이드](deploy/hosted/README.md): 구성, 기동, 백업·복구
- [설계 기록](docs/superpowers/specs/): 승인된 기능 설계
- [구현 계획](docs/superpowers/plans/): 단계별 검증 계획

## 지원 범위 요약

로컬 OpenAPI 3.0.x·3.1.x 파일, 주요 HTTP method, path/query/header parameter, JSON body,
primitive·enum·array·object와 non-recursive local `$ref`를 지원한다. OpenAPI 3.1은 기본 dialect와
단일 non-null type + `null` union만 bounded하게 정규화한다. remote `$ref`, custom dialect,
composed/recursive schema, Maven, WebFlux, async, SSE와 STDIO는 지원하지 않는다.
정확한 serialization 및 validation 경계는 [사용자 가이드](docs/user-guide.md#지원-범위와-제한)를 참고한다.
