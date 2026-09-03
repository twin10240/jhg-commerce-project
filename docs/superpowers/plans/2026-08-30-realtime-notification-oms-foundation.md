# OMS Realtime Notification Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OMS가 사용자 연결용 단기 JWT를 발급하고 업무 상태 이벤트를 트랜잭셔널 Outbox로 유실 없이 실시간 서비스에 전달하게 한다.

**Architecture:** 기존 Spring 세션은 인증 원본으로 유지한다. 업무 서비스는 성공한 상태 전이와 같은 트랜잭션에서 Outbox 행을 추가하고, 별도 스케줄러가 행을 선점해 HMAC 서명 HTTP로 전달한다. Node 서비스 장애는 주문 트랜잭션에 전파하지 않는다.

**Tech Stack:** Java 17, Spring Boot 3.5.5, Spring Security, Spring Data JPA, Jackson, RestClient, H2(local/test), PostgreSQL(prod), Flyway

**Spec:** `docs/superpowers/specs/2026-08-30-realtime-notification-service-design.md`

## Global Constraints

- JWT 수명은 정확히 5분이며 알고리즘은 RS256이다.
- JWT에 OMS 세션 ID나 개인정보를 넣지 않는다.
- 업무 상태 변경과 Outbox 저장은 같은 트랜잭션이다.
- 외부 HTTP 호출은 업무 트랜잭션 안에서 실행하지 않는다.
- payload는 로컬 H2와 운영 PostgreSQL 호환을 위해 `TEXT`로 저장한다.
- 완료 Outbox는 7일 보존하고 미발행 Outbox는 자동 삭제하지 않는다.
- Kafka, RabbitMQ, Redis, CQRS를 추가하지 않는다.
- 기존 `USER`와 `ADMIN`, `OMSSESSION`, CSRF 정책을 재사용한다.

---

### Task 1: 실시간 연결 JWT 발급

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/java/com/jhg/hgpage/domain/dto/UserPrincipal.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/jhg/hgpage/realtime/auth/RealtimeJwtProperties.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/auth/RealtimeTokenService.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/web/RealtimeTokenApiController.java`
- Test: `src/test/java/com/jhg/hgpage/realtime/auth/RealtimeTokenServiceTest.java`
- Test: `src/test/java/com/jhg/hgpage/realtime/web/RealtimeTokenApiControllerMvcTest.java`

**Interfaces:**
- Produces: `RealtimeTokenService.TokenResponse issue(UserPrincipal principal, Instant now)`
- Produces: authenticated `POST /api/realtime/token` returning `{"token":"...","expiresAt":"..."}`

- [ ] **Step 1: Add the JOSE dependency and expose the principal role**

Add `implementation 'org.springframework.security:spring-security-oauth2-jose'` to `build.gradle`. Add this method to `UserPrincipal`:

```java
public Role getRole() {
    return role;
}
```

- [ ] **Step 2: Write failing token service tests**

Generate an RSA key pair inside the test. Assert the decoded JWT has `iss=oms`, `aud=realtime-service`, `sub=7`, `role=USER`, a nonblank UUID `jti`, and `exp-iat=300` seconds. Also assert passing a null principal fails.

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.auth.RealtimeTokenServiceTest"`

Expected: FAIL because `RealtimeTokenService` does not exist.

- [ ] **Step 3: Implement the minimum token service**

Bind these values without defaults that could become production secrets:

```yaml
realtime:
  jwt:
    issuer: ${REALTIME_JWT_ISSUER:oms}
    audience: ${REALTIME_JWT_AUDIENCE:realtime-service}
    private-key: ${REALTIME_JWT_PRIVATE_KEY:}
    ttl: 5m
```

`RealtimeTokenService.issue` must construct claims from the authenticated principal and sign with `NimbusJwtEncoder`. Parse the PEM key once at bean creation. When the private key is blank or invalid, fail the token request with a controlled `503` response rather than breaking unrelated OMS startup.

```java
public record TokenResponse(String token, Instant expiresAt) {}
```

- [ ] **Step 4: Run the token unit test**

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.auth.RealtimeTokenServiceTest"`

Expected: PASS.

- [ ] **Step 5: Write failing MVC security tests**

Test all of the following:

```text
anonymous POST              -> 403 or redirect according to the existing web chain, no token
USER + valid CSRF           -> 200 and JSON token
ADMIN + valid CSRF          -> 200 and role ADMIN claim
USER without CSRF           -> 403
configured key unavailable  -> 503 JSON response
```

Use `@WithMockUser` only for access checks; use a `UserPrincipal` authentication object when asserting `sub` and `role`.

- [ ] **Step 6: Implement the controller and run MVC tests**

