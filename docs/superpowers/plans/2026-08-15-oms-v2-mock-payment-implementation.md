# OMS V2 Mock Payment and Async Refund Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add recoverable mock payment approval, paid-order inventory allocation, full cancellation refunds, and accepted-return partial refunds to OMS V2.

**Architecture:** Keep the existing OMS ownership boundaries and add `PaymentGateway` as the replaceable PG port. `PaymentFacade` coordinates short transactional state services; gateway and WMS calls happen between transactions, while scheduled workers recover persisted payment, allocation, cancellation, and refund work after failures or restarts.

**Tech Stack:** Java 17, Spring Boot 3.5.5, Spring MVC, Spring Data JPA/Hibernate, Thymeleaf, H2, PostgreSQL/Flyway, JUnit 5, AssertJ, Mockito, MockMvc

**Spec:** `docs/superpowers/specs/2026-08-15-oms-v2-mock-payment-design.md`

## Global Constraints

- V2 uses a mock card gateway only; actual PG APIs, checkout widgets, webhooks, coupons, and points remain V3 scope.
- Never collect or persist card number, card password, CVC, or payment-token data.
- Charge before WMS allocation, including backorders; do not call WMS until payment approval succeeds.
- Use order-time `OrderItem.orderPrice * acceptedQuantity` for return refunds.
- Count `RESTOCKED` and `DISPOSED` accepted quantities; refund nothing for `REJECTED` or zero accepted quantity.
- Gateway and WMS calls must not run inside the transaction that persists their initial work item.
- Automatic retries reuse the original idempotency key and run after 1 minute, 5 minutes, 30 minutes, and 2 hours; after the initial attempt plus four retries, move to manual review.
- Customer payment retry creates a new `PaymentAttempt` and a new idempotency key.
- Admins may retry manual-review work but may not mark payment or refund success without a gateway result.
- Preserve customer order ownership checks, admin role checks, CSRF, and 404 behavior for another customer's order.
- Do not backfill fake payments for legacy orders. Reset OMS and WMS development databases together after implementation.
- Legacy orders without a `Payment` must remain readable as `결제 이력 없음`; their existing cancellation/return behavior must not dereference a missing payment or create a refund without payment evidence.
- Before every commit, stage only the exact files changed by that task and verify `git diff --cached --name-only`; do not stage pre-existing dirty OMS/RMA files.

## File Map

**Payment domain and persistence**

- Create `src/main/java/com/jhg/hgpage/oms/domain/Payment.java`: one payment aggregate per order and refund amount invariants.
- Create `src/main/java/com/jhg/hgpage/oms/domain/PaymentAttempt.java`: one customer approval attempt and its automatic retry state.
- Create `src/main/java/com/jhg/hgpage/oms/domain/RefundRequest.java`: persisted asynchronous refund work.
- Create payment/refund enums under `src/main/java/com/jhg/hgpage/oms/domain/enums/`.
- Create `PaymentRepository`, `PaymentAttemptRepository`, and `RefundRequestRepository` under `src/main/java/com/jhg/hgpage/oms/repository/`.
- Modify `Order.java` and `OrderStatus.java`: payment/allocation/cancellation states and persisted recovery fields.
- Create `src/main/resources/db/migration/V6__add_payments_and_order_processing.sql`: PostgreSQL schema, sequences, constraints, and indexes.

**Ports and orchestration**

- Create `src/main/java/com/jhg/hgpage/contract/PaymentGateway.java`: approval/refund port with typed results.
- Create `src/main/java/com/jhg/hgpage/payment/adapter/MockPaymentGateway.java`: default V2 adapter.
- Create `PaymentFacade.java`: controller/return-sync use-case entry point.
- Create `CheckoutService.java`, `PaymentService.java`, `PaymentApprovalProcessor.java`, and `PaymentApprovalSweeper.java`: order/payment creation and approval recovery.
- Modify `OrderAllocationService.java`; create `AllocationProcessor.java` and `AllocationSweeper.java`: persisted WMS allocation work.
- Create `OrderCancellationService.java`, `CancellationProcessor.java`, and `CancellationSweeper.java`: recoverable cancellation and reservation release.
- Create `RefundService.java`, `RefundProcessor.java`, `RefundSweeper.java`, and `RetrySchedule.java`: refund reservation, execution, and retry policy.
- Modify `WmsInventoryAdapter.java`: preserve exhausted transport/5xx failures as exceptions instead of converting them to stock shortage.

**Web and read models**

- Modify `OrderController.java`: checkout/cancel through the facade and customer payment retry endpoint.
- Create `PaymentViewDto.java` and `AdminPaymentDto.java`; extend `OrderDto.java`, `OrderDetailDto.java`, and `AdminOrderDto.java` with payment/processing labels.
- Modify `orders.html`, `orderview.html`, `orderdetail.html`, and `app.css`: customer payment/refund state.
- Create `PaymentAdminController.java` and `templates/admin/payments.html`; modify `fragments/layout.html` and `admin/orders.html` for admin review and allocation retry.
- Modify `ReturnSyncService.java`: create the partial refund in the same transaction that accepts a completed WMS result.

**Verification and documentation**

- Add focused domain, service, repository, scheduler, concurrency, adapter, and MockMvc tests alongside existing test packages.
- Modify `application.yml`, `initDb.java`, `README.md`, and `docs/manual-verification-scenarios.md`.

## Execution Preflight

The worktree already contains intentional, uncommitted RMA/customer-screen changes in files that later payment tasks also modify. Before Task 1, run `git status --short` and `./gradlew test --rerun-tasks`, then either commit that verified RMA baseline separately with the user's approval or preserve its exact diff and exclude it from every payment commit. Never use checkout/reset to remove it, and never hide mixed changes in a payment commit.

---

### Task 1: Persist Payment, Attempt, Refund, and Order Processing State

**Files:**
- Create: `src/main/java/com/jhg/hgpage/oms/domain/Payment.java`
- Create: `src/main/java/com/jhg/hgpage/oms/domain/PaymentAttempt.java`
- Create: `src/main/java/com/jhg/hgpage/oms/domain/RefundRequest.java`
- Create: `src/main/java/com/jhg/hgpage/oms/domain/enums/PaymentStatus.java`
- Create: `src/main/java/com/jhg/hgpage/oms/domain/enums/PaymentAttemptStatus.java`
- Create: `src/main/java/com/jhg/hgpage/oms/domain/enums/RefundStatus.java`
- Create: `src/main/java/com/jhg/hgpage/oms/domain/enums/RefundSourceType.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/domain/Order.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/domain/enums/OrderStatus.java`
- Create: `src/main/java/com/jhg/hgpage/oms/repository/PaymentRepository.java`
- Create: `src/main/java/com/jhg/hgpage/oms/repository/PaymentAttemptRepository.java`
- Create: `src/main/java/com/jhg/hgpage/oms/repository/RefundRequestRepository.java`
- Create: `src/main/resources/db/migration/V6__add_payments_and_order_processing.sql`
- Test: `src/test/java/com/jhg/hgpage/domain/PaymentTest.java`
- Test: `src/test/java/com/jhg/hgpage/domain/PaymentAttemptTest.java`
- Test: `src/test/java/com/jhg/hgpage/domain/RefundRequestTest.java`
- Test: `src/test/java/com/jhg/hgpage/domain/OrderPaymentStateTest.java`
- Test: `src/test/java/com/jhg/hgpage/repository/PaymentRepositoryTest.java`
- Test: `src/test/java/com/jhg/hgpage/PaymentMigrationTest.java`

