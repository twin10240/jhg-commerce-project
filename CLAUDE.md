# CLAUDE.md

이 파일은 이 저장소에서 작업하는 Claude Code(및 개발자)를 위한 가이드입니다.

## 프로젝트 개요

Spring Boot 기반 **학습용 커머스 웹 애플리케이션** (`hgpage`, group `com.jhg`).
김영한 JPA 강의 스타일의 도메인 설계(Order/OrderItem/Delivery/Inventory)에 Spring Security 인증,
QueryDSL, 장바구니 REST API를 직접 확장한 구조.

## 프로젝트 비전 / 로드맵 (2026-06-12 확정)

최종 목표는 쇼핑몰 완성이 아니라 **미니 OMS + 별도 WMS 간 통신** 구현이다.
핵심 컨셉: **재고가 없어도 주문이 가능해야 한다(백오더)**. 작업 방향을 판단할 때 이 비전을 기준으로 삼을 것.

- ~~**Phase 1 — 주문 정책 전환 (OMS화, 모놀리스 내부)**~~ ✅ 완료(2026-06-12): 주문 시 즉시 차감 → 예약(reserve) 모델로 전환.
  `availableQty = onHandQty − reservedQty`, 가용분 없으면 거부 대신 `BACKORDERED` 접수,
  실제 차감은 출고 시점, 입고 시 백오더 자동 할당(FIFO). 품절 UI를 "입고 대기 — 주문 가능"으로 전환.
- ~~**Phase 2 — 모듈 경계**: 패키지를 `oms/`(주문·고객)와 `wms/`(재고·발주·입고·출고)로 재배치, 서비스 인터페이스로만 통신.~~ ✅ **코어 재배치 완료(2026-06-18)**: domain·repository·service를 `contract/`·`catalog/`·`oms/`·`wms/`로 수직 분할, OMS↔WMS는 `contract/` 포트로만 통신(양방향 의존/순환 없음). (사전작업 2026-06-17 완료: ① WMS→OMS 백오더 역방향 결합을 `StockReplenishedHandler` 포트로 의존성 역전 ② OMS→WMS 재고 호출을 `InventoryPort`로 분리 + 전용 할당 컴포넌트로 순환 제거. 2026-06-18: ③ 가용 재고 조회를 `InventoryQueryPort`로 포트화 ④ 코어 패키지 물리 분리. 아래 해결됨 참고.) **web 계층 분리 완료(2026-06-22)**: 컨트롤러·폼·뷰DTO도 컨텍스트별로 수직 분할 — 응답 DTO→`oms/dto`, 폼→`oms/web/form`·`wms/web/form`, OMS 컨트롤러·api→`oms/web/{controller,api}`, `AdminController`→`OrderAdminController`(oms)+`InventoryAdminController`(wms) 분할, 교차/중립 `Home`·`Main`→공용 `web/`. 순수 이동(동작·DB·화면·URL 불변).
- **Phase 3 — WMS 물리 분리**: 별도 Spring Boot 앱 + REST 통신(출고 요청/재고 조회/입고 통지/출고 콜백).
  재고의 단일 진실 공급원은 WMS, OMS는 판매가용 재고. 멱등 API·보상 처리 학습 포인트.
  **사전작업(0번) 완료(2026-06-25)**: `catalog/Product ↔ wms/Inventory` JPA 양방향 객체그래프 절단 — `Inventory`가 `productId: Long`만 보유(역참조 제거), `Product`는 재고를 전혀 모름(`inventory` 필드·`addStock` 제거). 재고 접근을 객체그래프(`product.getInventory()`)에서 `InventoryRepository.findByProductId(In)` 경로로 전면 이전, `Product↔Inventory` cascade 시드를 명시 저장으로 전환, 관리자 재고화면을 `InventoryRow` DTO 조립으로 이전. catalog가 wms를 import하지 않게 됨(의존 단방향). 동작·DB데이터·화면·URL 불변. 이제 WMS를 별도 DB로 떼면 `InventoryRepository` 뒷단만 REST로 갈아끼우면 된다(멘탈모델: OMS=지점/WMS=본사). 아래 해결됨 참고.
  **선결작업 S0 완료(2026-06-29)**: `wms/`가 `catalog`를 한 줄도 import하지 않게 정리 + 재고 변경 포트를 `orderId`로 멱등화. ① `PurchaseOrderItem→Product` 그래프 절단(`productId` 소유, 발주 검증을 `InventoryRepository` SKU 존재로) ② 관리자 재고/발주 화면을 `InventoryRow(productId, onHandQty)`로 축소(상품명·가격 미표시 — WMS는 모름) ③ 주문당 멱등 예약 원장 `Reservation`(RESERVED→SHIPPED/RELEASED, orderId 유니크) 도입 ④ `InventoryPort.reserveAll/shipAll/releaseAll`에 `orderId` 추가 + 원장 기반 멱등 구현(재예약/재출고/재해제 no-op), `OrderService.order`는 예약 전에 저장해 orderId 확보. wms↔catalog 양방향 import 0 검증. **의도된 동작 변경 2가지**: 재고화면이 상품명·가격 대신 productId 표시 / 발주 검증이 catalog→재고 SKU 기준. 설계/계획: `docs/superpowers/{specs,plans}/2026-06-26-phase3-*`. `gradlew build` 통과. **영속 H2(TCP)는 `purchase_order_item.product_id` FK 제거 + `reservation` 테이블 추가로 `--spring.profiles.active=local` 1회 리셋 필요**(TDD 임베디드 H2는 무관). 이제 S1~ 물리 분리 착수 가능.
  **S1 완료(2026-07-01, master 병합됨)**: 별도 WMS 앱 `C:\study\jhg-wms-project`(`com.jhg.wms`, Java 21, Spring Boot 3.5.5, 포트 8081, 자체 H2 `~/jhg-wms`) 신설 — 재고 조회(`GET /api/inventory/availability?productIds=`) + 재고 쓰기(reserve/ship/release/adjust) REST 제공. OMS는 인프로세스 `Inventory`·`Reservation` 도메인/서비스/레포지토리 삭제하고 `WmsInventoryAdapter`(InventoryPort 구현)·`WmsInventoryQueryAdapter`(InventoryQueryPort 구현, RestClient, `wms.base-url` 설정)로 전환. WMS 다운 시 메인 페이지 500 방지 fallback(가용수량 0·reserveAll 백오더 접수) 포함.
  **S2 완료(2026-07-01 구현, 2026-07-06 통합검증)**: 발주/입고(PurchaseOrder)를 WMS로 이사 — WMS에 PO 도메인(ORDERED→RECEIVED, 중복입고 방어)·서비스(입고 시 재고 자동 증가)·REST(`GET/POST /api/purchase-orders`, `POST /api/purchase-orders/receive` — 404/409 분기)·관리자 Thymeleaf UI(`8081/admin/inventory`·`/admin/purchase-orders`) 신설. OMS `InventoryAdminController`는 `WmsPurchaseOrderAdapter`+`WmsInventoryAdapter`로만 동작, 인프로세스 `wms/{domain,service,repository}` 완전 삭제(잔존: adapter·dto·web/controller·web/form). OMS DB local 리셋 완료(inventory·reservation·purchase_order* 테이블 OMS에서 제거). 두 앱 동시 기동 통합 검증 통과: OMS 재고조정→WMS 반영, OMS 발주 생성→WMS 저장→입고→가용수량 증가→OMS 메인 그리드 표시. 플랜: `docs/superpowers/plans/2026-07-01-phase3-s2-write-extraction.md`.
  **S3 완료(2026-07-07)**: WMS→OMS 재고 보충 콜백(채널3) — WMS `InventoryService.adjust`(재고 증가 3경로가 전부 통과하는 단일 지점)가 `delta > 0`이면 커밋 후(`TransactionSynchronizationManager` afterCommit) `POST /api/replenishments {productIds}`를 OMS에 발화(`OmsReplenishmentNotifier`, try-catch best-effort — OMS 다운 시 조정은 성공·통지만 warn 유실). OMS는 `ReplenishmentApiController`(permitAll + CSRF 예외)가 수신해 `StockReplenishedHandler.onReplenished`(=`BackorderAllocator`)로 위임 — 통지는 자연 멱등(사실 전달뿐, 승격은 BACKORDERED 상태 기반 + `Reservation` orderId UNIQUE). `InventoryAdminController`의 인프로세스 직접 트리거 제거(OMS adjust 승격은 라운드트립: OMS→WMS adjust→콜백→승격). 주문 취소 경로 승격은 OMS 내부 `BackorderAllocator.allocate` 직접 호출 그대로(경계 안 넘음). 두 앱 통합 검증 통과(입고→승격, 라운드트립 승격, OMS 다운 best-effort). 스키마 변경 없음. 플랜: `docs/superpowers/plans/2026-07-07-phase3-s3-replenishment-callback.md`. 남은 것 = S4: WMS 다운 회복탄력성(보상 스윕·타임아웃/재시도).