```java
@PostMapping("/api/realtime/token")
public TokenResponse issue(@AuthenticationPrincipal UserPrincipal principal) {
    return tokenService.issue(principal, Instant.now());
}
```

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.web.RealtimeTokenApiControllerMvcTest"`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/main/java/com/jhg/hgpage/domain/dto/UserPrincipal.java src/main/java/com/jhg/hgpage/realtime src/main/resources/application.yml src/test/java/com/jhg/hgpage/realtime
git commit -m "feat(realtime): issue short-lived connection tokens"
```

### Task 2: Outbox 영속 모델과 이벤트 작성기

**Files:**
- Create: `src/main/java/com/jhg/hgpage/realtime/outbox/NotificationEventType.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/outbox/NotificationOutboxStatus.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/outbox/NotificationOutbox.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/outbox/NotificationOutboxRepository.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/outbox/NotificationEventWriter.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/outbox/NotificationEventPayload.java`
- Create: `src/main/resources/db/migration/V14__add_notification_outbox.sql`
- Test: `src/test/java/com/jhg/hgpage/realtime/outbox/NotificationEventWriterTest.java`
- Test: `src/test/java/com/jhg/hgpage/realtime/outbox/NotificationOutboxTest.java`
- Modify: `src/test/java/com/jhg/hgpage/FlywayMigrationTest.java`

**Interfaces:**
- Produces: `UUID NotificationEventWriter.append(NotificationEventType type, Long recipientId, String aggregateType, String aggregateId, Map<String, Object> data)`
- Produces: version 1 JSON matching the approved event contract

- [ ] **Step 1: Write failing entity transition tests**

Cover `pending -> processing -> published`, retry back to `pending`, stale processing recovery, terminal `failed`, and rejection of illegal transitions. Assert `attemptCount` increments only on claim.

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.outbox.NotificationOutboxTest"`

Expected: FAIL because the entity does not exist.

- [ ] **Step 2: Implement the enums and entity**

Define the 15 approved event names verbatim. `NotificationOutbox` owns all transition methods; callers must not set status fields directly.

```java
public enum NotificationOutboxStatus { PENDING, PROCESSING, PUBLISHED, FAILED }
```

Use an application-generated UUID for both `id` and `eventId`, `@Version Long version`, `@Column(columnDefinition = "text") String payload`, and explicit lengths matching migration columns. Do not use `@Lob`; PostgreSQL may map it to an OID instead of `TEXT` and break Flyway validation.

- [ ] **Step 3: Run entity tests**

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.outbox.NotificationOutboxTest"`

Expected: PASS.

- [ ] **Step 4: Write failing writer integration test**

Persist an event for member 7 and order 12. Deserialize `payload` with `ObjectMapper` and assert the exact envelope fields, UTC timestamp, aggregate values, and absence of name, email, phone, address, title, and body.

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.outbox.NotificationEventWriterTest"`

Expected: FAIL because the writer and repository do not exist.

- [ ] **Step 5: Implement payload, repository, writer, and migration**

```java
public record NotificationEventPayload(
        int schemaVersion, UUID eventId, NotificationEventType type, Instant occurredAt,
        Long recipientId, Aggregate aggregate, Map<String, Object> data) {
    public record Aggregate(String type, String id) {}
}
```

The writer creates one UUID, serializes the immutable payload with the injected `ObjectMapper`, saves one `PENDING` entity, and returns the event ID. Serialization failure must throw so the surrounding business transaction rolls back.

- [ ] **Step 6: Verify H2 and Flyway schemas**

Run:

```bash
./gradlew test --tests "com.jhg.hgpage.realtime.outbox.*"
./gradlew test --tests "com.jhg.hgpage.FlywayMigrationTest"
```

Expected: PASS on H2 entity creation and PostgreSQL Flyway migration validation.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/jhg/hgpage/realtime/outbox src/main/resources/db/migration/V14__add_notification_outbox.sql src/test/java/com/jhg/hgpage/realtime/outbox src/test/java/com/jhg/hgpage/FlywayMigrationTest.java
git commit -m "feat(realtime): persist notification outbox events"
```

### Task 3: OMS 업무 상태와 Outbox 연결

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/oms/service/PaymentService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderAllocationService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderCancellationService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/OrderService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/CustomerReturnService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/ReturnSyncService.java`
- Modify: `src/main/java/com/jhg/hgpage/oms/service/RefundService.java`
- Test: `src/test/java/com/jhg/hgpage/realtime/outbox/BusinessNotificationOutboxIntegrationTest.java`
- Modify: `src/test/java/com/jhg/hgpage/service/PaymentApprovalProcessorTest.java`
- Modify: `src/test/java/com/jhg/hgpage/service/OrderAllocationServiceTest.java`
- Modify: `src/test/java/com/jhg/hgpage/service/OrderCancellationServiceTest.java`
- Modify: `src/test/java/com/jhg/hgpage/service/RefundServiceTest.java`

