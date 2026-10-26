# 개발 및 검증

## 환경과 실행

생성기는 Java 21, 생성 대상 검증은 해당 profile의 Java 17 또는 21 JDK를 사용한다.
명령은 저장소 루트에서 실행한다. 기본 버전과 task 정의는 [mise.toml](../mise.toml)을 따른다.

```bash
mise trust ./mise.toml
mise run bootstrap
export GEN2SPRING_JAVA_17_HOME="$(mise where java@17.0.2)"
export GEN2SPRING_JAVA_21_HOME="$(mise where java@21.0.2)"
mise run dev
```

Web의 `READY` JSON에 있는 URL로 접속한다. CLI 설치와 실제 입력 예제는 [사용자 가이드](user-guide.md)에 있다.

### mise 환경 구성

`mise.toml`은 도구·공통 task, [`mise.dev.toml`](../mise.dev.toml)·[`mise.prod.toml`](../mise.prod.toml)은 공유 환경 선택을 담당한다.

```bash
mise trust ./mise.toml  # task와 overlay를 검토한 뒤 신뢰
mise run bootstrap      # 고정된 버전의 도구 설치 후 의존성 준비
mise run config:check   # task 참조·순환 검사, 앱 실행 없음
mise run verify         # 프로젝트 검증 (Docker 등 기존 검증 전제는 유지)
mise -E dev run verify
```

- **환경 프로필(`-E dev`, `-E prod`):** 기본 실행은 `APP_ENV=local`, `-E dev`는 개발 overlay, `-E prod`는 운영 설정 선택이다. 환경 선택 자체가 배포나 서비스 시작을 수행하지 않는다.
- **개인 개발 설정(`mise.dev.local.toml`):** 개인 개발 설정은 [`mise.dev.local.toml.example`](../mise.dev.local.toml.example)을 검토해 `mise.dev.local.toml`로 복사한다. `.env.dev.local`을 만든 뒤 `env._.file`을 활성화하면 `dev`에서만 읽는다. 기존 개인 파일을 덮어쓰지 않는다.
- **전역 로컬 오버라이드 주의(`mise.local.toml`):** `mise.local.toml`은 **모든 환경**에서 로드된다. `prod` checkout에 개인 override나 개발 dotenv를 두지 않는다. `-E local`은 사용하지 않는다.
- **`APP_ENV` 식별자:** `APP_ENV`는 공통 식별자다. Spring 실행 task의 프로파일은 해당 task에서 매핑하며, 존재하지 않는 운영 설정을 자동 생성하지 않는다. `prod` 선택만으로 기존 개발용 앱이 운영 준비를 마친 것은 아니다.
- **도구 버전 고정 및 `mise.lock` 정책:** mise는 개발 도구의 정확한 버전 고정과 프로필 분리에 사용한다. `[settings] lockfile = false`로 도구 lock 생성을 끄고 `mise.lock`은 관리하지 않는다. 공통 표준의 mise lock 지침보다 이 저장소의 정책을 우선하며, 설치 파일까지 고정해야 하는 요구가 생기면 다시 도입한다.
- **애플리케이션 잠금 유지:** 도구 버전은 `mise.toml`의 `[tools]`에서 관리하며 `dev`/`prod`에서도 같은 버전을 사용한다. 로컬과 CI는 `mise install` 또는 같은 정확한 버전의 setup action으로 도구를 준비한다. `package-lock.json`, `pnpm-lock.yaml`, `uv.lock`, Gradle lock 등 애플리케이션 의존성 잠금과 검증 옵션은 유지한다.
- **OS 및 플랫폼 호환성:** 지원 OS는 각 도구와 실행 스크립트의 호환성에 따른다. mise lock을 사용하지 않는다고 Windows 실행까지 보장되는 것은 아니다. 생성 프로젝트 검증에 기존 task가 요구하는 Java `17.0.2`도 함께 선언했으며, Java `21.0.2`가 기본이고 Windows 실행 task는 `gradlew.bat`를 사용한다.
- **부트스트랩 구분:** `mise run bootstrap`은 프로젝트 초기 설정이다. OS package·dotfile·서비스를 관리하는 `mise bootstrap`은 개인 머신 설정에서 별도로 채택한다.

