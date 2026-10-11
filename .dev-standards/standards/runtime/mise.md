# mise Runtime Guidelines

이 문서는 프로젝트의 런타임과 검증 명령을 `mise.toml`로 재현하기 위한 공통
계약입니다. 중앙 표준은 task의 의미를 정의하고, 정확한 버전과 명령은 각 프로젝트가 소유합니다.

## 적용 기준

권장: 루트 `mise.toml` + 공유 환경 overlay + 개인 override를 기본으로 사용합니다.
이유: 도구 버전과 검사 진입점을 한곳에서 관리하며, 앱이 늘어나도 같은 계약을 유지합니다.
트레이드오프: 앱별 독립 설정보다 루트 변경의 영향 범위가 큽니다.
전환 조건: 독립 도구 버전 또는 릴리스 주기를 가진 앱이 2개 이상이면 아래의 하위 config 방식으로 분리합니다.

이 표준의 애플리케이션 예제는 POSIX shell, Spring Boot/Gradle, pnpm과 기존 Docker
Compose v2 구성을 가정합니다. 범용 언어 profile은 그대로 유지하며, 실제 버전·서비스명·경로는
소비 저장소가 확정합니다. 새 라이브러리나 Docker 엔진을 자동 설치하지 않습니다.

## 버전과 설정 소유권

- 언어 런타임과 package manager 버전은 프로젝트 루트의 `mise.toml`에 고정합니다.
- `min_version`에는 이 설정과 task가 요구하는 최소 mise 버전을 명시합니다. mise 실행 파일
  자체는 `mise.lock` 대상이 아니므로 CI action 또는 설치 경로의 버전도 별도로 고정합니다.
- CI와 로컬 개발자는 같은 `mise.toml`을 사용하고 별도 CI 전용 버전을 만들지 않습니다.
- `mise.local.toml`에는 개인 설정만 두고 저장소의 `.gitignore`에 포함합니다.
- 시크릿은 `mise.toml`에 기록하지 않고 CI secret 또는 승인된 secret manager에서 주입합니다.

## Lockfile과 신뢰 경계

- `[settings] lockfile = true`를 활성화하고 `mise.toml`과 생성된 `mise.lock`을 함께 버전
  관리합니다. `mise.lock`에는 concrete version과 backend가 지원하는 URL, checksum 및
  verification metadata가 기록됩니다.
- tool 버전을 변경할 때는 `mise lock` 또는 대상 tool만 지정한 갱신 명령을 사용하고
  lockfile diff에서 version, backend, URL과 checksum 변화를 검토합니다.
- `mise.lock`은 application dependency까지 잠그지 않습니다. `pnpm-lock.yaml`, `uv.lock`,
  Gradle lock state 같은 생태계별 lockfile을 별도로 유지합니다.
- `mise.local.toml`을 사용하는 경우 생성되는 `mise.local.lock`도 함께 ignore해 개인
  runtime 선택이 공유 lockfile에 섞이지 않게 합니다.
- 처음 받은 저장소에서는 `mise.toml`, task, hook과 lockfile을 먼저 검토합니다. 상위 경로나
  모든 하위 설정을 일괄 신뢰하지 않고 필요한 project config만 신뢰합니다. 일반 trust는 하위 설정까지 신뢰 범위에
  포함될 수 있으므로 하위 task와 hook도 검토하고, 파일별 명시적 신뢰가 필요하면 paranoid 모드를 사용합니다.

## 공통 task 계약

- `mise run lint`: 프로젝트의 정적 분석을 실행합니다. formatter check를 함께 실행하는지
  별도 `format-check`로 분리하는지는 선택한 profile에 명시합니다.
- `mise run test`: 프로젝트의 자동화된 테스트를 실행합니다.
- `mise run verify`: 커밋 전 요구되는 전체 로컬 품질 게이트를 실행합니다.
- `mise run format`: 제공하는 profile에서 개발자가 명시적으로 자동 수정을 실행합니다.
  `verify`에는 파일을 수정하지 않는 검사만 연결합니다.
