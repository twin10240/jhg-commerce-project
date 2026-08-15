# OMS V2 RMA Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## 실행 현황 (2026-08-15)

- OMS 구현 및 자동 검증 완료: 전체 테스트 331개 통과
- 통합 수동 검증 5/9 완료: V2-5~V2-9 통과
- WMS V2 안정화 후 확인: V2-1 전체 재입고, V2-2 부분 승인·거절, V2-3 폐기, V2-4 접수 취소
- 결제·환불 상태와 금액 계산은 계획대로 후속 단계 범위

아래 체크리스트는 구현 당시의 TDD 실행 계획을 보존하며, 실제 완료 현황과 증거는 이 절과
`docs/manual-verification-scenarios.md`를 기준으로 판단한다.

**Goal:** 배송 완료 주문의 상품별 반품 신청을 OMS에 내구성 있게 저장하고, WMS RMA 접수·검수 결과를 콜백과 보상 조회로 동기화해 Thymeleaf 고객 화면에 표시한다.

**Architecture:** OMS는 고객 요청을 `PENDING_SUBMISSION`으로 먼저 커밋한 뒤 멱등 `requestKey`로 WMS를 호출한다. WMS 호출 실패는 저장된 요청과 스케줄러로 재처리하고, 검수 결과는 인증된 콜백과 `GET /api/returns/{rmaId}` 조회가 같은 상태 적용 서비스로 수렴한다. 결제·환불은 별도 후속 작업이며 이번 구현에는 상태나 가짜 처리를 추가하지 않는다.

**Tech Stack:** Java 17, Spring Boot 3.5.5, Spring MVC, Spring Data JPA, Spring Security, Thymeleaf, RestClient, H2, PostgreSQL, Flyway, JUnit 5, AssertJ, Mockito, MockMvc

## Global Constraints

- 구현 루트는 `/Users/jo/study/jhg-commerce-project`이며 WMS 저장소는 수정하지 않는다.
- 기준 설계는 `docs/superpowers/specs/2026-08-12-oms-v2-rma-integration-design.md`다.
- WMS 계약은 현재 구현 중인 `POST /api/returns`, `GET /api/returns/{rmaId}`, `POST /api/return-status-events` JSON과 맞춘다.
- OMS `requestKey`는 `UUID`로 저장하되 JSON에서는 WMS의 문자열 필드와 호환되는 UUID 문자열로 직렬화한다.
- 새 라이브러리, 메시지 브로커, outbox, feature flag를 추가하지 않는다.
- 토스페이먼츠, 결제 상태, 환불 상태·금액 계산을 추가하지 않는다.
- WMS HTTP 호출은 OMS DB 쓰기 트랜잭션 안에서 실행하지 않는다.
- 고객 반품 사유, Basic 인증정보, WMS 응답 본문을 로그에 남기지 않는다.
- 각 작업은 테스트 실패를 먼저 확인하고 최소 구현 후 관련 테스트를 통과시킨다.
- 기존 미추적 `.DS_Store`는 무시하며 커밋하지 않는다.

---

## File Map

### Delivery lifecycle

- Modify `src/main/java/com/jhg/hgpage/oms/domain/enums/DeliveryStatus.java` — `READY/SHIPPED/DELIVERED` 상태.
- Modify `src/main/java/com/jhg/hgpage/oms/domain/Order.java` — 출고·배송 완료 전이와 취소 가드.
- Modify `src/main/java/com/jhg/hgpage/oms/service/OrderService.java` — WMS 출고와 OMS 배송 완료 유스케이스.
- Modify `src/main/java/com/jhg/hgpage/oms/dto/AdminOrderDto.java` — 관리자 버튼 노출 조건.
- Modify `src/main/java/com/jhg/hgpage/oms/dto/OrderDetailDto.java` — 주문상품 ID와 배송 상태 표시 데이터.
- Modify `src/main/java/com/jhg/hgpage/oms/repository/OrderRepositoryQuery.java` — 관리자 정렬의 새 상태.
- Modify `src/main/java/com/jhg/hgpage/oms/web/controller/OrderAdminController.java` — 출고·배송 완료 POST.
- Modify `src/main/resources/templates/admin/orders.html` and `src/main/resources/templates/orderview.html` — 새 상태 문구와 버튼.

### Return domain and persistence

- Create `src/main/java/com/jhg/hgpage/oms/domain/enums/CustomerReturnStatus.java` — 로컬+WMS 상태.
- Create `src/main/java/com/jhg/hgpage/oms/domain/enums/ReturnDisposition.java` — 검수 처분.
- Create `src/main/java/com/jhg/hgpage/oms/domain/CustomerReturn.java` — 반품 헤더와 상태 전이.
- Create `src/main/java/com/jhg/hgpage/oms/domain/CustomerReturnItem.java` — 주문상품별 요청·검수 결과.
- Create `src/main/java/com/jhg/hgpage/oms/repository/CustomerReturnRepository.java` — 상세·상태·주문별 조회.
- Modify `src/main/java/com/jhg/hgpage/oms/repository/OrderRepository.java` — 주문 행 잠금 조회.
- Create `src/main/resources/db/migration/V5__add_customer_returns.sql` — 테이블·제약·`COMP` 데이터 변환.

### WMS integration and recovery

