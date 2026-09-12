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
| `mise run lint` | import 검사와 PMD, production/test/integrationTest Java 소스 | Kotlin 빌드 스크립트, 생성 템플릿 문자열 내부 Java의 분석 |
| `mise run architecture:test` | 모든 production 모듈의 ArchUnit·Gradle 의존 방향과 검사 자체의 회귀 테스트 | 컨테이너 실행·생성 프로젝트의 runtime 검증 |
| `mise run generator:test` | 주요 생성기 계약, emitter/validator fastTest, 선택 Web 계약 | 생성 프로젝트 내부 빌드와 전체 hosted 검증 |
| `mise run ui:test` | Web 모듈 unit/계약 테스트 | 실제 브라우저 시각·키보드 검증 |
| `mise run ui:build` | Web Boot JAR 패키징 | 사용자 흐름 검증 |
| `mise run generator:acceptance` | POSIX 12개 profile 또는 Windows 대표 profile의 실제 생성·검증 | hosted 배포 및 모든 MCP 구현 옵션의 별도 전체 조합 증명 |
| `mise run hosted:acceptance` | PostgreSQL·저장소·sandbox·worker·runtime·Web 관련 테스트 | 운영 환경의 실제 OIDC·인증서·네트워크 설정 검증 |

## CI와 전체 실행 검증

[CI](../.github/workflows/ci.yml)는 Linux·Windows에서 import 검사, core 계약, OpenAPI·filesystem 검사,
installed CLI 구성과 선택 Web 계약을 실행한다. 모든 `Test` task는 먼저 Java 품질 검사와 전체 모듈의
아키텍처 검사를 요구하며, 루트 `check`에도 연결한다. 로컬 `generator:test`와 대상이 완전히 같지는 않다.
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

## Java 품질과 의존 방향 검사

Java 정적 분석에는 PMD 7.27.0, 의존 방향 검사에는 ArchUnit 1.5.0을 사용한다.
버전은 [version catalog](../gradle/libs.versions.toml)에서 관리한다.
Checkstyle을 추가로 도입하지 않고 기존 `verifyJavaImportStyle`이 FQCN 정책을 계속 담당한다.

[PMD 규칙](../config/pmd/ruleset.xml)은 null 비교·finally 반환·switch fall-through·미사용 값 등
15개 규칙에 집중한다. Parser의 byte/codepoint cursor와 bounded stream read는 classic for 변수의 수동
진행이 필요하므로 `forReassign=allow`, foreach 변수 변경은 `deny`로 구분한다. 복잡도·서식의 일괄 변경이나
전체 경고 baseline은 도입하지 않는다. 각 source set의 실제 Java 파일을 분석하며 템플릿 문자열 안의 생성
Java는 기존 생성 프로젝트 검증으로 확인한다.

`verifyPmdRules`는 잘못된 규칙 이름·비어 있는 ruleset을 분석 전에 거부한다. PMD violation과 보고서의
processing/configuration error도 빌드를 실패시킨다. PMD 실행은 shared build service로 동시에 2개까지
허용하며, 보고서는 모듈별 `build/reports/pmd`에 XML·HTML로 기록한다.

루트 `src/test/java`는 제품 모듈에 포함되지 않는 검증 harness다. `architectureTest`는 루트 `test`를 실행하고
모든 production class directory와 실제 `compileClasspath`·`runtimeClasspath`의 선언된 project dependency를
검사한다. Test 전용 모듈 의존은 production graph에서 제외한다. 빈 class directory나 누락된 모듈은 실패한다.
Domain 역방향 fixture, 문자열 오탐 방지, 미사용 Gradle 의존 추가, PMD 실제 오류와 무효 설정도 회귀 테스트한다.
상세 규칙과 허용 예외는 [아키텍처 경계](architecture-boundaries.md#자동-의존-방향-검사)에 있다.

개별 모듈 테스트도 전체 품질·아키텍처 검사를 먼저 실행하므로 다른 모듈의 Java 컴파일이 필요할 수 있다.
이 선행 과정은 integration test 소스를 컴파일·분석하지만 컨테이너·생성 서버 통합 테스트를 실행하지 않는다.
전체 generation acceptance와 hosted acceptance는 기존 명령과 환경 조건을 별도로 유지한다.
