# Backorder Inventory Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OMS 재고 현황에 백오더 총수량, 입고 필요 수량, 할당 대기 수량을 정확히 표시한다.

**Architecture:** 기존 컨트롤러가 이미 보유한 상품별 가용재고와 백오더 수요를 합산한다. 별도 서비스나 DTO를 추가하지 않고 계산 결과만 모델에 전달하며, Thymeleaf는 표시만 담당한다.

**Tech Stack:** Java 21, Spring MVC, Thymeleaf, JUnit 5, MockMvc

## Global Constraints

- `입고 필요 수량 = Σ max(상품별 백오더 수요 - 상품별 가용재고, 0)`
- `할당 대기 수량 = 백오더 총수량 - 입고 필요 수량`
- 기존 사용자 변경과 재고 상세 표는 유지한다.
- 새 의존성이나 추상화를 추가하지 않는다.

---

### Task 1: Backorder summary metrics

**Files:**
- Modify: `src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java`
- Modify: `src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java`
- Modify: `src/main/resources/templates/admin/inventory.html`

**Interfaces:**
- Consumes: `InventoryRow.availableQty()`와 `OrderService.backorderDemandByProductId(List<Long>)`
- Produces: 모델 속성 `backorderQty`, `inboundRequiredQty`, `allocationWaitingQty`

- [ ] **Step 1: Write the failing MVC test**

상품별 가용재고가 18, 45, 60, 150이고 백오더 수요가 20, 2, 1, 1일 때 모델 값 24, 2, 22와 세 화면 라벨을 검증한다.

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home bash gradlew test --tests "com.jhg.hgpage.controller.admin.InventoryAdminControllerMvcTest"
```

Expected: `inboundRequiredQty` 또는 `allocationWaitingQty`가 없어 실패한다.

- [ ] **Step 3: Implement the minimal controller calculation**

상품 ID별 가용재고 맵을 만들고 위 전역 계산식으로 세 모델 속성을 설정한다.

- [ ] **Step 4: Update the summary UI**

기존 `입고 대기 총수량` 카드를 세 지표 카드로 교체하고 5개 카드용 반응형 그리드를 적용한다.

- [ ] **Step 5: Run focused and full tests**

Run the focused command from Step 2, then:

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home bash gradlew test
```

Expected: both commands pass.
