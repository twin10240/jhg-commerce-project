# 주문서(구매상세) 상품 선택 주문 설계

날짜: 2026-06-11 / 상태: 승인됨

## 문제

주문서에 담긴 상품은 전부 주문된다. `OrderController.checkout()`이 `form.getProduct()` 전체를
OrderLine으로 변환하므로, 여러 상품 중 일부만 골라 주문할 수 없다.

## 결정 사항

1. `CheckOutForm.ProductDto`에 `boolean selected = true` 추가.
   - 기본값 true → 단건 "바로 구매"와 기존 raw POST 흐름은 동작 불변.
   - 화면 체크박스는 `th:field`로 바인딩(해제 시 hidden 마커로 false 전송).
2. `checkout()`은 `selected == true`인 상품만 주문. 선택이 0개면
   `bindingResult.rejectValue("product", ...)` 후 주문서 재렌더링(기존 빈 주문 에러 영역 재사용).
3. orderdetail.html 상품 표:
   - 행 맨 앞 선택 체크박스 + 헤더 전체 선택/해제.
   - 해제된 행은 흐리게 + 수량 readonly(disabled 금지 — disabled는 폼 전송에서 빠져 @Min 위반 유발).
   - 결제 요약/총 개수는 선택된 상품만 합산.
4. 수량 @Min(1) 검증은 선택 여부와 무관하게 전체 적용(화면에서 1 미만 입력 불가하므로 실용상 무해).

## 테스트

- `OrderControllerMvcTest`:
  - 일부 선택 → 선택된 상품만 `orderService.order`의 lines에 포함 (ArgumentCaptor).
  - 전부 해제 → 주문 호출 없음, orderdetail 재렌더링 + `product` 필드 에러.
- 기존 테스트는 selected 기본값 true로 무수정 통과해야 함.
