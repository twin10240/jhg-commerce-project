# Admin Backorder Items Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배송관리 목록에서 주문 품목과 백오더 원인 상품을 표시한다.

**Architecture:** 기존 관리자 주문 조회와 `InventoryQueryPort`를 결합해 백오더 상품의 가용재고를 한 번에 조회한다. DTO가 품목별 표시 상태를 만들고 Thymeleaf는 이를 렌더링한다.

**Tech Stack:** Java 21, Spring MVC, Thymeleaf, JUnit 5, Mockito, MockMvc

## Global Constraints

- WMS 가용재고 조회는 관리자 목록 요청당 최대 1회다.
- `입고 필요`는 `BACKORDERED` 주문에서 주문 수량이 가용재고보다 큰 상품에만 표시한다.
- 개인정보, 상세 페이지, 새 API, 새 의존성은 추가하지 않는다.

---

### Task 1: Admin order item status

**Files:**
- Modify: `src/test/java/com/jhg/hgpage/service/OrderServiceAdminTest.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/dto/AdminOrderDto.java`

**Interfaces:**
- Consumes: `InventoryQueryPort.availableByProductIds(Collection<Long>)`
- Produces: `AdminOrderDto.getItems()`와 품목 속성 `productName`, `quantity`, `inboundRequired`

- [ ] **Step 1: Add a failing service test**

백오더 주문의 상품 1 수량 20, 상품 3 수량 2와 가용재고 18, 45를 사용해 상품 1만 입고 필요인지 검증한다.

- [ ] **Step 2: Run the service test and confirm the missing item model fails**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home bash gradlew test --tests "com.jhg.hgpage.service.OrderServiceAdminTest"
```

- [ ] **Step 3: Add the minimal DTO mapping and one batch inventory query**

백오더 주문의 상품 ID만 모아 가용재고를 조회하고 `AdminOrderDto.from(order, availability)`로 매핑한다.

### Task 2: Shipping table rendering

**Files:**
- Modify: `src/test/java/com/jhg/hgpage/controller/admin/OrderAdminControllerMvcTest.java`
- Modify: `src/main/resources/templates/admin/orders.html`

**Interfaces:**
- Consumes: `AdminOrderDto.getItems()`
- Produces: 배송관리 표의 `주문 상품` 열과 `입고 필요` 배지

- [ ] **Step 1: Add a failing MVC rendering test**

백오더 DTO로 `상품1 × 20`과 `입고 필요`가 HTML에 표시되는지 검증한다.

- [ ] **Step 2: Run the MVC test and confirm the missing column fails**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home bash gradlew test --tests "com.jhg.hgpage.controller.admin.OrderAdminControllerMvcTest"
```

- [ ] **Step 3: Add the minimal table column and styles**

상품 목록을 한 셀에 줄 단위로 표시하고 `inboundRequired=true`인 품목에만 배지를 렌더링한다.

- [ ] **Step 4: Run focused and full tests**

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home bash gradlew test --tests "com.jhg.hgpage.service.OrderServiceAdminTest" --tests "com.jhg.hgpage.controller.admin.OrderAdminControllerMvcTest"
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home bash gradlew test --rerun-tasks
```
