# Admin Backorder Items Design

## Goal

OMS 배송관리 목록에서 관리자가 주문별 상품과 수량을 확인하고, 백오더를 유발한 재고 부족 상품을 바로 식별할 수 있게 한다.

## Scope

- 모든 주문에 상품명과 주문 수량을 표시한다.
- `BACKORDERED` 주문에서 상품별 주문 수량이 현재 WMS 가용재고보다 큰 상품에만 `입고 필요` 배지를 표시한다.
- 관리자 목록에 이미 있는 주문자 이름 외 주소, 연락처, 결제정보는 추가하지 않는다.
- 별도 주문 상세 화면이나 새 API는 만들지 않는다.

## Data Flow

`OrderService.findAllForAdmin()`이 기존 주문 목록을 조회한 뒤 백오더 상품 ID를 모아 `InventoryQueryPort.availableByProductIds()`를 한 번 호출한다. `AdminOrderDto`는 주문 품목과 가용재고를 비교해 각 품목의 `inboundRequired` 값을 만든다. Thymeleaf는 계산 없이 상품명, 수량, 배지만 렌더링한다.

## Verification

- 서비스 테스트: 백오더 상품 중 가용재고가 부족한 상품만 `inboundRequired=true`
- MVC 테스트: 배송관리 HTML에 상품명·수량과 `입고 필요` 배지가 출력됨
- 전체 테스트와 실행 중 OMS 관리자 화면 확인
