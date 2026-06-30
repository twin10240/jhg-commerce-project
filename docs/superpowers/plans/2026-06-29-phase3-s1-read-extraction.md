# Phase 3 S1 — 읽기 추출 (채널1) 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WMS에 `Inventory` 엔티티를 이식하고 `GET /api/inventory/availability` 채널1을 노출한다. OMS 메인 그리드의 재고 조회를 인프로세스 → REST HTTP 호출로 전환한다.

**Architecture:** WMS(8081)에 Inventory JPA 엔티티 + REST 엔드포인트를 구성한다. OMS의 `InventoryQueryPort` 구현체를 RestClient 어댑터(`WmsInventoryQueryAdapter`)로 교체하고 `@Primary`로 등록해 호출부(`ProductService`)는 변경 없이 HTTP 조회로 전환된다. 동작·화면·URL은 불변, 통신 경로만 인프로세스 → HTTP.

**Tech Stack:** Java 21 / Spring Boot 3.5.5 (WMS), Java 17 / Spring Boot 3.5.5 (OMS), Spring RestClient, JUnit 5, @WebMvcTest, @RestClientTest, MockRestServiceServer

## Global Constraints

- WMS 프로젝트 루트: `C:\study\jhg-wms-project`, 패키지: `com.jhg.wms`
- OMS 프로젝트 루트: `C:\study\jhg-commerce-project`, 패키지: `com.jhg.hgpage`
- WMS는 catalog 패키지를 절대 import하지 않는다. productId(Long)로만 상품을 식별.
- 각 태스크 완료마다 `.\gradlew.bat build` (해당 프로젝트) green 확인 후 커밋.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` 포함.
- 테스트는 TDD: 실패하는 테스트 먼저 → 구현 → green 확인.
- WMS 빌드: `C:\study\jhg-wms-project` 디렉토리에서 `.\gradlew.bat build` (gradle.properties에 JDK 21 고정됨).
- OMS 빌드: `C:\study\jhg-commerce-project` 디렉토리에서 `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat build`.

---

## 파일 구조

### WMS 프로젝트 (신규 생성)

| 파일 | 역할 |
|------|------|
| `src/main/java/com/jhg/wms/domain/Inventory.java` | JPA 엔티티. productId 소유, getAvailableQty() 계산값. S1에서 필요한 필드만. |
| `src/main/java/com/jhg/wms/repository/InventoryRepository.java` | Spring Data JPA. findByProductIdIn 배치 조회. |
| `src/main/java/com/jhg/wms/InitDb.java` | @PostConstruct 시드. productId 1~20, onHandQty 15*(i+1). 이미 시드됐으면 skip. |
| `src/main/java/com/jhg/wms/web/InventoryController.java` | GET /api/inventory/availability?productIds=1,2,3 → Map<Long,Integer> JSON. |
| `src/test/java/com/jhg/wms/domain/InventoryTest.java` | 도메인 단위: getAvailableQty 검증 2건. |
| `src/test/java/com/jhg/wms/web/InventoryControllerTest.java` | @WebMvcTest: 가용수량 맵 반환, 없는 productId 미포함 검증. |

### OMS 프로젝트 (신규 생성 + 수정)

| 파일 | 역할 |
|------|------|
| `src/main/java/com/jhg/hgpage/wms/adapter/WmsInventoryQueryAdapter.java` | RestClient로 WMS 채널1 호출. InventoryQueryPort 구현, @Primary. |
| `src/main/resources/application.yml` | wms.base-url: http://localhost:8081 추가 |
| `src/test/resources/application.yml` | wms.base-url: http://localhost:8081 추가 (@SpringBootTest 전체 컨텍스트용) |
| `src/test/java/com/jhg/hgpage/adapter/WmsInventoryQueryAdapterTest.java` | @RestClientTest + MockRestServiceServer: 정상 조회, 빈 목록 단락 검증. |

---

## Task 1: WMS — Inventory 도메인 + InventoryRepository

**Files:**
- Create: `src/main/java/com/jhg/wms/domain/Inventory.java`
- Create: `src/main/java/com/jhg/wms/repository/InventoryRepository.java`
- Test: `src/test/java/com/jhg/wms/domain/InventoryTest.java`

**Interfaces:**
- Produces: `Inventory.create(Long productId, int onHandQty)` 팩토리, `getAvailableQty()`, `getProductId()`, `getOnHandQty()`
- Produces: `InventoryRepository.findByProductIdIn(Collection<Long>)`

---

- [ ] **Step 1: 실패하는 도메인 테스트 작성**

`C:\study\jhg-wms-project\src\test\java\com\jhg\wms\domain\InventoryTest.java` 생성:

```java
package com.jhg.wms.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryTest {

    @Test
    void 가용수량은_보유에서_예약을_뺀_값이다() {
        Inventory inv = Inventory.create(1L, 15);
        inv.setReservedQty(5);
        assertThat(inv.getAvailableQty()).isEqualTo(10);
    }

    @Test
    void 예약이_없으면_가용수량은_보유수량과_같다() {
        Inventory inv = Inventory.create(1L, 15);
        assertThat(inv.getAvailableQty()).isEqualTo(15);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```powershell
cd C:\study\jhg-wms-project
.\gradlew.bat test --tests "com.jhg.wms.domain.InventoryTest"
```

Expected: FAIL — `Inventory` 클래스 없음.

- [ ] **Step 3: Inventory 엔티티 구현**

`C:\study\jhg-wms-project\src\main\java\com\jhg\wms\domain\Inventory.java` 생성:

```java
package com.jhg.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

    @Id @GeneratedValue
    @Column(name = "inventory_id")
    private Long id;

    @Version
    private Long version;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int onHandQty = 0;

    @Column(nullable = false)
    private int reservedQty = 0;

    public static Inventory create(Long productId, int onHandQty) {
        Inventory inv = new Inventory();
        inv.productId = productId;
        inv.onHandQty = onHandQty;
        return inv;
    }

    /** 판매 가용 수량 = 실물(onHand) - 예약(reserved) */
    public int getAvailableQty() {
        return onHandQty - reservedQty;
    }
    // ponytail: reserve/ship/release는 S2에서 추가
}
```

- [ ] **Step 4: InventoryRepository 구현**

`C:\study\jhg-wms-project\src\main\java\com\jhg\wms\repository\InventoryRepository.java` 생성:

```java
package com.jhg.wms.repository;

import com.jhg.wms.domain.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    List<Inventory> findByProductIdIn(Collection<Long> productIds);
}
```

- [ ] **Step 5: 테스트 통과 확인**

```powershell
.\gradlew.bat test --tests "com.jhg.wms.domain.InventoryTest"
```

Expected: PASS 2건.

- [ ] **Step 6: 전체 빌드 확인**

```powershell
.\gradlew.bat build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```powershell
cd C:\study\jhg-wms-project
git add src/main/java/com/jhg/wms/domain/Inventory.java `
        src/main/java/com/jhg/wms/repository/InventoryRepository.java `
        src/test/java/com/jhg/wms/domain/InventoryTest.java
git commit -m "feat(wms): Inventory 도메인 + InventoryRepository — S1 읽기 추출 기반

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: WMS — InitDb 시드 (productId 1~20)

**Files:**
- Create: `src/main/java/com/jhg/wms/InitDb.java`

**Interfaces:**
- Consumes: `Inventory.create(Long productId, int onHandQty)`, `InventoryRepository`
- OMS 시드와 일치: productId 1~20, onHandQty = 15 * (i+1) (15, 30, ..., 300)

---

- [ ] **Step 1: InitDb 구현**

`C:\study\jhg-wms-project\src\main\java\com\jhg\wms\InitDb.java` 생성:

```java
package com.jhg.wms;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class InitDb {

    private final InitService initService;

    @PostConstruct
    public void init() {
        if (initService.alreadySeeded()) return;
        initService.seed();
    }

    @Component
    @Transactional
    @RequiredArgsConstructor
    static class InitService {
        private final InventoryRepository inventoryRepository;

        public boolean alreadySeeded() {
            return inventoryRepository.count() > 0;
        }

        public void seed() {
            for (int i = 0; i < 20; i++) {
                long productId = i + 1;          // OMS 시드의 상품 id 1~20과 일치
                int onHandQty = 15 * (i + 1);    // OMS initDb와 동일: 15, 30, ..., 300
                inventoryRepository.save(Inventory.create(productId, onHandQty));
            }
        }
    }
}
```

- [ ] **Step 2: 전체 빌드 + contextLoads 테스트 통과 확인**

WMS `JhgWmsApplicationTests.contextLoads()`는 `@SpringBootTest`로 전체 컨텍스트를 기동한다.
임베디드 H2(`create-drop`)에서 InitDb가 실행되고 Inventory 20건이 시드된다.

```powershell
.\gradlew.bat build
```

Expected: BUILD SUCCESSFUL (contextLoads 포함).

- [ ] **Step 3: 커밋**

```powershell
git add src/main/java/com/jhg/wms/InitDb.java
git commit -m "feat(wms): InitDb 시드 — productId 1~20, OMS 시드와 수량 일치

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: WMS — GET /api/inventory/availability 엔드포인트

**Files:**
- Create: `src/main/java/com/jhg/wms/web/InventoryController.java`
- Test: `src/test/java/com/jhg/wms/web/InventoryControllerTest.java`

**Interfaces:**
- Consumes: `InventoryRepository.findByProductIdIn(Collection<Long>)`, `Inventory.getAvailableQty()`, `Inventory.getProductId()`
- Produces: `GET /api/inventory/availability?productIds=1,2,3` → `{"1":10,"2":5,"3":0}` (Map<Long,Integer> JSON)
- 쿼리 파라미터 `productIds`는 콤마 구분 문자열(예: `"1,2,3"`). OMS 어댑터(Task 4)가 이 포맷으로 요청.

---

- [ ] **Step 1: 실패하는 컨트롤러 테스트 작성**

`C:\study\jhg-wms-project\src\test\java\com\jhg\wms\web\InventoryControllerTest.java` 생성:

```java
package com.jhg.wms.web;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryRepository inventoryRepository;

    @Test
    void 상품ID목록으로_가용수량_맵을_반환한다() throws Exception {
        Inventory i1 = Inventory.create(1L, 10);
        Inventory i2 = Inventory.create(2L, 5);
        when(inventoryRepository.findByProductIdIn(anyCollection())).thenReturn(List.of(i1, i2));

        mockMvc.perform(get("/api/inventory/availability").param("productIds", "1,2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.1").value(10))
                .andExpect(jsonPath("$.2").value(5));
    }

    @Test
    void 재고가_없는_상품ID는_응답에_포함되지_않는다() throws Exception {
        // productId=3은 WMS에 재고 없음 → 응답 맵에 미포함 (OMS 어댑터가 0으로 기본 처리)
        Inventory i1 = Inventory.create(1L, 10);
        when(inventoryRepository.findByProductIdIn(anyCollection())).thenReturn(List.of(i1));

        mockMvc.perform(get("/api/inventory/availability").param("productIds", "1,3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.1").value(10))
                .andExpect(jsonPath("$.3").doesNotExist());
    }
}
```

> 참고: Spring Boot 3.5에서 `@MockBean`은 deprecated → `@MockitoBean` 사용.

- [ ] **Step 2: 테스트 실패 확인**

```powershell
.\gradlew.bat test --tests "com.jhg.wms.web.InventoryControllerTest"
```

Expected: FAIL — `InventoryController` 없음.

- [ ] **Step 3: InventoryController 구현**

`C:\study\jhg-wms-project\src\main\java\com\jhg\wms\web\InventoryController.java` 생성:

```java
package com.jhg.wms.web;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryRepository inventoryRepository;

    /**
     * 채널1: OMS가 메인 그리드 조립 시 호출한다.
     * productIds는 콤마 구분 문자열(예: "1,2,3"). 응답은 {productId: availableQty} 맵.
     * 재고가 없는 productId는 응답에 미포함 — OMS가 0으로 기본 처리한다.
     */
    @GetMapping("/availability")
    public Map<Long, Integer> availability(@RequestParam String productIds) {
        List<Long> ids = Arrays.stream(productIds.split(","))
                .map(String::trim)
                .map(Long::parseLong)
                .toList();
        return inventoryRepository.findByProductIdIn(ids).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Inventory::getAvailableQty));
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```powershell
.\gradlew.bat test --tests "com.jhg.wms.web.InventoryControllerTest"
```

