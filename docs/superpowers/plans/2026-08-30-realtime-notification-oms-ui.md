# OMS Notification Inbox UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인증된 OMS 고객이 헤더에서 새 알림과 미확인 개수를 즉시 확인하고 90일 알림함에서 읽음 상태를 관리하게 한다.

**Architecture:** Thymeleaf는 실시간 서비스 공개 URL과 CSRF 값만 렌더링한다. 브라우저 JavaScript가 OMS에서 단기 JWT를 받아 Node REST API와 Socket.IO에 사용하며 토큰을 메모리에만 유지한다. 실시간 서비스 장애는 기존 페이지 탐색과 주문 기능을 차단하지 않는다.

**Tech Stack:** Spring MVC, Spring Security, Thymeleaf, browser Fetch API, Socket.IO browser client, plain CSS, Node.js built-in test runner

**Spec:** `docs/superpowers/specs/2026-08-30-realtime-notification-service-design.md`

## Global Constraints

- Customer notification UI is visible only to `ROLE_USER`.
- JWT remains in JavaScript memory and never enters localStorage, sessionStorage, cookies, URL, or DOM attributes.
- Member ID and room name are never sent by the UI.
- Render notification text with `textContent`; never inject notification HTML.
- Accept only relative links beginning with `/`; fall back to `/notifications` otherwise.
- The notification service failing must not block logout, navigation, ordering, payment, returns, or shipping admin pages.
- Use one shared script and stylesheet; do not copy notification logic into every template.
- Do not add a frontend framework, bundler, or client-side state library.

---

