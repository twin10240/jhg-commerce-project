# Local OMS/WMS Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Secure and verify the local OMS/WMS integration without Railway, then push both repositories to `origin/master`.

**Architecture:** Keep the existing synchronous REST boundary. Add a dedicated Spring Security filter chain for the WMS-to-OMS replenishment callback, send matching Basic credentials from WMS, reuse existing resilience tests, and verify real PostgreSQL and two-app HTTP flows with local processes rather than adding test infrastructure.

**Tech Stack:** Java 17/21, Spring Boot 3.5, Spring Security 6, JUnit 5, MockMvc, MockRestServiceServer, Gradle, Docker PostgreSQL, PowerShell.

---

### Task 1: Create isolated worktrees

**Files:** No source changes.

- [ ] **Step 1: Inspect both repositories**

Run:

```powershell
git -C C:\study\jhg-commerce-project status --short --branch
git -C C:\study\jhg-wms-project status --short --branch
```

Expected: OMS is clean; WMS has only the existing untracked `.claude/` directory.

- [ ] **Step 2: Create feature worktrees**

Run the `superpowers:using-git-worktrees` workflow and create:

```text
C:\study\jhg-commerce-project\.worktrees\local-hardening
C:\study\jhg-wms-project\.worktrees\local-hardening
```

Use branch `codex/local-hardening` in each repository. Verify both worktrees start from their current local `master` commits.

### Task 2: Require Basic authentication on the OMS callback

**Files:**
- Modify: `src/test/java/com/jhg/hgpage/controller/api/ReplenishmentApiControllerMvcTest.java`
- Modify: `src/main/java/com/jhg/hgpage/config/SecurityConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application.yml`

- [ ] **Step 1: Write the failing MVC security tests**

Change callback requests that exercise controller behavior to use:

```java
.with(httpBasic("wms", "wms"))
```

Replace the anonymous-success test with two explicit security cases:

```java
@Test
void 인증_없는_콜백은_401이고_핸들러를_호출하지_않는다() throws Exception {
    mockMvc.perform(post("/api/replenishments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"productIds\":[1]}"))
            .andExpect(status().isUnauthorized());
    verifyNoInteractions(stockReplenishedHandler);
}

@Test
void Basic_인증된_콜백을_핸들러에_위임한다() throws Exception {
    mockMvc.perform(post("/api/replenishments")
                    .with(httpBasic("wms", "wms"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"productIds\":[1,2]}"))
            .andExpect(status().isOk());
    verify(stockReplenishedHandler).onReplenished(List.of(1L, 2L));
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.jhg.hgpage.controller.api.ReplenishmentApiControllerMvcTest"
```

Expected: anonymous request returns `200`, so the new `401` assertion fails.

- [ ] **Step 3: Add the dedicated callback filter chain**

In `SecurityConfig`, add an order-1 chain matching only `/api/replenishments` and move the existing web chain to order 2. Use Spring Security's native in-memory provider:

```java
@Bean
@Order(1)
SecurityFilterChain replenishmentCallback(HttpSecurity http,
        @Value("${oms.callback.user:wms}") String user,
        @Value("${oms.callback.password:wms}") String password) throws Exception {
    UserDetailsService users = new InMemoryUserDetailsManager(
            User.withUsername(user).password("{noop}" + password).roles("WMS").build());
    http.securityMatcher("/api/replenishments")
            .csrf(AbstractHttpConfigurer::disable)
            .userDetailsService(users)
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .httpBasic(withDefaults());
    return http.build();
}
```

Remove `/api/replenishments` from the general chain's CSRF exceptions and `permitAll` matchers.

- [ ] **Step 4: Add matching configuration**

Add to both OMS YAML files:

```yaml
oms:
  callback:
    user: ${OMS_CALLBACK_USER:wms}
    password: ${OMS_CALLBACK_PASSWORD:wms}
```

In test YAML, literal `wms` values are sufficient.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the command from Step 2. Expected: all callback MVC tests pass.

