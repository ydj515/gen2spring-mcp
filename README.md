# OpenAPI MCP Generator

OpenAPI 3.0.x와 3.1.x operation을 실행 가능한 Spring AI Streamable HTTP MCP 서버 프로젝트로 변환한다.
생성 결과는 선택한 Java target으로 컴파일하고, 애플리케이션 기동과 MCP
`initialize`·`tools/list`·대표 `tools/call`까지 검증한 뒤 ZIP으로 제공한다.

## 주요 기능

- Spring AI 1.1 / 2.0과 Java 17 / 21 조합 지원
- Spring Boot + Thymeleaf 기반 로컬 operation editor와 CLI 제공
- Tool schema, request binding, response normalization, retry·pagination 생성
- 최종 Tool IR 기반의 결정적 `RUNTIME_METADATA.json` 생성
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

## 생성 프로젝트 소스 구조

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
│       └── {Domain}McpToolCallbacks.java
└── runtime/                              # 프로덕션 안정성 보장 엔진
    ├── OpenApiOperationExecutor.java    # RestClient 호출, 타임아웃, 큐/동시성, 1MB 크기 제한
    ├── ResponseNormalizer.java           # 응답 정규화 및 에러 포맷팅
    ├── RuntimeTelemetry.java             # Micrometer 메트릭 및 W3C 분산 추적
    ├── ToolArgumentContext.java          # 파라미터 컨텍스트 전달
    └── RetryPolicy / PaginationPolicy    # 재시도 및 페이징 제어 (선택적 생성)
```

## 설계 배경: annotation-scanner 비활성화 이유

생성된 프로젝트의 `application.yml`에서 `spring.ai.mcp.server.annotation-scanner.enabled: false`를 기본 적용하는 이유는 다음과 같습니다.

1. **OpenAPI 스키마 무결성 보장 (Deterministic Tool Schema)**: Spring AI 어노테이션 스캐너는 Java 리플렉션을 통해 JSON Schema를 동적 생성하므로 OpenAPI 원본의 세부 제약(포맷, required 필드 순서, 추가 속성 제한 등)이 유실될 수 있습니다. 본 생성기는 OpenAPI 스펙으로부터 계산된 엄격한 JSON Schema 리터럴을 `DefaultToolDefinition.inputSchema`에 직접 주입하여 MCP 클라이언트와의 계약을 100% 보장합니다.
2. **도구 중복 등록 및 어노테이션 혼선 방지**: Spring AI MCP Server Starter의 스캐너는 일반 AI `@Tool`이 아닌 `@McpTool`을 스캔하며, Spring AI 버전 간(1.1의 Community 패키지 vs 2.0의 공식 패키지) 어노테이션 네임스페이스가 상이합니다. 어노테이션 스캐너를 끄고 명시적 `List<McpServerFeatures.SyncToolSpecification>` 빈으로 등록함으로써 도구 중복 등록과 스캔 누락을 방지합니다.
3. **런타임 파이프라인 및 안전한 에러 캡슐화**: 커스텀 `callHandler`를 통해 도구 호출 시 W3C 분산 추적 및 Micrometer 메트릭(`RuntimeTelemetry`)을 수집하고, 공급자 API 오류 시 원시 스택트레이스 대신 정제된 safe error payload(`isError=true`)를 안전하게 반환합니다.



## Hosted mode

Hosted mode는 외부 OIDC, PostgreSQL 17.9, private MinIO, 격리된 URL import와
rootless Docker Worker를 사용하는 Linux 단일-host 배포 경계다.

```bash
mise run hosted:config
mise run hosted:up
mise run hosted:acceptance
```

secret 준비, 백업·복구와 운영 절차는 [Hosted 배포 가이드](deploy/hosted/README.md)를 따른다.

validation report가 `VALIDATED`인 generation이 성공으로 완료되면 3개 다운로드 artifact와 함께
immutable Tool Catalog가 같은 PostgreSQL transaction에서 게시된다. Catalog는 OIDC 인증 owner에게만
다음 조회 API를 제공한다.

```text
GET /api/tool-catalogs
GET /api/tool-catalogs/{catalogId}
GET /api/tool-catalogs/{catalogId}/tools/{toolName}
```

Catalog metadata에는 Tool schema와 HTTP·policy·credential 요구사항만 포함하며 secret 값, 환경변수 이름,
사용자·작업 식별자와 로컬 경로는 포함하지 않는다. 기존 generation은 backfill하지 않는다.

Hosted mode의 Managed Runtime은 credential-free 단일 Tool Catalog를 bearer token으로 활성화해
`/mcp/{runtimeId}`에서 SDK 기반 `tools/list`와 bounded `tools/call`을 제공한다. provider HTTP는 mTLS
`provider-egress`만 통과한다. 이 기능은 생성 ZIP에 포함되는 서버도, 공유·권한·credential routing을
제공하는 Gateway가 아니다. v1은 single-replica session transport이며 multi-replica/stateless 운영은 지원하지 않는다.

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
- [Managed Runtime 구조도](docs/architecture/managed-mcp-runtime.html): 제어·실행·egress 격리 시각화
- [Hosted 배포 가이드](deploy/hosted/README.md): 구성, 기동, 백업·복구
- [설계 기록](docs/superpowers/specs/): 승인된 기능 설계
- [구현 계획](docs/superpowers/plans/): 단계별 검증 계획

## 지원 범위 요약

로컬 OpenAPI 3.0.x·3.1.x 파일, 주요 HTTP method, path/query/header parameter, JSON body,
primitive·enum·array·object와 non-recursive local `$ref`를 지원한다. OpenAPI 3.1은 기본 dialect와
단일 non-null type + `null` union만 bounded하게 정규화한다. remote `$ref`, custom dialect,
composed/recursive schema, Maven, WebFlux, async, SSE와 STDIO는 지원하지 않는다.
정확한 serialization 및 validation 경계는 [사용자 가이드](docs/user-guide.md#지원-범위와-제한)를 참고한다.