- 추가 task가 필요하면 `verify`에서 호출하거나 README에 별도 실행 조건을 명시합니다.

언어 또는 build tool에 맞는 시작점을 선택합니다.

- Gradle: `templates/mise/gradle/mise.toml.example`
- Go: `templates/mise/go/mise.toml.example`
- Maven: `templates/mise/maven/mise.toml.example`
- Python: `templates/mise/python/mise.toml.example`
- TypeScript: `templates/mise/typescript/mise.toml.example`

bootstrap은 선택한 profile을 루트 `mise.toml`로, 공통 환경 템플릿을 `mise.dev.toml`과
`mise.prod.toml`로 복사합니다. `mise.dev.local.toml.example`과 `mise.gitignore.example`은
채택용 예제로만 복사하며 활성 local 파일이나 `.gitignore`를 자동 수정하지 않습니다.
여러 후보가 선택되면 `--mise-profile`을 명시합니다. 복합 애플리케이션의 실행 task는
profile로 선택하지 않으며, 디렉터리 이름만 보고 자동 추론하지 않습니다.
profile의 tool 목록은 `tools` selector에 따라 자동으로 필터링되지 않으므로 실제 사용하는
도구와 버전을 확인합니다.

| Profile | `lint` 범위 | `verify` 진입점 |
| --- | --- | --- |
| Gradle | `./gradlew check -x test` | `./gradlew check` |
| Maven | `./mvnw verify -DskipTests` | `./mvnw verify` |
| Go | `golangci-lint run`으로 lint와 설정된 formatter 차이 검사 | `lint`, `test` task |
| Python | `ruff check .` | `format-check`, `lint`, `typecheck`, `test` task |
| TypeScript | `pnpm run lint` | frozen 설치 후 `pnpm run verify` |

예제는 task 이름과 실행 진입점의 기준이며, 실제 plugin과 package script가 전체
`verify` 경로에 연결되어 있는지는 각 프로젝트가 검증합니다. Gradle의 `-x test`는 해당
task만 제외하므로 별도 test suite나 coverage task가 테스트를 실행할 수 있습니다.
Maven의 `lint`도 테스트 생략 설정의 적용 범위를 확인하며, 전체 성공 판단에는 반드시
`mise run verify`를 사용합니다. Go의
race test는 비용을 고려해 별도 task로 제공하며 필요한 CI 경로에서 명시적으로 실행합니다.
Python 예제는 uv lockfile을 기준으로 환경을 동기화하고 Ruff와 공식 npm Pyright CLI를
각각 고정합니다. TypeScript 예제는 pnpm 버전을 `package.json#packageManager`와 맞추고,
frozen lockfile 설치 후 package script가 Prettier 또는 Biome 중 선택한 formatter를
실행하게 합니다. Python과 TypeScript의 `lint`만 실행하면 format 검사를 완료한 것이
아닙니다. TypeScript의 `verify` script에는 format 검사, lint, typecheck, test와 build를
연결합니다.

모든 profile은 `ci`를 `verify`의 alias로 제공합니다.
`verify`가 `lint`와 `test`에 의존하도록 구성하면 로컬과 CI가 같은 진입점을 사용할 수 있습니다.

```toml
[tasks.verify]
depends = ["lint", "test"]
```

`typescript`는 프론트와 라이브러리의 공통 도구·검증 profile입니다. 프론트의 `dev`,
`build` task나 backend·infra 의존 task는 이 profile을 복사한 뒤 프로젝트 구조에 맞게
추가합니다. bootstrap profile 종류를 앱 조합마다 늘리지 않아 frontend 단독 레포와
모노레포가 같은 기반을 공유합니다.

## 저장소 구조와 task 소유권

