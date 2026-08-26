# OMS Return Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 고객 반품 신청을 OMS 승인 대기에 저장하고, 관리자가 전체 승인한 건만 기존 WMS RMA 흐름으로 전송하며 반려 사유를 고객에게 보여준다.

**Architecture:** `CustomerReturn`에 `PENDING_APPROVAL`과 `REJECTED`를 추가하고 승인·반려 감사 정보를 함께 보관한다. 기존 `PENDING_SUBMISSION` 이후의 WMS 전송·스위퍼·콜백·환불 흐름은 그대로 두며, 관리자 승인만 그 흐름의 새 진입점이 된다. 관리자 목록은 기존 `CustomerReturnService`와 전용 읽기 DTO를 사용해 별도 서비스 계층을 만들지 않는다.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring MVC, Spring Data JPA, Spring Security, Thymeleaf, Flyway, JUnit 5, AssertJ, MockMvc

**Spec:** `docs/superpowers/specs/2026-08-26-oms-return-approval-design.md`

## Global Constraints

- OMS 관리자는 신청 전체만 승인하거나 반려한다. 품목별 수량과 처분은 WMS가 검수한다.
- 반려 사유는 1자 이상 500자 이하이며 고객 화면에 표시한다.
- 승인·반려는 기존 `ADMIN` 권한과 CSRF 보호를 사용한다.
- 승인된 요청만 `PENDING_SUBMISSION`으로 전환하고 기존 WMS 전송·멱등키·스위퍼를 재사용한다.
- WMS 저장소와 WMS API 계약은 변경하지 않는다.
- 운영 PostgreSQL 스키마는 Flyway `V10`으로 변경하고 로컬·테스트 H2는 기존 Hibernate 관리를 유지한다.
- 새 라이브러리와 별도 승인 프레임워크를 추가하지 않는다.

---

### Task 1: 반품 승인 상태 머신과 운영 스키마

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/oms/domain/enums/CustomerReturnStatus.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/domain/CustomerReturn.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/ReturnSyncService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/dto/CustomerReturnDto.java`
- Create: `src/main/resources/db/migration/V10__add_return_review.sql`
- Modify: `src/test/java/com/jhg/hgpage/domain/CustomerReturnTest.java`
- Modify: `src/test/java/com/jhg/hgpage/PaymentMigrationTest.java`
- Modify fixture setup in every test returned by `rg -l 'CustomerReturn\.create' src/test/java`

**Interfaces:**
- Produces: `CustomerReturnStatus.getLabel()`
- Produces: `CustomerReturn.approve(String reviewer)`
- Produces: `CustomerReturn.reject(String reviewer, String reason)`
- Produces getters `getReviewedBy()`, `getReviewedAt()`, `getRejectionReason()` through existing Lombok `@Getter`
- Preserves: `PENDING_SUBMISSION` as the only state accepted by `CustomerReturnService.pendingSubmission(Long)`

- [ ] **Step 1: Write failing domain tests for creation, approval, rejection, and illegal repeat review**

Add focused tests to `CustomerReturnTest`:

```java
@Test
void 새_반품은_OMS_승인대기로_생성한다() {
    CustomerReturn result = pendingReturn(2);

    assertThat(result.getStatus()).isEqualTo(CustomerReturnStatus.PENDING_APPROVAL);
}

@Test
void 승인하면_WMS_전송대기가_되고_승인자를_기록한다() {
    CustomerReturn result = pendingReturn(2);

    result.approve(" admin@example.com ");

    assertThat(result.getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);
    assertThat(result.getReviewedBy()).isEqualTo("admin@example.com");
    assertThat(result.getReviewedAt()).isNotNull();
}

@Test
void 반려하면_사유를_기록하고_재처리를_거부한다() {
    CustomerReturn result = pendingReturn(2);

    result.reject("admin@example.com", " 정책상 반품 불가 ");

    assertThat(result.getStatus()).isEqualTo(CustomerReturnStatus.REJECTED);
    assertThat(result.getRejectionReason()).isEqualTo("정책상 반품 불가");
    assertThatThrownBy(() -> result.approve("admin@example.com"))
            .isInstanceOf(IllegalStateException.class);
}
```

Also add parameterized rejection tests for `null`, empty, blank, and 501-character reasons, plus an empty reviewer test.

- [ ] **Step 2: Run the domain tests and verify RED**

Run:

```bash
./gradlew test --tests com.jhg.hgpage.domain.CustomerReturnTest
```

Expected: compilation fails because `PENDING_APPROVAL`, `REJECTED`, `approve`, and `reject` do not exist.

- [ ] **Step 3: Implement the enum labels, state transitions, and audit fields**

Use one label source in `CustomerReturnStatus`:

```java
public enum CustomerReturnStatus {
    PENDING_APPROVAL("OMS 승인 대기"),
    PENDING_SUBMISSION("WMS 전송 중"),
    SUBMISSION_FAILED("접수 실패"),
    REQUESTED("반품 접수"),
    RECEIVED("창고 입고"),
    COMPLETED("반품 완료"),
    CANCELLED("반품 취소"),
    REJECTED("반품 반려");

