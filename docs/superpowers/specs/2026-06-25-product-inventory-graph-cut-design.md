# Phase 3 선결작업(0번) — Product↔Inventory 객체그래프 절단

> 작성일: 2026-06-25 · 상태: 승인됨, 구현 대기

## 배경 / 목표

Phase 3는 WMS를 별도 Spring Boot 앱 + 별도 DB로 물리 분리하는 단계다.
멘탈 모델: **OMS = 지점(재고 0, 손님/주문/백오더 명단 보유), WMS = 본사(재고 마스터 + 입고 전담)**.

물리 분리를 막는 진짜 장벽은 경계 포트가 아니라 **데이터 모델의 객체 그래프**다:

```
catalog/Product ──@OneToOne(inventory_id FK)──▶ wms/Inventory
       ▲                                              │
       └────────────@OneToOne(mappedBy)──────────────┘  (양방향, 같은 DB)
```

재고를 `product.getInventory()`로 객체 그래프를 타고 읽기 때문에, WMS가 다른 DB로
이사 가면 이 JPA 관계는 존재할 수 없다(WMS엔 Product 테이블이 없음).

**0번의 목표:** 모놀리스 내부에서 미리 이 그래프를 끊어, WMS가 재고를 `productId`로만
소유하게 만든다. **동작·DB데이터·화면·URL 전부 불변** — 바뀌는 건 "재고를 객체로 손잡기"를
"productId로 찾기"로 교체하는 것뿐. 이게 돼야 Phase 3에서 InventoryRepository 뒷단만
REST로 갈아끼우면 끝난다.

## 결정된 범위 (확정)

- **완전 분리**: `Inventory`가 `productId: Long`을 소유. `Product`는 재고를 전혀 모름.
- **재고 그래프만** 절단. `PurchaseOrderItem → Product`, `OrderItem → Product`,
  `CartItem → Product` 카탈로그 참조는 **유지**(범위 밖, Phase 3에서 필요 시 그때).

## 설계

### ① 도메인 — 관계 역전
- `Inventory`: `private Product product`(mappedBy 역참조) **삭제** → `private Long productId`
  추가(`@Column(name="product_id")`, unique). 정적 팩토리 `Inventory.create(productId)`.
  `@Version`·도메인 연산(`reserve`/`release`/`ship`/`addOnHandQty`/`getAvailableQty`) 그대로.
- `Product`: `@OneToOne inventory` 필드 + `addStock()` 메서드 **삭제**.
  → catalog가 더 이상 `wms.domain.Inventory`를 import 안 함(의존 방향 정리).
- FK 이동: `product.inventory_id` → `inventory.product_id`.

### ② 재고 접근 — `InventoryRepository` 신설 (`wms/repository`)
- `extends JpaRepository<Inventory, Long>`
- `Optional<Inventory> findByProductId(Long)`, `List<Inventory> findByProductIdIn(Collection<Long>)`
- `InventoryService`(InventoryPort + InventoryQueryPort 구현): `productRepository.findAll().getInventory()`
  경로 4곳을 `inventoryRepository.findByProductIdIn(ids)` 기반으로 교체. 동작 단언 불변.
- `InventoryAdjustmentService`: `product.getInventory()` → `inventoryRepository.findByProductId(id)`.

### ③ 입고 경로 — 도메인 순수화
- `PurchaseOrder.receive()`: 현재 `item.getProduct().addStock(qty)`로 재고를 직접 증가
  → **상태 전이(ORDERED→RECEIVED + 중복 입고 거부)만** 남김(Product에 재고가 없어 직접 증가 불가).
- `PurchaseOrderService.receive()`: 상태 전이 후 `item`들의 `productId→qty`로
  `inventoryRepository.findByProductIdIn` → `inventory.addOnHandQty(qty)`(더티체킹 저장).
  이어서 `stockReplenishedHandler.onReplenished(productIds)` 통지(그대로).
  → `Order` 도메인이 재고연산을 서비스에 위임하는 기존 패턴과 일치.

### ④ 시드(`initDb`)
- cascade ALL이 사라지므로 `product.setInventory(inv)` → product 저장 후
  `inventoryRepository.save(Inventory.create(product.getId()))` 명시 저장. 멱등 가드 그대로.

### ⑤ 화면 — 한 곳
- `ProductRepository.findAllWithInventory()`(`join fetch p.inventory`) **삭제**,
  `ProductService.findAllWithInventory()` 제거.
- 새 읽기: WMS가 `InventoryRow{ id, name, price, onHandQty }` 조립
  (`inventoryRepository.findAll()` + 카탈로그 `productRepository.findAllById(productIds)` 이름·가격) —
  메인 그리드 `ProductCardDto` 패턴 그대로. 조립 메서드는 `InventoryService`에 추가(새 서비스 X).
  Phase 3에서 WMS 재고조회 엔드포인트로 진화.
- `admin/inventory.html:152` `${p.inventory.onHandQty}` → `${p.onHandQty}`.
  `purchaseorders.html` 셀렉트는 `p.id`/`p.name`만 쓰니 같은 DTO로 충족.
- `main.html`은 이미 `ProductCardDto.availableQty` 사용 → 무관.

### ⑥ 테스트 (TDD RED→GREEN)
- `InventoryServiceTest`: 가용조회/예약/출고/해제가 productId 기반으로 동일 — 단언 보존, 배선만 교체.
- `PurchaseOrderServiceTest`: 입고 시 재고 증가 + `onReplenished` 통지 — 의도 불변, 증가 경로 검증 갱신.
- 신규 `InventoryRepositoryTest`(`@DataJpaTest`): `findByProductId(In)` + Product 없이 Inventory 단독 영속.
- `InventoryAdjustmentServiceTest`, `InitDbTest`: productId 배선으로 갱신.
- 풀 `gradlew build`: 순환 의존 없음(풀 컨텍스트 기동) + 전체 green.

## 비범위 / 의도적 단순화 (ponytail)
- 새 서비스 클래스 안 만듦 — `InventoryRow` 조립은 `InventoryService` 메서드 하나.
- `InventoryWriteService` 류 추상화 없음 — 입고 재고증가는 `PurchaseOrderService`가
  `InventoryRepository` 직접(WMS 내부, 포트 불필요).
- `PurchaseOrderItem → Product` 그래프 유지(카탈로그 참조, 범위 밖).
- `InventoryRow`는 화면이 쓰는 필드만(id/name/price/onHandQty). reserved/available 미노출(현행 화면과 동일).

## 리스크 / 마이그레이션
- FK가 `product → inventory`로 이동하는 **스키마 변경**이라 로컬/운영 H2(TCP)는
  `--spring.profiles.active=local`로 **1회 리셋** 필요(`ddl-auto: update`가 FK 이동을 못 따라감).
  테스트는 임베디드 create-drop이라 무관.

## 성공 기준
- 메인 그리드·주문·취소·출고·재고조정·발주·입고 동작 전부 이전과 동일.
- `admin/inventory` 화면이 보유 수량을 동일하게 표시.
- catalog 패키지가 wms를 import하지 않음(의존 방향 단방향).
- 전체 테스트 green, 풀 컨텍스트 기동 성공(순환 없음).
