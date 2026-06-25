# Product↔Inventory 객체그래프 절단 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WMS가 재고를 `productId`로만 소유하도록 `catalog/Product ↔ wms/Inventory` JPA 양방향 객체그래프를 끊는다(동작·DB데이터·화면·URL 불변).

**Architecture:** Expand-contract(병행 변경) 리팩터. 먼저 `Inventory.productId` + `InventoryRepository`를 추가하고(기존 그래프 유지), 소비자(InventoryService·InventoryAdjustmentService·입고·관리자화면)를 productId 경로로 하나씩 이전한 뒤, 마지막에 `Product.inventory`/`Inventory.product` 그래프를 제거한다. 각 커밋은 컴파일·테스트 green 유지.

**Tech Stack:** Java 17, Spring Boot 3.5.5, Spring Data JPA(Hibernate), JUnit5 + Mockito + AssertJ, 임베디드 H2(test).

## Global Constraints

- 서비스는 클래스 레벨 `@Transactional(readOnly = true)`, 쓰기 메서드에만 `@Transactional`.
- ID 조회 실패는 `orElseThrow(() -> new EntityNotFoundException(대상, id))`.
- 복잡 조회는 `*RepositoryQuery` QueryDSL, 단순 조회는 Spring Data 파생 쿼리.
- 빌드/테스트: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"` 후 `.\gradlew.bat test` / `build`.
- 테스트는 임베디드 H2(create-drop)라 TCP 서버 불필요. 동작·DB데이터·화면·URL 불변이 성공 기준.
- `Inventory.productId`는 plain nullable `Long`. // ponytail: 1:1 유일성은 시드/앱이 보장. WMS 동시 insert가 생기면 unique index 추가.

---

### Task 1: Inventory.productId + InventoryRepository (Expand)

기존 `Product↔Inventory` 그래프를 **그대로 둔 채** 새 경로를 추가한다. 이 커밋 후엔 두 경로가 공존한다.

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/wms/domain/Inventory.java`
- Create: `src/main/java/com/jhg/hgpage/wms/repository/InventoryRepository.java`
- Modify: `src/main/java/com/jhg/hgpage/initDb.java:61-74`
- Test: `src/test/java/com/jhg/hgpage/repository/InventoryRepositoryTest.java` (new)

**Interfaces:**
- Produces:
  - `Inventory.create(Long productId)` → 새 Inventory(`productId` 세팅, onHand/reserved는 기존 `@Setter`로).
  - `Inventory.getProductId()` / `setProductId(Long)` (Lombok `@Getter/@Setter`).
  - `InventoryRepository extends JpaRepository<Inventory, Long>` with
    `Optional<Inventory> findByProductId(Long productId)`,
    `List<Inventory> findByProductIdIn(Collection<Long> productIds)`.

- [ ] **Step 1: 실패하는 테스트 작성** — `InventoryRepositoryTest.java`

```java
package com.jhg.hgpage.repository;

import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inventory를 Product 객체그래프 없이 productId만으로 단독 영속/조회한다.
 * (구 ProductInventoryPersistenceTest의 cascade 검증을 대체)
 */
