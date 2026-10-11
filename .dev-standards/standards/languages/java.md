# Java Guidelines & Standards

Java 코드는 명시적인 타입과 객체의 불변식, 예측 가능한 수명 주기를 활용해야 합니다.
최신 문법을 장식처럼 쓰기보다 상태 공간을 타입으로 좁히고, 공개 계약을 컴파일러와
정적 분석기가 검증할 수 있게 만드는 데 사용합니다.

## 1. Java다운 기본 원칙

- build 실행 JDK, compiler toolchain, `--release`와 배포 JDK를 구분하고 지원 대상에 맞는
  공식 Java 문법·API를 사용합니다. 아래 pattern `switch` 예시는 Java 21 이상의 안정
  기능을 전제로 합니다. 이전 버전에서는 지원되는 분기 방식으로 작성합니다.
- preview 기능은 명시적으로 채택한 경우에만 compile/test/runtime flag를 함께 관리합니다.
  새 bytecode나 문법을 도입할 때 formatter, 정적 분석과 coverage 도구의 지원도 확인합니다.

- package와 타입 경계를 도메인 책임에 맞추고 `Utils`, `Manager`, `Helper`에 서로 다른
  책임을 모으지 않습니다.
- 생성자가 객체의 필수 불변식을 완성하게 하고, 선택적 변경이 필요하지 않으면
  setter 기반의 반쯤 초기화된 객체를 만들지 않습니다.
- 상속은 명확한 대체 가능성이 있을 때만 사용하고, 코드 재사용은 composition을
  우선합니다.
- method는 한 추상화 수준에서 읽히게 구성하고 boolean 인자 여러 개보다 의미 있는
  타입이나 별도 method를 사용합니다.
- framework annotation과 persistence 규칙은 해당 framework 문서에 두고 언어 모델과
  결합하지 않습니다.

## 2. 타입으로 상태를 모델링한다

- 값 전달 객체에는 `record`를 우선하되, identity와 lifecycle이 핵심이거나 framework가
  proxy와 no-arg constructor를 요구하는 객체에는 기계적으로 적용하지 않습니다.
- 닫힌 상태 계층은 `sealed interface`와 record/class 구현으로 표현하고 exhaustive
  `switch` expression으로 처리합니다.
- 고정된 상수와 그 동작은 문자열 상수 대신 `enum`으로 모델링합니다. 외부 wire value는
  `name()`이나 `ordinal()`에 우연히 의존하지 않고 명시적으로 변환합니다.
- 단위가 있는 숫자와 식별자를 원시 타입으로 계속 전달하지 말고 작은 value type으로
  의미와 검증을 묶습니다.

```java
sealed interface PaymentResult permits Approved, Declined {}

record Approved(String authorizationId) implements PaymentResult {}
record Declined(String reason) implements PaymentResult {}

String message(PaymentResult result) {
    return switch (result) {
        case Approved approved -> "approved: " + approved.authorizationId();
        case Declined declined -> "declined: " + declined.reason();
    };
}
```

## 3. Null, Optional과 collection

- 공개 경계의 null 허용 여부를 annotation, Javadoc과 runtime 검증으로 일치시킵니다.
- `Optional`은 “결과 없음”이 정상적인 method 반환에 사용합니다. field, method parameter,
  collection element, serialization model에 습관적으로 사용하지 않습니다.
- 값을 계산할 수 없음이 정상적인 부재가 아니라 validation, I/O 또는 정책 실패라면
  `Optional.empty()`로 숨기지 않고 의미 있는 예외나 결과 타입으로 표현합니다.
- 기본값 생성 비용이나 side effect가 있으면 `orElse` 대신 `orElseGet`을 사용합니다.
- collection 반환은 특별한 계약이 없다면 `null` 대신 빈 collection을 사용합니다.
- 내부 mutable collection을 노출하지 않고 `List.copyOf` 또는 defensive copy로 소유권을
  분리합니다.
- `Map.get` 결과의 `null`이 key 부재인지 저장된 null인지 구분해야 하면 `containsKey`나
  null을 허용하지 않는 collection 계약을 사용합니다. 단순 조회를 예외 흐름으로 바꾸지
  않습니다.

## 4. Stream과 제어 흐름

- stream은 side effect 없는 filter/map/reduce 변환에 사용합니다. 상태 변경, 복잡한 분기,
  조기 종료나 checked exception 처리가 핵심이면 명시적인 loop가 더 관용적입니다.
- 한 stream pipeline에서 지나치게 많은 domain 단계를 숨기지 말고 이름 있는 method로
  분리합니다.
