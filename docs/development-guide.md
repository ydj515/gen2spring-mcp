# 개발 및 검증

## 환경과 실행

생성기는 Java 21, 생성 대상 검증은 해당 profile의 Java 17 또는 21 JDK를 사용한다.
명령은 저장소 루트에서 실행한다. 기본 버전과 task 정의는 [mise.toml](../mise.toml)을 따른다.

```bash
mise install
mise install java@17
export GEN2SPRING_JAVA_17_HOME="$(mise where java@17)"
export GEN2SPRING_JAVA_21_HOME="$(mise where java@21)"
mise run dev
```

Web의 `READY` JSON에 있는 URL로 접속한다. CLI 설치와 실제 입력 예제는 [사용자 가이드](user-guide.md)에 있다.

## 검증 범위

| 명령 | 검증하는 것 | 포함하지 않는 것 |
| --- | --- | --- |
| `mise run lint:imports` | Java import 정적 정책 | 실행 동작 |
| `mise run generator:test` | 주요 생성기 계약, emitter/validator fastTest, 선택 Web 계약 | 생성 프로젝트 내부 빌드와 전체 hosted 검증 |
| `mise run ui:test` | Web 모듈 unit/계약 테스트 | 실제 브라우저 시각·키보드 검증 |
| `mise run ui:build` | Web Boot JAR 패키징 | 사용자 흐름 검증 |
| `mise run generator:acceptance` | POSIX 12개 profile 또는 Windows 대표 profile의 실제 생성·검증 | hosted 배포 및 모든 MCP 구현 옵션의 별도 전체 조합 증명 |
| `mise run hosted:acceptance` | PostgreSQL·저장소·sandbox·worker·runtime·Web 관련 테스트 | 운영 환경의 실제 OIDC·인증서·네트워크 설정 검증 |

## CI와 전체 실행 검증

[CI](../.github/workflows/ci.yml)는 Linux·Windows에서 import 검사, core 계약, OpenAPI·filesystem 검사,
installed CLI 구성과 선택 Web 계약을 실행한다. 로컬 `generator:test`와 대상이 완전히 같지는 않다.
[Generation Acceptance](../.github/workflows/generation-acceptance.yml)는 `workflow_dispatch`로 실행하며
Linux 전체 profile과 Windows 대표 profile을 검증한다. PR fast gate 성공만으로 생성 결과의 MCP 기동까지
검증됐다고 보고하지 않는다.

Hosted 검증은 Docker 및 테스트별 외부 실행 전제가 필요하다. 배포 준비와 백업 복구는
[Hosted 배포 가이드](../deploy/hosted/README.md)를 따른다. 환경 때문에 실행하지 못한 검증은 미실행으로 기록한다.

## 변경별 검증 선택

- 파서·schema: OpenAPI/domain/application 계약, 두 루트 Swagger fixture, 생성·Managed Runtime 입력 동등성
- emitter·profile: fastTest 이후 target JDK compile, context, MCP 목록·호출, archive 결정성
- hosted 저장: 실제 PostgreSQL에서 소유권·중복 요청·동시 claim·stale fencing·원자적 게시
- 화면: Web 계약 후 파일 교체, 이전 단계 이동, preview 무효화, SSE fallback, 400px 화면과 키보드 탐색
- 문서만 변경: `git diff --check`, 내부 파일 링크·앵커, 코드 경로·명령 대조. 런타임 검증을 수행한 것으로 보고하지 않음

검증 결과는 실행한 명령, 범위, 실패·미실행 이유를 구분한다. 예전 설계의 통과 기록은 현재 결과가 아니다.
