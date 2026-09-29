# MCP 2026-07-28 구현 범위와 완료 기준

## 상태

2026-09-29 사용자 결정: 서버 측 핵심 명세와 Tasks 같은 선택 확장을 이번 구현 범위에 포함한다.
이 문서는 구현 계획이며 지원 완료 선언이 아니다. 기준 커밋 `98e3036`은 tools 전용 신형
어댑터와 구형·신형·병행 선택을 제공한다. 아래 체크는 현재 작업 트리의 구현·로컬 검증 상태이며 기준 커밋의 지원 범위와 구분한다.

## 적용 대상

- 직접 Java SDK 생성 서버와 Managed Runtime에서 같은 신형 프로토콜 구현을 사용한다.
- `LEGACY`, `MODERN`, `DUAL` 선택 및 기존 구형 요청의 호환성을 유지한다.
- Spring AI 생성 경로는 공식 지원 여부를 별도로 검증하기 전까지 구형으로 제한한다.
- 서버가 광고하는 capability는 실제로 구현되고 활성화된 기능에 한정한다.
- 서버 기능뿐 아니라 필요한 생성 설정, UI, manifest, 검증 클라이언트, 운영 설정까지 연결한다.

## 서버 측 핵심 명세

- [ ] 필수·조건부 요구사항을 공식 명세 절과 테스트에 일대일로 연결한다.
- [ ] JSON-RPC 요청·알림·오류, 버전 및 capability 협상, 발견 응답을 검증한다.
- [ ] Streamable HTTP의 메타데이터 헤더, 도구 인자 헤더, Origin 검증 및 상태 코드를 구현한다.
- [ ] stdio의 메시지 경계와 출력 분리, 종료·취소 동작을 구현한다.
- [ ] Tools의 입력·출력 계약, 콘텐츠 유형과 오류 결과를 검증한다.
- [x] Resources의 목록·템플릿·읽기와 텍스트·바이너리 콘텐츠 등록을 구현한다.
- [x] Prompts의 목록·인자·메시지 생성 및 등록을 구현한다.
- [ ] Completion, pagination, caching의 계약과 잘못된 입력 처리를 구현한다.
- [ ] MRTR과 Elicitation의 입력 요청·재개·거절·취소 흐름을 구현한다.
- [x] requestState를 principal·원 요청·만료 시간에 연결하고 변조·재사용 정책을 검증한다.
- [x] Subscriptions의 최초 확인 알림, 필터, 변경 알림, 연결 종료를 구현한다.
- [ ] 진행 알림·취소·시간 제한을 실제 작업 실행에 연결한다.
- [ ] HTTP 인증의 discovery, protected resource metadata, 토큰 audience·scope 검증을 구현한다.

Roots·Sampling·Logging은 해당 명세에서 deprecated 상태다. 새 사용을 권장하지 않되,
호환 기능의 서버 측 계약과 MRTR 전달을 검증 범위에 포함한다. 실제 모델 실행이나
클라이언트 파일 접근은 상대 클라이언트가 담당한다.

## 선택 확장

### Tasks — 필수 포함

기준은 `2026-07-28` 확장이다. 예전 `tasks/result`·`tasks/list` 흐름을 혼합하지 않는다.

- [x] 요청마다 `io.modelcontextprotocol/tasks` capability를 확인한다.
- [x] `tools/call`에서 서버가 비동기 실행을 선택하고 평탄한 `resultType: task` 응답을 반환한다.
- [x] 응답 전에 taskId로 조회 가능한 작업을 영속화한다.
- [x] `tasks/get`, `tasks/update`, `tasks/cancel`을 구현한다.
- [x] working → input_required → working 및 completed·failed·cancelled 상태 전이를 구현한다.
- [x] terminal 상태의 불변성과 완료·취소 경합을 검증한다.
- [x] tool의 `isError: true` 결과는 completed, JSON-RPC 오류는 failed로 처리한다.
- [x] 입력 키를 작업 수명 동안 재사용하지 않고 부분 입력·중복 입력·알 수 없는 입력을 처리한다.
- [x] TTL, polling 간격, 실행 동시성·대기열·저장 용량 상한을 설정한다.
- [x] 매 조회·입력·취소·구독에 소유자 및 현재 권한을 검증한다.
- [x] 연결 단절·프로세스 재시작 후 조회와 결과 복구를 검증한다.
- [x] 재실행할 수 없는 외부 부작용 작업의 중단 상태와 복구 정책을 명시한다.
- [x] `notifications/tasks`를 허용된 taskId 구독에만 전달한다.

