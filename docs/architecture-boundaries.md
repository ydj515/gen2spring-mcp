# 아키텍처 경계

## 중심 모델과 외부 구현

Domain은 canonical OpenAPI, Tool IR, 호환 profile과 순수 정책을 소유한다.
Application은 생성 단계, Tool 정책, hosted 작업과 managed 실행을 조율하며 외부 기능은 포트로 호출한다.
Swagger Parser, Spring AI, 파일시스템, PostgreSQL과 컨테이너 구현은 adapter에서 연결한다.

```text
domain <- application <- adapters
              ^             ^
              +-- bootstrap-+
                      ^
                  CLI / Web
```

이 그림은 생성기 조립의 기본 방향이다. Worker·Runtime·egress 앱은 자신의 config에서 필요한 adapter를
직접 조립한다. bootstrap을 요청마다 거치는 서비스 계층으로 사용하지 않는다.
현재 [application 빌드](../modules/application/build.gradle.kts)는 domain 외에 Jackson Databind에도
의존한다. 따라서 application 전체를 외부 라이브러리가 없는 계층으로 설명하지 않는다. 다만
`application/**/port/in`과 `application/**/port/out`의 계약은 Jackson을 노출하지 않는다.

실제 25개 모듈의 직접 의존과 `api`/`implementation` 범위는
[모듈 의존성 다이어그램](module-dependencies.md)에서 확인할 수 있다.

## 멀티모듈과 내부 패키지의 역할

Gradle 모듈은 배포 단위와 외부 기술 의존성을 나누고, 모듈 내부 패키지는 기능과 책임을 나눈다.
`apps`에는 실행 진입점과 해당 앱의 presentation/application/infrastructure/config를 둔다.
공유 use case와 output port는 `modules/application`, 순수 모델과 정책은 `modules/domain`이 소유한다.
이미 infrastructure 역할인 adapter 모듈 안에 application/domain 계층을 다시 만들지 않는다.
외부 구현 하나를 연결하는 작은 모듈은 평면 패키지를 유지한다.

다음은 `settings.gradle.kts`의 production 모듈 25개에 대응하는 패키지 경계다.
패키지는 공통 접두사 `io.gen2spring.mcp`를 생략했다.

