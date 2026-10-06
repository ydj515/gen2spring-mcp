# OpenAPI MCP Generator

**OpenAPI 명세로 기존 REST API를 AI가 사용할 수 있는 MCP 도구로 만듭니다.**

[사용자 가이드](docs/user-guide.md) · [전체 문서](docs/README.md) · [개발 가이드](docs/development-guide.md)

OpenAPI MCP Generator는 OpenAPI 3.0·3.1 명세에서 필요한 API를 선택해
Java 기반 MCP(Model Context Protocol) 서버 프로젝트를 생성하는 도구입니다.
API마다 연결 코드를 직접 작성하는 작업을 줄이고, 생성한 프로젝트를 검증해 ZIP으로 제공합니다.

```text
OpenAPI 명세 → API 선택·도구 설정 → 서버 코드 생성·검증 → 프로젝트 ZIP
```

## 주요 기능

- **웹 화면과 CLI** — API를 선택하고 도구 이름, 입력값, 응답 처리 방식을 설정합니다.
- **서버 프로젝트 생성** — Spring AI 또는 MCP Java SDK 기반 코드를 생성하며, Java 17·21과 Gradle·Maven을 지원합니다.
- **MCP 버전 병행 지원** — Managed Runtime은 `2025-03-26`과 `2026-07-28`을 병행 지원합니다. 직접 Java SDK 생성 서버는 화면에서 구형·신형·병행 지원을 선택합니다. Spring AI 생성 서버는 기존 버전을 유지합니다.
- **생성 결과 검증** — 선택한 검증 수준에 따라 컴파일, 서버 기동, MCP 도구 호출을 확인합니다.
- **Hosted 운영** — 생성 작업과 도구 목록을 관리하고, Managed Runtime에서 도구를 실행할 수 있습니다.

## 문서

- [사용자 가이드](docs/user-guide.md) — 설치, 웹·CLI 사용법, 지원 조합과 제한
- [개발 및 검증](docs/development-guide.md) — 개발 환경, 테스트, 품질 검사
- [프로젝트 구조](docs/project-structure.md) · [아키텍처 경계](docs/architecture-boundaries.md) — 코드 구성과 의존 방향
- [Hosted 배포 가이드](deploy/hosted/README.md) — 배포, 백업, 운영
- [전체 문서](docs/README.md) — 제품 요구사항, 설계, 생성 파이프라인, Managed Runtime

## mise 환경과 초기 설정

`mise.toml`은 도구·공통 task, `mise.dev.toml`/`mise.prod.toml`은 공유 환경 선택을 담당한다.

```bash
mise trust ./mise.toml   # task와 overlay를 검토한 뒤 신뢰
mise run bootstrap     # 프로젝트 도구를 명시해 locked 설치 후 의존성 준비
mise run config:check  # task 참조·순환 검사, 앱 실행 없음
mise run verify        # 프로젝트 검증 (Docker 등 기존 검증 전제는 유지)
mise -E dev run verify
```

- 기본 실행은 `APP_ENV=local`, `-E dev`는 개발 overlay, `-E prod`는 운영 설정 선택이다. 환경 선택 자체가 배포나 서비스 시작을 수행하지 않는다.
- 개인 개발 설정은 `mise.dev.local.toml.example`을 검토해 `mise.dev.local.toml`로 복사한다. `.env.dev.local`을 만든 뒤 `env._.file`을 활성화하면 dev에서만 읽는다. 기존 개인 파일을 덮어쓰지 않는다.
- `mise.local.toml`은 **모든 환경**에서 로드된다. prod checkout에 개인 override나 개발 dotenv를 두지 않는다. `-E local`은 사용하지 않는다.
- `APP_ENV`는 공통 식별자다. Spring 실행 task의 프로파일은 해당 task에서 매핑하며, 존재하지 않는 운영 설정을 자동 생성하지 않는다. prod 선택만으로 기존 개발용 앱이 운영 준비를 마친 것은 아니다.
- `mise.lock`은 도구 잠금이며 npm/pnpm/Gradle 의존성 잠금과 별개다. 버전 변경 시 `mise lock`을 실행하고 diff를 검토한다. CI에서는 동일한 `-E` 선택으로 `mise install --locked` 후 검증한다. 기본 대상은 macOS ARM64와 Linux x64다.
- `mise run bootstrap`은 프로젝트 초기 설정이다. OS package·dotfile·서비스를 관리하는 `mise bootstrap`은 개인 머신 설정에서 별도로 채택한다.

생성 프로젝트 검증에 기존 task가 요구하는 Java 17.0.2도 함께 선언했다. Java 21.0.2가 기본이며, 이 저장소 잠금 파일은 Windows x64도 포함한다.