    private final String label;

    CustomerReturnStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}
```

Add fields to `CustomerReturn`:

```java
@Column(name = "reviewed_by", length = 255)
private String reviewedBy;

@Column(name = "reviewed_at")
private LocalDateTime reviewedAt;

@Column(name = "rejection_reason", length = 500)
private String rejectionReason;
```

Create new returns in `PENDING_APPROVAL`. Implement `approve` and `reject` with a shared state guard and trimmed validation:

```java
public void approve(String reviewer) {
    requirePendingApproval();
    reviewedBy = requireText(reviewer, "승인자는 필수입니다.", 255);
    reviewedAt = LocalDateTime.now();
    changeStatus(CustomerReturnStatus.PENDING_SUBMISSION);
}

public void reject(String reviewer, String reason) {
    requirePendingApproval();
    reviewedBy = requireText(reviewer, "승인자는 필수입니다.", 255);
    rejectionReason = requireText(reason, "반려 사유는 1자 이상 500자 이하여야 합니다.", 500);
    reviewedAt = LocalDateTime.now();
    changeStatus(CustomerReturnStatus.REJECTED);
}
```

Update `CustomerReturnDto` to use `status.getLabel()`, expose `rejectionReason`, count `PENDING_APPROVAL` as claimed, and render rejected item results as `반품 반려`. Update `ReturnSyncService.isLegal` so both `PENDING_APPROVAL` and `REJECTED` return `false`; a WMS callback can never bypass OMS approval.

- [ ] **Step 4: Add the Flyway migration and extend its executable migration test**

Create `V10__add_return_review.sql`:

```sql
ALTER TABLE customer_return ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(255);
ALTER TABLE customer_return ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;
ALTER TABLE customer_return ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(500);
```

In `PaymentMigrationTest`, apply `V5__add_customer_returns.sql` before payment migrations and `V10__add_return_review.sql` last. Extend the metadata assertion:

```java
assertThat(columnNames(metadata, "customer_return"))
        .contains("reviewed_by", "reviewed_at", "rejection_reason");
```

- [ ] **Step 5: Update existing test fixtures to enter the state they claim to represent**

For helpers named `pendingReturn()` that represent a WMS submission, call approval immediately after creation:

```java
CustomerReturn customerReturn = CustomerReturn.create(order, key, reason, items);
customerReturn.approve("admin@example.com");
return customerReturn;
```

Leave customer-request fixtures unapproved. Update exhaustive enum switches and assertions across the listed test files; do not relax production transition guards to keep old fixtures passing.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run:

```bash
./gradlew test --tests com.jhg.hgpage.domain.CustomerReturnTest \
  --tests com.jhg.hgpage.PaymentMigrationTest \
  --tests com.jhg.hgpage.service.ReturnSyncServiceTest \
  --tests com.jhg.hgpage.service.ReturnSubmissionServiceTest
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit the state machine**

```bash
git add src/main/java/com/jhg/hgpage/oms/domain/enums/CustomerReturnStatus.java \
  src/main/java/com/jhg/hgpage/oms/domain/CustomerReturn.java \
  src/main/java/com/jhg/hgpage/oms/service/ReturnSyncService.java \
  src/main/java/com/jhg/hgpage/oms/dto/CustomerReturnDto.java \
  src/main/resources/db/migration/V10__add_return_review.sql \
  src/test/java
git commit -m "feat(returns): add OMS review states"
```

---

