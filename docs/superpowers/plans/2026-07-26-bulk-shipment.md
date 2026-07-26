# Bulk Shipment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OMS 배송 관리 화면에서 선택한 출고 가능 주문을 독립적으로 일괄 처리한다.

**Architecture:** 관리자 컨트롤러가 선택 ID를 중복 제거한 뒤 기존 트랜잭션 서비스 메서드를 주문별로 호출한다. 화면은 별도 벌크 form과 form 속성으로 연결한 체크박스를 사용해 기존 단건 form과 중첩되지 않게 한다.

**Tech Stack:** Java 17, Spring MVC, Thymeleaf, JUnit 5, MockMvc

## Global Constraints

- WMS 코드는 변경하지 않는다.
- 기존 단건 출고 경로와 서비스 메서드를 재사용한다.
- 한 주문의 실패가 다른 성공 주문을 롤백하지 않는다.

---

### Task 1: 선택 출고 컨트롤러

**Files:**
- Modify: `src/test/java/com/jhg/hgpage/controller/admin/OrderAdminControllerMvcTest.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/web/controller/OrderAdminController.java`

- [ ] 성공, 부분 실패, 빈 선택 MVC 테스트를 작성하고 실패를 확인한다.
- [ ] `POST /admin/orders/complete-deliveries`를 최소 구현한다.
- [ ] 관리자 MVC 테스트 통과를 확인한다.

### Task 2: 배송 관리 선택 UI

**Files:**
- Modify: `src/test/java/com/jhg/hgpage/controller/admin/OrderAdminControllerMvcTest.java`
- Modify: `src/main/resources/templates/admin/orders.html`

- [ ] 출고 가능한 주문의 체크박스와 벌크 form 렌더링 테스트를 작성하고 실패를 확인한다.
- [ ] 선택, 전체 선택, 버튼 활성화와 확인 창을 구현한다.
- [ ] 전체 OMS 테스트를 실행한다.
