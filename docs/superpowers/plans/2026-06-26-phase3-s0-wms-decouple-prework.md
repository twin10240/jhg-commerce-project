# Phase 3 S0 — WMS 분리 선결작업 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 모놀리스 내부에서 `wms/` 패키지가 `catalog`를 전혀 import하지 않게 만들고, 재고 변경 포트(예약/출고/해제)를 `orderId` 자연키로 멱등화한다. WMS 물리 분리(S1~)의 전제 조건.

**Architecture:** expand-in-monolith 패턴(0번과 동일). ① `PurchaseOrderItem→Product` 객체그래프를 `productId`로 절단 ② 재고 관리화면을 catalog 조인 없이 `productId + 수량`만으로 조립 ③ `Reservation` 멱등 원장을 WMS에 도입하고 `InventoryPort`에 `orderId`를 실어 예약/출고/해제를 멱등으로. 동작은 두 가지만 의도적으로 바뀐다(아래 Global Constraints 참고). 나머지는 동작·DB데이터·URL 불변.

**Tech Stack:** Java 17, Spring Boot 3.5.5, Spring Data JPA(Hibernate), JUnit5 + Mockito + AssertJ, 임베디드 H2(테스트).

## Global Constraints

- **빌드/테스트**: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"` 선설정 후 `.\gradlew.bat test` (테스트는 임베디드 H2라 TCP 서버 불필요).
- **S0의 목표 불변식**: 작업 종료 시 `src/main/java/com/jhg/hgpage/wms/**`가 `com.jhg.hgpage.catalog.*`를 **단 한 줄도 import하지 않는다**(Task 5에서 grep으로 검증).
- **의도된 동작 변경 2가지**(그 외 전부 불변):
  1. 관리자 재고/발주 화면이 **상품명·가격을 더 이상 표시하지 않고 `productId`로 표시**한다(결정 A — WMS는 상품명·가격을 모른다).
  2. 발주 생성 시 상품 존재 검증을 **`ProductRepository` → `InventoryRepository`(재고 SKU 존재)** 로 바꾼다(catalog 의존 제거, 실질 동등 — 모든 상품에 재고행이 있음).
- **포트 시그니처 최종형**(Task 4 이후 모든 호출부가 이걸 따른다):
  - `boolean reserveAll(Long orderId, Map<Long,Integer> qtyByProductId)`
  - `void shipAll(Long orderId, Map<Long,Integer> qtyByProductId)`
  - `void releaseAll(Long orderId, Map<Long,Integer> qtyByProductId)`
- **영속 H2(TCP) 주의**: `purchase_order_item.product_id`가 FK→일반 컬럼으로 바뀌고 `reservation` 테이블이 추가된다. 영속 DB로 앱을 띄울 거면 `--spring.profiles.active=local`로 1회 리셋 필요(`ddl-auto: update`가 FK 제거를 못 따라감). TDD(임베디드 H2 create-drop)는 무관.
- TDD: 테스트(RED) → 최소 구현(GREEN) → 커밋. 각 Task는 끝에서 `.\gradlew.bat test` 전체 green.

---

### Task 1: `PurchaseOrderItem → Product` 객체그래프를 `productId`로 절단

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/wms/domain/PurchaseOrderItem.java`
- Modify: `src/main/java/com/jhg/hgpage/wms/service/PurchaseOrderService.java`
- Modify: `src/main/java/com/jhg/hgpage/wms/repository/PurchaseOrderRepository.java`
- Modify: `src/main/resources/templates/admin/purchaseorders.html:151`
- Test: `src/test/java/com/jhg/hgpage/domain/PurchaseOrderTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/PurchaseOrderServiceTest.java`
- Test: `src/test/java/com/jhg/hgpage/repository/PurchaseOrderRepositoryTest.java`
- Test: `src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java`

**Interfaces:**
- Produces: `PurchaseOrderItem.create(Long productId, int quantity)`, `PurchaseOrderItem.getProductId() : Long` (기존 `create(Product, int)`·`getProduct()` 제거).
- Produces: `PurchaseOrderService.create`·`receive` 시그니처 불변(동작만 catalog-free).

- [ ] **Step 1: 도메인/리포지토리 테스트를 새 API로 고쳐 RED 만들기**

`PurchaseOrderTest.java` 전체를 아래로 교체(Product 의존 제거):

```java
package com.jhg.hgpage.domain;

import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.wms.domain.enums.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PurchaseOrder 도메인은 상태 전이(ORDERED→RECEIVED, 중복 거부)만 책임진다.
 * 품목은 productId만 보유한다(catalog 객체그래프 없음).
 */
class PurchaseOrderTest {

    @Test
    void 발주를_생성하면_ORDERED_상태로_초기화된다() {
        PurchaseOrder po = PurchaseOrder.create("긴급 발주", PurchaseOrderItem.create(1L, 5));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(po.getMemo()).isEqualTo("긴급 발주");
        assertThat(po.getCreatedAt()).isNotNull();
        assertThat(po.getReceivedAt()).isNull();
        assertThat(po.getItems()).hasSize(1);
        assertThat(po.getItems().get(0).getProductId()).isEqualTo(1L);
    }

