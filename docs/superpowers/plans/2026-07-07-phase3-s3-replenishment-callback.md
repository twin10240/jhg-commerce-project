# Phase 3 S3 — WMS→OMS 재고 보충 콜백 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WMS에서 재고가 늘면(입고·+조정) WMS가 OMS에 REST 콜백(`POST /api/replenishments`)을 보내 백오더를 자동 승격시킨다. OMS의 인프로세스 승격 트리거(경계 넘는 잔재)를 제거한다.

**Architecture:** 재고 증가 3경로(발주 입고, WMS REST adjust, WMS 관리 UI adjust)는 전부 WMS `InventoryService.adjust` 한 함수를 통과한다(입고는 내부에서 adjust 호출). 따라서 **훅은 adjust 한 곳, `delta > 0` 조건**. 통지는 `TransactionSynchronizationManager.registerSynchronization`의 afterCommit으로 **트랜잭션 커밋 후에만** 발화하고, HTTP 실패는 삼킨다(best-effort — 유실 승격은 S4 보상 스윕이 회수 예정). 통지 내용은 "이 상품 가용분이 늘었다"는 사실뿐이라 **자연 멱등** — 중복 콜백·재시도 모두 안전(승격은 BACKORDERED 상태 기반, 예약은 `Reservation` orderId UNIQUE가 이미 멱등).

**Tech Stack:** WMS(Java 21, Spring Boot 3.5.5, RestClient, @RestClientTest/MockRestServiceServer), OMS(Java 17, Spring Boot 3.5.5, Spring Security, @WebMvcTest)

## Global Constraints

- WMS 포트: 8081, OMS 포트: 8080. WMS→OMS 콜백 대상: `oms.base-url` = `http://localhost:8080`
- 콜백 API: `POST /api/replenishments` body `{"productIds":[1,2]}` → 200 (빈 목록/누락은 400)
- afterCommit 안에서 던진 예외는 커밋 호출자까지 전파된다 — **HTTP 호출은 반드시 try-catch + `log.warn`**
- **스키마 변경 없음** — 두 DB 모두 리셋 불필요
- 작업 순서 제약: OMS 인프로세스 트리거 제거(Task 4)는 콜백 신설(Task 1~3) 이후. 먼저 지우면 유일하게 작동하는 승격 경로가 죽는다
- OMS는 `feature/phase3-s3` 브랜치에서 작업 후 master 병합. WMS는 master에 직접 커밋(S2와 동일)
- 각 태스크마다 `.\gradlew.bat build`(또는 해당 테스트) 통과 후 커밋. TDD: 테스트 먼저 RED 확인
- OMS 빌드 전 `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"` (시스템 기본이 Java 8)

---

## 파일 맵

### WMS (`C:\study\jhg-wms-project`)

| 파일 | 작업 | 역할 |
|------|------|------|
| `src/main/java/com/jhg/wms/client/OmsReplenishmentNotifier.java` | 생성 | afterCommit 등록 + OMS POST(try-catch best-effort) |
| `src/main/java/com/jhg/wms/service/InventoryService.java` | 수정 | adjust에 `delta > 0` 통지 훅 + notifier 의존 추가 |
| `src/main/java/com/jhg/wms/service/PurchaseOrderService.java` | 수정 | `ponytail: S3에서…` 코멘트 삭제(완료됨) |
| `src/main/resources/application.yml` | 수정 | `oms.base-url` 추가 |
| `src/test/java/com/jhg/wms/client/OmsReplenishmentNotifierTest.java` | 생성 | @RestClientTest 3건 |
| `src/test/java/com/jhg/wms/service/InventoryServiceTest.java` | 수정 | 생성자에 mock notifier + 통지 조건 테스트 2건 |
| `src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java` | 수정 | 생성자에 mock notifier + 입고 통지 테스트 1건 |
| `README.md` | 수정 | 콜백(채널3) 문서화 |

### OMS (`C:\study\jhg-commerce-project`)

