# App 패키지 아키텍처 정리 설계

- 상태: 승인됨
- 작성일: 2026-08-30
- 범위: `apps/*` Java 패키지 구조와 저장소 내 완전수식 타입 사용

## 1. 목표

애플리케이션 진입점, 웹 경계, 설정, 실행 로직이 같은 루트 패키지에 섞인 앱을 책임 중심 패키지로 재구성한다. 패키지는 파일 수를 맞추기 위해 만들지 않고, 변경 이유와 의존 방향이 구분되는 책임에만 도입한다.

동시에 `@org.springframework...ConditionalOnProperty`처럼 import로 표현할 수 있는 완전수식 타입 사용을 정리한다. 이 작업은 소스 가독성과 패키지 경계를 개선하는 리팩터링이며 HTTP, CLI, 환경변수, 데이터베이스, 생성 산출물 계약은 변경하지 않는다.

## 2. 선택한 접근

### 2.1 책임 중심 선택적 재패키징

각 앱 루트에는 실행 진입점만 남기고, 다음 책임이 실제로 분리되는 앱만 하위 패키지를 둔다.

| 앱 | 유지 또는 신설 패키지 | 책임 |
| --- | --- | --- |
| `cli` | 기존 `command`, `output`, `error` | 이미 분리되어 있으므로 유지 |
| `web` | 기존 `api`, `config`, `error`, `hosted`, `job`, `page`, `security` | 이미 앱 루트 책임이 분리되어 있으므로 유지 |
| `fetch-gateway` | `api`, `fetching`, `config` | 내부 HTTP 경계, 제한된 URL fetch 실행, 보안 설정 |
| `import-runner` | `job` | 격리 프로세스의 import protocol과 실행 흐름 |
| `provider-egress` | `api`, `egress`, `config` | 내부 HTTP 경계, provider 호출 정책과 transport, 보안 설정 |
| `runtime` | `config`, `security`, `server` | 런타임 조립, bearer 인증, MCP server routing과 handle 관리 |
| `worker` | `config`, `execution` | worker 조립과 속성, loop·heartbeat·readiness 실행 흐름 |

`*Application` 클래스는 각 앱 루트에 유지한다. Spring component scan의 기준점과 Gradle `mainClass` 계약은 바꾸지 않는다.

### 2.2 의존 방향

패키지 의존은 다음 방향을 따른다.

```text
Application -> config -> execution/service internals
HTTP api -> execution/service facade
security -> framework security boundary
```

설정 클래스가 package-private transport 구현을 생성해야 한다면 해당 transport 패키지에 가까운 설정으로 이동한다. 패키지 분리만을 위해 내부 타입을 무조건 `public`으로 변경하지 않는다. 다른 패키지가 실제로 소비하는 facade와 구성 속성만 필요한 가시성을 갖는다.

## 3. 앱별 이동 원칙

### 3.1 Fetch Gateway

- `FetchGatewayApplication`: 앱 루트 유지
- `FetchController`: `api`
- `BoundedFetcher`, `FetchTransport`, `ApacheFetchTransport`, `ValidatedDnsResolver`, `FetchFailure`: `fetching`
- `FetchGatewaySecurityConfiguration`: `config`
- transport 생성은 `fetching` 내부 configuration으로 옮겨 package-private 구현을 외부에 노출하지 않는다.

### 3.2 Import Runner

- `ImportRunnerApplication`: 앱 루트 유지
- `ImportRunner`, `ImportJobProtocol`, `ImportGatewayClient`, `ImportRunnerFailure`: `job`
- 별도 transport 패키지는 만들지 않는다. 현재 gateway port 하나만으로는 독립 패키지의 응집도가 충분하지 않다.

### 3.3 Provider Egress

- `ProviderEgressApplication`: 앱 루트 유지
- `ProviderEgressController`: `api`
- transport, DNS resolver, request/response policy와 실패 모델: `egress`
- controller는 `egress`의 단일 facade를 통해 실행하고 transport 세부 구현에 직접 의존하지 않는다.
- `ProviderEgressSecurityConfiguration`: `config`

### 3.4 Managed Runtime