**Interfaces:**
- Consumes: `NotificationEventWriter.append(...)` from Task 2
- Produces: one Outbox event for each actual customer-visible transition

- [ ] **Step 1: Write parameterized failing integration tests**

For each approved event, drive the public service method that owns the transaction and assert one event with the correct recipient and aggregate. Repeat idempotent WMS delivery and return callbacks and assert the count remains one.

Important cases:

```text
approval during cancellation       -> no PAYMENT_APPROVED
ordinary payment decline           -> PAYMENT_FAILED
initial allocation success         -> STOCK_ALLOCATED
backorder allocation success       -> STOCK_ALLOCATED
allocation false                   -> ORDER_BACKORDERED
syncShipment READY -> DELIVERED     -> SHIPMENT_STARTED and DELIVERY_COMPLETED
duplicate delivered callback       -> no second event
return current == target            -> no second event
refund manual review                -> REFUND_REVIEW_REQUIRED once
```

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.outbox.BusinessNotificationOutboxIntegrationTest"`

Expected: FAIL because services do not append events.

- [ ] **Step 2: Add the minimum event calls at successful transitions**

Inject one concrete `NotificationEventWriter` into each listed service. Do not introduce a domain-event bus or one-implementation interface. Capture the previous status before idempotent methods and append only when the customer-visible target is newly reached.

Update every test that manually constructs an affected service to pass a mocked `NotificationEventWriter`. Verify the expected event in the focused service tests instead of weakening existing assertions.

Use these aggregate mappings:

```text
payment/allocation/cancellation/shipment/delivery -> ORDER/{orderId}
return                                             -> RETURN/{returnId}
refund                                             -> REFUND/{refundRequestId}
```

Every `data` map includes `orderId`; return events also include `returnId`; refund events include `refundId` and integer `amount`.

- [ ] **Step 3: Run the integration and existing workflow tests**

Run:

```bash
./gradlew test --tests "com.jhg.hgpage.realtime.outbox.BusinessNotificationOutboxIntegrationTest"
./gradlew test --tests "com.jhg.hgpage.service.PaymentWorkflowIntegrationTest"
./gradlew test --tests "com.jhg.hgpage.service.ReturnSyncServiceTest"
./gradlew test --tests "com.jhg.hgpage.service.OrderServiceTest"
```

Expected: PASS with no duplicate callback regressions.

- [ ] **Step 4: Verify rollback atomicity**

Add a test that throws after appending inside a test transaction and assert neither the business mutation nor Outbox row commits. Add a serialization-failure test and assert the business state rolls back.

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.outbox.BusinessNotificationOutboxIntegrationTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jhg/hgpage/oms/service src/test/java/com/jhg/hgpage/realtime/outbox/BusinessNotificationOutboxIntegrationTest.java
git commit -m "feat(realtime): enqueue customer status notifications"
```

### Task 4: HMAC HTTP 발행기와 복구 스케줄러

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/jhg/hgpage/realtime/outbox/OutboxDeliveryClient.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/outbox/NotificationOutboxService.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/outbox/NotificationOutboxProcessor.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/outbox/NotificationOutboxSweeper.java`
- Test: `src/test/java/com/jhg/hgpage/realtime/outbox/OutboxDeliveryClientTest.java`
- Test: `src/test/java/com/jhg/hgpage/realtime/outbox/NotificationOutboxServiceTest.java`
- Test: `src/test/java/com/jhg/hgpage/realtime/outbox/NotificationOutboxSweeperTest.java`

**Interfaces:**
- Produces: `DeliveryResult OutboxDeliveryClient.deliver(UUID eventId, String payload, Instant now)`
- Produces: claim/process/recover methods used only by the sweeper and admin requeue

- [ ] **Step 1: Write failing HMAC client tests with a local HTTP server**

Capture the raw body and assert:

```text
X-OMS-Event-Id == body.eventId
X-OMS-Timestamp == supplied epoch seconds
X-OMS-Signature == "v1=" + hex(HMAC_SHA256(secret, timestamp + "." + rawBody))
```

Map `200/201` to success, `429/5xx` and I/O timeout to retryable, and other `4xx` to permanent failure.

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.outbox.OutboxDeliveryClientTest"`

Expected: FAIL because the client does not exist.

- [ ] **Step 2: Implement the client with JDK crypto and existing RestClient**

Use `javax.crypto.Mac`, `SecretKeySpec`, `HexFormat`, and the existing global Spring HTTP timeouts. Add only these settings:

```yaml
realtime:
  base-url: ${REALTIME_BASE_URL:http://localhost:3000}
  event-hmac-secret: ${REALTIME_EVENT_HMAC_SECRET:}
  outbox:
    enabled: ${REALTIME_OUTBOX_ENABLED:false}
    sweep-delay: ${REALTIME_OUTBOX_SWEEP_DELAY:1s}
    processing-timeout: ${REALTIME_OUTBOX_PROCESSING_TIMEOUT:1m}
```

Guard the sweeper and delivery client with `@ConditionalOnProperty(name = "realtime.outbox.enabled", havingValue = "true")`. Outbox writes remain active while delivery is disabled, so enabling the dispatcher later drains accumulated events. When enabled, a blank HMAC secret must fail OMS startup. Do not log the secret or full payload.

- [ ] **Step 3: Write failing claim, retry, stale recovery, and cleanup tests**

Reuse the existing `RetrySchedule`. Assert a batch limit of 50, retry delays of `1m, 5m, 30m, 2h`, terminal failure on the fifth failed delivery, stale lease recovery after one minute, and deletion only for `PUBLISHED` rows older than seven days.

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.outbox.NotificationOutboxServiceTest"`

Expected: FAIL.

- [ ] **Step 4: Implement service, processor, and sweeper**

The scheduled method performs only:

```java
service.recoverStale(now.minus(processingTimeout), now);
service.deletePublishedBefore(now.minus(Duration.ofDays(7)));
service.findDueIds(now, 50).forEach(processor::process);
```

`NotificationOutboxProcessor` uses separate transactions for claim and result application so no database lock is held during HTTP. A process crash after remote success is handled by stale recovery plus Node event idempotency.

- [ ] **Step 5: Run focused tests**

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.outbox.*"`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/jhg/hgpage/realtime/outbox src/main/resources/application.yml src/test/java/com/jhg/hgpage/realtime/outbox
git commit -m "feat(realtime): deliver outbox events with HMAC"
```

### Task 5: 실패 이벤트 운영 화면과 수동 재전송

**Files:**
- Create: `src/main/java/com/jhg/hgpage/realtime/web/NotificationOutboxAdminController.java`
- Create: `src/main/resources/templates/admin/notification-events.html`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Test: `src/test/java/com/jhg/hgpage/realtime/web/NotificationOutboxAdminControllerMvcTest.java`

**Interfaces:**
- Consumes: `NotificationOutboxService.requeueFailed(UUID id, Instant now)`
- Produces: `GET /admin/notification-events` and CSRF-protected `POST /admin/notification-events/{id}/retry`

- [ ] **Step 1: Write failing access and behavior tests**

Assert anonymous is redirected, USER gets 403, ADMIN sees only failed rows, a valid ADMIN retry changes one failed row to pending, a non-failed or missing ID returns the list with an error message, and missing CSRF returns 403.

- [ ] **Step 2: Implement the minimal controller and table**

Show event ID, type, aggregate, attempts, last error code, and creation time. Add one retry icon button with a tooltip and confirmation. Do not expose payload or HMAC data.

- [ ] **Step 3: Run MVC tests**

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.web.NotificationOutboxAdminControllerMvcTest"`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/jhg/hgpage/realtime/web/NotificationOutboxAdminController.java src/main/resources/templates/admin/notification-events.html src/main/resources/templates/fragments/layout.html src/test/java/com/jhg/hgpage/realtime/web/NotificationOutboxAdminControllerMvcTest.java
git commit -m "feat(admin): support failed notification retries"
```

### Task 6: OMS 전체 회귀와 운영 문서

**Files:**
- Modify: `README.md`
- Modify: `docs/manual-verification-scenarios.md`
- Create: `docs/contracts/realtime-event-v1.json`

**Interfaces:**
- Produces: the canonical sample consumed by the Node contract test

- [ ] **Step 1: Add exact configuration and key-generation instructions**

Document generating an RSA 2048 key pair with OpenSSL, multiline environment variable handling, `REALTIME_BASE_URL`, and HMAC secret generation. Never commit generated keys.

- [ ] **Step 2: Add contract and manual recovery scenarios**

The JSON fixture must be a valid `DELIVERY_COMPLETED` event. Add procedures for service-down recovery, duplicate delivery, invalid HMAC, token expiry, failed Outbox requeue, and seven-day cleanup boundary.

- [ ] **Step 3: Run the complete OMS suite**

Run:

```bash
./gradlew test
git diff --check
```

Expected: all tests PASS and no whitespace errors.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/manual-verification-scenarios.md docs/contracts/realtime-event-v1.json
git commit -m "docs(realtime): add notification operations guide"
```