- `parallelStream()`은 측정과 thread-safety 검증 없이 사용하지 않습니다. 공용
  ForkJoinPool과 blocking I/O를 섞지 않습니다.
- 지역 변수 타입이 우변에서 분명할 때만 `var`를 사용하고 API 의미를 숨기는 경우에는
  명시적 타입을 유지합니다.
- builder는 선택 값이 많아 constructor 의미가 흐려지는 객체에 사용합니다. 필수 값 몇
  개뿐인 value는 constructor/record, 이름 있는 생성 경로가 필요하면 static factory를
  우선합니다.

## 5. 예외와 자원 수명 주기

- 도메인 실패와 인프라 실패를 의미 있는 예외 타입으로 구분하고 `throws Exception`이나
  광범위한 `catch (Exception)`으로 계약을 흐리지 않습니다.
- checked exception은 호출자가 복구 전략을 가질 수 있는 공개 계약에만 남깁니다. 구현
  세부의 checked exception을 의미 없이 여러 계층으로 전달하지 말고 경계에서 원인을
  보존한 예외로 변환합니다.
- 예외를 변환할 때 원인을 constructor에 전달합니다. 로그를 남기고 같은 예외를 다시
  던지는 중복 처리를 피합니다.
- `InterruptedException`을 잡으면 중단을 전파하거나 `Thread.currentThread().interrupt()`로
  상태를 복구합니다.
- `AutoCloseable`은 try-with-resources로 소유권을 표현하고 close 실패 처리 정책을
  정합니다.

```java
try (var input = Files.newInputStream(path)) {
    return parser.parse(input);
} catch (IOException cause) {
    throw new OrderImportException("failed to import " + path, cause);
}
```

## 6. 동시성과 공유 상태

- 가능한 경우 immutable object와 thread confinement로 공유 mutable state를 제거합니다.
- executor를 만들면 shutdown 책임과 application lifecycle 연결을 함께 구현합니다.
- virtual thread, platform thread, reactive pipeline을 한 호출 경로에서 이유 없이
  혼합하지 않습니다. pinned thread와 blocking 지점은 사용하는 JDK와 framework에서
  측정합니다.
- 동시 collection을 사용해도 여러 연산을 묶은 불변식이 자동으로 원자적이 되지는
  않으므로 필요한 경계를 lock 또는 atomic operation으로 표현합니다.

## 7. Javadoc과 공개 API

- 공개 API는 입력 범위, 반환 의미, side effect, thread-safety와 실패 조건을 설명합니다.
- `@param`, `@return`, `@throws`에서 타입 이름이나 method 이름을 반복하지 않습니다.
- 구현 세부보다 호출자가 의존할 안정적인 계약을 기록합니다.

```java
/**
 * Loads an order visible to the current tenant.
 *
 * @param orderId positive order identifier
 * @return the matching order
 * @throws OrderNotFoundException if the order is absent or not visible
 */
Order loadOrder(long orderId) {
    // 구현 내용
}
```

## 8. 테스트와 품질 게이트

- value type, sealed 분기, 예외 원인과 경계값을 단위 테스트합니다.
- 시간, 난수와 외부 I/O는 명시적 collaborator로 전달하여 재현 가능하게 만듭니다.
- Checkstyle은 형식, PMD는 source pattern, SpotBugs는 bytecode defect를 담당하게 하고
  동일 문제를 여러 도구에 중복 설정하지 않습니다.
- ArchUnit은 package와 layer 의존 방향을 실행 가능한 test로 고정합니다.
- coverage는 JaCoCo를 기본으로 사용하고 Kotlin 중심 build에서 Kover를 채택했다면 같은
  module에 두 coverage plugin을 중복 적용하지 않습니다.

```sh
./gradlew test check
./mvnw verify
```

도구 시작점은 `tools/languages/java/`와 `templates/gradle/`에 있습니다.

## 9. 다른 언어 습관을 옮기지 않는다

- Kotlin의 nullable chain을 `Optional` chain으로 기계적으로 번역하지 않습니다.
- Python/JavaScript식 dictionary를 핵심 domain model로 사용하지 않습니다.
- 모든 흐름을 stream 한 줄로 압축하거나 모든 객체를 record로 바꾸는 것을 modern Java로
  오해하지 않습니다.

참고: [Records](https://dev.java/learn/using-record-to-model-immutable-data/),
[Pattern Matching](https://dev.java/learn/pattern-matching/),
[Optional](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Optional.html)

버전별 문법 참고: [JEP 441: Pattern Matching for switch](https://openjdk.org/jeps/441)