**Interfaces:**
- Produces: `Payment.create(Order, int)`, `Payment.reserveRefund(int)`, `Payment.completeRefund(int)`, and `Payment.cancelUnpaid()`.
- Produces: `PaymentAttempt.create(Payment, UUID)`, `claim(LocalDateTime)`, `succeed(String, LocalDateTime)`, `fail(String, String, LocalDateTime)`, `retryAt(LocalDateTime, String, String)`, `manualReview(String, String, LocalDateTime)`, and `cancel(LocalDateTime)`.
- Produces: `RefundRequest.create(Payment, UUID, RefundSourceType, Long, int)`, `claim(LocalDateTime)`, `retryAt(LocalDateTime, String, String, LocalDateTime)`, `manualReview(String, String, LocalDateTime)`, and `succeed(LocalDateTime)`.
- Produces: order transitions `markPaymentPending()`, `markPaymentFailed()`, `markPaymentReview()`, `markAllocationPending()`, `claimAllocation(LocalDateTime)`, `retryAllocation(LocalDateTime, String)`, `markAllocationReview(String)`, `requestCancellation(Boolean, LocalDateTime)`, `resolveCancellationRelease(boolean)`, and `finishCancellation()`.

- [ ] **Step 1: Write failing aggregate tests for amount and transition invariants**

```java
@Test
void 환불_예약과_완료는_승인금액을_넘을_수_없다() {
    Payment payment = paidPayment(30_000);

    payment.reserveRefund(20_000);
    assertThatThrownBy(() -> payment.reserveRefund(10_001))
            .isInstanceOf(IllegalStateException.class);

    payment.completeRefund(20_000);
    assertThat(payment.getPendingRefundAmount()).isZero();
    assertThat(payment.getRefundedAmount()).isEqualTo(20_000);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
}

@Test
void 출고는_재고확보_ORDER_상태에서만_허용한다() {
    Order order = order();
    order.markPaymentPending();

    assertThatThrownBy(order::ship)
            .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run the domain tests and verify the new types are missing**

Run: `./gradlew test --tests "com.jhg.hgpage.domain.PaymentTest" --tests "com.jhg.hgpage.domain.PaymentAttemptTest" --tests "com.jhg.hgpage.domain.RefundRequestTest" --tests "com.jhg.hgpage.domain.OrderPaymentStateTest"`

Expected: FAIL at test compilation because the payment aggregates and new order states do not exist.

- [ ] **Step 3: Implement enums and aggregate methods with explicit guards**

```java
public enum PaymentStatus {
    PENDING, PAYMENT_FAILED, PAYMENT_REVIEW, PAID,
    PARTIALLY_REFUNDED, REFUNDED, CANCELLED
}

public enum PaymentAttemptStatus {
    PENDING, PROCESSING, SUCCEEDED, FAILED, MANUAL_REVIEW, CANCELLED
}

public enum RefundStatus {
    PENDING, PROCESSING, RETRYING, SUCCEEDED, MANUAL_REVIEW
}