- (선택) Phase 4 — REST → 이벤트/메시지 기반 전환.

> 주의: "품절 시 구매 차단 강화" 같은 쇼핑몰 방향 개선은 이 비전과 충돌한다. 기존 품절 UX(C)는 Phase 1에서 백오더 UX로 대체 예정.

## 기술 스택

| 영역 | 사용 기술 |
|------|-----------|
| 언어/빌드 | Java 17, Gradle, Spring Boot 3.5.5 |
| 영속성 | Spring Data JPA (Hibernate), QueryDSL 5.0 (jakarta) |
| DB | H2 (TCP 모드 `jdbc:h2:tcp://localhost/~/hgpage`), `ddl-auto: update`(기본) / `local` 프로파일은 `create`(리셋용). 테스트는 임베디드 H2(`src/test/resources/application.yml`) |
| 보안 | Spring Security 6 + BCrypt(strength 12), Thymeleaf-Security 통합 |
| 화면 | Thymeleaf + Bootstrap |
| 기타 | Lombok, p6spy(SQL 로깅), AOP 시간측정 |

## 빌드 / 실행 명령

```powershell
# 빌드
.\gradlew.bat build

# 테스트
.\gradlew.bat test

# 단일 테스트 클래스
.\gradlew.bat test --tests "com.jhg.hgpage.service.OrderServiceTest"

# 애플리케이션 실행 (기본: ddl-auto update — 데이터 보존)
.\gradlew.bat bootRun

# 스키마 리셋 + 재시드 (local 프로파일: ddl-auto create)
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

> 주의: `bootRun` 전에 H2 TCP 서버가 `localhost`에 떠 있어야 한다(`application.yml`의 datasource URL 참고).
> 테스트는 임베디드 H2를 쓰므로 TCP 서버 없이 돈다.
> QueryDSL Q타입(`QMember`, `QCartItem` 등)은 `annotationProcessor`가 빌드 시 `generated/`에 생성한다.
> `build/reports/problems/problems-report.html`이 깨진 ACL로 삭제 불가라 Gradle이 리포트를 못 덮어써 `FileAlreadyExistsException`이 났었음. `gradle.properties`의 `org.gradle.problems.report=false`로 리포트 생성을 꺼서 우회 중(플래그 불필요). 근본 해결은 관리자 권한 터미널에서 해당 파일 삭제.

## 배포 (Railway)

- **빌드**: 루트 `Dockerfile`(멀티스테이지 — JDK17 빌드 / JRE17 실행). Railway는 Dockerfile 존재 시 Nixpacks 대신 이걸 사용. `.dockerignore`로 `build/`·`.git/` 등 제외(깨진 ACL `problems-report.html`도 컨텍스트에서 빠져 Docker 빌드는 `clean`이 무해).
- **포트**: `application.yml`의 `server.port: ${PORT:8080}` — Railway가 주입하는 `${PORT}`에 바인딩(로컬은 8080).
- **DB**: `prod` 프로파일(PostgreSQL). `SPRING_PROFILES_ACTIVE=prod` + Railway PostgreSQL 플러그인이 주입하는 `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD` 참조. `ddl-auto: update`로 첫 기동 시 스키마 생성 → `initDb`가 빈 DB 시드. SQL 로깅·p6spy off. (로컬/테스트는 그대로 H2.)
- **드라이버**: `build.gradle`에 `runtimeOnly 'org.postgresql:postgresql'`(H2와 공존, prod에서만 사용). `devtools`는 `developmentOnly`라 운영 jar에 미포함.
- **관리자 비밀번호**: 코드에 박지 않고 `ADMIN_PASSWORD` 환경변수로 주입(`initDb`, 로컬 기본값 `1111`). 운영은 Railway 앱 서비스 Variables에 강한 값 설정. 시드는 빈 DB에만 돌므로 **기존 DB의 관리자 비번은 코드 수정만으로 안 바뀜** → 비번 적용엔 DB 리셋(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`) 후 재배포 필요. H2 콘솔은 prod에서 미사용(Postgres).

## 아키텍처 (계층 구조)

```
Controller (Home/Auth/Main/Cart/Order)  +  CartApiController(REST)
        │
Service (Account/Member/Product/Cart/Order)   ← @Transactional(readOnly=true) 기본
        │
Repository (Spring Data JPA + QueryDSL 별도 클래스 *RepositoryQuery)
        │
Domain (Account ─ Member ─ Cart ─ CartItem / Order ─ OrderItem ─ Delivery / Product ─ Inventory)
```

### 패키지 구조 (`src/main/java/com/jhg/hgpage`)

**바운디드 컨텍스트 코어** (2026-06-18 코어 재배치 + 2026-06-22 web 계층 분리 완료 — domain·repository·service·web을 컨텍스트별로 수직 분할):
- `contract/` — **OMS↔WMS 경계 포트**(컨슈머가 아니라 공용에 둬 양방향 의존/순환 차단): `InventoryPort`(OMS→WMS 재고 예약/해제/출고), `InventoryQueryPort`(OMS→WMS 가용수량 조회, CQRS 읽기), `StockReplenishedHandler`(WMS→OMS 재고 보충 통지 콜백). oms·wms는 서로 직접 import하지 않고 contract에만 의존.
- `catalog/` — 상품 카탈로그(OMS·WMS 공통 참조): `Product`, `ProductRepository`, `ProductService`(메인 그리드 카드 조립 — 카탈로그 + `InventoryQueryPort` 가용수량), `ProductCardDto`.
- `oms/` — 주문·장바구니·고객. `domain/`(`Order`/`OrderItem`/`Delivery`/`Cart`/`CartItem`/`Account`/`Member`/`Address` + `enums/`(OrderStatus/DeliveryStatus)), `repository/`(주문·장바구니·회원 Spring Data + QueryDSL `*RepositoryQuery` + `SearchOption`), `service/`(`OrderService`/`OrderAllocationService`/`CartService`/`AccountService`/`MemberService`/`BackorderAllocator`), `dto/`(응답 뷰DTO `OrderDto`/`OrderDetailDto`/`AdminOrderDto`/`CartItemDto` — repository→web 역의존 회피 위해 web 밑이 아닌 컨텍스트 레벨), `web/`(`controller/`: `AuthController`/`CartController`/`OrderController`/`OrderAdminController`, `api/`: `CartApiController`/`OrderApiController`, `form/`: `CheckOutForm`/`OrderRequest`/`SignUpForm`).
- `wms/` — 재고·발주·입고·출고. `domain/`(`Inventory`/`PurchaseOrder`/`PurchaseOrderItem` + `enums/PurchaseOrderStatus`), `repository/`(`PurchaseOrderRepository`), `service/`(`InventoryService`(=`InventoryPort`+`InventoryQueryPort` 구현)/`InventoryAdjustmentService`(재고조정+승격 트리거, 순환 회피 위해 `InventoryPort` 구현체와 분리)/`PurchaseOrderService`), `web/`(`controller/InventoryAdminController`(재고조정·발주·입고), `form/PurchaseOrderForm`).

