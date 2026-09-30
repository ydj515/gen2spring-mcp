# 설계와 의사결정

이 문서는 날짜별 설계·구현 계획의 선택 이유와 유지할 계약을 현재 주제별 가이드로 통합한다.
과거의 승인·완료 표시는 당시 작업의 상태이며 현재 테스트 통과를 의미하지 않는다.
구현은 소스와 설정을 기준으로 확인하고, 기존 문서의 버전·클래스명·중간 작업 순서를 그대로 재사용하지 않는다.

## 구조와 조립

생성기는 Gradle 멀티모듈로 canonical OpenAPI/Tool 모델을 parser·emitter·validator에서 분리한다.
한 모듈에 모든 코드를 모으는 것보다 설정과 포트 수는 늘지만 버전별 생성 코드와 외부 실행의 변경을 격리한다.
초기 `generator-*` 배치는 현재 `modules/domain`, `application`, `adapters`, `bootstrap`, `apps`로 대체했다.
앱 패키지는 역할이 분리되는 곳만 나누고, 작은 import runner에 불필요한 transport 계층을 만들지 않는다.
현재 패키지와 실제 application 의존성은 [프로젝트 구조](project-structure.md), [아키텍처 경계](architecture-boundaries.md)에 있다.

초기 Web의 JDK HttpServer·직접 쿠키/CSRF 처리는 Spring Boot MVC·Thymeleaf·Spring Security로 대체했다.
CLI와 Web은 생성 파이프라인을 공유하지만 local과 hosted의 identity, 작업 저장, 실행 격리는 별도다.
Framework 전환의 목적은 HTTP·template·보안 lifecycle을 기존 Spring 경계에 맡기는 것이며 생성 계약을 바꾸는 것이 아니다.

## 검증 후 ZIP과 재현성

파일 생성 성공만으로 배포 가능성을 판단하지 않는다. Compile, context, MCP initialize/list 이후 실제
대표 tools/call과 loopback HTTP binding까지 검사한다. 명시적 argument는 arbitrary regex·업무 규칙을
추측하는 자동 sample 생성보다 입력 부담이 있지만 검증할 호출을 재현할 수 있다.
기존 '한 upstream 요청'은 retry/pagination 도입으로 순서 있는 bounded interaction 검증으로 확장했다.
검증 실패는 보고서와 조사 가능한 생성 결과를 보존하고 ZIP 게시를 차단한다.

Source/ZIP 결정성은 재생성 차이를 찾기 위한 계약이다. 시간·출력 byte 측정값은 정규화해서 비교하고
실제 source 차이는 감추지 않는다. 세부 순서와 실패 경계는 [생성 파이프라인](generation-pipeline.md)에 있다.

## 생성 방식과 호환성

| 결정 | 이유와 비용 | 다시 검토할 조건 |
| --- | --- | --- |
| Spring AI 계열별 emitter와 공통 IR | Boot/Jackson/API 차이를 parser에서 격리. 계열별 source 검증 필요 | 새 계열 도입 시 독립 생성·검증 경계를 먼저 확보 |
| build tool scaffold와 runtime 생성 분리 | Gradle/Maven 추가가 실행 모델을 중복시키지 않음 | 새 build tool의 wrapper/JDK/package 검증이 확보될 때 확장 |
| canonical profile registry | CLI/API/UI/manifest의 조합 불일치 방지 | 새 조합의 compile/context/MCP acceptance 통과 후 등록 |
| Spring AI 1 WebFlux Async 보류 | 현재 registry의 upstream transport 호환성 제한 | 수정 버전에서 Java 17·21 × Gradle·Maven 전체 4조합 검증 통과 |
| 전역 annotation scanner 비활성화 | 자동 schema 유실·중복 등록 방지 | 원본 schema·중복 방지·오류/telemetry 계약을 모두 보존하는 등록 방식 확보 |