### Task 2: 승인·반려 서비스, 수량 정책, 관리자 조회

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/oms/repository/CustomerReturnRepository.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/CustomerReturnService.java`
- Create: `src/main/java/com/jhg/hgpage/oms/dto/AdminCustomerReturnDto.java`
- Modify: `src/test/java/com/jhg/hgpage/service/CustomerReturnServiceTest.java`
- Modify: `src/test/java/com/jhg/hgpage/service/CustomerReturnConcurrencyTest.java`
- Modify: `src/test/java/com/jhg/hgpage/repository/CustomerReturnRepositoryTest.java`

**Interfaces:**
- Produces: `void CustomerReturnService.approveReturn(Long returnId, String reviewer)`
- Produces: `void CustomerReturnService.rejectReturn(Long returnId, String reviewer, String reason)`
- Produces: `List<AdminCustomerReturnDto> CustomerReturnService.findAllForAdmin(CustomerReturnStatus status)`
- Produces: `List<CustomerReturn> CustomerReturnRepository.findAllDetailedForAdmin(CustomerReturnStatus status)`

- [ ] **Step 1: Write failing service tests for approval gating and rejected quantity release**

Replace the first request test so it proves the new boundary:

```java
Long returnId = customerReturnService.request(orderId, memberId, "상품 불량", lines);
assertThat(customerReturnRepository.findById(returnId).orElseThrow().getStatus())
        .isEqualTo(CustomerReturnStatus.PENDING_APPROVAL);
assertThatThrownBy(() -> customerReturnService.pendingSubmission(returnId))
        .isInstanceOf(IllegalStateException.class);

customerReturnService.approveReturn(returnId, "admin@example.com");

assertThat(customerReturnService.pendingSubmission(returnId).returnId()).isEqualTo(returnId);
```

Add a rejection-and-resubmission test:

```java
Long rejectedId = customerReturnService.request(orderId, memberId, "불량", linesForAllUnits);
customerReturnService.rejectReturn(rejectedId, "admin@example.com", "정책상 반품 불가");

Long retryId = customerReturnService.request(orderId, memberId, "상세 사유 보완", linesForAllUnits);

assertThat(retryId).isNotNull();
```

- [ ] **Step 2: Write failing repository and admin DTO tests**

Add repository fixtures with old and new request timestamps and assert that an unfiltered query places `PENDING_APPROVAL` first and orders equal-priority rows oldest-first. Assert a status-filtered query returns only that state and initializes order member, items, order item, and product.

Add service assertions for the DTO fields:

```java
assertThat(customerReturnService.findAllForAdmin(CustomerReturnStatus.PENDING_APPROVAL))
        .singleElement()
        .satisfies(row -> {
            assertThat(row.customerName()).isEqualTo("테스터");
            assertThat(row.statusLabel()).isEqualTo("OMS 승인 대기");
            assertThat(row.items()).singleElement()
                    .extracting(AdminCustomerReturnDto.Item::quantity).isEqualTo(2);
        });
```

- [ ] **Step 3: Run service and repository tests and verify RED**

Run:

```bash
./gradlew test --tests com.jhg.hgpage.service.CustomerReturnServiceTest \
  --tests com.jhg.hgpage.repository.CustomerReturnRepositoryTest
```

Expected: compilation fails because review methods, query, and DTO do not exist.

- [ ] **Step 4: Implement the repository query and admin DTO**

Add an entity-graph query that accepts a nullable filter and applies the required ordering:

```java
@EntityGraph(attributePaths = {"order", "order.member", "items", "items.orderItem", "items.orderItem.product"})
@Query("""
        select distinct r from CustomerReturn r
        where (:status is null or r.status = :status)
        order by case when r.status = com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus.PENDING_APPROVAL
                      then 0 else 1 end,
                 r.requestedAt, r.id
        """)
List<CustomerReturn> findAllDetailedForAdmin(CustomerReturnStatus status);
```

Create `AdminCustomerReturnDto` as a record:

```java
public record AdminCustomerReturnDto(
        Long id, Long orderId, String customerName,
        CustomerReturnStatus status, String statusLabel,
        String reason, String reviewedBy, LocalDateTime reviewedAt,
        String rejectionReason, String failureReason,
        LocalDateTime requestedAt, List<Item> items) {
    public record Item(String productName, int quantity) {}

    public static AdminCustomerReturnDto from(CustomerReturn value) {
        return new AdminCustomerReturnDto(
                value.getId(), value.getOrder().getId(), value.getOrder().getMember().getName(),
                value.getStatus(), value.getStatus().getLabel(), value.getReason(),
                value.getReviewedBy(), value.getReviewedAt(), value.getRejectionReason(),
                value.getFailureReason(), value.getRequestedAt(),
                value.getItems().stream()
                        .map(item -> new Item(item.getOrderItem().getProduct().getName(),
                                item.getRequestedQuantity()))
                        .toList());
    }
}
```

The implementation of `from` must map requested quantities, not accepted quantities; OMS review displays what the customer asked for.

- [ ] **Step 5: Implement review service methods and quantity accounting**

Use the existing pessimistic `findDetailedByIdForUpdate` path:

```java
@Transactional
public void approveReturn(Long returnId, String reviewer) {
    findForUpdate(returnId).approve(reviewer);
}