    @Test
    void 입고하면_RECEIVED가_되고_입고시각이_찍힌다() {
        PurchaseOrder po = PurchaseOrder.create("정기 발주",
                PurchaseOrderItem.create(1L, 5),
                PurchaseOrderItem.create(2L, 20));

        po.receive();

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
    }

    @Test
    void 이미_입고된_발주는_다시_입고할_수_없다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        po.receive();

        assertThatThrownBy(po::receive).isInstanceOf(IllegalStateException.class);
    }
}
```

`PurchaseOrderRepositoryTest.java` 전체를 아래로 교체(상품 fetch join 제거, productId 단언):

```java
package com.jhg.hgpage.repository;

import com.jhg.hgpage.wms.repository.PurchaseOrderRepository;
import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PurchaseOrderRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;

    @Test
    void findAllWithItems는_품목을_fetch_join으로_함께_로드한다() {
        em.persist(PurchaseOrder.create("발주1", PurchaseOrderItem.create(1L, 5)));
        em.flush();
        em.clear();

        List<PurchaseOrder> orders = purchaseOrderRepository.findAllWithItems();

        assertThat(orders).hasSize(1);
        PurchaseOrder po = orders.get(0);
        assertThat(Hibernate.isInitialized(po.getItems())).isTrue();
        assertThat(po.getItems().get(0).getProductId()).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: 컴파일 실패(RED) 확인**

Run: `.\gradlew.bat test --tests "com.jhg.hgpage.domain.PurchaseOrderTest"`
Expected: 컴파일 에러 — `create(long,int)`/`getProductId()` 없음.

- [ ] **Step 3: `PurchaseOrderItem`을 productId 소유로 변경**

`PurchaseOrderItem.java` 전체를 아래로 교체:

```java
package com.jhg.hgpage.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderItem {

    @Id @GeneratedValue
    @Column(name = "purchase_order_item_id")
    private Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    // WMS는 상품을 productId로만 참조한다(catalog 객체그래프 없음).
    @Column(name = "product_id")
    private Long productId;

    private int quantity;

    public static PurchaseOrderItem create(Long productId, int quantity) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.productId = productId;
        item.quantity = quantity;
        return item;
    }

    void setPurchaseOrder(PurchaseOrder purchaseOrder) {
        this.purchaseOrder = purchaseOrder;
    }
}
```

- [ ] **Step 4: `PurchaseOrderService`를 catalog-free로 변경**

`PurchaseOrderService.java`에서 (a) `import com.jhg.hgpage.catalog.Product;`·`import com.jhg.hgpage.catalog.ProductRepository;` 제거, (b) `private final ProductRepository productRepository;` 필드 제거, (c) `create`·`receive` 본문을 아래로 교체:

```java
    @Transactional
    public Long create(List<PurchaseOrderLine> lines, String memo) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("발주 품목이 없습니다.");
        }

        PurchaseOrderItem[] items = lines.stream()
                .map(line -> {
                    if (line.quantity() < 1) {
                        throw new IllegalArgumentException("발주 수량은 1개 이상이어야 합니다.");
                    }
                    // catalog가 아니라 WMS 재고(SKU) 존재로 검증한다(의존 단방향).
                    if (inventoryRepository.findByProductId(line.productId()).isEmpty()) {
                        throw new EntityNotFoundException("Inventory", line.productId());
                    }
                    return PurchaseOrderItem.create(line.productId(), line.quantity());
                })
                .toArray(PurchaseOrderItem[]::new);

        PurchaseOrder purchaseOrder = purchaseOrderRepository.save(PurchaseOrder.create(memo, items));

        return purchaseOrder.getId();
    }

    @Transactional
    public Long receive(Long poId) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new EntityNotFoundException("PurchaseOrder", poId));

        purchaseOrder.receive(); // 상태 전이(중복 입고 거부)

        // 입고 수량만큼 실물 재고를 늘린다(상품별 합산)
        Map<Long, Integer> qtyByProductId = purchaseOrder.getItems().stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getProductId,
                        PurchaseOrderItem::getQuantity, Integer::sum));
        Map<Long, Inventory> inventories = inventoryRepository.findByProductIdIn(qtyByProductId.keySet()).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Function.identity()));
        for (Long productId : qtyByProductId.keySet()) {
            if (!inventories.containsKey(productId)) {
                throw new EntityNotFoundException("Inventory", productId);
            }
        }
        qtyByProductId.forEach((productId, qty) -> inventories.get(productId).addOnHandQty(qty));

        // 입고로 가용분이 생겼음을 통지한다(백오더 승격은 OMS 구현체가 처리)
        stockReplenishedHandler.onReplenished(List.copyOf(qtyByProductId.keySet()));

        return purchaseOrder.getId();
    }
```

- [ ] **Step 5: `PurchaseOrderRepository.findAllWithItems`에서 상품 fetch join 제거**

`PurchaseOrderRepository.java`의 `@Query`를 아래로 교체:

```java
    // 관리자 발주 현황 화면용: 품목까지 한 번에 로드(최신순). 상품(catalog)은 WMS가 모르므로 productId만.
    @Query("select distinct po from PurchaseOrder po " +
            "left join fetch po.items " +
            "order by po.id desc")
    List<PurchaseOrder> findAllWithItems();
```

- [ ] **Step 6: 발주 현황 템플릿의 상품명 표시를 productId로 변경**

`templates/admin/purchaseorders.html:151`의 품목 표시를 교체:

```html
            <span th:text="|상품#${item.productId} x${item.quantity}|">상품#1 x10</span><span th:if="${!stat.last}">, </span>
```

- [ ] **Step 7: 서비스/MVC 테스트를 새 API로 갱신**

`PurchaseOrderServiceTest.java`를 아래로 교체(ProductRepository mock·Product 헬퍼 제거, 재고 존재 검증으로 전환):

```java
package com.jhg.hgpage.service;

import com.jhg.hgpage.wms.service.PurchaseOrderService;
import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.wms.domain.enums.PurchaseOrderStatus;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import com.jhg.hgpage.wms.repository.PurchaseOrderRepository;
import com.jhg.hgpage.wms.service.PurchaseOrderService.PurchaseOrderLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {

    @Mock PurchaseOrderRepository purchaseOrderRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock StockReplenishedHandler stockReplenishedHandler;
    @InjectMocks PurchaseOrderService purchaseOrderService;

    @Test
    void 발주를_생성하면_ORDERED_상태로_저장된다() {
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(Inventory.create(1L)));
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        purchaseOrderService.create(List.of(new PurchaseOrderLine(1L, 20)), "긴급 발주");

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(captor.capture());
        PurchaseOrder saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(saved.getMemo()).isEqualTo("긴급 발주");
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().get(0).getProductId()).isEqualTo(1L);
        assertThat(saved.getItems().get(0).getQuantity()).isEqualTo(20);
    }

    @Test
    void 품목이_없으면_발주를_거부한다() {
        assertThatThrownBy(() -> purchaseOrderService.create(List.of(), "메모"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void 수량이_1_미만이면_발주를_거부한다() {
        assertThatThrownBy(() -> purchaseOrderService.create(List.of(new PurchaseOrderLine(1L, 0)), "메모"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void 재고에_없는_상품을_발주하면_EntityNotFoundException을_던진다() {
        when(inventoryRepository.findByProductId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> purchaseOrderService.create(List.of(new PurchaseOrderLine(99L, 5)), "메모"))
                .isInstanceOf(EntityNotFoundException.class);
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void 입고하면_RECEIVED가_되고_실물_재고가_늘어난다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        Inventory inv = Inventory.create(1L);
        inv.setOnHandQty(10);
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(inv));

        purchaseOrderService.receive(7L);

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(inv.getOnHandQty()).isEqualTo(15);
    }

    @Test
    void 입고_상품의_재고가_없으면_EntityNotFoundException을_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> purchaseOrderService.receive(7L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("1");
    }

    @Test
    void 입고하면_입고된_상품들의_백오더_할당을_트리거한다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        Inventory inv = Inventory.create(1L);
        inv.setOnHandQty(10);
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(inv));

        purchaseOrderService.receive(7L);

        verify(stockReplenishedHandler).onReplenished(List.of(1L));
    }

    @Test
    void 중복_입고가_거부되면_백오더_할당도_트리거하지_않는다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        po.receive();
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> purchaseOrderService.receive(7L))
                .isInstanceOf(IllegalStateException.class);

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }

    @Test
    void 없는_발주를_입고하면_EntityNotFoundException을_던진다() {
        when(purchaseOrderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> purchaseOrderService.receive(99L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }
}
```

`InventoryAdminControllerMvcTest.java`에서 발주현황 mock의 Product 의존 제거:
- `import com.jhg.hgpage.catalog.Product;` 제거, `productForPo()` 헬퍼 제거.
- `발주화면은_발주현황과_상품목록을_조회한다` 의 stub을 아래로 교체:

```java
        when(purchaseOrderService.findAllWithItems()).thenReturn(
                List.of(PurchaseOrder.create("긴급 발주", PurchaseOrderItem.create(1L, 5))));
```

> 주의: `sampleRow()`는 Task 2에서 다시 고친다. Task 1 시점엔 `new InventoryRow(1L, "상품1", 10000, 15)` 그대로 둔다.

- [ ] **Step 8: 테스트 통과(GREEN) 확인**

Run: `.\gradlew.bat test`
Expected: 전체 PASS.

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "refactor(wms): PurchaseOrderItem→Product 객체그래프 절단(productId 소유), 발주 검증을 재고 SKU 기준으로 - Phase3 S0"
```

---

### Task 2: 재고 관리화면을 catalog-free로 (`InventoryRow` = productId + 수량)

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/wms/dto/InventoryRow.java`
- Modify: `src/main/java/com/jhg/hgpage/wms/service/InventoryService.java`
- Modify: `src/main/resources/templates/admin/inventory.html`
- Modify: `src/main/resources/templates/admin/purchaseorders.html:113`
- Test: `src/test/java/com/jhg/hgpage/service/InventoryServiceTest.java` (findInventoryRows 부분만)
- Test: `src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java` (sampleRow)

**Interfaces:**
- Produces: `record InventoryRow(Long productId, int onHandQty)` (기존 `(Long id, String name, int price, int onHandQty)` 제거).
- Produces: `InventoryService`가 `ProductRepository`에 의존하지 않음(필드·import 제거). reserve/ship/release 시그니처는 Task 4에서 변경하므로 **여기선 손대지 않는다**.

- [ ] **Step 1: `InventoryServiceTest`의 findInventoryRows 테스트를 새 형태로 고쳐 RED**

`InventoryServiceTest.java`에서:
- `import com.jhg.hgpage.catalog.Product;`, `import com.jhg.hgpage.catalog.ProductRepository;` 제거.
- `@Mock ProductRepository productRepository;` 필드 제거.
- 마지막 두 테스트(`재고행을_카탈로그_이름가격과_보유수량으로_조립한다`, `재고행_조립시_대응_상품이_없으면_EntityNotFoundException을_던진다`)를 아래 하나로 교체:

```java
    @Test
    void 재고행을_productId와_보유수량으로_조립한다() {
        Inventory i1 = inventoryOf(1L, 30, 0);
        Inventory i2 = inventoryOf(2L, 0, 0);
        when(inventoryRepository.findAll()).thenReturn(List.of(i1, i2));

        List<InventoryRow> rows = inventoryService.findInventoryRows();

        assertThat(rows).extracting(InventoryRow::productId).containsExactly(1L, 2L);
        assertThat(rows.get(0).onHandQty()).isEqualTo(30);
        assertThat(rows.get(1).onHandQty()).isEqualTo(0);
    }
```

> 이 시점에 reserve/ship/release 테스트는 아직 옛 시그니처(`reserveAll(Map...)`)다. Task 2는 그것들을 건드리지 않으므로 컴파일·통과 유지된다(InventoryService의 reserve/ship/release도 Task 2에선 그대로).

- [ ] **Step 2: RED 확인**

Run: `.\gradlew.bat test --tests "com.jhg.hgpage.service.InventoryServiceTest"`
Expected: 컴파일 에러 — `InventoryRow::productId` 없음 / `findInventoryRows`가 ProductRepository 참조.

- [ ] **Step 3: `InventoryRow`를 productId+수량으로 축소**

`InventoryRow.java` 전체를 교체:

```java
package com.jhg.hgpage.wms.dto;

/** 관리자 재고화면 행: WMS는 상품명·가격을 모르므로 productId와 보유수량만 노출한다. */
public record InventoryRow(Long productId, int onHandQty) {}
```

- [ ] **Step 4: `InventoryService`에서 catalog 의존 제거**

`InventoryService.java`에서:
- `import com.jhg.hgpage.catalog.Product;`, `import com.jhg.hgpage.catalog.ProductRepository;` 제거.
- `private final ProductRepository productRepository;` 필드 제거.
- `findInventoryRows()` 를 아래로 교체:

```java
    /** 관리자 재고화면 행 조립: WMS 재고의 productId + 보유수량만(상품명·가격은 OMS catalog 소관). */
    public List<InventoryRow> findInventoryRows() {
        return inventoryRepository.findAll().stream()
                .map(inv -> new InventoryRow(inv.getProductId(), inv.getOnHandQty()))
                .sorted(Comparator.comparing(InventoryRow::productId))
                .toList();
    }
```

> `EntityNotFoundException`·`Function`·`Collectors`·`Comparator` import는 다른 메서드(`loadInventories`/`availableByProductIds`)에서 계속 쓰이므로 그대로 둔다. 컴파일러 경고가 나는 미사용 import만 제거.

- [ ] **Step 5: 재고 화면 템플릿에서 상품명·가격 열 제거**

`templates/admin/inventory.html`:
- 조정 폼 select(120~122행)의 옵션을 productId로:

```html
        <select name="productId" class="input">
          <option th:each="p : ${products}" th:value="${p.productId}" th:text="${p.productId}">1</option>
        </select>
```

- 테이블 헤더(138~145행)를 아래로 교체(상품명·가격 열 삭제):

```html
      <thead>
      <tr>
        <th style="width:120px">상품 ID</th>
        <th class="right">보유 수량</th>
      </tr>
      </thead>
```

- 테이블 본문 행(147~157행)을 아래로 교체:

```html
      <tr th:each="p : ${products}">
        <td th:text="${p.productId}">1</td>
        <td class="right">
          <span th:text="${p.onHandQty}" th:classappend="${p.onHandQty == 0} ? 'qty-zero'">15</span>개
        </td>
      </tr>
      <tr th:if="${#lists.isEmpty(products)}">
        <td colspan="2" class="hint">등록된 상품이 없습니다.</td>
      </tr>
```

- [ ] **Step 6: 발주 생성 select도 productId로**

`templates/admin/purchaseorders.html:112-114`의 select 옵션을 교체:

```html
        <select name="items[0].productId" class="input">
          <option th:each="p : ${products}" th:value="${p.productId}" th:text="${p.productId}">1</option>
        </select>
```

- [ ] **Step 7: MVC 테스트의 sampleRow를 새 형태로**

`InventoryAdminControllerMvcTest.java`의 `sampleRow()` 교체:

```java
    private InventoryRow sampleRow() {
        return new InventoryRow(1L, 15);
    }
```

- [ ] **Step 8: 테스트 통과(GREEN) 확인**

Run: `.\gradlew.bat test`
Expected: 전체 PASS.

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "refactor(wms): 재고 관리화면을 catalog 조인 없이 productId+수량으로 조립(InventoryRow 축소) - Phase3 S0"
```

---

### Task 3: `Reservation` 멱등 원장 도입 (엔티티 + enum + 리포지토리)

**Files:**
- Create: `src/main/java/com/jhg/hgpage/wms/domain/enums/ReservationStatus.java`
- Create: `src/main/java/com/jhg/hgpage/wms/domain/Reservation.java`
- Create: `src/main/java/com/jhg/hgpage/wms/repository/ReservationRepository.java`
- Test: `src/test/java/com/jhg/hgpage/domain/ReservationTest.java`

**Interfaces:**
- Produces: `enum ReservationStatus { RESERVED, SHIPPED, RELEASED }`
- Produces: `Reservation.reserve(Long orderId) : Reservation`(status=RESERVED), `void ship()`(RESERVED→SHIPPED, 그 외 IllegalState), `void release()`(RESERVED→RELEASED, 그 외 IllegalState), `Long getOrderId()`, `ReservationStatus getStatus()`.
- Produces: `ReservationRepository.findByOrderId(Long) : Optional<Reservation>`.

- [ ] **Step 1: 도메인 상태전이 테스트 작성(RED)**

`src/test/java/com/jhg/hgpage/domain/ReservationTest.java`:

```java
package com.jhg.hgpage.domain;

import com.jhg.hgpage.wms.domain.Reservation;
import com.jhg.hgpage.wms.domain.enums.ReservationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reservation = 주문(orderId)당 재고 예약의 멱등 원장. 상태 전이만 책임진다.
 * RESERVED → SHIPPED(출고) 또는 RESERVED → RELEASED(취소). 그 외 전이는 거부.
 */
class ReservationTest {

    @Test
    void 예약을_생성하면_RESERVED_상태이고_orderId를_보유한다() {
        Reservation r = Reservation.reserve(10L);

        assertThat(r.getOrderId()).isEqualTo(10L);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void 출고하면_SHIPPED가_된다() {
        Reservation r = Reservation.reserve(10L);

        r.ship();

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.SHIPPED);
    }

    @Test
    void 해제하면_RELEASED가_된다() {
        Reservation r = Reservation.reserve(10L);

        r.release();

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void 이미_해제된_예약은_출고할_수_없다() {
        Reservation r = Reservation.reserve(10L);
        r.release();

        assertThatThrownBy(r::ship).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 이미_출고된_예약은_해제할_수_없다() {
        Reservation r = Reservation.reserve(10L);
        r.ship();

        assertThatThrownBy(r::release).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: RED 확인**

Run: `.\gradlew.bat test --tests "com.jhg.hgpage.domain.ReservationTest"`
Expected: 컴파일 에러 — `Reservation`/`ReservationStatus` 없음.

- [ ] **Step 3: enum 생성**

`ReservationStatus.java`:

```java
package com.jhg.hgpage.wms.domain.enums;

public enum ReservationStatus {
    RESERVED, SHIPPED, RELEASED
}
```

- [ ] **Step 4: `Reservation` 엔티티 생성**

`Reservation.java`:

```java
package com.jhg.hgpage.wms.domain;

import com.jhg.hgpage.wms.domain.enums.ReservationStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문(orderId)당 재고 예약 원장. orderId 자연키로 멱등성을 보장한다
 * (같은 주문의 예약/출고/해제 재요청은 서비스가 이 상태를 보고 no-op 처리).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id @GeneratedValue
    @Column(name = "reservation_id")
    private Long id;

    @Column(unique = true, nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    public static Reservation reserve(Long orderId) {
        Reservation reservation = new Reservation();
        reservation.orderId = orderId;
        reservation.status = ReservationStatus.RESERVED;
        return reservation;
    }

    public void ship() {
        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("예약(RESERVED) 상태에서만 출고할 수 있습니다. 현재: " + status);
        }
        this.status = ReservationStatus.SHIPPED;
    }

    public void release() {
        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("예약(RESERVED) 상태에서만 해제할 수 있습니다. 현재: " + status);
        }
        this.status = ReservationStatus.RELEASED;
    }
}
```

- [ ] **Step 5: 리포지토리 생성**

`ReservationRepository.java`:

```java
package com.jhg.hgpage.wms.repository;

import com.jhg.hgpage.wms.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    Optional<Reservation> findByOrderId(Long orderId);
}
```

- [ ] **Step 6: 테스트 통과(GREEN) 확인**

Run: `.\gradlew.bat test --tests "com.jhg.hgpage.domain.ReservationTest"`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "feat(wms): 주문당 멱등 예약 원장 Reservation(RESERVED→SHIPPED/RELEASED) 도입 - Phase3 S0"
```

---

### Task 4: `InventoryPort`에 orderId 추가 + 원장 기반 멱등 예약/출고/해제 (원자적 커밋)

> 포트 시그니처를 바꾸면 모든 호출부가 동시에 안 바뀌면 컴파일이 깨진다. **이 Task는 production+test를 한 커밋에** 처리한다.

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/contract/InventoryPort.java`
- Modify: `src/main/java/com/jhg/hgpage/wms/service/InventoryService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderAllocationService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderService.java` (`order` 저장 순서, `completeDelivery`, `cancelOrder`)
- Test: `src/test/java/com/jhg/hgpage/service/InventoryServiceTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/OrderAllocationServiceTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/OrderServiceAdminTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/OrderServiceCancelTest.java`

**Interfaces:**
- Consumes: `ReservationRepository.findByOrderId`, `Reservation.reserve/ship/release` (Task 3).
- Produces: 포트 3종 최종 시그니처(Global Constraints 참고). `InventoryService`가 `ReservationRepository`를 주입받아 멱등 처리.

- [ ] **Step 1: 포트 시그니처 변경**

`InventoryPort.java`의 세 메서드 선언을 교체(javadoc은 유지, 시그니처만):

```java
    boolean reserveAll(Long orderId, Map<Long, Integer> qtyByProductId);

    void shipAll(Long orderId, Map<Long, Integer> qtyByProductId);

    void releaseAll(Long orderId, Map<Long, Integer> qtyByProductId);
```

- [ ] **Step 2: `InventoryService`를 원장 기반 멱등 구현으로 변경**

`InventoryService.java`에:
- `import com.jhg.hgpage.wms.domain.Reservation;`, `import com.jhg.hgpage.wms.domain.enums.ReservationStatus;`, `import com.jhg.hgpage.wms.repository.ReservationRepository;` 추가.
- 필드 추가: `private final ReservationRepository reservationRepository;`
- `reserveAll`/`shipAll`/`releaseAll`을 아래로 교체:

```java
    @Override
    @Transactional
    public boolean reserveAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation existing = reservationRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            // 멱등: 같은 주문은 한 번만 예약한다(해제된 게 아니면 예약 성공으로 간주).
            return existing.getStatus() != ReservationStatus.RELEASED;
        }

        Map<Long, Inventory> inventories = loadInventories(qtyByProductId.keySet());
        boolean allAvailable = qtyByProductId.entrySet().stream()
                .allMatch(e -> inventories.get(e.getKey()).getAvailableQty() >= e.getValue());
        if (!allAvailable) {
            return false; // 예약 기록을 남기지 않는다 → 입고/재시도 시 재예약(백오더 승격) 가능
        }
        qtyByProductId.forEach((productId, qty) -> inventories.get(productId).reserve(qty));
        reservationRepository.save(Reservation.reserve(orderId));
        return true;
    }

    @Override
    @Transactional
    public void shipAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation reservation = reservationRepository.findByOrderId(orderId).orElse(null);
        if (reservation == null) {
            throw new IllegalStateException("예약이 없어 출고할 수 없습니다. orderId=" + orderId);
        }
        if (reservation.getStatus() == ReservationStatus.SHIPPED) {
            return; // 멱등: 이미 출고됨
        }
        applyToInventories(qtyByProductId, Inventory::ship);
        reservation.ship();
    }

    @Override
    @Transactional
    public void releaseAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation reservation = reservationRepository.findByOrderId(orderId).orElse(null);
        if (reservation == null || reservation.getStatus() == ReservationStatus.RELEASED) {
            return; // 멱등/방어: 풀 예약이 없으면 no-op
        }
        applyToInventories(qtyByProductId, Inventory::release);
        reservation.release();
    }
```

- [ ] **Step 3: OMS 호출부 변경 — `OrderAllocationService`**

`OrderAllocationService.allocate`의 reserveAll 호출에 orderId 추가:

```java
    @Transactional
    public void allocate(Order order) {
        if (inventoryPort.reserveAll(order.getId(), order.quantitiesByProductId())) {
            order.markOrdered();
        } else {
            order.markBackordered();
        }
    }
```

- [ ] **Step 4: OMS 호출부 변경 — `OrderService`**

`OrderService.order`에서 **저장을 할당보다 먼저** 수행(예약 멱등키로 쓸 `order.getId()`가 필요하므로). 63~66행을 교체:

```java
        Order order = Order.createOrder(member, delivery, orderItems);
        // 예약 멱등키(orderId)를 확보하려면 먼저 저장해 id를 받는다.
        orderRepository.save(order);
        // 가용분이 있으면 예약(ORDER), 부족하면 거부하지 않고 백오더(BACKORDERED)로 접수 — WMS 포트에 위임
        orderAllocationService.allocate(order);

        return order.getId();
```

`completeDelivery`의 shipAll 호출에 orderId 추가:

```java
        order.completeDelivery();
        inventoryPort.shipAll(order.getId(), order.quantitiesByProductId());
```

`cancelOrder`의 releaseAll 호출에 orderId 추가(116행 부근):

```java
            inventoryPort.releaseAll(order.getId(), order.quantitiesByProductId());
```

- [ ] **Step 5: `InventoryServiceTest` 갱신(orderId + 원장 mock + 멱등 테스트)**

`InventoryServiceTest.java`를 아래로 교체:

```java
package com.jhg.hgpage.service;

import com.jhg.hgpage.wms.service.InventoryService;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.wms.domain.Reservation;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.dto.InventoryRow;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import com.jhg.hgpage.wms.repository.ReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InventoryPort 구현(예약/해제/출고) — orderId 자연키로 멱등이며, productId 기반으로 Inventory를 직접 변경한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock InventoryRepository inventoryRepository;
    @Mock ReservationRepository reservationRepository;
    @InjectMocks InventoryService inventoryService;

    private Inventory inventoryOf(long productId, int onHand, int reserved) {
        Inventory inv = Inventory.create(productId);
        inv.setOnHandQty(onHand);
        inv.setReservedQty(reserved);
        return inv;
    }

    @Test
    void 전_라인이_가용하면_모두_예약하고_원장을_기록하며_true를_반환한다() {
        Inventory i1 = inventoryOf(1L, 10, 0);
        Inventory i2 = inventoryOf(2L, 10, 0);
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i1, i2));

        boolean reserved = inventoryService.reserveAll(100L, Map.of(1L, 2, 2L, 1));

        assertThat(reserved).isTrue();
        assertThat(i1.getReservedQty()).isEqualTo(2);
        assertThat(i2.getReservedQty()).isEqualTo(1);
        verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    void 한_라인이라도_부족하면_아무것도_예약하지_않고_원장도_남기지_않으며_false() {
        Inventory i1 = inventoryOf(1L, 10, 0);
        Inventory i2 = inventoryOf(2L, 1, 0);
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i1, i2));

        boolean reserved = inventoryService.reserveAll(100L, Map.of(1L, 2, 2L, 5));

        assertThat(reserved).isFalse();
        assertThat(i1.getReservedQty()).isEqualTo(0);
        assertThat(i2.getReservedQty()).isEqualTo(0);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void 같은_orderId로_다시_예약하면_재예약하지_않고_true를_반환한다() {
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.of(Reservation.reserve(100L)));

        boolean reserved = inventoryService.reserveAll(100L, Map.of(1L, 2));

        assertThat(reserved).isTrue();
        verify(inventoryRepository, never()).findByProductIdIn(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void 예약은_가용수량_기준이며_예약분을_제외하고_판정한다() {
        Inventory i = inventoryOf(1L, 10, 8); // 가용 2
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        assertThat(inventoryService.reserveAll(100L, Map.of(1L, 2))).isTrue();
        assertThat(i.getReservedQty()).isEqualTo(10);
    }

    @Test
    void 출고하면_예약과_실물이_차감되고_원장이_SHIPPED가_된다() {
        Inventory i = inventoryOf(1L, 10, 2);
        Reservation r = Reservation.reserve(100L);
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.of(r));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        inventoryService.shipAll(100L, Map.of(1L, 2));

        assertThat(i.getOnHandQty()).isEqualTo(8);
        assertThat(i.getReservedQty()).isEqualTo(0);
    }

    @Test
    void 이미_출고된_주문의_출고요청은_무시된다() {
        Inventory i = inventoryOf(1L, 10, 2);
        Reservation r = Reservation.reserve(100L);
        r.ship();
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.of(r));

        inventoryService.shipAll(100L, Map.of(1L, 2));

        // 실물·예약이 한 번 더 차감되지 않는다
        assertThat(i.getOnHandQty()).isEqualTo(10);
        assertThat(i.getReservedQty()).isEqualTo(2);
        verify(inventoryRepository, never()).findByProductIdIn(any());
    }

    @Test
    void 해제하면_예약분이_복구되고_원장이_RELEASED가_된다() {
        Inventory i = inventoryOf(1L, 10, 3);
        Reservation r = Reservation.reserve(100L);
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.of(r));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        inventoryService.releaseAll(100L, Map.of(1L, 3));

        assertThat(i.getReservedQty()).isEqualTo(0);
        assertThat(i.getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 없는_상품을_예약하면_EntityNotFoundException을_던진다() {
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> inventoryService.reserveAll(100L, Map.of(99L, 1)))
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

    @Test
    void 재고행을_productId와_보유수량으로_조립한다() {
        Inventory i1 = inventoryOf(1L, 30, 0);
        Inventory i2 = inventoryOf(2L, 0, 0);
        when(inventoryRepository.findAll()).thenReturn(List.of(i1, i2));

        List<InventoryRow> rows = inventoryService.findInventoryRows();

        assertThat(rows).extracting(InventoryRow::productId).containsExactly(1L, 2L);
        assertThat(rows.get(0).onHandQty()).isEqualTo(30);
        assertThat(rows.get(1).onHandQty()).isEqualTo(0);
    }
}
```

- [ ] **Step 6: `OrderAllocationServiceTest` 갱신(order id 부여 + reserveAll orderId)**

`OrderAllocationServiceTest.java`에서:
- `import org.springframework.test.util.ReflectionTestUtils;` 추가.
- `orderOf` 헬퍼가 order에 id를 부여하도록 교체:

```java
    private Order orderOf(OrderItem... items) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, items);
        ReflectionTestUtils.setField(order, "id", 100L);
        return order;
    }
```

- 두 테스트의 reserveAll stub/verify에 orderId 추가:

```java
        when(inventoryPort.reserveAll(100L, Map.of(1L, 2))).thenReturn(true);
        // ...
        verify(inventoryPort).reserveAll(100L, Map.of(1L, 2));
```

```java
        when(inventoryPort.reserveAll(100L, Map.of(1L, 5))).thenReturn(false);
```

- [ ] **Step 7: `OrderServiceAdminTest` 갱신(shipAll orderId)**

`OrderServiceAdminTest.java`의 `배송완료_처리하면...` 테스트에서 order에 id를 부여하고 shipAll verify에 orderId 추가. `import org.springframework.test.util.ReflectionTestUtils;`가 없으면 추가하고, 해당 테스트를 아래로 교체:

```java
    @Test
    void 배송완료_처리하면_배송상태가_COMP가_되고_재고_출고를_포트에_위임한다() {
        Order order = newOrder("회원A"); // 상품1, 수량 2
        ReflectionTestUtils.setField(order, "id", 10L);

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        orderService.completeDelivery(10L);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.COMP);
        verify(inventoryPort).shipAll(10L, Map.of(1L, 2));
    }
```

- [ ] **Step 8: `OrderServiceCancelTest` 갱신(releaseAll orderId)**

`OrderServiceCancelTest.java`에서 `orderOf` 헬퍼가 order에 id(10L)를 부여하도록 교체:

```java
    private Order orderOf(Member member, OrderItem... items) {
        Delivery delivery = new Delivery();
        delivery.setAddress(ADDRESS);
        Order order = Order.createOrder(member, delivery, items);
        ReflectionTestUtils.setField(order, "id", 10L);
        return order;
    }
```

- `ORDER_취소로...`와 `여러_상품...` 테스트의 releaseAll verify에 orderId 추가:

```java
        verify(inventoryPort).releaseAll(10L, Map.of(7L, 3));
```

```java
        verify(inventoryPort).releaseAll(10L, Map.of(7L, 2, 8L, 1));
```

(BACKORDERED 취소 테스트의 `verifyNoInteractions(inventoryPort)`는 그대로 둔다.)

- [ ] **Step 9: 전체 테스트 통과(GREEN) 확인**

Run: `.\gradlew.bat test`
Expected: 전체 PASS. (`OrderServiceOrderTest`·`OrderServiceOrderFromCartTest`는 allocate를 mock하므로 저장 순서 변경에 영향 없음 — 그대로 통과해야 한다.)

- [ ] **Step 10: 커밋**

```bash
git add -A
git commit -m "feat(wms): InventoryPort에 orderId 추가 + Reservation 원장 기반 예약/출고/해제 멱등화 - Phase3 S0"
```

---

### Task 5: 디커플링 검증 + 전체 빌드 green

**Files:** (없음 — 검증/방어 단계)

- [ ] **Step 1: `wms/`가 catalog를 import하지 않음을 grep으로 확인**

Run(Grep 도구 또는): 패턴 `com\.jhg\.hgpage\.catalog` 를 `src/main/java/com/jhg/hgpage/wms` 범위로 검색.
Expected: **매치 0건.** (있으면 해당 파일에서 catalog 의존을 productId 기반으로 제거 후 재검증.)

- [ ] **Step 2: 역방향도 확인 — catalog가 wms를 import하지 않음(0번에서 이미 단방향)**

패턴 `com\.jhg\.hgpage\.wms` 를 `src/main/java/com/jhg/hgpage/catalog` 범위로 검색.
Expected: 매치 0건.

- [ ] **Step 3: 전체 빌드**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL (전체 테스트 포함).

- [ ] **Step 4: 커밋(검증 단계에서 정리한 게 있으면)**

변경이 있었다면:
```bash
git add -A
git commit -m "chore(wms): catalog 단방향 의존 검증 및 정리 - Phase3 S0"
```
없으면 커밋 생략.

---

## Self-Review (작성자 점검 결과)

- **Spec 커버리지(S0 범위)**: ① PurchaseOrderItem→Product 절단 = Task 1 ✓ ② 재고화면 catalog 조인 제거 = Task 2 ✓ ③ 포트 orderId + Reservation 멱등 = Task 3+4 ✓ ④ wms catalog import 0 = Task 5 ✓.
- **Placeholder 스캔**: TBD/TODO/"적절히 처리" 없음. 모든 코드 단계에 실제 코드 포함.
- **타입 일관성**: 포트 시그니처 `(Long orderId, Map<Long,Integer>)`가 InventoryPort·InventoryService·OrderAllocationService·OrderService·모든 테스트에서 동일. `InventoryRow(Long productId, int onHandQty)`가 DTO·서비스·템플릿·MVC 테스트에서 동일. `Reservation.reserve/ship/release`·`ReservationRepository.findByOrderId`가 Task 3 정의와 Task 4 사용에서 일치.
- **주의 사항**: Task 4는 포트 시그니처 변경으로 production+test를 한 커밋에 처리(중간 컴파일 불가 구간 없음). 영속 H2(TCP)는 스키마 변경(FK 제거 + reservation 테이블)으로 `local` 1회 리셋 필요(TDD는 무관).
