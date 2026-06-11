# C: 품절 사용자 UX 설계

날짜: 2026-06-11 / 상태: 승인됨 (로드맵 C)

## 문제

메인 상품 카드가 재고와 무관하게 항상 구매 가능해 보인다. 재고 0인 상품도
구매/장바구니 버튼이 활성화되어 있고, 사용자는 주문 확정 단계에서야
"재고 부족" flash로 실패를 알게 된다.

## 결정 사항

1. **메인 상품 카드 재고 표시**:
   - 재고 0: "품절" 배지 + 카드 흐림(soldout) + 수량 입력/바로구매/장바구니 버튼 disabled
   - 재고 1~9: "N개 남음" 표시 (구매 유도 + 실패 예방)
   - 재고 10 이상: 표시 없음
   - 수량 입력에 `max=재고` 부여
   - `p.inventory`가 null이면 0으로 간주(방어)
2. **페이징 쿼리 fetch join**: 카드가 재고를 읽으므로 `ProductRepository`에
   `findPageWithInventory` / `findPageByNameWithInventory`(둘 다 `join fetch` + countQuery)를
   추가하고 `ProductService.findPage`가 위임. OSIV 의존 N+1(페이지당 10쿼리) 제거.
3. 서버 측 강제는 기존 그대로(`removeStock`의 NotEnoughStockException + 낙관적 락) —
   이 작업은 표시 계층만 다룬다. 장바구니에 이미 담긴 품절 상품은 기존 주문 흐름의
   재고 부족 처리에 맡긴다(범위 외).

## 테스트

- `ProductRepositoryTest`: 두 페이징 쿼리가 inventory를 초기화된 채 로드 + 키워드 필터 동작.
- `ProductServiceFindPageTest`: 위임 대상을 새 메서드로 갱신.
- `MainControllerMvcTest`: 재고 0 → "품절"+disabled 렌더링 / 재고 3 → "3개 남음" /
  재고 충분 → 품절·남은수량 미표시.