@Transactional
public void rejectReturn(Long returnId, String reviewer, String reason) {
    findForUpdate(returnId).reject(reviewer, reason);
}

@Transactional(readOnly = true)
public List<AdminCustomerReturnDto> findAllForAdmin(CustomerReturnStatus status) {
    return customerReturnRepository.findAllDetailedForAdmin(status).stream()
            .map(AdminCustomerReturnDto::from)
            .toList();
}
```

Update `usedQuantities` exactly as follows:

```java
case PENDING_APPROVAL, PENDING_SUBMISSION, REQUESTED, RECEIVED -> item.getRequestedQuantity();
case COMPLETED -> item.getAcceptedQuantity();
case REJECTED, CANCELLED, SUBMISSION_FAILED -> 0;
```

`pendingSubmissionIds()` continues to select only `PENDING_SUBMISSION`.

- [ ] **Step 6: Add a concurrent approve/reject check**

In `CustomerReturnConcurrencyTest`, start `approveReturn` and `rejectReturn` against the same ID behind a `CountDownLatch`. Assert exactly one call succeeds, one throws `IllegalStateException`, and the saved state is either `PENDING_SUBMISSION` or `REJECTED`, never an intermediate state.

- [ ] **Step 7: Run focused tests and verify GREEN**

Run:

```bash
./gradlew test --tests com.jhg.hgpage.service.CustomerReturnServiceTest \
  --tests com.jhg.hgpage.service.CustomerReturnConcurrencyTest \
  --tests com.jhg.hgpage.repository.CustomerReturnRepositoryTest
```

Expected: all selected tests pass.

- [ ] **Step 8: Commit the service boundary**

```bash
git add src/main/java/com/jhg/hgpage/oms/repository/CustomerReturnRepository.java \
  src/main/java/com/jhg/hgpage/oms/service/CustomerReturnService.java \
  src/main/java/com/jhg/hgpage/oms/dto/AdminCustomerReturnDto.java \
  src/test/java/com/jhg/hgpage/service/CustomerReturnServiceTest.java \
  src/test/java/com/jhg/hgpage/service/CustomerReturnConcurrencyTest.java \
  src/test/java/com/jhg/hgpage/repository/CustomerReturnRepositoryTest.java
git commit -m "feat(returns): add OMS review service"
```

---

### Task 3: 고객 신청을 승인 대기에서 멈추고 상태를 안내

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/oms/web/controller/CustomerReturnController.java`
- Modify: `src/main/resources/templates/returnview.html`
- Modify: `src/main/resources/templates/orderview.html`
- Modify: `src/test/java/com/jhg/hgpage/controller/order/CustomerReturnControllerMvcTest.java`
- Modify: `src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java`

**Interfaces:**
- Consumes: `CustomerReturnService.request(...)` returning a `PENDING_APPROVAL` ID
- Consumes: `CustomerReturnDto.getRejectionReason()` and `CustomerReturnStatus.getLabel()`
- Produces: customer-visible approval waiting and rejection detail

- [ ] **Step 1: Rewrite the customer submission MVC test to require no WMS call**

Remove `ReturnSubmissionService` from the controller test context and assert the new message:

```java
mockMvc.perform(post("/orders/10/returns")
        .with(user(userPrincipal())).with(csrf())
        .param("reason", "사이즈가 맞지 않습니다.")
        .param("lines[0].orderItemId", "102")
        .param("lines[0].quantity", "2"))
    .andExpect(status().is3xxRedirection())
    .andExpect(flash().attribute("successMessage",
            "반품 신청이 접수되어 관리자 승인을 기다리고 있습니다."));

verify(customerReturnService).request(eq(10L), eq(1L),
        eq("사이즈가 맞지 않습니다."), anyList());
```

