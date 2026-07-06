# Phase 3 S2 — 쓰기 추출 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WMS에 PurchaseOrder(발주/입고)를 이사하고, OMS의 인프로세스 재고 쓰기 코드를 REST 어댑터로 완전히 교체한다.

**Architecture:** WMS가 재고·발주·입고의 단일 진실 공급원이 된다. OMS `InventoryAdminController`는 WMS REST API(채널2: reserve/ship/release/adjust, 신규: PO CRUD)를 어댑터로 호출한다. OMS의 `wms/service/`, `wms/domain/`, `wms/repository/` 인프로세스 구현은 삭제된다.

**Tech Stack:** WMS(Java 21, Spring Boot 3.5.5, JPA, Thymeleaf), OMS(Java 17, Spring Boot 3.5.5, RestClient, @RestClientTest/MockRestServiceServer)

## Global Constraints

- WMS 포트: 8081, OMS 포트: 8080
- WMS는 `productId`만 안다 — 상품명·가격 불필요
- 각 슬라이스(태스크)마다 `./gradlew build` 또는 `./gradlew test` 통과 후 커밋
- OMS DB: `--spring.profiles.active=local` 로 1회 리셋 필요 (Task 7 완료 후 → `purchase_order`, `purchase_order_item`, `reservation`, `inventory` 테이블 OMS DB에서 제거)
- `StockReplenishedHandler.onReplenished()` OMS 내부 트리거는 S2에서 유지, S3에서 WMS→OMS 콜백으로 이동
- TDD: 테스트를 먼저 작성하고 RED 확인 후 구현

---

## 파일 맵

### WMS (jhg-wms-project) — 신규 생성

| 파일 | 역할 |
|------|------|
| `domain/PurchaseOrderStatus.java` | enum ORDERED/RECEIVED |
| `domain/PurchaseOrder.java` | 발주 엔티티 (status·memo·items·createdAt·receivedAt) |
| `domain/PurchaseOrderItem.java` | 발주 품목 엔티티 (productId·quantity) |
| `repository/PurchaseOrderRepository.java` | `findAllWithItems()` |
| `service/PurchaseOrderService.java` | create / receive / findAllWithItems |
| `web/PurchaseOrderRequest.java` | POST body record |
| `web/PurchaseOrderResponse.java` | GET/POST 응답 record (entity→DTO 변환) |
| `web/PurchaseOrderController.java` | `GET /api/purchase-orders`, `POST /api/purchase-orders`, `POST /api/purchase-orders/receive` |
| `web/WmsAdminController.java` | Thymeleaf 관리자 UI 컨트롤러 |
| `resources/templates/admin/inventory.html` | 재고 조회·조정 화면 |
| `resources/templates/admin/purchaseorders.html` | 발주 생성·입고 화면 |
| `test/.../domain/PurchaseOrderTest.java` | 도메인 단위 테스트 |
| `test/.../service/PurchaseOrderServiceTest.java` | 서비스 단위 테스트 (@DataJpaTest) |
| `test/.../web/PurchaseOrderControllerTest.java` | REST 컨트롤러 슬라이스 (@WebMvcTest) |

### OMS (jhg-commerce-project) — 신규 생성

| 파일 | 역할 |
|------|------|
| `wms/adapter/WmsPurchaseOrderAdapter.java` | PO REST 클라이언트 (create/receive/findAll) |
| `wms/dto/PurchaseOrderDto.java` | WMS 응답 역직렬화 DTO |

### OMS — 수정

| 파일 | 변경 내용 |
|------|-----------|
| `wms/web/controller/InventoryAdminController.java` | `InventoryAdjustmentService`·`PurchaseOrderService` 제거 → `WmsInventoryAdapter` 직접 호출 + `WmsPurchaseOrderAdapter` |
| `resources/templates/admin/purchaseorders.html` | `${po.status.name()}` → `${po.status}` (String 비교로 전환) |
| `test/.../controller/admin/InventoryAdminControllerMvcTest.java` | MockBean 교체 + 테스트 갱신 |

### OMS — 삭제

| 파일 |
|------|
| `wms/service/PurchaseOrderService.java` |
| `wms/service/InventoryAdjustmentService.java` |
| `wms/domain/PurchaseOrder.java` |
| `wms/domain/PurchaseOrderItem.java` |
| `wms/domain/enums/PurchaseOrderStatus.java` |
| `wms/domain/enums/ReservationStatus.java` |
| `wms/repository/PurchaseOrderRepository.java` |
| `test/.../domain/PurchaseOrderTest.java` |
| `test/.../repository/PurchaseOrderRepositoryTest.java` |
| `test/.../service/PurchaseOrderServiceTest.java` |
| `test/.../service/InventoryAdjustmentServiceTest.java` |

---

## Task 1: WMS — PurchaseOrder 도메인

**Files:**
- Create: `src/main/java/com/jhg/wms/domain/PurchaseOrderStatus.java`
- Create: `src/main/java/com/jhg/wms/domain/PurchaseOrder.java`
- Create: `src/main/java/com/jhg/wms/domain/PurchaseOrderItem.java`
- Test: `src/test/java/com/jhg/wms/domain/PurchaseOrderTest.java`

**Interfaces:**
- Produces: `PurchaseOrder.create(String memo, PurchaseOrderItem... items)`, `PurchaseOrder.receive()`, `PurchaseOrderItem.create(Long productId, int quantity)`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/jhg/wms/domain/PurchaseOrderTest.java`:
```java
package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PurchaseOrderTest {

    @Test
    void create_발주는_ORDERED_상태로_생성된다() {
        PurchaseOrder po = PurchaseOrder.create("긴급", PurchaseOrderItem.create(1L, 10));
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(po.getMemo()).isEqualTo("긴급");
        assertThat(po.getItems()).hasSize(1);
        assertThat(po.getCreatedAt()).isNotNull();
    }

    @Test
    void receive_ORDERED에서_RECEIVED로_전이한다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        po.receive();
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
    }

    @Test
    void receive_이미_입고된_발주는_예외를_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        po.receive();
        assertThatThrownBy(po::receive).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
cd C:\study\jhg-wms-project
.\gradlew.bat test --tests "com.jhg.wms.domain.PurchaseOrderTest"
```
Expected: FAIL — PurchaseOrderTest 클래스 없음

- [ ] **Step 3: PurchaseOrderStatus 구현**

`src/main/java/com/jhg/wms/domain/PurchaseOrderStatus.java`:
```java
package com.jhg.wms.domain;

