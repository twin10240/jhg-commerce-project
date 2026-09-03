# OMS Reservation Request Key Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Use an immutable order UUID, rather than the resettable numeric order ID, as the OMS↔WMS reservation lifecycle key.

**Architecture:** `Order.requestKey` is generated once and propagated through allocation/cancellation claims, inventory ports, adapters, shipment sync, and delivery callbacks. Numeric `orderId` remains a human-readable reference. Existing OMS rows are backfilled before enforcing uniqueness and non-nullability.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data JPA, H2 local DB, PostgreSQL/Flyway production DB, JUnit 5, MockWebServer

**Spec:** `../../../../jhg-wms-project/docs/oms-request-reservation-request-key.md`

## Global Constraints

- `requestKey` is a UUID generated exactly once when an order is created and is immutable afterward.
- WMS write/query response validation uses `requestKey`; `orderId` is not an integration identity.
- Requests without `requestKey` are not supported by a compatibility fallback.
- Existing local and production order rows are backfilled; historic OMS/WMS links are intentionally not reconstructed.

---

### Task 1: Order identity and database migration

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/oms/domain/Order.java`
- Modify: `src/main/resources/db/local-data-migration.sql`
- Create: `src/main/resources/db/migration/V15__add_order_request_key.sql`
- Test: `src/test/java/com/jhg/hgpage/domain/OrderTest.java`

**Interfaces:**
- Produces: `UUID Order.getRequestKey()` with a non-null value from `Order.createOrder(...)`.

- [x] Add a failing domain test asserting two newly created orders have distinct, non-null request keys.
- [x] Run `./gradlew test --tests com.jhg.hgpage.domain.OrderTest` and verify the missing API fails compilation.
- [x] Add the immutable UUID field and generate it in `createOrder`.
- [x] Add H2 and PostgreSQL idempotent backfill/constraint SQL.
- [x] Re-run the focused test.

### Task 2: Inventory contract and HTTP adapters

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/contract/InventoryPort.java`
- Modify: `src/main/java/com/jhg/hgpage/contract/InventoryQueryPort.java`
- Modify: `src/main/java/com/jhg/hgpage/wms/adapter/WmsInventoryAdapter.java`
- Modify: `src/main/java/com/jhg/hgpage/wms/adapter/WmsInventoryQueryAdapter.java`
- Test: `src/test/java/com/jhg/hgpage/adapter/WmsInventoryAdapterTest.java`
- Test: `src/test/java/com/jhg/hgpage/adapter/WmsInventoryQueryAdapterTest.java`

**Interfaces:**
- Produces: `reserveAll(UUID, Long, Map)`, `shipAll(UUID, Map)`, `releaseAll(UUID, Map)`, and `shipmentByRequestKey(UUID)`.

- [x] Change adapter tests first to require the UUID JSON/path contract and UUID response validation.
- [x] Run both adapter test classes and verify compilation fails on the old signatures.
- [x] Change the two ports, DTO records, and adapters with no order-ID fallback.
- [x] Re-run both adapter test classes.

### Task 3: Propagate request keys through workflows

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderAllocationService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/AllocationProcessor.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderCancellationService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/CancellationProcessor.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderService.java`
- Test: focused allocation, cancellation, and order-service tests that exercise these boundaries.

**Interfaces:**
- Produces: `AllocationCommand(UUID requestKey, int attemptNumber, Map quantities)` and `CancellationClaim(UUID requestKey, int attemptNumber, boolean releaseRequired, Map quantities)`.

- [x] Update focused tests first so wrong/missing UUID propagation fails.
- [x] Run the focused workflow tests and verify the expected failures.
- [x] Put `requestKey` in both claims and pass it through reserve/ship/release/shipment query calls.
- [x] Re-run focused workflow tests.

### Task 4: Delivery callback identity

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/oms/repository/OrderRepository.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/web/api/DeliveryEventApiController.java`
- Test: `src/test/java/com/jhg/hgpage/controller/api/DeliveryEventApiControllerMvcTest.java`
- Test: `src/test/java/com/jhg/hgpage/service/OrderServiceAdminTest.java`

**Interfaces:**
- Produces: `findByRequestKeyForUpdate(UUID)` and `markDelivered(UUID, Instant)`.

- [x] Update callback tests first: requestKey is required, orderId is informational, and lookup is by UUID.
- [x] Run the focused tests and verify failure against the old callback.
- [x] Add the locked UUID repository lookup and update service/controller records and logging.
- [x] Re-run focused callback tests.

### Task 5: Compile fallout, documentation, and integration verification

**Files:**
- Modify: remaining compile-failing tests and `src/main/java/com/jhg/hgpage/oms/README.md` only where old signatures remain.

**Interfaces:**
- Consumes: all UUID-based APIs from Tasks 1–4.

- [x] Run `rg` for old inventory lifecycle signatures and update remaining test fixtures.
- [x] Run `./gradlew test` in OMS.
- [x] Run `./gradlew test` in WMS.
- [x] Apply/verify the local OMS migration, start both services, and execute the five integration checks from the spec.
- [x] Run `git diff --check` and review the final diff against every spec section.
