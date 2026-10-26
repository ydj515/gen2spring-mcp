# 기여 가이드

OpenAPI MCP Generator에 기여해 주셔서 감사합니다. 변경 사항을 제출하기 전에 아래 환경 설정과 검증 절차를 확인해 주세요.

## 1. 개발 환경 준비

도구 버전(Java `21.0.2`, 타깃 검증용 Java `17.0.2`)과 공통 작업은 [`mise.toml`](./mise.toml)에 정의되어 있습니다.

```bash
mise trust ./mise.toml
mise run bootstrap
mise run config:check
```

- `dev`/`prod` 환경 오버레이(`-E dev`, `-E prod`)와 개인 로컬 설정(`mise.dev.local.toml`) 정책은 [개발 가이드의 mise 환경 구성](./docs/development-guide.md#mise-환경-구성)을 참고하세요.
- `mise.local.toml`은 모든 환경에서 로드되므로 개인 개발용 오버라이드는 반드시 `mise.dev.local.toml`에 작성합니다.

## 2. 코드 작성 및 포맷팅 규칙

- **Java 포맷팅 (Spotless):** 변경하거나 새로 작성한 Java 파일(`**/src/**/*.java`)은 고정된 Google Java Format(AOSP, 4칸 들여쓰기)을 따릅니다. 커밋 전에 아래 명령으로 포맷을 적용하고 확인하세요.
  ```bash
  ./gradlew spotlessApply
  ./gradlew spotlessCheck
  ```
- **Import 및 정적 분석:** 와일드카드·미사용 import와 불필요한 FQCN(Fully Qualified Class Name) 사용을 금지합니다.
  ```bash
  mise run lint:imports
  mise run lint
  ```
- **아키텍처 경계:** 계층 및 모듈 의존 규칙은 [`architectureTest`](./docs/architecture-boundaries.md)로 검증합니다. 프로덕션 모듈 간 의존성을 변경한 경우 [모듈 의존성 다이어그램](./docs/module-dependencies.md)도 함께 갱신해야 합니다.
  ```bash
  mise run architecture:test
  ```

## 3. 테스트 및 검증

변경 범위에 맞는 검증 명령을 실행하고 통과 여부를 확인합니다.

| 변경 영역 | 필수 확인 명령 |
| --- | --- |
| 일반 코드·빌드 변경 | `mise run verify` (`spotlessCheck`, `verifyJavaQuality`, `architectureTest`, `coverageVerification`) |
| 생성기·스키마·emitter | `mise run generator:test` 및 필요 시 `mise run generator:acceptance` |
| 로컬 웹 UI (`apps/web`) | `mise run ui:test`, `mise run ui:build` |
| Hosted·Managed Runtime | `mise run hosted:config`, `mise run hosted:acceptance` |
| 문서 변경 | `git diff --check`, 상대 경로 링크 및 실제 명령·옵션 일치 여부 |

전체 테스트 스위트와 커버리지 기준(전체 프로덕션 라인 65%, 브랜치 55% 이상)은 [개발 및 검증 가이드](./docs/development-guide.md)에 설명되어 있습니다.

## 4. 제출 전 체크리스트

- [ ] [새 기능 추가 체크리스트](./docs/feature-addition-checklist.md)의 책임 배치 및 보안·오류 경계를 확인했습니다.
- [ ] `./gradlew spotlessApply` 및 `mise run verify`가 오류 없이 통과합니다.
- [ ] 사용자 설정·프로필·운영 계약이 바뀐 경우 [사용자 가이드](./docs/user-guide.md)와 [전체 문서 안내](./docs/README.md)를 함께 갱신했습니다.
- [ ] 로그, 예외 메시지, 매니페스트, 테스트 픽스처에 실제 시크릿이나 개인 경로가 포함되지 않았습니다.