## 검증 범위

| 명령 | 검증하는 것 | 포함하지 않는 것 |
| --- | --- | --- |
| `./gradlew spotlessCheck` | 도입 기준 커밋(`ratchetFrom`) 이후 변경·신규 Java 파일의 Google Java Format(AOSP) 서식 | 기준 커밋 이전 미변경 파일, 빌드 산출물 |
| `./gradlew spotlessApply` | 변경·신규 Java 파일에 Google Java Format(AOSP, 4칸 들여쓰기)·공백·개행 규칙 적용 | 정적 분석 및 런타임 검증 |
| `mise run lint:imports` | Java import 정적 정책 | 실행 동작 |
| `mise run lint` | Spotless 포맷 검사, import 검사와 Checkstyle·PMD, production/test/integrationTest Java 소스 | Kotlin 빌드 스크립트, 생성 템플릿 문자열 내부 Java의 분석 |
| `mise run coverage:check` | 고정 16개 suite, 전체 production 라인 65%·브랜치 55% 하한 | 컨테이너·생성 프로젝트 acceptance 실행 |
| `mise run architecture:test` | 모든 production 모듈의 ArchUnit·Gradle 의존 방향과 검사 자체의 회귀 테스트 | 컨테이너 실행·생성 프로젝트의 runtime 검증 |
| `mise run generator:test` | 주요 생성기 계약, emitter/validator fastTest, 선택 Web 계약 | 생성 프로젝트 내부 빌드와 전체 hosted 검증 |
| `mise run ui:test` | Web 모듈 unit/계약 테스트 | 실제 브라우저 시각·키보드 검증 |
| `mise run ui:build` | Web Boot JAR 패키징 | 사용자 흐름 검증 |
| `mise run verify` | 루트 `check` 게이트(`spotlessCheck`, `verifyJavaQuality`, `architectureTest`, `coverageVerification`) | 생성 프로젝트 전체 acceptance 및 hosted 컨테이너 기동 |
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

## 생성 프로젝트 전체 검증

`mise run generator:acceptance`는 POSIX에서 release profile matrix의 세 시나리오,
Windows에서 대표 profile 시나리오 하나를 실행한다.
MCP 구현 옵션, 제공된 OpenAPI 3.0/3.1 명세, emitter 회귀와 Web 다운로드까지 확인하려면 다음을 실행한다.

```bash
export GEN2SPRING_JAVA_17_HOME="$(mise where java@17.0.2)"
export GEN2SPRING_JAVA_21_HOME="$(mise where java@21.0.2)"
./gradlew \
  :apps:cli:integrationTest \
  :modules:adapters:emitters:spring-ai-1:integrationTest \
  :modules:adapters:emitters:spring-ai-2:integrationTest \
  :modules:adapters:validation:test \
  :apps:web:integrationTest \
  --no-daemon --non-interactive --no-parallel --max-workers=2 --rerun-tasks
```

- CLI suite 전체는 12개 release profile의 생성·빌드·MCP 실행·archive 결정성, 지원되는
  annotation/SDK 옵션, 두 OpenAPI 버전의 schema 계약과 잘못된 대표 인자를 검증한다.
- 두 emitter의 `integrationTest`는 생성 코드의 컴파일·실행, MVC·reactive·async 동작을 검증한다.
- validation의 `test`는 fast suite에서 제외한 실제 생성 프로젝트 smoke test도 포함한다.
- Web의 `integrationTest`는 설치된 JAR에서 profile 조회와 생성·검증·다운로드·삭제 여정을 확인한다.
- 각 suite의 결과는 해당 모듈 `build/reports/tests/<task>/index.html`과
  `build/test-results/<task>/TEST-*.xml`에 기록한다. 전체 통과 여부와 skipped 수를 함께 확인한다.

명령은 실행 중인 OS에서 검증한다. `windowsRepresentativeProfilesValidateAcrossTargetAxes`가
macOS/Linux에서 통과해도 Windows 검증을 대신하지 않는다.
실제 Linux·Windows 실행은 [Generation Acceptance](../.github/workflows/generation-acceptance.yml)를
`workflow_dispatch`로 실행한다. 이 워크플로는 Linux 전체 profile matrix와 Windows 대표 조합·실행 명령 회귀를 다루며,
위 로컬 명령의 모든 integration suite를 원격에서 실행하는 것은 아니다.
Hosted 저장·worker·runtime acceptance는 별도 범위다.