Delete the controller tests that branch on immediate `PENDING_SUBMISSION` and `SUBMISSION_FAILED`; those states can now arise only after an administrator approves.

- [ ] **Step 2: Add failing customer detail tests for approval waiting and rejection**

Use real `CustomerReturn` fixtures:

```java
@Test
void 승인대기_반품은_OMS_승인단계를_현재로_표시한다() throws Exception {
    when(customerReturnService.findOwned(77L, 1L)).thenReturn(approvalPendingReturn());

    mockMvc.perform(get("/returns/77").with(user(userPrincipal())))
            .andExpect(content().string(containsString("OMS 승인 대기")))
            .andExpect(content().string(containsString(">OMS 승인</div>")));
}

@Test
void 반려된_반품은_반려사유를_표시한다() throws Exception {
    CustomerReturn value = approvalPendingReturn();
    value.reject("admin@example.com", "배송 완료 후 30일이 지났습니다.");
    when(customerReturnService.findOwned(77L, 1L)).thenReturn(value);

    mockMvc.perform(get("/returns/77").with(user(userPrincipal())))
            .andExpect(content().string(containsString("반품 반려")))
            .andExpect(content().string(containsString("배송 완료 후 30일이 지났습니다.")));
}
```

- [ ] **Step 3: Run customer MVC tests and verify RED**

Run:

```bash
./gradlew test --tests com.jhg.hgpage.controller.order.CustomerReturnControllerMvcTest \
  --tests com.jhg.hgpage.controller.order.OrderControllerMvcTest
```

Expected: old immediate WMS behavior and missing approval/rejection markup cause failures.

- [ ] **Step 4: Remove immediate WMS submission from the customer controller**

Remove the `ReturnSubmissionService` dependency and the post-save status switch. After a successful `customerReturnService.request(...)`, set exactly one success message:

```java
redirectAttributes.addFlashAttribute("successMessage",
        "반품 신청이 접수되어 관리자 승인을 기다리고 있습니다.");
```

Keep all existing form validation, PRG behavior, ownership checks, and CSRF behavior unchanged.

- [ ] **Step 5: Render the four-step customer timeline and rejection reason**

Change `returnview.html` to show these steps:

```html
<div class="timeline-step">OMS 승인</div>
<div class="timeline-step">WMS 접수</div>
<div class="timeline-step">창고 도착</div>
<div class="timeline-step">반품 완료</div>
```

Use status conditions so `PENDING_APPROVAL` marks OMS 승인 as current, `REJECTED` marks it stopped, `PENDING_SUBMISSION` marks WMS 접수 current, and existing WMS states advance the remaining steps. Add:

```html
<dt th:if="${customerReturn.rejectionReason != null}">반려 사유</dt>
<dd th:if="${customerReturn.rejectionReason != null}"
    th:text="${customerReturn.rejectionReason}">반려 사유</dd>
```

`orderview.html` already renders `statusLabel`; retain that path so the enum label automatically shows `OMS 승인 대기` or `반품 반려`.
Inside each return-history row, also render the reason for rejected requests:

```html
<span th:if="${customerReturn.rejectionReason != null}"
      th:text="|반려 사유: ${customerReturn.rejectionReason}|">반려 사유</span>
```

- [ ] **Step 6: Run customer MVC tests and verify GREEN**

Run:

```bash
./gradlew test --tests com.jhg.hgpage.controller.order.CustomerReturnControllerMvcTest \
  --tests com.jhg.hgpage.controller.order.OrderControllerMvcTest
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit the customer flow**

```bash
git add src/main/java/com/jhg/hgpage/oms/web/controller/CustomerReturnController.java \
  src/main/resources/templates/returnview.html \
  src/main/resources/templates/orderview.html \
  src/test/java/com/jhg/hgpage/controller/order/CustomerReturnControllerMvcTest.java \
  src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java
