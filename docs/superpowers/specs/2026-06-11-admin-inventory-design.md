# B-1: 관리자 재고 조정/조회 백엔드 설계

날짜: 2026-06-11 / 상태: 승인됨 (로드맵 B-1)

## 문제

main.html 관리자 탭의 재고 조정 폼(`POST /admin/inventory/adjust`)과
재고조회 링크(`GET /admin/inventory`)에 백엔드가 없어 404. adjust 폼은
CSRF hidden 필드가 주석 처리되어 있어 백엔드가 생겨도 403이 난다.
(발주/입고 `/admin/purchase-orders*`는 B-2 범위)

## 결정 사항

1. **`InventoryService.adjust(productId, delta, reason)`** (신규, `@Transactional`):
   상품 조회(없으면 `EntityNotFoundException`) → `onHandQty + delta`가 음수면
   `IllegalArgumentException` → 반영 후 조정된 수량 반환. `reason`은 감사 테이블이
   없으므로 로그로만 남긴다(`Slf4j`). 동시 주문과의 충돌은 `@Version` 낙관적 락이 보호.
2. **`AdminController`** (`controller/admin/`):
   - `GET /admin/inventory`: 전체 상품+재고 목록 → `admin/inventory` 뷰.
   - `POST /admin/inventory/adjust`: 성공 시 flash `successMessage`,
     `IllegalArgumentException`은 catch해 flash `errorMessage` → 둘 다
     `redirect:/admin/inventory` (조정 결과가 바로 보이는 페이지로 이동).
3. **`ProductRepository.findAllWithInventory()`**: `join fetch`로 N+1 방지.
   `ProductService.findAllWithInventory()`가 위임.
4. **`templates/admin/inventory.html`** (신규): 공통 테마(끊김 없는 배경/카드/다크모드),
   상품 ID/이름/가격/보유수량 표 + flash 메시지 영역 + 메인으로 버튼.
5. **main.html**: adjust 폼의 CSRF hidden 필드 주석 해제.
6. 인가: SecurityConfig의 기존 `/admin/** hasRole(ADMIN)` 규칙 사용(변경 없음).

## 테스트

- `InventoryServiceTest`(Mockito): 증가/감소/음수 방지(재고 불변)/없는 상품.
- `AdminControllerMvcTest`(`@WebMvcTest` + `@Import(SecurityConfig)`):
  ADMIN 조회 200·뷰·모델 / USER 403 / 조정 성공 flash+redirect / 부족 시 에러 flash.
- `ProductRepositoryTest`(`@DataJpaTest`): fetch join으로 inventory가 초기화된 채 로드되는지.
