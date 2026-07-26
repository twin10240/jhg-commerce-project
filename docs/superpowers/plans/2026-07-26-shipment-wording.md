# Shipment Wording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 출고 동작을 변경하지 않고 OMS 화면의 `배송완료` 표현을 `출고 처리/출고 완료`로 바로잡는다.

**Architecture:** `DeliveryStatus.COMP`, 서비스 메서드와 HTTP 경로는 호환성을 위해 유지한다. Thymeleaf 노출 문구와 컨트롤러 flash 메시지만 변경하고 MVC 테스트로 관리자 및 고객 화면 결과를 검증한다.

**Tech Stack:** Java 17, Spring Boot 3.5, Thymeleaf, JUnit 5, MockMvc

## Global Constraints

- WMS 예약과 실재고 차감 흐름은 변경하지 않는다.
- `DeliveryStatus.READY/COMP`, `completeDelivery()`와 `/admin/orders/complete-delivery`는 유지한다.
- 실제 배송완료 단계는 설계 문서의 고도화 전략으로만 남긴다.

---

### Task 1: 관리자 출고 처리 문구

**Files:**
- Modify: `src/test/java/com/jhg/hgpage/controller/admin/OrderAdminControllerMvcTest.java`
- Modify: `src/main/resources/templates/admin/orders.html`
- Modify: `src/main/java/com/jhg/hgpage/oms/web/controller/OrderAdminController.java`

**Interfaces:**
- Consumes: `POST /admin/orders/complete-delivery`
- Produces: 관리자 버튼 `출고 처리`, 성공 메시지 `출고 처리되었습니다.`

- [ ] **Step 1: 실패 테스트 작성**

관리자 목록 응답에 `출고 처리`가 있고 `배송완료`가 없으며, POST 성공 flash가
`출고 처리되었습니다. (주문 #10)`인지 검증한다.

- [ ] **Step 2: 실패 확인**

Run: `bash gradlew test --tests "com.jhg.hgpage.controller.admin.OrderAdminControllerMvcTest"`

Expected: 기존 `배송완료` 문구 때문에 FAIL.

- [ ] **Step 3: 최소 구현**

관리자 템플릿의 버튼과 확인 문구, 컨트롤러 성공 flash를 출고 용어로 변경한다.

- [ ] **Step 4: 통과 확인**

Run: `bash gradlew test --tests "com.jhg.hgpage.controller.admin.OrderAdminControllerMvcTest"`

Expected: PASS.

### Task 2: 고객 출고 완료 문구와 문서

**Files:**
- Modify: `src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java`
- Modify: `src/main/resources/templates/orders.html`
- Modify: `src/main/resources/templates/orderview.html`
- Modify: `README.md`
- Modify: `src/main/java/com/jhg/hgpage/oms/README.md`

**Interfaces:**
- Consumes: `DeliveryStatus.COMP`
- Produces: 고객 주문 목록·상세의 `출고 완료` 표시

- [ ] **Step 1: 실패 테스트 작성**

내 주문 목록과 완료 주문 상세에서 `COMP`가 `출고 완료`로 렌더링되는지 검증한다.

- [ ] **Step 2: 실패 확인**

Run: `bash gradlew test --tests "com.jhg.hgpage.controller.order.OrderControllerMvcTest"`

Expected: 기존 `배송 완료` 문구 때문에 FAIL.

- [ ] **Step 3: 최소 구현**

두 고객 템플릿의 완료 표시를 `출고 완료`로 바꾸고 README의 관리자 책임과 흐름을
`출고 처리`로 정비한다. 실제 배송완료 단계는 후속 고도화 항목임을 명시한다.

- [ ] **Step 4: 통과 및 회귀 확인**

Run: `bash gradlew test --tests "com.jhg.hgpage.controller.admin.OrderAdminControllerMvcTest" --tests "com.jhg.hgpage.controller.order.OrderControllerMvcTest"`

Expected: PASS.