작업 영속화가 외부 API 호출의 exactly-once 실행을 보장하지는 않는다. 재시도 가능한 작업과
운영자 확인이 필요한 작업을 구분하며, 인증 토큰·provider 시크릿을 작업 데이터에 저장하지 않는다.

### Skills — 포함

- [x] `io.modelcontextprotocol/skills`와 Resources capability를 제공한다.
- [x] `skills/list`, `skills/get`, `resources/read`를 구현한다.
- [x] frontmatter 보존, SKILL.md와 보조 파일의 전체 manifest, SHA-256·바이트 크기를 검증한다.
- [x] 직접 URI 조회를 목록 노출 여부와 독립적으로 지원한다.
- [x] directoryRead를 제공할 때 모든 등록 skill 디렉터리의 탐색을 구현한다.
- [x] namespace 격리, 경로 탈출 방지, 파일 수·크기 제한을 검증한다.

Skill의 활성화 승인과 실행 권한 부여는 호스트의 책임이다. 서버가 파일을 제공했다는 이유로
클라이언트에서 자동 활성화하거나 실행하지 않는다.

### MCP Apps — 포함

- [x] `io.modelcontextprotocol/ui` 협상과 도구의 `_meta.ui.resourceUri`를 제공한다.
- [x] `ui://` 리소스와 `text/html;profile=mcp-app` 콘텐츠를 제공한다.
- [ ] 도구 visibility 및 UI 리소스의 CSP·권한 메타데이터를 제공한다.
- [ ] 제공하는 App 화면의 초기화·tool result 전달 계약을 테스트 호스트로 검증한다.
- [x] Apps 미지원 클라이언트에도 정상적인 텍스트·구조화 도구 결과를 제공한다.

호스트의 iframe sandbox·사용자 승인 UI는 외부 호스트 구현이다. 이 프로젝트의 서버와
함께 제공하는 App 콘텐츠 및 검증용 호스트 계약을 구현 범위로 한다.

### 인증 확장 — 포함

- [ ] OAuth Client Credentials로 발급한 토큰의 서버 측 수용·권한 검증을 구현한다.
- [ ] Enterprise-Managed Authorization의 MCP resource server 역할을 구현한다.
- [ ] metadata와 실제 설정의 일치, issuer·audience·scope·만료·철회 시나리오를 검증한다.
- [ ] 로컬 테스트용 Authorization Server/IdP 계약과 배포 설정 안내를 제공한다.

외부 IdP의 계정·앱 등록과 운영 권한 변경은 코드 구현과 별개다. 사용자 승인 없이 실제 IdP를
변경하지 않는다. 토큰 발급·ID-JAG 교환은 명세의 역할 구분에 따라 Authorization Server와
클라이언트에서 수행하며, MCP resource server에 임의로 합치지 않는다.

## 구현 순서

1. 명세별 요구사항과 테스트 목록 확정, 공통 프로토콜·전송·기능 처리기 분리.
2. Resources·Prompts·Completion·등록 설정 및 요청 검증 보완.
3. MRTR·Elicitation·Subscriptions·진행 및 취소 연결.
4. Tasks 영속화·권한·복구와 동시성 검증.
5. Skills·MCP Apps와 인증 확장 연결.
6. 생성 UI·CLI·Managed Runtime 통합, 공식 conformance와 버전별 회귀 검증.