public enum RefundSourceType { ORDER_CANCEL, RETURN }
```

Implement `Payment.reserveRefund` with:

```java
if (amount <= 0 || refundedAmount + pendingRefundAmount + amount > paidAmount) {
    throw new IllegalStateException("환불 가능 금액을 초과했습니다.");
}
pendingRefundAmount += amount;
```

Use `@Version` on `Payment` and `RefundRequest`, `order_id UNIQUE` on `Payment`, `request_key UNIQUE` on both work tables, and `(source_type, source_id) UNIQUE` on `RefundRequest`. Store money as non-negative `int` KRW values.

- [ ] **Step 4: Add repositories, migration, and persistence tests**

Repository contracts:

```java
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.order.id = :orderId")
    Optional<Payment> findByOrderIdForUpdate(Long orderId);
}

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    Optional<PaymentAttempt> findByRequestKey(UUID requestKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentAttempt a join fetch a.payment p join fetch p.order where a.id = :id")
    Optional<PaymentAttempt> findByIdForUpdate(Long id);
    List<PaymentAttempt> findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderById(
            Collection<PaymentAttemptStatus> statuses, LocalDateTime now);
}

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {
    Optional<RefundRequest> findBySourceTypeAndSourceId(RefundSourceType type, Long sourceId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundRequest r join fetch r.payment p join fetch p.order where r.id = :id")
    Optional<RefundRequest> findByIdForUpdate(Long id);
    List<RefundRequest> findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderById(
            Collection<RefundStatus> statuses, LocalDateTime now);
}
```

`V6` must add three sequences/tables, the four uniqueness rules, non-negative amount checks, retry lookup indexes, and these `orders` columns: `allocation_attempt_count`, `next_allocation_attempt_at`, `allocation_failure_code`, `allocation_processing_at`, nullable `cancellation_release_required`, `cancellation_requested_at`, and `cancellation_processing_at`. The nullable release flag is deliberate: `null` means an in-flight approval/allocation result must resolve before cancellation, `false` means no reservation release, and `true` means release is required. Do not update legacy order rows to fake paid states.

`PaymentMigrationTest` applies V1, V4, and V6 to H2 in PostgreSQL mode and verifies the new tables, columns, unique constraints, and indexes. Keep the existing V1-only `FlywayMigrationTest` unchanged because V2 contains PostgreSQL-only legacy cleanup SQL.

- [ ] **Step 5: Run focused persistence tests**

Run: `./gradlew test --tests "com.jhg.hgpage.domain.*Payment*" --tests "com.jhg.hgpage.domain.RefundRequestTest" --tests "com.jhg.hgpage.repository.PaymentRepositoryTest" --tests "com.jhg.hgpage.PaymentMigrationTest" --tests "com.jhg.hgpage.FlywayMigrationTest"`

Expected: PASS; duplicate order payment, duplicate request key, and duplicate refund source are rejected.

- [ ] **Step 6: Commit the domain foundation**

```bash
git add src/main/java/com/jhg/hgpage/oms/domain/Payment.java src/main/java/com/jhg/hgpage/oms/domain/PaymentAttempt.java src/main/java/com/jhg/hgpage/oms/domain/RefundRequest.java src/main/java/com/jhg/hgpage/oms/domain/Order.java src/main/java/com/jhg/hgpage/oms/domain/enums/PaymentStatus.java src/main/java/com/jhg/hgpage/oms/domain/enums/PaymentAttemptStatus.java src/main/java/com/jhg/hgpage/oms/domain/enums/RefundStatus.java src/main/java/com/jhg/hgpage/oms/domain/enums/RefundSourceType.java src/main/java/com/jhg/hgpage/oms/domain/enums/OrderStatus.java src/main/java/com/jhg/hgpage/oms/repository/PaymentRepository.java src/main/java/com/jhg/hgpage/oms/repository/PaymentAttemptRepository.java src/main/java/com/jhg/hgpage/oms/repository/RefundRequestRepository.java src/main/resources/db/migration/V6__add_payments_and_order_processing.sql src/test/java/com/jhg/hgpage/domain/PaymentTest.java src/test/java/com/jhg/hgpage/domain/PaymentAttemptTest.java src/test/java/com/jhg/hgpage/domain/RefundRequestTest.java src/test/java/com/jhg/hgpage/domain/OrderPaymentStateTest.java src/test/java/com/jhg/hgpage/repository/PaymentRepositoryTest.java src/test/java/com/jhg/hgpage/PaymentMigrationTest.java
git diff --cached --name-only
git commit -m "feat(oms): add payment and refund persistence"
```

### Task 2: Add the Replaceable Mock Payment Gateway

**Files:**
- Create: `src/main/java/com/jhg/hgpage/contract/PaymentGateway.java`
- Create: `src/main/java/com/jhg/hgpage/payment/adapter/MockPaymentGateway.java`
- Test: `src/test/java/com/jhg/hgpage/adapter/MockPaymentGatewayTest.java`

**Interfaces:**
- Produces: `ApprovalResult approve(ApprovalCommand)` and `RefundResult refund(RefundCommand)`.
- Produces: result outcomes `SUCCESS`, `DECLINED`, `RETRYABLE_FAILURE`, `PERMANENT_FAILURE`, and `UNKNOWN`.
- Commands carry only order/payment/refund identifiers, integer KRW amount, and UUID idempotency key.

- [ ] **Step 1: Write the failing adapter contract tests**

```java
@Test
void 기본_모의_승인과_환불은_성공하고_거래번호를_반환한다() {
    MockPaymentGateway gateway = new MockPaymentGateway();

    ApprovalResult approval = gateway.approve(new ApprovalCommand(1L, 10_000, UUID.randomUUID()));
    RefundResult refund = gateway.refund(new RefundCommand(1L, 1L, 5_000, UUID.randomUUID()));

    assertThat(approval.outcome()).isEqualTo(GatewayOutcome.SUCCESS);
    assertThat(approval.transactionId()).isNotBlank();
    assertThat(refund.outcome()).isEqualTo(GatewayOutcome.SUCCESS);
    assertThat(refund.transactionId()).isNotBlank();
}
```

- [ ] **Step 2: Run the adapter test and verify it fails**

Run: `./gradlew test --tests "com.jhg.hgpage.adapter.MockPaymentGatewayTest"`

Expected: FAIL because `PaymentGateway` and `MockPaymentGateway` are absent.

- [ ] **Step 3: Implement the port and deterministic default adapter**

```java
public interface PaymentGateway {
    ApprovalResult approve(ApprovalCommand command);
    RefundResult refund(RefundCommand command);

    record ApprovalCommand(Long orderId, int amount, UUID requestKey) {}
    record RefundCommand(Long paymentId, Long refundId, int amount, UUID requestKey) {}
    record ApprovalResult(GatewayOutcome outcome, String transactionId,
                          String failureCode, String failureReason) {}
    record RefundResult(GatewayOutcome outcome, String transactionId,
                        String failureCode, String failureReason) {}
    enum GatewayOutcome { SUCCESS, DECLINED, RETRYABLE_FAILURE, PERMANENT_FAILURE, UNKNOWN }
}
```

`MockPaymentGateway` validates positive amounts, returns `PERMANENT_FAILURE` for invalid commands, and otherwise returns `SUCCESS` with `MOCK-PAY-<UUID>` or `MOCK-REFUND-<UUID>`. Failure scenarios are injected in service tests by mocking the port; no public failure-control endpoint is added.

- [ ] **Step 4: Run the adapter tests**

Run: `./gradlew test --tests "com.jhg.hgpage.adapter.MockPaymentGatewayTest"`

Expected: PASS.

- [ ] **Step 5: Commit the gateway port**

```bash
git add src/main/java/com/jhg/hgpage/contract/PaymentGateway.java src/main/java/com/jhg/hgpage/payment/adapter/MockPaymentGateway.java src/test/java/com/jhg/hgpage/adapter/MockPaymentGatewayTest.java
git diff --cached --name-only
git commit -m "feat(oms): add mock payment gateway port"
```

### Task 3: Create Orders Through Payment Approval and Recover Approval Retries

**Files:**
- Create: `src/main/java/com/jhg/hgpage/oms/service/CheckoutService.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/PaymentService.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/PaymentApprovalProcessor.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/PaymentApprovalSweeper.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/RetrySchedule.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/PaymentFacade.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/web/controller/OrderController.java`
- Modify: `src/main/resources/templates/orderdetail.html`
- Test: `src/test/java/com/jhg/hgpage/service/CheckoutServiceTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/PaymentApprovalProcessorTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/PaymentApprovalSweeperTest.java`
- Modify: `src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java`

**Interfaces:**
- Consumes: aggregates and `PaymentGateway` from Tasks 1-2.
- Produces: `PaymentFacade.checkout(Long, Address, List<OrderService.OrderLine>, boolean)` and `PaymentFacade.retryPayment(Long, Long)`.
- Produces: `CheckoutService.createPending(Long, Address, List<OrderService.OrderLine>, boolean) -> CheckoutResult(orderId, attemptId)` in one transaction.
- Produces: `PaymentApprovalProcessor.process(Long attemptId)` with gateway invocation outside transaction.
- Produces: `RetrySchedule.nextAttemptAt(int completedAttempts, LocalDateTime now)` returning 1m, 5m, 30m, or 2h and empty after five total attempts.

- [ ] **Step 1: Write failing checkout and approval tests**

```java
@Test
void 주문과_결제시도를_저장하지만_승인전에는_WMS를_호출하지_않는다() {
    CheckoutResult result = checkoutService.createPending(memberId, address, lines, false);

    assertThat(order(result.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    assertThat(payment(result.orderId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(attempt(result.attemptId()).getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
    verifyNoInteractions(inventoryPort);
}

@Test
void 승인성공은_결제완료와_할당대기로_전환한다() {
    when(gateway.approve(any())).thenReturn(success("MOCK-PAY-1"));

    processor.process(attemptId);

    assertThat(payment(orderId).getStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.ALLOCATION_PENDING);
}
```

Also cover explicit `DECLINED -> PAYMENT_FAILED`, retryable/unknown scheduling with the same request key, fifth failure to `PAYMENT_REVIEW`, stale `PROCESSING` recovery, and customer retry creating a new attempt key only from `PAYMENT_FAILED`.

- [ ] **Step 2: Run the new service tests and verify failure**

Run: `./gradlew test --tests "com.jhg.hgpage.service.CheckoutServiceTest" --tests "com.jhg.hgpage.service.PaymentApprovalProcessorTest" --tests "com.jhg.hgpage.service.PaymentApprovalSweeperTest"`

Expected: FAIL because the checkout/payment services are absent.

- [ ] **Step 3: Move order creation into a short checkout transaction**

`CheckoutService.createPending` reuses the current bulk product lookup and order-line construction, calls `order.markPaymentPending()`, saves the order, creates `Payment` with `order.getTotalPrice()`, creates the first `PaymentAttempt`, and clears selected cart items only when `fromCart` is true. It must not inject or call `InventoryPort`.

Keep `OrderService.OrderLine` temporarily as the shared input record, migrate the controller and tests to `PaymentFacade`, then remove the current `OrderService.order` and `orderFromCart` implementations. `CheckoutService` owns product lookup, order persistence, payment creation, and cart cleanup; this avoids an `OrderService -> PaymentFacade -> CheckoutService` dependency cycle and leaves one active checkout path.

- [ ] **Step 4: Implement approval claim/call/complete boundaries**

Use three phases:

```java
public void process(Long attemptId) {
    Optional<ApprovalCommand> claimed = paymentService.claimApproval(attemptId); // transaction commits
    if (claimed.isEmpty()) return;
    ApprovalResult result = paymentGateway.approve(claimed.get());      // no DB transaction
    paymentService.applyApprovalResult(attemptId, result);             // new transaction
}
```

`claimApproval` returns no command when the attempt is no longer due/claimable. `applyApprovalResult` locks the attempt's payment/order and keeps success terminal. For `CANCEL_REQUESTED`, a late success records `PAID` and resolves cancellation with `releaseRequired=false`; Task 5 then creates the full refund. A late decline records `CANCELLED` and finishes the unpaid cancellation without a refund.

- [ ] **Step 5: Add immediate processing, scheduled retry, and controller wiring**

`PaymentFacade.checkout` calls `CheckoutService.createPending`, then `PaymentApprovalProcessor.process`, then returns the order ID. `retryPayment` performs ownership/state checks, persists a new attempt/key, and invokes the same processor. Add:

```java
@PostMapping("/orders/{orderId}/payment/retry")
public String retryPayment(@AuthenticationPrincipal UserPrincipal user,
                           @PathVariable Long orderId,
                           RedirectAttributes redirectAttributes)
```

Use `@Scheduled(fixedDelayString = "${payments.sweep-delay:5s}")` for due/stale attempts. Keep the checkout button label `결제하고 주문하기` and display `모의 카드 결제`; do not add card fields.

- [ ] **Step 6: Run service and MVC tests**

Run: `./gradlew test --tests "com.jhg.hgpage.service.CheckoutServiceTest" --tests "com.jhg.hgpage.service.PaymentApprovalProcessorTest" --tests "com.jhg.hgpage.service.PaymentApprovalSweeperTest" --tests "com.jhg.hgpage.controller.order.OrderControllerMvcTest"`

Expected: PASS; controller tests verify USER, CSRF, ownership delegation, Korean flash messages, and that another user's order remains 404.

- [ ] **Step 7: Commit paid checkout**

```bash
git add src/main/java/com/jhg/hgpage/oms/service/CheckoutService.java src/main/java/com/jhg/hgpage/oms/service/PaymentService.java src/main/java/com/jhg/hgpage/oms/service/PaymentApprovalProcessor.java src/main/java/com/jhg/hgpage/oms/service/PaymentApprovalSweeper.java src/main/java/com/jhg/hgpage/oms/service/RetrySchedule.java src/main/java/com/jhg/hgpage/oms/service/PaymentFacade.java src/main/java/com/jhg/hgpage/oms/service/OrderService.java src/main/java/com/jhg/hgpage/oms/web/controller/OrderController.java src/main/resources/templates/orderdetail.html src/test/java/com/jhg/hgpage/service/CheckoutServiceTest.java src/test/java/com/jhg/hgpage/service/PaymentApprovalProcessorTest.java src/test/java/com/jhg/hgpage/service/PaymentApprovalSweeperTest.java src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java
git diff --cached --name-only
git commit -m "feat(oms): approve mock payment before allocation"
```

### Task 4: Process Paid Inventory Allocation Asynchronously

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/wms/adapter/WmsInventoryAdapter.java`
- Modify: `src/test/java/com/jhg/hgpage/adapter/WmsInventoryAdapterTest.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderAllocationService.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/AllocationProcessor.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/AllocationSweeper.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/BackorderAllocator.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/BackorderSweeper.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/repository/OrderRepository.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/repository/OrderRepositoryQuery.java`
- Test: `src/test/java/com/jhg/hgpage/service/AllocationProcessorTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/AllocationSweeperTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/AllocationClaimConcurrencyTest.java`
- Modify: `src/test/java/com/jhg/hgpage/service/OrderAllocationServiceTest.java`
- Modify: `src/test/java/com/jhg/hgpage/service/BackorderAllocatorTest.java`

**Interfaces:**
- Consumes: `OrderStatus.ALLOCATION_PENDING/PROCESSING/REVIEW` and `RetrySchedule`.
- Produces: `OrderAllocationService.claim(Long) -> Optional<AllocationCommand>` and transactional result methods.
- Produces: `AllocationProcessor.process(Long orderId)` and `AllocationSweeper.sweep()`.
- Preserves: `InventoryPort.reserveAll` returns `false` only for a valid all-or-nothing stock shortage response.

- [ ] **Step 1: Change adapter tests to distinguish shortage from outage**

```java
@Test
void reserve_명시적_false만_재고부족으로_반환한다() {
    server.expect(requestTo("http://wms-test/api/inventory/reserve"))
            .andRespond(withSuccess("false", MediaType.APPLICATION_JSON));
    assertThat(adapter.reserveAll(1L, Map.of(1L, 3))).isFalse();
}

@Test
void reserve_통신재시도까지_실패하면_예외를_유지한다() {
    server.expect(requestTo("http://wms-test/api/inventory/reserve"))
            .andRespond(withException(new ConnectException("refused")));
    server.expect(requestTo("http://wms-test/api/inventory/reserve"))
            .andRespond(withException(new ConnectException("refused")));

    assertThatThrownBy(() -> adapter.reserveAll(1L, Map.of(1L, 3)))
            .isInstanceOf(ResourceAccessException.class);
}
```

- [ ] **Step 2: Run adapter tests and verify the old false fallback fails the new assertion**

Run: `./gradlew test --tests "com.jhg.hgpage.adapter.WmsInventoryAdapterTest"`

Expected: FAIL because exhausted transport/5xx errors currently return `false`.

- [ ] **Step 3: Rethrow the second WMS transport/5xx failure**

Replace the second catch fallback with `throw second;`. Preserve the one immediate retry and existing 4xx propagation.

- [ ] **Step 4: Write failing allocation worker tests**

Cover `true -> ORDER`, explicit `false -> BACKORDERED`, transient exception -> due `ALLOCATION_PENDING`, permanent 4xx -> `ALLOCATION_REVIEW`, fifth transient failure -> review, stale processing recovery, and `CANCEL_REQUESTED` resolution. `AllocationClaimConcurrencyTest` starts two processor transactions for one due order and asserts only one reaches `InventoryPort`. During an allocation/cancel race, successful reservation sets `cancellationReleaseRequired=true`, explicit shortage sets it to `false`, and an unknown transport result keeps it `null` while the same `orderId` allocation is retried.

- [ ] **Step 5: Implement persisted allocation claim and processing**

```java
public void process(Long orderId) {
    Optional<AllocationCommand> claimed = orderAllocationService.claim(orderId);
    if (claimed.isEmpty()) return;
    try {
        boolean reserved = inventoryPort.reserveAll(orderId, claimed.get().quantities());
        orderAllocationService.complete(orderId, reserved);
    } catch (HttpClientErrorException exception) {
        orderAllocationService.manualReview(orderId, "WMS_" + exception.getStatusCode().value());
    } catch (RestClientException exception) {
        orderAllocationService.retryOrReview(orderId, "WMS_UNAVAILABLE");
    }
}
```

Repository due queries must order by `orderDate, id` to preserve FIFO. `BackorderAllocator` should enqueue matching paid backorders as `ALLOCATION_PENDING`; the processor performs the WMS call after that transaction commits. Do not enqueue cancelled, unpaid, shipped, or manual-review orders.

When cancellation arrives during `ALLOCATION_PROCESSING`, keep `CANCEL_REQUESTED` and the nullable release flag unresolved. `AllocationSweeper` must also find cancelled orders with an unresolved allocation result, retry the idempotent reserve call, and set the flag to `true` or `false`. Task 5 consumes that resolved flag and performs any required release and refund.

- [ ] **Step 6: Run allocation, backorder, and adapter tests**

Run: `./gradlew test --tests "com.jhg.hgpage.adapter.WmsInventoryAdapterTest" --tests "com.jhg.hgpage.service.OrderAllocationServiceTest" --tests "com.jhg.hgpage.service.AllocationProcessorTest" --tests "com.jhg.hgpage.service.AllocationSweeperTest" --tests "com.jhg.hgpage.service.AllocationClaimConcurrencyTest" --tests "com.jhg.hgpage.service.BackorderAllocatorTest" --tests "com.jhg.hgpage.service.BackorderSweeperTest"`

Expected: PASS; no WMS outage is misreported as `BACKORDERED`.

- [ ] **Step 7: Commit asynchronous allocation**

```bash
git add src/main/java/com/jhg/hgpage/wms/adapter/WmsInventoryAdapter.java src/main/java/com/jhg/hgpage/oms/service/OrderAllocationService.java src/main/java/com/jhg/hgpage/oms/service/AllocationProcessor.java src/main/java/com/jhg/hgpage/oms/service/AllocationSweeper.java src/main/java/com/jhg/hgpage/oms/service/BackorderAllocator.java src/main/java/com/jhg/hgpage/oms/service/BackorderSweeper.java src/main/java/com/jhg/hgpage/oms/repository/OrderRepository.java src/main/java/com/jhg/hgpage/oms/repository/OrderRepositoryQuery.java src/test/java/com/jhg/hgpage/adapter/WmsInventoryAdapterTest.java src/test/java/com/jhg/hgpage/service/AllocationProcessorTest.java src/test/java/com/jhg/hgpage/service/AllocationSweeperTest.java src/test/java/com/jhg/hgpage/service/AllocationClaimConcurrencyTest.java src/test/java/com/jhg/hgpage/service/OrderAllocationServiceTest.java src/test/java/com/jhg/hgpage/service/BackorderAllocatorTest.java src/test/java/com/jhg/hgpage/service/BackorderSweeperTest.java
git diff --cached --name-only
git commit -m "feat(oms): recover paid inventory allocation"
```

### Task 5: Cancel Paid Orders and Execute Full Refunds Reliably

**Files:**
- Create: `src/main/java/com/jhg/hgpage/oms/service/RefundService.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/RefundProcessor.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/RefundSweeper.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/OrderCancellationService.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/CancellationProcessor.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/CancellationSweeper.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/PaymentFacade.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/web/controller/OrderController.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/repository/OrderRepository.java`
- Test: `src/test/java/com/jhg/hgpage/service/RefundServiceTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/RefundProcessorTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/RefundSweeperTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/OrderCancellationServiceTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/CancellationProcessorTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/PaymentCancellationConcurrencyTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/RefundClaimConcurrencyTest.java`
- Modify: `src/test/java/com/jhg/hgpage/service/OrderServiceCancelTest.java`

**Interfaces:**
- Produces: `RefundService.requestOrderCancellationRefund(Long orderId) -> Optional<Long>`.
- Produces: `RefundService.requestReturnRefund(CustomerReturn) -> Optional<Long>` for Task 6.
- Produces: `RefundProcessor.process(Long refundId)` and `RefundSweeper.sweep()`.
- Produces: `PaymentFacade.cancelOrder(Long orderId, Long memberId)`.
- Produces: recoverable `CANCEL_REQUESTED` processing when WMS reservation release is required.

- [ ] **Step 1: Write failing refund worker tests**

```java
@Test
void 환불요청은_pending을_예약하고_성공시_refunded로_이동한다() {
    Long refundId = refundService.requestOrderCancellationRefund(orderId).orElseThrow();
    assertThat(payment(orderId).getPendingRefundAmount()).isEqualTo(30_000);

    when(gateway.refund(any())).thenReturn(refundSuccess("MOCK-REFUND-1"));
    refundProcessor.process(refundId);

    assertThat(payment(orderId).getPendingRefundAmount()).isZero();
    assertThat(payment(orderId).getRefundedAmount()).isEqualTo(30_000);
    assertThat(refund(refundId).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
}
```

Also test retry intervals, same request key, immediate permanent failure to `MANUAL_REVIEW`, retry exhaustion, idempotent duplicate source, over-refund rejection, stale `PROCESSING` recovery, and two processors racing for one refund with only one gateway call.

- [ ] **Step 2: Run refund tests and verify failure**

Run: `./gradlew test --tests "com.jhg.hgpage.service.RefundServiceTest" --tests "com.jhg.hgpage.service.RefundProcessorTest" --tests "com.jhg.hgpage.service.RefundSweeperTest"`

Expected: FAIL because refund services do not exist.

- [ ] **Step 3: Implement refund reservation and three-phase processing**

`RefundService` locks `Payment`, checks `refunded + pending + new <= paid`, creates one work row per source, and increments pending in the same transaction. `RefundProcessor` follows claim -> gateway call -> result transaction. A manual-review result keeps its amount in `pendingRefundAmount`; only success transfers pending to refunded.

- [ ] **Step 4: Write failing cancellation state tests**

Cover:

```text
PAYMENT_PENDING unclaimed -> CANCEL with no refund
PAYMENT_PENDING processing -> CANCEL_REQUESTED
PAYMENT_FAILED -> CANCEL with no refund
PAYMENT_REVIEW -> CANCEL_REQUESTED
ALLOCATION_PENDING/REVIEW/BACKORDERED -> CANCEL plus full refund request
ALLOCATION_PROCESSING -> CANCEL_REQUESTED, resolved by allocation result
ORDER -> CANCEL_REQUESTED, release reservation, CANCEL plus full refund request
SHIPPED/DELIVERED -> cancellation rejected
duplicate cancellation -> no duplicate release and no duplicate refund
```

- [ ] **Step 5: Implement recoverable cancellation**

`OrderCancellationService.request` verifies ownership and records whether WMS release is required. Approval/allocation processing uses `null` until its result resolves the release decision, and the cancellation worker skips unresolved rows. `CancellationProcessor` atomically leases a resolved `CANCEL_REQUESTED` row through `cancellationProcessingAt`, calls `InventoryPort.releaseAll` outside a DB transaction when the flag is true, then finishes cancellation and creates the full refund request transactionally. `CancellationSweeper` reclaims stale leases after restart. A completed release can be repeated because WMS release is idempotent by `orderId`.

Replace controller use of `OrderService.cancelOrder` with `PaymentFacade.cancelOrder`. Update success copy to `주문 취소가 접수되었습니다. 환불 상태를 확인해주세요.` for paid orders, while unpaid cancellation may still report immediate completion.

- [ ] **Step 6: Run cancellation, refund, and concurrency tests**

Run: `./gradlew test --tests "com.jhg.hgpage.service.Refund*" --tests "com.jhg.hgpage.service.OrderCancellationServiceTest" --tests "com.jhg.hgpage.service.CancellationProcessorTest" --tests "com.jhg.hgpage.service.PaymentCancellationConcurrencyTest" --tests "com.jhg.hgpage.service.OrderServiceCancelTest" --tests "com.jhg.hgpage.controller.order.OrderControllerMvcTest"`

Expected: PASS; payment approval/allocation cancellation races converge to one cancellation and at most one full refund.

- [ ] **Step 7: Commit cancellation refunds**

```bash
git add src/main/java/com/jhg/hgpage/oms/service/RefundService.java src/main/java/com/jhg/hgpage/oms/service/RefundProcessor.java src/main/java/com/jhg/hgpage/oms/service/RefundSweeper.java src/main/java/com/jhg/hgpage/oms/service/OrderCancellationService.java src/main/java/com/jhg/hgpage/oms/service/CancellationProcessor.java src/main/java/com/jhg/hgpage/oms/service/CancellationSweeper.java src/main/java/com/jhg/hgpage/oms/service/PaymentFacade.java src/main/java/com/jhg/hgpage/oms/repository/OrderRepository.java src/main/java/com/jhg/hgpage/oms/web/controller/OrderController.java src/test/java/com/jhg/hgpage/service/RefundServiceTest.java src/test/java/com/jhg/hgpage/service/RefundProcessorTest.java src/test/java/com/jhg/hgpage/service/RefundSweeperTest.java src/test/java/com/jhg/hgpage/service/RefundClaimConcurrencyTest.java src/test/java/com/jhg/hgpage/service/OrderCancellationServiceTest.java src/test/java/com/jhg/hgpage/service/CancellationProcessorTest.java src/test/java/com/jhg/hgpage/service/PaymentCancellationConcurrencyTest.java src/test/java/com/jhg/hgpage/service/OrderServiceCancelTest.java src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java
git diff --cached --name-only
git commit -m "feat(oms): refund paid order cancellations"
```

### Task 6: Create Accepted-Quantity Refunds From Completed Returns

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/oms/service/ReturnSyncService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/RefundService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/repository/CustomerReturnRepository.java`
- Modify: `src/test/java/com/jhg/hgpage/service/ReturnSyncServiceTest.java`
- Create: `src/test/java/com/jhg/hgpage/service/ReturnRefundConcurrencyTest.java`
- Modify: `src/test/java/com/jhg/hgpage/controller/api/ReturnStatusApiControllerMvcTest.java`

**Interfaces:**
- Consumes: `RefundService.requestReturnRefund(CustomerReturn)` from Task 5.
- Preserves: WMS callback validation, regression ignoring, `CANCELLED` with null disposition, and duplicate completed callback no-op.
- Produces: one `RefundRequest(RETURN, customerReturn.id)` when calculated amount is positive.

- [ ] **Step 1: Write failing completed-return refund tests**

```java
@Test
void 완료_반품은_승인수량과_주문단가로_부분환불을_예약한다() {
    // order item prices: 10,000 and 20,000; accepted quantities: 2 and 1
    returnSyncService.apply(completedResult);

    RefundRequest refund = refundRepository
            .findBySourceTypeAndSourceId(RefundSourceType.RETURN, customerReturnId)
            .orElseThrow();
    assertThat(refund.getAmount()).isEqualTo(40_000);
}
```

Also test `RESTOCKED` and `DISPOSED` inclusion, zero accepted/rejected no refund, partial approval, duplicate callback, cumulative separate returns, over-refund rollback, and simultaneous duplicate callbacks producing one request.

- [ ] **Step 2: Run return tests and verify missing refund behavior**

Run: `./gradlew test --tests "com.jhg.hgpage.service.ReturnSyncServiceTest" --tests "com.jhg.hgpage.service.ReturnRefundConcurrencyTest"`

Expected: FAIL because completed WMS results do not create refunds.

- [ ] **Step 3: Integrate refund creation in the callback transaction**

After a successful `customerReturn.complete(List<CustomerReturn.ResultItem>)` call, invoke:

```java
refundService.requestReturnRefund(customerReturn);
```

Calculate with persisted order-time prices:

```java
int amount = customerReturn.getItems().stream()
        .filter(item -> item.getAcceptedQuantity() != null)
        .mapToInt(item -> item.getOrderItem().getOrderPrice() * item.getAcceptedQuantity())
        .sum();
```

For `current == COMPLETED`, validate matching results and return without creating another work row. A zero amount returns `Optional.empty()`.

- [ ] **Step 4: Run return callback and concurrency tests**

Run: `./gradlew test --tests "com.jhg.hgpage.service.ReturnSyncServiceTest" --tests "com.jhg.hgpage.service.ReturnRefundConcurrencyTest" --tests "com.jhg.hgpage.controller.api.ReturnStatusApiControllerMvcTest" --tests "com.jhg.hgpage.service.ReturnMutationConcurrencyTest"`

Expected: PASS; contract mismatch still rolls back both RMA status and refund creation.

- [ ] **Step 5: Commit return refunds**

```bash
git add src/main/java/com/jhg/hgpage/oms/service/ReturnSyncService.java src/main/java/com/jhg/hgpage/oms/service/RefundService.java src/main/java/com/jhg/hgpage/oms/repository/CustomerReturnRepository.java src/test/java/com/jhg/hgpage/service/ReturnSyncServiceTest.java src/test/java/com/jhg/hgpage/service/ReturnRefundConcurrencyTest.java src/test/java/com/jhg/hgpage/controller/api/ReturnStatusApiControllerMvcTest.java
git diff --cached --name-only
git commit -m "feat(oms): refund accepted return quantities"
```

### Task 7: Expose Customer and Admin Payment Operations

**Files:**
- Create: `src/main/java/com/jhg/hgpage/oms/dto/PaymentViewDto.java`
- Create: `src/main/java/com/jhg/hgpage/oms/dto/AdminPaymentDto.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/dto/OrderDto.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/dto/OrderDetailDto.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/dto/AdminOrderDto.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/repository/OrderRepositoryQuery.java`
- Create: `src/main/java/com/jhg/hgpage/oms/service/PaymentAdminService.java`
- Create: `src/main/java/com/jhg/hgpage/oms/web/controller/PaymentAdminController.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/web/controller/OrderAdminController.java`
- Modify: `src/main/resources/templates/orders.html`
- Modify: `src/main/resources/templates/orderview.html`
- Modify: `src/main/resources/templates/admin/orders.html`
- Create: `src/main/resources/templates/admin/payments.html`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java`
- Create: `src/test/java/com/jhg/hgpage/controller/admin/PaymentAdminControllerMvcTest.java`
- Modify: `src/test/java/com/jhg/hgpage/controller/admin/OrderAdminControllerMvcTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/PaymentAdminConcurrencyTest.java`
- Modify: `src/test/java/com/jhg/hgpage/template/ResponsiveTemplateContractTest.java`

**Interfaces:**
- Produces: customer read fields `paymentStatusLabel`, `paidAmount`, `pendingRefundAmount`, `refundedAmount`, `paymentRetryable`, and processing-safe `orderStatusLabel`.
- Produces: `GET /admin/payments`, `POST /admin/refunds/{refundId}/retry`, and `POST /admin/orders/{orderId}/allocation/retry`.
- Produces: admin counts for refund manual review and allocation manual review.

- [ ] **Step 1: Write failing customer rendering tests**

Assert exact Korean labels:

```text
PAYMENT_PENDING/PAYMENT_REVIEW -> 결제 확인 중
PAYMENT_FAILED -> 결제 실패
ALLOCATION_PENDING/PROCESSING -> 재고 확인 중
ALLOCATION_REVIEW -> 재고 확인 지연
CANCEL_REQUESTED -> 주문 취소 처리 중
BACKORDERED with PAID -> 결제 완료 · 입고 대기
Refund PENDING/PROCESSING/RETRYING/MANUAL_REVIEW -> 환불 확인 중
```

Verify the customer page never renders `failureCode`, `failureReason`, idempotency keys, or gateway transaction IDs. Show `다시 결제` only for an owned `PAYMENT_FAILED` order.

- [ ] **Step 2: Run customer MVC/template tests and verify failure**

Run: `./gradlew test --tests "com.jhg.hgpage.controller.order.OrderControllerMvcTest" --tests "com.jhg.hgpage.template.ResponsiveTemplateContractTest"`

Expected: FAIL because payment fields and labels are absent.

- [ ] **Step 3: Extend read queries/DTOs and customer templates**

Fetch payment and refund summaries without per-order N+1 queries. Keep the existing active-first/newest sorting, timeline, delivery status, and return UI. Add a separate unframed payment section in order detail and compact payment status in each order list row. Update timeline handling so pre-allocation states do not appear as inventory secured.

- [ ] **Step 4: Write failing admin authorization and action tests**

```java
mockMvc.perform(get("/admin/payments").with(user(userPrincipal())))
        .andExpect(status().isForbidden());

mockMvc.perform(post("/admin/refunds/7/retry").with(user(admin())).with(csrf()))
        .andExpect(status().is3xxRedirection());
verify(paymentAdminService).retryRefund(7L);
```

Also verify missing CSRF is 403, only `MANUAL_REVIEW` refund retry is accepted, only `ALLOCATION_REVIEW` allocation retry is accepted, and actions requeue work without directly setting success. `PaymentAdminConcurrencyTest` races an admin retry with the scheduled worker and asserts the transactional claim permits one gateway/WMS call.

- [ ] **Step 5: Implement admin read/actions and templates**

`PaymentAdminService` returns filterable rows with order ID, return ID, amounts, request key, attempt count, failure reason, and next retry time. `retryRefund` and `retryAllocation` atomically move eligible work back to processable state and invoke their processor after commit. Add `결제·환불 관리` to the admin nav and summary counts at the top of that page.

- [ ] **Step 6: Run customer/admin UI tests**

Run: `./gradlew test --tests "com.jhg.hgpage.controller.order.OrderControllerMvcTest" --tests "com.jhg.hgpage.controller.admin.PaymentAdminControllerMvcTest" --tests "com.jhg.hgpage.controller.admin.OrderAdminControllerMvcTest" --tests "com.jhg.hgpage.service.PaymentAdminConcurrencyTest" --tests "com.jhg.hgpage.template.ResponsiveTemplateContractTest"`

Expected: PASS at desktop/mobile template contracts with no action exposed to the wrong role.

- [ ] **Step 7: Commit payment operations UI**

```bash
git add src/main/java/com/jhg/hgpage/oms/dto/PaymentViewDto.java src/main/java/com/jhg/hgpage/oms/dto/AdminPaymentDto.java src/main/java/com/jhg/hgpage/oms/dto/OrderDto.java src/main/java/com/jhg/hgpage/oms/dto/OrderDetailDto.java src/main/java/com/jhg/hgpage/oms/dto/AdminOrderDto.java src/main/java/com/jhg/hgpage/oms/service/OrderService.java src/main/java/com/jhg/hgpage/oms/service/PaymentAdminService.java src/main/java/com/jhg/hgpage/oms/repository/OrderRepositoryQuery.java src/main/java/com/jhg/hgpage/oms/web/controller/PaymentAdminController.java src/main/java/com/jhg/hgpage/oms/web/controller/OrderAdminController.java src/main/resources/templates/orders.html src/main/resources/templates/orderview.html src/main/resources/templates/admin/orders.html src/main/resources/templates/admin/payments.html src/main/resources/templates/fragments/layout.html src/main/resources/static/css/app.css src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java src/test/java/com/jhg/hgpage/controller/admin/PaymentAdminControllerMvcTest.java src/test/java/com/jhg/hgpage/controller/admin/OrderAdminControllerMvcTest.java src/test/java/com/jhg/hgpage/service/PaymentAdminConcurrencyTest.java src/test/java/com/jhg/hgpage/template/ResponsiveTemplateContractTest.java
git diff --cached --name-only
git commit -m "feat(oms): expose payment and refund operations"
```

### Task 8: Configure Recovery, Seed Scenarios, Document, and Run Full Verification

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/jhg/hgpage/initDb.java`
- Modify: `src/test/java/com/jhg/hgpage/InitDbTest.java`
- Modify: `src/test/java/com/jhg/hgpage/HgpageApplicationTests.java`
- Create: `src/test/java/com/jhg/hgpage/service/PaymentWorkflowIntegrationTest.java`
- Modify: `README.md`
- Modify: `docs/manual-verification-scenarios.md`

**Interfaces:**
- Consumes: all Tasks 1-7.
- Produces: explicit worker delays/stale thresholds, local demo states, automated end-to-end workflow coverage, and the 12 payment/refund manual scenarios from the spec.

- [ ] **Step 1: Add failing configuration and workflow integration tests**

The integration test must prove:

```text
checkout -> paid -> allocation -> ORDER
checkout -> paid -> shortage -> BACKORDERED
paid BACKORDERED cancel -> one full refund
delivered order -> accepted partial return -> one partial refund
application-context restart query -> unfinished work remains discoverable
```

Use mocked `PaymentGateway` and `InventoryPort`; assert persisted states and amounts after each worker call.

- [ ] **Step 2: Run the workflow test and verify missing configuration/seed behavior**

Run: `./gradlew test --tests "com.jhg.hgpage.service.PaymentWorkflowIntegrationTest" --tests "com.jhg.hgpage.InitDbTest" --tests "com.jhg.hgpage.HgpageApplicationTests"`

Expected: FAIL until worker properties and reset-compatible seed expectations are added.

- [ ] **Step 3: Add worker settings and reset-only demo seeds**

Add these defaults to `application.yml`:

```yaml
payments:
  sweep-delay: ${PAYMENT_SWEEP_DELAY:5s}
  processing-timeout: ${PAYMENT_PROCESSING_TIMEOUT:5m}
allocation:
  sweep-delay: ${ALLOCATION_SWEEP_DELAY:5s}
  processing-timeout: ${ALLOCATION_PROCESSING_TIMEOUT:5m}
refunds:
  sweep-delay: ${REFUND_SWEEP_DELAY:5s}
  processing-timeout: ${REFUND_PROCESSING_TIMEOUT:5m}
cancellations:
  sweep-delay: ${CANCELLATION_SWEEP_DELAY:5s}
  processing-timeout: ${CANCELLATION_PROCESSING_TIMEOUT:5m}
```

Keep production values overrideable through environment variables. Seed the seven design states only on an empty/reset database, using domain factories rather than SQL. Do not mutate or backfill existing orders during ordinary startup.

- [ ] **Step 4: Update README and manual scenarios**

README must describe V2 mock payment, charge-before-allocation, asynchronous refund recovery, admin manual review, and the V3 actual-PG/coupon/point roadmap. Append the 12 numbered manual scenarios from design section 18 with setup, action, expected OMS state, expected WMS state, payment/refund amount, and idempotency observation.

- [ ] **Step 5: Run focused integration and migration checks**

Run: `./gradlew test --tests "com.jhg.hgpage.service.PaymentWorkflowIntegrationTest" --tests "com.jhg.hgpage.FlywayMigrationTest" --tests "com.jhg.hgpage.LocalDataMigrationTest" --tests "com.jhg.hgpage.InitDbTest" --tests "com.jhg.hgpage.HgpageApplicationTests"`

Expected: PASS.

- [ ] **Step 6: Run the entire OMS test suite from a clean task execution**

Run: `./gradlew test --rerun-tasks`

Expected: `BUILD SUCCESSFUL` with zero failed tests. Record the executed test count from `build/test-results/test/*.xml` in the implementation close-out.

- [ ] **Step 7: Check the diff and migration consistency**

Run: `git diff --check`

Run: `rg -n "TO[D]O|TB[D]|FIXM[E]|PAYMENT_|ALLOCATION_|MANUAL_REVIEW|pendingRefundAmount|refundedAmount" src/main src/test README.md docs/manual-verification-scenarios.md`

Expected: no whitespace errors, no unfinished placeholders, and every new state represented in domain logic, read models, UI labels, and tests.

- [ ] **Step 8: Commit configuration and documentation**

```bash
git add src/main/resources/application.yml src/main/java/com/jhg/hgpage/initDb.java src/test/java/com/jhg/hgpage/InitDbTest.java src/test/java/com/jhg/hgpage/HgpageApplicationTests.java src/test/java/com/jhg/hgpage/service/PaymentWorkflowIntegrationTest.java README.md docs/manual-verification-scenarios.md
git diff --cached --name-only
git commit -m "docs(oms): verify mock payment workflow"
```

- [ ] **Step 9: Reset and manually verify both development databases**

Stop both applications. Delete/reset the OMS and WMS local databases together using the repositories' documented reset commands, then start both with the `local` profile. Execute all 12 scenarios in `docs/manual-verification-scenarios.md`, record actual order/payment/refund/RMA IDs, and confirm that repeated callbacks/retries do not duplicate WMS reservation, release, refund, or return stock ledger effects.

Do not run this destructive reset until the user explicitly confirms that current local development data may be discarded.
