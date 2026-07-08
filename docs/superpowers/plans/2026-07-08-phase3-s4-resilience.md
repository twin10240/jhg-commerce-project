# Phase 3 — S4 회복탄력성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WMS↔OMS 사이의 다운/hang/유실을 다루는 회복탄력성 — 타임아웃, 예약 재시도 1회, ship/release 실패 안내, 보상 스윕, WMS shipAll RELEASED 가드.

**Architecture:** 스펙 `docs/superpowers/specs/2026-07-08-phase3-s4-resilience-design.md` 참조. 타임아웃은 Spring Boot 3.4+의 `spring.http.client.*` 프로퍼티(코드 0줄)로 양쪽 앱의 자동구성 `RestClient.Builder` 전체에 적용. 보상 스윕은 기존 `BackorderAllocator.allocate` 재사용(트리거만 추가). 스키마 변경 없음.

**Tech Stack:** Spring Boot 3.5.5 양쪽. OMS = Java 17, `C:\study\jhg-commerce-project`. WMS = Java 21, `C:\study\jhg-wms-project`(gradle.properties에 JDK21 지정돼 있어 JAVA_HOME 불필요).

## Global Constraints

- OMS 빌드/테스트 전 `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"` (시스템 기본이 Java 8).
- OMS 작업은 브랜치 `feature/phase3-s4`(base: master), WMS 작업은 master 직접 커밋(S3와 동일).
- 타임아웃 값: `connect-timeout: 1s`, `read-timeout: 2s` (스펙 확정값).
- 스윕 주기 기본값: `60s`, 프로퍼티 키 `backorder.sweep-delay` (yml 등록 없이 `@Scheduled` 기본값으로만 — 오버라이드는 커맨드라인).
- 재시도는 예약 경로만 1회. ship/release/adjust에는 재시도 없음.
- 신규 테스트는 신세대 패턴(Mockito 단위 / `@WebMvcTest` / `@DataJpaTest` / `@RestClientTest`). 기존 테스트 파일에 추가할 때 기존 헬퍼를 재사용.
- 로컬에서 8080은 무관 프로세스가 점유 중 — 통합 검증 시 OMS는 8090으로 기동.

---

### Task 1: [WMS] shipAll RELEASED 가드 + 타임아웃 yml

**Files:**
- Modify: `C:\study\jhg-wms-project\src\main\java\com\jhg\wms\service\InventoryService.java` (shipAll, 78행 부근)
- Modify: `C:\study\jhg-wms-project\src\main\resources\application.yml`
- Test: `C:\study\jhg-wms-project\src\test\java\com\jhg\wms\service\InventoryServiceTest.java`

**Interfaces:**
- Consumes: 기존 `InventoryService.reserveAll/releaseAll/shipAll`, `ReservationStatus.RELEASED`
- Produces: shipAll이 RELEASED 예약 출고 시 `IllegalStateException` (기존 "예약 없음" 예외와 동일 타입 — WMS REST `/api/inventory/ship`은 기존처럼 500으로 표면화되지만 침묵 오염이 가시적 실패로 바뀌는 것이 목적)

- [ ] **Step 1: 실패하는 테스트 작성** — `InventoryServiceTest.java`의 `// ── 멱등성 ──` 섹션 끝에 추가:

```java
@Test
void shipAll_해제된_예약이면_예외를_던지고_재고는_불변이다() {
    // 취소 release가 처리됐는데 응답만 타임아웃난 반쪽 상태에서 출고가 들어온 시나리오 —
    // 가드 없으면 reservedQty가 음수로 내려가 가용수량이 부풀려진다(침묵 오염).
    seed(1L, 10);
    service.reserveAll(99L, Map.of(1L, 6));
    service.releaseAll(99L, Map.of(1L, 6));

    assertThatThrownBy(() -> service.shipAll(99L, Map.of(1L, 6)))
            .isInstanceOf(IllegalStateException.class);

    Inventory after = repo.findByProductIdIn(List.of(1L)).get(0);
    assertThat(after.getOnHandQty()).isEqualTo(10);
    assertThat(after.getReservedQty()).isEqualTo(0);
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd C:\study\jhg-wms-project; .\gradlew.bat test --tests "com.jhg.wms.service.InventoryServiceTest"`
Expected: FAIL — 새 테스트가 `reservedQty` -6(음수) 또는 예외 미발생으로 실패

