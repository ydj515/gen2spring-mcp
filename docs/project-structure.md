# 프로젝트 구조

## 모듈 배치

실제 Gradle 프로젝트 목록은 [settings.gradle.kts](../settings.gradle.kts)가 기준이다.
생성기의 Java 패키지는 `io.gen2spring.mcp` 아래에 있으며 생성 결과의 사용자 지정 패키지와 구분한다.

```text
apps/
  cli/                  profiles, inspect, generate 진입점
  web/                  local editor와 hosted 제어 API·Thymeleaf 페이지
  worker/               작업 선점, heartbeat, 격리 실행과 결과 게시
  import-runner/        격리된 URL import 작업 프로토콜
  fetch-gateway/        URL import의 제한된 HTTP fetch
  runtime/              bearer 인증과 Managed MCP 서버
  provider-egress/      Managed Runtime의 제한된 provider HTTP 실행
modules/
  domain/               OpenAPI·Tool·profile·정책·작업·runtime 모델
  application/          계획·생성, hosted/managed 유스케이스와 포트
  adapters/
    configuration/      엄격한 생성 설정 파싱
    openapi/            OpenAPI 입력 분석과 canonical schema 정규화
    filesystem/         workspace, checksum, manifest, report, ZIP
    validation/         JDK·빌드·기동·MCP·mock upstream 검증
    emitters/
      support/          공유 scaffold·스키마·소스 생성 지원
      mcp-runtime/      생성 프로젝트의 공통 MCP 실행 코드
      spring-ai-1/      Spring AI 1 계열 생성
      spring-ai-2/      Spring AI 2 계열과 WebFlux Async 생성
    persistence-postgres/  내구성 작업·Catalog·runtime 저장
    object-storage-s3/     private specification·artifact 저장
    container-runtime/     rootless sandbox 실행
    url-fetch/             import gateway 연결
    cryptography/          token·credential 암호 처리
    provider-egress/       provider gateway 연결
    mcp-java-sdk/          Managed Runtime MCP SDK 연결
  bootstrap/            생성기 객체 그래프 조립
```

`modules/adapters/mcp-java-sdk`는 Managed Runtime 어댑터다. 다운로드 프로젝트의 SDK 생성 옵션은
공유 emitter와 Spring AI 1 계열 scaffold 경로도 사용한다. 이름만 보고 두 책임을 같은 것으로 보지 않는다.

`modules/application`은 hosted·managed 기능 아래에서 외부 저장소·client 계약을 `port/out`에,
delivery가 호출하는 계약을 필요한 경우 `port/in`에 둔다. 업무 흐름과 결과 모델은 해당 기능 패키지에 남는다.
`modules/adapters/persistence-postgres`는 account·job·catalog·runtime 등 기능 패키지에서 이 포트를 구현한다.
runtime telemetry 계약은 업무 domain 모델이 아닌 application의 toolmodel 아래에 둔다.

## 앱별 패키지

| 앱 | 책임 패키지 |
| --- | --- |
| CLI | `presentation`, `application`, `infrastructure`, `config` |
| Web | `presentation`, `application`, `infrastructure`, `config` (각 계층 아래 local·hosted 책임 분리) |
| Fetch Gateway | `presentation/fetch`, `application/fetch`, `infrastructure/client/fetch`, `config` |
| Import Runner | `presentation/job`, `application/imports`, `infrastructure/analysis`, `infrastructure/client/fetch`, `config` |
| Provider Egress | `presentation/provider`, `application/provider`, `infrastructure/client/provider`, `config` |
| Runtime | `presentation/mcp`, `presentation/security`, `config` |
| Worker | `application/worker`, `infrastructure/readiness`, `infrastructure/scheduling`, `config` |

앱 루트에는 실행 진입점을 둔다. transport 구현과 package-private 협력자는 같은 책임 패키지에 두고,
다른 패키지에 필요한 facade만 공개한다. 파일 수나 줄 수를 맞추기 위해 패키지를 나누지 않는다.
각 앱의 `*PackageArchitectureTest`가 책임 패키지와 진입점 위치를 검사한다.
Worker의 application은 작업 순서를 소유하며 scheduling adapter는 input port를 통해 호출한다.
준비 상태의 DB·S3·Docker 검사는 infrastructure에 두고 config에서 조립한다.

## 구현 탐색 시작점

생성 전용 command·usecase·planning·validation·port는 `io.gen2spring.mcp.application.generation` 아래에 모은다.
Hosted·managed 실행과 공유하는 Tool model과 runtime metadata는 기존 application 패키지에 둔다.

- [GenerationPipeline](../modules/application/src/main/java/io/gen2spring/mcp/application/generation/usecase/GenerationPipeline.java): 생성 단계와 검증 후 패키징
- [GenerationPlanner](../modules/application/src/main/java/io/gen2spring/mcp/application/generation/planning/GenerationPlanner.java): profile·Tool 계획
- [HostedWorker](../modules/application/src/main/java/io/gen2spring/mcp/application/hosted/worker/HostedWorker.java): 격리 실행과 게시
- [ManagedRuntimeMigrationService](../modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/ManagedRuntimeMigrationService.java): Catalog 전환
- [editor.html](../apps/web/src/main/resources/templates/editor.html): 화면 구조

`src/test`는 모듈 단위 계약, `src/integrationTest`는 해당 모듈이 정의한 실행 통합 검증을 담는다.
생성 프로젝트의 디렉터리 구조는 [사용자 가이드](user-guide.md#생성-프로젝트-소스-구조)를 따른다.

## 저장소 전체 검증 harness

루트 [src/test/java](../src/test/java)는 ArchUnit과 PMD 설정 회귀 테스트를 소유한다.
루트에 제품 Java 소스를 추가하지 않으며, 테스트용 역방향 참조 fixture는 production class import에서 제외한다.
아키텍처 검사 때문에 별도 제품 모듈이나 앱 간 runtime 의존을 추가하지 않는다.