- [ ] **Step 6: Commit OMS callback protection**

```powershell
git add src/main/java/com/jhg/hgpage/config/SecurityConfig.java src/main/resources/application.yml src/test/resources/application.yml src/test/java/com/jhg/hgpage/controller/api/ReplenishmentApiControllerMvcTest.java
git commit -m "fix(oms): authenticate replenishment callbacks"
```

### Task 3: Send callback credentials from WMS

**Files:**
- Modify: `src/test/java/com/jhg/wms/client/OmsReplenishmentNotifierTest.java`
- Modify: `src/main/java/com/jhg/wms/client/OmsReplenishmentNotifier.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application.yml`

- [ ] **Step 1: Write the failing Authorization-header test**

Set test properties and require the Basic header:

```java
@TestPropertySource(properties = {
        "oms.base-url=http://oms-test",
        "oms.callback.user=wms",
        "oms.callback.password=wms"
})
```

```java
.andExpect(header("Authorization", "Basic d21zOndtcw=="))
```

- [ ] **Step 2: Run the focused WMS test and verify RED**

```powershell
.\gradlew.bat test --tests "com.jhg.wms.client.OmsReplenishmentNotifierTest"
```

Expected: the Authorization header assertion fails.

- [ ] **Step 3: Configure RestClient Basic authentication**

Extend the constructor with callback properties and use the installed RestClient feature:

```java
public OmsReplenishmentNotifier(RestClient.Builder builder,
        @Value("${oms.base-url}") String baseUrl,
        @Value("${oms.callback.user:wms}") String user,
        @Value("${oms.callback.password:wms}") String password) {
    this.restClient = builder.baseUrl(baseUrl)
            .defaultHeaders(headers -> headers.setBasicAuth(user, password))
            .build();
}
```

- [ ] **Step 4: Add WMS callback configuration**

Nest under the existing `oms` section in both YAML files:

```yaml
oms:
  base-url: ${OMS_BASE_URL:http://localhost:8080}
  callback:
    user: ${OMS_CALLBACK_USER:wms}
    password: ${OMS_CALLBACK_PASSWORD:wms}
```

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the command from Step 2. Expected: all notifier tests pass.

- [ ] **Step 6: Commit WMS callback credentials**

```powershell
git add src/main/java/com/jhg/wms/client/OmsReplenishmentNotifier.java src/main/resources/application.yml src/test/resources/application.yml src/test/java/com/jhg/wms/client/OmsReplenishmentNotifierTest.java
git commit -m "fix(wms): authenticate OMS callbacks"
```

### Task 4: Remove deprecated OMS test annotations

**Files:**
- Modify the seven test files returned by `rg -l "@MockBean" src/test/java`

- [ ] **Step 1: Replace imports and annotations mechanically**

Replace:

```java
import org.springframework.boot.test.mock.mockito.MockBean;
@MockBean
```

with:

```java
import org.springframework.test.context.bean.override.mockito.MockitoBean;
@MockitoBean
```

- [ ] **Step 2: Compile all OMS tests**

```powershell
.\gradlew.bat compileTestJava --rerun-tasks
```

Expected: compilation succeeds and no `MockBean ... marked for removal` warning remains.

- [ ] **Step 3: Commit the test cleanup**

Stage only the seven reported test files and commit:

```powershell
git commit -m "test: replace deprecated MockBean usage"
```

### Task 5: Verify resilience and idempotency checks

**Files:** No production changes expected.

- [ ] **Step 1: Run OMS failure and recovery tests**

```powershell
.\gradlew.bat test --rerun-tasks --tests "com.jhg.hgpage.adapter.WmsInventoryAdapterTest" --tests "com.jhg.hgpage.service.BackorderSweeperTest" --tests "com.jhg.hgpage.service.BackorderAllocatorTest"
```

Expected: retry-after-timeout, repeated-5xx fallback, idle sweep, active sweep, FIFO promotion, and insufficient-stock retention all pass.

