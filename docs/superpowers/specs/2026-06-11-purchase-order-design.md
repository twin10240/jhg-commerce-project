# B-2: 발주/입고 (PurchaseOrder) 설계

날짜: 2026-06-11 / 상태: 승인됨 (로드맵 B-2)

## 문제

main.html 관리자 탭의 발주 생성(`POST /admin/purchase-orders`)과 입고 처리 폼에
백엔드가 없어 404. 두 폼 모두 CSRF hidden이 주석 상태. 입고 폼은
`th:action`의 `${poId}`가 모델에 없어 URL 자체가 깨진다(`/admin/purchase-orders//receive`).

## 도메인 설계

- `PurchaseOrderStatus` enum: `ORDERED`(발주됨) → `RECEIVED`(입고완료)
- `PurchaseOrder`: id, status(`@Enumerated(STRING)`), memo, createdAt, receivedAt,
  items(`@OneToMany cascade ALL`). 컨벤션대로 `@NoArgsConstructor(PROTECTED)` + 정적 팩토리.
  - `create(memo, items...)`: ORDERED + createdAt 초기화
  - `receive()`: 이미 RECEIVED면 `IllegalStateException`(중복 입고 = 재고 이중 증가 방지).
    각 품목의 `product.addStock(quantity)` 후 RECEIVED + receivedAt.
- `PurchaseOrderItem`: purchaseOrder(`@ManyToOne LAZY`), product(`@ManyToOne LAZY`), quantity.
- `Inventory.reservedQty`는 계속 미사용(결제 예약 용도로 남겨둠 — 발주와 무관).

## 서비스 / 컨트롤러

- `PurchaseOrderService`:
  - `create(List<PurchaseOrderLine>, memo)`: 품목 없음/수량<1 → `IllegalArgumentException`,
    상품 없음 → `EntityNotFoundException`. 저장 후 id 반환.
  - `receive(poId)`: PO 없음 → `EntityNotFoundException`, `po.receive()` 위임. id 반환.
  - `findAllWithItems()`: 관리자 화면용 fetch join 목록(최신순).
- `AdminController` 확장:
  - `POST /admin/purchase-orders` (`PurchaseOrderForm`: items[i].productId/quantity, memo)
    → 성공 flash / `IllegalArgumentException` 에러 flash → `redirect:/admin/inventory`
  - `POST /admin/purchase-orders/receive` (param `poId`) — HTML 폼은 입력값을 path에 넣을 수
    없으므로 path variable 대신 param 방식 채택. 성공/이미 입고/없는 PO 모두 flash로 안내.
  - `GET /admin/inventory`에 발주 목록(purchaseOrders) 모델 추가.

## 화면

- main.html: 발주/입고 폼 CSRF hidden 복원, 입고 폼 action을 `/admin/purchase-orders/receive`로 교체.
- admin/inventory.html: 발주 현황 테이블(번호/상태/품목/메모/생성일) 추가 — 입고할 PO 번호를 여기서 확인.

## 테스트

- `PurchaseOrderTest`(도메인): create 초기 상태 / receive 시 재고 증가+RECEIVED /
  중복 receive 거부+재고 불변.
- `PurchaseOrderServiceTest`(Mockito): create 검증·저장, 수량 0 거부, 없는 상품/PO.
- `PurchaseOrderRepositoryTest`(`@DataJpaTest`): findAllWithItems fetch join 초기화 확인.
- `AdminControllerMvcTest`: 발주 생성 성공 flash / 입고 성공 flash / 중복 입고 에러 flash.