- `ManagedRuntimeApplication`: 앱 루트 유지
- `RuntimeConfiguration`, `RuntimeProperties`: `config`
- `RuntimeBearerFilter`: `security`
- `ManagedMcpRouter`, `RuntimeServerHandle`, `RuntimeServerHandleRegistry`: `server`
- 구성 패키지만 adapter 구현을 조립하고 server·security 패키지는 application/domain 계약을 소비한다.

### 3.5 Worker

- `Gen2SpringWorkerApplication`: 앱 루트 유지
- `WorkerConfiguration`, `WorkerInfrastructureConfiguration`, `WorkerProperties`: `config`
- `WorkerLoop`, `WorkerHeartbeatPublisher`, `WorkerReadiness`, `WorkerStartupFailure`: `execution`
- execution 패키지는 polling과 lifecycle만 담당하고 infrastructure 생성은 config에 남긴다.

## 4. 완전수식 타입 정리 규칙

1. 저장소의 실제 Java 소스와 생성 Java 테스트 fixture에서 FQCN 어노테이션을 검색한다.
2. 이름 충돌이 없으면 import를 추가하고 단순 타입명으로 변경한다.
3. `apps/**`의 메서드 본문, 시그니처, 제네릭 타입에 있는 불필요한 FQCN도 같은 기준으로 정리한다.
4. 다음 사용은 유지한다.
   - `jakarta.servlet.request.X509Certificate` 같은 프로토콜 문자열
   - 시스템 속성명과 문서 예제 문자열
   - 같은 단순 이름이 충돌해 FQCN이 의도를 명확히 하는 경우
   - 문자열 자체가 검증 대상인 golden fixture
5. import 순서는 현재 포맷터와 기존 코드 스타일을 따른다.

우선 확인 대상에는 `ArtifactController`를 포함한 local-mode component의 `ConditionalOnProperty`, `WebErrorMapper`의 `Component`, emitter integration fixture의 `Qualifier`가 포함된다.

## 5. 테스트와 구조 계약

- 이동한 production class의 테스트 package를 production package와 일치시킨다.
- 각 flat 앱에 package architecture test를 추가해 진입점과 책임 패키지의 존재, 과거 루트 타입의 부재를 검증한다.
- package-private 동작 테스트는 동일 package에서 유지한다.
- 문자열 기반 class name, Gradle `mainClass`, Spring component scan 범위를 함께 점검한다.

검증 순서는 다음과 같다.

1. 이전 package 경로와 FQCN 어노테이션 잔존 검색
2. 모든 `apps` unit test와 integration test source compile
3. `mise run generator:test`
4. `mise run hosted:acceptance`
5. `git diff --check`
6. 변경 diff에 대한 CodeRabbit review

Docker 또는 외부 실행 환경 때문에 acceptance를 수행할 수 없으면 성공으로 간주하지 않고 미실행 사유를 별도로 보고한다.

## 6. 오류 처리와 호환성

예외 타입과 HTTP error mapping은 기존 의미를 유지한다. 패키지 이동 중 visibility가 바뀌더라도 예외 코드, 상태 코드, 응답 body는 변경하지 않는다. Spring bean name이나 conditional property도 유지한다.

- 제약: Java package 이동으로 모든 source set의 import와 문자열 class reference를 함께 갱신해야 한다.
- 위험: package-private 협력자를 무리하게 분리하면 public surface가 늘거나 Spring bean 탐색이 실패할 수 있다.
- 예외: 이름 충돌, 프로토콜 문자열, golden fixture는 FQCN 정리 대상에서 제외한다.

## 7. 복잡도와 비기능 영향

패키지 이동과 import 정리는 런타임 알고리즘을 변경하지 않는다. 시간 복잡도와 공간 복잡도는 기존과 동일하다. 빌드 시 증분 컴파일 캐시는 package 이동으로 한 차례 무효화될 수 있으나 지속적인 운영 비용은 늘어나지 않는다.

## 8. 제외 범위

- `modules/*` 패키지 아키텍처 재설계
- HTTP, CLI, DB schema, 환경변수 계약 변경
- 앱 간 모듈 의존성 변경
- 파일 크기만을 근거로 한 클래스 분할
- 테스트와 무관한 코드 스타일 일괄 변경