## 변경별 검증 선택

- 파서·schema: OpenAPI/domain/application 계약, 두 루트 Swagger fixture, 생성·Managed Runtime 입력 동등성
- emitter·profile: fastTest 이후 target JDK compile, context, MCP 목록·호출, archive 결정성
- hosted 저장: 실제 PostgreSQL에서 소유권·중복 요청·동시 claim·stale fencing·원자적 게시
- 화면: Web 계약 후 파일 교체, 이전 단계 이동, preview 무효화, SSE fallback, 400px 화면과 키보드 탐색
- 문서만 변경: `git diff --check`, 내부 파일 링크·앵커, 코드 경로·명령 대조. 런타임 검증을 수행한 것으로 보고하지 않음

검증 결과는 실행한 명령, 범위, 실패·미실행 이유를 구분한다. 예전 설계의 통과 기록은 현재 결과가 아니다.

## Java 품질과 의존 방향 검사

Java 코드 포맷에는 Spotless 7.0.2와 Google Java Format 1.25.2(AOSP), 스타일 검사에는 Checkstyle 10.21.4,
정적 분석에는 PMD 7.27.0, 커버리지에는 JaCoCo 0.8.13, 의존 방향 검사에는 ArchUnit 1.5.0을 사용한다.
버전은 [version catalog](../gradle/libs.versions.toml)에서 관리한다.
Kotlin 소스가 없는 Java 프로젝트이므로 Kover·ktlint·detekt는 적용하지 않는다.
기존 `verifyJavaImportStyle`이 FQCN 정책을 계속 담당한다.

### Java 포맷팅 (Spotless)

`./gradlew spotlessCheck`는 모든 모듈과 소스셋(`**/src/**/*.java`)에서 도입 기준 커밋(`ratchetFrom`) 이후
변경되거나 새로 작성된 Java 파일을 검사하며, `./gradlew spotlessApply`는 해당 파일들을 고정된
Google Java Format(AOSP, 4칸 들여쓰기)과 줄 끝 공백 제거·파일 끝 개행 규칙으로 포맷한다.
생성된 빌드 산출물(`build/**`, `.gradle/**`)은 대상에서 제외된다.
`spotlessCheck`는 `verifyJavaQuality`(`mise run lint`)와 루트 `check`(`mise run verify`)에 포함되어 있으며,
CI는 전체 Git 이력(`fetch-depth: 0`)을 체크아웃해 `ratchetFrom` 기준 커밋을 항상 유지한다.
기존 정적 분석(import 정책, Checkstyle, PMD)도 함께 활성 상태를 유지한다.

[Checkstyle 규칙](../config/checkstyle/checkstyle.xml)은 wildcard·중복·미사용 import,
파일명과 최상위 타입명 일치, modifier 순서, 파일 끝 개행을 검사한다.
production·test·integrationTest 소스를 `verifyJavaQuality`와 기존 CI에서 검사하며,
위반은 빌드를 실패시킨다. 보고서는 모듈별 `build/reports/checkstyle`에 생성한다.

