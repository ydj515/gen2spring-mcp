# OpenAPI MCP Generator

[![CI](https://github.com/ydj515/gen2spring-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/ydj515/gen2spring-mcp/actions/workflows/ci.yml)
[![Generation Acceptance](https://github.com/ydj515/gen2spring-mcp/actions/workflows/generation-acceptance.yml/badge.svg)](https://github.com/ydj515/gen2spring-mcp/actions/workflows/generation-acceptance.yml)
[![Sync Development Standards](https://github.com/ydj515/gen2spring-mcp/actions/workflows/sync-dev-standards.yml/badge.svg)](https://github.com/ydj515/gen2spring-mcp/actions/workflows/sync-dev-standards.yml)
![Java](https://img.shields.io/badge/Java-21%20%7C%2017-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![MCP](https://img.shields.io/badge/MCP-2025--03--26%20%7C%202026--07--28-4A90E2)

OpenAPI 3.0·3.1 명세에서 필요한 API를 선택해 Java 기반 MCP(Model Context Protocol) 서버 프로젝트를 생성하고 검증하는 도구입니다.
로컬 웹 화면과 CLI에서 엔드포인트 선택·도구 설정·응답 정규화를 구성할 수 있으며, 다중 사용자를 위한 Hosted 플랫폼과 격리된 Managed Runtime을 함께 제공합니다.

```text
OpenAPI 명세 → API 선택·도구 설정 → 서버 코드 생성·검증 → 프로젝트 ZIP
```

**기술 스택:** Java 21 · Spring Boot 3.5 · Spring AI 1.1/2.0 · MCP Java SDK · Gradle 9.6 · PostgreSQL · Docker Compose

## 주요 기능

- **웹 화면과 CLI:** API를 선택하고 도구 이름, 입력값, 응답 정규화·재시도·페이지네이션 방식을 설정합니다.
- **서버 프로젝트 생성:** Spring AI 또는 MCP Java SDK 기반 코드를 생성하며, Java 17·21, Gradle·Maven, MVC·WebFlux 조합을 지원합니다.
- **MCP 버전 병행 지원:** Managed Runtime은 `2025-03-26`과 `2026-07-28`을 병행 지원합니다. 직접 Java SDK 생성 서버는 화면에서 구형·신형·병행 지원을 선택하며, Spring AI 생성 서버는 기존 버전을 유지합니다.
- **생성 결과 검증:** 선택한 검증 수준에 따라 타깃 JDK 컴파일, 서버 기동, MCP 도구 호출을 확인한 뒤 검증된 프로젝트만 ZIP으로 제공합니다.
- **Hosted 운영:** 생성 작업과 Tool Catalog를 관리하고, 격리된 Managed Runtime에서 도구를 실행할 수 있습니다.

## 요구 사항

- [mise](https://mise.jdx.dev/) — 생성기 실행용 Java 21(`21.0.2`)과 타깃 검증용 Java 17(`17.0.2`) 버전은 [`mise.toml`](mise.toml)에 고정되어 있으며 `mise`가 설치합니다. 빌드 도구는 저장소의 Gradle Wrapper(`gradlew`)를 사용합니다.

## 빠른 시작

### 로컬 웹 화면

```bash
mise trust ./mise.toml  # 최초 1회, mise.toml 검토 후 신뢰
mise run bootstrap      # 고정된 Java 21·17 설치 및 Gradle Wrapper 확인
mise run dev            # 로컬 웹 편집기 빌드 및 실행
```

터미널의 `READY` 로그에 표시된 주소(`http://127.0.0.1:<port>/`)를 여세요.
OpenAPI 파일 업로드부터 엔드포인트 선택, 생성 설정, 검증 및 프로젝트 ZIP 다운로드까지 5단계 위저드로 진행할 수 있습니다.
포트를 고정하려면 `GEN2SPRING_UI_PORT=8080 mise run dev`로 실행합니다.

### CLI 단일 실행

```bash
./gradlew :apps:cli:installDist --no-daemon --non-interactive
OPENAPI_MCP=apps/cli/build/install/openapi-mcp/bin/openapi-mcp

mkdir -p out
"$OPENAPI_MCP" inspect \
  --spec swagger-3.0.yml \
  --output out/analysis.json

GEN2SPRING_JAVA_17_HOME="$(mise where java@17.0.2)" \
"$OPENAPI_MCP" generate \
  --spec apps/cli/src/integrationTest/resources/openapi/weather-api.yaml \
  --config apps/cli/src/integrationTest/resources/config/weather-generation.yaml \
  --output out/weather-mcp-server
```

전체 CLI 옵션·설정 파일 스키마와 Hosted 플랫폼 구성은 [사용자 가이드](docs/user-guide.md)와 [Hosted 배포 가이드](deploy/hosted/README.md)에 설명합니다.

## 주요 명령

| 명령 | 설명 |
| --- | --- |
| `mise run dev` | 로컬 Spring Boot 웹 편집기 빌드 후 격리 실행 |
| `mise run generator:test` | 주요 생성기·emitter·validator·Web 빠른 계약 테스트 |
| `mise run ui:test` | 로컬 웹 편집기 단위 및 계약 테스트 |
| `mise run lint` | Spotless 포맷, import 정책, Checkstyle, PMD 정적 분석 |
| `mise run architecture:test` | 모듈 의존 방향 및 바이트코드 아키텍처 경계 검증 |
| `mise run coverage:check` | 전체 운영 클래스 라인 65%·브랜치 55% 커버리지 하한 검증 |
| `mise run verify` | 저장소 전체 품질 게이트 실행 (`./gradlew check`) |
| `mise run generator:acceptance` | 생성 프로젝트 실제 빌드·기동·MCP 프로토콜 통합 검증 |
| `mise run hosted:up` | 단일 호스트 Hosted 플랫폼(Compose) 기동 |

새로 작성하거나 수정한 Java 코드는 `./gradlew spotlessApply`로 포맷을 맞출 수 있습니다.

전체 task 목록은 `mise tasks`로 확인할 수 있습니다. `dev`/`prod` 환경 프로필(`-E dev`, `-E prod`)과 개인 설정(`mise.dev.local.toml`), Java 포맷팅·품질 기준은 [개발 가이드의 mise 환경 구성](docs/development-guide.md#mise-환경-구성)을 참고하세요.

## 문서

- [사용자 가이드](docs/user-guide.md) — 설치, 웹·CLI 사용법, 지원 조합과 제한
- [개발 및 검증](docs/development-guide.md) — `mise` 환경 구성, 테스트, 코드 포맷·품질 검사
- [프로젝트 구조](docs/project-structure.md) · [아키텍처 경계](docs/architecture-boundaries.md) — 코드 구성과 의존 방향
- [생성 파이프라인과 스키마](docs/generation-pipeline.md) — Tool IR, 스키마 정규화, 검증과 결정성
- [Hosted 배포 가이드](deploy/hosted/README.md) — 배포, 백업, 운영
- [전체 문서](docs/README.md) — 제품 요구사항, 설계 의사결정, Managed Runtime 상세 안내

## 기여

변경 전 [기여 가이드](./CONTRIBUTING.md)를 확인해 주세요.
