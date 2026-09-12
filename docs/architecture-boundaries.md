# 아키텍처 경계

## 중심 모델과 외부 구현

Domain은 canonical OpenAPI, Tool IR, 호환 profile과 순수 정책을 소유한다.
Application은 생성 단계, Tool 정책, hosted 작업과 managed 실행을 조율하며 외부 기능은 포트로 호출한다.
Swagger Parser, Spring AI, 파일시스템, PostgreSQL과 컨테이너 구현은 adapter에서 연결한다.

```text
domain <- application <- adapters
              ^             ^
              +-- bootstrap-+
                      ^
                  CLI / Web
```

이 그림은 생성기 조립의 기본 방향이다. Worker·Runtime·egress 앱은 자신의 config에서 필요한 adapter를
직접 조립한다. bootstrap을 요청마다 거치는 서비스 계층으로 사용하지 않는다.
현재 [application 빌드](../modules/application/build.gradle.kts)는 domain 외에 Jackson Databind에도
의존한다. 따라서 application 전체를 외부 라이브러리가 없는 계층으로 설명하지 않는다.

## 생성 대상 분리

Tool IR은 Spring AI 버전이나 Swagger Parser 타입을 노출하지 않는다. profile registry가 생성 대상의
Java·Boot·Spring AI·build tool·web stack을 결정하고 emitter registry가 구현을 선택한다.
공유 scaffold와 schema 로직을 재사용하되 Jackson 2/3 및 MVC/Async 등록 차이는 소유 emitter에 둔다.
저장소 내부 패키지를 이동해도 생성 소스의 패키지·경로·서비스 등록 리소스는 별도 출력 계약이다.

## Web와 실행 경계

Local Web은 loopback 단일 사용자 실행과 메모리 작업 관리에 맞춘다. Hosted Web은 OIDC 소유권 확인과
내구성 작업 접수에 집중하며 Docker socket을 갖지 않는다. Worker가 격리 프로세스를 실행한다.
Managed Runtime은 플랫폼 저장소에 접근하지만 provider HTTP는 mTLS egress 서비스에 위임한다.
URL import의 fetch gateway와 실행 중 provider egress는 서로 다른 책임이다.

## Spring 조립과 오류

- 앱 진입점과 component scan 기준을 함께 유지한다. 보안 필터는 servlet 자동 등록을 끄고 보안 체인에서 한 번 실행한다.
- `RestClient.Builder`·`WebClient.Builder`는 주입된 builder를 복제하여 provider 전용 제한을 적용한다.
- 전용 JSON mapper의 null·응답 계약과 telemetry의 민감 정보 제거를 호스트 기본 설정으로 덮어쓰지 않는다.
- Domain/애플리케이션 오류 코드는 경계에서 안전한 CLI 또는 HTTP 응답으로 변환한다. 원본 예외·프로세스 출력·경로를 공개 응답으로 전파하지 않는다.
- 외부 provider의 예상된 실행 실패는 MCP `isError=true` 결과다. 이를 생성기 자체 실패나 모든 JSON-RPC 오류와 동일하게 처리하지 않는다.

[Spring 조립 경계](user-guide.md#spring-조립-경계)와 [생성 파이프라인](generation-pipeline.md)에 구체적인 계약을 정리한다.

## 변경 시 확인

패키지 이동은 모든 source set, Spring 설정, Gradle main class, 서비스 로더 리소스를 함께 확인한다.
Java import 검사는 [루트 빌드](../build.gradle.kts)의 `verifyJavaImportStyle`이 담당하고 test task에도 연결된다.
프로토콜 문자열, 이름 충돌, 검증 대상 문자열은 단순 치환하지 않는다.
