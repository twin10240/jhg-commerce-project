# Local OMS/WMS Hardening Design

## Goal

Railway 없이 로컬 OMS와 WMS의 핵심 주문·보충 흐름을 검증하고, PostgreSQL 마이그레이션과 서비스 간 인증·장애 복구를 확인한 뒤 두 저장소의 `master`를 원격에 반영한다.

## Scope

- OMS: `C:\study\jhg-commerce-project`
- WMS: `C:\study\jhg-wms-project`
- Railway 설정·배포·스모크 테스트는 제외한다.
- 새 테스트 프레임워크나 컨테이너 라이브러리는 추가하지 않는다.
- WMS의 사용자 소유 `.claude/`는 수정하거나 커밋하지 않는다.

## Approach

기존 Spring Boot 애플리케이션, Gradle 테스트, Docker PostgreSQL과 HTTP Basic 인증을 재사용한다. Docker Compose 기반 신규 통합 환경이나 Testcontainers 교차 저장소 테스트는 만들지 않는다. 로컬 실행 검증은 두 앱을 각각 `8080`과 `8081`에서 구동해 실제 REST 경계를 통과시킨다.

## Changes

### 1. Callback authentication

OMS의 `POST /api/replenishments`에 전용 HTTP Basic 인증을 적용한다. 일반 회원 인증정보를 재사용하지 않고 콜백 전용 환경변수를 둔다. WMS `OmsReplenishmentNotifier`는 같은 자격증명을 전송한다. 인증 누락·오류는 `401`, 정상 요청은 기존 백오더 승격 흐름으로 전달한다.

기본 로컬 자격증명은 별도 설정 없이 두 앱이 연동되도록 동일한 개발 기본값을 사용하고, 외부 환경에서는 환경변수로 반드시 교체할 수 있게 한다.

### 2. Local end-to-end verification

두 앱을 로컬에서 실행하고 다음 흐름을 실제 HTTP 경계로 확인한다.

1. OMS가 WMS 재고를 조회한다.
2. OMS가 보충 요청을 생성하고 WMS가 동일 요청을 조회한다.
3. 동일 `requestKey` 재전송이 중복 생성되지 않는다.
4. WMS 승인으로 발주가 생성되고 입고 후 재고가 증가한다.
5. WMS 콜백으로 OMS 백오더가 `ORDER`로 승격된다.

### 3. PostgreSQL/Flyway verification

로컬 Docker PostgreSQL 빈 데이터베이스에서 OMS를 `prod` 프로파일로 기동한다. Flyway V1~V4 적용과 Hibernate `validate` 통과를 확인한다. 검증은 실제 PostgreSQL 기동 결과와 Flyway 스키마 이력으로 판단하며 새 테스트 의존성은 추가하지 않는다.

### 4. Failure and recovery verification

기존 단위·MVC 테스트를 우선 재사용하고 빠진 경계만 최소 테스트로 보강한다.

- WMS 예약 타임아웃·5xx: 한 번 재시도 후 OMS는 `BACKORDERED`로 접수
- WMS 재기동 또는 콜백 유실: `BackorderSweeper`가 재할당
- 중복 콜백·중복 보충 요청: 상태와 멱등키로 동일 결과 수렴
- 잘못된 콜백 자격증명: OMS 상태 변경 없이 `401`

분산 트랜잭션용 outbox·메시지 브로커는 이번 범위에서 추가하지 않는다.

### 5. Test deprecation cleanup

OMS 테스트의 폐기 예정 `@MockBean`을 Spring Boot 3.5에서 제공하는 `@MockitoBean`으로 기계적으로 교체한다. 테스트 의미와 운영 코드는 변경하지 않는다.

### 6. Verification and delivery

OMS와 WMS에서 전체 Gradle 테스트를 캐시 없이 실행한다. PostgreSQL과 로컬 E2E 결과까지 확인한 뒤 저장소별로 의도한 파일만 커밋한다. 각 `master`의 upstream과 원격 차이를 확인하고 일반 fast-forward push만 수행한다. 강제 push, reset, rebase, stash는 사용하지 않는다.

## Success Criteria

- OMS와 WMS 전체 테스트가 각각 실패 없이 통과한다.
- OMS 콜백은 올바른 Basic 인증에만 성공한다.
- 로컬 PostgreSQL에서 Flyway V1~V4와 Hibernate 검증이 통과한다.
- 로컬 두 앱에서 보충 요청부터 입고·백오더 승격까지 확인된다.
- 장애·재시도·스윕·멱등 테스트가 통과한다.
- 두 저장소의 의도한 커밋이 `origin/master`에 push된다.