@DataJpaTest
class InventoryRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired InventoryRepository inventoryRepository;

    private Long persistInventory(long productId, int onHand) {
        Inventory inv = Inventory.create(productId);
        inv.setOnHandQty(onHand);
        inv.setReservedQty(0);
        return em.persistAndFlush(inv).getId();
    }

    @Test
    void productId로_재고를_단독_조회한다() {
        persistInventory(1L, 30);
        em.clear();

        Inventory found = inventoryRepository.findByProductId(1L).orElseThrow();

        assertThat(found.getProductId()).isEqualTo(1L);
        assertThat(found.getOnHandQty()).isEqualTo(30);
        assertThat(found.getAvailableQty()).isEqualTo(30);
    }

    @Test
    void productId_묶음으로_재고를_일괄_조회한다() {
        persistInventory(1L, 10);
        persistInventory(2L, 20);
        persistInventory(3L, 30);
        em.clear();

        List<Inventory> found = inventoryRepository.findByProductIdIn(List.of(1L, 3L));

        assertThat(found).extracting(Inventory::getProductId)
                .containsExactlyInAnyOrder(1L, 3L);
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `.\gradlew.bat test --tests "com.jhg.hgpage.repository.InventoryRepositoryTest"`
Expected: 컴파일 실패 — `Inventory.create`, `getProductId`, `InventoryRepository` 미존재.

- [ ] **Step 3: Inventory에 productId + 팩토리 추가** — `Inventory.java`

`@OneToOne ... private Product product;` 필드는 **그대로 둔다**. 아래를 추가한다(필드 영역에 `productId`, 메서드 영역에 팩토리):

```java
    @Column(name = "product_id")
    private Long productId;
```

```java
    /** WMS가 productId만으로 소유하는 재고를 생성한다(onHand/reserved는 setter로). */
    public static Inventory create(Long productId) {
        Inventory inventory = new Inventory();
        inventory.productId = productId;
        return inventory;
    }
```

- [ ] **Step 4: InventoryRepository 생성** — `InventoryRepository.java`

```java
package com.jhg.hgpage.wms.repository;

import com.jhg.hgpage.wms.domain.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductId(Long productId);

    List<Inventory> findByProductIdIn(Collection<Long> productIds);
}
```

- [ ] **Step 5: 시드가 productId도 채우도록 수정** — `initDb.java` `initProduct()`

`em.persist(product)` 직후 productId를 세팅한다(cascade는 그대로 유지). 메서드 전체를 아래로 교체:

```java
        public void initProduct() {
            for (int i = 0; i < 20; i++) {
                Inventory inventory = new Inventory();
                inventory.setOnHandQty(15 * (i + 1));
                inventory.setReservedQty(0);

                Product product = new Product();
                product.setName("상품" + (i + 1));
                product.setPrice(10000 + (i * 1000));
                product.setInventory(inventory);

                em.persist(product); // cascade로 inventory도 저장되며 id 부여
                inventory.setProductId(product.getId()); // 새 경로용 productId 채움(더티체킹 flush)
            }
        }
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `.\gradlew.bat test --tests "com.jhg.hgpage.repository.InventoryRepositoryTest"`
Expected: PASS (2건).

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jhg/hgpage/wms/domain/Inventory.java \
        src/main/java/com/jhg/hgpage/wms/repository/InventoryRepository.java \
        src/main/java/com/jhg/hgpage/initDb.java \
        src/test/java/com/jhg/hgpage/repository/InventoryRepositoryTest.java
git commit -m "feat: Inventory에 productId 소유 경로 추가(InventoryRepository) — 그래프 절단 1/5 expand"
```

---

### Task 2: InventoryService를 productId 경로로 이전

포트 구현(`InventoryService`)이 `product.getInventory()` 대신 `InventoryRepository`를 쓰게 한다. 동작 단언은 보존, 배선만 교체.

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/wms/service/InventoryService.java`
- Test: `src/test/java/com/jhg/hgpage/service/InventoryServiceTest.java` (재작성)

**Interfaces:**
- Consumes: `InventoryRepository.findByProductIdIn` (Task 1).
- Produces: `InventoryService`(`InventoryPort`+`InventoryQueryPort`) 동작 불변 — `reserveAll`/`shipAll`/`releaseAll`/`availableByProductIds` 시그니처 유지. 없는 productId는 `EntityNotFoundException("Inventory", id)`.

- [ ] **Step 1: 테스트 재작성(실패 상태)** — `InventoryServiceTest.java` 전체 교체

```java
package com.jhg.hgpage.service;

import com.jhg.hgpage.wms.service.InventoryService;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * InventoryPort 구현(예약/해제/출고) — productId 기반으로 Inventory를 직접 조회·변경한다.
 * 예약은 전부-아니면-실패(원자적)이며, 가용수량(onHand-reserved) 기준으로 판정한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock InventoryRepository inventoryRepository;
    @InjectMocks InventoryService inventoryService;

    private Inventory inventoryOf(long productId, int onHand, int reserved) {
        Inventory inv = Inventory.create(productId);
        inv.setOnHandQty(onHand);
        inv.setReservedQty(reserved);
        return inv;
    }

    @Test
    void 전_라인이_가용하면_모두_예약하고_true를_반환한다() {
        Inventory i1 = inventoryOf(1L, 10, 0);
        Inventory i2 = inventoryOf(2L, 10, 0);
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i1, i2));

        boolean reserved = inventoryService.reserveAll(Map.of(1L, 2, 2L, 1));

        assertThat(reserved).isTrue();
        assertThat(i1.getReservedQty()).isEqualTo(2);
        assertThat(i2.getReservedQty()).isEqualTo(1);
    }

    @Test
    void 한_라인이라도_부족하면_아무것도_예약하지_않고_false를_반환한다() {
        Inventory i1 = inventoryOf(1L, 10, 0);
        Inventory i2 = inventoryOf(2L, 1, 0); // 가용 1인데 5 요청 → 부족
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i1, i2));

        boolean reserved = inventoryService.reserveAll(Map.of(1L, 2, 2L, 5));

        assertThat(reserved).isFalse();
        assertThat(i1.getReservedQty()).isEqualTo(0);
        assertThat(i2.getReservedQty()).isEqualTo(0);
    }

    @Test
    void 예약은_가용수량_기준이며_예약분을_제외하고_판정한다() {
        Inventory i = inventoryOf(1L, 10, 8); // 가용 2
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        assertThat(inventoryService.reserveAll(Map.of(1L, 2))).isTrue();
        assertThat(i.getReservedQty()).isEqualTo(10);
    }

    @Test
    void 출고하면_예약과_실물이_차감된다() {
        Inventory i = inventoryOf(1L, 10, 2);
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        inventoryService.shipAll(Map.of(1L, 2));

        assertThat(i.getOnHandQty()).isEqualTo(8);
        assertThat(i.getReservedQty()).isEqualTo(0);
    }

    @Test
    void 해제하면_예약분이_복구된다() {
        Inventory i = inventoryOf(1L, 10, 3);
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        inventoryService.releaseAll(Map.of(1L, 3));

        assertThat(i.getReservedQty()).isEqualTo(0);
        assertThat(i.getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 없는_상품을_예약하면_EntityNotFoundException을_던진다() {
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> inventoryService.reserveAll(Map.of(99L, 1)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void 가용수량을_상품id별로_조회한다() {
        Inventory i1 = inventoryOf(1L, 10, 3); // 가용 7
        Inventory i2 = inventoryOf(2L, 5, 5);  // 가용 0
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i1, i2));

        Map<Long, Integer> available = inventoryService.availableByProductIds(List.of(1L, 2L));

        assertThat(available).containsEntry(1L, 7).containsEntry(2L, 0);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `.\gradlew.bat test --tests "com.jhg.hgpage.service.InventoryServiceTest"`
Expected: 컴파일/단언 실패 — InventoryService가 아직 ProductRepository 기반.

- [ ] **Step 3: InventoryService 재배선** — `InventoryService.java` 전체 교체

```java
package com.jhg.hgpage.wms.service;

import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.contract.InventoryQueryPort;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * InventoryPort 구현(WMS 재고 변경: 예약/해제/출고) + InventoryQueryPort(가용수량 조회).
 * productId로 Inventory를 직접 조회·변경한다(Product 객체그래프 미사용).
 * <p>OMS의 백오더 승격 트리거는 의도적으로 갖지 않는다({@link InventoryAdjustmentService}로 분리 — 생성자 순환 회피).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService implements InventoryPort, InventoryQueryPort {

    private final InventoryRepository inventoryRepository;

    @Override
    public Map<Long, Integer> availableByProductIds(Collection<Long> productIds) {
        return inventoryRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Inventory::getAvailableQty));
    }

    @Override
    @Transactional
    public boolean reserveAll(Map<Long, Integer> qtyByProductId) {
        Map<Long, Inventory> inventories = loadInventories(qtyByProductId.keySet());

        boolean allAvailable = qtyByProductId.entrySet().stream()
                .allMatch(e -> inventories.get(e.getKey()).getAvailableQty() >= e.getValue());
        if (!allAvailable) {
            return false;
        }
        qtyByProductId.forEach((productId, qty) -> inventories.get(productId).reserve(qty));
        return true;
    }

    @Override
    @Transactional
    public void shipAll(Map<Long, Integer> qtyByProductId) {
        applyToInventories(qtyByProductId, Inventory::ship);
    }

    @Override
    @Transactional
    public void releaseAll(Map<Long, Integer> qtyByProductId) {
        applyToInventories(qtyByProductId, Inventory::release);
    }

    private void applyToInventories(Map<Long, Integer> qtyByProductId, BiConsumer<Inventory, Integer> operation) {
        Map<Long, Inventory> inventories = loadInventories(qtyByProductId.keySet());
        qtyByProductId.forEach((productId, qty) -> operation.accept(inventories.get(productId), qty));
    }

    /** productId 묶음으로 Inventory를 일괄 로드한다(N+1 회피). 누락 시 EntityNotFoundException. */
    private Map<Long, Inventory> loadInventories(Collection<Long> productIds) {
        Map<Long, Inventory> inventories = inventoryRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Function.identity()));
        for (Long productId : productIds) {
            if (!inventories.containsKey(productId)) {
                throw new EntityNotFoundException("Inventory", productId);
            }
        }
        return inventories;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `.\gradlew.bat test --tests "com.jhg.hgpage.service.InventoryServiceTest"`
Expected: PASS (7건).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jhg/hgpage/wms/service/InventoryService.java \
        src/test/java/com/jhg/hgpage/service/InventoryServiceTest.java
git commit -m "refactor: InventoryService를 InventoryRepository(productId) 경로로 이전 — 그래프 절단 2/5"
```

---

### Task 3: 재고 조정 + 입고 경로를 productId로 이전

`InventoryAdjustmentService`와 입고(`PurchaseOrder.receive`/`PurchaseOrderService.receive`)를 productId 경로로 옮긴다. `PurchaseOrder.receive()`는 상태 전이만 남기고 재고 증가는 서비스가 수행.

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/wms/service/InventoryAdjustmentService.java`
- Modify: `src/main/java/com/jhg/hgpage/wms/domain/PurchaseOrder.java:50-59`
- Modify: `src/main/java/com/jhg/hgpage/wms/service/PurchaseOrderService.java`
- Test: `src/test/java/com/jhg/hgpage/service/InventoryAdjustmentServiceTest.java` (재작성)
- Test: `src/test/java/com/jhg/hgpage/service/PurchaseOrderServiceTest.java` (재작성)
- Test: `src/test/java/com/jhg/hgpage/domain/PurchaseOrderTest.java` (재작성)

**Interfaces:**
- Consumes: `InventoryRepository.findByProductId`, `findByProductIdIn` (Task 1); `Inventory.addOnHandQty`.
- Produces:
  - `InventoryAdjustmentService.adjust(Long productId, int delta, String reason)` 동작 불변.
  - `PurchaseOrder.receive()` → 상태 전이(ORDERED→RECEIVED, 중복 거부)만. **재고 미변경.**
  - `PurchaseOrderService.receive(Long poId)` → 상태 전이 후 재고 증가 + `onReplenished` 통지.

- [ ] **Step 1: InventoryAdjustmentService 테스트 재작성(실패)** — `InventoryAdjustmentServiceTest.java` 전체 교체

```java
package com.jhg.hgpage.service;

import com.jhg.hgpage.wms.service.InventoryAdjustmentService;
import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 재고 조정 + 재고 증가 시 백오더 승격 트리거.
 * productId로 Inventory를 직접 조회한다(Product 객체그래프 미사용).
 */
@ExtendWith(MockitoExtension.class)
class InventoryAdjustmentServiceTest {

    @Mock InventoryRepository inventoryRepository;
    @Mock StockReplenishedHandler stockReplenishedHandler;
    @InjectMocks InventoryAdjustmentService inventoryAdjustmentService;

    private Inventory inventoryOf(int onHand) {
        Inventory inv = Inventory.create(1L);
        inv.setOnHandQty(onHand);
        return inv;
    }

    @Test
    void 재고를_증가시킨다() {
        Inventory inv = inventoryOf(10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        int adjusted = inventoryAdjustmentService.adjust(1L, 5, "정기조사");

        assertThat(adjusted).isEqualTo(15);
        assertThat(inv.getOnHandQty()).isEqualTo(15);
    }

    @Test
    void 재고를_감소시킨다() {
        Inventory inv = inventoryOf(10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        int adjusted = inventoryAdjustmentService.adjust(1L, -3, "파손");

        assertThat(adjusted).isEqualTo(7);
        assertThat(inv.getOnHandQty()).isEqualTo(7);
    }

    @Test
    void 재고가_음수가_되는_조정은_거부하고_재고를_보존한다() {
        Inventory inv = inventoryOf(10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> inventoryAdjustmentService.adjust(1L, -11, "조정"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(inv.getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 없는_상품을_조정하면_EntityNotFoundException을_던진다() {
        when(inventoryRepository.findByProductId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryAdjustmentService.adjust(99L, 1, "조정"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void 재고를_증가시키면_백오더_할당을_트리거한다() {
        Inventory inv = inventoryOf(10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        inventoryAdjustmentService.adjust(1L, 5, "정기조사");

        verify(stockReplenishedHandler).onReplenished(List.of(1L));
    }

    @Test
    void 재고_감소는_백오더_할당을_트리거하지_않는다() {
        Inventory inv = inventoryOf(10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        inventoryAdjustmentService.adjust(1L, -3, "파손");

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }

    @Test
    void 예약된_수량_아래로_감소시키는_조정은_거부한다() {
        Inventory inv = inventoryOf(10);
        inv.setReservedQty(4);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> inventoryAdjustmentService.adjust(1L, -7, "조정"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(inv.getOnHandQty()).isEqualTo(10);
    }
}
```

- [ ] **Step 2: InventoryAdjustmentService 재배선** — `InventoryAdjustmentService.java`

`productRepository` 의존을 `inventoryRepository`로 교체하고 `adjust` 본문의 조회를 바꾼다. import도 `ProductRepository`/`Product` 제거, `InventoryRepository` 추가. 핵심부:

```java
    private final InventoryRepository inventoryRepository;
    private final StockReplenishedHandler stockReplenishedHandler;
```

```java
    @Transactional
    public int adjust(Long productId, int delta, String reason) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new EntityNotFoundException("Inventory", productId));

        int adjusted = inventory.getOnHandQty() + delta;
        if (adjusted < 0) {
            throw new IllegalArgumentException("재고는 0 미만이 될 수 없습니다. (현재 " + inventory.getOnHandQty() + "개)");
        }
        if (adjusted < inventory.getReservedQty()) {
            throw new IllegalArgumentException("예약된 수량(" + inventory.getReservedQty() + "개) 미만으로 줄일 수 없습니다.");
        }

        inventory.setOnHandQty(adjusted);
        log.info("재고 조정: productId={}, delta={}, adjusted={}, reason={}", productId, delta, adjusted, reason);

        if (delta > 0) {
            stockReplenishedHandler.onReplenished(List.of(productId));
        }
        return adjusted;
    }
```

> 주의: `없는_상품을_조정하면` 테스트는 메시지에 "99"만 포함되면 되므로 `EntityNotFoundException("Inventory", 99L)`로 충분(대상 라벨이 Product→Inventory로 바뀜).

- [ ] **Step 3: PurchaseOrder.receive() 도메인 순수화** — `PurchaseOrder.java:50-59`

```java
    /** 입고 처리: 상태만 ORDERED→RECEIVED로 전이한다. 중복 입고는 거부.
     *  실물 재고 증가는 서비스(PurchaseOrderService)가 WMS 재고에 위임한다. */
    public void receive() {
        if (status == PurchaseOrderStatus.RECEIVED) {
            throw new IllegalStateException("이미 입고 처리된 발주입니다. (발주 #" + id + ")");
        }
        this.status = PurchaseOrderStatus.RECEIVED;
        this.receivedAt = LocalDateTime.now();
    }
```

- [ ] **Step 4: PurchaseOrderService.receive() 재고 증가 위임** — `PurchaseOrderService.java`

`InventoryRepository` 의존 추가, import 추가(`Inventory`, `InventoryRepository`, `java.util.Map`, `java.util.function.Function`, `java.util.stream.Collectors`). `receive` 교체:

```java
    private final ProductRepository productRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final InventoryRepository inventoryRepository;
    private final StockReplenishedHandler stockReplenishedHandler;
```

```java
    @Transactional
    public Long receive(Long poId) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new EntityNotFoundException("PurchaseOrder", poId));

        purchaseOrder.receive(); // 상태 전이(중복 입고 거부)

        // 입고 수량만큼 실물 재고를 늘린다(상품별 합산)
        Map<Long, Integer> qtyByProductId = purchaseOrder.getItems().stream()
                .collect(Collectors.toMap(item -> item.getProduct().getId(),
                        PurchaseOrderItem::getQuantity, Integer::sum));
        Map<Long, Inventory> inventories = inventoryRepository.findByProductIdIn(qtyByProductId.keySet()).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Function.identity()));
        qtyByProductId.forEach((productId, qty) -> inventories.get(productId).addOnHandQty(qty));

        // 입고로 가용분이 생겼음을 통지한다(백오더 승격은 OMS 구현체가 처리)
        stockReplenishedHandler.onReplenished(List.copyOf(qtyByProductId.keySet()));

        return purchaseOrder.getId();
    }
```

- [ ] **Step 5: PurchaseOrderService 테스트 재작성** — `PurchaseOrderServiceTest.java`

`@Mock InventoryRepository inventoryRepository;` 추가. `productWithStock` 헬퍼를 productId 상품 + 별도 Inventory로 바꾸고 입고 2건을 재고 mock 기반으로 교체. import에 `InventoryRepository` 추가. 변경 메서드:

```java
    @Mock InventoryRepository inventoryRepository;
```

```java
    private Product productWithId(long id) {
        Product product = new Product();
        product.setId(id);
        product.setName("상품" + id);
        return product;
    }

    @Test
    void 입고하면_발주의_receive가_실행되어_재고가_늘어난다() {
        Product product = productWithId(1L);
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(product, 5));
        Inventory inv = Inventory.create(1L);
        inv.setOnHandQty(10);
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(inv));

        purchaseOrderService.receive(7L);

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(inv.getOnHandQty()).isEqualTo(15);
    }

    @Test
    void 입고하면_입고된_상품들의_백오더_할당을_트리거한다() {
        Product product = productWithId(1L);
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(product, 5));
        Inventory inv = Inventory.create(1L);
        inv.setOnHandQty(10);
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(inv));

        purchaseOrderService.receive(7L);

        verify(stockReplenishedHandler).onReplenished(List.of(1L));
    }

    @Test
    void 중복_입고가_거부되면_백오더_할당도_트리거하지_않는다() {
        Product product = productWithId(1L);
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(product, 5));
        po.receive(); // 이미 입고됨
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> purchaseOrderService.receive(7L))
                .isInstanceOf(IllegalStateException.class);

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }
```

> 기존 `productWithStock(int)` 헬퍼와 그 import(`Inventory`는 위에서 다시 씀)는 `productWithId`로 대체되며, `발주를_생성하면…`·`품목이_없으면…`·`수량이_1_미만…`·`없는_상품을_발주…`·`없는_발주를_입고…` 테스트는 `productWithStock(10)` 호출만 `productWithId(1L)`로 바꾸면 된다(재고 무관). `create`류는 `findByProductIdIn` 스텁이 필요 없다.

- [ ] **Step 6: PurchaseOrder 도메인 테스트 재작성** — `PurchaseOrderTest.java` 전체 교체

```java
package com.jhg.hgpage.domain;

import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.wms.domain.enums.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PurchaseOrder 도메인은 상태 전이(ORDERED→RECEIVED, 중복 거부)만 책임진다.
 * 실물 재고 증가는 서비스가 WMS 재고에 위임하므로 여기선 재고를 다루지 않는다.
 */
class PurchaseOrderTest {

    private Product productOf(String name) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(10000);
        return product;
    }

    @Test
    void 발주를_생성하면_ORDERED_상태로_초기화된다() {
        PurchaseOrder po = PurchaseOrder.create("긴급 발주", PurchaseOrderItem.create(productOf("상품"), 5));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(po.getMemo()).isEqualTo("긴급 발주");
        assertThat(po.getCreatedAt()).isNotNull();
        assertThat(po.getReceivedAt()).isNull();
        assertThat(po.getItems()).hasSize(1);
    }

    @Test
    void 입고하면_RECEIVED가_되고_입고시각이_찍힌다() {
        PurchaseOrder po = PurchaseOrder.create("정기 발주",
                PurchaseOrderItem.create(productOf("상품1"), 5),
                PurchaseOrderItem.create(productOf("상품2"), 20));

        po.receive();

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
    }

    @Test
    void 이미_입고된_발주는_다시_입고할_수_없다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(productOf("상품"), 5));
        po.receive();

        assertThatThrownBy(po::receive).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 7: 세 테스트 통과 확인**

Run: `.\gradlew.bat test --tests "com.jhg.hgpage.service.InventoryAdjustmentServiceTest" --tests "com.jhg.hgpage.service.PurchaseOrderServiceTest" --tests "com.jhg.hgpage.domain.PurchaseOrderTest"`
Expected: PASS (전부).

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jhg/hgpage/wms/service/InventoryAdjustmentService.java \
        src/main/java/com/jhg/hgpage/wms/domain/PurchaseOrder.java \
        src/main/java/com/jhg/hgpage/wms/service/PurchaseOrderService.java \
        src/test/java/com/jhg/hgpage/service/InventoryAdjustmentServiceTest.java \
        src/test/java/com/jhg/hgpage/service/PurchaseOrderServiceTest.java \
        src/test/java/com/jhg/hgpage/domain/PurchaseOrderTest.java
git commit -m "refactor: 재고조정·입고 경로를 productId(InventoryRepository)로 이전, receive 도메인 순수화 — 그래프 절단 3/5"
```

---

### Task 4: 관리자 재고화면을 DTO 조립으로 이전

`admin/inventory`·`admin/purchase-orders`가 `product.inventory` 객체그래프 대신 `InventoryRow` DTO를 쓰게 한다.

**Files:**
- Create: `src/main/java/com/jhg/hgpage/wms/dto/InventoryRow.java`
- Modify: `src/main/java/com/jhg/hgpage/wms/service/InventoryService.java` (조립 메서드 추가)
- Modify: `src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java:24-41`
- Modify: `src/main/resources/templates/admin/inventory.html:152`
- Test: `src/test/java/com/jhg/hgpage/service/InventoryServiceTest.java` (조립 테스트 +1)
- Test: `src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java` (배선 갱신)

**Interfaces:**
- Consumes: `InventoryRepository.findAll`, `ProductRepository.findAllById`.
- Produces: `record InventoryRow(Long id, String name, int price, int onHandQty)` (Thymeleaf 접근: `${p.id}`/`${p.name}`/`${p.price}`/`${p.onHandQty}` — `ProductCardDto`와 동일한 record 접근 방식); `InventoryService.findInventoryRows()` → `List<InventoryRow>`(id 오름차순).

- [ ] **Step 1: InventoryRow DTO 생성** — `InventoryRow.java`

```java
package com.jhg.hgpage.wms.dto;

/** 관리자 재고화면 행: 카탈로그(id/name/price) + WMS 보유수량(onHandQty)을 합친 뷰 DTO. */
public record InventoryRow(Long id, String name, int price, int onHandQty) {}
```

- [ ] **Step 2: 조립 테스트 추가(실패)** — `InventoryServiceTest.java`에 아래 추가

상단 import에 다음을 추가:
```java
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.wms.dto.InventoryRow;
```
`@Mock ProductRepository productRepository;`를 필드에 추가(InjectMocks가 함께 주입). 테스트 메서드:

```java
    @Test
    void 재고행을_카탈로그_이름가격과_보유수량으로_조립한다() {
        Inventory i1 = inventoryOf(1L, 30, 0);
        Inventory i2 = inventoryOf(2L, 0, 0);
        Product p1 = new Product(); p1.setId(1L); p1.setName("상품1"); p1.setPrice(10000);
        Product p2 = new Product(); p2.setId(2L); p2.setName("상품2"); p2.setPrice(11000);
        when(inventoryRepository.findAll()).thenReturn(List.of(i1, i2));
        when(productRepository.findAllById(any())).thenReturn(List.of(p1, p2));

        List<InventoryRow> rows = inventoryService.findInventoryRows();

        assertThat(rows).extracting(InventoryRow::id).containsExactly(1L, 2L);
        assertThat(rows.get(0)).extracting(InventoryRow::name, InventoryRow::price, InventoryRow::onHandQty)
                .containsExactly("상품1", 10000, 30);
        assertThat(rows.get(1).onHandQty()).isEqualTo(0);
    }
```

- [ ] **Step 3: 실패 확인**

Run: `.\gradlew.bat test --tests "com.jhg.hgpage.service.InventoryServiceTest"`
Expected: 컴파일 실패 — `findInventoryRows` 미존재.

- [ ] **Step 4: InventoryService에 ProductRepository + findInventoryRows 추가**

import 추가(`com.jhg.hgpage.catalog.Product`, `com.jhg.hgpage.catalog.ProductRepository`, `com.jhg.hgpage.wms.dto.InventoryRow`, `java.util.Comparator`, `java.util.List`). 의존성에 `ProductRepository` 추가(WMS가 카탈로그를 읽는 건 `PurchaseOrderService` 선례와 동일):

```java
    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
```

메서드 추가:

```java
    /** 관리자 재고화면 행 조립: WMS 재고(보유수량) + 카탈로그(이름·가격)를 productId로 합친다. */
    public List<InventoryRow> findInventoryRows() {
        List<Inventory> inventories = inventoryRepository.findAll();
        List<Long> productIds = inventories.stream().map(Inventory::getProductId).toList();
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return inventories.stream()
                .map(inv -> {
                    Product p = products.get(inv.getProductId());
                    return new InventoryRow(p.getId(), p.getName(), p.getPrice(), inv.getOnHandQty());
                })
                .sorted(Comparator.comparing(InventoryRow::id))
                .toList();
    }
```

- [ ] **Step 5: 컨트롤러 배선 교체** — `InventoryAdminController.java`

`productService` 의존을 `inventoryService`로 교체(import: `com.jhg.hgpage.catalog.ProductService` 제거, `com.jhg.hgpage.wms.service.InventoryService` 추가):

```java
    private final InventoryAdjustmentService inventoryAdjustmentService;
    private final InventoryService inventoryService;
    private final PurchaseOrderService purchaseOrderService;
```

`inventory`/`purchaseOrders` 핸들러의 모델 채움을 교체:

```java
        model.addAttribute("products", inventoryService.findInventoryRows()); // /admin/inventory
```
```java
        model.addAttribute("products", inventoryService.findInventoryRows()); // /admin/purchase-orders select용
```

- [ ] **Step 6: 템플릿 한 줄 교체** — `admin/inventory.html:152`

```html
          <span th:text="${p.onHandQty}" th:classappend="${p.onHandQty == 0} ? 'qty-zero'">15</span>개
```

(`purchaseorders.html`의 셀렉트는 `${p.id}`/`${p.name}`만 쓰므로 무수정.)

- [ ] **Step 7: MVC 테스트 배선 갱신** — `InventoryAdminControllerMvcTest.java`

`@MockBean ProductService productService;`를 `@MockBean InventoryService inventoryService;`로 바꾸고(import 교체), `sampleProduct()`를 `sampleRow()`로 교체, `productService.findAllWithInventory()` 스텁 2곳을 `inventoryService.findInventoryRows()`로 교체.

import: `com.jhg.hgpage.catalog.ProductService` → `com.jhg.hgpage.wms.service.InventoryService`, `com.jhg.hgpage.wms.dto.InventoryRow` 추가. `com.jhg.hgpage.catalog.Product`·`com.jhg.hgpage.wms.domain.Inventory` import는 `sampleProduct` 삭제로 불필요해지면 제거(단 `발주화면…` 테스트가 `PurchaseOrderItem.create(sampleProduct(),5)`에서 Product를 쓰므로, 그 호출은 아래처럼 별도 `productForPo()`로 바꾼다).

교체 내용:
```java
    @MockBean InventoryService inventoryService;
```
```java
    private InventoryRow sampleRow() {
        return new InventoryRow(1L, "상품1", 10000, 15);
    }

    private com.jhg.hgpage.catalog.Product productForPo() {
        com.jhg.hgpage.catalog.Product p = new com.jhg.hgpage.catalog.Product();
        p.setId(1L); p.setName("상품1"); p.setPrice(10000);
        return p;
    }
```
```java
    @Test
    void 재고화면은_재고목록만_조회한다() throws Exception {
        when(inventoryService.findInventoryRows()).thenReturn(List.of(sampleRow()));

        mockMvc.perform(get("/admin/inventory").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(model().attributeExists("products"))
                .andExpect(model().attributeDoesNotExist("purchaseOrders"));
    }

    @Test
    void 발주화면은_발주현황과_상품목록을_조회한다() throws Exception {
        when(inventoryService.findInventoryRows()).thenReturn(List.of(sampleRow()));
        when(purchaseOrderService.findAllWithItems()).thenReturn(
                List.of(PurchaseOrder.create("긴급 발주", PurchaseOrderItem.create(productForPo(), 5))));

        mockMvc.perform(get("/admin/purchase-orders").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/purchaseorders"))
                .andExpect(model().attributeExists("purchaseOrders", "products"));
    }
```
(나머지 adjust/create/receive 테스트는 `productService`/`inventoryService`를 안 쓰므로 불변. 더 이상 쓰이지 않으면 `sampleProduct()`와 그 import 제거.)

- [ ] **Step 8: 통과 확인**

Run: `.\gradlew.bat test --tests "com.jhg.hgpage.service.InventoryServiceTest" --tests "com.jhg.hgpage.controller.admin.InventoryAdminControllerMvcTest"`
Expected: PASS (InventoryServiceTest 8건, MVC 전건).

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/jhg/hgpage/wms/dto/InventoryRow.java \
        src/main/java/com/jhg/hgpage/wms/service/InventoryService.java \
        src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java \
        src/main/resources/templates/admin/inventory.html \
        src/test/java/com/jhg/hgpage/service/InventoryServiceTest.java \
        src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java
git commit -m "refactor: 관리자 재고화면을 InventoryRow DTO 조립으로 이전 — 그래프 절단 4/5"
```

---

### Task 5: 객체그래프 제거 (Contract)

마지막. `Product.inventory`/`Inventory.product`/`findAllWithInventory`/`addStock` 등 옛 그래프를 제거하고 시드를 productId 명시 저장으로 바꾼다. 이 커밋 후 catalog는 wms를 import하지 않는다.

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/catalog/Product.java`
- Modify: `src/main/java/com/jhg/hgpage/wms/domain/Inventory.java:22-24`
- Modify: `src/main/java/com/jhg/hgpage/catalog/ProductRepository.java:14-15`
- Modify: `src/main/java/com/jhg/hgpage/catalog/ProductService.java:27-29`
- Modify: `src/main/java/com/jhg/hgpage/initDb.java` `initProduct()`
- Delete: `src/test/java/com/jhg/hgpage/repository/ProductInventoryPersistenceTest.java`
- Modify: `src/test/java/com/jhg/hgpage/repository/PurchaseOrderRepositoryTest.java:24-33`

**Interfaces:**
- Produces: `Product`(재고 무관), `Inventory`(productId 단독 소유). catalog→wms 의존 없음.

- [ ] **Step 1: Product에서 inventory/addStock 제거** — `Product.java`

`com.jhg.hgpage.wms.domain.Inventory` import 제거, `inventory` 필드(`@OneToOne ...`)와 `addStock` 메서드 삭제. 결과:

```java
package com.jhg.hgpage.catalog;

import com.jhg.hgpage.oms.domain.CartItem;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter @Setter
public class Product {
    @Id @GeneratedValue
    @Column(name = "product_id")
    private Long id;
    private String name;
    private int price;

    @OneToMany(mappedBy = "product")
    private List<CartItem> cartItems = new ArrayList<>();
}
```

- [ ] **Step 2: Inventory에서 product 역참조 제거** — `Inventory.java`

`com.jhg.hgpage.catalog.Product` import 제거, 아래 필드 삭제:
```java
    @OneToOne(mappedBy = "inventory", fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;
```
(`productId`·연산·`create` 팩토리는 유지. `FetchType` import가 더 이상 안 쓰이면 `import jakarta.persistence.*`라 무관.)

- [ ] **Step 3: ProductRepository/ProductService에서 findAllWithInventory 제거**

`ProductRepository.java`: `@Query("select p from Product p join fetch p.inventory") List<Product> findAllWithInventory();` 와 미사용 import(`Query`)를 제거. `ProductService.java`: `findAllWithInventory()` 메서드 삭제.

- [ ] **Step 4: 시드를 productId 명시 저장으로 교체** — `initDb.java` `initProduct()`

cascade가 사라졌으므로 inventory를 명시 저장한다. `initService`에 `InventoryRepository` 주입이 필요 — 생성자와 필드를 확장한다.

필드/생성자:
```java
        private final EntityManager em;
        private final InventoryRepository inventoryRepository;
        private final String adminPassword;

        initService(EntityManager em, InventoryRepository inventoryRepository,
                    @Value("${ADMIN_PASSWORD:1111}") String adminPassword) {
            this.em = em;
            this.inventoryRepository = inventoryRepository;
            this.adminPassword = adminPassword;
        }
```
`initProduct()`:
```java
        public void initProduct() {
            for (int i = 0; i < 20; i++) {
                Product product = new Product();
                product.setName("상품" + (i + 1));
                product.setPrice(10000 + (i * 1000));
                em.persist(product);

                Inventory inventory = Inventory.create(product.getId());
                inventory.setOnHandQty(15 * (i + 1));
                inventory.setReservedQty(0);
                inventoryRepository.save(inventory);
            }
        }
```
import 추가: `com.jhg.hgpage.wms.repository.InventoryRepository`.

- [ ] **Step 5: InitDbTest의 생성자 호출 갱신** — `InitDbTest.java`

`new initDb.initService(em, "1111")` 2곳이 컴파일 실패한다. `@DataJpaTest`에 `InventoryRepository`를 주입받아 넘긴다. 상단에 추가:
```java
import com.jhg.hgpage.wms.repository.InventoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
```
```java
    @Autowired InventoryRepository inventoryRepository;
```
두 생성자 호출을 각각:
```java
        initDb.initService service = new initDb.initService(em, inventoryRepository, "1111");
```
```java
        initDb.initService service = new initDb.initService(em, inventoryRepository, "s3cret-from-env");
```
(단언은 불변 — 계정 2·상품 20·비밀번호 주입.)

- [ ] **Step 6: cascade 테스트 삭제 + 발주 리포지토리 테스트 정리**

`ProductInventoryPersistenceTest.java` 삭제(cascade Product→Inventory 검증은 무의미; Inventory 단독 영속은 Task 1 `InventoryRepositoryTest`가 커버).

```bash
git rm src/test/java/com/jhg/hgpage/repository/ProductInventoryPersistenceTest.java
```

`PurchaseOrderRepositoryTest.java`의 `persistProduct`에서 inventory 생성 제거(Product에 inventory가 없으므로 컴파일 실패). 교체:
```java
    private Product persistProduct(String name) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(10000);
        em.persist(product);
        return product;
    }
```
미사용이 된 `com.jhg.hgpage.wms.domain.Inventory` import 제거.

- [ ] **Step 7: 전체 빌드 — 순환 없음 + 전건 green**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL. 풀 컨텍스트 기동(순환 의존 없음) + 전체 테스트 통과. catalog가 wms를 import하지 않음.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jhg/hgpage/catalog/Product.java \
        src/main/java/com/jhg/hgpage/wms/domain/Inventory.java \
        src/main/java/com/jhg/hgpage/catalog/ProductRepository.java \
        src/main/java/com/jhg/hgpage/catalog/ProductService.java \
        src/main/java/com/jhg/hgpage/initDb.java \
        src/test/java/com/jhg/hgpage/InitDbTest.java \
        src/test/java/com/jhg/hgpage/repository/PurchaseOrderRepositoryTest.java
git commit -m "refactor: Product↔Inventory 객체그래프 제거, Inventory가 productId 단독 소유 — 그래프 절단 5/5 contract"
```

---

## 실행 후 수동 검증 (스키마 리셋)

FK가 `product.inventory_id` → `inventory.product_id`로 이동하므로 로컬/운영 영속 H2(TCP)는 1회 리셋이 필요하다(테스트는 임베디드 create-drop이라 무관).

```powershell
# H2 TCP 서버가 떠 있는 상태에서
.\gradlew.bat bootRun --args='--spring.profiles.active=local'   # ddl-auto: create로 스키마 리셋 + 재시드
```
검증: 메인 그리드 가용수량 표시, 주문→취소→재고 복구, 발주→입고→보유수량 증가, `/admin/inventory` 보유 수량 표시가 이전과 동일.

## Self-Review (작성자 체크 완료)
- **Spec 커버리지:** 설계 ①~⑥ 전부 Task 1~5에 매핑(① Task1+5, ② Task1+2, ③ Task3, ④ Task1+5, ⑤ Task4, ⑥ 각 Task 테스트). 스키마 리셋(리스크) = 실행 후 검증 절차.
- **플레이스홀더:** 없음(모든 코드 블록 실체 포함).
- **타입 일관성:** `Inventory.create(Long)`·`getProductId()`·`InventoryRepository.findByProductId(In)`·`InventoryService.findInventoryRows():List<InventoryRow>`·`InventoryRow(Long,String,int,int)`가 Task 전반에서 동일 시그니처로 사용됨.
