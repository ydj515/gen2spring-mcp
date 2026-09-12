# 사용자 흐름과 진행 상태

## 5단계 위저드

현재 화면의 기준은 [editor.html](../apps/web/src/main/resources/templates/editor.html)과
[브라우저 모듈](../apps/web/src/main/resources/static)이다. 초기 3단계 설명은 아래 흐름으로 대체한다.

| 단계 | 사용자 행동과 완료 조건 |
| --- | --- |
| 1. OpenAPI 파일 | 업로드·분석 완료를 확인하고 직접 다음 단계로 이동 |
| 2. Endpoint 선택 | 지원 가능한 endpoint의 checkbox로 선택. 지원 불가 항목도 이유와 함께 표시 |
| 3. 생성 설정 | 프로젝트·MCP 구현 방식·호환 profile과 Tool별 이름·설명·정책 편집 |
| 4. 설정 검증 및 프로젝트 생성 | preview 결과와 검증 순서를 확인한 뒤 생성 요청 |
| 5. 생성 진행 | 현재 stage·취소·실패 또는 완료 상태를 확인하고 제공되는 artifact 다운로드 |

분석 완료 후 큰 dropzone은 compact file card로 바뀌며 파일 교체·제거는 계속 가능하다.
교체·제거는 이전 파일의 선택·override·preview·job 상태를 초기화한다. 성공 즉시 다음 화면으로 자동 이동하지 않는다.
생성 입력이 바뀌면 이전 preview를 다시 사용할 수 없어야 하며 서버도 선택과 설정을 재검증한다.

Step 3은 Tool 목록과 편집 영역을 사용한다. Retry/Pagination은 명시적으로 켤 때만 종속 입력을 표시·활성화하고,
꺼도 기존 편집값을 보존한다. Parameter source와 Response normalization은 별도 master enable flag가 없다.
한 번에 정책 panel 하나만 열며 panel 접기/펼치기가 값 변경을 일으키지 않는다. 도움말은 별도 버튼으로 열고
Escape·외부 클릭으로 닫으며 정책 panel 토글과 독립적이다.

## 상태 소유권과 복구

서버 분석 결과가 endpoint 지원 여부의 기준이다. 클라이언트는 표시·선택·설정 편집을 담당한다.
`upload.js`, `operations.js`, `editor.js`, `wizard.js`, `state.js`의 기존 책임을 유지하고,
API payload와 도메인 상태를 화면 재배치 때문에 변경하지 않는다.
Hosted의 작업 목록·작업 상세는 저장된 작업을 다시 조회하며 local 메모리 작업을 durable job처럼 설명하지 않는다.
실패·취소를 100% 성공 표시로 바꾸지 않고 마지막 stage와 제공 가능한 artifact 상태를 구분한다.

## SSE 진행 표시

`GET /api/jobs/{id}/events`는 local/hosted 공통의 진행 전송 경로다.
[JobEventStream](../apps/web/src/main/java/io/gen2spring/mcp/app/web/job/JobEventStream.java)이 stream을 관리한다.

- 연결 직후와 변경 시 `snapshot`을 전달한다.
- 유휴 시 `heartbeat`로 연결 생존을 알린다.
- 마지막 snapshot 뒤 terminal 상태에서는 `done`으로 종료한다.
- 연결 불가·복구 실패 시 기존 job 조회 polling으로 전환한다.

Local은 메모리 작업의 변경 신호를 기다린다. Hosted는 서버에서 저장된 상태를 반복 조회하므로
SSE 도입이 PostgreSQL polling 제거를 뜻하지 않는다. Hosted stream을 열기 전에 소유권을 확인하고,
조회 중에도 owner 조건을 유지한다. 두 모드의 payload 형태 차이는 API 모듈에서 화면용으로 정규화한다.
여기의 SSE는 생성 진행용 HTTP 기능이며 생성 MCP 서버의 transport로 SSE를 지원한다는 의미가 아니다.

## 화면과 접근성 기준

상단 stepper·summary·활성 panel은 같은 content frame에 정렬한다. 이전/다음은 일반 문서 흐름 안에 배치한다.
공유 shell은 editor·hosted dashboard·job detail에서 재사용한다. Bootstrap CSS·Icons는 로컬 WebJar로
제공하며 CDN이나 remote font가 필요하지 않다. Bootstrap JavaScript 대신 기존 ECMAScript 모듈이 상호작용을 맡는다.