Expected: PASS 2건.

- [ ] **Step 5: 전체 빌드 확인**

```powershell
.\gradlew.bat build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```powershell
git add src/main/java/com/jhg/wms/web/InventoryController.java `
        src/test/java/com/jhg/wms/web/InventoryControllerTest.java
git commit -m "feat(wms): GET /api/inventory/availability — 채널1 가용수량 조회 엔드포인트

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: OMS — WmsInventoryQueryAdapter (RestClient, @Primary)

**Files:**
- Create: `src/main/java/com/jhg/hgpage/wms/adapter/WmsInventoryQueryAdapter.java`
- Modify: `src/main/resources/application.yml` (wms.base-url 추가)
- Modify: `src/test/resources/application.yml` (wms.base-url 추가)
- Test: `src/test/java/com/jhg/hgpage/adapter/WmsInventoryQueryAdapterTest.java`

**Interfaces:**
- Consumes: `InventoryQueryPort.availableByProductIds(Collection<Long>)` (contract 패키지)
- Produces: `WmsInventoryQueryAdapter` — `@Primary` `InventoryQueryPort` 구현체. `RestClient.Builder` 주입받아 WMS 채널1을 호출.
- 빈 목록 단락: `productIds.isEmpty()` → WMS 미호출, `Map.of()` 즉시 반환.

**배경:** `InventoryService`(OMS wms 패키지)가 `InventoryQueryPort`를 이미 구현하고 있다. `@Primary`로 어댑터를 우선 등록하면 `ProductService` 주입부(`InventoryQueryPort` 필드)는 코드 수정 없이 어댑터를 사용한다. `InventoryService`는 S2까지 OMS에 유지(쓰기 경로 아직 인프로세스).

---

- [ ] **Step 1: OMS application.yml에 wms.base-url 추가**

`C:\study\jhg-commerce-project\src\main\resources\application.yml` 하단에 추가:

```yaml
wms:
  base-url: http://localhost:8081
