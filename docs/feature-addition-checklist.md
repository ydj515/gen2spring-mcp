# 새 기능 추가 체크리스트

## 책임 배치

- [ ] 사용자 입력·결과 계약과 실패 조건을 먼저 정의하고 local/hosted/generated/managed 중 적용 범위를 명시한다.
- [ ] 순수 정책은 domain, 실행 순서는 application, 외부 라이브러리·저장소는 adapter, 조립은 bootstrap/앱 config에 둔다.
- [ ] 계층 밖으로 Swagger/Spring AI 구현 타입을 노출하지 않고 기존 포트와 패키지 가시성을 확인한다.

## OpenAPI·Tool·profile 변경

- [ ] 분석의 supported/unsupported 사유, Tool IR, MCP schema, preview, metadata를 함께 반영한다.
- [ ] absent/null, required, 조합·크기 한도를 생성 코드와 Managed Runtime에서 동일하게 처리한다.
- [ ] profile registry, CLI/API, UI 선택, emitter, manifest, JDK/build driver의 호환 조건을 맞춘다.
- [ ] 기존 설정에서 `mcpImplementation`을 생략한 호환 동작과 SDK 제한을 유지한다.
- [ ] 새 profile은 compile/context/MCP 목록·대표 호출을 검증한 뒤 노출한다.

## Hosted·runtime 변경

- [ ] 모든 리소스 접근의 owner 조건과 idempotency payload 충돌을 검사한다.
- [ ] heartbeat/event/완료/게시가 최신 lease·fencing을 요구하는지 확인한다.
- [ ] DB transaction과 object storage·컨테이너 실행 경계를 구분하고 실패 후 정리를 검증한다.
- [ ] credential·grant·rate·audit와 Catalog migration의 동시성 및 revoke 경계를 검증한다.
- [ ] log, span, metric, 오류, manifest와 metadata에 민감 값이 새로 들어가지 않는지 확인한다.

## UI·문서·검증

- [ ] 단계 이동, 입력 변경 시 preview 무효화, job 복구, SSE fallback을 확인한다.
- [ ] keyboard/focus/live region, 긴 값과 400px 화면을 확인한다.
- [ ] 관련 모듈 테스트 뒤 [개발 및 검증](development-guide.md)의 실제 영향 범위 acceptance를 실행한다.
- [ ] 사용자 가이드·설계 결정·운영 문서를 갱신하고 [문서 안내](README.md)에서 연결한다.
- [ ] `git diff --check`, 내부 링크와 실제 코드 경로를 검사한다.
- [ ] 실행 검증, 정적 확인, 미실행 항목을 구분해 기록한다.

새 라이브러리, DB schema와 운영 변경은 문서만으로 승인된 것으로 취급하지 않고 작업의 명시적 범위를 따른다.