| 파일 | 작업 | 역할 |
|------|------|------|
| `src/main/java/com/jhg/hgpage/oms/web/api/ReplenishmentApiController.java` | 생성 | 콜백 수신 → `StockReplenishedHandler.onReplenished` 위임 |
| `src/main/java/com/jhg/hgpage/config/SecurityConfig.java` | 수정 | `/api/replenishments` permitAll + CSRF 예외 |
| `src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java` | 수정 | 인프로세스 `StockReplenishedHandler` 의존·호출 제거 |
| `src/test/java/com/jhg/hgpage/controller/api/ReplenishmentApiControllerMvcTest.java` | 생성 | @WebMvcTest 3건 (무인증·무CSRF 200 핀 포함) |
| `src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java` | 수정 | 트리거 테스트 2건 삭제 + mock 정리 |
| `CLAUDE.md` | 수정 | S3 완료 기록 + 개선 우선순위 갱신 |

---

## Task 1: WMS — OmsReplenishmentNotifier

**Files:**
- Create: `src/main/java/com/jhg/wms/client/OmsReplenishmentNotifier.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/jhg/wms/client/OmsReplenishmentNotifierTest.java`

**Interfaces:**
- Produces: `OmsReplenishmentNotifier.notifyAfterCommit(Long productId)` — 활성 트랜잭션에 afterCommit 동기화 등록. 커밋 후 `POST {oms.base-url}/api/replenishments` body `{"productIds":[productId]}` 전송, 실패는 log.warn으로 삼킴. `send(Long productId)`는 패키지 프라이빗(테스트용 직접 호출 가능).

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/jhg/wms/client/OmsReplenishmentNotifierTest.java`:
```java
package com.jhg.wms.client;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@RestClientTest(OmsReplenishmentNotifier.class)
@TestPropertySource(properties = "oms.base-url=http://oms-test")
class OmsReplenishmentNotifierTest {

    @Autowired MockRestServiceServer server;
    @Autowired OmsReplenishmentNotifier notifier;