| 모듈 | 내부 구성과 유지 기준 |
| --- | --- |
| `apps/cli` | `app.cli`의 진입점, `presentation`, `application`, `infrastructure.file/logging`, `config`. CLI 해석과 파일 접근을 분리한다. |
| `apps/web` | `app.web` 아래 계층별로 `local`/`hosted` 기능을 나눈다. HTTP 응답은 presentation, 제출·조회 순서는 application, 파일·실행기 구현은 infrastructure가 담당한다. |
| `apps/worker` | `app.worker.application.worker`가 작업·heartbeat·maintenance를 조율한다. `infrastructure.scheduling/readiness`는 스레드 수명과 시작 조건을 담당한다. |
| `apps/runtime` | `app.runtime.presentation.mcp/security`, 실행 자원을 관리하는 `infrastructure.execution`, `config`로 구성한다. 업무 실행은 공유 application을 호출하므로 단순 위임용 앱 application 계층을 추가하지 않는다. |
| `apps/fetch-gateway` | `app.fetch` 아래 fetch presentation, application과 `infrastructure.client.fetch`를 분리한다. HTTP transport는 output port 뒤에 둔다. |
| `apps/provider-egress` | `app.provideregress` 아래 provider presentation/application/client infrastructure/config를 분리한다. 요청 정책과 실제 provider 통신을 구분한다. |
| `apps/import-runner` | `app.importer.presentation.job`은 프로토콜, application은 import 순서, infrastructure는 gateway 호출·격리 workspace 분석을 담당한다. |
| `modules/domain` | `domain.specification/tool/profile/execution/response/runtime/platform`의 기능별 모델·불변식. 저장소·Spring·JSON 구현은 포함하지 않는다. |
| `modules/application` | `application.generation/hosted/managed/toolmodel/runtime`의 기능별 흐름과 포트. 공유 값과 서비스가 함께 있던 hosted/managed 기능은 `service`를 분리한다. Catalog처럼 결과가 별도 패키지에 있는 기능은 기존 서비스 위치를 유지한다. Generation의 조율은 `usecase`, 입출력 값은 `model/result/progress/analysis`로 분리한다. |
| `modules/bootstrap` | `bootstrap.GeneratorRuntime` 하나가 생성기 객체 그래프를 조립한다. 앱 config만 이 조립 결과를 분해해 주입한다. |
| `adapters/configuration` | `adapter.configuration`의 설정 parser와 오류. 한 가지 입력 형식 경계이므로 평면 구성을 유지한다. |
| `adapters/container-runtime` | `adapter.container`의 Docker 실행 adapter, command runner와 결과 수집기. 컨테이너 수명·제한·실패 변환이 한 기술 경계 안에서 협력한다. |
| `adapters/cryptography` | `adapter.cryptography`의 자격 증명·import target·runtime token 구현. 암호화 계약은 application이 소유한다. |
| `adapters/filesystem` | `adapter.filesystem.generation`은 생성 파일·검증 workspace·checksum·archive, `imports`는 임시 명세 분석을 담당한다. |
| `adapters/openapi` | `adapter.openapi.swagger`의 Swagger 변환·schema 정규화·budget. Swagger 타입을 이 패키지 밖의 application 계약에 노출하지 않는다. |
| `adapters/object-storage-s3` | `adapter.storage`의 S3 구현과 bucket readiness probe. object storage 포트를 구현하는 단일 기술 패키지다. |
| `adapters/persistence-postgres` | `adapter.persistence.account/catalog/credential/job/policy/query/runtime/specification/storage/worker`로 저장 대상별 분리. SQL 매핑과 원자적 갱신은 해당 adapter에 둔다. |
| `adapters/provider-egress` | `adapter.provideregress`의 provider 호출 client와 wire codec. provider-egress 앱의 서버 구현과 모듈 의존으로 연결하지 않는다. |
| `adapters/url-fetch` | `adapter.urlfetch`의 gateway client와 mTLS 설정. fetch-gateway 앱의 클래스에 의존하지 않는다. |
| `adapters/mcp-java-sdk` | `adapter.mcp`의 SDK Tool 등록과 실행 결과 변환. SDK 타입을 application input port에 노출하지 않는다. |
| `adapters/validation` | `adapter.validation`의 공개 validator, `project`의 빌드·검증 순서, `process`의 프로세스 수명, `runtime`의 기동 확인, `mcp`의 프로토콜 client, `upstream`의 모의 provider 검증. |
| `adapters/emitters/support` | `adapter.emitter.support.project`의 빌드 scaffold와 `source`의 Java 소스 표현. 특정 Spring AI 계열을 참조하지 않는다. |
| `adapters/emitters/mcp-runtime` | `adapter.emitter.mcpruntime`의 등록 모델과 renderer. 두 클래스가 하나의 MCP 등록 출력 계약을 담당한다. |
| `adapters/emitters/spring-ai-1` | `adapter.emitter.springai1`은 application 포트 구현, `render`는 계열별 소스·프로젝트 렌더링. 세부 renderer의 package-private 접근을 유지한다. |
| `adapters/emitters/spring-ai-2` | `adapter.emitter.springai2`는 application 포트 구현, `render`는 sync/async 렌더링 전략과 소스 구성. 전략 구현은 같은 렌더링 경계 안에 둔다. |

표의 `adapters/*`는 `modules/adapters/*`를 의미한다. module 경계 자체가 계층을 표현하므로
각 모듈에 같은 네 가지 계층이나 모든 service의 interface를 기계적으로 추가하지 않는다.
이는 참조한 dev-standards의 `frameworks/spring.md`, `languages/java.md`,
`architectures/layered-clean.md`에서 정한 생성자 주입, 계층 방향, 포트 소유권과 실질적 경계 기준을 따른다.

### 계약과 구현 의존성

- application의 공개 계약이 domain 타입을 사용하므로 Gradle에서 domain을 `api`로 노출한다.
  CLI와 Web은 직접 사용하는 중심 모듈을 명시하며 bootstrap의 전이 의존에만 기대지 않는다.
- bootstrap의 `api`는 공개 조립 결과에 나타나는 domain/application/configuration 타입에만 사용한다.
  parser·emitter·filesystem·validator의 다른 구현은 `implementation`으로 숨긴다.
- `Catalog*`, `Audit*`, runtime transition 조회 결과와 `AnalysisResult`는 application 계약이다.
  저장소 포트와 delivery가 이를 공유하고, presentation이 `port.out` 타입을 직접 받지 않는다.
- 서비스와 포트용 값 타입은 패키지를 구분한다. `service → port.out → 값 타입` 방향을 유지하여
  포트가 서비스를 포함한 패키지로 되돌아가지 않게 한다. Generation planning과 output port도
  `usecase`의 조율 구현 대신 `model/result/progress` 계약을 참조한다.
- Web 인증 실패는 `presentation.error.HostedAuthenticationFailure`로 전달한다. 오류 매퍼가
  security resolver의 내부 타입을 참조하지 않으므로 `security → error` 방향을 유지한다.
- 공유 `SpecificationImportService`는 byte 기반 분석 포트를 호출한다. 임시 파일 생성과 정리는
  filesystem adapter가 소유한다. 이 서비스는 현재 실행 앱에 조립되어 있지 않은 재사용 use case이며,
  실제 import-runner의 격리 workspace 수명은 해당 앱 infrastructure가 계속 소유한다.