초기 P0의 annotation scanner 기본 사용은 현재 정책이 아니다. 화면은 `SPRING_AI_ANNOTATIONS` 또는
`MCP_JAVA_SDK`를 선택하고, 설정 필드 생략은 `SPRING_AI_EXPLICIT` 호환 모드다. Annotation 모드는 전용
provider로 실제 `@McpTool`을 탐색한 후 canonical schema를 적용한다. SDK 모드는 지원되는 Boot 3 MVC
조합에서 Spring AI 의존성을 제외한다. [현재 옵션](user-guide.md#mcp-구현-방식-선택)을 기준으로 한다.

## 안전한 스키마 확장과 실행

OpenAPI 3.1을 무제한 JSON Schema 지원으로 해석하지 않는다. Nullability, array, composition과 ref sibling을
canonical 모델로 정규화하고 지원할 수 없는 제약은 endpoint별 사유를 남긴다.
초기 nullable body descendants만 허용한 정책은 optional nullable parameter/root body와 bounded composition으로
확장했다. 생략과 JSON null은 HTTP 의미가 다르므로 보존한다.

응답 정규화는 고정 envelope와 bounded JSON Pointer를 사용한다. JSONPath 같은 표현 언어보다 범위는 좁지만
결정적인 검증과 자원 제한이 가능하다. Pointer 불일치 시 raw body를 대신 반환하지 않는다.
GET retry/pagination도 횟수·페이지·전체 timeout을 제한한다. 공통 관측은 low-cardinality metric과 안전한 span을
사용하며 URL·query·argument·원문 예외를 자동 observation으로 노출하지 않는다.
세부 제약은 [파이프라인](generation-pipeline.md)과 [runtime 관측](user-guide.md#runtime-metrics와-opentelemetry)에 있다.

## Hosted 분리와 내구성

Hosted는 다중 사용자 소유권과 비동기 실행을 위해 PostgreSQL queue, private storage와 독립 Worker를 사용한다.
별도 broker보다 운영 구성은 작지만 DB claim·lease·fencing과 cleanup을 명시적으로 관리해야 한다.
OIDC identity, idempotency, fenced completion과 Catalog 게시의 원자성을 생략해 구현을 단순화하지 않는다.

Web에 Docker socket을 주거나 platform DB를 가진 실행 프로세스가 외부 provider에 직접 연결하는 구조를 피한다.
URL import와 provider 호출은 각각 제한된 gateway를 통과한다. 세부 상태와 실패 처리는
[Hosted 작업과 복구](hosted-platform.md)에, 배포·backup/restore는 [배포 가이드](../deploy/hosted/README.md)에 있다.

## Metadata, Catalog와 runtime 전환

Metadata는 최종 Tool IR의 framework-neutral projection이다. 생성 코드 역분석이나 source와 별도로 계산한
Tool 정의를 사용하지 않아 생성 서버와 Hosted Catalog의 의미가 갈라지는 것을 막는다.
초기 credential-free runtime은 credential vault·exact binding·scoped grant·DB rate/audit로 확장했고,
stateful SDK session을 correctness 기준으로 두던 설계는 stateless replica 실행으로 대체했다.

Catalog는 immutable linear revision이다. Runtime 변경은 보수적 compatible diff와 CAS를 통과해야 한다.
이 방식은 자동 전환 가능 범위가 좁지만 active grant·credential·rate·audit를 유지하며 rollback을 추적할 수 있다.
여러 Catalog 결합, OAuth acquisition, billing은 현재 범위 밖이다. [Managed Runtime](managed-runtime.md)에
현재 활성화·호출·revision·migration 계약을 정리한다.

## 화면 흐름과 진행 전송

현재 화면은 5단계이고 상단 stepper와 같은 폭의 summary/panel, 일반 흐름의 이전·다음 버튼을 사용한다.
초기 3단계 설명과 floating action dock 제안은 현재 기준이 아니다. 분석 완료 후 자동 이동하는 초기 계획도
사용자가 결과를 확인한 뒤 다음 버튼을 누르는 동작으로 대체했다.

SSE는 browser polling을 기본 경로에서 대체하고 polling fallback을 보존한다. Hosted는 여전히 서버 read loop를
사용한다. LISTEN/NOTIFY는 전용 연결·재연결 관리 비용이 있어 보류했으며, 진행 지연이 2초를 초과하거나
동시 stream의 DB 읽기 부하가 측정된 병목이 되면 같은 feed 경계의 교체를 검토한다.
[사용자 흐름](user-flows.md)에 상태·접근성·화면 QA 계약이 있다.

## DUAL 프로토콜 모드의 단일 endpoint 라우팅

생성 서버의 프로토콜 모드를 `DUAL`로 선택하면 `/mcp/v1`, `/mcp/v2` 같은 별도 endpoint를
만들지 않고 **단일 `POST /mcp` endpoint**에서 요청별로 프로토콜 버전을 판별한다.
별도 endpoint 분리 대신 단일 경로를 선택한 이유는 (1) 클라이언트의 base URL 설정을 하나로
유지하고, (2) 이전 기간 동안 클라이언트 설정 변경 없이 서버만 업그레이드하는 시나리오를
지원하기 위함이다.

### DualMcpServlet 라우팅 흐름

`DualMcpServlet`은 `ServletRegistrationBean`으로 `/mcp`에 단일 등록되며, 아래 순서로 분기한다.

1. **신형(2026-07-28) 판별** — `ModernMcpProtocol.isModern()`:
   - `MCP-Protocol-Version` 헤더 값이 구형(`2025-03-26`, `2025-06-18`, `2025-11-25`)이 아닌 경우
   - 요청 본문 `params._meta`에 `io.modelcontextprotocol/protocolVersion` 키가 존재하는 경우
   - JSON-RPC method가 `server/discover`인 경우

2. **신형 요청 처리** — `ModernMcpProtocol.handle()`:
   - Origin 검증(동일 loopback 주소·scheme·port, 또는 Origin 헤더 없음)으로 CSRF/DNS Rebinding 방지
   - `MCP-Protocol-Version: 2026-07-28` 헤더와 본문 메타데이터 일치 검증
   - `Mcp-Method`, 도구 호출 시 `Mcp-Name` 헤더 검증
   - 세션 ID를 발급하거나 사용하지 않는 무상태(stateless) 처리

3. **구형(2025-03-26) 요청 처리** — `legacy.service()`:
   - `modern.supportsLegacy()` (DUAL 또는 LEGACY 모드)를 확인한 뒤 MCP Java SDK의
     `HttpServletStreamableServerTransportProvider`로 위임
   - `initialize` → `notifications/initialized` 세션 기반 핸드셰이크를 수행

4. **비지원 버전 거부**: HTTP 400 / JSON-RPC 에러 `-32022` (Unsupported protocol version)

### 구현체 제약

- `DUAL`과 `MODERN` 모드는 `McpImplementation.MCP_JAVA_SDK`에서만 지원한다.
  Spring AI 구현체는 구형(2025-03-26)만 지원하므로, Spring AI + DUAL 조합은 `GenerationCommand`
  생성자와 `GradleMcpProjectValidator`에서 검증 오류를 반환한다.
- `McpProtocolMode`의 `legacy()`와 `modern()` 메서드로 각 모드의 버전 지원 여부를 판별한다.
  DUAL은 둘 다 `true`를 반환한다.

클라이언트 관점의 동작 계약은 [사용자 가이드](user-guide.md#mcp-프로토콜-버전-선택)에 있다.

## 통합 출처

아래 식별자는 삭제한 과거 파일명이며 링크나 재실행할 계획이 아니다. 각 설계와 계획의 유지할 내용은 연결된
가이드에서 관리하고, 이전 클래스 배치·버전·단계별 커밋 지시는 보존 대상에서 제외했다.
설계·QA PNG는 `docs/assets`에 보존한다. 유지할 검증 기준은 [사용자 흐름](user-flows.md#화면-회귀-qa-체크리스트)에
통합하고, 과거 화면 검토·수정 이력은 Git에서 확인한다.

| 과거 문서 | 보존한 내용의 현재 위치 |
| --- | --- |
| `plans/2026-08-07-openapi-mcp-generator-p0.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `plans/2026-08-09-tools-call-mock-validation.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `plans/2026-08-10-response-normalization.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `plans/2026-08-10-spring-ai-2-java17-profiles.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `plans/2026-08-11-local-operation-editor.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `plans/2026-08-11-p1-completion.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `plans/2026-08-11-runtime-observability.md` | [사용자 가이드](user-guide.md#runtime-metrics와-opentelemetry): 안전한 metric·trace |
| `plans/2026-08-11-spring-ai-1-profiles.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `plans/2026-08-12-linux-ci-restoration.md` | [개발 및 검증](development-guide.md): fast CI와 전체 acceptance 분리 |
| `plans/2026-08-12-repository-structure-refactor.md` | [프로젝트 구조](project-structure.md), [아키텍처 경계](architecture-boundaries.md) |
| `plans/2026-08-12-spring-boot-thymeleaf-web-migration.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `plans/2026-08-13-hosted-generation-platform.md` | [Hosted 작업과 복구](hosted-platform.md), [배포 가이드](../deploy/hosted/README.md) |
| `plans/2026-08-13-hosted-persistence-core.md` | [Hosted 작업과 복구](hosted-platform.md), [배포 가이드](../deploy/hosted/README.md) |
| `plans/2026-08-13-hosted-url-import-storage.md` | [Hosted 작업과 복구](hosted-platform.md), [배포 가이드](../deploy/hosted/README.md) |
| `plans/2026-08-13-hosted-web-deployment.md` | [Hosted 작업과 복구](hosted-platform.md), [배포 가이드](../deploy/hosted/README.md) |
| `plans/2026-08-13-hosted-worker-sandbox.md` | [Hosted 작업과 복구](hosted-platform.md), [배포 가이드](../deploy/hosted/README.md) |
| `plans/2026-08-14-openapi-31-guided-editor.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `plans/2026-08-16-job-progress-sse.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `plans/2026-08-16-stepwise-editor-redesign.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `plans/2026-08-17-openapi-nullable-array-constraints.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `plans/2026-08-21-managed-mcp-runtime.md` | [Managed Runtime](managed-runtime.md), [Hosted 게시](hosted-platform.md) |
| `plans/2026-08-21-managed-runtime-credential-policy-scale.md` | [Managed Runtime](managed-runtime.md), [Hosted 게시](hosted-platform.md) |
| `plans/2026-08-21-runtime-metadata-tool-catalog.md` | [Managed Runtime](managed-runtime.md), [Hosted 게시](hosted-platform.md) |
| `plans/2026-08-22-catalog-version-diff-migration.md` | [Managed Runtime](managed-runtime.md), [Hosted 게시](hosted-platform.md) |
| `plans/2026-08-22-openapi-schema-expansion.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `plans/2026-08-23-generation-target-expansion.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `plans/2026-08-24-ui-ux-redesign.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `plans/2026-08-24-wizard-flow-refinement.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `plans/2026-08-25-upload-policy-ux-refinement.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `plans/2026-08-30-app-package-architecture.md` | [프로젝트 구조](project-structure.md), [아키텍처 경계](architecture-boundaries.md) |
| `specs/2026-08-07-openapi-mcp-generator-p0-design.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `specs/2026-08-09-tools-call-mock-validation-design.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `specs/2026-08-10-response-normalization-design.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `specs/2026-08-10-spring-ai-2-java17-profiles-design.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `specs/2026-08-11-local-operation-editor-design.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `specs/2026-08-11-p1-completion-design.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `specs/2026-08-11-runtime-observability-design.md` | [사용자 가이드](user-guide.md#runtime-metrics와-opentelemetry): 안전한 metric·trace |
| `specs/2026-08-11-spring-ai-1-profiles-design.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `specs/2026-08-12-linux-ci-restoration-design.md` | [개발 및 검증](development-guide.md): fast CI와 전체 acceptance 분리 |
| `specs/2026-08-12-repository-structure-refactor-design.md` | [프로젝트 구조](project-structure.md), [아키텍처 경계](architecture-boundaries.md) |
| `specs/2026-08-12-spring-boot-thymeleaf-web-migration-design.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `specs/2026-08-13-hosted-generation-platform-design.md` | [Hosted 작업과 복구](hosted-platform.md), [배포 가이드](../deploy/hosted/README.md) |
| `specs/2026-08-14-openapi-31-guided-editor-design.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `specs/2026-08-16-job-progress-sse-design.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `specs/2026-08-16-stepwise-editor-redesign-design.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `specs/2026-08-17-openapi-nullable-array-constraints-design.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `specs/2026-08-21-managed-mcp-runtime-design.md` | [Managed Runtime](managed-runtime.md), [Hosted 게시](hosted-platform.md) |
| `specs/2026-08-21-managed-runtime-credential-policy-scale-design.md` | [Managed Runtime](managed-runtime.md), [Hosted 게시](hosted-platform.md) |
| `specs/2026-08-21-runtime-metadata-tool-catalog-design.md` | [Managed Runtime](managed-runtime.md), [Hosted 게시](hosted-platform.md) |
| `specs/2026-08-22-catalog-version-diff-migration-design.md` | [Managed Runtime](managed-runtime.md), [Hosted 게시](hosted-platform.md) |
| `specs/2026-08-22-openapi-schema-expansion-design.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `specs/2026-08-23-generation-target-expansion-design.md` | [생성 파이프라인](generation-pipeline.md), [사용자 가이드](user-guide.md), [개발 및 검증](development-guide.md) |
| `specs/2026-08-24-ui-ux-redesign-design.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `specs/2026-08-24-wizard-flow-refinement-design.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `specs/2026-08-25-upload-policy-ux-refinement-design.md` | [사용자 흐름](user-flows.md), [파이프라인](generation-pipeline.md) |
| `specs/2026-08-30-app-package-architecture-design.md` | [프로젝트 구조](project-structure.md), [아키텍처 경계](architecture-boundaries.md) |