> `AdminController`는 OMS(배송)·WMS(재고·발주) 혼재를 2026-06-22에 `OrderAdminController`(oms)·`InventoryAdminController`(wms)로 분할. URL은 전부 불변(`/admin/orders*`→oms, `/admin/inventory*`·`/admin/purchase-orders*`→wms).

**공용 web / 인프라** (컨텍스트 횡단):
- `aop/` — `TimeTraceAop` (메서드 실행시간 로깅)
- `web/` — 교차/중립(BFF 성격) 화면 컨트롤러 `HomeController`(`/`), `MainController`(`/main` — 상품 그리드 + 내 주문 + ADMIN 재고). 특정 컨텍스트에 속하지 않아 공용에 둠.
- `config/` — `SecurityConfig`, `QueryDslConfig`
- `domain/` — `dto`(`UserPrincipal`), `enums/`(`Role` — 인증 공용. OrderStatus/DeliveryStatus는 oms로, PurchaseOrderStatus는 wms로 이동). 엔티티·뷰DTO는 모두 컨텍스트 패키지로 이동.
- `exception/` — `NotEnoughStockException`, `EntityNotFoundException`, `DuplicateEmailException`, `GlobalExceptionHandler`
- `initDb.java` — `@PostConstruct`로 초기 계정/상품 시드 (멱등: Account가 하나라도 있으면 skip)

### 도메인 모델 핵심
- **Account ↔ Member (1:1 분리)**: 인증정보(Account: email/password/role)와 회원정보(Member: name/phone/address)를 분리. `UserPrincipal`이 둘을 합쳐 Spring Security 주체로 동작.
- **Member → Cart (1:1, cascade ALL)**: `Member.createUser()` 시 장바구니 자동 생성, `createAdmin()`은 장바구니 없음.
- **Order → OrderItem → Product / Delivery**: 주문은 **예약/백오더 모델** — `OrderAllocationService.allocate(order)`가 `InventoryPort.reserveAll`(원자적 전부-아니면-실패)로 전 라인 예약을 시도해 성공이면 ORDER, 부족하면 거부 없이 `BACKORDERED` 접수. **`Order` 도메인은 상태 전이(`markOrdered/markBackordered/cancel/completeDelivery`)만 담당하고 재고 연산(예약/해제/출고)은 모두 서비스가 `InventoryPort`(WMS)에 위임**한다(객체 그래프 `getProduct().getInventory()` 결합 제거 — Phase 2 사전 정지작업). 취소는 예약 해제(ORDER만), 출고(`completeDelivery`)에서 비로소 실물 차감. 백오더는 출고 불가, 입고/재고증가 시 `BackorderAllocator`가 FIFO로 재할당해 ORDER로 승격.
- **Product ↔ Inventory (1:1)**: 재고를 별도 엔티티로 분리. `onHandQty`(실물)/`reservedQty`(예약)/`availableQty`(가용=실물−예약, 계산값). 도메인 연산은 `reserve/release/ship`만 노출(OMS는 `InventoryPort`를 통해서만 호출). `@Version` 낙관적 락.
- **PurchaseOrder → PurchaseOrderItem → Product**: 관리자 발주(`ORDERED`) → 입고(`receive()`: 재고 증가 + `RECEIVED`). 중복 입고 거부.

### 주요 흐름
- **회원가입**: `AuthController` → `AccountService.signUp(member, account)` 단일 트랜잭션으로 Member+Account 원자적 저장. 이메일 중복 시 `DuplicateEmailException` → 컨트롤러가 signup 폼의 email 필드 에러로 안내.
- **로그인**: `AccountService.loadUserByUsername`(email 조회) → `UserPrincipal`. 로그인 파라미터는 `email`/`password`, 성공 시 `/main`. 로그인 화면 템플릿은 `home.html`.
- **메인**: `GET /main` — 상품 페이징·검색(`keyword`, size=10, sort=id) + 내 주문 목록 + (ADMIN이면) 전체 재고 목록. 페이지 네비게이션은 숫자 클릭형(`이전 | 1 … 4 [5] 6 … 12 | 다음`, 윈도우 최대 5개) — 컨트롤러가 0-based `beginPage`/`endPage`를 모델로 내려준다.
- **장바구니**: `CartApiController`(REST) — fetch 호출용. 담기/수량변경/삭제/카운트. 모든 응답에 최신 장바구니 count 반환.
- **주문**: 메인·장바구니 → `POST /orders/checkout-form`(주문서 생성) → `POST /orders/checkout`(확정, `@Valid CheckOutForm` — 상품 0개·수량 0 검증 있음). 주문서에서 상품별 체크박스(`ProductDto.selected`, 기본 true)로 일부만 골라 주문 가능 — 체크된 상품만 OrderLine으로 변환되고, 전부 해제 시 `product` 필드 에러. 장바구니에서 온 주문서는 `CheckOutForm.fromCart`(hidden) = true — 주문 확정 시 `OrderService.orderFromCart()`가 주문 생성과 함께 주문된 상품만 장바구니에서 제거(단일 트랜잭션). 바로 구매는 장바구니 불변. `GET /orders/me`는 새로고침 폼의 JS 폴백(`redirect:/main`) — 실제 새로고침은 `GET /api/orders/me`(JSON) fetch.
- **주문 상세/취소**: `GET /orders/{id}`(`orderview.html`) — `findDetailById` fetch join 단건 조회, **본인 주문만**(타인/없는 주문은 404로 존재를 숨김, IDOR 방지). `POST /orders/{id}/cancel` — `Order.cancel()` 호출(재고 복구, 배송완료·재취소 거부 가드), 성공/실패를 flash로 상세에 표시. 취소 버튼은 `OrderDetailDto.cancelable`(ORDER 상태 + 배송완료 전)일 때만 노출.
- **관리자 배송 관리**: `GET /admin/orders` — 전체 주문 목록(최신순), READY 건에만 "배송완료" 버튼 → `POST /admin/orders/complete-delivery` → `Order.completeDelivery()`(READY→COMP, 취소된 주문·중복 처리 거부).
- **가격 정책**: 가격은 항상 서버에서 `Product`를 재조회해 사용한다. 클라이언트가 보낸 가격을 신뢰하지 않는다. 주문/장바구니에 당시 가격을 스냅샷(`orderPrice`/`productPrice`)으로 저장.

### 초기 시드 계정 (`initDb`)
- 관리자: `admin@admin.com` / **`${ADMIN_PASSWORD:1111}`** (ROLE_ADMIN, 장바구니 없음). 비번은 `ADMIN_PASSWORD` env로 주입(로컬 기본 `1111`, 운영은 Railway에 강한 값). `initService` 생성자가 `@Value`로 받음 — 테스트는 생성자에 직접 주입.
- 일반회원: `twin10240@naver.com` / `1111` (ROLE_USER, 데모 계정이라 하드코딩 유지)
- 상품 20개("상품1"~"상품20", 가격 10000~29000) + 각 재고 자동 생성
- **빈 DB에만 시드된다**(Account 존재 시 skip). 처음부터 다시 시드하려면 `local` 프로파일로 실행.