```

- [ ] **Step 2: OMS test application.yml에 wms.base-url 추가**

`C:\study\jhg-commerce-project\src\test\resources\application.yml` 하단에 추가:

```yaml
wms:
  base-url: http://localhost:8081
```

(@SpringBootTest 전체 컨텍스트 테스트에서 WmsInventoryQueryAdapter가 빈으로 등록될 때 필요.)

- [ ] **Step 3: 실패하는 어댑터 테스트 작성**

`C:\study\jhg-commerce-project\src\test\java\com\jhg\hgpage\adapter\WmsInventoryQueryAdapterTest.java` 생성:

```java
package com.jhg.hgpage.adapter;

import com.jhg.hgpage.wms.adapter.WmsInventoryQueryAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(WmsInventoryQueryAdapter.class)
@TestPropertySource(properties = "wms.base-url=http://wms-test")
class WmsInventoryQueryAdapterTest {

    @Autowired MockRestServiceServer server;
    @Autowired WmsInventoryQueryAdapter adapter;

    @Test
    void WMS_채널1로_productId별_가용수량을_조회한다() {
        server.expect(requestToUriTemplate("http://wms-test/api/inventory/availability?productIds={ids}", "1,2"))
              .andRespond(withSuccess("{\"1\":10,\"2\":5}", MediaType.APPLICATION_JSON));

        Map<Long, Integer> result = adapter.availableByProductIds(List.of(1L, 2L));

        assertThat(result).containsEntry(1L, 10).containsEntry(2L, 5);
        server.verify();
    }

