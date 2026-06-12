# CLAUDE.md

이 파일은 이 저장소에서 작업하는 Claude Code(및 개발자)를 위한 가이드입니다.

## 프로젝트 개요

Spring Boot 기반 **학습용 커머스(쇼핑몰) 웹 애플리케이션** (`hgpage`, group `com.jhg`).
김영한 JPA 강의 스타일의 도메인 설계(Order/OrderItem/Delivery/Inventory)에 Spring Security 인증,
QueryDSL, 장바구니 REST API를 직접 확장한 구조.

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
- `aop/` — `TimeTraceAop` (메서드 실행시간 로깅)
- `api/` — `CartApiController`(장바구니 REST: 담기/수량변경/삭제/카운트), `OrderApiController`(내 주문 목록 JSON — 메인 새로고침 fetch용)
- `config/` — `SecurityConfig`, `QueryDslConfig`
- `controller/` — 화면 컨트롤러 (`auth`, `main`, `cart`, `order`, `admin`) + `form/` (요청 폼 DTO)
- `domain/` — JPA 엔티티 + `dto/view`(응답 DTO), `enums/`(Role/OrderStatus/DeliveryStatus)
- `exception/` — `NotEnoughStockException`, `EntityNotFoundException`, `DuplicateEmailException`, `GlobalExceptionHandler`
- `repository/` — Spring Data 인터페이스 + QueryDSL 구현 클래스
- `service/` — 비즈니스 로직
- `initDb.java` — `@PostConstruct`로 초기 계정/상품 시드 (멱등: Account가 하나라도 있으면 skip)

### 도메인 모델 핵심
- **Account ↔ Member (1:1 분리)**: 인증정보(Account: email/password/role)와 회원정보(Member: name/phone/address)를 분리. `UserPrincipal`이 둘을 합쳐 Spring Security 주체로 동작.
- **Member → Cart (1:1, cascade ALL)**: `Member.createUser()` 시 장바구니 자동 생성, `createAdmin()`은 장바구니 없음.
- **Order → OrderItem → Product / Delivery**: 주문 시 `OrderItem.createOrderItem()`에서 재고 차감, `Order.cancel()`에서 재고 복구.
- **Product ↔ Inventory (1:1)**: 재고를 별도 엔티티로 분리(onHandQty/reservedQty). `@Version` 낙관적 락.
- **PurchaseOrder → PurchaseOrderItem → Product**: 관리자 발주(`ORDERED`) → 입고(`receive()`: 재고 증가 + `RECEIVED`). 중복 입고 거부.

### 주요 흐름
- **회원가입**: `AuthController` → `AccountService.signUp(member, account)` 단일 트랜잭션으로 Member+Account 원자적 저장. 이메일 중복 시 `DuplicateEmailException` → 컨트롤러가 signup 폼의 email 필드 에러로 안내.
- **로그인**: `AccountService.loadUserByUsername`(email 조회) → `UserPrincipal`. 로그인 파라미터는 `email`/`password`, 성공 시 `/main`. 로그인 화면 템플릿은 `home.html`.
- **메인**: `GET /main` — 상품 페이징·검색(`keyword`, size=10, sort=id) + 내 주문 목록 + (ADMIN이면) 전체 재고 목록. 페이지 네비게이션은 숫자 클릭형(`이전 | 1 … 4 [5] 6 … 12 | 다음`, 윈도우 최대 5개) — 컨트롤러가 0-based `beginPage`/`endPage`를 모델로 내려준다.
- **장바구니**: `CartApiController`(REST) — fetch 호출용. 담기/수량변경/삭제/카운트. 모든 응답에 최신 장바구니 count 반환.
- **주문**: 메인·장바구니 → `POST /orders/checkout-form`(주문서 생성) → `POST /orders/checkout`(확정, `@Valid CheckOutForm` — 상품 0개·수량 0 검증 있음). 주문서에서 상품별 체크박스(`ProductDto.selected`, 기본 true)로 일부만 골라 주문 가능 — 체크된 상품만 OrderLine으로 변환되고, 전부 해제 시 `product` 필드 에러. 장바구니에서 온 주문서는 `CheckOutForm.fromCart`(hidden) = true — 주문 확정 시 `OrderService.orderFromCart()`가 주문 생성과 함께 주문된 상품만 장바구니에서 제거(단일 트랜잭션). 바로 구매는 장바구니 불변. `GET /orders/me`는 새로고침 폼의 JS 폴백(`redirect:/main`) — 실제 새로고침은 `GET /api/orders/me`(JSON) fetch.
- **주문 상세/취소**: `GET /orders/{id}`(`orderview.html`) — `findDetailById` fetch join 단건 조회, **본인 주문만**(타인/없는 주문은 404로 존재를 숨김, IDOR 방지). `POST /orders/{id}/cancel` — `Order.cancel()` 호출(재고 복구, 배송완료·재취소 거부 가드), 성공/실패를 flash로 상세에 표시. 취소 버튼은 `OrderDetailDto.cancelable`(ORDER 상태 + 배송완료 전)일 때만 노출.
- **가격 정책**: 가격은 항상 서버에서 `Product`를 재조회해 사용한다. 클라이언트가 보낸 가격을 신뢰하지 않는다. 주문/장바구니에 당시 가격을 스냅샷(`orderPrice`/`productPrice`)으로 저장.

