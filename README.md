# 그니 마켓 (jhg-commerce)

Spring Boot 기반 학습용 커머스이자 **미니 OMS(Order Management System)** 입니다. 주문·회원·장바구니를 관리하고, 별도 **WMS(Warehouse Management System)** 와 REST로 재고·예약·발주를 연동합니다.

핵심 정책은 **재고가 없어도 주문을 받는 백오더**입니다. 가용 재고가 부족하면 주문을 `BACKORDERED`로 접수하고, 입고 후 오래된 주문부터 자동으로 예약합니다.

## 로드맵

| 단계 | 목표 | 상태 |
|------|------|------|
| Phase 1 | 예약·백오더 주문 모델 | ✅ 완료 |
| Phase 2 | OMS/WMS 모듈 경계와 포트 분리 | ✅ 완료 |
| Phase 3 | 별도 WMS 앱·DB와 REST 통신 | ✅ 완료 |
| Phase 4 | 이벤트·메시지 기반 전환 | 선택 |

자세한 배경과 시나리오는 [기획서](docs/기획서.md), 상세 변경 이력은 [CLAUDE.md](CLAUDE.md)를 참고하세요.

## 주요 기능

| 영역 | 기능 |
|------|------|
| 회원 | 회원가입, 이메일 중복·비밀번호 확인, 로그인·로그아웃, USER/ADMIN 권한 |
| 상품 | 키워드 검색, 숫자 페이지 이동, WMS 가용 재고·입고 대기 표시 |
| 장바구니 | REST 기반 담기·수량 변경·삭제, 실시간 카운트 |
| 주문 | 바로 구매·선택 상품 주문, 주문 상세·취소, 배송 완료 |
| 백오더 | 재고 부족 주문 접수, 입고 콜백·보상 스윕을 통한 FIFO 자동 승격 |
| WMS 연동 | 가용 재고 조회, 멱등 예약·출고·해제, 재고조정, 발주·입고 |
| 안정성 | WMS 타임아웃, 예약 1회 재시도, `orderId` 멱등 원장, 낙관적 락 |

## 시스템 구성

```text
Browser
  │
  ▼
OMS (이 저장소, Java 17, :8080)
  ├─ 회원·장바구니·상품 카탈로그
  ├─ 주문·배송·백오더 정책
  └─ WMS REST adapters ──HTTP Basic──▶ WMS (jhg-wms-project, Java 21, :8081)
                                       ├─ 재고·예약 원장
                                       ├─ 발주·입고·출고
                                       └─ 자체 DB(H2/PostgreSQL)
```

OMS와 WMS는 서로의 엔티티를 공유하지 않습니다. OMS는 `contract/` 포트에 의존하고 `wms/adapter`가 포트를 REST 호출로 구현합니다. WMS는 `productId`와 `orderId`만으로 재고·예약을 관리합니다.

```text
src/main/java/com/jhg/hgpage
├─ contract/   InventoryPort · InventoryQueryPort · StockReplenishedHandler
├─ catalog/    Product · ProductService · ProductRepository
├─ oms/        주문·장바구니·고객 domain/repository/service/web
├─ wms/        REST adapter · 응답 DTO · 관리자 프록시 web
├─ config/     Security · QueryDSL · Scheduling
├─ web/        공용 Home/Main 컨트롤러
└─ exception/  화면·API 전역 예외 처리
```

실제 `Inventory`, `Reservation`, `PurchaseOrder` 도메인과 영속성은 별도 `jhg-wms-project`에 있습니다.

## 기술 스택

- OMS: Java 17, Spring Boot 3.5.5, Spring Data JPA, QueryDSL, Spring Security, Thymeleaf
- WMS: Java 21, Spring Boot 3.5.5, Spring Data JPA, Spring Security, Thymeleaf
- 로컬 DB: 각 앱의 별도 H2
- 운영 DB: 각 앱의 별도 PostgreSQL
- 배포: Docker, Railway

## 로컬 실행

두 저장소를 함께 실행해야 전체 기능을 사용할 수 있습니다. 기본 프로파일은 양쪽 모두 H2 TCP를 사용하므로 각 DB 서버도 먼저 실행해야 합니다.

```powershell
# 1. jhg-wms-project에서 WMS 실행(:8081)
.\gradlew.bat bootRun

# 2. 이 저장소에서 OMS 실행(:8080)
.\gradlew.bat bootRun
```

기본 연결값은 다음과 같습니다.

```text
WMS_BASE_URL=http://localhost:8081
WMS_BASIC_USER=wms
WMS_BASIC_PASSWORD=wms
```

WMS의 모든 화면과 API는 HTTP Basic 인증을 요구합니다. 두 앱의 `WMS_BASIC_USER`와 `WMS_BASIC_PASSWORD`가 반드시 같아야 합니다.

OMS 기본 프로파일은 H2 TCP(`~/hgpage`)와 `ddl-auto: update`를 사용합니다. 스키마를 초기화하려면 `--spring.profiles.active=local`로 실행합니다. 테스트는 임베디드 H2를 사용하므로 외부 DB가 필요 없습니다.

### OMS 초기 계정

| 구분 | 이메일 | 비밀번호 |
|------|--------|----------|
| 관리자 | `admin@admin.com` | `${ADMIN_PASSWORD:1111}` |
| 일반회원 | `twin10240@naver.com` | `1111` |

OMS는 상품 20개를, WMS는 같은 `productId` 1~20의 초기 재고를 각자 시드합니다.

## Railway 배포

- OMS와 WMS는 각자 Docker 이미지와 PostgreSQL을 사용합니다.
- OMS는 Flyway와 `ddl-auto: validate`, WMS는 현재 `ddl-auto: update`를 사용합니다.
- OMS→WMS 호출은 `WMS_BASE_URL=http://jhg-wms-project.railway.internal:8081` private networking을 유지합니다.
- WMS 공개 관리자 URL은 `https://jhg-wms-project-production.up.railway.app`이며 HTTP Basic 인증이 필요합니다.
- 두 서비스에 동일한 `WMS_BASIC_USER`·`WMS_BASIC_PASSWORD`를 설정해야 합니다.
- 자격증명을 변경할 때는 새 자격증명을 전송하는 OMS를 먼저 배포하고 WMS를 배포합니다. 순서를 바꾸면 OMS→WMS 호출이 모두 401로 실패합니다.

## 테스트

```powershell
.\gradlew.bat test
```

2026-07-14 기준 OMS 전체 테스트 **163건 통과**, 실패·오류·스킵 0건입니다. Mockito 단위 테스트, `@WebMvcTest`, `@DataJpaTest`, 어댑터와 백오더 복구 테스트를 포함합니다.

## 문서

- [기획서](docs/기획서.md) — 비전·시나리오·로드맵
- [OMS 경계 문서](src/main/java/com/jhg/hgpage/oms/README.md) — 주문과 WMS 연동 흐름
- [리스크](risk.md) — 현재 열린 운영 위험
- [CLAUDE.md](CLAUDE.md) — 상세 아키텍처·변경 이력·개발 규칙
