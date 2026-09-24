# Managed Runtime과 Catalog

## 단일 immutable Catalog 실행

Managed Runtime은 검증된 Tool Catalog 하나를 활성화하여 `/mcp/{runtimeId}`에서 실행한다.
다운로드 ZIP의 독립 서버와 수명·인증·저장소가 다르며 여러 Catalog를 합치는 공개 Gateway 기능은 없다.
Catalog 조회는 owner에게 Tool schema, HTTP binding과 실행·credential 요구사항을 제공한다.

[ManagedRuntimeService](../modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/service/ManagedRuntimeService.java)는
활성화·인증·해제를 담당한다. 활성화/grant token은 발급 시 한 번 전달하고 저장소에는 HMAC digest만 남긴다.
만료와 revoke는 인증을 거부한다. API 목록과 요청 예시는 [사용자 가이드](user-guide.md#managed-runtime)에 있다.

## Credential과 grant

Owner는 OPAQUE·Bearer·Basic credential을 생성·회전·폐기할 수 있다. Catalog의 exact slot에 credential을
연결하고 호출 시 해당 Tool에 필요한 slot만 복호화한다. Secret은 envelope encryption으로 저장하며
조회·audit에 plaintext를 반환하지 않는다. OAuth2 credential acquisition은 지원 범위에 포함하지 않는다.

Grant는 허용 Tool 집합과 분당 호출 제한을 가진다. `tools/list`와 `tools/call` 모두 같은 visibility를 적용하고,
사용자 argument가 credential header/query target을 덮어쓰지 못하게 한다.
[RuntimeGrantService](../modules/application/src/main/java/io/gen2spring/mcp/application/managed/policy/RuntimeGrantService.java),
[ManagedCredentialService](../modules/application/src/main/java/io/gen2spring/mcp/application/managed/credential/service/ManagedCredentialService.java)가
제어 API의 application 경계다.

## 실행·rate·audit

MCP transport는 stateless이며 cookie, `Mcp-Session-Id`와 sticky routing을 요구하지 않는다.
프로세스 내부 SDK handle은 runtime/Catalog/policy 조합의 재구성 가능한 cache다. PostgreSQL이 replica 간
rate 획득과 audit 상태의 정합성을 맡는다. Audit 시작과 rate 허용 후 필요한 credential을 해제한다.
Audit에는 안전한 실행 결과를 남기고 원문 Tool 인수·provider body·token·secret을 담지 않는다.

Provider HTTP는 mTLS `provider-egress`에 위임한다. Public HTTP/HTTPS 80/443, DNS resolve-and-connect,
redirect 거부, private/reserved/mixed DNS answer 거부, hop-by-hop header와 body 제한을 같은 실행 경계에서 적용한다.
Expected provider 실패는 `isError=true`로 변환한다. Typed output이 있는 성공은 text와 같은 의미의
`structuredContent`를 제공한다. Handle 용량 부족은 활성 handle을 무리하게 제거하지 않고 503으로 거부한다.

## Catalog revision과 diff

새 generation의 명시적 `predecessorCatalogId`가 같은 owner의 현재 family head여야 다음 revision을 게시한다.
Catalog는 immutable이고 family는 linear history를 유지한다. 동시 후속 게시 충돌을 숨기거나 자동 분기하지 않는다.

[CatalogDiffService](../modules/application/src/main/java/io/gen2spring/mcp/application/hosted/catalog/CatalogDiffService.java)는
Tool 이름 기준의 deterministic diff를 만든다. Tool 추가, description 변경, optional output property 추가만
compatible로 취급하는 보수적 정책이다. Input/binding/credential/정책 변경을 임의로 compatible로 넓히지 않는다.

## Migration과 rollback

[ManagedRuntimeMigrationService](../modules/application/src/main/java/io/gen2spring/mcp/application/managed/runtime/service/ManagedRuntimeMigrationService.java)는
현재 Catalog ID와 target checksum을 확인하고 저장소의 CAS transaction으로 전환한다.
Breaking target 또는 target에 없는 Tool을 사용하는 active grant가 있으면 거부한다.

전환은 runtime ID, bearer, credential binding/version, provider override, expiry, grant, rate와 audit을 보존하고
append-only history를 남긴다. Target에 추가된 Tool을 기존 scoped grant에 자동 허용하지 않는다.
Rollback은 가장 최근의 아직 되돌리지 않은 forward migration을 복원한다. Source에 없는 Tool을 허용하는
active grant가 있으면 거부하므로 해당 grant를 revoke한 뒤 현재 Catalog 조건으로 재시도해야 한다.
Rollback은 token 재발급이나 공개 응답 재노출 동작이 아니다.

## 회귀 기준

서로 다른 replica에서 같은 grant의 rate 제한, revoke/credential 회전, migration 이후 목록·호출을 확인한다.
CAS 실패와 active grant 충돌은 기존 binding·history를 부분 변경하지 않아야 한다.
실제 저장소와 transport 검증은 [개발 및 검증](development-guide.md)의 hosted acceptance 범위를 따른다.