| 구성 | 설정 위치와 실행 방식 | 선택 근거 |
| --- | --- | --- |
| frontend만 있는 레포 | `typescript` profile + frontend task | backend·Docker·Java 없이 개발 서버와 검사 실행 |
| backend만 있는 레포 | `gradle` profile + backend task | frontend 설치·검사를 요구하지 않음 |
| frontend/backend/infra | `typescript` + `gradle` 도구와 root task의 `dir` | 도구 profile과 앱 실행 소유권을 분리 |
| 앱별 독립 운영 | 하위 `mise.toml` + 명시적인 monorepo config roots | 앱별 버전·환경 소유권을 분리 |

`dir`는 작업 디렉터리를 지정합니다. 루트 task에 `dir = "backend"`만 붙였다고
`backend/mise.toml`의 도구·환경까지 자동 선택한다고 가정하지 않습니다. 독립 설정을 쓸 때는
[monorepo mode](https://mise.jdx.dev/tasks/monorepo.html)를 사용합니다.

```toml
monorepo_root = true

[monorepo]
config_roots = ["frontend", "backend"]
# This layout needs a mise version supporting root monorepo lockfiles.
lockfile = true

[tasks.verify]
alias = "ci"
depends = ["//frontend:verify", "//backend:verify"]
```

이 방식은 하위 앱에 실제 `mise.toml`과 `verify` task가 있을 때만 사용합니다. 현재 배포
템플릿은 단일 루트 방식이므로 이 블록을 그대로 덧붙이지 않습니다. 하위 lockfile에서
루트 lockfile로 옮길 때는 mise 최소 버전과 lock diff를 함께 검증합니다.

## 환경 파일과 변수

| 파일 | 목적 | Git 관리 |
| --- | --- | --- |
| `mise.toml` | 공통 도구/task; 기본 `APP_ENV=local` | 포함 |
| `mise.dev.toml` | `-E dev`에서 공유 개발 설정 | 포함 |
| `mise.prod.toml` | `-E prod`에서 운영용 설정값 선택 | 포함 |
| `mise.local.toml` | 모든 환경에 적용되는 개인 override | 제외 |
| `mise.dev.local.toml` | dev에서만 적용되는 개인 dotenv·포트 | 제외 |
| `.miserc.local.toml` | 개인 checkout의 기본 환경 선택 | 제외 |

여기서 **local 실행은 `-E`를 생략한 기본 환경**입니다. `mise.local.toml`은 이름이 local인
환경 전용 파일이 아니라 자동 로드되는 override입니다. `-E local`을 환경 선택 규칙으로
사용하지 않습니다. prod 실행 checkout에는 개인 override를 두지 않습니다.

같은 디렉터리에서는 기본 파일보다 환경 파일, 공유 파일보다 해당 local override가
우선합니다. 하위 디렉터리의 설정은 상위 설정보다 우선하므로 최종 파일 목록은
`mise -E dev config`로 확인합니다. 여러 환경은 `-E dev,ci`처럼 선택할 수 있지만
뒤쪽 환경이 우선하므로 prod와 dev를 함께 선택하지 않습니다.
[공식 환경 선택 규칙](https://mise.jdx.dev/configuration/environments.html)

```sh
mise run dev                   # default APP_ENV=local; local services
mise -E dev run verify         # shared dev overlay + optional dev.local override
mise -E prod run verify        # production configuration; no deployment
```

`MISE_ENV`는 config 발견 전에 필요합니다. `[env] MISE_ENV = "dev"`로 자기 환경을 선택하지
말고 CLI `-E`, 프로세스 환경 변수 또는 `.miserc.local.toml`의 `env = ["dev"]`를 사용합니다.
환경 선택만으로 Spring profile, Vite mode 또는 `NODE_ENV`가 바뀌지는 않습니다. 앱 task에서
명시적으로 매핑합니다. production frontend 빌드에도 devDependencies가 필요할 수 있으므로
설치는 `pnpm install --frozen-lockfile --prod=false`로 유지합니다.

`[vars]`는 경로 등 TOML 템플릿의 내부 값이고 `[env]`는 자식 프로세스에 전달하는 값입니다.
경로는 `dir = "{{ vars.backend_dir }}"`처럼 재사용하고, shell 명령에 넣을 때는
`{{ vars.compose_file | quote }}`처럼 인용합니다. 상위에서 이미 계산한 vars의 문자열은
나중에 다른 vars를 override해도 자동 재계산되지 않으므로 파생 표현식은 사용하는 task에 둡니다.
[공식 vars 문서](https://mise.jdx.dev/configuration/vars.html)

개인 dev 비밀값은 배포된 `mise.dev.local.toml.example`을 검토하여 이름의 `.example`을
제거한 파일과 `.env.dev.local`에 작성합니다. 먼저 `mise.gitignore.example`의 규칙을 기존
`.gitignore`에 병합합니다. 예제 자체에는 비밀값이 없습니다. base와 prod에서는 개발 dotenv를
읽지 않습니다. `.env`는 자동 발견을 가정하지 않고 `env._.file`로 로드할 파일을 지정합니다.

```toml
# mise.prod.toml: require values supplied by the deployment system
[env]
APP_ENV = "prod"
DATABASE_URL = { required = true, redact = true }
```

필수 변수는 값의 존재를 검사하며 유효한 URL·권한·빈 문자열 여부까지 보장하지 않습니다.
앱의 설정 검증도 유지합니다. `redact`는 mise 출력 마스킹이며 자식 앱 로그나 frontend
bundle의 비밀 노출을 막지 않습니다. frontend 공개 변수에는 비밀값을 넣지 않습니다.
[공식 env 문서](https://mise.jdx.dev/environments/)

## 서비스 실행 순서와 준비 상태

백엔드의 `dev`와 모노레포의 `dev:stack`(`dev` alias)은 기본 local 환경만 허용합니다. Compose context는 `default`를 명시하므로
개발 머신의 실제 로컬 context 이름에 맞춰 수정합니다. 기존 Compose 파일·Dockerfile·앱
소스는 bootstrap이 생성하지 않습니다.

- `verify`/`ci`는 이 개발 스택을 자동으로 켜지 않습니다. 통합 테스트에 서비스가 필요하면
  별도 test 환경의 선행 task를 해당 검증 경로에 연결합니다.

모노레포의 프론트 실행 경로는 명시적으로 선택합니다.

```sh
mise run dev:frontend          # frontend only; no Docker or backend startup
mise -E dev run dev:frontend   # frontend only with the dev environment overlay
mise run dev:stack             # infra readiness -> backend readiness -> frontend
mise run dev                   # alias for dev:stack in the monorepo profile
```

이 실행 task들은 profile 이름이 아니라 애플리케이션 레포가 소유합니다. 예를 들어
`typescript`와 `gradle` profile을 함께 쓰는 모노레포는 다음처럼 frontend 전용 task와
전체 stack task를 같은 `mise.toml`에 추가합니다. 실제 서비스명·healthcheck·package
script는 소비 레포의 설정으로 바꿉니다.

```toml
[vars]
frontend_dir = "frontend"
compose_file = "infra/compose.yaml"

[tasks."frontend:install"]
dir = "{{ vars.frontend_dir }}"
run = "pnpm install --frozen-lockfile --prod=false"

[tasks."dev:frontend"]
dir = "{{ vars.frontend_dir }}"
depends = ["frontend:install"]
env = { NODE_ENV = "development" }
run = "pnpm run dev"

[tasks."infra:up"]
run = "docker compose -f {{ vars.compose_file | quote }} up -d --wait --wait-timeout 120 db"

[tasks."backend:up"]
depends = ["infra:up"]
run = "docker compose -f {{ vars.compose_file | quote }} up -d --build --wait --wait-timeout 180 backend"

[tasks."dev:stack"]
dir = "{{ vars.frontend_dir }}"
depends = ["backend:up", "frontend:install"]
env = { NODE_ENV = "development" }
run = "pnpm run dev"
```

`mise run dev:frontend`는 backend와 infra를 시작하지 않고, `mise run dev:stack`은
인프라와 backend의 readiness가 모두 통과한 뒤 frontend를 시작합니다. 두 task를 `dev`라는
이름으로 동시에 정의하지 말고, 기본 진입점은 레포의 운영 방식에 맞춰 하나의 alias로
정합니다. 프론트 단독 레포는 `dev:frontend`만 두고, backend가 다른 레포에 있으면
자동으로 찾아 실행하지 않습니다.

`dev:frontend`는 frontend 설치와 개발 환경 검사만 선행합니다. backend wrapper나 Docker
진단을 요구하지 않습니다. 연결할 API 주소 또는 mock 모드는 프론트의 기존 환경 설정으로
선택합니다. backend를 생략했다고 API 요청을 자동으로 mock하지 않습니다. 예를 들어 Vite는
`VITE_API_BASE_URL` 같은 프로젝트 소유 변수를 `mise.dev.local.toml`에서 주입할 수 있으며,
실제 변수명과 dev-server proxy 설정은 앱의 기존 구현에 맞춥니다. 프론트 단독 레포는
`dev:frontend`만 정의하고, 모노레포는 같은 task와 `dev:stack`을 함께 정의합니다. 별도 backend 레포를 함께
켜야 한다면 해당 레포의 준비 상태 확인 task를 프로젝트에서 명시적으로 연결합니다.

Compose의 `--wait`는 healthcheck가 없는 서비스에는 running 상태만 확인할 수 있습니다.
`db`에는 DB 접속 검사, `backend`에는 실제 readiness endpoint 검사를 **반드시** 정의합니다.
Compose 내부에도 backend → db의 `depends_on.condition: service_healthy`를 선언하면
Compose를 직접 실행할 때도 같은 순서를 유지합니다. 컨테이너 backend의 Spring profile,
DB 호스트, 포트와 frontend API 주소도 Compose 및 앱 설정에서 연결해야 합니다.
[Compose startup order](https://docs.docker.com/compose/how-tos/startup-order/)

DB 또는 API 준비 실패 시 timeout으로 실패하여 frontend 시작을 막습니다. 실패 전에 시작된
컨테이너는 남을 수 있습니다. 프론트 단독 실행은 Ctrl-C로 서버를 종료합니다.
모노레포 frontend는 Ctrl-C로 종료하고 `mise run stack:stop`으로
컨테이너를 중지합니다. backend 단독은 Ctrl-C 후 `mise run infra:stop`을 사용합니다.
이 task들은 volume을 제거하지 않습니다.

일반 task의 `depends = ["a", "b"]`는 목록 순서가 아니라 의존 관계이며 a와 b는 병렬 실행될
수 있습니다. 계속 실행되는 서버를 `depends` 선행 조건으로 지정하면 서버 종료 전에는
다음 task로 넘어가지 않습니다. `wait_for` 역시 healthcheck가 아닙니다. 빌드 등 종료되는
단계는 task DAG로, 상시 프로세스는 Compose 또는 supervisor로 관리합니다.
[공식 task 설정](https://mise.jdx.dev/tasks/task-configuration.html)

### 호스트에서 여러 앱을 실행해야 할 때

컨테이너 backend 대신 IDE 디버깅·hot reload를 위해 여러 호스트 프로세스를 함께 관리해야
한다면 mise daemons를 검토합니다. 이는 `experimental = true`와 별도 Pitchfork 설치가
필요한 선택 기능이며 기본 bootstrap에는 추가하지 않습니다.

다음은 기존 Node 앱의 root 설정에 병합하는 최소 예제입니다. 버전과 실행 명령을 확정하고
Pitchfork를 설치한 프로젝트에서만 사용합니다.

```toml
[settings]
experimental = true

[daemons.api]
run = "exec npm run dev:api"
ready_cmd = "curl --fail --silent --max-time 2 http://127.0.0.1:8080/health >/dev/null"

[daemons.web]
run = "exec npm run dev:web"
depends = ["api"]
ready_port = 5173
```

`mise daemons start web`은 API 준비 후 web을 시작합니다. `mise daemons stop web` 후
`mise daemons stop api`로 중지하고 앱별 상태를 확인합니다.
포트 열림만으로 앱의 정상 응답을 보장할 수 없다면 web에도 `ready_cmd`를 사용합니다.
추가 서비스·재시작·worktree 포트 격리는
[공식 개발 스택 예제](https://mise.jdx.dev/daemons/development-stack.html)와
[daemon 참조](https://mise.jdx.dev/daemons.html)를 기준으로 확장합니다.

## 도구 backend와 mise 자체 설정

- Java·Node 같은 도구는 기존 registry short name을 유지합니다. 배포판·다운로드 출처를
  강제해야 할 때만 backend를 명시하고 `mise tool <name>` 및 lock diff로 확인합니다.
- 공유 version alias는 `[tool_alias.<tool>.versions]`에 둡니다. 개인 global alias를 팀의
  필수 설정으로 삼지 않습니다. alias는 정확한 patch pin이나 lockfile을 대체하지 않습니다.
- `[settings]`에는 mise 동작만 둡니다. 공통 기본은 `lockfile = true`이며 `experimental`,
  자동 설치 hook, 전역 trust 확장은 기본으로 켜지 않습니다. `jobs`는 머신 자원에 따라
  `mise -j 2 run verify`처럼 조절합니다. 병렬화 성능 이점은 측정 전에는 가설입니다.
- Gradle/Maven은 wrapper를 유지하고 mise에 별도 Gradle/Maven 버전을 중복 설치하지 않습니다.
  pnpm 버전은 `package.json#packageManager`와 일치시킵니다.

참고: [Dev Tools](https://mise.jdx.dev/dev-tools/),
[backend architecture](https://mise.jdx.dev/dev-tools/backend_architecture.html),
[tool aliases](https://mise.jdx.dev/dev-tools/aliases.html),
[settings](https://mise.jdx.dev/configuration/settings.html).

## 사용 흐름

`mise trust --show`로 상태를 확인하고 처음 받은 설정을 검토한 뒤, 신뢰할 파일만
`mise trust ./mise.toml`로 지정합니다. 아래 lock 생성 흐름은 최초 도입이나 tool 버전 변경
시에 사용합니다. 이미 lockfile이 준비된 CI에서는 다시 생성하지 않습니다.

```sh
mise trust --show
mise lock
mise install --locked
mise current
mise tasks ls
mise run verify
```

- `mise trust --show`는 상태만 확인하며 설정을 신뢰 처리하지 않습니다.
- `mise install` 후 lockfile과 실제 선택된 버전을 확인합니다. lockfile을 준비한 CI는
  `MISE_LOCKED=1 mise install`로 누락된 resolution이 조용히 추가되지 않게 합니다.
- CI runner의 OS·architecture와 사용할 backend에 필요한 lock entry를 미리 준비합니다.
  locked mode는 offline 설치를 의미하지 않으며 검증 범위는 backend 지원에 따릅니다.
- task 이름만 존재하는지 확인하는 데 그치지 않고 `mise run verify`의 종료 상태를 확인합니다.
- 일부 task만 실행했다면 전체 검증을 통과한 것으로 보고하지 않습니다.

### 환경과 플랫폼별 lock 정책

도구는 가능한 한 base에만 선언하여 환경 간 toolchain 차이를 줄입니다. 환경 파일에서
도구를 추가/변경했다면 `mise.<env>.lock`도 생성하여 커밋하고 해당 환경으로 CI를 실행합니다.
개인 `.local.lock` 파일은 제외합니다. 앱 dependency lockfile은 별도로 유지합니다.

```sh
# Run only when adopting or changing the toolchain; select actual target platforms.
mise lock --platform linux-x64,macos-arm64
mise -E dev lock --platform linux-x64,macos-arm64
# CI: use the same selected environment for installation and verification.
MISE_LOCKED=1 mise -E dev install
MISE_LOCKED=1 mise -E dev run verify
```

환경 overlay에 `[env]`만 있을 때 도구용 lockfile을 빈 파일로 만들어 넣지 않습니다.
버전 범위를 사용하는 도구의 의도적인 갱신에는 `mise lock --bump <tool>`을 사용하고,
정확히 고정한 버전을 바꾸려면 먼저 해당 pin을 변경합니다. 일반 `mise lock`은 기존의
일치하는 resolution을 유지할 수 있으므로 최신 버전 갱신과 동일시하지 않습니다.
`mise.lock`을 손으로 작성하거나 다른 레포에서 복사하지 않습니다.

### 진단과 검증

`mise doctor`는 mise 설치 자체를, `mise doctor project`는 `[doctor.checks]`에 선언한 프로젝트
전제 조건을 검사합니다. 후자는 디렉터리 진입만으로 실행되지 않습니다. 앱 profile은 `doctor`
task를 제공하며 서비스 실행의 선행 조건으로 연결합니다. 버전 선택은 `mise current`, task
참조는 `mise tasks validate`, 의존 관계는 `mise tasks deps verify`로 확인합니다.
[공식 project diagnostics](https://mise.jdx.dev/configuration/project-diagnostics.html)

- 제약: 템플릿의 `REPLACE_ME`, 앱 경로·script와 healthcheck를 소비 저장소에서 확정해야 합니다.
- 위험: local override의 운영값 덮어쓰기, healthcheck 누락, 공유 출력 디렉터리에 대한 병렬
  build는 잘못된 환경 연결이나 검증 경쟁을 만들 수 있습니다.
- 예외: Windows native shell은 POSIX task를 그대로 실행할 수 없습니다. WSL을 사용하거나
  `run_windows`와 경로·wrapper 명령을 별도 검증합니다.

## 개발 머신 설정과 프로젝트 bootstrap 구분

이 레포의 `scripts/bootstrap.sh`는 설정 템플릿을 최초 복사하는 도구입니다. mise의
`mise bootstrap`은 머신 package·서비스·dotfile 등을 관리하는 별도 기능입니다.
개인 `~/.zshrc`와 `~/.gitconfig` 관리는 프로젝트 profile에 넣지 않고 개인 global 설정에서
명시적으로 채택합니다. dotfile history/sync 대상에는 secret 파일을 포함하지 않습니다.
이 표준을 적용하는 것만으로 shell 파일, 서비스 또는 OS 설정을 변경하지 않습니다.
[공식 dotfiles](https://mise.jdx.dev/dotfiles.html),
[공식 machine setup](https://mise.jdx.dev/bootstrap/setup.html)

## 변경 기준

- 런타임 major 버전 변경은 compiler, plugin, dependency peer range와 CI runner 호환성을 함께 확인합니다.
- lockfile을 사용하는 프로젝트는 런타임 변경과 lockfile 재생성을 같은 변경에서 검증합니다.
- 여러 저장소에 같은 버전을 적용하더라도 각 저장소의 실제 빌드와 테스트 결과를 별도로 확인합니다.

참고: [mise configuration](https://mise.jdx.dev/configuration.html),
[mise.lock](https://mise.jdx.dev/dev-tools/mise-lock.html),
[mise trust](https://mise.jdx.dev/cli/trust.html)
