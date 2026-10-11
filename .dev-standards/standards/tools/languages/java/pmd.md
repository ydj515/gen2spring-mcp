# PMD Guidelines

이 문서는 Java source의 code smell과 오류 가능 패턴을 PMD로 검사하기 위한 기준입니다.
PMD는 source 분석을 담당하며 형식만 다루는 규칙은 Checkstyle 또는 formatter에 맡기고,
bytecode에서만 확인할 수 있는 문제는 SpotBugs에 맡깁니다.

## 규칙 선택

- PMD 버전과 ruleset을 저장소에서 고정하고 major 변경 시 제거·이름 변경된 rule을
  확인합니다.
- 공통 경로는 `config/pmd/ruleset.xml`로 두고 Gradle과 Maven에서 같은 파일을
  참조합니다.
- `errorprone`과 `bestpractices` category에서 프로젝트에 의미 있는 규칙부터 채택합니다.
- priority가 낮다는 이유만으로 모든 위반을 warning 처리하지 않고 실패 기준을
  문서화합니다.
- type resolution이 필요한 규칙에는 compile 결과와 dependency classpath가 제공되는지
  확인합니다.
- 시작점은 `templates/pmd/ruleset.xml`을 사용하되 false positive는 재현 사례를 확인한
  뒤 최소 범위로 제외합니다.

## Gradle 연결

- Gradle core `pmd` plugin을 적용하고 `toolVersion`과 ruleset 경로를 고정합니다.
- Kotlin DSL 시작점은 `templates/gradle/pmd/build.gradle.kts.example`을 사용합니다.
- `pmdMain`, `pmdTest` 분석 범위와 생성 코드 제외를 확인합니다.
- Java plugin과 함께 생성되는 PMD task가 `check`에 포함되는지 검증합니다.
- parallel analysis thread 수는 Gradle 병렬 project 수와 곱해질 수 있으므로 측정 없이
  과도하게 늘리지 않습니다.

## Maven 연결

- `maven-pmd-plugin` 버전과 ruleset 경로를 POM에서 고정합니다.
- report 생성만 하는 `pmd` goal이 아니라 violation을 실패시키는 `check` goal을
  `verify` lifecycle에 연결합니다.
- `failOnViolation`, `failurePriority`, 허용 위반 수로 gate를 무력화하지 않습니다.
- multi-module aggregate 분석은 type resolution과 lifecycle 중복 실행 여부를 별도로
  검증합니다.

## 검증

```sh
./gradlew pmdMain pmdTest
./gradlew check
```

```sh
./mvnw pmd:check
./mvnw verify
```

- rule set 변경 전후 violation을 비교하고 새 규칙이 실제 source에 적용되는지 확인합니다.
- suppression에는 rule id, 대상과 코드만으로 알 수 없는 이유를 기록합니다.
