# 그니 마켓 (jhg-commerce)

Spring Boot 기반 **학습용 커머스 웹 애플리케이션**입니다.
JPA 도메인 설계(주문/배송/재고)에 Spring Security 인증, QueryDSL, 장바구니 REST API를 직접 확장하며 만들고 있습니다.

## 프로젝트 비전 — 미니 OMS + 별도 WMS

최종 목표는 쇼핑몰 완성이 아니라 **미니 OMS(주문관리시스템)와 별도 WMS(창고관리시스템) 간 통신** 구현입니다.
핵심 컨셉은 **"재고가 없어도 주문이 가능해야 한다(백오더)"** 입니다 — 가용 재고가 없으면 구매를 차단하는 대신, 주문을 *입고 대기(BACKORDERED)* 로 접수하고 관리자가 발주·입고하면 밀린 주문이 자동으로 충족됩니다.

| 단계 | 목표 | 상태 |
|------|------|------|
| Phase 1 | 주문 정책 전환(예약/백오더 모델) — 모놀리스 내부 OMS화 | ✅ 완료 |
| Phase 2 | 모듈 경계 분리(`oms/`·`wms/`, 서비스 인터페이스 통신) | ⬜ 다음 단계 |
| Phase 3 | WMS 물리 분리(별도 앱 + REST 통신) | ⬜ |
| Phase 4 | (선택) 이벤트/메시지 기반 전환 | ⬜ |

> 📄 자세한 배경·시나리오·로드맵은 **[기획서](docs/기획서.md)** 를 참고하세요.

## 주요 기능

| 영역 | 기능 |
|------|------|
| 회원 | 회원가입(Member+Account 단일 트랜잭션, 이메일 중복 검증), 로그인/로그아웃 |
| 상품 | 키워드 검색 + 숫자 페이지 네비게이션(`이전 \| 1 … 4 [5] 6 … 12 \| 다음`), 품절/잔여 재고 표시 |
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
- **H2** (TCP 모드), p6spy(SQL 로깅), Lombok
- **Thymeleaf** 서버 사이드 렌더링

## 실행 방법

```bash
# 1. H2 TCP 서버 실행 (datasource: jdbc:h2:tcp://localhost/~/hgpage)

# 2. 애플리케이션 실행
./gradlew bootRun     # Windows: .\gradlew.bat bootRun

# 3. http://localhost:8080 접속
```

> JDK 17 이상이 필요합니다. (`JAVA_HOME` 확인)

### 초기 계정 (자동 시드)

| 구분 | 이메일 | 비밀번호 |
|------|--------|----------|
| 관리자 | `admin@admin.com` | `1111` |
| 일반회원 | `twin10240@naver.com` | `1111` |

상품 20개와 재고가 함께 시드됩니다.

## 테스트

```bash
./gradlew test
```

- **단위 테스트**: Mockito 기반 서비스/도메인 테스트 (`InventoryServiceTest`, `PurchaseOrderTest` 등)
- **슬라이스 테스트**: `@WebMvcTest`(Security 포함 컨트롤러·템플릿 렌더링 검증), `@DataJpaTest`(임베디드 H2 — 낙관적 락, fetch join 쿼리 검증, 별도 DB 서버 불필요)

## 프로젝트 구조

```
src/main/java/com/jhg/hgpage
├── api/          장바구니 REST API
├── config/       Security, QueryDSL 설정
├── controller/   화면 컨트롤러 (auth / main / cart / order / admin) + form DTO
├── domain/       JPA 엔티티 (Order·Delivery·Inventory·PurchaseOrder 등) + enums
├── exception/    전역 예외 처리 (GlobalExceptionHandler 등)
├── repository/   Spring Data JPA + QueryDSL
└── service/      비즈니스 로직
```

## 문서

- [`docs/기획서.md`](docs/기획서.md) — 프로젝트 기획서(배경·비전·핵심 시나리오·로드맵)
- [`CLAUDE.md`](CLAUDE.md) — 아키텍처·도메인 규칙·알려진 이슈·개선 우선순위
- [`docs/superpowers/specs/`](docs/superpowers/specs/) — 기능별 설계 문서
