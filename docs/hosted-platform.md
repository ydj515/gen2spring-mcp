# Hosted 작업과 복구

## 배포와 소유권

Hosted는 외부 OIDC, PostgreSQL, private object storage, Worker와 rootless sandbox를 사용하는
Linux 단일-host 배포다. Web은 작업을 접수하고 Worker가 실행하며 공개 진입점은 TLS proxy다.
Local의 loopback·메모리 작업 관리를 hosted 장애 시 fallback으로 사용하지 않는다.

OIDC `(issuer, subject)`를 내부 account UUID로 매핑한다. email·표시 이름을 권한 키로 사용하지 않는다.
Specification, job, event, artifact, Catalog와 runtime 조회에는 owner 조건이 적용된다.
다른 owner의 식별자는 리소스 존재 여부를 알려주지 않는 응답으로 거부한다.
Web의 session·CSRF와 Managed Runtime의 bearer 인증은 별도 경계다.

## 접수와 상태 전이

[HostedJobService](../modules/application/src/main/java/io/gen2spring/mcp/application/hosted/job/HostedJobService.java)가
작업 접수를 담당한다. 생성 요청은 owner 범위의 idempotency key와 request hash를 사용한다.
같은 key와 payload는 기존 작업을 반환하고 다른 payload는 충돌로 거부한다.

```text
QUEUED -> RUNNING -> SUCCEEDED / FAILED / CANCELLED
   |          |
   +-> CANCELLED
              +-> QUEUED  (lease 회수 후 재시도 가능할 때)
```

[JobTransitionPolicy](../modules/domain/src/main/java/io/gen2spring/mcp/domain/platform/job/JobTransitionPolicy.java)는
terminal 상태를 다시 열지 않는다. `cancel_requested`는 terminal 상태와 별개인 실행 제어 신호다.

## Lease와 fencing

PostgreSQL queue는 `FOR UPDATE SKIP LOCKED`로 작업을 선점하고 lease·attempt·단조 증가 fencing token을 관리한다.
Heartbeat, event, 완료·artifact 게시에는 현재 claim 소유권이 필요하다. 만료된 Worker의 늦은 성공이
새 시도의 상태를 덮어쓰지 못해야 한다. 재시도 가능 작업은 다시 QUEUED로, 소진된 작업은 안전한 실패로 처리한다.

[HostedWorker](../modules/application/src/main/java/io/gen2spring/mcp/application/hosted/worker/HostedWorker.java)와
[WorkerLeaseService](../modules/application/src/main/java/io/gen2spring/mcp/application/hosted/job/WorkerLeaseService.java)가
실행 및 복구 흐름의 시작점이다. 컨테이너 정리와 DB 완료는 같은 원자적 동작이 아니므로 claim fencing과
잔존 실행 정리를 함께 검증한다. 단순 재기동 성공만으로 복구를 증명하지 않는다.

## URL import와 sandbox

Web은 URL을 직접 fetch하지 않고 import 작업을 만든다. import runner는 fetch gateway를 통해 제한된
HTTP/HTTPS 요청을 수행한다. Gateway에서 DNS 검증과 실제 연결을 한 경계에 두고 허용되지 않은 주소,
redirect 대상과 응답 크기를 검사한다. 문서 내부 remote `$ref`는 URL import 지원과 별개로 거부한다.
URL query를 포함한 민감 target과 원본 응답을 event·오류·telemetry에 노출하지 않는다.

Generation sandbox는 non-root, read-only rootfs, network-none, resource limit을 적용한다.
Web에는 Docker socket이나 Worker workspace를 공유하지 않는다. 실행에 필요한 의존성 준비와 rootless
환경 구성은 [배포 가이드](../deploy/hosted/README.md)를 따른다. Local/CLI의 timeout·bounded process output은
이 OCI 격리와 같은 보장이 아니다.

## Artifact와 Catalog 게시

Private storage의 object와 PostgreSQL metadata는 서로 다른 저장소다. 성공한 generation은 검증된 결과를
준비한 뒤 현재 fencing token으로 DB 완료 transaction을 수행한다. 그 transaction에 artifact metadata,
immutable Catalog/Tool entry, job terminal state와 event를 함께 기록한다.
이는 object upload까지 포함한 분산 transaction을 뜻하지 않는다. 게시 실패·stale claim 이후 남는 object는
retention/cleanup 경계에서 다룬다.

Catalog는 최종 Tool IR의 canonical `RUNTIME_METADATA.json`을 사용하며 framework/secret 정보를 끌어오지 않는다.
`VALIDATED`인 성공 결과만 게시하고 기존 generation을 자동 backfill하지 않는다.
다운로드는 Web이 owner를 확인한 뒤 private object를 stream한다. public bucket이나 anonymous listing을 사용하지 않는다.

## 검증과 운영

실제 PostgreSQL 테스트에서 중복 접수, 소유권, 동시 claim, lease 만료, stale 완료 거절과 Catalog의 원자적 게시를
검증한다. Sandbox 테스트에서는 timeout·취소·프로세스 실패·잘못된 결과와 정리를 확인한다.
진행 표시의 SSE/polling 계약은 [사용자 흐름](user-flows.md), 생성 완료 후 runtime 사용은
[Managed Runtime](managed-runtime.md)에 있다. 설치·설정·backup/restore 명령은 배포 가이드에서 관리한다.
