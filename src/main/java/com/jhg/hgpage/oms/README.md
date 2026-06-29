# OMS (Order Management System)

주문·장바구니·고객(인증/회원)을 담당하는 바운디드 컨텍스트. 멘탈 모델로는 **지점(branch)** — 손님을 받고 "누가 무엇을 주문했나"와 백오더 명단을 쥐지만, **재고의 진실은 갖지 않는다**(재고는 WMS=본사 소관).

> 현재는 단일 모놀리스 안의 패키지(`com.jhg.hgpage.oms`)지만, Phase 3에서 별도 앱으로 물리 분리될 것을 전제로 설계돼 있다. OMS는 WMS를 **직접 import하지 않고**, `contract/` 포트 인터페이스로만 통신한다(아래 [경계 규칙](#경계-규칙) 참고).

## 책임 범위

| 담당 (OMS) | 담당 안 함 (→ 다른 컨텍스트) |
|------------|------------------------------|
| 주문 생성·조회·취소, 주문 생명주기(상태) | 재고 수량/예약/출고 실연산 → **WMS** |
| 백오더 접수 및 입고 후 승격(FIFO) 정책 | 발주·입고 → **WMS** |
| 장바구니 | 상품 카탈로그(이름·가격) → **catalog** (공유 커널) |
| 회원가입·로그인·회원정보, 배송 상태 전이 | |

핵심 정책: **재고가 없어도 주문은 접수된다(백오더).** 가용분이 있으면 예약(`ORDER`), 부족하면 거부 없이 `BACKORDERED`로 접수하고, 입고로 가용분이 생기면 오래된 주문부터(FIFO) 자동 승격한다.

## 패키지 구조

```
oms/
├─ domain/          주문·장바구니·고객 엔티티 + enums
│  ├─ Order, OrderItem, Delivery
│  ├─ Cart, CartItem
│  ├─ Account, Member, Address
│  └─ enums/        OrderStatus, DeliveryStatus
├─ repository/      Spring Data JPA + QueryDSL(*RepositoryQuery) + SearchOption
├─ service/         주문/할당/장바구니/인증/회원 서비스
├─ dto/             응답 뷰 DTO (OrderDto, OrderDetailDto, AdminOrderDto, CartItemDto)
└─ web/
   ├─ controller/   서버 렌더링 MVC (@Controller)
   ├─ api/          REST (@RestController) — fetch용
   └─ form/         요청 폼 (CheckOutForm, OrderRequest, SignUpForm)
```

## 도메인 모델

```
Account ──1:1── Member ──1:1── Cart ──1:N── CartItem ──N:1── Product(catalog)
                  │
                  └──1:N── Order ──1:N── OrderItem ──N:1── Product(catalog)
                              │
                              └──1:1── Delivery
```

- **Account ↔ Member (1:1 분리)**: 인증정보(email/password/role)와 회원정보(name/phone/address)를 분리. `UserPrincipal`이 둘을 합쳐 Spring Security 주체로 동작.
- **Member → Cart (1:1, cascade ALL, orphanRemoval)**: `Member.createUser()`는 가입 시 장바구니 자동 생성, `createAdmin()`은 장바구니 없음.
- **Order → OrderItem / Delivery**: `Order`는 상태 전이만 책임지고(아래), 재고 연산은 서비스가 `InventoryPort`에 위임. `OrderItem`/`CartItem`은 `catalog.Product`를 `@ManyToOne`으로 참조(가격은 주문/담기 시점 스냅샷 `orderPrice`/`productPrice`로 별도 저장).
- 엔티티는 정적 팩토리로만 생성: `Order.createOrder`, `OrderItem.createOrderItem`, `Member.createUser/createAdmin` 등. 기본 생성자는 `protected`로 차단.

### 상태(enum)

- `OrderStatus`: **ORDER**(접수+재고 예약 완료) · **BACKORDERED**(재고 부족, 입고 대기 — 예약 없음) · **CANCEL**
- `DeliveryStatus`: **READY** · **COMP**

### Order 상태 전이 + 가드

| 메서드 | 전이 | 가드 |
|--------|------|------|
| `markOrdered()` | → ORDER | (표시만) |
| `markBackordered()` | → BACKORDERED | (표시만) |
| `cancel()` | → CANCEL | 배송완료(COMP)·이미 취소면 거부 |
| `completeDelivery()` | delivery → COMP | 취소·백오더·이미 완료면 거부 |

> 상태 전이는 **도메인**이, 실물 재고 연산(reserve/release/ship)은 **서비스가 `InventoryPort`(WMS)에** 위임한다. `Order.quantitiesByProductId()`가 라인을 `상품id→수량` 맵으로 집계해 재고 연산 입력으로 쓴다.

## 주요 흐름

- **회원가입**: `POST /signup` → `AccountService.signUp(member, account)` 단일 트랜잭션으로 Member+Account 원자적 저장. 비번 불일치는 서버 검증, 이메일 중복은 `DuplicateEmailException`.
- **로그인**: Spring Security + `AccountService.loadUserByUsername(email)` → `UserPrincipal`. 성공 시 `/main`.
- **장바구니**: `CartApiController`(REST, fetch). 담기/수량변경/삭제/카운트 — 모든 응답에 최신 count 반환.
- **주문**: `POST /orders/checkout-form`(주문서) → `POST /orders/checkout`(확정). 장바구니발 주문(`fromCart`)은 `orderFromCart`로 **주문 생성 + 주문된 상품만 장바구니 제거**를 한 트랜잭션으로. 바로 구매는 장바구니 불변.
- **주문/예약 (핵심 순서)**:
  ```
  OrderService.order()
    → orderRepository.save(order)            // ① 먼저 저장해 orderId 확보 (예약 멱등키)
    → orderAllocationService.allocate(order) // ② 그 orderId로 InventoryPort.reserveAll
         성공 → markOrdered (ORDER)
         실패 → markBackordered (BACKORDERED, 거부 안 함)
  ```
- **취소**: `POST /orders/{id}/cancel` → 본인 확인 후 `Order.cancel()`. 취소 직전이 `ORDER`였을 때만 `inventoryPort.releaseAll(orderId, …)`로 예약 해제 + 늘어난 가용분으로 `backorderAllocator.allocate()` 승격 트리거. `BACKORDERED` 취소는 풀 예약이 없어 트리거 안 함.
- **배송완료(관리자)**: `POST /admin/orders/complete-delivery` → `Order.completeDelivery()`(가드 통과 시) → `inventoryPort.shipAll(orderId, …)`로 **이때 비로소 실물 차감**.
- **백오더 승격**: WMS 입고/재고증가 → `StockReplenishedHandler.onReplenished(productIds)` 콜백 → `BackorderAllocator`가 해당 상품 포함 BACKORDERED 주문을 FIFO로 재할당, 가능한 것을 ORDER로 승격.

## HTTP 엔드포인트

### MVC (`@Controller`)

| 메서드 | 경로 | 역할 |
|--------|------|------|
| POST | `/orders/checkout-form` | 단건/장바구니 선택분으로 주문서 생성·렌더링 |
| POST | `/orders/checkout` | 검증 후 주문 확정 → `/main` |
| GET | `/orders/me` | 폼 폴백(실제 새로고침은 아래 API). `/main` 리다이렉트 |
| GET | `/orders/{orderId}` | 본인 주문 상세 |
| POST | `/orders/{orderId}/cancel` | 주문 취소 (flash 후 상세로) |
| GET | `/admin/orders` | 관리자 주문/배송 목록 |
| POST | `/admin/orders/complete-delivery` | 배송완료(`@RequestParam orderId`) |
| GET | `/cart` | 장바구니 화면 |
| GET/POST | `/signup` | 회원가입 폼 / 처리 |
| GET | `/login`, `/logout` | 로그인 화면 / 로그아웃 |

### REST (`@RestController`)

| 메서드 | 경로 | 역할 |
|--------|------|------|
| GET | `/api/orders/me` | 내 주문 목록(JSON) — 메인 새로고침 fetch |
| GET | `/api/cart/count` | 장바구니 품목 수 |
| POST | `/api/cart/items` | 상품 담기 |
| PATCH | `/api/cart/items/{productId}` | 수량 변경 |
| DELETE | `/api/cart/items/{productId}` | 단건 삭제 |
| DELETE | `/api/cart/items` | 다건 삭제(body `productIds`) |

## 서비스 계층

| 서비스 | 책임 |
|--------|------|
| `OrderService` | 주문 생성/조회/상세/취소/배송완료. 재고는 `InventoryPort`에 위임 |
| `OrderAllocationService` | 할당 정책(전부-아니면-백오더). `reserveAll` 결과로 ORDER/BACKORDERED 표시 |
| `BackorderAllocator` | `StockReplenishedHandler` 구현. 입고 통지 → 백오더 FIFO 승격 |
| `CartService` | 담기/수량변경/삭제/카운트/조회 |
| `AccountService` | `UserDetailsService` 구현. 회원가입(원자적)·인증 주체 로드 |
| `MemberService` | 회원 조회/가입 |

## 경계 규칙

- **OMS는 `wms/`를 직접 import하지 않는다** (검증: `oms/**`의 `com.jhg.hgpage.wms` import 0건). WMS와의 통신은 오직 `contract/` 포트로.
- **catalog(상품 카탈로그)는 공유 커널** — OMS가 `catalog.Product`/`ProductRepository`를 참조하는 것은 허용. (가격은 항상 서버에서 `Product` 재조회, 클라이언트 가격 불신.)

### contract 포트 (OMS ↔ WMS 경계)

| 포트 | 방향 | OMS에서 | 시그니처/요지 |
|------|------|---------|----------------|
| `InventoryPort` | OMS→WMS | 사용 | `reserveAll(orderId, qtyByProductId)`(전부-아니면-실패) · `shipAll(orderId, …)` · `releaseAll(orderId, …)`. 모든 호출에 **orderId**를 실어 WMS가 멱등 처리 |
| `InventoryQueryPort` | OMS→WMS | (OMS 미사용*) | `availableByProductIds(ids)` — 판매 가용수량 배치 조회(CQRS 읽기) |
| `StockReplenishedHandler` | WMS→OMS 콜백 | `BackorderAllocator`가 구현 | `onReplenished(productIds)` — "재고 보충됨" 통지(백오더 개념은 모름). 역방향 의존을 정방향으로 역전 |

> *`InventoryQueryPort`는 판매 그리드 조립(`catalog.ProductService`)에서 쓰이며 OMS 패키지 내부에서는 직접 호출하지 않는다.

## Phase 3 분리 관점

OMS는 이미 **WMS가 같은 JVM에 있든 다른 서버에 있든 동일하게 동작**하도록 준비돼 있다:
- WMS 직접 의존 0, 통신은 포트 3개로만.
- 재고 변경 호출이 `orderId` 자연키로 멱등화돼 있어 네트워크 재시도에 안전.

물리 분리 시 OMS 코드는 거의 손대지 않고, 포트 구현체를 "메서드 호출" → "REST 호출" 어댑터로 갈아끼우면 된다. OMS `Order.status`(주문 관점)와 WMS `Reservation`(창고 관점)이 `orderId`로 이어진 **두 개의 독립 장부**가 된다.