새 라이브러리나 DB 스키마 변경이 필요하면 의존 대상과 변경안을 구체화한 뒤 별도로 승인받는다.
기존 구형 지원 종료 조건은 사용자 가이드의 이전·관측·공지·롤백 기준을 유지한다.

## 완료 기준

- [ ] 두 실행 경로에서 지원 여부와 활성화 설정이 동일한 계약을 따른다.
- [ ] capability 미선언·미지원·비활성 요청이 올바르게 거부된다.
- [x] Tasks의 재시작·권한 격리·만료·중복 입력·취소 경합 테스트를 통과한다.
- [x] 신형·구형·병행 모드의 실제 생성 서버 테스트를 통과한다.
- [ ] 관련 공식 conformance 시나리오의 실패가 0건이다. 적용 제외는 명세 근거와 함께 기록한다.
- [ ] 전체 로컬 CI와 아키텍처·품질 검증을 통과한다.
- [x] 미검증 기능을 manifest·문서·UI에서 지원 완료로 표시하지 않는다.
- [x] 원격 CI와 외부 호스트 호환성 결과는 로컬 결과와 구분해 기록한다.

## 현재 검증 근거와 남은 작업

2026-09-30 작업 트리 기준:

- `McpExtensionsTest`: 등록 콘텐츠·Skills 해시·MRTR 소유자/변조·Tasks 영속화,
  재시작·입력 재개·취소·TTL·단일 writer·구독·stdio 계약.
- `P1GenerationIntegrationTest.generatedModernExtensionsExecuteOverHttpAndStdio`:
  실제 생성 JAR의 HTTP Resources·Prompts·Skills·Apps·Tasks 및 stdio 실행.
- `P1GenerationIntegrationTest.directSdkHonorsProtocolSelectionOnJava17And21`:
  Java 17/Maven·Java 21/Gradle × LEGACY/MODERN/DUAL 생성 서버 실행.
- `ManagedRuntimeJourneyIntegrationTest`: Bearer 보호 경로의 Tasks 조회·도구 실행·권한 폐기.
- `mcp-features.test.mjs`: 실제 editor 모듈의 설정 조립·잘못된 JSON·정수 범위·구형 모드.
- CodeRabbit의 YAML 숫자 타입과 HTTP 구독 격리 지적을 수정하고 회귀 검증한다.

미완료 체크는 전체 명세 대응을 선언하기 전에 남아 있는 범위다. 특히 OAuth Resource Server
의존성 추가는 사용자 승인이 필요하며 아직 추가하지 않았다. MCP Apps는 HTML 리소스와
도구 연결을 제공하지만 샘플 App의 호스트 초기화·양방향 메시지 계약 검증이 남아 있다.
진행 알림은 완료 시점만 제공하며 실행 중 세부 진행률·일반 요청 취소 연계는 미완료다.
공식 전체 conformance와 원격 CI는 이 작업 트리에서 통과를 확인하지 않았다.

## 기준 자료

- [MCP 2026-07-28 명세](https://modelcontextprotocol.io/specification/2026-07-28)
- [Deprecated 기능](https://modelcontextprotocol.io/specification/2026-07-28/deprecated)
- [Tasks 고정 명세](https://github.com/modelcontextprotocol/ext-tasks/blob/6c0997fbc040e6145c5cbd1e757aef9debb94303/specification/2026-07-28/tasks.md)
- [Skills](https://modelcontextprotocol.io/extensions/skills/overview)
- [MCP Apps 고정 명세](https://github.com/modelcontextprotocol/ext-apps/blob/82221c0c8ce7661efa6771c9d461511b1650495f/specification/2026-01-26/apps.mdx)
- [OAuth Client Credentials](https://modelcontextprotocol.io/extensions/auth/oauth-client-credentials)
- [Enterprise-Managed Authorization](https://modelcontextprotocol.io/extensions/auth/enterprise-managed-authorization)

확장별 버전은 core 명세 날짜와 같다고 가정하지 않는다. 구현을 시작할 때 각 확장의
stable/draft 상태와 기준 revision을 고정하고 변경 여부를 별도로 관리한다.