## 컨벤션 / 주의사항

- 서비스는 클래스 레벨 `@Transactional(readOnly = true)`, 쓰기 메서드에만 `@Transactional` 부여.
- 엔티티는 정적 팩토리 메서드로 생성(`Member.createUser`, `Order.createOrder`, `OrderItem.createOrderItem`).
- 엔티티 기본 생성자는 `@NoArgsConstructor(access = PROTECTED)`로 막음.
- 복잡한 조회는 Spring Data 파생 쿼리 대신 `*RepositoryQuery` QueryDSL 클래스에 작성.
- 응답 DTO는 컨텍스트 레벨 `oms/dto`(repository→web 역의존 회피), 요청 폼은 web 계층 `oms/web/form`·`wms/web/form`.
- ID 조회 실패는 `Optional.get()` 대신 `orElseThrow(() -> new EntityNotFoundException(대상, id))`. 전역 예외 처리는 `exception/GlobalExceptionHandler`(`/api/**`는 ProblemDetail JSON, 화면은 `error.html` 또는 flash 리다이렉트).
- 빌드/테스트 실행 시 `JAVA_HOME`이 JDK 17+를 가리켜야 함(시스템 기본이 Java 8): `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"`.

## 테스트 현황

신규 테스트는 **신세대 패턴**을 따를 것(Mockito 단위 / `@WebMvcTest` 슬라이스 / `@DataJpaTest` 자체 데이터 + 단언).

