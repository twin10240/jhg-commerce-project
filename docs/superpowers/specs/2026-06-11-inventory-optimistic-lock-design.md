# 재고 차감 낙관적 락 설계

날짜: 2026-06-11 / 상태: 승인됨

## 문제

`Product.removeStock()`이 락 없이 "조회→검사→차감"하므로, 마지막 재고를 두 사용자가
동시에 주문하면 둘 다 성공해 재고가 음수가 되거나 오버셀이 발생할 수 있다.

## 결정 사항

1. `Inventory`에 `@Version Long version` 추가 — JPA 낙관적 락.
   동시 수정 시 늦게 커밋하는 트랜잭션은 버전 불일치로 실패한다.
2. `GlobalExceptionHandler`에 `OptimisticLockingFailureException`(Spring DAO 계층 변환 타입) 핸들러 추가:
   - 화면 요청: flash "주문이 몰려 처리하지 못했습니다..." + `redirect:/main` (NotEnoughStock과 동일 패턴)
   - `/api/**`: 409 ProblemDetail
3. 비관적 락/재시도 로직은 도입하지 않음(YAGNI) — 충돌 시 사용자 재시도로 충분한 학습 프로젝트 규모.

## 테스트

- `InventoryOptimisticLockTest` (`@DataJpaTest`, 임베디드 H2):
  - 수정 후 flush 시 version 증가.
  - 다른 트랜잭션이 먼저 커밋한 뒤 stale 엔티티를 merge+flush하면 `OptimisticLockException`.
- `OrderControllerMvcTest`: 주문 중 `ObjectOptimisticLockingFailureException` 발생 시
  `/main` 리다이렉트 + `errorMessage` flash.