git commit -m "feat(returns): wait for OMS approval"
```

---

### Task 4: OMS 관리자 반품 관리 화면과 승인 후 WMS 인계

**Files:**
- Create: `src/main/java/com/jhg/hgpage/oms/web/controller/CustomerReturnAdminController.java`
- Create: `src/main/resources/templates/admin/returns.html`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Create: `src/test/java/com/jhg/hgpage/controller/admin/CustomerReturnAdminControllerMvcTest.java`

**Interfaces:**
- Consumes: `CustomerReturnService.findAllForAdmin(CustomerReturnStatus)`
- Consumes: `CustomerReturnService.approveReturn(Long, String)`
- Consumes: `CustomerReturnService.rejectReturn(Long, String, String)`
- Consumes: `ReturnSubmissionService.submit(Long)` after the approval transaction returns
- Produces routes: `GET /admin/returns`, `POST /admin/returns/{returnId}/approve`, `POST /admin/returns/{returnId}/reject`

- [ ] **Step 1: Write failing MVC tests for list, filter, approval, rejection, authorization, and CSRF**

Create `CustomerReturnAdminControllerMvcTest` with `@WebMvcTest(CustomerReturnAdminController.class)` and `@Import(SecurityConfig.class)`. Include these observable checks:

```java
@Test
void 관리자는_승인대기_반품과_처리버튼을_본다() throws Exception {
    when(customerReturnService.findAllForAdmin(CustomerReturnStatus.PENDING_APPROVAL))
            .thenReturn(List.of(pendingRow()));

    mockMvc.perform(get("/admin/returns")
            .param("status", "PENDING_APPROVAL").with(user(admin())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("OMS 승인 대기")))
            .andExpect(content().string(containsString("/admin/returns/77/approve")))
            .andExpect(content().string(containsString("/admin/returns/77/reject")));
}