- Create `src/main/java/com/jhg/hgpage/contract/ReturnPort.java` — 접수·단건 조회 포트와 전송 레코드.
- Create `src/main/java/com/jhg/hgpage/wms/adapter/WmsReturnAdapter.java` — RestClient 구현.
- Create `src/main/java/com/jhg/hgpage/oms/service/CustomerReturnService.java` — 신청·조회·영속 상태 변경.
- Create `src/main/java/com/jhg/hgpage/oms/service/ReturnSubmissionService.java` — 트랜잭션 밖 WMS 접수.
- Create `src/main/java/com/jhg/hgpage/oms/service/ReturnSyncService.java` — WMS 결과 검증·적용 단일 경로.
- Create `src/main/java/com/jhg/hgpage/oms/service/ReturnReconciliationSweeper.java` — 접수 재전송·상태 회수.
- Create `src/main/java/com/jhg/hgpage/oms/web/api/ReturnStatusApiController.java` — WMS 콜백.
- Modify `src/main/java/com/jhg/hgpage/config/SecurityConfig.java` — 콜백 인증 경로 확장.

### Customer web

- Create `src/main/java/com/jhg/hgpage/oms/web/form/CustomerReturnForm.java` — 복수 품목 폼 검증.
- Create `src/main/java/com/jhg/hgpage/oms/dto/CustomerReturnDto.java` — 목록·상세 표시 모델.
- Create `src/main/java/com/jhg/hgpage/oms/web/controller/CustomerReturnController.java` — 신청·상세.
- Modify `src/main/java/com/jhg/hgpage/oms/web/controller/OrderController.java` — 주문 상세 반품 모델.
- Create `src/main/resources/templates/returnview.html` — 고객 반품 상세.
- Modify `src/main/resources/templates/orderview.html` — 신청 폼과 반품 목록.

---

### Task 1: Correct the delivery lifecycle

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/oms/domain/enums/DeliveryStatus.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/domain/Order.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/dto/AdminOrderDto.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/dto/OrderDetailDto.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/repository/OrderRepositoryQuery.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/web/controller/OrderAdminController.java`
- Modify: `src/main/resources/templates/admin/orders.html`
- Modify: `src/main/resources/templates/orderview.html`
- Test: `src/test/java/com/jhg/hgpage/domain/OrderTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/OrderServiceAdminTest.java`
- Test: `src/test/java/com/jhg/hgpage/controller/admin/OrderAdminControllerMvcTest.java`
- Test: `src/test/java/com/jhg/hgpage/repository/OrderRepositoryAdminListTest.java`

**Interfaces:**
- Consumes: existing `InventoryPort.shipAll(Long, Map<Long,Integer>)`.
- Produces: `Order.ship()`, `Order.deliver()`, `OrderService.shipOrder(Long)`, `OrderService.deliverOrder(Long)`, `AdminOrderDto.isShippable()`, `AdminOrderDto.isDeliverable()`.

- [ ] **Step 1: Replace domain tests with explicit shipping and delivery transitions**

Add focused cases to `OrderTest`:

```java
@Test
void 출고하면_READY에서_SHIPPED로_전이한다() {
    Order order = createOrder();
    order.ship();
    assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.SHIPPED);
}

@Test
void 배송완료는_SHIPPED에서만_가능하다() {
    Order order = createOrder();
    assertThatThrownBy(order::deliver).isInstanceOf(IllegalStateException.class);
    order.ship();
    order.deliver();
    assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
}