    @Test
    void 빈_목록은_WMS_미호출_후_빈_맵을_반환한다() {
        // server에 아무 expect도 없음 → verify()가 호출이 없었음을 검증
        Map<Long, Integer> result = adapter.availableByProductIds(List.of());

        assertThat(result).isEmpty();
        server.verify(); // WMS로 HTTP 요청이 나가지 않았음을 보장
    }
}
```

- [ ] **Step 4: 테스트 실패 확인**

```powershell
cd C:\study\jhg-commerce-project
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat test --tests "com.jhg.hgpage.adapter.WmsInventoryQueryAdapterTest"
```

Expected: FAIL — `WmsInventoryQueryAdapter` 없음.

- [ ] **Step 5: WmsInventoryQueryAdapter 구현**

`C:\study\jhg-commerce-project\src\main\java\com\jhg\hgpage\wms\adapter\WmsInventoryQueryAdapter.java` 생성:

```java
package com.jhg.hgpage.wms.adapter;

import com.jhg.hgpage.contract.InventoryQueryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * InventoryQueryPort REST 어댑터 (채널1: OMS→WMS 가용수량 조회).
 * @Primary로 등록돼 ProductService의 InventoryQueryPort 주입을 가로챈다.
 * InventoryService(OMS wms 패키지)는 S2까지 병존.
 */
