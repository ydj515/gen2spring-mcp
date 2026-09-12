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

검증할 화면은 desktop 1440×1024와 좁은 400px 화면이며, 긴 경로·Tool 이름, 지원 불가 사유, 오류 summary,
진행·완료·다운로드 상태를 포함한다. 키보드만으로 선택·도움말·단계 이동이 가능하고 focus와 live announcement를
유지해야 한다. 기존 이미지는 [화면 QA 기록](../design-qa.md)과 [보존 이미지](assets)에 있으며 과거 검토 증거다.
현재 화면의 재검증 결과로 간주하지 않는다.