    @Test
    void send_OMS에_productIds를_POST한다() {
        server.expect(requestTo("http://oms-test/api/replenishments"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().json("{\"productIds\":[1]}"))
              .andRespond(withSuccess());

        notifier.send(1L);
        server.verify();
    }

    @Test
    void send_OMS가_죽어있어도_예외를_던지지_않는다() {
        server.expect(requestTo("http://oms-test/api/replenishments"))
              .andRespond(withServerError());

        assertThatCode(() -> notifier.send(1L)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void notifyAfterCommit_동기화를_등록하고_커밋_시점에만_전송한다() {
        server.expect(requestTo("http://oms-test/api/replenishments"))
              .andRespond(withSuccess());

        TransactionSynchronizationManager.initSynchronization();
        try {
            notifier.notifyAfterCommit(1L);
            var syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.get(0).afterCommit(); // 커밋 시점 시뮬레이션 — 이때 비로소 전송
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        server.verify();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
cd C:\study\jhg-wms-project
.\gradlew.bat test --tests "com.jhg.wms.client.OmsReplenishmentNotifierTest"
```
Expected: FAIL — `OmsReplenishmentNotifier` 클래스 없음(컴파일 에러)

- [ ] **Step 3: OmsReplenishmentNotifier 구현**

`src/main/java/com/jhg/wms/client/OmsReplenishmentNotifier.java`:
```java
package com.jhg.wms.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * 재고 보충(채널3) 콜백 — 재고가 늘었다는 사실을 OMS에 통지해 백오더 승격을 트리거한다.
 * 통지는 자연 멱등(사실 전달뿐)이라 중복·재시도 안전. 실패는 삼킨다(best-effort,
 * 유실된 승격은 S4 보상 스윕이 회수 예정).
 */
@Slf4j
@Component
public class OmsReplenishmentNotifier {

    private final RestClient restClient;

    public OmsReplenishmentNotifier(RestClient.Builder builder,
                                    @Value("${oms.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    private record ReplenishmentRequest(List<Long> productIds) {}

    /** 현재 트랜잭션 커밋 후에 통지한다(롤백되면 통지 안 나감). */
    public void notifyAfterCommit(Long productId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(productId);
            }
        });
    }

    // afterCommit에서 던진 예외는 커밋 호출자까지 전파되므로 반드시 여기서 삼킨다.
    void send(Long productId) {
        try {
            restClient.post()
                    .uri("/api/replenishments")
                    .body(new ReplenishmentRequest(List.of(productId)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("OMS 재고보충 통지 실패(무시 — 백오더 승격 지연): productId={}", productId, e);
        }
    }
}
```

- [ ] **Step 4: application.yml에 oms.base-url 추가**

`src/main/resources/application.yml`의 `server:` 블록 아래(첫 번째 문서, `---` 위)에 추가:
```yaml
# 재고 보충 콜백(채널3) 대상 — OMS
oms:
  base-url: http://localhost:8080
```

- [ ] **Step 5: 테스트 통과 확인**

```
.\gradlew.bat test --tests "com.jhg.wms.client.OmsReplenishmentNotifierTest"
```
Expected: PASS 3건

- [ ] **Step 6: 커밋**

```
git add src/main/java/com/jhg/wms/client/OmsReplenishmentNotifier.java src/test/java/com/jhg/wms/client/OmsReplenishmentNotifierTest.java src/main/resources/application.yml
git commit -m "feat(wms): OmsReplenishmentNotifier — 커밋 후 OMS 재고보충 콜백(best-effort)"
```

---

## Task 2: WMS — InventoryService.adjust 훅

**Files:**
- Modify: `src/main/java/com/jhg/wms/service/InventoryService.java`
- Modify: `src/main/java/com/jhg/wms/service/PurchaseOrderService.java` (코멘트 1줄 삭제)
- Test: `src/test/java/com/jhg/wms/service/InventoryServiceTest.java`
- Test: `src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java`

**Interfaces:**
- Consumes: `OmsReplenishmentNotifier.notifyAfterCommit(Long productId)` (Task 1)
- Produces: `InventoryService` 생성자가 3개 인자로 변경 — `new InventoryService(InventoryRepository, ReservationRepository, OmsReplenishmentNotifier)`. `adjust(productId, delta)`는 `delta > 0`일 때만 `notifyAfterCommit(productId)` 호출.

> 재고 증가 3경로(발주 입고→receive→adjust, REST adjust, 관리 UI adjust)가 전부 이 함수를 통과하므로 훅은 여기 한 곳이면 충분하다. `reserveAll/shipAll/releaseAll`은 adjust를 안 타므로 오발화 없음.

- [ ] **Step 1: 실패 테스트 작성 — InventoryServiceTest**

`src/test/java/com/jhg/wms/service/InventoryServiceTest.java` 수정.

import 추가:
```java
import com.jhg.wms.client.OmsReplenishmentNotifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
```

필드·setUp 교체 (기존 22~25행):
```java
    InventoryService service;
    OmsReplenishmentNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = mock(OmsReplenishmentNotifier.class);
        service = new InventoryService(repo, reservationRepo, notifier);
    }
```

`// ── adjust / rows ──` 섹션에 테스트 2건 추가:
```java
    @Test
    void adjust_증가면_커밋_후_OMS_통지를_예약한다() {
        seed(1L, 10);
        service.adjust(1L, 5);
        verify(notifier).notifyAfterCommit(1L);
    }

    @Test
    void adjust_감소면_OMS_통지를_예약하지_않는다() {
        seed(1L, 10);
        service.adjust(1L, -3);
        verify(notifier, never()).notifyAfterCommit(any());
    }
```

- [ ] **Step 2: 실패 테스트 작성 — PurchaseOrderServiceTest**

`src/test/java/com/jhg/wms/service/PurchaseOrderServiceTest.java` 수정.

import 추가:
```java
import com.jhg.wms.client.OmsReplenishmentNotifier;

import static org.mockito.Mockito.*;
```

setUp 교체 (기존 296~300행):
```java
    OmsReplenishmentNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = mock(OmsReplenishmentNotifier.class);
        inventoryService = new InventoryService(inventoryRepo, reservationRepo, notifier);
        service = new PurchaseOrderService(poRepo, inventoryService);
    }
```
(주의: `OmsReplenishmentNotifier notifier;` 필드는 기존 `PurchaseOrderService service;` 필드 옆에 추가)

테스트 1건 추가:
```java
    @Test
    void receive_입고하면_품목별로_OMS_통지를_예약한다() {
        inventoryRepo.save(com.jhg.wms.domain.Inventory.create(1L, 5));
        Long poId = service.create(List.of(new PurchaseOrderLine(1L, 10)), "발주");

        service.receive(poId);

        verify(notifier).notifyAfterCommit(1L);
    }
```

- [ ] **Step 3: 컴파일 실패 확인**

```
.\gradlew.bat test --tests "com.jhg.wms.service.InventoryServiceTest" --tests "com.jhg.wms.service.PurchaseOrderServiceTest"
```
Expected: FAIL — `InventoryService` 생성자가 2개 인자(컴파일 에러)

- [ ] **Step 4: InventoryService 수정**

`src/main/java/com/jhg/wms/service/InventoryService.java`:

import 추가:
```java
import com.jhg.wms.client.OmsReplenishmentNotifier;
```

필드 추가 (기존 `reservationRepository` 아래):
```java
    private final OmsReplenishmentNotifier omsReplenishmentNotifier;
```

`adjust` 메서드 교체:
```java
    /** 관리자 수동 재고 조정(+/-). 조정 후 수량을 반환한다. */
    @Transactional
    public int adjust(Long productId, int delta) {
        Inventory inv = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException("재고 없음: productId=" + productId));
        int adjusted = inv.getOnHandQty() + delta;
        if (adjusted < 0)
            throw new IllegalArgumentException("재고는 0 미만이 될 수 없습니다. (현재 " + inv.getOnHandQty() + "개)");
        if (adjusted < inv.getReservedQty())
            throw new IllegalArgumentException("예약된 수량(" + inv.getReservedQty() + "개) 미만으로 줄일 수 없습니다.");
        inv.setOnHandQty(adjusted);
        if (delta > 0) {
            // 모든 재고 증가(입고·REST·UI 조정)가 이 지점을 통과한다 — OMS 백오더 승격 트리거.
            // ponytail: adjust 호출당 HTTP 1발(3품목 입고=3발). 자연 멱등이라 무해 — 배치 필요 시 트랜잭션 스코프 Set으로 모을 것.
            omsReplenishmentNotifier.notifyAfterCommit(productId);
        }
        return adjusted;
    }
```

- [ ] **Step 5: PurchaseOrderService 코멘트 삭제**

`src/main/java/com/jhg/wms/service/PurchaseOrderService.java` 42행의 아래 줄 삭제(S3 완료로 무효):
```java
        // ponytail: S3에서 OMS 콜백(StockReplenishedHandler) 추가. 현재는 OMS가 자체 트리거 유지.
```

- [ ] **Step 6: 테스트 통과 + 전체 빌드 확인**

```
.\gradlew.bat build
```
Expected: BUILD SUCCESSFUL (InventoryServiceTest 12건, PurchaseOrderServiceTest 7건 포함 전체 green)

- [ ] **Step 7: 커밋 + push**

```
git add -A
git commit -m "feat(wms): 재고 증가 시 OMS 재고보충 콜백 발화 — adjust 단일 훅(delta>0, afterCommit)"
git push
```

---

## Task 3: OMS — 콜백 수신 엔드포인트 + SecurityConfig

**Files:**
- Create: `src/main/java/com/jhg/hgpage/oms/web/api/ReplenishmentApiController.java`
- Modify: `src/main/java/com/jhg/hgpage/config/SecurityConfig.java`
- Test: `src/test/java/com/jhg/hgpage/controller/api/ReplenishmentApiControllerMvcTest.java`

**Interfaces:**
- Consumes: `StockReplenishedHandler.onReplenished(Collection<Long> productIds)` (기존 contract 포트, `BackorderAllocator`가 구현)
- Produces: `POST /api/replenishments` body `{"productIds":[1,2]}` → 200. productIds 누락/빈 목록 → 400 (빈 컬렉션이 `findBackordersContaining`의 in절로 흘러가는 것 차단). 무인증·무CSRF 허용(WMS 서버 간 호출).

- [ ] **Step 1: OMS 브랜치 생성**

```
cd C:\study\jhg-commerce-project
git checkout -b feature/phase3-s3
```

- [ ] **Step 2: 실패 테스트 작성**

`src/test/java/com/jhg/hgpage/controller/api/ReplenishmentApiControllerMvcTest.java`:
```java
package com.jhg.hgpage.controller.api;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.oms.web.api.ReplenishmentApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReplenishmentApiController.class)
@Import(SecurityConfig.class)
class ReplenishmentApiControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean StockReplenishedHandler stockReplenishedHandler;

    // 의도적으로 .with(user())·.with(csrf()) 없음 — WMS 서버 간 호출은 세션도 CSRF 토큰도 없다.
    // SecurityConfig의 permitAll + CSRF 예외가 빠지면 이 테스트가 401/403으로 잡는다.
    @Test
    void 인증과_CSRF_없이_콜백을_수신해_핸들러에_위임한다() throws Exception {
        mockMvc.perform(post("/api/replenishments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[1,2]}"))
                .andExpect(status().isOk());

        verify(stockReplenishedHandler).onReplenished(List.of(1L, 2L));
    }

    @Test
    void productIds가_빈_목록이면_400이고_핸들러를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/replenishments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[]}"))
                .andExpect(status().isBadRequest());

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }

    @Test
    void productIds가_누락되면_400이고_핸들러를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/replenishments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat test --tests "com.jhg.hgpage.controller.api.ReplenishmentApiControllerMvcTest"
```
Expected: FAIL — `ReplenishmentApiController` 클래스 없음(컴파일 에러)

- [ ] **Step 4: ReplenishmentApiController 구현**

`src/main/java/com/jhg/hgpage/oms/web/api/ReplenishmentApiController.java`:
```java
package com.jhg.hgpage.oms.web.api;

import com.jhg.hgpage.contract.StockReplenishedHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * WMS→OMS 재고 보충 콜백(채널3) 수신. WMS에서 재고가 늘면(입고·+조정) 호출되어
 * 백오더 승격을 트리거한다. 통지는 자연 멱등 — 중복 수신해도 승격할 게 없으면 no-op.
 */
@RestController
@RequiredArgsConstructor
public class ReplenishmentApiController {

    private final StockReplenishedHandler stockReplenishedHandler;

    public record ReplenishmentRequest(List<Long> productIds) {}

    @PostMapping("/api/replenishments")
    public ResponseEntity<Void> replenished(@RequestBody ReplenishmentRequest request) {
        if (request.productIds() == null || request.productIds().isEmpty())
            return ResponseEntity.badRequest().build();
        stockReplenishedHandler.onReplenished(request.productIds());
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 5: SecurityConfig 수정**

`src/main/java/com/jhg/hgpage/config/SecurityConfig.java` 두 곳:

CSRF 예외 (기존 19행):
```java
            // CSRF 기본 활성화(폼 기반이라면 권장), 특정 경로만 예외 가능
            .csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**", "/api/replenishments"))
```

인가 — `.requestMatchers("/admin/**")` 줄 바로 위에 추가:
```java
                    // WMS 서버 간 콜백(재고 보충 통지) — 세션 없음
                    .requestMatchers("/api/replenishments").permitAll()
```

- [ ] **Step 6: 테스트 통과 확인**

```
.\gradlew.bat test --tests "com.jhg.hgpage.controller.api.ReplenishmentApiControllerMvcTest"
```
Expected: PASS 3건

- [ ] **Step 7: 커밋**

```
git add src/main/java/com/jhg/hgpage/oms/web/api/ReplenishmentApiController.java src/main/java/com/jhg/hgpage/config/SecurityConfig.java src/test/java/com/jhg/hgpage/controller/api/ReplenishmentApiControllerMvcTest.java
git commit -m "feat(oms): POST /api/replenishments — WMS 재고보충 콜백 수신, 백오더 승격 위임"
```

---

## Task 4: OMS — 인프로세스 승격 트리거 제거

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java`
- Modify: `src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java`

> 이제 OMS 재고조정 +delta의 승격은 라운드트립으로 동작한다: OMS adjust → WMS REST adjust → WMS afterCommit 콜백 → OMS `/api/replenishments` → 승격. 인프로세스 직접 호출을 남기면 이중 통지(무해하지만 경계 넘는 잔재).
>
> **주문 취소 경로의 승격은 무관** — `OrderService.cancelOrder`는 구체 타입 `BackorderAllocator.allocate`를 직접 호출하므로(OMS 내부, 경계 안 넘음) 이 변경의 영향 없음.

- [ ] **Step 1: 테스트 갱신 (RED 먼저)**

`src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java`:

삭제 — 테스트 2건 전체 (89~110행):
```java
    @Test
    void 재고_증가_시_백오더_트리거를_호출한다() throws Exception { ... }

    @Test
    void 재고_감소_시_백오더_트리거를_호출하지_않는다() throws Exception { ... }
```

삭제 — mock 필드 (36행):
```java
    @MockitoBean StockReplenishedHandler stockReplenishedHandler;
```

삭제 — import (4행):
```java
import com.jhg.hgpage.contract.StockReplenishedHandler;
```

- [ ] **Step 2: 테스트 실패 확인**

```
.\gradlew.bat test --tests "com.jhg.hgpage.controller.admin.InventoryAdminControllerMvcTest"
```
Expected: FAIL — 컨트롤러가 여전히 `StockReplenishedHandler` 빈을 요구하는데 mock이 사라져 컨텍스트 로딩 실패(`NoSuchBeanDefinitionException`)

- [ ] **Step 3: InventoryAdminController 수정**

`src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java`:

삭제 — import (3행):
```java
import com.jhg.hgpage.contract.StockReplenishedHandler;
```

삭제 — 필드 (24행):
```java
    private final StockReplenishedHandler stockReplenishedHandler;
```

`adjustInventory`의 try 블록에서 인프로세스 트리거 제거 — 교체 전(44~49행):
```java
        try {
            int adjusted = wmsInventoryAdapter.adjust(productId, delta, reason);
            if (delta > 0) {
                // ponytail: S3에서 WMS→OMS 콜백으로 이동. S2까지는 OMS가 직접 트리거.
                stockReplenishedHandler.onReplenished(List.of(productId));
            }
            redirectAttributes.addFlashAttribute("successMessage",
                    "재고가 조정되었습니다. (현재 " + adjusted + "개)");
        }
```
교체 후:
```java
        try {
            // 승격 트리거는 WMS 콜백(POST /api/replenishments)이 담당 — S3에서 인프로세스 직접 호출 제거
            int adjusted = wmsInventoryAdapter.adjust(productId, delta, reason);
            redirectAttributes.addFlashAttribute("successMessage",
                    "재고가 조정되었습니다. (현재 " + adjusted + "개)");
        }
```

삭제 — `receivePurchaseOrder`의 코멘트 (79행, S3 완료로 무효):
```java
            // ponytail: 입고 후 백오더 트리거는 S3 콜백으로 이동. S2는 WMS가 재고만 증가.
```

- [ ] **Step 4: 테스트 통과 + 전체 빌드 확인**

```
.\gradlew.bat build
```
Expected: BUILD SUCCESSFUL (InventoryAdminControllerMvcTest 8건 green, 전체 테스트 green)

- [ ] **Step 5: 커밋**

```
git add src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java
git commit -m "refactor(oms): 인프로세스 백오더 승격 트리거 제거 — WMS 콜백으로 대체"
```

---

## Task 5: 통합 검증 (두 앱 동시 기동, 수동)

> 스키마 변경이 없으므로 두 DB 모두 리셋 불필요. OMS는 `feature/phase3-s3`, WMS는 master 최신으로 기동.

- [ ] **Step 1: 기동**

1. H2 TCP 서버 (별도 터미널)
2. WMS: `cd C:\study\jhg-wms-project` → `.\gradlew.bat bootRun` (8081)
3. OMS: `cd C:\study\jhg-commerce-project` → `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"` → `.\gradlew.bat bootRun` (8080)

- [ ] **Step 2: 백오더 만들기**

1. `http://localhost:8081/admin/inventory`에서 상품 1의 현재 보유 수량 확인
2. WMS에서 상품 1을 delta `-(보유수량)`으로 조정해 0으로 만듦 (예약이 있어 거부되면 다른 상품 사용)
3. OMS `http://localhost:8080` — `twin10240@naver.com` / `1111` 로그인 → 메인에서 상품 1이 "입고 대기 — 주문 가능" 표시 확인 → 주문
4. 내 주문 목록에서 해당 주문이 **BACKORDERED** 상태인지 확인

- [ ] **Step 3: 입고 → 자동 승격 (S3 핵심 시나리오)**

1. WMS `http://localhost:8081/admin/purchase-orders` — 상품 1, 수량 20 발주 생성 → 입고 처리
2. OMS 콘솔 로그에 `백오더 승격: orderId=...` 확인
3. OMS 내 주문 목록 새로고침 → 주문이 **ORDER**로 승격됐는지 확인

- [ ] **Step 4: OMS 재고조정 라운드트립 승격**

1. WMS에서 상품 2를 0으로 조정 → OMS에서 상품 2 주문(BACKORDERED)
2. OMS `http://localhost:8080/admin/inventory` (admin@admin.com / 1111) — 상품 2를 +20 조정
3. OMS 로그에 `백오더 승격` 확인 (경로: OMS→WMS adjust REST→WMS 콜백→OMS 승격)

- [ ] **Step 5: OMS 다운 시 best-effort 확인**

1. OMS 종료(Ctrl+C), WMS는 유지
2. WMS에서 아무 상품 +5 조정 → **조정은 성공**하고 WMS 로그에 `OMS 재고보충 통지 실패(무시...)` warn 확인
3. OMS 재기동 (이후 승격은 S4 보상 스윕 전까지 다음 보충 통지 시점에 — 알려진 한계)

- [ ] **Step 6: 이상 발견 시**

문제가 있으면 superpowers:systematic-debugging으로 원인을 잡고 수정 후 이 태스크를 재실행. 전부 통과하면 Task 6으로.

---

## Task 6: 문서 갱신 + 병합

**Files:**
- Modify: `C:\study\jhg-commerce-project\CLAUDE.md`
- Modify: `C:\study\jhg-wms-project\README.md`

- [ ] **Step 1: WMS README 갱신**

`C:\study\jhg-wms-project\README.md`의 `### 예약 멱등성` 섹션 앞에 추가:
```markdown
### OMS 콜백 (채널3 — 재고 보충 통지)

재고가 늘면(발주 입고·+조정 — 모두 `InventoryService.adjust` 경유) 트랜잭션 커밋 후
`POST {oms.base-url}/api/replenishments` `{"productIds":[...]}` 를 OMS에 보내 백오더 승격을 트리거합니다.
best-effort — OMS가 죽어 있으면 조정은 성공하고 통지만 warn 로그로 유실됩니다(S4 보상 스윕 예정).
설정: `application.yml`의 `oms.base-url` (기본 `http://localhost:8080`).
```

WMS 커밋:
```
cd C:\study\jhg-wms-project
git add README.md
git commit -m "docs: 채널3 OMS 재고보충 콜백 문서화"
git push
```

- [ ] **Step 2: OMS CLAUDE.md 갱신**

`C:\study\jhg-commerce-project\CLAUDE.md` 세 곳:

① Phase 3 로드맵의 S2 항목 끝에 있는 `**주의(S3로 이연)**: 입고 후 백오더 승격 트리거 없음 — ...` 문장을 삭제하고, S2 항목 뒤에 추가:
```markdown
  **S3 완료(2026-07-07)**: WMS→OMS 재고 보충 콜백(채널3) — WMS `InventoryService.adjust`(재고 증가 3경로가 전부 통과하는 단일 지점)가 `delta > 0`이면 커밋 후(`TransactionSynchronizationManager` afterCommit) `POST /api/replenishments {productIds}`를 OMS에 발화(`OmsReplenishmentNotifier`, try-catch best-effort — OMS 다운 시 조정은 성공·통지만 warn 유실). OMS는 `ReplenishmentApiController`(permitAll + CSRF 예외)가 수신해 `StockReplenishedHandler.onReplenished`(=`BackorderAllocator`)로 위임 — 통지는 자연 멱등(사실 전달뿐, 승격은 BACKORDERED 상태 기반 + `Reservation` orderId UNIQUE). `InventoryAdminController`의 인프로세스 직접 트리거 제거(OMS adjust 승격은 라운드트립: OMS→WMS adjust→콜백→승격). 주문 취소 경로 승격은 OMS 내부 `BackorderAllocator.allocate` 직접 호출 그대로(경계 안 넘음). 두 앱 통합 검증 통과(입고→승격, 라운드트립 승격, OMS 다운 best-effort). 스키마 변경 없음. 플랜: `docs/superpowers/plans/2026-07-07-phase3-s3-replenishment-callback.md`. 남은 것 = S4: WMS 다운 회복탄력성(보상 스윕·타임아웃/재시도).
```

② `개선 우선순위` 1번 교체:
```markdown
1. **Phase 3 — S4: 회복탄력성** (WMS 다운 시 예약 강등 + 보상 스윕 잡(`@Scheduled`로 BACKORDERED 재할당 — 콜백 유실 회수) + RestClient 타임아웃/재시도). S3까지 완료 — 입고/재고증가 시 WMS→OMS 콜백으로 백오더 자동 승격.
```

③ `개선 우선순위` 1-1에 추가 (기존 문장 끝에):
```markdown
 `oms.base-url`(WMS→OMS 콜백)도 환경변수화 대상.
```

- [ ] **Step 3: OMS 커밋 + master 병합**

```
cd C:\study\jhg-commerce-project
git add CLAUDE.md docs/superpowers/plans/2026-07-07-phase3-s3-replenishment-callback.md
git commit -m "docs: Phase3 S3 완료 기록 — 재고 보충 콜백 로드맵 반영 + 구현 플랜"
git checkout master
git merge feature/phase3-s3
```

병합 후 확인:
```
.\gradlew.bat build
```
Expected: BUILD SUCCESSFUL

---

## Self-Review 결과

- **커버리지**: 브리핑/검증에서 확정한 항목 전부 태스크로 존재 — adjust 단일 훅(T2), afterCommit synchronization + try-catch(T1), OMS 엔드포인트 + SecurityConfig permitAll·CSRF(T3), 인프로세스 제거 + 깨질 테스트 2건(T4), 제거 순서 제약(T4가 T3 뒤), 통합 검증 + best-effort 확인(T5), 문서(T6).
- **타입 일관성**: `notifyAfterCommit(Long)`(T1 정의, T2 소비), `InventoryService` 3-인자 생성자(T2 정의, 두 테스트 모두 갱신), `onReplenished(Collection<Long>)`에 `List<Long>` 전달(T3 — 기존 포트 시그니처 그대로).
- **플레이스홀더 없음** 확인.