- **구세대 잔재**: 2026-06-15 전면 교체 완료. assertion 없이 출력만 하거나 시드·하드코딩 ID(2L)에 의존하던 `OrderServiceTest`·`ProductServiceTest`·`OrderRepositoryTest`·`CartRepositoryTest`·`MemberRepositoryTest`를 신세대로 교체(`MemberServiceTest`는 `MemberServiceFindMemberTest`와 중복이라 삭제, `ProductServiceTest`는 `ProductInventoryPersistenceTest`로 대체). 남은 예외는 `OptionalTest`(Java API 연습장)·`PassWordTest`(bcrypt 해시 출력)뿐 — 프로젝트 검증과 무관한 연습 테스트로 사용자 결정에 따라 보존(#21). (2026-06-12부터 테스트는 임베디드 H2를 쓰므로 TCP 서버 없이 돌고 실 DB를 오염시키지 않는다.)
- **신세대** (권장 패턴): `ProductServiceFindPageTest`(Mockito 단위), `OrderControllerTest`(Validator + ArgumentCaptor 단위), `OrderControllerMvcTest`/`MainControllerMvcTest`/`CartApiControllerMvcTest`(`@WebMvcTest` 슬라이스, Security `user()`·`csrf()` 포함), 예외 처리 단위 테스트(`MemberServiceFindMemberTest`, `CartServiceExceptionTest`, `OrderServiceExceptionTest`, `AccountServiceLoadUserTest`), 회원가입(`AccountServiceSignUpTest`, `AuthControllerMvcTest`), 임베디드 H2 통합(`AccountServiceTest`, `CartServiceTest`, `InitDbTest` — 자체 데이터 생성 + 롤백). 격리되어 있고 검증이 명확함.

## 알려진 이슈 / 기술 부채 (작업 시 참고)

### 해결됨 (2026-06-25)
- ~~Phase 3 선결작업(0번): `Product↔Inventory` 객체그래프 결합~~ — WMS 물리 분리를 막던 진짜 장벽은 경계 포트가 아니라 **데이터 모델의 양방향 객체그래프**(`catalog/Product —@OneToOne(inventory_id)→ wms/Inventory`, 역참조 `mappedBy`)였다. WMS가 다른 DB로 이사 가면 이 JPA 관계는 존재할 수 없으므로(WMS엔 Product 테이블 없음), 모놀리스 내부에서 미리 끊어 **WMS가 재고를 `productId`로만 소유**하게 했다. **expand-contract 5단계**(각 커밋 build green): ① `Inventory.productId` + `InventoryRepository`(`findByProductId(In)`) 추가(기존 그래프 유지) ② `InventoryService`(포트 구현)를 `product.getInventory()` → `inventoryRepository.findByProductIdIn` 경로로 이전 ③ `InventoryAdjustmentService`·입고 경로 이전 + `PurchaseOrder.receive()`를 상태전이만 남기고 재고증가는 `PurchaseOrderService`가 위임(누락 재고는 `EntityNotFoundException("Inventory")` 가드) ④ 관리자 재고화면(`/admin/inventory`)을 `product.inventory` 객체그래프 대신 `InventoryRow{id,name,price,onHandQty}` DTO 조립(`InventoryService.findInventoryRows`, 카탈로그+WMS합성)으로 이전 ⑤ **contract**: `Product.inventory`·`addStock`·`Inventory.product`·`ProductRepository/ProductService.findAllWithInventory` 제거, `initDb` cascade 시드 → `Inventory.create(productId)` 명시 저장(`InventoryRepository` 주입). **결과: catalog가 wms를 import하지 않음(의존 단방향), 동작·DB데이터·화면·URL 불변.** 이제 WMS를 별도 DB/앱으로 떼면 `InventoryRepository`·`InventoryQueryPort` 뒷단만 REST로 갈아끼우면 된다(멘탈모델: OMS=지점/WMS=본사 — 본사가 재고 마스터+입고, 지점은 재고0+주문/백오더 명단). **주의: FK가 `product.inventory_id`→`inventory.product_id`로 이동 → 영속 H2(TCP)는 `--spring.profiles.active=local`로 1회 리셋 필요**(`ddl-auto: update`가 FK 이동을 못 따라감). 테스트(TDD)는 임베디드 H2라 무관. 영향 테스트 다수 정리(Order 계열 `setInventory` 픽스처 제거 ~12파일, `OrderTest` 재고단언 제거, `OrderRepositoryBackorderTest` 상태 명시 셋업, cascade·죽은쿼리 테스트 `ProductInventoryPersistenceTest`·`ProductRepositoryTest` 삭제 → `InventoryRepositoryTest`로 대체). 설계/계획 문서: `docs/superpowers/{specs,plans}/2026-06-25-*`. `gradlew build` 전체 통과.

### 해결됨 (2026-06-18)
- ~~가용 재고 조회 결합(OMS 판매 화면이 `Product→Inventory` 객체 그래프 직접 조회)~~ — **Phase 2 마지막 OMS→WMS 읽기 결합 제거**: 메인 상품 그리드가 `p.inventory.availableQty`로 WMS 재고를 객체 그래프로 들추던 것을 **조회 전용 포트 `InventoryQueryPort.availableByProductIds(ids)→Map`**(변경 포트 `InventoryPort`와 분리, CQRS)로 바꿈. `ProductService.findPage`(`Page<Product>`, 재고 fetch join) → **`findCardPage`**(`Page<ProductCardDto>`): 카탈로그 페이지(id/name/price) + 포트 가용수량을 합쳐 카드 DTO로 조립, 화면은 DTO만 보고 재고 객체그래프를 안 탐. 죽은 쿼리 `findPageWithInventory`/`findPageByNameWithInventory` + 그 테스트 제거. Phase 3의 "재고 조회 GET" REST와 1:1 대응. 순수 리팩터링 — 동작·DB·화면 불변. 테스트(TDD RED→GREEN): `InventoryServiceTest`(+가용수량 조회), `ProductServiceFindPageTest`(재작성 — 포트 합성·카드 매핑·품절 0 기본), `MainControllerMvcTest`(DTO 전환), `ProductRepositoryTest`(죽은 테스트 제거). `gradlew build` 전체 통과.
- ~~코어 패키지 물리 분리 (Phase 2 본작업)~~ — domain·repository·service를 **바운디드 컨텍스트별 수직 분할**: `contract/`(경계 포트 3종 — 공용에 둬 oms↔wms 양방향 의존/순환 차단) → `catalog/`(Product 카탈로그, OMS·WMS 공통) → `wms/`(재고·발주: Inventory·PurchaseOrder*·해당 서비스/리포지토리) → `oms/`(주문·장바구니·고객: Order·Cart·Account·Member 등·해당 서비스/리포지토리). 4커밋으로 슬라이스 진행(각 `gradlew build` green). 컨텍스트별로 묶여 이동한 클래스는 same-package 참조 유지, 경계를 넘는 참조만 import 보강. **순수 이동 — 동작·DB·화면 불변**(Spring 컴포넌트 스캔은 루트 `com.jhg.hgpage` 기준이라 설정 변경 불필요, QueryDSL Q타입은 새 패키지로 재생성, `main.html`의 SpEL `T(...OrderStatus)` 경로 1곳 갱신). **사용자 결정: 코어만 먼저** — 컨트롤러·DTO·config·exception·initDb는 공용 web/infra 계층에 유지(`Role` enum은 인증 공용이라 `domain.enums`에 잔류). `admin` 컨트롤러는 WMS/OMS 혼재라 컨텍스트 분리 보류. 전체 테스트 그대로 green(171건).

### 해결됨 (2026-06-17)
- ~~백오더 역방향 결합(WMS→OMS 직접 의존)~~ — **Phase 2 사전 정지작업**: 재고를 늘리는 WMS 측(`PurchaseOrderService.receive` 입고·`InventoryService.adjust` 재고 증가)이 OMS의 `BackorderAllocator`를 직접 주입·호출하던 역방향 의존을 **콜백 포트 인터페이스로 의존성 역전**. `StockReplenishedHandler.onReplenished(Collection<Long>)`(신설, "재고 보충됐다"는 사실만 통지 — 백오더 개념 모름)를 WMS 측이 의존하고 `BackorderAllocator`가 구현(`onReplenished`→기존 `allocate` 위임, 로직 불변). 이로써 의존 화살표가 WMS→OMS에서 OMS→WMS(정상 방향) 한 방향으로 정리돼 Phase 2 패키지 분리 시 순환 의존이 안 생긴다. Phase 3에서 이 포트가 "입고 후 OMS에 통지하는 콜백 REST"로 진화하는 지점. **`OrderService.cancelOrder`의 백오더 재할당은 OMS 내부 호출이라 구체 타입 `BackorderAllocator`·`allocate` 그대로 유지**(경계를 안 넘음). 순수 리팩터링 — 외부 동작·DB·화면 불변. 테스트(TDD RED→GREEN): `PurchaseOrderServiceTest`·`InventoryServiceTest`의 mock을 `StockReplenishedHandler`/`verify(...).onReplenished(...)`로 갱신(단언 의도 불변), `BackorderAllocatorTest`·`OrderServiceCancelTest`는 구체 타입이라 불변. `gradlew build` 전체 통과.
- ~~결합점 1: OMS→WMS 재고 호출이 `Order` 엔티티 안 객체 그래프에 박혀 있음~~ — **Phase 2 사전 정지작업(3슬라이스)**: `Order.completeDelivery/cancel/allocate`가 `orderItem.getProduct().getInventory().ship/release/reserve`로 WMS 엔티티를 직접 타고 들던 것을 `InventoryPort`(배치형 `shipAll/releaseAll/reserveAll`, 구현 `InventoryService`)로 분리. ① 출고(ship) ② 예약 해제(release) ③ 예약(reserve)+할당 정책 순으로 진행. `Order`는 상태 전이(`markOrdered/markBackordered`)와 `quantitiesByProductId()` 집계만 남기고, 예약/가용성 판정은 신설 `OrderAllocationService`가 `InventoryPort.reserveAll`(원자적 전부-아니면-실패, check-then-act 경합 제거)로 수행. **순환 의존 제거**: reserve를 포트로 옮기면 `InventoryService.adjust→승격기→reserve→InventoryService` 생성자 순환이 생기므로, `adjust`+승격 트리거를 `InventoryAdjustmentService`로 분리해 `InventoryPort` 구현체가 `StockReplenishedHandler`에 의존하지 않게 함(되돌아오는 화살표 없음). 동작 불변 — 출고 시 실물 차감·취소 시 예약 해제·주문 시 예약/백오더 접수 모두 포트 경유로 동일. 테스트(TDD RED→GREEN): `InventoryPort`/포트 위임 검증, `OrderAllocationServiceTest`·`InventoryAdjustmentServiceTest` 신설, `InventoryServiceTest`·`BackorderAllocatorTest` 재작성(실 서비스 배선으로 실재고 FIFO·all-or-nothing 검증 보존), `OrderTest` 도메인은 상태/가드만. `gradlew build` 전체 통과(풀 컨텍스트 기동 = 순환 없음 확인). 다음: 결합점 잔여(가용 재고 조회) 정리 후 `oms/`·`wms/` 패키지 물리 분리.

### 해결됨 (2026-06-16)
- ~~N+1성 조회 루프 (#9)~~: `OrderService.order`가 주문 라인마다 `productRepository.findById`를 호출하던 N+1을 **`findAllById` 단건 일괄 조회(`Map<id,Product>`)**로 교체(누락 상품은 `EntityNotFoundException` 보존, 단일 사용처가 된 `findProduct` 헬퍼 제거). `OrderRepositoryQuery.findOrders`는 **컬렉션 fetch join + `limit(100)`** 조합이 limit을 메모리에 적용하던 문제(HHH90003004)를, 루트(`order`)만 limit으로 조회하고 `orderItems`는 batch fetch(`default_batch_fetch_size=100`, 운영·테스트 yml 모두 설정됨)에 맡기도록 재작성(컬렉션 fetch join·distinct 제거). #9가 함께 지적한 미사용 메서드 `OrderRepository.findOrdersByMemberId`(1:N fetch join + distinct 없음)도 죽은 코드라 제거. 둘 다 동작 불변·순수 성능 개선. 테스트(TDD RED→GREEN): `OrderServiceOrderTest`(신규 — `findAllById` 일괄 조회·`findById` 미사용 검증, 누락 상품 예외), `OrderRepositoryInMemoryPagingTest`(신규 `@DataJpaTest` + logback `ListAppender` — HHH90003004 메모리 페이징 경고 부재), `findById`를 스텁하던 기존 단위 테스트(`OrderServiceOrderFromCartTest`·`OrderServiceExceptionTest`)는 `findAllById` 스텁으로 갱신(동작 단언 불변). `gradlew build` 전체 통과.
- ~~`OrderController`의 findById 루프 N+1 (#19, #9 잔여)~~: `restoreCheckOutDisplay`(검증 실패 재렌더)와 `createCheckOutFrom`의 장바구니 분기(`checkout-form` 주문서 생성) 두 곳 모두 폼/선택 상품마다 `productRepository.findById`를 호출하던 루프를 **`findAllById` 일괄 조회**로 교체. `restoreCheckOutDisplay`는 id가 null이거나 없는 상품을 그대로 둠(기존 `ifPresent` 의미 보존), 장바구니 분기는 `order()`와 동일하게 없는 상품이면 `EntityNotFoundException`. 단건 구매 분기(`findProduct` 1회)는 N+1이 아니라 그대로 유지. 테스트(TDD): `OrderControllerMvcTest`(+2 — 검증 실패 재렌더·장바구니 다상품 주문서 생성에서 각각 `findAllById` 1회·`findById` 미사용 검증). `gradlew build` 전체 통과.
- ~~`@GeneratedValue` 전략 불일치 (#12)~~: IDENTITY 4종(`Account`/`Product`/`Cart`/`CartItem`)을 다수(7종)가 쓰던 기본 `@GeneratedValue`(AUTO/시퀀스)로 통일(사용자 결정). JDBC 배치 인서트 가능·시퀀스 기반 일관성 확보. 임베디드 H2 테스트는 매 실행 create-drop이라 새 시퀀스 스키마로 생성되며 기존 영속성 테스트들이 4개 엔티티의 ID 생성을 모두 검증. **주의: 영속 H2(TCP) DB는 IDENTITY 컬럼→시퀀스 전환을 `ddl-auto: update`가 못 따라가므로 `local` 프로파일로 1회 리셋 필요**(`--spring.profiles.active=local`). `gradlew build` 전체 통과.

### 해결됨 (2026-06-15)
- ~~구세대 테스트 전면 교체~~: assertion 없이 출력만 하거나 시드·하드코딩 ID(2L)에 의존하던 구세대 테스트 6종을 신세대 패턴으로 교체. `OrderServiceTest`→`OrderService.findOrders` 매핑 검증(Mockito), `OrderRepositoryTest`→실제 쓰이는 `OrderRepositoryQuery.findOrders` 검증(`@DataJpaTest`, 미사용 `findOrdersByMemberId` 대신), `CartRepositoryTest`·`MemberRepositoryTest`→자체 데이터 + 단언(`@DataJpaTest`, 하드코딩 ID 제거), `ProductServiceTest`→`ProductInventoryPersistenceTest`(Product↔Inventory cascade 영속화, `@Rollback(false)` DB 오염 제거), `MemberServiceTest`→삭제(중복: `MemberServiceFindMemberTest`가 커버). `OptionalTest`/`PassWordTest`만 연습 테스트로 보존(#21). `gradlew build` 전체 통과.
- ~~회원가입 서버 검증 부족 (#13)~~: `passwordConfirm`이 화면 JS로만 검증되고 name/phone/주소는 서버 검증이 없던 문제 해결. `SignUpForm`의 `passwordConfirm/name/phone/city/street/zipcode`에 `@NotBlank` 추가(email/password의 기존 `@NotEmpty`는 유지), `AuthController.signUp`에서 `@Valid` 직후 `password != passwordConfirm`이면 `result.rejectValue("passwordConfirm", ...)`로 비밀번호 일치를 서버 검증(이메일 중복 처리와 동일 패턴). `signup.html`의 주소 3필드(zipcode/city/street)에 `th:errors` 표시 블록 추가(name/phone과 동일 패턴). 테스트: `AuthControllerMvcTest`(+2 — 비밀번호 불일치 시 passwordConfirm 필드에러·가입 미시도, name/phone/주소 공백 시 필드에러·가입 미시도). `gradlew build` 전체 통과.
- ~~`CartItemDto` 필드 중복 (#10)~~: 3개 이름으로 2개 개념을 중복 표현하던 가격 필드를 정리. `productPrice`(= unitPrice, 미사용)·`cartPrice`(= lineTotalPrice, 컨트롤러 합계에서만 사용)·`getTotalPrice()`(= lineTotalPrice, 죽은 메서드)·`idx`(미사용 — 화면은 Thymeleaf `stat.index` 사용) 제거. 남은 필드: `memberId/cartId/productId/productName/unitPrice/lineTotalPrice/quantity`. `CartController`는 `getCartPrice()`→`getLineTotalPrice()`로 교체. `idx` 제거로 무의미해진 `CartService.findCartItemByMemberId`의 DTO 재빌드 루프를 리포지토리 결과 직접 반환으로 단순화(미사용 `IntStream`/`Collectors` import 제거). 사용처 0이 된 `@Builder`/`@AllArgsConstructor`도 제거(QueryDSL은 `@QueryProjection` 생성자만 사용). `@QueryProjection` 생성자 시그니처 불변이라 `QCartItemDto`·리포지토리 수정 불필요. 리팩터링(동작 불변) — 안전망인 `CartServiceTest`(단가/라인합계/합계) green 유지 + `gradlew build` 전체 통과.
- ~~`TimeTraceAop` 포인트컷 과다 + `System.out.println` (#7)~~: 포인트컷을 `com.jhg.hgpage..*`(전 메서드) → **service/controller/api 계층으로 한정**(`execution(* ...service..*) || ...controller..* || ...api..*`)해 domain getter·DTO·repository 트레이스로 인한 로그 폭증/성능 저하 제거. `System.out.println` → **SLF4J 로거(`@Slf4j`, `log.info`)** 로 전환(로그 레벨로 on/off 가능, 운영 시 `logging.level.com.jhg.hgpage.aop=WARN`으로 끔), `joinPoint.toString()` → `getSignature()`로 출력 간소화. 테스트: `TimeTraceAopTest`(AspectJProxyFactory + logback ListAppender — 서비스 계층 트레이스됨 / domain 계층 트레이스 안 됨 2건, 픽스처는 `service`/`domain` 패키지의 비-컴포넌트 클래스). `gradlew build` 전체 통과.
- ~~주문 취소 시 백오더 자동 승격 누락~~: 백오더 승격(`BackorderAllocator`)은 입고(`PurchaseOrderService.receive`)·재고 +조정(`InventoryService.adjust`)에서만 트리거됐고, **`ORDER` 취소로 예약(`release`)이 풀려 가용분이 늘어도** 그 상품을 기다리던 백오더는 방치됐다. `OrderService.cancelOrder`가 취소 직전 상태가 `ORDER`였을 때만(= 실제로 예약이 풀린 경우) 해당 주문 상품 id들로 `backorderAllocator.allocate()`를 호출하도록 수정. `BACKORDERED` 취소는 풀릴 예약이 없어 트리거하지 않음. 취소된 주문 자신은 이미 `CANCEL`이라 승격 후보(BACKORDERED 조회)에서 제외되어 안전. 테스트: `OrderServiceCancelTest`(ORDER 취소 트리거·BACKORDERED 취소 미트리거·다품목 id 전달 3건). `gradlew build` 전체 통과.

### 해결됨 (2026-06-12)
- ~~Phase 1: 주문 즉시 차감 → 예약/백오더 모델 전환~~: 4커밋(Phase1-1~4)으로 구현. ① `Inventory.reserve/release/ship/getAvailableQty` ② `Order.allocate()`(전부-아니면-백오더) + 취소=예약해제 + 출고 시 실물차감(`ship`), `OrderStatus.BACKORDERED` 추가, `OrderItem.createOrderItem` 순수화, 재고 부족 주문도 정상 접수라 장바구니 정리 수행 ③ `BackorderAllocator`(FIFO 승격) — 발주 입고·재고 증가 조정이 트리거, 조정 감소는 예약 침범 거부, 백오더 조회는 fetch join 컬렉션 잘림 회피 2단계 쿼리(`findBackordersContaining`) ④ 메인 카드 가용수량 기준 + "입고 대기"(버튼 활성), 상세/관리자 BACKORDERED 배지, 백오더도 취소 가능(`cancelable`). `Product.removeStock` 제거(`addStock`은 입고용 유지). 주의: 동시 주문이 같은 가용분을 두고 경합하면 늦은 쪽 `reserve()`가 `NotEnoughStockException`/낙관적 락으로 실패할 수 있음(재시도하면 백오더로 접수됨) — 기존 GlobalExceptionHandler가 처리.
- ~~배송 상태가 영원히 READY (관리자 배송 처리 부재)~~: `Order.completeDelivery()`(취소된 주문 거부 + 중복 처리 거부 가드) + `GET /admin/orders`(`admin/orders.html` — 전체 주문 목록, READY 건에만 배송완료 버튼) + `POST /admin/orders/complete-delivery`(param `orderId`, flash 안내). 목록은 `OrderRepositoryQuery.findAllForAdmin()`(member/delivery fetch join, orderItems는 batch fetch, id desc) → `AdminOrderDto`(completable 포함). 진입점: 메인 재고 탭·재고 관리 페이지에 "배송관리" 링크. 이로써 "배송완료 시 취소 불가" 가드와 상세 페이지 취소 버튼 숨김이 실전 동작. 테스트: `OrderTest`(+3), `OrderRepositoryAdminListTest`, `OrderServiceAdminTest`, `AdminControllerMvcTest`(+4, USER 403 포함).
- ~~주문 상세 페이지 부재("상세" 버튼 404) / 주문 취소 기능 부재~~: `GET /orders/{id}` + `orderview.html`(주문정보/상품 테이블/취소 버튼) + `POST /orders/{id}/cancel` 구현. `OrderRepositoryQuery.findDetailById`(QueryDSL, member/delivery/orderItems/product fetch join — 컨벤션대로 복잡 조회는 `*RepositoryQuery`에 배치. 1:N fetch join + `fetchOne()` 단건 조회는 Hibernate 6 메모리 중복 제거 덕에 안전하며 다품목 테스트로 검증), `OrderService.findOrderDetail/cancelOrder`(본인 확인 공통화 — 타인 주문은 404로 숨김). 같은 쿼리의 JPQL 버전을 `OrderRepository.findDetailById`에 **학습용 비교 자료로 의도적으로 보존**(javadoc에 명시 — 죽은 코드 청소 대상 아님). **`Order.cancel()`의 재취소 미차단 버그 수정**(CANCEL 상태 재호출 시 재고 이중 복구되던 것을 `IllegalStateException`으로 거부). 테스트: `OrderTest`(재취소 가드), `OrderRepositoryDetailTest`(`@DataJpaTest` fetch join), `OrderServiceDetailTest`(인가 6건), `OrderControllerMvcTest`(렌더링/404/취소 flash 5건).
- ~~내 주문 새로고침이 전체 페이지 이동~~: 새로고침 버튼의 폼 submit을 JS가 가로채 `GET /api/orders/me`(신규 `OrderApiController`, JSON) fetch로 목록 tbody만 재렌더링. JS 비활성 시 기존 `GET /orders/me` 폴백 유지. 테스트: `OrderApiControllerMvcTest`, `MainControllerMvcTest`(배선 핀).
- ~~구세대 확정 실패 2건이 빌드 차단~~: `AccountServiceTest`(시드 이메일 재가입 → `DuplicateEmailException`)와 `CartServiceTest`(단가 합을 라인합계 합으로 기대한 낡은 assertion)를 자체 데이터 생성 + `@Transactional` 롤백 방식의 통합 테스트로 재작성(시드/하드코딩 ID 의존 제거). 회원가입 2건(원자적 저장·중복 거부), 장바구니 2건(DTO 단가/라인합계, 동일 상품 재담기 수량 증가). `gradlew build` 전체 통과 복구.
- ~~죽은 코드/중복/오타/백업 파일 청소 (#8, #11, #14, #16, #20)~~: 주석 코드 제거(`OrderController.createOrder`, `CartService` 매핑 블록, `OrderRepositoryQuery.findOrders(SearchOption)`+member.name 필터 버그였던 `productLike`), 미사용 제거(`OrderService` 단건 주문 오버로드, `CartService.firstOrElseGet`, `CartRepositoryQuery`의 미사용 5개 메서드, `OrderCreateForm` 클래스, `Member.setCart/removeCart`, `Account.enabled`/수동 `getRole()`/email unique 이중 선언), `getTotalPice`→`getTotalPrice` 오타 수정(`OrderItem`+`Order`, `CartItem` 쪽은 미사용이라 삭제), 백업 템플릿 3종(`backup.html`, `backup2.html`, `cart_backup.html`) 삭제. `GET /orders/me` 매핑은 main.html 검색 폼이 사용하므로 유지. 동작 불변 — 전체 테스트로 회귀 없음 확인. `OptionalTest`/`PassWordTest`는 사용자 요청으로 보존.
- ~~`ddl-auto: create` 데이터 소실 (#1)~~: 기본 `ddl-auto: update`(데이터 보존) + `local` 프로파일만 `create`(리셋용, `--spring.profiles.active=local`). `initDb`는 멱등 가드 추가(Account 존재 시 skip)로 update 환경에서 재시작해도 중복 시드/기동 실패 없음. `src/test/resources/application.yml` 신설로 테스트는 임베디드 H2(`mem:hgpage-test`, create-drop) 사용 — 구세대 테스트의 H2 TCP 서버 의존 제거, 테스트가 실 DB를 오염시키지 않음. 실부트 3회(update 기동/재기동 보존/local 리셋+재시드)로 검증. 테스트: `InitDbTest`(`@DataJpaTest`, 멱등성). 주의: `update`는 컬럼 타입 변경/삭제를 반영하지 못하므로 엔티티 구조 변경 후 스키마가 어긋나면 `local`로 리셋할 것.
- ~~주문 후 장바구니 미정리 (#6)~~: `CheckOutForm.fromCart`(hidden, 주문서 생성 시 장바구니 경유면 true)로 출처를 구분해, 장바구니발 주문은 `OrderService.orderFromCart()`가 주문 생성 + 주문된 상품만 장바구니 제거를 **단일 트랜잭션**으로 수행(주문 실패 시 장바구니 불변). 바로 구매는 장바구니를 건드리지 않는다. `Cart.removeItems(productIds)` 도메인 메서드 신규, `CartService.removeCartItems`는 상품별 재조회 루프(#9 일부)에서 장바구니 1회 조회 + 일괄 제거로 재작성. 테스트: `CartTest`, `CartServiceRemoveItemsTest`, `OrderServiceOrderFromCartTest`, `OrderControllerMvcTest`(fromCart 분기 4건).
- ~~`OrderRepositoryQuery.findOrders` distinct 누락 (#17)~~: `select(order).distinct()` 명시. Hibernate 6이 fetch join 중복을 자동 제거하긴 하지만 의도를 코드에 명시. 컬렉션 fetch join + `limit(100)` 조합은 limit이 메모리 적용되는 문제(HHH90003004)가 남아 있음 — #9 페이징 개선 때 함께 다룰 것.
- ~~메인 관리자 재고 목록 fetch join 미사용 (#18)~~: `MainController`의 ADMIN 분기를 `productService.findAll()` → `findAllWithInventory()`로 교체. `/admin/inventory`와 조회 경로 일관화, OSIV 의존 제거. `MainControllerMvcTest`의 mock 검증도 `findAllWithInventory()`로 갱신.

### 해결됨 (2026-06-11)
- ~~발주/입고 백엔드 부재 (B-2)~~: `PurchaseOrder`(`ORDERED`→`RECEIVED`, 정적 팩토리, `@Enumerated(STRING)`) + `PurchaseOrderItem` 도메인 신규. `receive()`는 중복 입고를 `IllegalStateException`으로 거부(재고 이중 증가 방지). `PurchaseOrderService.create/receive/findAllWithItems`(fetch join) + `AdminController` 매핑. 입고는 HTML 폼 제약 때문에 path variable 대신 `POST /admin/purchase-orders/receive`(param `poId`) 채택, main.html의 깨진 `th:action`(`${poId}` 미존재)과 주석 CSRF 복원. 재고 페이지에 발주 현황 테이블 추가. 테스트: `PurchaseOrderTest`, `PurchaseOrderServiceTest`, `PurchaseOrderRepositoryTest`, `AdminControllerMvcTest`.
- ~~품절 사용자 UX 부재 (C)~~: 메인 상품 카드에 재고 표시 — 재고 0이면 "품절" 배지 + 카드 흐림 + 수량/구매/장바구니 disabled, 1~9개면 "N개 남음", 수량 입력 `max=재고`. 카드가 재고를 읽으므로 페이징 쿼리를 `findPageWithInventory`/`findPageByNameWithInventory`(fetch join + countQuery)로 교체해 OSIV 의존 N+1 제거. 서버 측 강제는 기존 `removeStock` 예외 + 낙관적 락 그대로. 테스트: `ProductRepositoryTest`(페이징 fetch join), `MainControllerMvcTest`(품절/남은수량 렌더링 — 인라인 CSS 주석에 한글 키워드를 쓰면 content 문자열 검사가 오염되니 주의).
- ~~관리자 재고 조정/조회 백엔드 부재 (B-1)~~: `InventoryService.adjust(productId, delta, reason)`(음수 방지, reason은 로그) + `AdminController`(`GET /admin/inventory` 조회 페이지, `POST /admin/inventory/adjust` 조정 후 flash와 함께 조회로 리다이렉트) 구현. `ProductRepository.findAllWithInventory()` fetch join으로 N+1 방지. main.html adjust 폼의 주석 처리됐던 CSRF hidden 복원. 발주/입고(B-2)는 미구현으로 남음. 테스트: `InventoryServiceTest`, `AdminControllerMvcTest`(USER 403 포함), `ProductRepositoryTest`.
- ~~`Delivery.status`가 항상 null~~: `Order.createOrder()`에서 `DeliveryStatus.READY`로 초기화해 `cancel()`의 "배송완료 시 취소 불가" 가드를 살림. `Delivery.status`에 빠져 있던 `@Enumerated(STRING)`도 추가(ORDINAL 저장 위험 제거, `Order.status`와 일관). 테스트: `OrderTest`(도메인 단위), `DeliveryStatusMappingTest`(`@DataJpaTest` — 네이티브 쿼리로 문자열 저장 검증).
- ~~재고 차감 동시성 미보장~~: `Inventory`에 `@Version` 낙관적 락 도입. 동시 수정 시 늦게 커밋하는 쪽은 `OptimisticLockingFailureException` → `GlobalExceptionHandler`가 화면이면 flash("주문이 몰려...") + `redirect:/main`, API면 409. 테스트: `InventoryOptimisticLockTest`(`@DataJpaTest` 임베디드 H2 — TCP 서버 불필요), `OrderControllerMvcTest`. `Inventory.reservedQty`는 여전히 미사용(결제 단계 도입 시 예약 모델과 함께 활용 예정).
- ~~회원가입 트랜잭션 분리~~: `AccountService.signUp(Member, Account)` 단일 `@Transactional`로 통합. 이메일 중복은 `DuplicateEmailException`(저장 전 선검사)으로 던지고, `AuthController`가 catch하여 signup 폼 email 필드 에러로 표시. `AuthController`의 `MemberService` 의존 제거. 테스트: `AccountServiceSignUpTest`, `AuthControllerMvcTest`(`@Import(SecurityConfig.class)` — `/signup` permitAll 필요).

### 해결됨 (2026-06-10)
- ~~`Optional.get()` 남발 / 전역 예외처리 부재~~: `EntityNotFoundException` + `GlobalExceptionHandler` 도입. 모든 `.get()`을 `orElseThrow`로 교체, `NotEnoughStockException`은 화면이면 flash + `redirect:/main`, API면 409. `MemberService.findMember`/`findById` 중복도 `findMember`로 통합. `loadUserByUsername`이 Account PK를 Member PK로 오용하던 잠재 버그도 `account.getMember()`로 수정.

### 심각도 높음
- 없음 (2026-06-12 기준. 운영 배포 단계가 되면 `update` 대신 Flyway 마이그레이션 도입 검토)

### 심각도 중간
7. ~~**`TimeTraceAop` 포인트컷 과다**~~ — 해결됨(2026-06-15, 위 해결됨 섹션 참고).
9. ~~**N+1성 루프**~~ — 해결됨(2026-06-16, 위 해결됨 섹션 참고). `OrderService.order`·`findOrders`·`OrderController`의 모든 findById 루프 제거 완료.
10. ~~**`CartItemDto` 필드 중복**~~ — 해결됨(2026-06-15, 위 해결됨 섹션 참고).
12. ~~**`@GeneratedValue` 전략 불일치**~~ — 해결됨(2026-06-16, AUTO/시퀀스로 통일. 위 해결됨 섹션 참고).

### 알려진 한계 (의도된 동작 — 추후 정책 재검토 대상)
22. **백오더 FIFO 부분 기아(starvation)**: 승격은 "전부-아니면-백오더 + 부분출고 없음" 정책이라, 앞선 큰 수량 백오더가 가용분으로 못 채워지면 뒤쪽의 더 작은(채울 수 있는) 주문도 계속 BACKORDERED로 대기한다. 현재는 학습용으로 단순 FIFO를 유지(의도된 동작). 추후 부분 할당/우선순위/타임아웃 등 할당 정책 도입 시 재검토.

### 심각도 낮음
13. ~~회원가입 서버 검증 부족~~ — 해결됨(2026-06-15, 위 해결됨 섹션 참고).
15. H2 콘솔 `permitAll` + CSRF 예외 — 개발용으로는 무방하나 운영 배포 시 제거. `/api/replenishments`(무인증 콜백 — 위조돼도 승격은 WMS 실가용분 검증 기반이라 무결성 안전, 스캔 트리거만 가능)도 운영 시 보호(공유 시크릿 헤더 or 네트워크 격리) 대상.
19. ~~`OrderController.restoreCheckOutDisplay` findById 루프~~ — 해결됨(2026-06-16, 위 해결됨 섹션 참고).
21. `OptionalTest`(Java API 연습장), `PassWordTest`(bcrypt 해시 출력용 `@SpringBootTest`) — 프로젝트 검증과 무관한 연습 테스트. 정리 후보(사용자 결정으로 보존 중).

### 개선 우선순위
1. **Phase 3 — S4: 회복탄력성** (WMS 다운 시 예약 강등 + 보상 스윕 잡(`@Scheduled`로 BACKORDERED 재할당 — 콜백 유실 회수) + RestClient 타임아웃/재시도 — 타임아웃은 WMS `OmsReplenishmentNotifier`·OMS `WmsInventoryAdapter` **양쪽** 모두, 통지를 요청 스레드에서 떼는 것(@Async)도 함께 검토. 현재 OMS 재고조정은 4-hop 동기 체인이라 OMS hang 시 타임아웃 없으면 연쇄 블록). S3까지 완료 — 입고/재고증가 시 WMS→OMS 콜백으로 백오더 자동 승격.
1-1. 운영(Railway) 배포 시 정합성: `wms.base-url` 환경변수화(현재 prod에서도 localhost:8081), WMS 앱 prod 프로파일·Dockerfile 신설, Flyway V3(OMS DB에서 inventory·reservation·purchase_order* DROP) 작성. `oms.base-url`(WMS→OMS 콜백)도 환경변수화 대상.
2. (선택) Phase 2 잔여 — 컨트롤러·DTO의 컨텍스트별 분리 + `admin` 컨트롤러 OMS/WMS 분리
3. 운영 배포 단계 시 `update` 대신 Flyway 마이그레이션 도입 검토(#15 H2 콘솔 정리 포함)
