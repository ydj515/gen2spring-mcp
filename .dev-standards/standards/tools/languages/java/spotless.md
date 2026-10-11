# Spotless Guidelines

이 문서는 Java와 Kotlin/JVM source의 formatting을 Spotless로 일관되게 적용하기 위한
기준입니다. Spotless는 formatting을 담당하며 architecture, bug pattern과 dependency
검증은 각 목적에 맞는 별도 도구에 맡깁니다. Spotless를 도입했다고 코드 품질이나
아키텍처 검증을 통과한 것으로 간주하지 않습니다.

## 선택과 설정

- Gradle plugin `com.diffplug.spotless` 버전을 version catalog와 lock 정책으로 고정합니다.
- Java는 `googleJavaFormat`, `palantirJavaFormat` 또는 저장소가 선택한 formatter 하나를
  고정하고, Kotlin은 `ktfmt` 또는 `ktlint` 중 Spotless가 호출할 formatter의 소유권을
  명확히 합니다. 같은 파일을 두 formatter가 수정하지 않게 합니다.
- `.editorconfig`와 formatter 설정의 우선순위를 기록하고 generated source는 실제 경로만
  제외합니다. `build/`, 외부 소유 코드와 캐시는 포맷 대상에 포함하지 않습니다.
- 기존 코드 전체를 한 번에 재포맷하지 않으려면 `ratchetFrom` 기준을 지정하고 별도 포맷
  커밋과 기능 변경을 분리합니다.

## Gradle 연결과 버전 고정

- 시작점은 `templates/gradle/spotless/build.gradle.kts.example`입니다. 기존 Java/Kotlin
  plugin이 적용된 build에 필요한 블록만 병합하며 bootstrap은 build script를 대체하지 않습니다.
- plugin과 formatter engine은 별도 버전입니다. version catalog의 `spotless`,
  `google-java-format`, `ktlint` 자리표시자를 각각 검증한 버전으로 바꿉니다.
- Gradle daemon을 실행하는 JDK와 formatter가 요구하는 JDK를 확인합니다. 애플리케이션의
  compile toolchain을 바꾸는 것만으로 formatter 실행 환경이 바뀐다고 가정하지 않습니다.
- 예시는 Java에 `googleJavaFormat(libs.versions.google.java.format.get())`, Kotlin에
  `ktlint(libs.versions.ktlint.get())`를 사용합니다. 버전 없는 호출로 기본 engine에
  의존하지 않습니다. Java만 있으면 Kotlin 블록을 제거합니다.
- `target`은 main/test뿐 아니라 실제 custom source set도 포함하도록 조정합니다. 생성 파일
  제외 후에도 애플리케이션 source가 검사되는지 대표 파일로 확인합니다.

## Kotlin과 기존 포맷터의 역할

- Spotless가 ktlint engine을 실행하면 같은 source의 format 진입점은 Spotless로 통일합니다.
  독립 ktlint Gradle plugin을 함께 유지해야 한다면 검사 범위를 분리합니다.
- `.editorconfig`는 engine이 지원하는 항목만 적용됩니다. ktlint의 code style 설정과
  google-java-format의 고정 스타일을 동일한 설정으로 제어할 수 있다고 가정하지 않습니다.
- Checkstyle이나 Detekt의 format 규칙이 formatter 출력과 충돌하면 실제 출력 기준으로
  중복 규칙을 조정합니다. 분석 규칙 전체를 꺼서 format 충돌을 해결하지 않습니다.
- Kotlin DSL을 검사하려면 `kotlinGradle` 블록에서 `*.gradle.kts` 범위와 ktlint 버전을
  별도로 지정합니다. `kotlin` source 대상에 포함된다고 가정하지 않습니다.

## Multi-module과 기존 코드 도입

- 공통 설정은 convention plugin으로 관리하고 해당 언어 모듈에 적용합니다. 루트의
  `src/**` 검사만으로 하위 모듈까지 검사했다고 판단하지 않습니다.
- CI 진입점에서 각 모듈의 `spotlessCheck`가 실행되는지 task graph를 확인합니다.
  루트와 하위 모듈이 같은 파일을 중복 소유하지 않도록 build script 대상도 구분합니다.
- ratchet을 사용하면 기준 ref를 CI에서도 fetch해야 합니다. shallow checkout에 기준이
  없을 때 검사를 생략하지 말고 checkout 설정을 수정합니다.
- ratchet은 점진적 도입 범위입니다. 전체 코드 검증이 필요한 릴리스 검사와 구분하고,
  기존 부채 해소 후 제한을 없앨 시점을 기록합니다.

## 예외와 검증

- 포맷 제외는 generated/외부 소유 경로나 필요한 최소 구간으로 제한하고 이유를 남깁니다.
- 최초 연결 시 대상 파일에 의도적인 format 위반을 넣어 check 실패를 확인한 뒤 복원합니다.
  대상 파일이 없거나 task가 건너뛰어진 결과를 검증 완료로 기록하지 않습니다.
- apply 후 diff를 검토하고 재실행 시 추가 변경이 없는지 확인합니다. 반복 실행마다 결과가
  바뀌면 formatter나 IDE 저장 동작의 충돌을 먼저 해결합니다.

CI에서는 파일을 수정하지 않는 `spotlessCheck`만 실행하고, 개발자가 명시적으로
`spotlessApply`를 실행합니다.

```sh
./gradlew spotlessCheck
./gradlew spotlessApply
```

`spotlessCheck`를 root `check` 또는 프로젝트의 표준 검증 task에 연결하고, 적용한 formatter와
대상 source set을 문서에 기록합니다.

참고: [Spotless Gradle plugin](https://github.com/diffplug/spotless/tree/main/plugin-gradle)