- [ ] **Step 3: 가드 구현** — `InventoryService.shipAll`의 SHIPPED 조기 반환 직후에 1줄:

```java
    /** 예약분 출고. 이미 출고됐으면 no-op. 해제된 예약은 출고 거부(반쪽 상태 오염 방지). */
    @Transactional
    public void shipAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation reservation = reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("예약이 없어 출고할 수 없습니다. orderId=" + orderId));
        if (reservation.getStatus() == ReservationStatus.SHIPPED) return;
        if (reservation.getStatus() == ReservationStatus.RELEASED)
            throw new IllegalStateException("해제된 예약은 출고할 수 없습니다. orderId=" + orderId);
        inventoryRepository.findByProductIdIn(qtyByProductId.keySet())
                .forEach(inv -> inv.ship(qtyByProductId.get(inv.getProductId())));
        reservation.ship();
    }
```

- [ ] **Step 4: 통과 확인**

Run: `cd C:\study\jhg-wms-project; .\gradlew.bat test --tests "com.jhg.wms.service.InventoryServiceTest"`
Expected: PASS (기존 테스트 포함 전건)

- [ ] **Step 5: 타임아웃 yml** — `application.yml` 첫 문서의 `spring:` 블록에 추가(`h2:` 항목 아래, `server:` 위):

```yaml
  # S4: RestClient 공통 타임아웃 — OMS 콜백(OmsReplenishmentNotifier) hang 방지.
  # 자동구성 RestClient.Builder 전체에 적용(Spring Boot 3.4+).
  http:
    client:
      connect-timeout: 1s
      read-timeout: 2s
```

- [ ] **Step 6: 전체 빌드 + 커밋 + push**

```
cd C:\study\jhg-wms-project
.\gradlew.bat build
git add src/main/java/com/jhg/wms/service/InventoryService.java src/test/java/com/jhg/wms/service/InventoryServiceTest.java
git commit -m "fix(wms): shipAll에 RELEASED 가드 — 해제된 예약 출고로 인한 reservedQty 음수 오염 방지"
git add src/main/resources/application.yml
git commit -m "feat(wms): RestClient 타임아웃 1s/2s — OMS 콜백 hang 시 무기한 블록 방지"
git push
```
Expected: BUILD SUCCESSFUL, push 성공

---

### Task 2: [OMS] 타임아웃 yml + reserveAll 재시도 1회

**Files:**
- Modify: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\wms\adapter\WmsInventoryAdapter.java`
- Modify: `C:\study\jhg-commerce-project\src\main\resources\application.yml`
- Test: `C:\study\jhg-commerce-project\src\test\java\com\jhg\hgpage\adapter\WmsInventoryAdapterTest.java`

**Interfaces:**
- Consumes: `InventoryPort.reserveAll(Long orderId, Map<Long,Integer>)` 시그니처 불변, WMS reserveAll의 orderId 멱등(기존 orderId면 상태 기반 반환 — 재시도 안전 근거)
- Produces: `reserveAll`이 `ResourceAccessException` 시 1회 재시도 후 실패면 `false`(→BACKORDERED 강등). 동작 시그니처 불변 — 호출부(`OrderAllocationService`) 수정 없음

- [ ] **Step 1: 실패하는 테스트 작성** — `WmsInventoryAdapterTest.java`에 추가 (import 추가: `java.net.ConnectException`, `java.net.SocketTimeoutException`, `org.springframework.test.web.client.response.MockRestResponseCreators.withException`):

```java
// ── S4: 재시도/강등 ──────────────────────────────────────────

@Test
void reserve_통신실패면_1회_재시도하고_성공하면_true() {
    // 첫 요청이 실제로는 WMS에 닿았어도 orderId 멱등이라 재시도는 같은 결과로 수렴한다.
    server.expect(requestTo("http://wms-test/api/inventory/reserve"))
          .andRespond(withException(new SocketTimeoutException("read timeout")));
    server.expect(requestTo("http://wms-test/api/inventory/reserve"))
          .andRespond(withSuccess("true", MediaType.APPLICATION_JSON));

    boolean result = adapter.reserveAll(1L, Map.of(1L, 3));

    assertThat(result).isTrue();
    server.verify(); // 정확히 2회 호출
}