@Test
void 출고후에는_주문을_취소할수없다() {
    Order order = createOrder();
    order.ship();
    assertThatThrownBy(order::cancel).isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run the domain test and verify the old `COMP` model fails**

Run: `./gradlew test --tests "com.jhg.hgpage.domain.OrderTest"`

Expected: FAIL because `SHIPPED`, `DELIVERED`, `ship()`, and `deliver()` do not exist.

- [ ] **Step 3: Implement the minimal domain transitions**

Use:

```java
public enum DeliveryStatus { READY, SHIPPED, DELIVERED }
```

In `Order` replace `completeDelivery()` with:

```java
public void ship() {
    if (status == OrderStatus.CANCEL) throw new IllegalStateException("취소된 주문은 출고할 수 없습니다.");
    if (status == OrderStatus.BACKORDERED) throw new IllegalStateException("입고 대기 주문은 출고할 수 없습니다.");
    if (delivery.getStatus() != DeliveryStatus.READY) throw new IllegalStateException("출고 준비 상태가 아닙니다.");
    delivery.setStatus(DeliveryStatus.SHIPPED);
}

public void deliver() {
    if (delivery.getStatus() != DeliveryStatus.SHIPPED) throw new IllegalStateException("출고 완료 상태에서만 배송 완료할 수 있습니다.");
    delivery.setStatus(DeliveryStatus.DELIVERED);
}
```

Change `cancel()` to reject every delivery state except `READY`.

- [ ] **Step 4: Update service, DTO, repository ordering, controllers, and templates**

`OrderService` must expose:

```java
@Transactional
public void shipOrder(Long orderId) {
    Order order = findOrder(orderId);
    order.ship();
    inventoryPort.shipAll(order.getId(), order.quantitiesByProductId());
}

@Transactional
public void deliverOrder(Long orderId) {
    findOrder(orderId).deliver();
}
```

Use `/admin/orders/ship`, `/admin/orders/ships`, and `/admin/orders/deliver`. Render `출고 처리` only for `READY`, and `배송 완료` only for `SHIPPED`. Replace every `DeliveryStatus.COMP` condition and customer label with `SHIPPED` or `DELIVERED` according to meaning. Add `orderItemId` and `productId` to `OrderDetailDto.OrderLineDto`; later return tasks consume them.

- [ ] **Step 5: Update and run affected tests**

Run:

```bash
./gradlew test --tests "com.jhg.hgpage.domain.OrderTest" \
  --tests "com.jhg.hgpage.service.OrderServiceAdminTest" \
  --tests "com.jhg.hgpage.controller.admin.OrderAdminControllerMvcTest" \
  --tests "com.jhg.hgpage.repository.OrderRepositoryAdminListTest" \
  --tests "com.jhg.hgpage.controller.order.OrderControllerMvcTest"
```

Expected: PASS; no production or test reference to `DeliveryStatus.COMP` remains.

- [ ] **Step 6: Commit the delivery lifecycle**

```bash
git add src/main/java/com/jhg/hgpage/oms/domain/enums/DeliveryStatus.java src/main/java/com/jhg/hgpage/oms/domain/Order.java src/main/java/com/jhg/hgpage/oms/service/OrderService.java src/main/java/com/jhg/hgpage/oms/dto/AdminOrderDto.java src/main/java/com/jhg/hgpage/oms/dto/OrderDetailDto.java src/main/java/com/jhg/hgpage/oms/repository/OrderRepositoryQuery.java src/main/java/com/jhg/hgpage/oms/web/controller/OrderAdminController.java src/main/resources/templates/admin/orders.html src/main/resources/templates/orderview.html src/test/java/com/jhg/hgpage/domain/OrderTest.java src/test/java/com/jhg/hgpage/service/OrderServiceAdminTest.java src/test/java/com/jhg/hgpage/controller/admin/OrderAdminControllerMvcTest.java src/test/java/com/jhg/hgpage/repository/OrderRepositoryAdminListTest.java src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java
git commit -m "feat(oms): split shipping and delivery states"
```

---

### Task 2: Add the OMS return domain and schema

**Files:**
- Create: `src/main/java/com/jhg/hgpage/oms/domain/enums/CustomerReturnStatus.java`
- Create: `src/main/java/com/jhg/hgpage/oms/domain/enums/ReturnDisposition.java`
- Create: `src/main/java/com/jhg/hgpage/oms/domain/CustomerReturn.java`
- Create: `src/main/java/com/jhg/hgpage/oms/domain/CustomerReturnItem.java`
- Create: `src/main/java/com/jhg/hgpage/oms/repository/CustomerReturnRepository.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/repository/OrderRepository.java`
- Create: `src/main/resources/db/migration/V5__add_customer_returns.sql`
- Test: `src/test/java/com/jhg/hgpage/domain/CustomerReturnTest.java`
- Test: `src/test/java/com/jhg/hgpage/repository/CustomerReturnRepositoryTest.java`

**Interfaces:**
- Consumes: `Order`, `OrderItem`, `DeliveryStatus.DELIVERED` from Task 1.
- Produces: `CustomerReturn.create(Order, UUID, String, List<RequestItem>)`, `markRequested`, `markReceived`, `complete`, `cancel`, `failSubmission`; repository detailed and status queries.

- [ ] **Step 1: Write failing domain tests for creation and result invariants**

Cover these exact cases:

```java
@Test
void 배송완료_주문의_반품요청을_PENDING으로_생성한다() {
    CustomerReturn result = CustomerReturn.create(deliveredOrder(), UUID.randomUUID(), "불량",
            List.of(new CustomerReturn.RequestItem(orderItem(), 2)));
    assertThat(result.getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);
    assertThat(result.getItems()).singleElement().extracting(CustomerReturnItem::getRequestedQuantity).isEqualTo(2);
}

@Test
void 승인0은_REJECTED만_허용한다() {
    CustomerReturnItem item = pendingItem(2);
    assertThatThrownBy(() -> item.applyResult(0, ReturnDisposition.RESTOCKED))
            .isInstanceOf(IllegalArgumentException.class);
    item.applyResult(0, ReturnDisposition.REJECTED);
}

@Test
void 최종상태는_변경할수없다() {
    CustomerReturn result = requestedReturn();
    result.complete(validResults());
    assertThatThrownBy(result::markReceived).isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run tests and verify missing types fail compilation**

Run: `./gradlew test --tests "com.jhg.hgpage.domain.CustomerReturnTest"`

Expected: FAIL because the return domain does not exist.

- [ ] **Step 3: Implement enums and entities**

Use these enum values exactly:

```java
public enum CustomerReturnStatus {
    PENDING_SUBMISSION, SUBMISSION_FAILED, REQUESTED, RECEIVED, COMPLETED, CANCELLED
}

public enum ReturnDisposition { RESTOCKED, DISPOSED, REJECTED }
```

Map tables explicitly as `customer_return` and `customer_return_item`; use `EnumType.STRING`, `UUID requestKey`, nullable `Long rmaId`, `LocalDateTime` timestamps, and cascade+orphan removal from header to items. `CustomerReturnItem.applyResult` must enforce the approved quantity/disposition matrix.

- [ ] **Step 4: Add repository APIs and order-row lock**

`OrderRepository`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select o from Order o where o.id = :orderId")
Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);
```

`CustomerReturnRepository` must provide entity-graph queries for `items`, `items.orderItem`, `items.orderItem.product`, and `order`:

```java
@EntityGraph(attributePaths = {"order", "items", "items.orderItem", "items.orderItem.product"})
@Query("select distinct r from CustomerReturn r where r.id = :id")
Optional<CustomerReturn> findDetailedById(Long id);

@EntityGraph(attributePaths = {"order", "items", "items.orderItem", "items.orderItem.product"})
@Query("select distinct r from CustomerReturn r where r.requestKey = :requestKey")
Optional<CustomerReturn> findDetailedByRequestKey(UUID requestKey);

@EntityGraph(attributePaths = {"order", "items", "items.orderItem", "items.orderItem.product"})
@Query("select distinct r from CustomerReturn r where r.order.id = :orderId order by r.id desc")
List<CustomerReturn> findDetailedByOrderId(Long orderId);

@EntityGraph(attributePaths = {"order", "items", "items.orderItem", "items.orderItem.product"})
@Query("select distinct r from CustomerReturn r where r.status in :statuses order by r.id")
List<CustomerReturn> findDetailedByStatusIn(Collection<CustomerReturnStatus> statuses);
```

- [ ] **Step 5: Add V5 migration**

Create both Hibernate sequences with `INCREMENT BY 50`, both tables, FK constraints to `orders` and `order_item`, UNIQUE constraints for `request_key`, nullable `rma_id`, and `(customer_return_id, order_item_id)`. Store enum fields as `VARCHAR(30)` and UUID as PostgreSQL `UUID`:

```sql
CREATE SEQUENCE IF NOT EXISTS customer_return_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS customer_return_item_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE customer_return (
    customer_return_id BIGINT NOT NULL DEFAULT nextval('customer_return_seq'),
    order_id BIGINT NOT NULL,
    request_key UUID NOT NULL,
    rma_id BIGINT,
    status VARCHAR(30) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    failure_reason VARCHAR(100),
    requested_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT pk_customer_return PRIMARY KEY (customer_return_id),
    CONSTRAINT fk_customer_return_order FOREIGN KEY (order_id) REFERENCES orders(order_id),
    CONSTRAINT uq_customer_return_request_key UNIQUE (request_key),
    CONSTRAINT uq_customer_return_rma_id UNIQUE (rma_id)
);

CREATE TABLE customer_return_item (
    customer_return_item_id BIGINT NOT NULL DEFAULT nextval('customer_return_item_seq'),
    customer_return_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    requested_quantity INTEGER NOT NULL,
    accepted_quantity INTEGER,
    disposition VARCHAR(30),
    CONSTRAINT pk_customer_return_item PRIMARY KEY (customer_return_item_id),
    CONSTRAINT fk_customer_return_item_return FOREIGN KEY (customer_return_id)
        REFERENCES customer_return(customer_return_id),
    CONSTRAINT fk_customer_return_item_order_item FOREIGN KEY (order_item_id)
        REFERENCES order_item(order_item_id),
    CONSTRAINT uq_customer_return_order_item UNIQUE (customer_return_id, order_item_id),
    CONSTRAINT ck_customer_return_requested_quantity CHECK (requested_quantity > 0),
    CONSTRAINT ck_customer_return_accepted_quantity CHECK
        (accepted_quantity IS NULL OR accepted_quantity >= 0)
);

UPDATE delivery SET status = 'SHIPPED' WHERE status = 'COMP';
```

- [ ] **Step 6: Run domain and repository tests**

Run:

```bash
./gradlew test --tests "com.jhg.hgpage.domain.CustomerReturnTest" \
  --tests "com.jhg.hgpage.repository.CustomerReturnRepositoryTest" \
  --tests "com.jhg.hgpage.domain.DeliveryStatusMappingTest"
```

Expected: PASS with enums stored as strings and detailed repository reads usable with `open-in-view:false`.

- [ ] **Step 7: Commit the persistence foundation**

```bash
git add src/main/java/com/jhg/hgpage/oms/domain/enums/CustomerReturnStatus.java src/main/java/com/jhg/hgpage/oms/domain/enums/ReturnDisposition.java src/main/java/com/jhg/hgpage/oms/domain/CustomerReturn.java src/main/java/com/jhg/hgpage/oms/domain/CustomerReturnItem.java src/main/java/com/jhg/hgpage/oms/repository/CustomerReturnRepository.java src/main/java/com/jhg/hgpage/oms/repository/OrderRepository.java src/main/resources/db/migration/V5__add_customer_returns.sql src/test/java/com/jhg/hgpage/domain/CustomerReturnTest.java src/test/java/com/jhg/hgpage/repository/CustomerReturnRepositoryTest.java
git commit -m "feat(oms): add customer return domain"
```

---

### Task 3: Persist and validate customer return requests

**Files:**
- Create: `src/main/java/com/jhg/hgpage/oms/service/CustomerReturnService.java`
- Test: `src/test/java/com/jhg/hgpage/service/CustomerReturnServiceTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/CustomerReturnConcurrencyTest.java`

**Interfaces:**
- Consumes: Task 2 entities and repository lock.
- Produces: `Long request(Long orderId, Long memberId, String reason, List<ReturnLine> lines)`, `Submission pendingSubmission(Long returnId)`, state mutation methods, and owned reads.

- [ ] **Step 1: Write failing service tests for trust-boundary validation**

Test all of these independently:

```text
DELIVERED 주문만 허용
타인 주문은 EntityNotFoundException
reason trim 후 빈 값/500자 초과 거절
품목 없음, 주문에 없는 orderItemId, 0 이하 수량 거절
같은 orderItemId 중복 거절
COMPLETED는 acceptedQuantity, 활성 상태는 requestedQuantity로 누적
CANCELLED와 SUBMISSION_FAILED는 누적 제외
```

The happy-path assertion must verify a UUID is generated once and status is `PENDING_SUBMISSION`.

- [ ] **Step 2: Run service tests and verify failure**

Run: `./gradlew test --tests "com.jhg.hgpage.service.CustomerReturnServiceTest"`

Expected: FAIL because `CustomerReturnService` is missing.

- [ ] **Step 3: Implement request validation under the order lock**

Use these records:

```java
public record ReturnLine(Long orderItemId, int quantity) {}
public record Submission(Long returnId, UUID requestKey, Long orderId, String reason, List<SubmissionItem> items) {}
public record SubmissionItem(Long orderItemId, Long productId, int quantity) {}
```

`request` must load `findByIdForUpdate`, hide ownership mismatch as 404, require `DELIVERED`, normalize lines to one entry per `orderItemId`, load existing returns for the order, calculate each line's remaining quantity, save `PENDING_SUBMISSION`, and return its local ID.

- [ ] **Step 4: Add transactional mutation methods used by later tasks**

Expose exact operations without HTTP calls:

```java
@Transactional(readOnly = true) Submission pendingSubmission(Long returnId);
@Transactional void markRequested(Long returnId, Long rmaId);
@Transactional void markSubmissionFailed(Long returnId, String failureCode);
@Transactional(readOnly = true) CustomerReturn findOwned(Long returnId, Long memberId);
@Transactional(readOnly = true) List<CustomerReturn> findForOwnedOrder(Long orderId, Long memberId);
@Transactional(readOnly = true) List<Long> pendingSubmissionIds();
@Transactional(readOnly = true) List<ActiveReturn> activeReturns();
```

`ActiveReturn` is `record ActiveReturn(Long returnId, Long rmaId) {}` and contains only
`REQUESTED`/`RECEIVED` rows with a non-null `rmaId`.

`markRequested` must bind a null `rmaId`, require equality when already bound, and never regress a later state to `REQUESTED`.

- [ ] **Step 5: Add one runnable concurrent oversubscription check**

In `CustomerReturnConcurrencyTest`, persist a delivered one-unit order, start two executor tasks requesting quantity 1 for the same order item, synchronize their start with a `CountDownLatch`, and assert exactly one succeeds. Then assert stored active requested quantity is 1.

- [ ] **Step 6: Run service and concurrency tests**

Run:

```bash
./gradlew test --tests "com.jhg.hgpage.service.CustomerReturnServiceTest" \
  --tests "com.jhg.hgpage.service.CustomerReturnConcurrencyTest"
```

Expected: PASS; no oversubscription under concurrent requests.

- [ ] **Step 7: Commit request persistence**

```bash
git add src/main/java/com/jhg/hgpage/oms/service/CustomerReturnService.java src/test/java/com/jhg/hgpage/service/CustomerReturnServiceTest.java src/test/java/com/jhg/hgpage/service/CustomerReturnConcurrencyTest.java
git commit -m "feat(oms): validate customer return requests"
```

---

### Task 4: Implement the WMS return port

**Files:**
- Create: `src/main/java/com/jhg/hgpage/contract/ReturnPort.java`
- Create: `src/main/java/com/jhg/hgpage/wms/adapter/WmsReturnAdapter.java`
- Test: `src/test/java/com/jhg/hgpage/adapter/WmsReturnAdapterTest.java`

**Interfaces:**
- Consumes: existing `wms.base-url`, `wms.basic.user/password`, Spring Boot `RestClient.Builder` timeouts.
- Produces: `ReturnPort.create(CreateRequest)` and `ReturnPort.find(Long)` returning the same `ReturnResult` shape.

- [ ] **Step 1: Write contract tests against the real WMS JSON shape**

Use `MockRestServiceServer` to verify:

```json
{"requestKey":"00000000-0000-0000-0000-000000000001","orderId":100,"reason":"불량","items":[{"orderItemId":501,"productId":1,"quantity":2}]}
```

and parse:

```json
{"rmaId":30,"requestKey":"00000000-0000-0000-0000-000000000001","orderId":100,"status":"COMPLETED","items":[{"orderItemId":501,"productId":1,"requestedQuantity":2,"acceptedQuantity":1,"disposition":"RESTOCKED"}]}
```

Cover POST `201`, POST `200`, POST `400`, POST `409`, `401/403`, 5xx/transport failure, GET success, and GET 404.

- [ ] **Step 2: Run the adapter test and verify missing classes fail compilation**

Run: `./gradlew test --tests "com.jhg.hgpage.adapter.WmsReturnAdapterTest"`

Expected: FAIL because the port and adapter do not exist.

- [ ] **Step 3: Define the neutral port records**

`ReturnPort` must define:

```java
ReturnResult create(CreateRequest request);
ReturnResult find(Long rmaId);

record CreateRequest(UUID requestKey, Long orderId, String reason, List<CreateItem> items) {}
record CreateItem(Long orderItemId, Long productId, int quantity) {}
record ReturnResult(Long rmaId, UUID requestKey, Long orderId, String status, List<ResultItem> items) {}
record ResultItem(Long orderItemId, Long productId, int requestedQuantity,
                  int acceptedQuantity, String disposition) {}
```

Define these exact nested unchecked exceptions in `ReturnPort`; constructors accept only safe codes/IDs and never a WMS response body:

```java
final class PermanentReturnRejection extends RuntimeException {
    private final String code;
    public PermanentReturnRejection(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
final class ReturnAuthenticationFailure extends RuntimeException {}
final class TransientReturnFailure extends RuntimeException {
    public TransientReturnFailure(Throwable cause) { super(cause); }
}
final class RemoteReturnNotFound extends RuntimeException {
    public RemoteReturnNotFound(Long rmaId) { super("RMA not found: " + rmaId); }
}
```

Map POST `400` to code `BAD_REQUEST`, POST `409` to `CONFLICT`, `401/403` to `ReturnAuthenticationFailure`, network/5xx to `TransientReturnFailure`, and GET `404` to `RemoteReturnNotFound`.

- [ ] **Step 4: Implement `WmsReturnAdapter` without hidden retries**

Build one `RestClient` with existing WMS Basic credentials. POST to `/api/returns`, GET `/api/returns/{rmaId}`, return deserialized records, and map HTTP/network failures to the four categories from Step 3. Do not catch and retry inside the adapter.

- [ ] **Step 5: Run adapter tests**

Run: `./gradlew test --tests "com.jhg.hgpage.adapter.WmsReturnAdapterTest"`

Expected: PASS and `server.verify()` confirms exactly one HTTP call per adapter invocation.

- [ ] **Step 6: Commit the WMS adapter**

```bash
git add src/main/java/com/jhg/hgpage/contract/ReturnPort.java src/main/java/com/jhg/hgpage/wms/adapter/WmsReturnAdapter.java src/test/java/com/jhg/hgpage/adapter/WmsReturnAdapterTest.java
git commit -m "feat(oms): add WMS return adapter"
```

---

### Task 5: Add durable submission and reconciliation

**Files:**
- Create: `src/main/java/com/jhg/hgpage/oms/service/ReturnSubmissionService.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/ReturnSyncService.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/ReturnReconciliationSweeper.java`
- Test: `src/test/java/com/jhg/hgpage/service/ReturnSubmissionServiceTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/ReturnSyncServiceTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/ReturnReconciliationSweeperTest.java`

**Interfaces:**
- Consumes: `CustomerReturnService` transaction methods and `ReturnPort`.
- Produces: `submit(Long returnId)`, `apply(ReturnResult result)`, and scheduled `sweep()`.

- [ ] **Step 1: Write failing submission outcome tests**

Verify:

```text
success → bind rmaId and REQUESTED
same request retried → same rmaId accepted
400/409 → SUBMISSION_FAILED
network/5xx/401/403 → PENDING_SUBMISSION unchanged
callback advanced state before POST response → no regression to REQUESTED
```

- [ ] **Step 2: Implement `ReturnSubmissionService` outside a DB transaction**

The method must have no `@Transactional` annotation:

```java
public void submit(Long returnId) {
    CustomerReturnService.Submission submission = customerReturnService.pendingSubmission(returnId);
    try {
        ReturnResult result = returnPort.create(toRequest(submission));
        customerReturnService.markRequested(returnId, result.rmaId());
    } catch (PermanentReturnRejection e) {
        customerReturnService.markSubmissionFailed(returnId, e.code());
    } catch (TransientReturnFailure | ReturnAuthenticationFailure e) {
        log.warn("RMA 접수 보류: returnId={}, orderId={}", returnId, submission.orderId());
    }
}
```

Do not log `reason` or the remote body.

- [ ] **Step 3: Write and implement result validation tests**

`ReturnSyncService.apply` is `@Transactional` and must verify request key, bound-or-null `rmaId`, order ID, exact order-item set, each product ID, requested quantity, accepted quantity, disposition matrix, and legal forward transition. A duplicate is a no-op. Define `ReturnSyncService.ReturnContractMismatchException` as the single mismatch type used by both callback and sweeper. Parse only WMS statuses `REQUESTED`, `RECEIVED`, `COMPLETED`, `CANCELLED`; every other string is a contract mismatch. Apply item results only for `COMPLETED`; require `acceptedQuantity=0` and null disposition for `CANCELLED`; ignore WMS's transport default `acceptedQuantity=0` while `REQUESTED` or `RECEIVED`.

- [ ] **Step 4: Write failing sweeper tests**

Assert an empty repository makes zero WMS calls. Assert pending IDs call `submit`. Assert `REQUESTED/RECEIVED` with `rmaId` call `find` then `apply`. Network, auth, 404, and contract mismatch must be logged and leave state unchanged so one bad RMA does not stop the remaining sweep.

- [ ] **Step 5: Implement the scheduled sweeper**

Use the existing scheduling configuration:

```java
@Scheduled(fixedDelayString = "${returns.sweep-delay:60s}",
           initialDelayString = "${returns.sweep-delay:60s}")
public void sweep() {
    for (Long returnId : customerReturnService.pendingSubmissionIds()) {
        try {
            returnSubmissionService.submit(returnId);
        } catch (RuntimeException e) {
            log.warn("RMA 접수 스윕 실패: returnId={}", returnId, e);
        }
    }
    for (CustomerReturnService.ActiveReturn active : customerReturnService.activeReturns()) {
        try {
            returnSyncService.apply(returnPort.find(active.rmaId()));
        } catch (RuntimeException e) {
            log.warn("RMA 상태 스윕 실패: returnId={}, rmaId={}",
                    active.returnId(), active.rmaId(), e);
        }
    }
}
```

Repository reads should return IDs/snapshots before HTTP calls; never keep an entity transaction open during WMS requests.

- [ ] **Step 6: Run submission, sync, and sweeper tests**

Run:

```bash
./gradlew test --tests "com.jhg.hgpage.service.ReturnSubmissionServiceTest" \
  --tests "com.jhg.hgpage.service.ReturnSyncServiceTest" \
  --tests "com.jhg.hgpage.service.ReturnReconciliationSweeperTest"
```

Expected: PASS, including POST-response/callback race and per-RMA error isolation.

- [ ] **Step 7: Commit recovery services**

```bash
git add src/main/java/com/jhg/hgpage/oms/service/ReturnSubmissionService.java src/main/java/com/jhg/hgpage/oms/service/ReturnSyncService.java src/main/java/com/jhg/hgpage/oms/service/ReturnReconciliationSweeper.java src/test/java/com/jhg/hgpage/service/ReturnSubmissionServiceTest.java src/test/java/com/jhg/hgpage/service/ReturnSyncServiceTest.java src/test/java/com/jhg/hgpage/service/ReturnReconciliationSweeperTest.java
git commit -m "feat(oms): reconcile WMS return state"
```

---

### Task 6: Receive authenticated WMS return callbacks

**Files:**
- Create: `src/main/java/com/jhg/hgpage/oms/web/api/ReturnStatusApiController.java`
- Modify: `src/main/java/com/jhg/hgpage/config/SecurityConfig.java`
- Test: `src/test/java/com/jhg/hgpage/controller/api/ReturnStatusApiControllerMvcTest.java`
- Test: `src/test/java/com/jhg/hgpage/controller/api/ReturnCallbackHttpTest.java`

**Interfaces:**
- Consumes: Task 4 `ReturnResult` JSON shape and Task 5 `ReturnSyncService.apply`.
- Produces: authenticated `POST /api/return-status-events` returning `200`, `400`, or `409`.

- [ ] **Step 1: Write callback MVC tests**

Use the exact WMS payload shape. Verify valid `COMPLETED` and `CANCELLED` delegate once and return `200`; malformed payload returns `400`; `ReturnContractMismatchException` returns `409`; duplicate valid callback still returns `200`.

- [ ] **Step 2: Run the MVC test and verify failure**

Run: `./gradlew test --tests "com.jhg.hgpage.controller.api.ReturnStatusApiControllerMvcTest"`

Expected: FAIL with no callback mapping.

- [ ] **Step 3: Implement the thin callback controller**

Accept a record matching `ReturnPort.ReturnResult`, reject null IDs/status/items as `400`, call `returnSyncService.apply`, and map only contract mismatch to `409`. Let unexpected errors remain 5xx rather than returning a false success.

- [ ] **Step 4: Expand the existing Basic-auth security chain**

Replace the single matcher with:

```java
http.securityMatcher("/api/replenishments", "/api/return-status-events")
```

Keep the same `oms.callback.user/password`, CSRF disablement, Basic authentication provider, and 401 behavior.

- [ ] **Step 5: Add real-servlet authentication verification**

Mirror `ReplenishmentCallbackHttpTest`: send a wrong Basic header to `/api/return-status-events`, assert `401`, and assert there is no `Location` redirect header.

- [ ] **Step 6: Run callback and security tests**

Run:

```bash
./gradlew test --tests "com.jhg.hgpage.controller.api.ReturnStatusApiControllerMvcTest" \
  --tests "com.jhg.hgpage.controller.api.ReturnCallbackHttpTest" \
  --tests "com.jhg.hgpage.controller.api.ReplenishmentCallbackHttpTest"
```

Expected: PASS; the existing replenishment callback remains authenticated.

- [ ] **Step 7: Commit callback handling**

```bash
git add src/main/java/com/jhg/hgpage/oms/web/api/ReturnStatusApiController.java src/main/java/com/jhg/hgpage/config/SecurityConfig.java src/test/java/com/jhg/hgpage/controller/api
git commit -m "feat(oms): receive WMS return callbacks"
```

---

### Task 7: Add the customer Thymeleaf return flow

**Files:**
- Create: `src/main/java/com/jhg/hgpage/oms/web/form/CustomerReturnForm.java`
- Create: `src/main/java/com/jhg/hgpage/oms/dto/CustomerReturnDto.java`
- Create: `src/main/java/com/jhg/hgpage/oms/web/controller/CustomerReturnController.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/web/controller/OrderController.java`
- Modify: `src/main/resources/templates/orderview.html`
- Create: `src/main/resources/templates/returnview.html`
- Test: `src/test/java/com/jhg/hgpage/controller/order/CustomerReturnControllerMvcTest.java`
- Test: `src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java`

**Interfaces:**
- Consumes: `CustomerReturnService.request`, `ReturnSubmissionService.submit`, owned return reads, and Task 1 order line IDs.
- Produces: `POST /orders/{orderId}/returns`, `GET /returns/{returnId}`, order-detail return summary.

- [ ] **Step 1: Write MVC tests for the complete customer flow**

Cover:

```text
DELIVERED order renders per-line remaining quantities and return form
READY/SHIPPED order does not render form
multi-item POST delegates normalized positive lines
empty selection and invalid reason return inline validation errors
successful POST invokes submit only after request returns a local ID, then redirects
invalid POST stores form and binding errors as flash attributes, then redirects
owned return detail renders results
another member receives 404
POST without CSRF receives 403
```

- [ ] **Step 2: Run MVC tests and verify failure**

Run:

```bash
./gradlew test --tests "com.jhg.hgpage.controller.order.CustomerReturnControllerMvcTest" \
  --tests "com.jhg.hgpage.controller.order.OrderControllerMvcTest"
```

Expected: FAIL because form, controller, DTO, and template do not exist.

- [ ] **Step 3: Implement the form and display DTO**

The form uses Bean Validation for `@NotBlank @Size(max=500) reason`; item quantities use `@Min(0)`. The controller removes zero-quantity rows before calling the service and rejects an empty normalized list. On validation failure, store both `returnForm` and `org.springframework.validation.BindingResult.returnForm` as flash attributes and redirect to `/orders/{orderId}` so all POST outcomes follow PRG while the GET renders inline errors.

`CustomerReturnDto` must expose Korean labels without embedding state conditionals throughout the template:

```text
PENDING_SUBMISSION → WMS 전송 중
SUBMISSION_FAILED  → 접수 실패
REQUESTED          → 반품 접수
RECEIVED           → 창고 입고
COMPLETED          → 검수 완료
CANCELLED          → 반품 취소
```

Disposition labels are `재입고`, `폐기`, `거절`.

- [ ] **Step 4: Implement controller transaction ordering**

The POST sequence is exactly:

```java
Long returnId = customerReturnService.request(orderId, user.getId(), form.getReason(), lines);
returnSubmissionService.submit(returnId);
return "redirect:/orders/" + orderId;
```

Because `CustomerReturnService` is a separate proxied bean, its transaction commits before the WMS call. Catch validation failures for inline form rendering; do not convert transient WMS failures into lost customer requests.

- [ ] **Step 5: Update Thymeleaf views with accessible native controls**

Use `<input type="number" min="0" th:attr="max=${item.returnableQuantity}">`, a labeled `<textarea maxlength="500">`, CSRF hidden input, status text in addition to color, and a confirmation message. Keep the existing responsive layout; the item table may scroll inside its block but the page must not overflow horizontally.

- [ ] **Step 6: Run MVC and template contract tests**

Run:

```bash
./gradlew test --tests "com.jhg.hgpage.controller.order.CustomerReturnControllerMvcTest" \
  --tests "com.jhg.hgpage.controller.order.OrderControllerMvcTest" \
  --tests "com.jhg.hgpage.template.ResponsiveTemplateContractTest"
```

Expected: PASS with existing order cancellation and responsive contracts intact.

- [ ] **Step 7: Commit the customer return UI**

```bash
git add src/main/java/com/jhg/hgpage/oms/web/form/CustomerReturnForm.java src/main/java/com/jhg/hgpage/oms/dto/CustomerReturnDto.java src/main/java/com/jhg/hgpage/oms/web/controller/CustomerReturnController.java src/main/java/com/jhg/hgpage/oms/web/controller/OrderController.java src/main/resources/templates/orderview.html src/main/resources/templates/returnview.html src/test/java/com/jhg/hgpage/controller/order/CustomerReturnControllerMvcTest.java src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java
git commit -m "feat(oms): add customer return flow"
```

---

### Task 8: Align documentation and verify the integrated workflow

**Files:**
- Modify: `README.md`
- Modify: `src/main/java/com/jhg/hgpage/oms/README.md`
- Modify: `docs/manual-verification-scenarios.md`
- Test: all OMS tests

**Interfaces:**
- Consumes: completed Tasks 1–7 and a running WMS implementation matching the approved contract.
- Produces: documented V2 behavior, full automated verification, and repeatable manual recovery evidence.

- [ ] **Step 1: Update current-state documentation**

Replace `COMP` and `/admin/orders/complete-delivery` documentation with `READY → SHIPPED → DELIVERED`, `/admin/orders/ship`, and `/admin/orders/deliver`. Document customer return ownership, WMS RMA ownership, callback authentication, `returns.sweep-delay`, and explicitly state that refunds are the next phase.

- [ ] **Step 2: Add manual verification scenarios**

Record exact scenarios:

```text
single-line full approval RESTOCKED
multi-line partial approval with one REJECTED line
DISPOSED approval without OMS inventory ownership
REQUESTED cancellation reflected in OMS
same requestKey retry returns the same rmaId
WMS unavailable during customer submission then sweeper recovery
OMS unavailable during WMS completion then GET recovery
tampered callback item/product/quantity rejected with 409
wrong callback Basic credentials rejected with 401
```

- [ ] **Step 3: Run targeted RMA tests without cache reuse**

Run:

```bash
./gradlew test --rerun-tasks --tests "*CustomerReturn*" \
  --tests "*ReturnSubmission*" --tests "*ReturnSync*" \
  --tests "*ReturnReconciliation*" --tests "*WmsReturnAdapter*" \
  --tests "*ReturnStatusApiController*" --tests "*ReturnCallbackHttpTest"
```

Expected: all selected tests PASS.

- [ ] **Step 4: Run the full OMS suite without cache reuse**

Run: `./gradlew test --rerun-tasks`

Expected: BUILD SUCCESSFUL with zero failed tests. Record the executed test count in README only if the README continues to publish a count.

- [ ] **Step 5: Run local OMS/WMS smoke verification**

Start WMS on 8081 and OMS on 8080 with matching Basic credentials. Execute the manual scenarios from Step 2, including stopping OMS before WMS completion and verifying the state converges after restart and the next sweep.

- [ ] **Step 6: Inspect the final diff and repository state**

Run:

```bash
git diff --check
git status --short
git diff --stat HEAD
```

Expected: no whitespace errors; only intended OMS files plus the pre-existing untracked `.DS_Store` appear.

- [ ] **Step 7: Commit documentation and verification updates**

```bash
git add README.md src/main/java/com/jhg/hgpage/oms/README.md docs/manual-verification-scenarios.md
git commit -m "docs(oms): document RMA workflow"
```

---

## Final Acceptance Criteria

- A customer can request one or more delivered order lines by quantity from the Thymeleaf order detail.
- OMS commits the request before calling WMS and preserves it through network, WMS, and process failures.
- Repeated submission uses the same UUID and converges on one WMS `rmaId`.
- OMS prevents per-order-item oversubscription under concurrent requests.
- WMS `COMPLETED` and `CANCELLED` results are validated, idempotent, and visible to the owning customer.
- Callback loss is recovered by single-RMA polling; WMS failure does not block unrelated sweep items.
- Existing replenishment callback security and order/backorder behavior remain passing.
- `COMP` is absent from runtime code and existing production values migrate to `SHIPPED`.
- No payment/refund placeholder, new dependency, broker, outbox, or mobile work is introduced.
- Full OMS tests pass with `--rerun-tasks`, followed by the documented local OMS/WMS recovery smoke test.