### 초기 시드 계정 (`initDb`)
- 관리자: `admin@admin.com` / `1111` (ROLE_ADMIN, 장바구니 없음)
- 일반회원: `twin10240@naver.com` / `1111` (ROLE_USER)
- 상품 20개("상품1"~"상품20", 가격 10000~29000) + 각 재고 자동 생성
- **빈 DB에만 시드된다**(Account 존재 시 skip). 처음부터 다시 시드하려면 `local` 프로파일로 실행.

## 컨벤션 / 주의사항

- 서비스는 클래스 레벨 `@Transactional(readOnly = true)`, 쓰기 메서드에만 `@Transactional` 부여.
- 엔티티는 정적 팩토리 메서드로 생성(`Member.createUser`, `Order.createOrder`, `OrderItem.createOrderItem`).
- 엔티티 기본 생성자는 `@NoArgsConstructor(access = PROTECTED)`로 막음.
- 복잡한 조회는 Spring Data 파생 쿼리 대신 `*RepositoryQuery` QueryDSL 클래스에 작성.
- 응답 DTO는 `domain/dto/view`, 요청 폼은 `controller/form`.
- ID 조회 실패는 `Optional.get()` 대신 `orElseThrow(() -> new EntityNotFoundException(대상, id))`. 전역 예외 처리는 `exception/GlobalExceptionHandler`(`/api/**`는 ProblemDetail JSON, 화면은 `error.html` 또는 flash 리다이렉트).
- 빌드/테스트 실행 시 `JAVA_HOME`이 JDK 17+를 가리켜야 함(시스템 기본이 Java 8): `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"`.

## 테스트 현황

테스트는 두 세대가 공존한다. 신규 테스트는 **신세대 패턴**을 따를 것.

- **구세대** (`service/`, `repository/`의 기존 `@SpringBootTest`): assertion 없이 출력만 하거나 initDb 시드·하드코딩 ID(2L)에 의존(`ProductServiceTest`, `MemberServiceTest`, `OrderServiceTest`, repository 테스트 등). (2026-06-12부터 테스트는 임베디드 H2를 쓰므로 TCP 서버 없이 돌고 실 DB를 오염시키지 않는다.) 빌드를 막던 확정 실패 2건(`AccountServiceTest`, `CartServiceTest`)은 2026-06-12 신세대 통합 테스트로 교체 완료 — 현재 `gradlew build` 전체 통과.
- **신세대** (권장 패턴): `ProductServiceFindPageTest`(Mockito 단위), `OrderControllerTest`(Validator + ArgumentCaptor 단위), `OrderControllerMvcTest`/`MainControllerMvcTest`/`CartApiControllerMvcTest`(`@WebMvcTest` 슬라이스, Security `user()`·`csrf()` 포함), 예외 처리 단위 테스트(`MemberServiceFindMemberTest`, `CartServiceExceptionTest`, `OrderServiceExceptionTest`, `AccountServiceLoadUserTest`), 회원가입(`AccountServiceSignUpTest`, `AuthControllerMvcTest`), 임베디드 H2 통합(`AccountServiceTest`, `CartServiceTest`, `InitDbTest` — 자체 데이터 생성 + 롤백). 격리되어 있고 검증이 명확함.

## 알려진 이슈 / 기술 부채 (작업 시 참고)

### 해결됨 (2026-06-12)
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
7. **`TimeTraceAop` 포인트컷 과다**: `execution(* com.jhg.hgpage..*(..))` — 모든 메서드를 감싸 로그 폭증/성능 저하(`System.out.println` 사용). 서비스·컨트롤러로 한정 권장.
9. **N+1성 루프**: `OrderService.order`가 라인별 `findById` 호출. `OrderRepositoryQuery.findOrders`는 `limit(100)` 하드코딩(컬렉션 fetch join과 함께라 limit이 메모리 적용됨 — HHH90003004), `OrderRepository.findOrdersByMemberId`는 1:N fetch join에 distinct 없음(미사용 메서드).
10. **`CartItemDto` 필드 중복**: `productPrice/cartPrice/unitPrice/lineTotalPrice` + `getTotalPrice()`가 사실상 동일 값. 정리 필요.
12. **`@GeneratedValue` 전략 불일치**: Account/Product/Cart/CartItem은 IDENTITY, Member/Order/OrderItem/Delivery/Inventory는 AUTO.

### 심각도 낮음
13. 회원가입 서버 검증 부족: `SignUpForm.passwordConfirm`이 서버에서 password와 비교되지 않음(화면 JS로만 검증). name/phone/address 서버 검증 없음.
15. H2 콘솔 `permitAll` + CSRF 예외 — 개발용으로는 무방하나 운영 배포 시 제거.
19. `OrderController.restoreCheckOutDisplay` — 주문서 검증 실패 시 폼의 상품마다 `productRepository.findById()` 루프 호출. `findAllById()` 일괄 조회로 개선 가능. (2026-06-12 발견)
21. `OptionalTest`(Java API 연습장), `PassWordTest`(bcrypt 해시 출력용 `@SpringBootTest`) — 프로젝트 검증과 무관한 연습 테스트. 정리 후보(사용자 결정으로 보존 중).

### 개선 우선순위
1. `TimeTraceAop` 포인트컷 범위 축소(#7)
2. `CartItemDto` 필드 정리, 회원가입 비밀번호 확인 서버 검증 추가, 남은 구세대 테스트를 신세대 패턴으로 점진 교체
