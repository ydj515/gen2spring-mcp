# JaCoCo Guidelines

이 문서는 Java와 Kotlin/JVM 프로젝트에서 JaCoCo coverage report와 최소 기준을 재현 가능하게
운영하기 위한 기준입니다.

## 선택과 버전

- Gradle core `jacoco` plugin을 적용하고 JaCoCo tool version을 version catalog에서 exact
  version으로 고정합니다.
- Kotlin 중심 build에서 Kover를 선택했다면 같은 module에 JaCoCo plugin을 중복 적용하지
  않습니다.
- Maven은 `jacoco-maven-plugin` 버전과 `prepare-agent`, `report`, `check` execution을 POM에
  고정합니다.

## Report와 검증

- Gradle Kotlin DSL 시작점은 `templates/gradle/jacoco/build.gradle.kts.example`입니다.
- `jacocoTestReport`는 기본적으로 `test`에 의존하지 않으므로 test 실행 관계를 명시합니다.
- `jacocoTestCoverageVerification`도 기본 `check` dependency가 아니므로 최소 기준을 운영할
  때는 `check` 또는 프로젝트 `verify`에 명시적으로 연결합니다.
- HTML과 XML report를 생성하고 line, branch 기준을 모두 검증합니다.
- generated source와 외부 소유 code만 제외합니다. 제외 규칙과 threshold를 낮추는 변경은
  test 변경과 같은 수준으로 검토합니다.

## 추가 Test suite와 Multi-module

- integration test 같은 별도 `Test` task는 각 task의 `JacocoTaskExtension.destinationFile`을
  report와 verification의 execution data로 연결합니다.
- stale하거나 비어 있는 execution data로 성공하지 않도록 선택한 모든 suite가 실행됐고
  결과 파일이 존재하는지 확인합니다.
- multi-module build는 Gradle JaCoCo report aggregation plugin 또는 명시적인 root aggregate
  task 중 하나를 표준 진입점으로 정합니다.

## 검증

```sh
./gradlew test jacocoTestReport jacocoTestCoverageVerification
./gradlew check
```

```sh
./mvnw verify
```

참고: [Gradle JaCoCo plugin](https://docs.gradle.org/current/userguide/jacoco_plugin.html),
[JaCoCo Maven plugin](https://www.jacoco.org/jacoco/trunk/doc/maven.html)
