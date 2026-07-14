# OMS (Order Management System)

`com.jhg.hgpage.oms`는 주문·장바구니·고객을 담당하는 바운디드 컨텍스트다. OMS는 누가 무엇을 주문했는지와 백오더 정책을 소유하지만 재고의 진실은 소유하지 않는다. 재고·예약·발주·입출고는 별도 `jhg-wms-project`가 담당한다.

## 책임

| OMS가 담당 | WMS·catalog에 위임 |
|------------|--------------------|
| 회원가입·인증·회원정보 | 상품 이름·가격 → `catalog` |
| 장바구니 | 실물·예약·가용 재고 → WMS |
| 주문 생성·조회·취소·배송 상태 | 예약·해제·출고 → WMS |
| `ORDER/BACKORDERED/CANCEL` 상태 | WMS `RESERVED/SHIPPED/RELEASED` 상태 |
| 백오더 FIFO 승격 정책 | 발주·입고·재고조정 → WMS |

핵심 정책은 **재고가 없어도 주문을 접수하는 것**이다. 전체 주문 라인을 예약할 수 있으면 `ORDER`, 하나라도 부족하면 `BACKORDERED`로 저장한다.

## 패키지

```text
oms/
├─ domain/       Order · OrderItem · Delivery · Cart · Account · Member
├─ repository/   Spring Data JPA + QueryDSL
├─ service/      주문·할당·백오더·장바구니·회원 서비스
├─ dto/          주문·장바구니 응답 DTO
└─ web/
   ├─ controller/  Thymeleaf MVC
   ├─ api/         장바구니·주문 JSON API
   └─ form/        checkout·signup 요청 폼
```

WMS REST 구현은 형제 패키지에 있다.

```text
wms/
├─ adapter/      WmsInventoryAdapter · WmsInventoryQueryAdapter · WmsPurchaseOrderAdapter
├─ dto/          InventoryRow · PurchaseOrderDto
└─ web/          OMS 관리자 화면용 WMS 프록시 컨트롤러·폼
```

OMS 저장소에는 `Inventory`, `Reservation`, `PurchaseOrder` 엔티티나 리포지토리가 없다.

## 도메인 모델

```text
Account ──1:1── Member ──1:1── Cart ──1:N── CartItem ──N:1── Product
                  │
                  └──1:N── Order ──1:N── OrderItem ──N:1── Product
                              │
                              └──1:1── Delivery
```

- `Account`는 인증정보, `Member`는 회원정보를 담당한다.
- `Order`는 주문·배송 상태 전이만 담당한다.
- `OrderItem`과 `CartItem`은 서버에서 조회한 `Product` 가격을 스냅샷으로 저장한다.
- 재고 연산은 엔티티가 아니라 서비스가 `InventoryPort`에 위임한다.

## 경계 포트와 구현

| 포트 | 방향 | 현재 구현 |
|------|------|-----------|
| `InventoryQueryPort` | OMS→WMS | `WmsInventoryQueryAdapter`: 가용수량 배치 조회 |
| `InventoryPort` | OMS→WMS | `WmsInventoryAdapter`: 예약·출고·해제 |
| `StockReplenishedHandler` | WMS→OMS | `BackorderAllocator`: 보충 콜백을 FIFO 재할당으로 연결 |

모든 OMS→WMS 호출은 `RestClient`를 사용하며 `WMS_BASE_URL`과 HTTP Basic 자격증명을 적용한다.

```text
WMS_BASE_URL=http://localhost:8081
WMS_BASIC_USER=wms
WMS_BASIC_PASSWORD=wms
```

운영에서는 private `WMS_BASE_URL`을 유지한다. WMS 공개 관리자 도메인이 있어도 OMS가 공개 경로를 호출하지 않는다.

## 주문·재고 흐름

### 주문 생성

```text
OrderService.order
  ├─ Order 저장 → orderId 확보
  └─ OrderAllocationService.allocate
       └─ InventoryPort.reserveAll(orderId, qtyByProductId)
            ├─ true  → Order.markOrdered()
            └─ false → Order.markBackordered()
```

`reserveAll`은 통신 장애 또는 WMS 5xx에서 한 번 재시도한다. 두 번 모두 실패하면 `false`를 반환해 주문을 `BACKORDERED`로 안전하게 접수한다.

### 배송완료와 취소

- 배송완료: `InventoryPort.shipAll(orderId, ...)` 후 배송 상태를 완료한다.
- `ORDER` 취소: `InventoryPort.releaseAll(orderId, ...)`로 예약을 해제하고 다른 백오더를 재할당한다.
- `BACKORDERED` 취소: 실제 예약이 없으므로 WMS 해제 없이 주문만 취소한다.

WMS의 `Reservation.qtyByProductId`가 예약 수량의 SSOT다. ship/release는 OMS 요청 body의 수량을 신뢰하지 않고 WMS 원장 값을 재생한다.

## 백오더 승격과 복구

```text
WMS 입고·양수 조정 커밋
  └─ POST /api/replenishments {productIds}
       └─ ReplenishmentApiController
            └─ BackorderAllocator.onReplenished
                 └─ 오래된 BACKORDERED부터 WMS 재예약
```

- 콜백은 재고가 늘었다는 사실만 전달하며 중복 수신에 안전하다.
- WMS가 콜백 전송에 실패해도 입고·조정 트랜잭션은 성공한다.
- `BackorderSweeper`가 기본 60초마다 백오더를 다시 확인해 유실 콜백과 WMS 장애 중 접수된 주문을 복구한다.
- 콜백과 스윕이 겹쳐도 WMS의 `orderId` 유니크 예약 원장이 중복 예약을 막는다.

## 독립 상태 장부

OMS와 WMS는 하나의 분산 트랜잭션을 만들지 않는다.

| OMS 주문 상태 | WMS 예약 상태 | 의미 |
|---------------|---------------|------|
| `ORDER` | `RESERVED` | 주문 접수·예약 완료 |
| `ORDER` + 배송완료 | `SHIPPED` | 실물 출고 완료 |
| `CANCEL` | `RELEASED` | 예약 해제 완료 |
| `BACKORDERED` | 예약 없음 | 재고 또는 통신 복구 대기 |

두 장부는 `orderId`로 연결되고, 멱등 API·상태 가드·보상 스윕으로 수렴한다.

## 주요 HTTP 엔드포인트

| 메서드 | 경로 | 역할 |
|--------|------|------|
| POST | `/orders/checkout-form` | 주문서 생성 |
| POST | `/orders/checkout` | 주문 확정 |
| GET | `/orders/{orderId}` | 본인 주문 상세 |
| POST | `/orders/{orderId}/cancel` | 본인 주문 취소 |
| GET | `/api/orders/me` | 내 주문 목록 JSON |
| GET/POST/PATCH/DELETE | `/api/cart/**` | 장바구니 API |
| POST | `/api/replenishments` | WMS 재고보충 콜백 |
| GET | `/admin/orders` | 관리자 배송 관리 |

## 경계 규칙

- `oms/**`는 `com.jhg.hgpage.wms`를 import하지 않는다.
- OMS는 재고 수량을 로컬 DB에 복제하지 않는다.
- 재고 변경은 항상 `orderId`와 상품별 수량을 WMS에 전달한다.
- 가격은 항상 OMS의 `Product`를 다시 조회하며 클라이언트 가격을 신뢰하지 않는다.
- WMS Basic 자격증명 변경 시 OMS와 WMS의 환경변수를 함께 바꾸고 OMS를 먼저 배포한다.