### Task 1: OMS notification page boundary and view configuration

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/jhg/hgpage/config/SecurityConfig.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/web/RealtimeViewAdvice.java`
- Create: `src/main/java/com/jhg/hgpage/realtime/web/NotificationPageController.java`
- Create: `src/main/resources/templates/notifications.html`
- Test: `src/test/java/com/jhg/hgpage/realtime/web/NotificationPageControllerMvcTest.java`

**Interfaces:**
- Produces: USER-only `GET /notifications`
- Produces: model value `realtimePublicUrl` for authenticated templates

- [ ] **Step 1: Write failing MVC access tests**

Assert anonymous redirects to login, USER gets the `notifications` view with configured `realtimePublicUrl`, and ADMIN gets 403. Assert the URL is absent from anonymous login/signup model rendering.

Run: `./gradlew test --tests "com.jhg.hgpage.realtime.web.NotificationPageControllerMvcTest"`

Expected: FAIL.

- [ ] **Step 2: Add public URL configuration and explicit USER authorization**

```yaml
realtime:
  public-url: ${REALTIME_PUBLIC_URL:http://localhost:3000}
```

Add `/notifications` to the existing USER matcher. `RealtimeViewAdvice` exposes only the public URL, not keys or secrets, and normalizes one trailing slash.

- [ ] **Step 3: Add the empty semantic page shell**

The page contains the shared nav, heading `알림`, unread filter control, `모두 읽음` command, an unframed list region with `aria-live="polite"`, empty state, loading state, and retry command. Do not hardcode sample notifications.

- [ ] **Step 4: Run MVC tests and commit**

Run:

```bash
./gradlew test --tests "com.jhg.hgpage.realtime.web.NotificationPageControllerMvcTest"
git add src/main/java/com/jhg/hgpage/config/SecurityConfig.java src/main/java/com/jhg/hgpage/realtime/web src/main/resources/application.yml src/main/resources/templates/notifications.html src/test/java/com/jhg/hgpage/realtime/web
git commit -m "feat(notifications): add customer inbox page"
```

Expected: PASS.

### Task 2: Token lifecycle and resilient API client

**Files:**
- Create: `src/main/resources/static/js/notification-client.js`
- Create: `src/test/js/notification-client.test.mjs`
- Modify: `src/main/resources/templates/fragments/layout.html`

**Interfaces:**
- Produces: `NotificationClient.start(root)` and `NotificationClient.stop()`
- Produces: authenticated `list`, `unreadCount`, `read`, and `readAll` methods used by Tasks 3-4

- [ ] **Step 1: Write failing Node built-in tests**

Mock `fetch`, timers, and a minimal DOM root. Assert:

```text
concurrent API calls share one in-flight token request
token request sends rendered CSRF header and POST
401 clears token, obtains one new token, retries once
repeated failure stops retrying and reports unavailable
token is held only in module closure
stop clears timers and disconnects socket
unsafe absolute or protocol-relative link becomes /notifications
```

Run: `node --test src/test/js/notification-client.test.mjs`

Expected: FAIL because the module does not exist.

- [ ] **Step 2: Add one notification root to the shared fragment**

Render only non-secret bootstrap data:

```html
<div sec:authorize="hasRole('USER')"
     id="notification-root"
     th:attr="data-realtime-url=${realtimePublicUrl},
              data-csrf-header=${_csrf.headerName},
              data-csrf-token=${_csrf.token}"></div>
```

Load `${realtimePublicUrl}/socket.io/socket.io.js` with `defer`, then the local `notification-client.js` with `defer`. Script load failure must leave ordinary nav usable.

- [ ] **Step 3: Implement the minimal in-memory client**

Use one module closure with `token`, `expiresAt`, `tokenPromise`, `socket`, and retry timer. Request a new token when absent or within 15 seconds of expiration. API requests use `Authorization: Bearer ${token}` and never `credentials: include` across origins.

Return typed plain objects after checking required response fields. Convert no server content to HTML. Surface a small `{ kind: 'unavailable' }` state instead of throwing into unrelated page code.

- [ ] **Step 4: Run the JavaScript tests and commit**

Run:

```bash
node --test src/test/js/notification-client.test.mjs
git add src/main/resources/static/js/notification-client.js src/main/resources/templates/fragments/layout.html src/test/js/notification-client.test.mjs
git commit -m "feat(notifications): add resilient realtime API client"
```

Expected: PASS.

### Task 3: Header unread badge and recent notification panel

**Files:**
- Modify: `src/main/resources/templates/fragments/layout.html`
- Modify: `src/main/resources/static/js/notification-client.js`
- Create: `src/main/resources/static/css/notifications.css`
- Modify: `src/test/js/notification-client.test.mjs`
- Create: `src/test/java/com/jhg/hgpage/template/NotificationTemplateContractTest.java`

**Interfaces:**
- Consumes: client methods from Task 2
- Produces: accessible notification trigger, unread badge, recent list panel, Socket.IO reconnect

- [ ] **Step 1: Add failing DOM and template contract tests**

Assert USER markup has one `알림` trigger with `aria-expanded`, an unread badge hidden at zero, a recent-list region, a link to `/notifications`, and no nested card. JavaScript tests assert initial unread fetch, safe recent item rendering, new-event prepend, badge increment, and duplicate notification ID suppression.

- [ ] **Step 2: Add compact header markup and shared CSS**

Use a text navigation command `알림` with a numeric badge because the project has no shared icon library. The panel is anchored to the nav, at most 360px wide, uses an 8px-or-smaller radius, and becomes a viewport-width sheet on narrow screens. Badge width is stable for `1` through `99+`.

- [ ] **Step 3: Implement recent-list behavior**

On startup fetch unread count. Fetch the first five notifications only when the panel first opens, then cache for that page lifetime. Render title, body, relative time, and unread state with DOM creation plus `textContent`. Clicking an item marks it read best-effort, adjusts the badge once, and follows the safe relative link.

- [ ] **Step 4: Implement Socket.IO lifecycle**

Connect with:

```js
io(realtimeUrl, {
  auth: { token },
  transports: ['websocket', 'polling'],
  reconnection: false
});
```

When the server disconnects at token expiration or connection fails, request a new token and reconnect with delays `1s, 2s, 5s, 10s, 30s`. Reset delay after a successful connection. Pause retries while `document.hidden`; resume on `visibilitychange`. Disconnect immediately when the shared logout form submits.

- [ ] **Step 5: Verify tests and commit**

Run:

```bash
node --test src/test/js/notification-client.test.mjs
./gradlew test --tests "com.jhg.hgpage.template.NotificationTemplateContractTest"
git add src/main/resources/templates/fragments/layout.html src/main/resources/static/css/notifications.css src/main/resources/static/js/notification-client.js src/test/js/notification-client.test.mjs src/test/java/com/jhg/hgpage/template/NotificationTemplateContractTest.java
git commit -m "feat(notifications): show realtime header alerts"
```

Expected: PASS.

### Task 4: Full inbox, cursor loading, and read actions

**Files:**
- Modify: `src/main/resources/templates/notifications.html`
- Modify: `src/main/resources/static/js/notification-client.js`
- Modify: `src/main/resources/static/css/notifications.css`
- Modify: `src/test/js/notification-client.test.mjs`
- Modify: `src/test/java/com/jhg/hgpage/template/NotificationTemplateContractTest.java`

**Interfaces:**
- Consumes: notification REST client from Task 2
- Produces: latest-first inbox with individual read, read-all, and cursor pagination

- [ ] **Step 1: Add failing inbox behavior tests**

Assert initial page limit 20, `nextCursor` load-more behavior, unread-only client filter, one row per ID, individual read idempotency, read-all cutoff response, empty state, API retry state, and `99+` badge cap. Also assert long unbroken body text wraps without horizontal overflow.

- [ ] **Step 2: Implement list rendering and cursor loading**

Render unframed rows separated by borders. Each row has title, body, timestamp, unread indicator, and safe link. Keep the load-more control dimension stable while loading. Do not use infinite scroll; one explicit `더 보기` command is easier to operate and test.

- [ ] **Step 3: Implement read actions**

Individual read updates the server first, then local DOM and badge. `모두 읽음` disables during the request and applies only after success. On failure keep the previous state and show an inline retryable status; do not optimistically lose unread counts.

- [ ] **Step 4: Run focused tests and responsive browser verification**

Run:

```bash
node --test src/test/js/notification-client.test.mjs
./gradlew test --tests "com.jhg.hgpage.template.NotificationTemplateContractTest"
```

With OMS and Node running, capture 1440x900 and 390x844 screenshots. Confirm no overlap between brand, nav, notification panel, badge, and logout; confirm long text wraps and touch targets remain usable.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/notifications.html src/main/resources/static/css/notifications.css src/main/resources/static/js/notification-client.js src/test/js/notification-client.test.mjs src/test/java/com/jhg/hgpage/template/NotificationTemplateContractTest.java
git commit -m "feat(notifications): complete the 90-day inbox UI"
```

### Task 5: Cross-service manual verification and documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/manual-verification-scenarios.md`
- Add screenshots under: `docs/portfolio/images/`

**Interfaces:**
- Consumes: completed OMS foundation and Node service plans
- Produces: portfolio evidence and repeatable end-to-end verification

- [ ] **Step 1: Add startup order and environment documentation**

Document PostgreSQL, Node on `3000`, OMS on `8080`, WMS on `8081`, RSA/HMAC environment variables, and the fact that OMS core flows remain available when Node is down.

- [ ] **Step 2: Execute the happy-path scenarios**

Verify payment approved, stock allocated, backordered then allocated, shipped, delivered, return requested/received/completed/rejected/cancelled, refund completed/review required, unread badge, individual read, read-all, and latest-first cursor loading.

- [ ] **Step 3: Execute isolation and recovery scenarios**

Verify another user's notification cannot be read, invalid JWT/HMAC is rejected, service-down events arrive once after recovery, duplicate event does not create a second row, JWT expiry reconnects without page reload, and logout stops the socket.

- [ ] **Step 4: Run all OMS checks**

Run:

```bash
node --test src/test/js/notification-client.test.mjs
./gradlew test
git diff --check
```

Expected: all checks PASS.

- [ ] **Step 5: Capture portfolio screenshots and commit**

Capture header recent alerts, full inbox, read/unread state, and one OMS order state beside its resulting alert. Add captions to the manual scenario document.

```bash
git add README.md docs/manual-verification-scenarios.md docs/portfolio/images
git commit -m "docs(notifications): record realtime verification evidence"
```
