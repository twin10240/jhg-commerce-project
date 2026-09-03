# OMS V2 경계와 실행 흐름

`com.jhg.hgpage.oms`는 주문·장바구니·회원과 백오더 정책을 담당한다. 재고·예약·발주·입고의
정본은 별도 `jhg-wms-project`가 소유하며, OMS는 `contract` 포트의 REST 어댑터로만 WMS와 통신한다.

## 책임

| OMS 소유 | WMS 또는 catalog 소유 |
|---|---|
| 회원·인증·장바구니 | 상품명·판매가격: `catalog` |
| 주문 생성·조회·취소 | 보유·예약·가용수량: WMS |
| 백오더 상태와 FIFO 재할당 | 주문별 예약·출고·해제 원장: WMS |
| 출고 지시와 주문 배송상태 | 보충 요청 원본·발주·부분입고: WMS |
| 고객 반품 신청·가능 수량·결과 조회 | RMA 접수·입고·검수·취소와 `RESTOCKED` 재고 반영: WMS |

## 패키지

```text
oms/
├── domain/       Order, OrderItem, Delivery, CustomerReturn, Cart, Account, Member
├── repository/   Spring Data JPA와 QueryDSL 조회
├── service/      주문·할당·백오더·장바구니·회원 서비스
├── dto/          고객·관리자 화면 DTO
└── web/
    ├── controller/  MVC 컨트롤러
    ├── api/         장바구니 API와 WMS 보충/RMA 결과 콜백
    └── form/        주문·반품·회원가입 폼
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

### 고객 반품 상태

```text
PENDING_SUBMISSION → REQUESTED → RECEIVED → COMPLETED
         │               └───────────────→ CANCELLED
         └→ SUBMISSION_FAILED
```

- OMS는 배송 완료 주문의 품목·수량별 신청을 먼저 `PENDING_SUBMISSION`으로 커밋하고 UUID `requestKey`를 소유한다.
- WMS는 RMA와 `rmaId`, 입고·검수·취소, `RESTOCKED`·`DISPOSED`·`REJECTED` 처분을 소유한다.
- OMS는 승인 수량과 처분을 고객에게 보여주지만 반품 재고 수량이나 `RETURN` 원장을 소유하지 않는다.
- 모의 카드 결제와 환불 작업은 OMS가 소유하며, 실제 PG 연동은 V3 범위다.

## 주문 이행

```text
OrderService
  -> 주문 저장으로 orderId 확보
  -> InventoryPort.reserveAll(requestKey, orderId, 수량)
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

## RMA 접수와 복구

1. OMS가 고객 요청과 `requestKey`를 커밋한 뒤 Basic 인증으로 WMS `POST /api/returns`를 호출한다.
2. 같은 `requestKey`와 같은 내용은 WMS의 기존 `rmaId`로 수렴한다.
3. WMS는 완료·취소 결과를 Basic 인증 `POST /api/return-status-events`로 알린다.
4. OMS는 `requestKey`, `rmaId`, 주문, 전체 품목·상품·수량을 대조하고 불일치 콜백을 `409`로 거부한다.
5. `ReturnReconciliationSweeper`가 `returns.sweep-delay`(기본 `60s`)마다 미접수 요청을 재전송하고
   `REQUESTED`·`RECEIVED` RMA를 `GET /api/returns/{rmaId}`로 조회한다.

콜백 경로는 보충 콜백과 같은 `oms.callback.user/password` 보안 체인을 사용한다. 잘못된 Basic 인증은
로그인 리다이렉트 없이 `401`이며, 네트워크·5xx·인증 실패 또는 단건 계약 불일치는 현재 상태를 보존해
다음 주기와 다른 RMA의 복구를 막지 않는다.

## 배송 완료

`DELIVERED` 전이 경로는 둘이고 둘 다 유효하다.

1. **WMS 콜백** — 창고가 배송 완료를 기록하면 Basic 인증 `POST /api/delivery-events`가 온다. `OrderService.markDelivered`는 이미 `DELIVERED`면 상태 전이는 no-op이고 WMS의 `deliveredAt`을 저장하므로 통지가 재발송돼도 안전하다(`409`는 출고되지 않은 주문일 때만).
2. **관리자 버튼(수동 복구)** — `POST /admin/orders/deliver`는 그대로 남지만 화면에서 **배송 완료(수동)**으로 표기하고 확인창이 정상 경로가 WMS임을 알린다. WMS가 다운돼 통지 자체가 오지 않을 때의 복구 경로이며, 사람이 누르는 경로라 상태가 맞지 않으면 예외를 낸다.

배송 완료를 OMS에서 먼저 눌러도 재고에는 영향이 없다(WMS는 출고 시점에 이미 차감했다). 다만 WMS의 `deliveredAt`은 비어 있게 되므로, 창고 화면에는 여전히 배송 완료 버튼이 남는다 — 눌러도 OMS 쪽은 no-op이다.

배송 완료 시각은 `Delivery.deliveredAt`에 저장한다. WMS가 내려간 동안 OMS 수동 복구를 사용하면 OMS 처리 시각을 먼저 저장하고, 이후 WMS 콜백이나 송장 동기화가 오면 WMS 시각으로 맞춘다.

관리자 `POST /admin/orders/{orderId}/shipment/sync`는 WMS `GET /api/shipments/{orderId}`를 조회해 누락된 송장·`SHIPPED`·`DELIVERED` 상태와 시각을 복구한다. 조회는 WMS 재고나 송장을 변경하지 않는다.

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
| POST | `/orders/{orderId}/returns` | 배송 완료 주문의 고객 반품 신청 |
| GET | `/returns/{returnId}` | 본인 반품 상세 |
| GET | `/admin/orders` | 배송관리와 주문상품·백오더 원인 조회 |
| POST | `/admin/orders/ship` | 단건 출고 |
| POST | `/admin/orders/ships` | 선택 일괄 출고 |
| POST | `/admin/orders/deliver` | 배송 완료 |
| POST | `/admin/orders/{orderId}/shipment/sync` | WMS 송장·배송 상태 동기화 |
| GET | `/admin/inventory` | WMS 재고와 백오더 수량 조회 |
| GET/POST | `/admin/replenishment-requests` | 보충 요청 이력·제출 |

## REST 경로

| 메서드 | 경로 | 역할 |
|---|---|---|
| GET/POST/PATCH/DELETE | `/api/cart/**` | 장바구니 조회·변경 |
| POST | `/api/replenishments` | WMS 재고 증가 콜백, 전용 Basic 인증 |
| POST | `/api/return-status-events` | WMS RMA 완료·취소 콜백, 전용 Basic 인증 |
| POST | `/api/delivery-events` | WMS 배송 완료 콜백, 전용 Basic 인증 |

## 경계 규칙

- `oms/**`는 `com.jhg.hgpage.wms`를 import하지 않는다.
- OMS는 재고수량을 저장하지 않는다.
- OMS는 고객 반품 요청과 동기화 상태만 저장하며 WMS RMA 재고를 저장하지 않는다.
- `InventoryPort`는 예약·출고·해제를, `InventoryQueryPort`는 가용수량 조회를 담당한다.
- `StockReplenishedHandler`는 WMS가 재고 증가 사실만 알리고 OMS가 백오더 정책을 결정하게 한다.
- OMS 주문상태와 WMS 예약상태는 독립된 상태 머신이며 `orderId`로 연결된다.
- OMS 반품상태와 WMS RMA 상태는 독립된 상태 머신이며 `requestKey`와 `rmaId`로 연결된다.