[Gradle JaCoCo 플러그인](https://docs.gradle.org/current/userguide/jacoco_plugin.html)으로
각 `Test` 태스크에 계측과 보고서를 연결한다. `test`·`fastTest`·`integrationTest` 실행 후
모듈별 `build/reports/jacoco/<태스크명>/coverage.xml`과 `html/index.html`을 생성한다.
`jacocoTestReport`·`jacocoFastTestReport`·`jacocoIntegrationTestReport`를 직접 실행하면
해당 테스트도 실행한다. 실행 데이터는 각 Test 태스크의 JaCoCo 설정에서 가져오므로 서로 섞이지 않는다.
보고서는 해당 모듈 production 클래스와 실행한 테스트만 대상으로 하며, 필터 실행이나 fast suite 결과는
전체 테스트 커버리지가 아니다. 별도 JVM으로 실행한 생성 프로젝트·서버는 계측하지 않는다.
`mise run coverage:check` (`./gradlew coverageVerification`)는 **전체 production 클래스 합산 라인 65%,
브랜치 55%**를 하한으로 검사한다. 루트 `check`와 Linux·Windows CI에 연결되어 하한 미달이면 실패한다.
미실행 production 코드도 분모에 포함하며 클래스 제외는 없다.
POSIX 전용 테스트는 Windows에서 건너뛰므로 플랫폼별 커버리지는 다를 수 있지만 하한은 동일하다.

측정 테스트는 domain·application·configuration·openapi·filesystem·emitters/support·bootstrap·web의
`test`, runtime·fetch-gateway·provider-egress 앱의 `test`와
spring-ai-1·spring-ai-2·validation·cli의 `fastTest`, 총 15개 suite로 고정한다.
세 hosted 앱의 단위 테스트는 컨테이너 없이 보안·요청 처리·런타임 동작을 검증하며 양쪽 CI에서 실행한다.
Web은 필터 없이 전체 단위 테스트를 실행한다. 기존 fastTest의 생성 프로젝트 실행 제외는 유지한다.
컨테이너 기반 hosted acceptance와 생성 프로젝트 acceptance는 이 하한의 실행 범위에 포함하지 않는다.
각 suite를 먼저 실행하고 `JacocoTaskExtension.destinationFile`의 데이터가 모두 존재하는지 확인한다.
필수 파일 누락·빈 파일은 보고서 생성 전에 실패하며, 과거 integrationTest나 다른 임의 exec 파일은 합산하지 않는다.
합산 보고서는 `build/reports/jacoco/coverageReport`에 XML·HTML로 생성한다.

[PMD 규칙](../config/pmd/ruleset.xml)은 null 비교·finally 반환·switch fall-through·미사용 값 등
15개 규칙에 집중한다. Parser의 byte/codepoint cursor와 bounded stream read는 classic for 변수의 수동
진행이 필요하므로 `forReassign=allow`, foreach 변수 변경은 `deny`로 구분한다. 복잡도·서식의 일괄 변경이나
전체 경고 baseline은 도입하지 않는다. 각 source set의 실제 Java 파일을 분석하며 템플릿 문자열 안의 생성
Java는 기존 생성 프로젝트 검증으로 확인한다.

`verifyPmdRules`는 잘못된 규칙 이름·비어 있는 ruleset을 분석 전에 거부한다. PMD violation과 보고서의
processing/configuration error도 빌드를 실패시킨다. Checkstyle·PMD 실행은 shared build service로 합쳐서 동시에 2개까지
허용하며, 보고서는 모듈별 `build/reports/pmd`에 XML·HTML로 기록한다.

루트 `src/test/java`는 제품 모듈에 포함되지 않는 검증 harness다. `architectureTest`는 루트 `test`를 실행하고
모든 production class directory와 실제 `compileClasspath`·`runtimeClasspath`의 선언된 project dependency를
검사한다. Domain·application·공유 emitter의 외부 라이브러리 선언도 허용 목록과 대조한다.
Test 전용 모듈 의존은 production graph에서 제외한다. 빈 class directory나 누락된 모듈은 실패한다.
`architectureTest`는 [모듈 의존성 다이어그램](module-dependencies.md)의 그림·텍스트 표·개수도
Gradle의 직접 production project 선언과 비교한다. 문서와 선언을 모두 검사 입력으로 등록하므로
다른 내용에서 얻은 통과 결과를 재사용하지 않는다.
Domain 역방향 fixture, 문자열 오탐 방지, 미사용 project/external 의존 추가, port에서 service 참조,
application의 직접 thread pool 사용, PMD 실제 오류와 무효 설정도 회귀 테스트한다.
상세 규칙과 허용 예외는 [아키텍처 경계](architecture-boundaries.md#자동-의존-방향-검사)에 있다.

개별 모듈 테스트도 전체 품질·아키텍처 검사를 먼저 실행하므로 다른 모듈의 Java 컴파일이 필요할 수 있다.
이 선행 과정은 integration test 소스를 컴파일·분석하지만 컨테이너·생성 서버 통합 테스트를 실행하지 않는다.
전체 generation acceptance와 hosted acceptance는 기존 명령과 환경 조건을 별도로 유지한다.
