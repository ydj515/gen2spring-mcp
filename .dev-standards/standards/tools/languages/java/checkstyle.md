# Checkstyle Guidelines

이 문서는 Java source의 형식과 팀 규약을 Checkstyle로 재현 가능하게 검사하기 위한
기준입니다. Checkstyle은 style과 구조 규칙을 담당하며 runtime bug나 dependency
취약점 탐지 도구로 사용하지 않습니다.

## 설정 관리

- 규칙 파일을 저장소에 커밋하고 build 설정에서 경로를 명시합니다.
- Gradle의 기본 경로를 따르면 `config/checkstyle/checkstyle.xml`을 사용합니다.
- Checkstyle tool 버전을 고정합니다. Gradle core plugin 버전은 Wrapper의 Gradle 버전을
  따르며, Maven plugin 버전은 POM에서 별도로 고정합니다.
- formatter가 자동 수정하는 영역과 Checkstyle이 실패시키는 영역의 소유권을 나눠
  같은 형식을 서로 다른 규칙으로 중복 강제하지 않습니다.
- 기본 설정은 `templates/checkstyle/checkstyle.xml`에서 시작하고 프로젝트 규약에
  맞는 rule만 명시적으로 추가합니다.

## Gradle 연결

- Gradle core `checkstyle` plugin을 적용하고 `toolVersion`을 고정합니다.
- Kotlin DSL 시작점은 `templates/gradle/checkstyle/build.gradle.kts.example`을 사용합니다.
- `checkstyleMain`과 `checkstyleTest`의 실제 분석 범위를 확인합니다.
- Java plugin과 함께 사용할 때 생성되는 Checkstyle task가 `check`에 연결되어 있는지
  task graph로 검증합니다.
- Checkstyle 실행 JDK 요구사항이 compile target과 다르면 Java toolchain launcher를
  명시합니다.

## Maven 연결

- `maven-checkstyle-plugin` 버전과 `configLocation`을 POM에서 고정합니다.
- site report만 생성하는 goal이 아니라 violation을 build 실패로 만드는 `check` goal을
  build lifecycle에 연결합니다.
- compile 오류보다 style 오류가 먼저 노출되어야 하는 정책이면 `validate`, compile
  이후 검사하려면 `verify` phase를 사용하고 팀 명령과 일치시킵니다.
- test source도 검사할지 명시하고 CI에서 violation 실패를 비활성화하지 않습니다.

## 예외와 검증

- suppression은 file 전체보다 rule과 source 범위를 좁게 지정합니다.
- 생성 코드 또는 외부 소유 코드만 경로 제외 대상으로 사용합니다.
- rule set을 변경하면 기존 위반 수와 formatter 중복을 확인하고 결과를 검토합니다.

```sh
./gradlew checkstyleMain checkstyleTest
./gradlew check
```

```sh
./mvnw checkstyle:check
./mvnw verify
```

참고: [Gradle Checkstyle plugin](https://docs.gradle.org/current/userguide/checkstyle_plugin.html)
