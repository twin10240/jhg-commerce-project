# 그니 마켓 (jhg-commerce)

[![CI](https://github.com/twin10240/jhg-commerce-project/actions/workflows/ci.yml/badge.svg)](https://github.com/twin10240/jhg-commerce-project/actions/workflows/ci.yml)

**모의 카드 결제 후 재고를 할당하고, 배송 완료 반품까지 복구 가능하게 연동하는 OMS V2입니다.**

주문은 모의 결제를 먼저 승인한 뒤 비동기로 WMS 재고를 할당합니다. 재고 부족은 결제 실패가 아니라
`BACKORDERED`로 보존되며, 입고 뒤 FIFO로 다시 할당합니다. 취소와 반품 환불은 DB 작업으로 저장해
게이트웨이 장애 뒤에도 같은 멱등키로 복구합니다.
주문·백오더 정책과 고객 화면은 OMS가, 재고·예약·발주·입고는 별도
[JHG-WMS](https://github.com/twin10240/jhg-wms-project)가 소유합니다.

| | |
|---|---|
| 주문 정책 | 모의 카드 승인 후 재고 확보 또는 `BACKORDERED`, 입고 시 FIFO 자동 할당 |
| 시스템 경계 | OMS는 주문·고객 반품 요청, WMS는 재고 정본·RMA 처리를 소유하고 REST로 통신 |
| 장애 복구 | 결제·할당·취소·환불의 타임아웃·멱등 작업과 백오더/RMA 보상 스윕 |
| 테스트 | 340개 (도메인·서비스·MVC·HTTP 통합·반응형 계약) |

## 프로젝트 비전 — 미니 OMS + 별도 WMS

최종 목표는 쇼핑몰 완성이 아니라 **미니 OMS(주문관리시스템)와 별도 WMS(창고관리시스템) 간 통신** 구현입니다.
핵심 컨셉은 **"재고가 없어도 주문이 가능해야 한다(백오더)"** 입니다 — 가용 재고가 없으면 구매를 차단하는 대신, 주문을 *입고 대기(BACKORDERED)* 로 접수합니다. OMS 관리자는 WMS에 보충을 요청하고, WMS가 승인·입고하면 밀린 주문이 자동으로 충족됩니다.

| 단계 | 목표 | 상태 |
|------|------|------|
| Phase 1 | 주문 정책 전환(예약/백오더 모델) — 모놀리스 내부 OMS화 | ✅ 완료 |
| Phase 2 | 모듈 경계 분리(`contract`·`catalog`·`oms`·`wms`, 서비스 인터페이스 통신) | ✅ 완료 (코어) |
| Phase 3 | WMS 물리 분리(별도 앱 + REST 통신) | ✅ 완료 |
| 포트폴리오 1차 | 고객·관리자 UX, 반응형 UI, OMS·WMS 통합 수동 검증 | ✅ 완료 (2026-08-04) |
| OMS V2 | `READY → SHIPPED → DELIVERED`, 고객 반품·WMS RMA 연동 | OMS 구현·자동 검증 완료, 통합 수동 검증 9/9 통과 |
| Phase 4 | (선택) 이벤트/메시지 기반 전환 | ⬜ |

> 📄 자세한 배경·시나리오·로드맵은 **[기획서](docs/기획서.md)** 를 참고하세요.

## OMS V2 — 배송 완료와 RMA (현재 상태)

OMS 자동 테스트 340개가 통과했고, 정상 재입고·부분 승인·폐기·취소·장애 복구·보안 계약을 포함한
통합 수동 시나리오 9개도 모두 완료했습니다. 실행 절차와 증거는
[수동 검증 시나리오](docs/manual-verification-scenarios.md#oms-v2-rma--현재-수동-검증-대상-2026-08-12)에 기록합니다.

배송은 `READY → SHIPPED → DELIVERED`로 구분합니다. 관리자는
`POST /admin/orders/ship`으로 WMS 출고를 확정하고, 이후
`POST /admin/orders/deliver`로 배송 완료를 기록합니다. 기존 운영 데이터의 출고 완료 값은
Flyway V5가 `SHIPPED`로 이전합니다.

OMS는 배송 완료 주문의 고객 반품 신청, 품목별 신청 가능 수량, 멱등 `requestKey`, WMS 결과 동기화와
고객 조회를 소유합니다. WMS는 RMA 접수·입고·검수·취소, `RESTOCKED` 재고 반영과
`DISPOSED`·`REJECTED` 판정을 소유합니다. 따라서 OMS에는 반품 재고 수량이나 재고 원장을 두지 않습니다.

### OMS V2 — 모의 결제와 환불 복구

체크아웃은 카드 정보 저장 없이 모의 결제 승인만 수행하고, 승인된 주문만 WMS에 할당합니다. 결제 실패는
주문을 보존해 고객 재결제를 허용하며, WMS 부족은 유료 `BACKORDERED`로 남습니다. 주문 취소는 전액,
WMS가 승인한 반품 수량은 부분 환불합니다.

환불은 외부 호출 전에 `RefundRequest`와 예약 금액을 저장합니다. 일시 실패는 같은 멱등키로 재시도하고,
자동 복구가 끝나지 않거나 영구 실패면 관리자 결제 화면의 수동 확인 대상으로 남습니다. 관리자는 재시도만
요청할 수 있고 근거 없이 완료 처리할 수 없습니다.

V3에서는 실제 PG 승인·취소와 거래 조회, 웹훅 서명 검증, 쿠폰·포인트 및 부분 환불 배분을 추가합니다.

OMS → WMS의 `POST /api/returns`와 `GET /api/returns/{rmaId}`는 기존 WMS Basic 인증을 사용합니다.
WMS → OMS의 `POST /api/return-status-events`는 `oms.callback.user/password`로 인증하며, 계약 불일치는
`409`, 인증 실패는 `401`입니다. 콜백이나 응답이 유실되면 `returns.sweep-delay`(기본 `60s`) 주기로
미접수 요청을 재전송하거나 진행 중 RMA를 단건 조회합니다.

토스페이먼츠 결제와 환불 상태·금액 처리는 **다음 단계**입니다. OMS V2 RMA는 WMS 검수 승인 수량까지만
확정하며 결제·환불을 가장하는 상태나 처리는 포함하지 않습니다.

## 주요 기능

| 영역 | 기능 |
|------|------|
| 회원 | 회원가입(Member+Account 단일 트랜잭션, 이메일 중복·비밀번호 일치 서버 검증), 로그인/로그아웃 |
| 상품 | 키워드 검색·페이징, 상품 상세, WMS 가용 재고·입고 대기 표시 |
| 장바구니 | REST API 기반 담기/수량변경/삭제, 실시간 카운트 배지 |
| 주문 | 바로 구매·선택 주문, 생성 주문 상세 이동, 내 주문·상태 타임라인·취소 |
| 백오더 | 재고 부족해도 **주문 접수(입고 대기)**, 입고 시 **FIFO 자동 충족**, 출고 시점에 실물 차감 |
| 반품 | 배송 완료 주문의 품목·수량별 신청, WMS RMA 접수·검수 결과와 복구 가능한 동기화 |
| 재고 | WMS 예약 모델(`가용 = 실물 − 예약`)과 `orderId` 멱등 원장 |
| 관리자 | WMS 재고·백오더 수량 조회, 보충 요청, 주문상품 확인, 단건·선택 일괄 출고·배송 완료 |
| 공통 | 375px~데스크톱 반응형 UI, 전역 예외 처리(화면: 에러 페이지·flash / API: ProblemDetail JSON), 다크 모드 지원 |

OMS 관리자에게는 수동 재고 조정·발주 생성·입고 권한이 없습니다. 해당 작업과 보충 요청 원본은 WMS가 소유합니다. 주문 이행을 위한 `reserve`·`ship`·`release` 호출은 기존과 같이 OMS 주문 흐름에서 유지됩니다.

## 기술 스택

- **Java 17**, **Spring Boot 3.5**, Gradle
- **Spring Data JPA** (Hibernate) + **QueryDSL** (jakarta)
- **Spring Security 6** (BCrypt, Thymeleaf 통합)
- **H2** (로컬/테스트) · **PostgreSQL** (운영)
- **Thymeleaf** 서버 사이드 렌더링, Lombok, p6spy(SQL 로깅)
- **Docker** · **Railway** (배포)

## 아키텍처 — 바운디드 컨텍스트

OMS(주문)와 WMS(재고)의 도메인·서비스·리포지토리를 **컨텍스트별로 수직 분할**하고,
둘은 서로 직접 의존하지 않고 **`contract/` 경계 포트로만 통신**합니다(패키지 순환 없음).

```
src/main/java/com/jhg/hgpage
├── contract/   OMS↔WMS 경계 포트 (InventoryPort · InventoryQueryPort · ReturnPort · StockReplenishedHandler)
├── catalog/    OMS 상품 카탈로그 (Product) + ProductService/Repository
├── oms/        주문·장바구니·고객   (domain · repository · service)
│                 Order · CustomerReturn · Cart · Account · Member · OrderService · BackorderAllocator …
├── wms/        WMS REST adapter·DTO와 OMS 관리자 보충 요청 화면
├── config/     Security, QueryDSL 설정
├── web/        상품 목록·상세 등 공용 화면
├── domain/     UserPrincipal · Role enum
├── exception/  전역 예외 처리 (GlobalExceptionHandler 등)
└── initDb      초기 시드 (빈 DB에만 실행)
```

> OMS→WMS의 주문 이행 호출(예약/해제/출고)과 가용 조회는 `contract` 포트를 거칩니다. 관리자 보충 요청 제출·이력 조회는 전용 WMS REST adapter를 사용합니다.

### OMS ↔ WMS 책임 경계

Phase 3에서 WMS를 물리적으로 분리한 뒤, 재고의 정본(source of truth)은 **WMS 한 곳**입니다. OMS는 재고 수량을 저장하지 않고 필요할 때마다 WMS에 HTTP로 조회/차감합니다(미러 아님 — 라이브 리드).

| | OMS (이 저장소, :8080) | WMS (jhg-wms, :8081) |
|---|---|---|
| 소유 도메인 | 주문·장바구니·고객·판매·고객 반품 요청, 백오더 | 재고 수량·예약·발주(PO)·입고·재고 원장·RMA 처리 |
| 재고에 대해 | 조회(실시간 질의) + "보충해줘" 요청 | 재고 정본. 수동 조정·발주·입고·요청 승인 |
| 관리자 권한 | 재고 조정·발주·입고 **없음**(설계상 제거) | 위 전부 소유 (OPERATOR/MANAGER 롤) |
| DB | 주문·고객 (재고 수량 없음) | 재고·예약·발주·원장 |

#### 통신 채널 (Basic 인증)

| 채널 | 방향 | 용도 |
|------|------|------|
| S1 | OMS → WMS | 가용수량 조회 `GET /api/inventory/availability` |
| S2 | OMS → WMS | 주문 이행 `reserve` / `ship` / `release` |
| S3 | WMS → OMS | 재고 증가(입고·조정) 통지 → OMS 백오더 FIFO 승격 |
| S4 | 양방향 | 회복탄력성 — 타임아웃 · best-effort · 보상 스윕 |
| RMA | 양방향 | OMS 접수·단건 조회 ↔ WMS 검수 결과 Basic 인증 콜백 |

보충 흐름: OMS가 백오더로 부족을 감지 → WMS에 **보충 요청** → WMS 관리자가 **승인 → 발주 생성 → 입고** → 재고 증가 → S3로 OMS에 통지 → OMS가 백오더 승격.

> 핵심: **"몇 개 있냐"는 오직 WMS.** OMS는 그 재고를 실시간으로 조회·차감할 뿐 자기 수량을 갖지 않는다. WMS가 응답하지 않으면 OMS는 가용수량 0으로 폴백(품절/백오더)해 무너지지 않는다.

## 로컬 실행

```bash
# 1. H2 TCP 서버 실행
# OMS: jdbc:h2:tcp://localhost/~/hgpage
# WMS: jdbc:h2:tcp://localhost/~/jhg-wms

# 2. WMS(:8081) 실행 - jhg-wms-project
./gradlew bootRun

# 3. OMS(:8080) 실행 - 이 저장소
./gradlew bootRun

# 스키마 리셋 + 재시드가 필요하면 local 프로파일 (ddl-auto: create)
./gradlew bootRun --args='--spring.profiles.active=local'

# 4. http://localhost:8080 접속
```

기본 로컬 실행은 데이터를 보존하며, OMS V2 최초 기동 시 기존 배송 상태 `COMP`를 같은 의미의
`SHIPPED`로 자동 변환합니다. `local` 프로파일은 데이터를 초기화해도 되는 경우에만 사용합니다.

로컬 기본 서비스 자격증명은 양쪽 모두 `WMS_BASIC_USER=wms`,
`WMS_BASIC_PASSWORD=wms`, `OMS_CALLBACK_USER=wms`, `OMS_CALLBACK_PASSWORD=wms`입니다.
OMS는 JDK 17 이상, WMS는 JDK 21이 필요합니다. 테스트는 임베디드 H2를 사용합니다.

### 초기 계정 (자동 시드)

| 구분 | 이메일 | 비밀번호 |
|------|--------|----------|
| 관리자 | `admin@admin.com` | `ADMIN_PASSWORD` 환경변수 (로컬 기본 `1111`) |
| 일반회원 | `twin10240@naver.com` | `1111` |

OMS는 상품 20개를, WMS는 같은 ID의 재고 20개를 각각 시드합니다. 관리자 비밀번호는
`ADMIN_PASSWORD` 환경변수로 주입합니다.

## 배포 (Railway)

Railway 배포 설정은 보존돼 있지만 **현재 서비스는 중단 상태**입니다. 아래 내용은 재배포 시 사용하는 구성입니다.

- **빌드**: 루트 `Dockerfile`(JDK17 멀티스테이지 — 빌드/실행 분리). `railway.json`이 Dockerfile 빌더를 강제.
- **DB**: `prod` 프로파일 + Railway PostgreSQL 플러그인. 앱 서비스에 환경변수 설정:
  ```
  SPRING_PROFILES_ACTIVE=prod
  PGHOST / PGPORT / PGDATABASE / PGUSER / PGPASSWORD   # Postgres 서비스 값
  ADMIN_PASSWORD=<강한 비밀번호>
  WMS_BASE_URL=http://<wms>.railway.internal:8081
  WMS_BASIC_USER / WMS_BASIC_PASSWORD
  OMS_CALLBACK_USER / OMS_CALLBACK_PASSWORD
  PAYMENT_SWEEP_DELAY / PAYMENT_PROCESSING_TIMEOUT
  ALLOCATION_SWEEP_DELAY / ALLOCATION_PROCESSING_TIMEOUT
  REFUND_SWEEP_DELAY / REFUND_PROCESSING_TIMEOUT
  CANCELLATION_SWEEP_DELAY / CANCELLATION_PROCESSING_TIMEOUT
  ```
- **포트**: `server.port=${PORT:8080}` 로 Railway가 주입하는 포트에 바인딩.
- **스키마**: Flyway로 버전 관리(`prod` 프로파일). 첫 기동 시 `V1__init_schema.sql`이 적용돼 스키마를 생성하고 `initDb`가 빈 DB를 시드. `ddl-auto: validate`로 엔티티-스키마 불일치를 기동 시 감지.

> 스키마 변경은 `src/main/resources/db/migration/V{n}__*.sql` 마이그레이션 파일로 관리. 스키마를 초기화하려면 Railway DB에서 `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` 후 재배포(데이터 소실).

## 테스트

```bash
./gradlew test
```

- **단위 테스트**: Mockito 기반 서비스/도메인 테스트 (`OrderServiceTest`, `OrderAllocationServiceTest`, `BackorderAllocatorTest` 등)
- **슬라이스 테스트**: `@WebMvcTest`(Security 포함 컨트롤러·템플릿 렌더링 검증), `@DataJpaTest`(임베디드 H2 — 낙관적 락, fetch join 쿼리, 시드 멱등성 검증 — 별도 DB 서버 불필요)

## 문서

- [`docs/기획서.md`](docs/기획서.md) — 프로젝트 기획서(배경·비전·핵심 시나리오·로드맵)
- [`docs/manual-verification-scenarios.md`](docs/manual-verification-scenarios.md) — OMS·WMS 통합 수동 검증 기준본
- [`risk.md`](risk.md) — 열린 운영·정합성 리스크
- [`CLAUDE.md`](CLAUDE.md) — 아키텍처·도메인 규칙·배포·알려진 이슈·개선 우선순위
- [`docs/superpowers/`](docs/superpowers/) — 날짜별 설계·구현 계획 기록