- `ManagedToolSessionService`는 Runtime에 고정된 카탈로그를 조회하고 checksum을 검증한 뒤 허용된 Tool 목록과
  호출 계약을 반환한다. Runtime config는 이 결과를 MCP SDK에 연결하며 조회·노출 정책을 직접 구현하지 않는다.
- `ManagedToolExecutor`는 재시도·페이지 처리·전체 제한 시간·결과 변환을 소유하고 `ManagedExecutionTasks`로 비동기 실행을 요청한다.
  Runtime의 `BoundedManagedExecutionTasks`가 제한된 스레드·대기열, 거절, 취소와 종료를 구현한다. 실행 자원은
  하나의 use case 객체가 독점 소유하며, Spring이 use case를 종료하면 포트를 통해 함께 닫는다.
- `HostedWorker`는 lease 갱신·취소·stale 판단을 소유하고 `LeaseMonitorScheduler`로 주기 실행을 요청한다.
  Worker infrastructure가 모니터별 스레드 생성·중지·대기와 인터럽트 복구를 담당한다.
- transaction의 업무 단위는 application의 포트 호출 계약으로 정한다. PostgreSQL adapter는
  claim·완료 게시·runtime 전환 같은 단일 원자적 명령 안에서 SQL transaction을 실행한다.
  여러 저장소에 걸친 새 use case는 이 단위를 먼저 정의해야 한다.

## 생성 대상 분리

Tool IR은 Spring AI 버전이나 Swagger Parser 타입을 노출하지 않는다. profile registry가 생성 대상의
Java·Boot·Spring AI·build tool·web stack을 결정하고 emitter registry가 구현을 선택한다.
공유 scaffold와 schema 로직을 재사용하되 Jackson 2/3 및 MVC/Async 등록 차이는 소유 emitter에 둔다.
저장소 내부 패키지를 이동해도 생성 소스의 패키지·경로·서비스 등록 리소스는 별도 출력 계약이다.

## Web와 실행 경계

Local Web은 loopback 단일 사용자 실행과 메모리 작업 관리에 맞춘다. Hosted Web은 OIDC 소유권 확인과
내구성 작업 접수에 집중하며 Docker socket을 갖지 않는다. Worker가 격리 프로세스를 실행한다.
Managed Runtime은 플랫폼 저장소에 접근하지만 provider HTTP는 mTLS egress 서비스에 위임한다.
URL import의 fetch gateway와 실행 중 provider egress는 서로 다른 책임이다.
Hosted 제출의 업로드·분석·작업 접수 순서는 Web application의 `HostedSubmissionService`가 소유한다.
임시 파일을 통한 생성기 실행과 요청 snapshot 직렬화는 Web infrastructure adapter가 처리한다.

## Spring 조립과 오류

- 앱 진입점과 component scan 기준을 함께 유지한다. 보안 필터는 servlet 자동 등록을 끄고 보안 체인에서 한 번 실행한다.
- `RestClient.Builder`·`WebClient.Builder`는 주입된 builder를 복제하여 provider 전용 제한을 적용한다.
- 전용 JSON mapper의 null·응답 계약과 telemetry의 민감 정보 제거를 호스트 기본 설정으로 덮어쓰지 않는다.
- Domain/애플리케이션 오류 코드는 경계에서 안전한 CLI 또는 HTTP 응답으로 변환한다. 원본 예외·프로세스 출력·경로를 공개 응답으로 전파하지 않는다.
- 외부 provider의 예상된 실행 실패는 MCP `isError=true` 결과다. 이를 생성기 자체 실패나 모든 JSON-RPC 오류와 동일하게 처리하지 않는다.

