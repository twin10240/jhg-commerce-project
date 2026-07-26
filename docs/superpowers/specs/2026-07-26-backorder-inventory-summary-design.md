# Backorder Inventory Summary Design

## Goal

OMS 재고 현황에서 백오더 총수량을 실제 입고 필요 수량과 현재 재고로 충족 가능한 할당 대기 수량으로 나눠 표시한다.

## Metrics

- `backorderQty`: 백오더 주문의 전체 상품 수량 합계
- `inboundRequiredQty`: 상품별 `max(백오더 수요 - 가용재고, 0)`의 합계
- `allocationWaitingQty`: `backorderQty - inboundRequiredQty`

현재 데이터에서는 각각 24개, 2개, 22개다. 할당 대기 수량은 예약 완료 수량이 아니라 백오더 수요 중 현재 가용재고로 충족 가능한 부분이다.

## UI

기존 품절 SKU 수와 저재고 SKU 수를 유지하고, 요약 영역에 `백오더 총수량`, `입고 필요 수량`, `할당 대기 수량`을 표시한다. 총 5개 지표가 데스크톱과 모바일에서 겹치지 않도록 반응형 그리드를 사용한다.

## Verification

MVC 테스트에서 여러 상품의 백오더 수요와 가용재고를 사용해 총 24개가 입고 필요 2개와 할당 대기 22개로 계산되는지 검증한다.
