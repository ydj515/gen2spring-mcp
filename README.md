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
- **생성 결과 검증** — 선택한 검증 수준에 따라 컴파일, 서버 기동, MCP 도구 호출을 확인합니다.
- **Hosted 운영** — 생성 작업과 도구 목록을 관리하고, Managed Runtime에서 도구를 실행할 수 있습니다.

## 문서

- [사용자 가이드](docs/user-guide.md) — 설치, 웹·CLI 사용법, 지원 조합과 제한
- [개발 및 검증](docs/development-guide.md) — 개발 환경, 테스트, 품질 검사
- [프로젝트 구조](docs/project-structure.md) · [아키텍처 경계](docs/architecture-boundaries.md) — 코드 구성과 의존 방향
- [Hosted 배포 가이드](deploy/hosted/README.md) — 배포, 백업, 운영
- [전체 문서](docs/README.md) — 제품 요구사항, 설계, 생성 파이프라인, Managed Runtime