[Spring 조립 경계](user-guide.md#spring-조립-경계)와 [생성 파이프라인](generation-pipeline.md)에 구체적인 계약을 정리한다.

## 변경 시 확인

패키지 이동은 모든 source set, Spring 설정, Gradle main class, 서비스 로더 리소스를 함께 확인한다.
Java import 검사는 [루트 빌드](../build.gradle.kts)의 `verifyJavaImportStyle`이 담당하고 test task에도 연결된다.
프로토콜 문자열, 이름 충돌, 검증 대상 문자열은 단순 치환하지 않는다.

## 자동 의존 방향 검사

[ArchUnit 규칙](../src/test/java/io/gen2spring/mcp/architecture/ArchitectureRules.java)은 production 바이트코드를
검사한다. 앱별 `*PackageArchitectureTest`는 클래스 위치와 해당 앱의 계층 의존 방향을 확인한다.

| 출발 계층 | 허용·금지 경계 |
| --- | --- |
| Domain | domain·JDK만 허용하고 SQL API는 금지 |
| Application | application·domain·JDK·`javax.lang.model`·기존 Jackson만 허용 |
| Application port | application·domain·JDK만 허용. 공유·앱 port/command/result에서 service·generation usecase/planning 구현 참조 금지 |
| Adapter | bootstrap·app 참조와 다른 adapter 직접 참조 금지. 공유 emitter 예외만 허용 |
| Bootstrap | 앱을 참조하지 않고 생성기 객체 그래프 조립 |
| App | 서로 다른 앱을 직접 참조하지 않음 |
| 모든 앱 application | 해당 앱 application·공유 application·domain·JDK만 허용. 앱 간 참조는 별도 금지 |
| 모든 앱 presentation | infrastructure·config·bootstrap·공유 adapter·application output port 직접 참조 금지 |
| 모든 앱 infrastructure | presentation·config·bootstrap 직접 참조 금지 |
| Controller | concrete persistence/storage adapter, SQL·Spring JDBC/repository, jOOQ·MyBatis 직접 참조 금지 |
| Fetch Gateway | application → presentation/infrastructure/config, presentation → infrastructure/config, infrastructure → presentation/config 참조 금지 |
| Provider Egress | application → presentation/infrastructure/config/adapter, presentation → infrastructure/config/adapter, infrastructure → presentation/config 참조 금지 |
| Import Runner | application → presentation/infrastructure/config/adapter, presentation → infrastructure/config/adapter, infrastructure → presentation/config 참조 금지 |
| CLI | application → presentation/infrastructure/config/adapter/Spring, presentation → infrastructure/config/adapter, infrastructure → config 참조 금지 |
| Web | application → presentation/infrastructure/config/Spring·Servlet·Jackson, infrastructure → presentation/config, presentation → 내부 infrastructure 참조 금지 |
| Runtime | 다른 앱과 동일한 presentation 규칙 적용 |
| Worker | application → infrastructure/config/adapter/Spring, infrastructure → config 참조 금지 |

공유 emitter의 허용 방향은 `springai1/springai2 → mcpruntime/support`, `mcpruntime → support`다.
계열 간 직접 참조와 공유 코드에서 계열 코드로 향하는 역방향은 금지한다. Application의 Jackson 사용은
현재 canonical JSON 모델 계약으로 명시적으로 허용한다. application의 직접 파일 접근과 executor·scheduler 구현 의존은
금지한다. `Path`, byte array, stream 같은 입출력 값 자체는 포트 계약에서 사용할 수 있다.
Emitter의 `render`는 상위 포트 구현을 참조하지 않는다. Validation 하위 패키지에는 순환을 허용하지 않고
process/runtime/MCP/upstream 구현에서 project orchestration으로 향하는 역방향도 금지한다.
모든 production 패키지는 전체 패키지명을 기준으로 순환 의존을 검사한다. 같은 기능 내부의
`service`, `port.out`, result 사이 순환도 예외로 두지 않는다. Port/command/result에서 service 구현으로
향하는 의존은 아직 순환을 만들지 않았더라도 실패한다.

[모듈 규칙](../src/test/java/io/gen2spring/mcp/architecture/ModuleDependencyRules.java)은 Gradle에 선언했지만
아직 코드에서 쓰지 않는 역방향 의존도 잡는다. Domain은 다른 모듈에 의존하지 않고 application은 domain에만,
일반 adapter는 domain/application에만 의존한다. Emitter support는 domain, MCP runtime은 domain/support,
계열 emitter는 domain/application/support/MCP runtime을 허용한다. Bootstrap은 중심 계층·adapter,
앱은 중심 계층·adapter·bootstrap을 허용한다. 테스트 전용 의존은 이 production 정책과 구분한다.
외부 라이브러리도 선언 단계에서 검사한다. Domain과 emitter support/MCP runtime은 외부 라이브러리를
허용하지 않고, application은 기존 Jackson Databind 선언만 허용한다. 아직 코드에서 사용하지 않는
Spring/JDBC 의존성을 추가해도 실패한다. 이는 선언된 compile/runtime 의존 검사이며, 전이 라이브러리의
실제 사용은 별도의 바이트코드 규칙이 검사한다. Adapter와 실행 앱의 기술 라이브러리는 이 허용 목록 대상이 아니다.

[패키지 소유권 규칙](../src/test/java/io/gen2spring/mcp/architecture/ModulePackageRules.java)은 각 모듈의
실제 class directory를 별도로 읽어 그 모듈의 package prefix만 포함하는지 확인한다. 전체 모듈 목록은
Gradle graph와 대조한다. 다른 모듈에 domain 패키지 클래스를 넣어 규칙을 우회하는 것도 실패한다.

실행 명령은 `mise run architecture:test`다. 모든 모듈의 `test`·`fastTest`·`integrationTest`와 루트 `check`에
연결되어 별도 명령을 잊어도 실행된다. PMD·import 검사와 검증 범위는 [개발 및 검증](development-guide.md)을 따른다.
