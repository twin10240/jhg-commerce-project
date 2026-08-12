# OMS 경계와 실행 흐름

`com.jhg.hgpage.oms`는 주문·장바구니·회원과 백오더 정책을 담당한다. 재고·예약·발주·입고의
정본은 별도 `jhg-wms-project`가 소유하며, OMS는 `contract` 포트의 REST 어댑터로만 WMS와 통신한다.

## 책임

| OMS 소유 | WMS 또는 catalog 소유 |
|---|---|
| 회원·인증·장바구니 | 상품명·판매가격: `catalog` |
| 주문 생성·조회·취소 | 보유·예약·가용수량: WMS |
| 백오더 상태와 FIFO 재할당 | 주문별 예약·출고·해제 원장: WMS |
| 출고 지시와 주문 배송상태 | 보충 요청 원본·발주·부분입고: WMS |

## 패키지

```text
oms/
├── domain/       Order, OrderItem, Delivery, Cart, Account, Member
├── repository/   Spring Data JPA와 QueryDSL 조회
├── service/      주문·할당·백오더·장바구니·회원 서비스
├── dto/          고객·관리자 화면 DTO
└── web/
    ├── controller/  MVC 컨트롤러
    ├── api/         장바구니 API와 WMS 보충 콜백
    └── form/        주문·회원가입 폼
```

OMS 저장소의 `wms/` 패키지에는 WMS 도메인이 아니라 REST 어댑터·DTO와 OMS 관리자용
재고/보충 화면 컨트롤러만 남아 있다.

## 상태

- `OrderStatus.ORDER`: 주문 접수와 WMS 예약 완료
- `OrderStatus.BACKORDERED`: 예약 없이 접수된 입고 대기 주문
- `OrderStatus.CANCEL`: 주문 취소
- `DeliveryStatus.READY`: 출고 대기
- `DeliveryStatus.SHIPPED`: 출고 완료
- `DeliveryStatus.DELIVERED`: 배송 완료

WMS 실물 출고는 `SHIPPED`까지만 전이하며, 배송 완료는 OMS가 `DELIVERED`로 전이한다.

## 주문 이행

```text
OrderService
  -> 주문 저장으로 orderId 확보
  -> InventoryPort.reserveAll(orderId, 수량)
     -> 성공: ORDER
     -> 재고 부족 또는 WMS 통신 최종 실패: BACKORDERED
```

- 예약은 모든 주문 라인을 한 번에 확보하는 전부-아니면-실패 정책이다.
- WMS `Reservation`은 `orderId`를 유니크 키로 사용해 재요청을 멱등 처리한다.
- 출고·해제는 호출자가 보낸 수량 대신 WMS 예약 원장의 수량을 재생한다.
- 주문 취소는 `ORDER`일 때만 WMS 예약을 해제하고 다음 백오더를 재할당한다.
- 출고 처리는 `ORDER + READY` 주문만 허용하며 WMS가 실물·예약수량과 `SHIP` 원장을 갱신한다.

## 백오더 복구

1. WMS 입고나 양수 재고조정이 커밋된다.
2. WMS가 Basic 인증으로 `POST /api/replenishments`를 호출한다.
3. `BackorderAllocator`가 영향 상품의 백오더를 FIFO로 재할당한다.
4. 콜백이 유실되면 `BackorderSweeper`가 기본 60초마다 같은 할당을 재시도한다.

OMS -> WMS 호출은 connect 1초/read 2초 타임아웃을 사용한다. 예약은 통신 실패 시 한 번 재시도한
뒤 백오더로 접수하고, 취소·출고 같은 쓰기 실패는 트랜잭션을 롤백해 사용자에게 재시도를 안내한다.

## 주요 MVC 경로

| 메서드 | 경로 | 역할 |
|---|---|---|
| GET | `/main` | 상품 검색·목록 |
| GET | `/products/{productId}` | 상품 상세 |
| GET | `/cart` | 장바구니 |
| POST | `/orders/checkout-form` | 주문서 |
| POST | `/orders/checkout` | 주문 생성 후 생성 주문 상세로 이동 |
| GET | `/orders` | 내 주문 |
| GET | `/orders/{orderId}` | 본인 주문 상세 |
| POST | `/orders/{orderId}/cancel` | 주문 취소 |
| GET | `/admin/orders` | 배송관리와 주문상품·백오더 원인 조회 |
| POST | `/admin/orders/ship` | 단건 출고 |
| POST | `/admin/orders/ships` | 선택 일괄 출고 |
| POST | `/admin/orders/deliver` | 배송 완료 |
| GET | `/admin/inventory` | WMS 재고와 백오더 수량 조회 |
| GET/POST | `/admin/replenishment-requests` | 보충 요청 이력·제출 |

## REST 경로

| 메서드 | 경로 | 역할 |
|---|---|---|
| GET/POST/PATCH/DELETE | `/api/cart/**` | 장바구니 조회·변경 |
| POST | `/api/replenishments` | WMS 재고 증가 콜백, 전용 Basic 인증 |

## 경계 규칙

- `oms/**`는 `com.jhg.hgpage.wms`를 import하지 않는다.
- OMS는 재고수량을 저장하지 않는다.
- `InventoryPort`는 예약·출고·해제를, `InventoryQueryPort`는 가용수량 조회를 담당한다.
- `StockReplenishedHandler`는 WMS가 재고 증가 사실만 알리고 OMS가 백오더 정책을 결정하게 한다.
- OMS 주문상태와 WMS 예약상태는 독립된 상태 머신이며 `orderId`로 연결된다.