@Test
void reserve_재시도까지_실패하면_false로_강등한다() {
    server.expect(requestTo("http://wms-test/api/inventory/reserve"))
          .andRespond(withException(new ConnectException("refused")));
    server.expect(requestTo("http://wms-test/api/inventory/reserve"))
          .andRespond(withException(new ConnectException("refused")));

    boolean result = adapter.reserveAll(1L, Map.of(1L, 3));

    assertThat(result).isFalse();
    server.verify();
}
```

> 성공 시 재시도 없음(1회 호출)은 기존 `reserve_WMS에_POST_요청을_보내고_결과를_반환한다` 테스트의 `server.verify()`가 이미 핀한다(기대 1건 등록 + verify).

- [ ] **Step 2: 실패 확인**

Run: `cd C:\study\jhg-commerce-project; $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat test --tests "com.jhg.hgpage.adapter.WmsInventoryAdapterTest"`
Expected: FAIL — 재시도 테스트가 "1회만 호출됨(기대 미충족)" 또는 첫 예외에서 false 반환으로 실패

- [ ] **Step 3: 재시도 구현** — `WmsInventoryAdapter.reserveAll`을 교체:

```java
    @Override
    public boolean reserveAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        try {
            return doReserve(orderId, qtyByProductId);
        } catch (ResourceAccessException first) {
            // 일시적 blip 구제 — orderId 멱등이라 첫 요청이 실제 예약됐어도 재시도가 같은 결과로 수렴(S4).
            log.warn("WMS 예약 통신 실패 — 1회 재시도: orderId={}", orderId);
            try {
                return doReserve(orderId, qtyByProductId);
            } catch (ResourceAccessException second) {
                // ponytail: WMS 다운 시 false → BACKORDERED 접수("예약 못 해본 백오더"). 회수는 보상 스윕.
                log.warn("WMS 예약 재시도 실패 — BACKORDERED로 접수: orderId={}", orderId);
                return false;
            }
        }
    }

    private boolean doReserve(Long orderId, Map<Long, Integer> qtyByProductId) {
        Boolean result = restClient.post()
                .uri("/api/inventory/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new WriteRequest(orderId, qtyByProductId))
                .retrieve()
                .body(Boolean.class);
        return Boolean.TRUE.equals(result);
    }
```

- [ ] **Step 4: 통과 확인**

Run: `cd C:\study\jhg-commerce-project; $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat test --tests "com.jhg.hgpage.adapter.WmsInventoryAdapterTest"`
Expected: PASS (기존 5건 + 신규 2건)

- [ ] **Step 5: 타임아웃 yml** — OMS `application.yml` 첫 문서의 `spring:` 블록에 추가(`logging:` 항목 아래, `wms:` 위 — 들여쓰기 주의, `spring:`의 자식):

```yaml
  # S4: WMS REST 호출 공통 타임아웃 — WMS hang 시 무기한 블록 방지.
  # 자동구성 RestClient.Builder 전체(어댑터 3종)에 적용(Spring Boot 3.4+).
  http:
    client:
      connect-timeout: 1s
      read-timeout: 2s
```

- [ ] **Step 6: 커밋**

```
git add src/main/java/com/jhg/hgpage/wms/adapter/WmsInventoryAdapter.java src/test/java/com/jhg/hgpage/adapter/WmsInventoryAdapterTest.java src/main/resources/application.yml
git commit -m "feat(oms): WMS 타임아웃 1s/2s + 예약 1회 재시도 — 강등 완성(hang→타임아웃→BACKORDERED)"
```

---

### Task 3: [OMS] GlobalExceptionHandler에 ResourceAccessException 핸들러

**Files:**
- Modify: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\exception\GlobalExceptionHandler.java`
- Test: `C:\study\jhg-commerce-project\src\test\java\com\jhg\hgpage\controller\order\OrderControllerMvcTest.java` (화면 flash)
- Test: `C:\study\jhg-commerce-project\src\test\java\com\jhg\hgpage\controller\api\ReplenishmentApiControllerMvcTest.java` (API 503)

**Interfaces:**
- Consumes: 기존 `isApiRequest`/`problem` private 헬퍼, `OrderService.cancelOrder`(컨트롤러가 `IllegalStateException`만 catch — `ResourceAccessException`은 전파됨), `StockReplenishedHandler.onReplenished`
- Produces: 화면 → flash `errorMessage` + `redirect:/main`, `/api/**` → 503 ProblemDetail

- [ ] **Step 1: 실패하는 테스트 2건 작성**

`OrderControllerMvcTest.java`의 취소 테스트들 옆에 추가 (import 추가: `org.springframework.web.client.ResourceAccessException`):

```java
@Test
void 취소_중_WMS_통신이_실패하면_main으로_리다이렉트하고_에러_flash를_담는다() throws Exception {
    doThrow(new ResourceAccessException("WMS down"))
            .when(orderService).cancelOrder(10L, 1L);

    mockMvc.perform(post("/orders/10/cancel")
                    .with(user(principal()))
                    .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/main"))
            .andExpect(flash().attributeExists("errorMessage"));
}
```

`ReplenishmentApiControllerMvcTest.java`에 추가 (import 추가: `org.springframework.web.client.ResourceAccessException`, `static org.mockito.Mockito.doThrow`(기존 `Mockito.*`에 포함돼 있으면 생략)):

```java
@Test
void 승격_중_WMS_통신이_실패하면_503을_반환한다() throws Exception {
    doThrow(new ResourceAccessException("WMS down"))
            .when(stockReplenishedHandler).onReplenished(any());

    mockMvc.perform(post("/api/replenishments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"productIds\":[1]}"))
            .andExpect(status().isServiceUnavailable());
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd C:\study\jhg-commerce-project; $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat test --tests "com.jhg.hgpage.controller.order.OrderControllerMvcTest" --tests "com.jhg.hgpage.controller.api.ReplenishmentApiControllerMvcTest"`
Expected: FAIL — 미처리 예외로 500 (redirect/503 아님)

- [ ] **Step 3: 핸들러 구현** — `GlobalExceptionHandler`에 추가 (import 추가: `org.springframework.web.client.ResourceAccessException`). `handleOptimisticLockingFailure` 아래에:

```java
    // WMS 통신 실패(연결 거부·타임아웃): 조회 어댑터는 자체 폴백(빈 맵/빈 목록)하므로
    // 여기 도달하는 것은 쓰기 경로(ship/release/adjust/발주)다. 트랜잭션은 롤백돼 있다.
    @ExceptionHandler(ResourceAccessException.class)
    public Object handleResourceAccess(ResourceAccessException e, HttpServletRequest request) {
        if (isApiRequest(request)) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "창고 시스템과 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }

        RequestContextUtils.getOutputFlashMap(request)
                .put("errorMessage", "창고 시스템과 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        return new ModelAndView("redirect:/main");
    }
```

- [ ] **Step 4: 통과 확인**

Run: Step 2와 동일 명령
Expected: PASS

- [ ] **Step 5: 커밋**

```
git add src/main/java/com/jhg/hgpage/exception/GlobalExceptionHandler.java src/test/java/com/jhg/hgpage/controller/order/OrderControllerMvcTest.java src/test/java/com/jhg/hgpage/controller/api/ReplenishmentApiControllerMvcTest.java
git commit -m "feat(oms): WMS 통신 실패 전역 처리 — 화면 flash 안내, API 503"
```

---

### Task 4: [OMS] OrderRepositoryQuery.findBackorderedProductIds

**Files:**
- Modify: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\oms\repository\OrderRepositoryQuery.java`
- Test: `C:\study\jhg-commerce-project\src\test\java\com\jhg\hgpage\repository\OrderRepositoryBackorderTest.java`

**Interfaces:**
- Consumes: QueryDSL `order`/`orderItem` Q타입(기존 static import), `OrderStatus.BACKORDERED`
- Produces: `public List<Long> findBackorderedProductIds()` — BACKORDERED 주문이 담은 상품 id를 중복 없이 반환. Task 5의 `BackorderSweeper`가 소비

- [ ] **Step 1: 실패하는 테스트 작성** — `OrderRepositoryBackorderTest.java`에 추가(기존 `newProduct`/`saveBackorder`/`saveOrdered` 헬퍼 재사용):

```java
@Test
void 백오더_주문의_상품id만_중복없이_반환한다() {
    Product scarce = newProduct("부족상품");
    Product other = newProduct("다른상품");
    Product plenty = newProduct("여유상품");

    saveBackorder(OrderItem.createOrderItem(scarce, 10000, 5),
                  OrderItem.createOrderItem(other, 10000, 2));
    saveBackorder(OrderItem.createOrderItem(scarce, 10000, 3)); // scarce 중복 — distinct 검증
    saveOrdered(OrderItem.createOrderItem(plenty, 10000, 1));   // ORDER — 제외 검증
    em.flush();
    em.clear();

    List<Long> result = orderRepositoryQuery.findBackorderedProductIds();

    assertThat(result).containsExactlyInAnyOrder(scarce.getId(), other.getId());
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd C:\study\jhg-commerce-project; $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat test --tests "com.jhg.hgpage.repository.OrderRepositoryBackorderTest"`
Expected: FAIL — 컴파일 에러("findBackorderedProductIds 심볼을 찾을 수 없음")

- [ ] **Step 3: 쿼리 구현** — `OrderRepositoryQuery.java`의 `findBackordersContaining` 아래에 추가:

```java
    /** 보상 스윕(S4)용 — BACKORDERED 주문이 기다리는 상품 id 목록(중복 제거). */
    public List<Long> findBackorderedProductIds() {
        return jpaQueryFactory.select(orderItem.product.id).distinct()
                .from(order)
                .join(order.orderItems, orderItem)
                .where(order.status.eq(OrderStatus.BACKORDERED))
                .fetch();
    }
```

- [ ] **Step 4: 통과 확인**

Run: Step 2와 동일 명령
Expected: PASS (기존 3건 + 신규 1건)

- [ ] **Step 5: 커밋**

```
git add src/main/java/com/jhg/hgpage/oms/repository/OrderRepositoryQuery.java src/test/java/com/jhg/hgpage/repository/OrderRepositoryBackorderTest.java
git commit -m "feat(oms): 백오더 상품id 조회 쿼리 — 보상 스윕용"
```

---

### Task 5: [OMS] BackorderSweeper + @EnableScheduling

**Files:**
- Create: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\oms\service\BackorderSweeper.java`
- Create: `C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\config\SchedulingConfig.java`
- Test: `C:\study\jhg-commerce-project\src\test\java\com\jhg\hgpage\service\BackorderSweeperTest.java`

**Interfaces:**
- Consumes: Task 4의 `OrderRepositoryQuery.findBackorderedProductIds()`, 기존 `BackorderAllocator.allocate(Collection<Long>) : int`(public `@Transactional`)
- Produces: 60초(기본)마다 백오더 재할당을 트리거하는 스케줄 잡. 외부 소비자 없음

- [ ] **Step 1: 실패하는 테스트 작성** — 신규 파일 `BackorderSweeperTest.java`:

```java
package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import com.jhg.hgpage.oms.service.BackorderAllocator;
import com.jhg.hgpage.oms.service.BackorderSweeper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BackorderSweeperTest {

    OrderRepositoryQuery orderRepositoryQuery = mock(OrderRepositoryQuery.class);
    BackorderAllocator backorderAllocator = mock(BackorderAllocator.class);
    BackorderSweeper sweeper = new BackorderSweeper(orderRepositoryQuery, backorderAllocator);

    @Test
    void 백오더_상품이_있으면_재할당을_트리거한다() {
        when(orderRepositoryQuery.findBackorderedProductIds()).thenReturn(List.of(1L, 2L));

        sweeper.sweep();

        verify(backorderAllocator).allocate(List.of(1L, 2L));
    }

    @Test
    void 백오더가_없으면_재할당을_호출하지_않는다() {
        // WMS 호출 0 보장 — 스윕이 유휴 상태에서 트래픽을 만들지 않는다.
        when(orderRepositoryQuery.findBackorderedProductIds()).thenReturn(List.of());

        sweeper.sweep();

        verify(backorderAllocator, never()).allocate(any());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd C:\study\jhg-commerce-project; $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat test --tests "com.jhg.hgpage.service.BackorderSweeperTest"`
Expected: FAIL — 컴파일 에러("BackorderSweeper 심볼을 찾을 수 없음")

- [ ] **Step 3: 구현** — 신규 파일 2개:

`src/main/java/com/jhg/hgpage/oms/service/BackorderSweeper.java`:

```java
package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 보상 스윕(S4) — 콜백 유실(OMS 다운·통지 타임아웃)과 "예약 못 해본 백오더"(WMS 다운 중 접수)를
 * 주기적으로 회수한다. 승격 정책은 BackorderAllocator를 그대로 재사용 — 트리거만 둘(콜백/스케줄).
 * 스윕과 콜백이 같은 주문을 동시 승격 시도해도 WMS 예약 원장 orderId 멱등으로 같은 결과에 수렴한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackorderSweeper {

    private final OrderRepositoryQuery orderRepositoryQuery;
    private final BackorderAllocator backorderAllocator;

    // initialDelay = 주기와 동일: 기동 직후(풀 컨텍스트 테스트 포함) 발화 방지
    @Scheduled(fixedDelayString = "${backorder.sweep-delay:60s}",
               initialDelayString = "${backorder.sweep-delay:60s}")
    public void sweep() {
        List<Long> productIds = orderRepositoryQuery.findBackorderedProductIds();
        if (productIds.isEmpty()) {
            return; // 백오더 없음 — WMS 호출 0
        }
        int promoted = backorderAllocator.allocate(productIds);
        if (promoted > 0) {
            log.info("보상 스윕: 백오더 {}건 승격", promoted);
        }
    }
}
```

`src/main/java/com/jhg/hgpage/config/SchedulingConfig.java`:

```java
package com.jhg.hgpage.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** S4 보상 스윕(BackorderSweeper)용 스케줄링 활성화. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

- [ ] **Step 4: 통과 확인 + 전체 빌드**

Run: `cd C:\study\jhg-commerce-project; $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat build`
Expected: BUILD SUCCESSFUL (스케줄링 활성화로 풀 컨텍스트 테스트가 깨지지 않는지 전체 스위트로 확인 — initialDelay 60s라 테스트 중 발화 없음)

- [ ] **Step 5: 커밋**

```
git add src/main/java/com/jhg/hgpage/oms/service/BackorderSweeper.java src/main/java/com/jhg/hgpage/config/SchedulingConfig.java src/test/java/com/jhg/hgpage/service/BackorderSweeperTest.java
git commit -m "feat(oms): 보상 스윕 — 60초마다 백오더 재할당, 콜백 유실 회수"
```

---

### Task 6: [통합] 두 앱 동시 기동 수동 검증

**Files:** 없음(검증만). 문제 발견 시 superpowers:systematic-debugging으로 원인을 잡고 수정 후 재실행.

**사전 조건:**
- H2 TCP 서버 기동 상태 (OMS `~/hgpage`, WMS `~/jhg-wms`)
- **8080은 무관 프로세스(Douzone) 점유 — OMS는 8090으로 기동**하고 WMS에 `--oms.base-url`을 오버라이드한다
- 검증 가속: OMS 스윕 주기를 5s로 오버라이드

기동 명령:

```
# WMS (터미널 1)
cd C:\study\jhg-wms-project
.\gradlew.bat bootRun --args='--oms.base-url=http://localhost:8090'

# OMS (터미널 2)
cd C:\study\jhg-commerce-project
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat bootRun --args='--server.port=8090 --backorder.sweep-delay=5s'
```

- [ ] **V1 — 타임아웃 프로퍼티 실적용 검증**: OMS만 read-timeout을 1ms로 재기동(`--spring.http.client.read-timeout=1ms` 추가, WMS는 정상 기동 상태) → `http://localhost:8090/main` 로드 → 상품 카드 전부 "가용수량 0"(품절/입고대기 표시) + OMS 로그에 `WMS 연결 실패 — 가용수량 0으로 폴백` warn 확인.
  Expected: 정상 WMS에도 1ms 타임아웃으로 조회가 실패 = `spring.http.client.*`가 RestClient에 실제 적용됨을 증명.
  **실패 시(타임아웃이 안 걸리면)**: 프로퍼티가 자동구성에 안 먹는 것 — `config/`에 `RestClientCustomizer` 빈 폴백을 구현한다:

```java
package com.jhg.hgpage.config;

import org.springframework.boot.autoconfigure.web.client.RestClientCustomizer;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** spring.http.client.* 프로퍼티 미적용 시 폴백 — 모든 RestClient.Builder에 타임아웃 적용. */
@Configuration
public class RestClientTimeoutConfig {

    @Bean
    public RestClientCustomizer timeoutCustomizer() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(1))
                .withReadTimeout(Duration.ofSeconds(2));
        return builder -> builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
    }
}
```

  (WMS에도 동일 증상이면 같은 폴백을 WMS에 적용.) 검증 후 1ms 오버라이드를 빼고 정상 기동으로 복귀.

- [ ] **V2 — WMS 다운 중 주문 → 즉시 강등 접수**: WMS 종료(터미널 1 Ctrl+C) → OMS 메인에서 상품 주문 → 주문이 거부 없이 접수되고 상세/내 주문에서 `BACKORDERED` 표시, 응답 소요가 수 초 내(재시도 1회 포함 — connect-timeout 1s × 2회 이내 + α).
  Expected: 접수 성공 + BACKORDERED + OMS 로그에 "WMS 예약 통신 실패 — 1회 재시도" → "재시도 실패 — BACKORDERED로 접수" warn 2줄.

- [ ] **V3 — WMS 복구 → 스윕이 자동 승격**: WMS 재기동(--oms.base-url 오버라이드 포함, V2 상품의 가용재고가 충분한지 WMS 관리 화면 `http://localhost:8081/admin/inventory`에서 확인, 부족하면 조정으로 확보) → 아무 조작 없이 대기 → 5s 주기 스윕이 V2 주문을 승격.
  Expected: ≤10초 내 OMS 로그 `보상 스윕: 백오더 1건 승격` + 주문 상세가 ORDER로 전환. (콜백이 아니라 스윕이 승격했음 — 재고 증가 이벤트가 없었으므로.)

- [ ] **V4 — WMS 다운 중 취소 → flash 안내 + 상태 불변**: ORDER 상태 주문을 하나 확보(정상 상태에서 주문) → WMS 종료 → 주문 상세에서 취소 → flash "창고 시스템과 통신하지 못했습니다..." + 주문이 여전히 ORDER(롤백 확인).
  Expected: 에러 페이지(500) 아님, /main 리다이렉트 + flash. WMS 재기동 후 같은 주문 취소 재시도 → 성공(CANCEL).

- [ ] **V5 — 정상 경로 회귀**: 두 앱 정상 기동 → 주문(ORDER 즉시 확정) → WMS 재고조정 +로 콜백 승격 경로 동작(백오더 하나 만들어서) → 발주→입고→승격도 1회 확인.
  Expected: S3까지의 기존 흐름 전부 정상.

- [ ] **마무리**: 검증 중 만든 테스트 주문·재고를 원상 복구할 필요는 없음(로컬 학습 DB). V1에서 폴백 Customizer를 구현했다면 커밋:

```
git add src/main/java/com/jhg/hgpage/config/RestClientTimeoutConfig.java
git commit -m "feat(oms): RestClient 타임아웃 Customizer 폴백 — spring.http.client 프로퍼티 미적용 환경 대응"
```

---

### Task 7: [문서] CLAUDE.md·README 갱신 + 병합

**Files:**
- Modify: `C:\study\jhg-commerce-project\CLAUDE.md`
- Modify: `C:\study\jhg-wms-project\README.md`

- [ ] **Step 1: OMS CLAUDE.md 갱신** — 세 곳:

① 로드맵 S3 완료 항목 끝의 `남은 것 = S4: WMS 다운 회복탄력성(보상 스윕·타임아웃/재시도).` 문장을 삭제하고, S3 항목 뒤에 추가:

```markdown
  **S4 완료(2026-07-08)**: 회복탄력성 — ① 타임아웃: 양쪽 앱 `spring.http.client.connect-timeout: 1s`/`read-timeout: 2s`(yml 2줄, 자동구성 RestClient.Builder 전체 적용)로 hang 무기한 블록 소멸 ② 예약 재시도: `WmsInventoryAdapter.reserveAll`이 `ResourceAccessException` 시 1회 재시도 후 실패면 false→BACKORDERED 강등("예약 못 해본 백오더" — WMS orderId 멱등 원장 덕에 재시도 안전) ③ ship/release/발주 실패: `GlobalExceptionHandler`에 `ResourceAccessException` 핸들러(화면 flash+redirect:/main, API 503) ④ 보상 스윕: `BackorderSweeper` `@Scheduled`(기본 60s, `backorder.sweep-delay`)가 BACKORDERED 상품id를 모아 `BackorderAllocator.allocate` 재사용 호출 — 콜백 유실·WMS 다운 중 접수분 회수, 스윕/콜백 동시 승격은 orderId 멱등으로 안전 ⑤ WMS `shipAll`에 RELEASED 가드 — 해제된 예약 출고(reservedQty 음수 오염) 차단. 스키마 변경 없음. 두 앱 통합 검증 통과(타임아웃 실적용·강등 접수·스윕 승격·취소 flash·정상 회귀). 설계/플랜: `docs/superpowers/{specs,plans}/2026-07-08-phase3-s4-*`.
```

② `알려진 한계 (의도된 동작 — 추후 정책 재검토 대상)` 섹션에 추가:

```markdown
23. **관리자 재고조정 경로의 중첩 체인 false-timeout**: OMS→WMS adjust(외곽 read 2s)의 응답은 WMS의 afterCommit 통지(→OMS 동기 승격→WMS reserve)가 끝난 뒤에 나가므로, 승격 대상이 있으면 내부 체인이 외곽 2s를 넘겨 조정은 커밋됐는데 관리자에겐 에러로 보일 수 있다. adjust는 비멱등이라 재시도 시 +delta 이중 적용 위험. 빈도 낮음(WMS가 건강하면 내부 체인도 빠름). 근본 회피는 통지 @Async 분리 — 필요해지면 도입.
```

③ `개선 우선순위` 1번·1-1을 교체(1-1을 1로 승격):

```markdown
1. 운영(Railway) 배포 시 정합성: `wms.base-url`·`oms.base-url`(WMS→OMS 콜백) 환경변수화(현재 prod에서도 localhost), WMS 앱 prod 프로파일·Dockerfile 신설, Flyway V3(OMS DB에서 inventory·reservation·purchase_order* DROP) 작성. Phase 3 전체 완료(S0~S4) — 재고·발주는 WMS 단일 진실 공급원, 콜백+보상 스윕으로 백오더 승격, 타임아웃/재시도/강등으로 장애 대응.
2. (선택) Phase 4 — REST → 이벤트/메시지 기반 전환(콜백·통지를 브로커로).
3. (선택) Phase 2 잔여 — 컨트롤러·DTO의 컨텍스트별 분리 정리.
4. 운영 배포 단계 시 `update` 대신 Flyway 마이그레이션 도입 검토(#15 H2 콘솔·`/api/replenishments` 보호 포함).
```

(기존 2·3번 항목은 위 목록으로 흡수 — 원문과 대조해 누락 없이 교체할 것.)

- [ ] **Step 2: WMS README 갱신** — `### OMS 재고보충 통지 (S3, 채널3)` 섹션의 불릿 목록 끝에 추가:

```markdown
- 통지·전 REST 응답에 타임아웃(connect 1s / read 2s, `spring.http.client.*`) — OMS hang이어도 최대 수 초 내 복귀 (S4)
- `shipAll`은 RELEASED 예약 출고를 거부(S4) — 취소 타임아웃 반쪽 상태에서의 재고 오염 방지
```

WMS 커밋:

```
cd C:\study\jhg-wms-project
git add README.md
git commit -m "docs: S4 회복탄력성 반영 — 타임아웃·shipAll RELEASED 가드"
git push
```

- [ ] **Step 3: OMS 커밋 + master 병합**

```
cd C:\study\jhg-commerce-project
git add CLAUDE.md docs/superpowers/plans/2026-07-08-phase3-s4-resilience.md
git commit -m "docs: Phase3 S4 완료 기록 — 회복탄력성 로드맵 반영 + 구현 플랜"
git checkout master
git merge feature/phase3-s4
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat build
git push
```

Expected: BUILD SUCCESSFUL. (병합 전 최종 whole-branch 리뷰는 실행 스킬의 게이트가 수행 — OMS 브랜치 diff + WMS 커밋 범위를 함께 전달할 것.)

---

## Self-Review 결과

- **스펙 커버리지**: ①타임아웃 yml(T1 WMS·T2 OMS + V1 실검증·Customizer 폴백) ②예약 재시도(T2) ③ResourceAccessException 핸들러(T3) ④보상 스윕+스케줄링(T4 쿼리, T5 잡) ⑤shipAll RELEASED 가드(T1) ⑥노티파이어=yml만(T1) — 스펙 컴포넌트 6개 전부 태스크 존재. 알려진 한계 2건은 T7 문서에 반영.
- **타입 일관성**: `findBackorderedProductIds() : List<Long>`(T4 정의, T5 소비), `BackorderAllocator.allocate(Collection<Long>) : int`(기존, T5 소비 — `List<Long>` 전달 호환), `reserveAll` 시그니처 불변(T2 — 호출부 무수정).
- **플레이스홀더 없음** 확인. 각 태스크 독립 테스트 사이클 + 커밋.
