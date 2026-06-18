# 그니 마켓 (jhg-commerce)

Spring Boot 기반 **학습용 커머스 웹 애플리케이션**입니다.
JPA 도메인 설계(주문/배송/재고)에 Spring Security 인증, QueryDSL, 장바구니 REST API를 직접 확장하며 만들고 있습니다.

## 프로젝트 비전 — 미니 OMS + 별도 WMS

최종 목표는 쇼핑몰 완성이 아니라 **미니 OMS(주문관리시스템)와 별도 WMS(창고관리시스템) 간 통신** 구현입니다.
핵심 컨셉은 **"재고가 없어도 주문이 가능해야 한다(백오더)"** 입니다 — 가용 재고가 없으면 구매를 차단하는 대신, 주문을 *입고 대기(BACKORDERED)* 로 접수하고 관리자가 발주·입고하면 밀린 주문이 자동으로 충족됩니다.

| 단계 | 목표 | 상태 |
|------|------|------|
| Phase 1 | 주문 정책 전환(예약/백오더 모델) — 모놀리스 내부 OMS화 | ✅ 완료 |
| Phase 2 | 모듈 경계 분리(`contract`·`catalog`·`oms`·`wms`, 서비스 인터페이스 통신) | ✅ 완료 (코어) |
| Phase 3 | WMS 물리 분리(별도 앱 + REST 통신) | ⬜ 다음 단계 |
| Phase 4 | (선택) 이벤트/메시지 기반 전환 | ⬜ |

> 📄 자세한 배경·시나리오·로드맵은 **[기획서](docs/기획서.md)** 를 참고하세요.

## 주요 기능

| 영역 | 기능 |
|------|------|
| 회원 | 회원가입(Member+Account 단일 트랜잭션, 이메일 중복·비밀번호 일치 서버 검증), 로그인/로그아웃 |
| 상품 | 키워드 검색 + 숫자 페이지 네비게이션(`이전 \| 1 … 4 [5] 6 … 12 \| 다음`), 가용 재고·입고 대기 표시 |
| 장바구니 | REST API 기반 담기/수량변경/삭제, 실시간 카운트 배지 |
| 주문 | 바로 구매 / 장바구니 주문, 주문서에서 **상품 선택 주문**, 검증 실패 인라인 에러 |
| 백오더 | 재고 부족해도 **주문 접수(입고 대기)**, 입고 시 **FIFO 자동 충족**, 출고 시점에 실물 차감 |
| 재고 | 예약 모델(`가용 = 실물 − 예약`), `@Version` 낙관적 락으로 동시 주문 오버셀 방지 |
| 관리자 | 재고 조회/수동 조정, 발주 생성 → 입고 처리(중복 입고 방지), 발주 현황, 배송완료 처리 |
| 공통 | 전역 예외 처리(화면: 에러 페이지·flash / API: ProblemDetail JSON), 다크 모드 지원 |

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
├── contract/   OMS↔WMS 경계 포트 (InventoryPort · InventoryQueryPort · StockReplenishedHandler)
├── catalog/    상품 카탈로그 (Product — OMS·WMS 공통) + ProductService/Repository
├── oms/        주문·장바구니·고객   (domain · repository · service)
│                 Order · Cart · Account · Member · OrderService · BackorderAllocator …
├── wms/        재고·발주·입고·출고   (domain · repository · service)
│                 Inventory · PurchaseOrder · InventoryService · PurchaseOrderService …
├── api/        장바구니·주문 REST API
├── config/     Security, QueryDSL 설정
├── controller/ 화면 컨트롤러 (auth / main / cart / order / admin) + form DTO
├── domain/     공용 응답 DTO(view) · UserPrincipal · Role enum
├── exception/  전역 예외 처리 (GlobalExceptionHandler 등)
└── initDb      초기 시드 (빈 DB에만 실행)
```

> OMS→WMS의 재고 호출(예약/해제/출고/가용 조회)은 모두 `contract` 포트를 거칩니다.
> Phase 3에서 이 포트가 그대로 **REST 호출**로 진화하는 지점입니다.

## 로컬 실행

```bash
# 1. H2 TCP 서버 실행 (datasource: jdbc:h2:tcp://localhost/~/hgpage)

# 2. 애플리케이션 실행
./gradlew bootRun          # Windows: .\gradlew.bat bootRun

# 스키마 리셋 + 재시드가 필요하면 local 프로파일 (ddl-auto: create)
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. http://localhost:8080 접속
```

> JDK 17 이상이 필요합니다 (`JAVA_HOME` 확인). 테스트는 임베디드 H2를 쓰므로 TCP 서버 없이 돕니다.

### 초기 계정 (자동 시드)

| 구분 | 이메일 | 비밀번호 |
|------|--------|----------|
| 관리자 | `admin@admin.com` | `ADMIN_PASSWORD` 환경변수 (로컬 기본 `1111`) |
| 일반회원 | `twin10240@naver.com` | `1111` |

상품 20개와 재고가 함께 시드됩니다. 관리자 비밀번호는 코드에 박지 않고 `ADMIN_PASSWORD` 환경변수로 주입합니다.

## 배포 (Railway)

GitHub `master`에 push하면 Railway가 자동 빌드·배포합니다.

- **빌드**: 루트 `Dockerfile`(JDK17 멀티스테이지 — 빌드/실행 분리). `railway.json`이 Dockerfile 빌더를 강제.
- **DB**: `prod` 프로파일 + Railway PostgreSQL 플러그인. 앱 서비스에 환경변수 설정:
  ```
  SPRING_PROFILES_ACTIVE=prod
  PGHOST / PGPORT / PGDATABASE / PGUSER / PGPASSWORD   # Postgres 서비스 값
  ADMIN_PASSWORD=<강한 비밀번호>
  ```
- **포트**: `server.port=${PORT:8080}` 로 Railway가 주입하는 포트에 바인딩.
- **스키마**: 첫 기동 시 `ddl-auto: update`로 생성 → `initDb`가 빈 DB를 시드.

> 엔티티 구조를 바꿔 스키마를 다시 만들려면 운영 DB에서 `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` 후 재배포합니다(데이터 소실 — 학습/데모 기준). 운영 안정화 시 Flyway 도입 검토.

## 테스트

```bash
./gradlew test
```

- **단위 테스트**: Mockito 기반 서비스/도메인 테스트 (`InventoryServiceTest`, `OrderAllocationServiceTest`, `PurchaseOrderTest` 등)
- **슬라이스 테스트**: `@WebMvcTest`(Security 포함 컨트롤러·템플릿 렌더링 검증), `@DataJpaTest`(임베디드 H2 — 낙관적 락, fetch join 쿼리, 시드 멱등성 검증 — 별도 DB 서버 불필요)

## 문서

- [`docs/기획서.md`](docs/기획서.md) — 프로젝트 기획서(배경·비전·핵심 시나리오·로드맵)
- [`CLAUDE.md`](CLAUDE.md) — 아키텍처·도메인 규칙·배포·알려진 이슈·개선 우선순위
- [`docs/`](docs/) — 기능별 설계 문서
