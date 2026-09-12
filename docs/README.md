# 문서 안내

이 문서 모음은 OpenAPI MCP Generator의 현재 구조, 생성 계약, 사용자 흐름과 운영 경계를 설명한다.
날짜별 설계·구현 계획의 유지할 내용은 주제별 가이드와 [설계와 의사결정](design-decisions.md)에 통합했다.
과거 단계별 작업 지시와 테스트 통과 기록은 현재 실행 절차나 검증 결과로 취급하지 않는다.

## 읽기 순서

1. [사용자 가이드](user-guide.md): 설치, CLI, 생성 설정, 지원 profile과 제한
2. [개발 및 검증](development-guide.md): 로컬 개발, 빠른 검사, 생성 프로젝트와 hosted acceptance
3. [프로젝트 구조](project-structure.md): Gradle 모듈과 앱별 패키지 책임
4. [아키텍처 경계](architecture-boundaries.md): 의존 방향, 조립, 오류·보안 경계
5. [생성 파이프라인과 스키마](generation-pipeline.md): Tool IR, nullable·composition, 검증과 결정성
6. [사용자 흐름과 진행 상태](user-flows.md): 5단계 위저드, SSE, 복구와 화면 계약
7. [Hosted 작업과 복구](hosted-platform.md): 소유권, lease·fencing, URL import, sandbox와 Catalog 게시
8. [Managed Runtime과 Catalog](managed-runtime.md): credential, grant, audit, revision·migration·rollback
9. [설계와 의사결정](design-decisions.md): 선택 이유, 과거 결정의 변경, 통합 출처
10. [새 기능 추가 체크리스트](feature-addition-checklist.md): 변경 책임과 함께 검증할 계약

## 제품·운영·시각 자료

| 문서 | 용도 |
| --- | --- |
| [제품 요구사항](prd.md) | 요구사항과 P0/P1/P2 도입 이력. 현재 사용법은 사용자 가이드 기준 |
| [Hosted 배포 가이드](../deploy/hosted/README.md) | secret 준비, Linux Compose, 백업·복구·운영 절차 |
| [Hosted 구조도](architecture/hosted-generation-platform.html) | 생성 플랫폼 배포 및 데이터 흐름 |
| [Managed Runtime 구조도](architecture/managed-mcp-runtime.html) | 제어·실행·provider egress 분리 |
| [화면 QA 기록](../design-qa.md) | 과거 화면 검토와 보존한 이미지 증거 |
| [OpenAPI 3.0 fixture](../swagger-3.0.yml), [3.1 fixture](../swagger-3.1.yml) | 분석·생성의 재현 입력. 이 서비스 자체의 REST API 명세가 아님 |

## 문서 관리 원칙

코드·설정·테스트에서 확인한 계약을 설명하고 해당 구현으로 연결한다. 사용자 설정과 profile 목록은
사용자 가이드, 운영 명령은 배포 가이드, 선택 이유는 설계 문서에서 관리한다. 참조 저장소의 주문·JPA
구조를 이 프로젝트에 적용하지 않고 이 저장소의 생성기·hosted·runtime 책임을 따른다.
문서 변경 시 내부 링크, 코드 경로, 명령, 지원 범위와 과거/현재 표현을 함께 확인한다.