@Primary
@Component
public class WmsInventoryQueryAdapter implements InventoryQueryPort {

    private final RestClient restClient;

    public WmsInventoryQueryAdapter(RestClient.Builder builder,
                                    @Value("${wms.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Map<Long, Integer> availableByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        String ids = productIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<Long, Integer> result = restClient.get()
                .uri("/api/inventory/availability?productIds={ids}", ids)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : Map.of();
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

```powershell
.\gradlew.bat test --tests "com.jhg.hgpage.adapter.WmsInventoryQueryAdapterTest"
```

Expected: PASS 2건.

- [ ] **Step 7: OMS 전체 빌드 + 전체 테스트 확인**

```powershell
.\gradlew.bat build
```

Expected: BUILD SUCCESSFUL. 기존 테스트 전부 green.

> 확인 포인트:
> - `ProductServiceFindPageTest` — `InventoryQueryPort`를 Mockito mock하므로 어댑터와 무관. PASS.
> - `MainControllerMvcTest` — `@WebMvcTest` 슬라이스, `ProductService` mock. PASS.
> - `InventoryServiceTest` — `InventoryService` 직접 테스트, 어댑터와 무관. PASS.
> - `AccountServiceTest`, `CartServiceTest`, `InitDbTest` — `@SpringBootTest`. `WmsInventoryQueryAdapter`가 빈으로 등록되나 test yml에 `wms.base-url`이 있어 초기화 성공, 실제 HTTP 호출 없음. PASS.

- [ ] **Step 8: 커밋**

```powershell
git add src/main/java/com/jhg/hgpage/wms/adapter/WmsInventoryQueryAdapter.java `
        src/main/resources/application.yml `
        src/test/resources/application.yml `
        src/test/java/com/jhg/hgpage/adapter/WmsInventoryQueryAdapterTest.java
git commit -m "feat(oms): WmsInventoryQueryAdapter — 채널1 REST 어댑터, InventoryQueryPort @Primary

OMS 메인 그리드 재고 조회가 인프로세스 → HTTP(WMS 8081)로 전환.
호출부(ProductService) 변경 없음. 빈 목록 단락으로 WMS 불필요 호출 방지.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 슬라이스 완료 기준

- [ ] WMS `.\gradlew.bat build` SUCCESSFUL (InventoryTest 2건 + InventoryControllerTest 2건 + contextLoads 포함)
- [ ] OMS `.\gradlew.bat build` SUCCESSFUL (기존 테스트 전부 + WmsInventoryQueryAdapterTest 2건)
- [ ] WMS `bootRun`(8081) + OMS `bootRun`(8080) 동시 기동 후 OMS 메인 그리드 상품 카드에 가용수량이 WMS 응답 기준으로 표시됨

### 수동 검증 절차

```powershell
# 터미널 1: H2 TCP 서버 기동 (이미 실행 중이면 생략)
# 터미널 2: WMS 기동
cd C:\study\jhg-wms-project
.\gradlew.bat bootRun

# 터미널 3: OMS 기동
cd C:\study\jhg-commerce-project
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat bootRun

# 브라우저: http://localhost:8080/main → 상품 카드 가용수량 확인
# WMS 직접 확인: http://localhost:8081/api/inventory/availability?productIds=1,2,3
```