public enum PurchaseOrderStatus { ORDERED, RECEIVED }
```

- [ ] **Step 4: PurchaseOrderItem 구현**

`src/main/java/com/jhg/wms/domain/PurchaseOrderItem.java`:
```java
package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderItem {

    @Id @GeneratedValue
    @Column(name = "purchase_order_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

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

- [ ] **Step 5: PurchaseOrder 구현**

`src/main/java/com/jhg/wms/domain/PurchaseOrder.java`:
```java
package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrder {

    @Id @GeneratedValue
    @Column(name = "purchase_order_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    private PurchaseOrderStatus status;

    private String memo;
    private LocalDateTime createdAt;
    private LocalDateTime receivedAt;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL)
    private List<PurchaseOrderItem> items = new ArrayList<>();

    public static PurchaseOrder create(String memo, PurchaseOrderItem... items) {
        PurchaseOrder po = new PurchaseOrder();
        po.memo = memo;
        po.status = PurchaseOrderStatus.ORDERED;
        po.createdAt = LocalDateTime.now();
        for (PurchaseOrderItem item : items) {
            po.items.add(item);
            item.setPurchaseOrder(po);
        }
        return po;
    }

    public void receive() {
        if (status == PurchaseOrderStatus.RECEIVED)
            throw new IllegalStateException("이미 입고 처리된 발주입니다. (발주 #" + id + ")");
        this.status = PurchaseOrderStatus.RECEIVED;
        this.receivedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

```
.\gradlew.bat test --tests "com.jhg.wms.domain.PurchaseOrderTest"
```
Expected: PASS 3건

- [ ] **Step 7: 커밋**

```
git add src/main/java/com/jhg/wms/domain/PurchaseOrder*.java src/test/java/com/jhg/wms/domain/PurchaseOrderTest.java
git commit -m "feat(wms): PurchaseOrder 도메인 — ORDERED→RECEIVED 상태전이 + 중복입고 방어"
```

---

## Task 2: WMS — PurchaseOrderRepository + PurchaseOrderService

**Files:**
- Create: `src/main/java/com/jhg/wms/repository/PurchaseOrderRepository.java`
- Create: `src/main/java/com/jhg/wms/service/PurchaseOrderService.java`
- Test: `src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java`

**Interfaces:**
- Consumes: `PurchaseOrder.create()`, `PurchaseOrder.receive()`, `PurchaseOrderItem.create()`, `InventoryService.adjust(Long productId, int delta)`
- Produces: `PurchaseOrderService.create(List<PurchaseOrderLine> lines, String memo) → Long`, `PurchaseOrderService.receive(Long poId) → Long`, `PurchaseOrderService.findAllWithItems() → List<PurchaseOrder>`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java`:
```java
package com.jhg.wms.service;

import com.jhg.wms.domain.PurchaseOrder;
import com.jhg.wms.domain.PurchaseOrderItem;
import com.jhg.wms.domain.PurchaseOrderStatus;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.PurchaseOrderRepository;
import com.jhg.wms.repository.ReservationRepository;
import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
class PurchaseOrderServiceTest {

    @Autowired InventoryRepository inventoryRepo;
    @Autowired ReservationRepository reservationRepo;
    @Autowired PurchaseOrderRepository poRepo;
    InventoryService inventoryService;
    PurchaseOrderService service;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(inventoryRepo, reservationRepo);
        service = new PurchaseOrderService(poRepo, inventoryService);
    }

    @Test
    void create_발주는_ORDERED_상태로_저장된다() {
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 20)), "긴급 발주");
        PurchaseOrder saved = poRepo.findById(poId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(saved.getMemo()).isEqualTo("긴급 발주");
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().get(0).getProductId()).isEqualTo(1L);
        assertThat(saved.getItems().get(0).getQuantity()).isEqualTo(20);
    }

    @Test
    void create_품목이_없으면_예외를_던진다() {
        assertThatThrownBy(() -> service.create(List.of(), "메모"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_수량이_0이면_예외를_던진다() {
        assertThatThrownBy(() -> service.create(List.of(new PurchaseOrderLine(1L, 0)), "메모"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receive_입고하면_RECEIVED가_되고_재고가_늘어난다() {
        // 재고 시드
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, 5));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 10)), "발주");

        service.receive(poId);

        PurchaseOrder po = poRepo.findById(poId).orElseThrow();
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
        assertThat(inventoryRepo.findByProductId(1L).orElseThrow().getOnHandQty()).isEqualTo(15);
    }

    @Test
    void receive_없는_발주는_예외를_던진다() {
        assertThatThrownBy(() -> service.receive(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void receive_중복_입고는_예외를_던진다() {
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 5)), "발주");
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, 0));
        service.receive(poId);
        assertThatThrownBy(() -> service.receive(poId))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
.\gradlew.bat test --tests "com.jhg.wms.service.PurchaseOrderServiceTest"
```
Expected: FAIL

- [ ] **Step 3: PurchaseOrderRepository 구현**

`src/main/java/com/jhg/wms/repository/PurchaseOrderRepository.java`:
```java
package com.jhg.wms.repository;

import com.jhg.wms.domain.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @Query("select distinct po from PurchaseOrder po left join fetch po.items order by po.id desc")
    List<PurchaseOrder> findAllWithItems();
}
```

- [ ] **Step 4: PurchaseOrderService 구현**

`src/main/java/com/jhg/wms/service/PurchaseOrderService.java`:
```java
package com.jhg.wms.service;

import com.jhg.wms.domain.PurchaseOrder;
import com.jhg.wms.domain.PurchaseOrderItem;
import com.jhg.wms.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final InventoryService inventoryService;

    public record PurchaseOrderLine(Long productId, int quantity) {}

    @Transactional
    public Long create(List<PurchaseOrderLine> lines, String memo) {
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("발주 품목이 없습니다.");
        PurchaseOrderItem[] items = lines.stream()
                .map(l -> {
                    if (l.quantity() < 1)
                        throw new IllegalArgumentException("발주 수량은 1개 이상이어야 합니다.");
                    return PurchaseOrderItem.create(l.productId(), l.quantity());
                })
                .toArray(PurchaseOrderItem[]::new);
        return purchaseOrderRepository.save(PurchaseOrder.create(memo, items)).getId();
    }

    @Transactional
    public Long receive(Long poId) {
        PurchaseOrder po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new IllegalArgumentException("발주가 없습니다: id=" + poId));
        po.receive(); // 중복 입고 시 IllegalStateException
        po.getItems().forEach(item -> inventoryService.adjust(item.getProductId(), item.getQuantity()));
        // ponytail: S3에서 OMS 콜백(StockReplenishedHandler) 추가. 현재는 OMS가 자체 트리거 유지.
        return po.getId();
    }

    public List<PurchaseOrder> findAllWithItems() {
        return purchaseOrderRepository.findAllWithItems();
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```
.\gradlew.bat test --tests "com.jhg.wms.service.PurchaseOrderServiceTest"
```
Expected: PASS 6건

- [ ] **Step 6: 전체 빌드 확인**

```
.\gradlew.bat build
```

- [ ] **Step 7: 커밋**

```
git add src/main/java/com/jhg/wms/repository/PurchaseOrderRepository.java src/main/java/com/jhg/wms/service/PurchaseOrderService.java src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java
git commit -m "feat(wms): PurchaseOrderService — 발주 생성/입고/조회, 입고 시 재고 자동 증가"
```

---

## Task 3: WMS — PurchaseOrder REST API

**Files:**
- Create: `src/main/java/com/jhg/wms/web/PurchaseOrderRequest.java`
- Create: `src/main/java/com/jhg/wms/web/PurchaseOrderResponse.java`
- Create: `src/main/java/com/jhg/wms/web/PurchaseOrderController.java`
- Test: `src/test/java/com/jhg/wms/web/PurchaseOrderControllerTest.java`

**Interfaces:**
- Consumes: `PurchaseOrderService.create()`, `PurchaseOrderService.receive()`, `PurchaseOrderService.findAllWithItems()`
- Produces:
  - `GET /api/purchase-orders` → `List<PurchaseOrderResponse>`
  - `POST /api/purchase-orders` body: `{"lines":[{"productId":1,"quantity":10}],"memo":"긴급"}` → `PurchaseOrderResponse`
  - `POST /api/purchase-orders/receive?poId=7` → 200 OK / 400(중복) / 404(없음)

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/jhg/wms/web/PurchaseOrderControllerTest.java`:
```java
package com.jhg.wms.web;

import com.jhg.wms.domain.PurchaseOrder;
import com.jhg.wms.domain.PurchaseOrderItem;
import com.jhg.wms.service.PurchaseOrderService;
import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PurchaseOrderController.class)
class PurchaseOrderControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean PurchaseOrderService purchaseOrderService;

    @Test
    void 발주_목록을_반환한다() throws Exception {
        PurchaseOrder po = PurchaseOrder.create("긴급", PurchaseOrderItem.create(1L, 10));
        when(purchaseOrderService.findAllWithItems()).thenReturn(List.of(po));

        mockMvc.perform(get("/api/purchase-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ORDERED"))
                .andExpect(jsonPath("$[0].memo").value("긴급"))
                .andExpect(jsonPath("$[0].items[0].productId").value(1))
                .andExpect(jsonPath("$[0].items[0].quantity").value(10));
    }

    @Test
    void 발주를_생성하고_응답을_반환한다() throws Exception {
        when(purchaseOrderService.create(anyList(), anyString())).thenReturn(7L);
        PurchaseOrder po = PurchaseOrder.create("긴급", PurchaseOrderItem.create(1L, 10));
        when(purchaseOrderService.findAllWithItems()).thenReturn(List.of(po));

        mockMvc.perform(post("/api/purchase-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"productId\":1,\"quantity\":10}],\"memo\":\"긴급\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));

        verify(purchaseOrderService).create(anyList(), eq("긴급"));
    }

    @Test
    void 발주_품목이_없으면_400을_반환한다() throws Exception {
        when(purchaseOrderService.create(anyList(), any()))
                .thenThrow(new IllegalArgumentException("발주 품목이 없습니다."));

        mockMvc.perform(post("/api/purchase-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[],\"memo\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 입고하면_200을_반환한다() throws Exception {
        when(purchaseOrderService.receive(7L)).thenReturn(7L);

        mockMvc.perform(post("/api/purchase-orders/receive").param("poId", "7"))
                .andExpect(status().isOk());

        verify(purchaseOrderService).receive(7L);
    }

    @Test
    void 없는_발주를_입고하면_404를_반환한다() throws Exception {
        when(purchaseOrderService.receive(99L))
                .thenThrow(new IllegalArgumentException("발주가 없습니다: id=99"));

        mockMvc.perform(post("/api/purchase-orders/receive").param("poId", "99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 중복_입고는_409를_반환한다() throws Exception {
        when(purchaseOrderService.receive(7L))
                .thenThrow(new IllegalStateException("이미 입고 처리된 발주입니다."));

        mockMvc.perform(post("/api/purchase-orders/receive").param("poId", "7"))
                .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
.\gradlew.bat test --tests "com.jhg.wms.web.PurchaseOrderControllerTest"
```
Expected: FAIL

- [ ] **Step 3: Request/Response DTO 작성**

`src/main/java/com/jhg/wms/web/PurchaseOrderRequest.java`:
```java
package com.jhg.wms.web;

import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;

import java.util.List;

public record PurchaseOrderRequest(List<LineItem> lines, String memo) {
    public record LineItem(Long productId, int quantity) {}

    public List<PurchaseOrderLine> toServiceLines() {
        return lines.stream().map(l -> new PurchaseOrderLine(l.productId(), l.quantity())).toList();
    }
}
```

`src/main/java/com/jhg/wms/web/PurchaseOrderResponse.java`:
```java
package com.jhg.wms.web;

import com.jhg.wms.domain.PurchaseOrder;

import java.time.LocalDateTime;
import java.util.List;

public record PurchaseOrderResponse(
        Long id, String status, String memo,
        LocalDateTime createdAt, LocalDateTime receivedAt,
        List<ItemResponse> items
) {
    public record ItemResponse(Long id, Long productId, int quantity) {}

    public static PurchaseOrderResponse from(PurchaseOrder po) {
        return new PurchaseOrderResponse(
                po.getId(), po.getStatus().name(), po.getMemo(),
                po.getCreatedAt(), po.getReceivedAt(),
                po.getItems().stream()
                        .map(i -> new ItemResponse(i.getId(), i.getProductId(), i.getQuantity()))
                        .toList()
        );
    }
}
```

- [ ] **Step 4: PurchaseOrderController 구현**

`src/main/java/com/jhg/wms/web/PurchaseOrderController.java`:
```java
package com.jhg.wms.web;

import com.jhg.wms.service.PurchaseOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @GetMapping
    public List<PurchaseOrderResponse> list() {
        return purchaseOrderService.findAllWithItems().stream()
                .map(PurchaseOrderResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<PurchaseOrderResponse> create(@RequestBody PurchaseOrderRequest req) {
        try {
            Long poId = purchaseOrderService.create(req.toServiceLines(), req.memo());
            // 생성 후 다시 조회해 items까지 포함한 응답 반환
            return ResponseEntity.ok(PurchaseOrderResponse.from(
                    purchaseOrderService.findAllWithItems().stream()
                            .filter(po -> po.getId().equals(poId))
                            .findFirst().orElseThrow()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/receive")
    public ResponseEntity<Void> receive(@RequestParam Long poId) {
        try {
            purchaseOrderService.receive(poId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```
.\gradlew.bat test --tests "com.jhg.wms.web.PurchaseOrderControllerTest"
```
Expected: PASS 6건

- [ ] **Step 6: 전체 빌드 확인**

```
.\gradlew.bat build
```

- [ ] **Step 7: 커밋**

```
git add src/main/java/com/jhg/wms/web/PurchaseOrder*.java src/test/java/com/jhg/wms/web/PurchaseOrderControllerTest.java
git commit -m "feat(wms): PurchaseOrder REST API — GET/POST /api/purchase-orders, POST /receive"
```

---

## Task 4: WMS — 관리자 Thymeleaf UI

**Files:**
- Create: `src/main/java/com/jhg/wms/web/WmsAdminController.java`
- Create: `src/main/resources/templates/admin/inventory.html`
- Create: `src/main/resources/templates/admin/purchaseorders.html`

> 테스트 없음 — Thymeleaf 화면은 두 앱을 동시 기동해 수동 검증한다. 단, WmsAdminController는 Spring Security 없으므로 `@WebMvcTest` 슬라이스로 smoke test 작성.

**Interfaces:**
- Consumes: `InventoryService.findAllRows()`, `InventoryService.adjust()`, `PurchaseOrderService.create()`, `PurchaseOrderService.receive()`, `PurchaseOrderService.findAllWithItems()`
- Produces: `GET /admin/inventory`, `POST /admin/inventory/adjust`, `GET /admin/purchase-orders`, `POST /admin/purchase-orders`, `POST /admin/purchase-orders/receive`

- [ ] **Step 1: WmsAdminController 구현**

`src/main/java/com/jhg/wms/web/WmsAdminController.java`:
```java
package com.jhg.wms.web;

import com.jhg.wms.service.InventoryService;
import com.jhg.wms.service.PurchaseOrderService;
import com.jhg.wms.service.PurchaseOrderService.PurchaseOrderLine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class WmsAdminController {

    private final InventoryService inventoryService;
    private final PurchaseOrderService purchaseOrderService;

    @GetMapping("/admin/inventory")
    public String inventory(Model model) {
        model.addAttribute("products", inventoryService.findAllRows());
        return "admin/inventory";
    }

    @PostMapping("/admin/inventory/adjust")
    public String adjust(@RequestParam Long productId, @RequestParam int delta,
                         @RequestParam(defaultValue = "") String reason,
                         RedirectAttributes ra) {
        try {
            int adjusted = inventoryService.adjust(productId, delta);
            ra.addFlashAttribute("successMessage", "재고 조정 완료. (현재 " + adjusted + "개)");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inventory";
    }

    @GetMapping("/admin/purchase-orders")
    public String purchaseOrders(Model model) {
        model.addAttribute("purchaseOrders", purchaseOrderService.findAllWithItems());
        model.addAttribute("products", inventoryService.findAllRows());
        return "admin/purchaseorders";
    }

    @PostMapping("/admin/purchase-orders")
    public String createPo(@ModelAttribute PurchaseOrderForm form, RedirectAttributes ra) {
        List<PurchaseOrderLine> lines = form.getItems().stream()
                .map(i -> new PurchaseOrderLine(i.getProductId(), i.getQuantity()))
                .toList();
        try {
            Long poId = purchaseOrderService.create(lines, form.getMemo());
            ra.addFlashAttribute("successMessage", "발주 생성 완료. (발주 #" + poId + ")");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders";
    }

    @PostMapping("/admin/purchase-orders/receive")
    public String receive(@RequestParam Long poId, RedirectAttributes ra) {
        try {
            purchaseOrderService.receive(poId);
            ra.addFlashAttribute("successMessage", "입고 처리 완료. (발주 #" + poId + ")");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders";
    }
}
```

- [ ] **Step 2: PurchaseOrderForm 추가**

`src/main/java/com/jhg/wms/web/PurchaseOrderForm.java`:
```java
package com.jhg.wms.web;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter @Setter
public class PurchaseOrderForm {
    private List<Item> items = new ArrayList<>();
    private String memo;

    @Getter @Setter
    public static class Item {
        private Long productId;
        private int quantity;
    }
}
```

- [ ] **Step 3: WMS 재고 관리 템플릿 작성**

`src/main/resources/templates/admin/inventory.html` (OMS 템플릿에서 CSRF 제거 + WMS 포트 반영):
```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8" />
  <title>WMS 재고 관리</title>
  <style>
    body { font-family: sans-serif; padding: 24px; max-width: 900px; margin: 0 auto; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 8px 12px; border: 1px solid #ddd; text-align: left; }
    th { background: #f5f5f5; }
    .flash-ok { color: green; padding: 8px; border: 1px solid green; margin-bottom: 12px; }
    .flash-err { color: red; padding: 8px; border: 1px solid red; margin-bottom: 12px; }
    .form-row { display: flex; gap: 8px; align-items: flex-end; margin-bottom: 16px; }
    input, select { padding: 6px 10px; border: 1px solid #ccc; border-radius: 4px; }
    button { padding: 7px 16px; background: #f4a261; color: white; border: none; border-radius: 4px; cursor: pointer; }
    nav { margin-bottom: 16px; }
    nav a { margin-right: 12px; }
  </style>
</head>
<body>
  <nav><a th:href="@{/admin/purchase-orders}">발주 관리</a></nav>
  <h2>WMS 재고 관리</h2>

  <div class="flash-ok" th:if="${successMessage}" th:text="${successMessage}"></div>
  <div class="flash-err" th:if="${errorMessage}" th:text="${errorMessage}"></div>

  <h3>수동 재고 조정</h3>
  <form th:action="@{/admin/inventory/adjust}" method="post" class="form-row">
    <div>
      <label>상품 ID</label><br/>
      <select name="productId">
        <option th:each="p : ${products}" th:value="${p.productId}" th:text="${p.productId}">1</option>
      </select>
    </div>
    <div>
      <label>증감 수량 (+/−)</label><br/>
      <input type="number" name="delta" value="-1" style="width:80px" />
    </div>
    <div>
      <label>사유</label><br/>
      <input type="text" name="reason" placeholder="정기실사 / 파손 등" />
    </div>
    <button type="submit">조정</button>
  </form>

  <h3>전체 재고</h3>
  <table>
    <thead><tr><th>상품 ID</th><th>보유 수량</th></tr></thead>
    <tbody>
      <tr th:each="p : ${products}">
        <td th:text="${p.productId}">1</td>
        <td th:text="${p.onHandQty}">15</td>
      </tr>
      <tr th:if="${#lists.isEmpty(products)}">
        <td colspan="2">재고 없음</td>
      </tr>
    </tbody>
  </table>
</body>
</html>
```

- [ ] **Step 4: WMS 발주 관리 템플릿 작성**

`src/main/resources/templates/admin/purchaseorders.html`:
```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8" />
  <title>WMS 발주 관리</title>
  <style>
    body { font-family: sans-serif; padding: 24px; max-width: 900px; margin: 0 auto; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 8px 12px; border: 1px solid #ddd; text-align: left; }
    th { background: #f5f5f5; }
    .flash-ok { color: green; padding: 8px; border: 1px solid green; margin-bottom: 12px; }
    .flash-err { color: red; padding: 8px; border: 1px solid red; margin-bottom: 12px; }
    .form-row { display: flex; gap: 8px; align-items: flex-end; margin-bottom: 16px; }
    input, select { padding: 6px 10px; border: 1px solid #ccc; border-radius: 4px; }
    button { padding: 7px 16px; background: #f4a261; color: white; border: none; border-radius: 4px; cursor: pointer; }
    button.sm { padding: 4px 10px; font-size: 13px; }
    nav { margin-bottom: 16px; }
    nav a { margin-right: 12px; }
  </style>
</head>
<body>
  <nav><a th:href="@{/admin/inventory}">재고 관리</a></nav>
  <h2>WMS 발주 관리</h2>

  <div class="flash-ok" th:if="${successMessage}" th:text="${successMessage}"></div>
  <div class="flash-err" th:if="${errorMessage}" th:text="${errorMessage}"></div>

  <h3>발주 생성</h3>
  <form th:action="@{/admin/purchase-orders}" method="post" class="form-row">
    <div>
      <label>상품 ID</label><br/>
      <select name="items[0].productId">
        <option th:each="p : ${products}" th:value="${p.productId}" th:text="${p.productId}">1</option>
      </select>
    </div>
    <div>
      <label>수량</label><br/>
      <input type="number" name="items[0].quantity" min="1" value="10" style="width:80px" />
    </div>
    <div>
      <label>메모</label><br/>
      <input type="text" name="memo" placeholder="긴급 발주 등" />
    </div>
    <button type="submit">발주 생성</button>
  </form>

  <h3>발주 현황</h3>
  <table>
    <thead>
      <tr><th>발주번호</th><th>상태</th><th>품목</th><th>메모</th><th>발주일시</th><th>입고</th></tr>
    </thead>
    <tbody>
      <tr th:each="po : ${purchaseOrders}">
        <td th:text="${po.id}">1</td>
        <td th:text="${po.status.name() == 'RECEIVED'} ? '입고완료' : '입고대기'">입고대기</td>
        <td>
          <span th:each="item, stat : ${po.items}">
            <span th:text="|상품#${item.productId} x${item.quantity}|">상품#1 x10</span><span th:if="${!stat.last}">, </span>
          </span>
        </td>
        <td th:text="${po.memo}">긴급</td>
        <td th:text="${#temporals.format(po.createdAt,'yyyy-MM-dd HH:mm')}">2026-07-01 10:00</td>
        <td>
          <form th:if="${po.status.name() != 'RECEIVED'}" th:action="@{/admin/purchase-orders/receive}" method="post" style="margin:0">
            <input type="hidden" name="poId" th:value="${po.id}" />
            <button class="sm" type="submit">입고</button>
          </form>
          <span th:if="${po.status.name() == 'RECEIVED'}">—</span>
        </td>
      </tr>
      <tr th:if="${#lists.isEmpty(purchaseOrders)}">
        <td colspan="6">발주 내역이 없습니다.</td>
      </tr>
    </tbody>
  </table>
</body>
</html>
```

- [ ] **Step 5: 전체 빌드 확인**

```
.\gradlew.bat build
```

- [ ] **Step 6: 두 앱 동시 기동 후 수동 확인**

1. H2 서버 기동 (별도 터미널)
2. OMS: `.\gradlew.bat bootRun` (포트 8080)
3. WMS: `.\gradlew.bat bootRun` (포트 8081)
4. `http://localhost:8081/admin/inventory` — 재고 목록 확인
5. `http://localhost:8081/admin/purchase-orders` — 발주 생성 후 입고 확인 → 재고 증가 확인

- [ ] **Step 7: 커밋**

```
git add src/main/java/com/jhg/wms/web/WmsAdminController.java src/main/java/com/jhg/wms/web/PurchaseOrderForm.java src/main/resources/templates/
git commit -m "feat(wms): 관리자 Thymeleaf UI — 재고 조회·조정, 발주 생성·입고"
```

- [ ] **Step 8: WMS 전체 push**

```
git push
```

---

## Task 5: OMS — WmsPurchaseOrderAdapter

**Files:**
- Create: `src/main/java/com/jhg/hgpage/wms/adapter/WmsPurchaseOrderAdapter.java`
- Create: `src/main/java/com/jhg/hgpage/wms/dto/PurchaseOrderDto.java`
- Test: `src/test/java/com/jhg/hgpage/adapter/WmsPurchaseOrderAdapterTest.java`

**Interfaces:**
- Produces: `WmsPurchaseOrderAdapter.create(List<PurchaseOrderLine> lines, String memo) → Long`, `.receive(Long poId) → Long`, `.findAllWithItems() → List<PurchaseOrderDto>`
- `PurchaseOrderLine` record: `(Long productId, int quantity)`

- [ ] **Step 1: PurchaseOrderDto 작성**

`src/main/java/com/jhg/hgpage/wms/dto/PurchaseOrderDto.java`:
```java
package com.jhg.hgpage.wms.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PurchaseOrderDto(
        Long id,
        String status,
        String memo,
        LocalDateTime createdAt,
        LocalDateTime receivedAt,
        List<ItemDto> items
) {
    public record ItemDto(Long id, Long productId, int quantity) {}
}
```

- [ ] **Step 2: 실패 테스트 작성**

`src/test/java/com/jhg/hgpage/adapter/WmsPurchaseOrderAdapterTest.java`:
```java
package com.jhg.hgpage.adapter;

import com.jhg.hgpage.wms.adapter.WmsPurchaseOrderAdapter;
import com.jhg.hgpage.wms.adapter.WmsPurchaseOrderAdapter.PurchaseOrderLine;
import com.jhg.hgpage.wms.dto.PurchaseOrderDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@RestClientTest(WmsPurchaseOrderAdapter.class)
@TestPropertySource(properties = "wms.base-url=http://wms-test")
class WmsPurchaseOrderAdapterTest {

    @Autowired MockRestServiceServer server;
    @Autowired WmsPurchaseOrderAdapter adapter;

    @Test
    void findAllWithItems_WMS에_GET_요청을_보내고_목록을_반환한다() {
        server.expect(requestTo("http://wms-test/api/purchase-orders"))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess(
                  "[{\"id\":1,\"status\":\"ORDERED\",\"memo\":\"긴급\",\"createdAt\":\"2026-07-01T10:00:00\",\"receivedAt\":null,\"items\":[{\"id\":1,\"productId\":1,\"quantity\":10}]}]",
                  MediaType.APPLICATION_JSON));

        List<PurchaseOrderDto> result = adapter.findAllWithItems();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("ORDERED");
        assertThat(result.get(0).items().get(0).productId()).isEqualTo(1L);
        server.verify();
    }

    @Test
    void create_WMS에_POST_요청을_보내고_발주번호를_반환한다() {
        server.expect(requestTo("http://wms-test/api/purchase-orders"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(
                  "{\"id\":7,\"status\":\"ORDERED\",\"memo\":\"긴급\",\"createdAt\":\"2026-07-01T10:00:00\",\"receivedAt\":null,\"items\":[]}",
                  MediaType.APPLICATION_JSON));

        Long poId = adapter.create(List.of(new PurchaseOrderLine(1L, 10)), "긴급");

        assertThat(poId).isEqualTo(7L);
        server.verify();
    }

    @Test
    void receive_WMS에_POST_요청을_보낸다() {
        server.expect(requestTo("http://wms-test/api/purchase-orders/receive?poId=7"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess());

        adapter.receive(7L);
        server.verify();
    }

    @Test
    void receive_404면_IllegalArgumentException을_던진다() {
        server.expect(requestTo("http://wms-test/api/purchase-orders/receive?poId=99"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> adapter.receive(99L))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void receive_409면_IllegalStateException을_던진다() {
        server.expect(requestTo("http://wms-test/api/purchase-orders/receive?poId=7"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT));

        assertThatThrownBy(() -> adapter.receive(7L))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

OMS 프로젝트에서:
```
cd C:\study\jhg-commerce-project
.\gradlew.bat test --tests "com.jhg.hgpage.adapter.WmsPurchaseOrderAdapterTest"
```
Expected: FAIL

- [ ] **Step 4: WmsPurchaseOrderAdapter 구현**

`src/main/java/com/jhg/hgpage/wms/adapter/WmsPurchaseOrderAdapter.java`:
```java
package com.jhg.hgpage.wms.adapter;

import com.jhg.hgpage.wms.dto.PurchaseOrderDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class WmsPurchaseOrderAdapter {

    private final RestClient restClient;

    public WmsPurchaseOrderAdapter(RestClient.Builder builder,
                                   @Value("${wms.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public record PurchaseOrderLine(Long productId, int quantity) {}
    private record CreateRequest(List<PurchaseOrderLine> lines, String memo) {}

    public List<PurchaseOrderDto> findAllWithItems() {
        List<PurchaseOrderDto> result = restClient.get()
                .uri("/api/purchase-orders")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : List.of();
    }

    public Long create(List<PurchaseOrderLine> lines, String memo) {
        PurchaseOrderDto dto = restClient.post()
                .uri("/api/purchase-orders")
                .body(new CreateRequest(lines, memo))
                .retrieve()
                .body(PurchaseOrderDto.class);
        return dto != null ? dto.id() : null;
    }

    public Long receive(Long poId) {
        try {
            restClient.post()
                    .uri("/api/purchase-orders/receive?poId={id}", poId)
                    .retrieve()
                    .toBodilessEntity();
            return poId;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND)
                throw new IllegalArgumentException("발주가 없습니다: id=" + poId);
            throw new IllegalStateException("입고 처리 실패: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```
.\gradlew.bat test --tests "com.jhg.hgpage.adapter.WmsPurchaseOrderAdapterTest"
```
Expected: PASS 5건

- [ ] **Step 6: 커밋**

```
cd C:\study\jhg-commerce-project
git add src/main/java/com/jhg/hgpage/wms/adapter/WmsPurchaseOrderAdapter.java src/main/java/com/jhg/hgpage/wms/dto/PurchaseOrderDto.java src/test/java/com/jhg/hgpage/adapter/WmsPurchaseOrderAdapterTest.java
git commit -m "feat(oms): WmsPurchaseOrderAdapter — PO 생성/입고/조회 REST 어댑터"
```

---

## Task 6: OMS — InventoryAdminController 전환

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java`
- Modify: `src/main/resources/templates/admin/purchaseorders.html`
- Modify: `src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java`

**Note:** `PurchaseOrderForm`은 OMS `wms/web/form/PurchaseOrderForm.java`를 그대로 사용.

- [ ] **Step 1: InventoryAdminController 수정**

`src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java` 전체 교체:
```java
package com.jhg.hgpage.wms.web.controller;

import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.adapter.WmsInventoryAdapter;
import com.jhg.hgpage.wms.adapter.WmsInventoryQueryAdapter;
import com.jhg.hgpage.wms.adapter.WmsPurchaseOrderAdapter;
import com.jhg.hgpage.wms.adapter.WmsPurchaseOrderAdapter.PurchaseOrderLine;
import com.jhg.hgpage.wms.web.form.PurchaseOrderForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class InventoryAdminController {

    private final WmsInventoryAdapter wmsInventoryAdapter;
    private final WmsInventoryQueryAdapter wmsInventoryQueryAdapter;
    private final WmsPurchaseOrderAdapter wmsPurchaseOrderAdapter;
    private final StockReplenishedHandler stockReplenishedHandler;

    @GetMapping("/admin/inventory")
    public String inventory(Model model) {
        model.addAttribute("products", wmsInventoryQueryAdapter.allRows());
        return "admin/inventory";
    }

    @GetMapping("/admin/purchase-orders")
    public String purchaseOrders(Model model) {
        model.addAttribute("purchaseOrders", wmsPurchaseOrderAdapter.findAllWithItems());
        model.addAttribute("products", wmsInventoryQueryAdapter.allRows());
        return "admin/purchaseorders";
    }

    @PostMapping("/admin/inventory/adjust")
    public String adjustInventory(@RequestParam Long productId,
                                  @RequestParam int delta,
                                  @RequestParam(defaultValue = "") String reason,
                                  RedirectAttributes redirectAttributes) {
        try {
            int adjusted = wmsInventoryAdapter.adjust(productId, delta, reason);
            if (delta > 0) {
                // ponytail: S3에서 WMS→OMS 콜백으로 이동. S2까지는 OMS가 직접 트리거.
                stockReplenishedHandler.onReplenished(List.of(productId));
            }
            redirectAttributes.addFlashAttribute("successMessage",
                    "재고가 조정되었습니다. (현재 " + adjusted + "개)");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inventory";
    }

    @PostMapping("/admin/purchase-orders")
    public String createPurchaseOrder(@ModelAttribute PurchaseOrderForm form,
                                      RedirectAttributes redirectAttributes) {
        List<PurchaseOrderLine> lines = form.getItems().stream()
                .map(item -> new PurchaseOrderLine(item.getProductId(), item.getQuantity()))
                .toList();
        try {
            Long poId = wmsPurchaseOrderAdapter.create(lines, form.getMemo());
            redirectAttributes.addFlashAttribute("successMessage",
                    "발주가 생성되었습니다. (발주 #" + poId + ")");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders";
    }

    @PostMapping("/admin/purchase-orders/receive")
    public String receivePurchaseOrder(@RequestParam Long poId,
                                       RedirectAttributes redirectAttributes) {
        try {
            wmsPurchaseOrderAdapter.receive(poId);
            // ponytail: 입고 후 백오더 트리거는 S3 콜백으로 이동. S2는 WMS가 재고만 증가.
            redirectAttributes.addFlashAttribute("successMessage",
                    "입고 처리되었습니다. (발주 #" + poId + ")");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/purchase-orders";
    }
}
```

- [ ] **Step 2: OMS purchaseorders.html 템플릿 수정**

`src/main/resources/templates/admin/purchaseorders.html`에서 `${po.status.name()}` → `${po.status}` 로 3곳 변경:

```html
<!-- 변경 전 -->
th:classappend="${po.status.name() == 'RECEIVED'} ? 'received' : 'ordered'"
th:text="${po.status.name() == 'RECEIVED'} ? '입고완료' : '입고대기'"
th:if="${po.status.name() != 'RECEIVED'}"
th:if="${po.status.name() == 'RECEIVED'}"

<!-- 변경 후 -->
th:classappend="${po.status == 'RECEIVED'} ? 'received' : 'ordered'"
th:text="${po.status == 'RECEIVED'} ? '입고완료' : '입고대기'"
th:if="${po.status != 'RECEIVED'}"
th:if="${po.status == 'RECEIVED'}"
```

- [ ] **Step 3: InventoryAdminControllerMvcTest 갱신**

`src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java` 전체 교체:
```java
package com.jhg.hgpage.controller.admin;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.wms.adapter.WmsInventoryAdapter;
import com.jhg.hgpage.wms.adapter.WmsInventoryQueryAdapter;
import com.jhg.hgpage.wms.adapter.WmsPurchaseOrderAdapter;
import com.jhg.hgpage.wms.dto.InventoryRow;
import com.jhg.hgpage.wms.dto.PurchaseOrderDto;
import com.jhg.hgpage.wms.web.controller.InventoryAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryAdminController.class)
@Import(SecurityConfig.class)
class InventoryAdminControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean WmsInventoryAdapter wmsInventoryAdapter;
    @MockitoBean WmsInventoryQueryAdapter wmsInventoryQueryAdapter;
    @MockitoBean WmsPurchaseOrderAdapter wmsPurchaseOrderAdapter;
    @MockitoBean StockReplenishedHandler stockReplenishedHandler;

    private UserPrincipal admin() {
        return new UserPrincipal(2L, "admin@admin.com", "관리자", "010-1111-2222", "pw", Role.ADMIN);
    }

    private UserPrincipal user() {
        return new UserPrincipal(1L, "u@u.com", "유저", "010-0000-0000", "pw", Role.USER);
    }

    @Test
    void 재고화면은_재고목록을_조회한다() throws Exception {
        when(wmsInventoryQueryAdapter.allRows()).thenReturn(List.of(new InventoryRow(1L, 15)));

        mockMvc.perform(get("/admin/inventory").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(model().attributeExists("products"));
    }

    @Test
    void 발주화면은_발주목록과_재고목록을_조회한다() throws Exception {
        when(wmsInventoryQueryAdapter.allRows()).thenReturn(List.of(new InventoryRow(1L, 15)));
        when(wmsPurchaseOrderAdapter.findAllWithItems()).thenReturn(List.of(
                new PurchaseOrderDto(1L, "ORDERED", "긴급", null, null, List.of())));

        mockMvc.perform(get("/admin/purchase-orders").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/purchaseorders"))
                .andExpect(model().attributeExists("purchaseOrders", "products"));
    }

    @Test
    void 일반사용자는_재고목록에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/inventory").with(user(user())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 재고를_조정하면_조회페이지로_리다이렉트한다() throws Exception {
        when(wmsInventoryAdapter.adjust(1L, 5, "정기조사")).thenReturn(20);

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(admin())).with(csrf())
                        .param("productId", "1").param("delta", "5").param("reason", "정기조사"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(wmsInventoryAdapter).adjust(1L, 5, "정기조사");
    }

    @Test
    void 재고_증가_시_백오더_트리거를_호출한다() throws Exception {
        when(wmsInventoryAdapter.adjust(1L, 5, "")).thenReturn(20);

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(admin())).with(csrf())
                        .param("productId", "1").param("delta", "5").param("reason", ""))
                .andExpect(status().is3xxRedirection());

        verify(stockReplenishedHandler).onReplenished(List.of(1L));
    }

    @Test
    void 재고_감소_시_백오더_트리거를_호출하지_않는다() throws Exception {
        when(wmsInventoryAdapter.adjust(1L, -3, "")).thenReturn(7);

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(admin())).with(csrf())
                        .param("productId", "1").param("delta", "-3").param("reason", ""))
                .andExpect(status().is3xxRedirection());

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }

    @Test
    void 재고_조정_실패는_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(wmsInventoryAdapter.adjust(1L, -99, "조정"))
                .thenThrow(new IllegalArgumentException("재고는 0 미만이 될 수 없습니다."));

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(admin())).with(csrf())
                        .param("productId", "1").param("delta", "-99").param("reason", "조정"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 발주를_생성하면_성공메시지와_함께_리다이렉트한다() throws Exception {
        when(wmsPurchaseOrderAdapter.create(anyList(), eq("긴급 발주"))).thenReturn(7L);

        mockMvc.perform(post("/admin/purchase-orders")
                        .with(user(admin())).with(csrf())
                        .param("items[0].productId", "1")
                        .param("items[0].quantity", "10")
                        .param("memo", "긴급 발주"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/purchase-orders"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(wmsPurchaseOrderAdapter).create(anyList(), eq("긴급 발주"));
    }

    @Test
    void 입고하면_성공메시지와_함께_리다이렉트한다() throws Exception {
        when(wmsPurchaseOrderAdapter.receive(7L)).thenReturn(7L);

        mockMvc.perform(post("/admin/purchase-orders/receive")
                        .with(user(admin())).with(csrf())
                        .param("poId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/purchase-orders"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(wmsPurchaseOrderAdapter).receive(7L);
    }

    @Test
    void 이미_입고된_발주는_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(wmsPurchaseOrderAdapter.receive(7L))
                .thenThrow(new IllegalStateException("이미 입고 처리된 발주입니다."));

        mockMvc.perform(post("/admin/purchase-orders/receive")
                        .with(user(admin())).with(csrf())
                        .param("poId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```
.\gradlew.bat test --tests "com.jhg.hgpage.controller.admin.InventoryAdminControllerMvcTest"
```
Expected: PASS 9건

- [ ] **Step 5: 전체 빌드 확인**

```
.\gradlew.bat build
```

- [ ] **Step 6: 커밋**

```
git add src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java src/main/resources/templates/admin/purchaseorders.html src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java
git commit -m "feat(oms): InventoryAdminController — PurchaseOrderService→WmsPurchaseOrderAdapter 교체"
```

---

## Task 7: OMS — 인프로세스 wms 코드 삭제 + DB 리셋

**Files (삭제):**
- `wms/service/PurchaseOrderService.java`
- `wms/service/InventoryAdjustmentService.java`
- `wms/domain/PurchaseOrder.java`
- `wms/domain/PurchaseOrderItem.java`
- `wms/domain/enums/PurchaseOrderStatus.java`
- `wms/domain/enums/ReservationStatus.java`
- `wms/repository/PurchaseOrderRepository.java`
- `test/.../domain/PurchaseOrderTest.java`
- `test/.../repository/PurchaseOrderRepositoryTest.java`
- `test/.../service/PurchaseOrderServiceTest.java`
- `test/.../service/InventoryAdjustmentServiceTest.java`

- [ ] **Step 1: 인프로세스 서비스 삭제**

```
cd C:\study\jhg-commerce-project
del src\main\java\com\jhg\hgpage\wms\service\PurchaseOrderService.java
del src\main\java\com\jhg\hgpage\wms\service\InventoryAdjustmentService.java
```

- [ ] **Step 2: 인프로세스 도메인 삭제**

```
del src\main\java\com\jhg\hgpage\wms\domain\PurchaseOrder.java
del src\main\java\com\jhg\hgpage\wms\domain\PurchaseOrderItem.java
del src\main\java\com\jhg\hgpage\wms\domain\enums\PurchaseOrderStatus.java
del src\main\java\com\jhg\hgpage\wms\domain\enums\ReservationStatus.java
del src\main\java\com\jhg\hgpage\wms\repository\PurchaseOrderRepository.java
```

- [ ] **Step 3: 관련 테스트 삭제**

```
del src\test\java\com\jhg\hgpage\domain\PurchaseOrderTest.java
del src\test\java\com\jhg\hgpage\repository\PurchaseOrderRepositoryTest.java
del src\test\java\com\jhg\hgpage\service\PurchaseOrderServiceTest.java
del src\test\java\com\jhg\hgpage\service\InventoryAdjustmentServiceTest.java
```

- [ ] **Step 4: 전체 빌드 확인**

```
.\gradlew.bat build
```
Expected: BUILD SUCCESSFUL (삭제된 클래스를 참조하는 곳이 없어야 함)

- [ ] **Step 5: OMS DB 리셋**

> **주의**: 로컬 H2 TCP DB에 데이터가 있다면 날아간다. OMS DB에서 `inventory`, `reservation`, `purchase_order`, `purchase_order_item` 테이블이 제거된다.

```
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```
기동 후 정상 동작 확인 → Ctrl+C로 종료

- [ ] **Step 6: 커밋**

```
git add -A
git commit -m "chore(oms): wms 인프로세스 PurchaseOrder 코드 삭제 — WMS 물리 분리 완료(S2)"
```

- [ ] **Step 7: 두 앱 동시 기동 통합 검증**

1. WMS 기동 (`--spring.profiles.active=local` 로 WMS DB 리셋): `cd jhg-wms-project && .\gradlew.bat bootRun --args='--spring.profiles.active=local'`
2. OMS 기동: `cd jhg-commerce-project && .\gradlew.bat bootRun`
3. OMS 메인 그리드(`http://localhost:8080/main`) — 가용수량 표시 확인
4. OMS 관리자 재고(`http://localhost:8080/admin/inventory`) — 조정 후 WMS 재고 반영 확인
5. OMS 관리자 발주(`http://localhost:8080/admin/purchase-orders`) — 발주 생성 → WMS DB에 저장됨 확인 (`http://localhost:8081/admin/purchase-orders`)
6. OMS 발주 입고 → OMS 메인 가용수량 증가 확인

- [ ] **Step 8: 최종 push**

```
git push
cd C:\study\jhg-wms-project && git push
```

---

## 완료 기준

- [ ] WMS: `GET/POST /api/purchase-orders`, `POST /api/purchase-orders/receive` 동작
- [ ] WMS: `http://localhost:8081/admin/inventory` 및 `/admin/purchase-orders` 화면 동작
- [ ] OMS: `InventoryAdminController`가 `WmsInventoryAdapter` + `WmsPurchaseOrderAdapter`로만 동작
- [ ] OMS: `wms/service/`, `wms/domain/`, `wms/repository/` 인프로세스 구현 없음
- [ ] 두 앱 동시 기동 시 OMS 전체 기능 정상 동작
- [ ] `.\gradlew.bat build` 양쪽 모두 PASS