- [ ] **Step 2: Run WMS idempotency and fulfillment tests**

```powershell
.\gradlew.bat test --rerun-tasks --tests "com.jhg.wms.web.ReplenishmentRequestControllerTest" --tests "com.jhg.wms.service.ReplenishmentRequestServiceTest" --tests "com.jhg.wms.service.PurchaseOrderServiceTest"
```

Expected: identical request keys converge, conflicting content is rejected, approval creates a purchase order, and receiving fulfills the linked request.

### Task 6: Verify OMS Flyway on local PostgreSQL

**Files:** No source changes expected.

- [ ] **Step 1: Start an isolated PostgreSQL 16 container**

Use a uniquely named local container and port, for example `task-oms-hardening-db` on `55432`, after verifying the name and port are unused.

- [ ] **Step 2: Start OMS with the prod profile against the empty database**

Set `SPRING_PROFILES_ACTIVE=prod`, `PGHOST=localhost`, `PGPORT=55432`, `PGDATABASE=oms_hardening`, `PGUSER=task`, `PGPASSWORD=task`, and start the app on an unused port. Expected logs:

```text
Successfully applied 4 migrations
Started HgpageApplication
```

- [ ] **Step 3: Query Flyway history**

Run in PostgreSQL:

```sql
select version, success from flyway_schema_history order by installed_rank;
```

Expected: versions `1`, `2`, `3`, `4`, all successful.

- [ ] **Step 4: Stop the temporary app and container**

Stop only the exact process and container created by this task. Report removal and recoverability.

### Task 7: Run local OMS/WMS end-to-end verification

**Files:** No source changes expected.

- [ ] **Step 1: Start WMS and OMS from the feature worktrees**

Use embedded/local H2 databases, WMS on `8081`, OMS on `8080`, and matching `wms.basic` and `oms.callback` credentials. Start processes hidden and retain their process IDs and log paths.

- [ ] **Step 2: Verify inventory read and replenishment request submission**

Use the OMS admin UI for request submission and WMS API/UI for observation. Confirm the request payload reaches WMS and the history returned to OMS matches.

- [ ] **Step 3: Verify request-key idempotency**

POST the same authenticated WMS request twice. Expected: first response `201`, second `200`, with the same request ID.

- [ ] **Step 4: Verify approval, receiving, callback, and promotion**

Create a backordered OMS order, approve its replenishment request in WMS, receive the linked purchase order, and confirm the OMS order becomes `ORDER` after the authenticated callback or the next sweep.

- [ ] **Step 5: Verify bad callback credentials**

POST to OMS callback with missing and invalid Basic credentials. Expected: `401` and no order-state change.

- [ ] **Step 6: Stop only the two processes started in Step 1**

Confirm ports `8080` and `8081` are released.

### Task 8: Full verification, merge, and push

**Files:** Documentation may be updated only if commands or behavior differ from the approved design.

- [ ] **Step 1: Run fresh full test suites**

OMS with JDK 17:

```powershell
.\gradlew.bat --no-daemon test --rerun-tasks
```

WMS with its configured JDK:

```powershell
.\gradlew.bat --no-daemon test --rerun-tasks
```

Expected: both builds succeed with zero failed tests.

- [ ] **Step 2: Review each repository**

Run `git status --short --branch`, `git diff --check`, `git diff master...HEAD`, and confirm WMS `.claude/` is absent from the commit set.

- [ ] **Step 3: Finish both feature branches**

Use `superpowers:finishing-a-development-branch`. Fast-forward each local `master` to its verified feature branch without reset, rebase, stash, or force operations.

- [ ] **Step 4: Push OMS and WMS**

Confirm upstreams and remote divergence, then run normal pushes:

```powershell
git -C C:\study\jhg-commerce-project push origin master
git -C C:\study\jhg-wms-project push origin master
```

If either remote has advanced, stop without pulling or force-pushing and report the non-fast-forward state.