@Test
void 승인하면_상태를_먼저_바꾸고_WMS에_전송한다() throws Exception {
    mockMvc.perform(post("/admin/returns/77/approve")
            .with(user(admin())).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/returns"));

    InOrder calls = inOrder(customerReturnService, returnSubmissionService);
    calls.verify(customerReturnService).approveReturn(77L, "admin@example.com");
    calls.verify(returnSubmissionService).submit(77L);
}

@Test
void 반려는_사유를_필수로_저장하고_WMS를_호출하지않는다() throws Exception {
    mockMvc.perform(post("/admin/returns/77/reject")
            .with(user(admin())).with(csrf())
            .param("reason", "정책상 반품 불가"))
            .andExpect(status().is3xxRedirection());

    verify(customerReturnService)
            .rejectReturn(77L, "admin@example.com", "정책상 반품 불가");
    verifyNoInteractions(returnSubmissionService);
}
```

Also test USER receives 403 for all three routes, missing CSRF receives 403, a blank rejection reason produces an error flash, and an already-reviewed state exception produces an error flash without WMS submission.

- [ ] **Step 2: Run the new MVC test and verify RED**

Run:

```bash
./gradlew test --tests com.jhg.hgpage.controller.admin.CustomerReturnAdminControllerMvcTest
```

Expected: compilation fails because controller and template do not exist.

- [ ] **Step 3: Implement the admin controller**

Use this controller boundary:

```java
@Controller
@RequiredArgsConstructor
@Slf4j
public class CustomerReturnAdminController {
    private final CustomerReturnService customerReturnService;
    private final ReturnSubmissionService returnSubmissionService;

    @GetMapping("/admin/returns")
    public String returns(@RequestParam(required = false) CustomerReturnStatus status, Model model) {
        model.addAttribute("returns", customerReturnService.findAllForAdmin(status));
        model.addAttribute("statuses", CustomerReturnStatus.values());
        model.addAttribute("activeStatus", status);
        return "admin/returns";
    }

    @PostMapping("/admin/returns/{returnId}/approve")
    public String approve(@AuthenticationPrincipal UserPrincipal admin,
                          @PathVariable Long returnId,
                          RedirectAttributes redirectAttributes) {
        try {
            customerReturnService.approveReturn(returnId, admin.getEmail());
            try {
                returnSubmissionService.submit(returnId);
                redirectAttributes.addFlashAttribute("successMessage", "반품을 승인했습니다.");
            } catch (RuntimeException exception) {
                log.warn("승인 후 WMS 반품 전송 실패: returnId={}", returnId, exception);
                redirectAttributes.addFlashAttribute("successMessage",
                        "승인은 완료되었으며 WMS 전송을 다시 확인합니다.");
            }
        } catch (IllegalArgumentException | IllegalStateException | EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:/admin/returns";
    }

    @PostMapping("/admin/returns/{returnId}/reject")
    public String reject(@AuthenticationPrincipal UserPrincipal admin,
                         @PathVariable Long returnId,
                         @RequestParam String reason,
                         RedirectAttributes redirectAttributes) {
        try {
            customerReturnService.rejectReturn(returnId, admin.getEmail(), reason);
            redirectAttributes.addFlashAttribute("successMessage", "반품을 반려했습니다.");
        } catch (IllegalArgumentException | IllegalStateException | EntityNotFoundException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:/admin/returns";
    }
}
```

The approve method must call `approveReturn` before `submit`. Expected transient WMS failures are already absorbed by `ReturnSubmissionService`; if an unexpected runtime failure escapes after approval, keep the approved `PENDING_SUBMISSION` state, log it, and show `승인은 완료되었으며 WMS 전송을 다시 확인합니다.`. State or validation errors show their message as `errorMessage` and do not call `submit`.

- [ ] **Step 4: Build the admin template and navigation link**

Create `admin/returns.html` using existing `app.css` and a horizontally scrollable table. The status filter must keep enum values in the request and show labels:

```html
<option th:each="value : ${statuses}" th:value="${value}"
        th:selected="${value == activeStatus}" th:text="${value.label}">OMS 승인 대기</option>
```

Render requested items and quantities from `AdminCustomerReturnDto.items`. Only `PENDING_APPROVAL` rows render:

```html
<form th:action="@{/admin/returns/{id}/approve(id=${row.id})}" method="post">
  <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
  <button class="app-btn" type="submit">승인</button>
</form>
<form th:action="@{/admin/returns/{id}/reject(id=${row.id})}" method="post">
  <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
  <input name="reason" maxlength="500" required aria-label="반려 사유">
  <button class="app-btn secondary" type="submit">반려</button>
</form>
```

The table columns must render `id`, `orderId`, `customerName`, item product names and quantities, `reason`, `statusLabel`, `requestedAt`, `reviewedBy`, `reviewedAt`, `rejectionReason`, and `failureReason`. Use `-` for nullable audit and failure values, and format timestamps as `yyyy-MM-dd HH:mm`.

Add the ADMIN-only `반품 관리` link to `fragments/layout.html` with active key `returns`.

- [ ] **Step 5: Run the admin MVC test and verify GREEN**

Run:

```bash
./gradlew test --tests com.jhg.hgpage.controller.admin.CustomerReturnAdminControllerMvcTest
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit the administrator workflow**

```bash
git add src/main/java/com/jhg/hgpage/oms/web/controller/CustomerReturnAdminController.java \
  src/main/resources/templates/admin/returns.html \
  src/main/resources/templates/fragments/layout.html \
  src/test/java/com/jhg/hgpage/controller/admin/CustomerReturnAdminControllerMvcTest.java
git commit -m "feat(admin): review customer returns"
```

---

### Task 5: 회귀 시나리오와 전체 검증

**Files:**
- Modify: `docs/manual-verification-scenarios.md`
- Test: all tests under `src/test/java`

**Interfaces:**
- Consumes the complete approved return flow from Tasks 1–4
- Produces executable manual evidence steps for OMS and WMS operators

- [ ] **Step 1: Update manual scenarios for the approval boundary**

Add a scenario before existing V2-1:

```markdown
### V2-0. OMS 승인·반려 게이트

1. 고객이 배송 완료 주문의 반품을 신청한다.
2. WMS에 RMA가 없고 OMS 반품 상태가 `PENDING_APPROVAL`인지 확인한다.
3. 한 건은 OMS에서 반려 사유와 함께 반려하고 고객 화면에서 사유를 확인한다.
4. 같은 수량을 다시 신청한 뒤 OMS에서 승인한다.
5. 승인 뒤에만 WMS RMA가 생성되고 OMS가 `REQUESTED`로 수렴하는지 확인한다.
```

Update common setup so every WMS RMA scenario includes OMS approval. Change V2-6 to stop WMS before the administrator approves, then verify `PENDING_SUBMISSION` and sweeper recovery after WMS restarts.

- [ ] **Step 2: Run diff hygiene and all tests fresh**

Run:

```bash
git diff --check
./gradlew test --rerun-tasks --no-daemon
```

Expected: no whitespace errors and `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 3: Inspect the final scope**

Run:

```bash
git status --short
git diff --stat HEAD~4
```

Confirm only OMS return approval code, templates, migration, tests, and manual verification documentation changed. Keep pre-existing `.DS_Store` and `.superpowers/` untracked.

- [ ] **Step 4: Commit the verification documentation**

```bash
git add docs/manual-verification-scenarios.md
git commit -m "docs(returns): add OMS approval scenarios"
```