화면 검증은 desktop 1440×1024와 중간 폭 1024×900, mobile 390×844 및 400×900에서 수행한다.
긴 경로·Tool 이름, 지원 불가 사유, 오류 summary, 진행·완료·다운로드 상태를 포함한다.
키보드만으로 선택·도움말·단계 이동이 가능하고 focus와 live announcement를 유지해야 한다.

## 화면 회귀 QA 체크리스트

아래 항목은 화면 변경 시 사용할 검증 기준이며 현재 테스트 통과 기록이 아니다.

| 영역 | 확인할 동작 |
| --- | --- |
| 업로드와 초기화 | 두 루트 OpenAPI fixture로 분석하고 서버가 반환한 지원 판정·개수를 표시한다. 잘못된 파일·제거·미분석 상태에서는 다음 이동을 막고, 교체 시 이전 설정을 초기화한다. |
| 단계 상태와 복구 | 유효한 기본값만으로 방문하지 않은 단계를 완료 표시하지 않는다. 이전/다음 이동, 파일 초기화, 저장된 hosted job 복구에서 방문 상태와 이동 가능 여부를 구분한다. |
| Endpoint 선택 | 검색·필터·전체 선택이 지원 가능한 항목에만 적용된다. checkbox의 선택 표시가 CSP 아래에서도 보이고 긴 이름을 자르지 않는다. |
| Tool 편집 | Tool 전환 후 이름·설명·정책 편집값이 유지된다. 선택한 Tool의 편집 영역을 다시 열 수 있고 긴 목록에서도 편집·이동에 접근할 수 있다. |
| 프로젝트와 구현 방식 | 분석·profile 로딩 중 불필요하게 설정을 펼치지 않는다. Spring AI 애노테이션/SDK 전환 시 지원 profile·설명이 함께 갱신된다. |
| 정책과 도움말 | Retry/Pagination의 활성화·비활성화, 값 보존, Tool 전환을 검사한다. 독립 도움말의 키보드·Escape·외부 클릭과 단일 panel 열기 동작을 확인한다. |
| Preview | 대표 Tool 제외나 입력 변경 시 summary와 preview를 무효화하고, 뒤늦게 도착한 이전 요청의 응답을 적용하지 않는다. 유효한 설정 검증 후에만 생성을 허용한다. |
| 진행과 실패 | 실제 backend stage로 진행률을 계산하고 사용자용 5개 그룹의 완료 개수와 구분한다. 실패 그룹·후속 skipped·취소·SSE fallback을 확인한다. |
| 결과와 다운로드 | 완료 결과에서 다운로드를 이력보다 먼저 찾을 수 있고 상세 단계·실패 정보도 접근 가능하다. 준비 중 artifact는 비활성화하고 ZIP·manifest·report의 이름과 다운로드 응답을 각각 확인한다. |
| 접근성과 반응형 | 페이지 수평 overflow, 긴 값 줄바꿈, 키보드 focus, live region, reduced motion, 색상 이외의 상태 표시와 artifact별 접근 가능한 이름을 검사한다. |

실제 생성 확인은 설정 검증 → generation → stage event → 검증 완료 → ZIP 다운로드까지 연결해서 수행한다.
Mockup의 예시 endpoint 개수·진행률·가짜 대기 시간을 제품의 기대값으로 사용하지 않는다.
브라우저 오류도 확인하되 local 서버 재시작으로 소멸한 메모리 job과 실제 회귀 오류를 구분한다.
Local 위저드 결과로 hosted dashboard·job detail까지 검증했다고 보고하지 않는다.

## QA 증거 관리

새 검증 기록에는 대상 커밋, local/hosted 범위, 입력 fixture와 화면 상태, viewport·device pixel ratio,
실행 명령과 결과, 미검증 범위를 남긴다. 화면 비교는 같은 상태의 전체 화면과 필요한 세부 영역을 사용하고,
이미지 크기 조정이 있으면 명시한다. 개인 컴퓨터의 절대 경로를 재현 절차로 사용하지 않는다.

[보존 이미지](assets)는 과거 디자인·QA 자료이며 현재 화면의 공식 회귀 기준이나 재검증 결과가 아니다.
과거 검토·수정 이력은 Git에서 확인한다. 특정 화면을 공식 기준으로 채택할 때 해당 이미지와 재현 절차를
함께 관리한다. 자동 검사와 실제 브라우저 검증 범위는 [개발 및 검증](development-guide.md)을 따른다.
