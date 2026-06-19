# web 계층 컨텍스트 분리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 컨트롤러·폼·뷰DTO를 OMS/WMS 바운디드 컨텍스트로 수직 분할하고 `AdminController`를 OMS/WMS 두 컨트롤러로 쪼갠다.

**Architecture:** 순수 패키지 이동(동작·DB·화면·URL 불변). 각 슬라이스마다 모든 참조(main+test)를 같이 갱신해 `gradlew build`를 green으로 유지하며 커밋. 응답 DTO는 repository→web 역의존을 피하려고 `oms/web/dto`가 아닌 컨텍스트 레벨 `oms/dto`에 둔다. 폼은 HTTP 입력 전용이라 `oms/web/form`·`wms/web/form`.

**Tech Stack:** Java 17, Spring Boot 3.5.5, QueryDSL 5.0, Gradle, 임베디드 H2 테스트.

## Global Constraints

- 빌드/테스트: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat build` — 기대 출력 `BUILD SUCCESSFUL`. 테스트는 임베디드 H2라 H2 TCP 서버 불필요.
- 동작·DB·화면·URL·템플릿 불변. 기능 추가/정책 변경 금지(순수 이동).
- 테스트 패키지는 **옮기지 않는다** — 코어 분할(2026-06-18) 선례대로 테스트는 기존 패키지에 두고 import만 갱신한다.
- `UserPrincipal`(`com.jhg.hgpage.domain.dto`)·`Role`(`com.jhg.hgpage.domain.enums`)은 이동하지 않는다(인증 공용).
- 각 태스크는 자체적으로 main+test 참조를 모두 갱신해 빌드 green으로 끝낸다.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` 추가. 브랜치: `phase2-web-context-split`.

---

### Task 1: 응답 DTO → `oms/dto`

4개 응답 DTO(`OrderDto`/`OrderDetailDto`/`CartItemDto`/`AdminOrderDto`)를 `com.jhg.hgpage.domain.dto.view`에서 `com.jhg.hgpage.oms.dto`로 이동. `CartItemDto`의 `@QueryProjection`이 생성하는 `QCartItemDto`도 빌드 시 새 패키지로 재생성된다.

**Files:**
- Move: `domain/dto/view/{OrderDto,OrderDetailDto,CartItemDto,AdminOrderDto}.java` → `oms/dto/`
- Modify (main import): `oms/service/OrderService.java`, `oms/service/CartService.java`, `oms/repository/CartRepositoryQuery.java`, `api/OrderApiController.java`, `controller/cart/CartController.java`, `controller/main/MainController.java`
- Modify (test import): `service/OrderServiceTest.java`, `service/OrderServiceDetailTest.java`, `service/OrderServiceAdminTest.java`, `service/CartServiceTest.java`, `api/OrderApiControllerMvcTest.java`, `controller/admin/AdminControllerMvcTest.java`, `controller/order/OrderControllerMvcTest.java`

**Interfaces:**
- Produces: `com.jhg.hgpage.oms.dto.OrderDto`, `...OrderDetailDto`, `...CartItemDto`, `...AdminOrderDto`, 생성된 `...QCartItemDto`. 공개 메서드 시그니처(`OrderDto.from`, `OrderDetailDto.from`, `AdminOrderDto.from`, `QCartItemDto` 생성자)는 불변.

- [ ] **Step 1: 4개 DTO 파일을 git mv로 이동**

```bash
git mv src/main/java/com/jhg/hgpage/domain/dto/view/OrderDto.java        src/main/java/com/jhg/hgpage/oms/dto/OrderDto.java
git mv src/main/java/com/jhg/hgpage/domain/dto/view/OrderDetailDto.java  src/main/java/com/jhg/hgpage/oms/dto/OrderDetailDto.java
git mv src/main/java/com/jhg/hgpage/domain/dto/view/CartItemDto.java     src/main/java/com/jhg/hgpage/oms/dto/CartItemDto.java
git mv src/main/java/com/jhg/hgpage/domain/dto/view/AdminOrderDto.java   src/main/java/com/jhg/hgpage/oms/dto/AdminOrderDto.java
```

- [ ] **Step 2: 이동한 4개 파일의 package 선언 변경**

각 파일의 첫 줄 `package com.jhg.hgpage.domain.dto.view;` → `package com.jhg.hgpage.oms.dto;`

- [ ] **Step 3: main 측 import 갱신**

각 파일에서 `import com.jhg.hgpage.domain.dto.view.<X>;` → `import com.jhg.hgpage.oms.dto.<X>;`:
- `oms/service/OrderService.java`: `AdminOrderDto`, `OrderDetailDto`, `OrderDto` (3줄)
- `oms/service/CartService.java`: `CartItemDto`
- `oms/repository/CartRepositoryQuery.java`: `CartItemDto`, `QCartItemDto` (2줄 — `QCartItemDto`도 `com.jhg.hgpage.oms.dto.QCartItemDto`로)
- `api/OrderApiController.java`: `OrderDto`
- `controller/cart/CartController.java`: `CartItemDto`
- `controller/main/MainController.java`: `OrderDto`

- [ ] **Step 4: test 측 import/FQN 갱신**

- `service/OrderServiceTest.java`: `import com.jhg.hgpage.domain.dto.view.OrderDto;` → `import com.jhg.hgpage.oms.dto.OrderDto;`
- `service/OrderServiceDetailTest.java`: `OrderDetailDto` import 동일 패턴
- `service/OrderServiceAdminTest.java`: `AdminOrderDto` import 동일 패턴
- `service/CartServiceTest.java`: `CartItemDto` import 동일 패턴
- `api/OrderApiControllerMvcTest.java`: `OrderDto` import 동일 패턴
- `controller/admin/AdminControllerMvcTest.java`: FQN 2곳 `com.jhg.hgpage.domain.dto.view.AdminOrderDto` → `com.jhg.hgpage.oms.dto.AdminOrderDto` (replace_all)
- `controller/order/OrderControllerMvcTest.java`: FQN 3곳 `com.jhg.hgpage.domain.dto.view.OrderDetailDto` → `com.jhg.hgpage.oms.dto.OrderDetailDto` (replace_all)

- [ ] **Step 5: 빌드 green 확인**

Run: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`. (`domain/dto/view/` 디렉터리는 비게 됨 — 정상)

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "refactor: 응답 DTO를 oms/dto로 이동 (Phase 2 web 분리 1/5)

OrderDto/OrderDetailDto/CartItemDto/AdminOrderDto를 domain.dto.view → oms.dto로 이동.
QueryDSL QCartItemDto는 새 패키지로 재생성. repository→web 역의존 회피 위해 web 밑이 아닌 컨텍스트 레벨에 배치. 순수 이동.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: 폼 → `oms/web/form` · `wms/web/form`

`CheckOutForm`/`OrderRequest`/`SignUpForm`을 `oms/web/form`으로, `PurchaseOrderForm`을 `wms/web/form`으로 이동.

**Files:**
- Move: `controller/form/{CheckOutForm,OrderRequest,SignUpForm}.java` → `oms/web/form/`; `controller/form/PurchaseOrderForm.java` → `wms/web/form/`
- Modify (main import): `controller/auth/AuthController.java`, `controller/order/OrderController.java`, `controller/admin/AdminController.java`
- Modify (test import): `controller/order/OrderControllerTest.java`

**Interfaces:**
- Produces: `com.jhg.hgpage.oms.web.form.{CheckOutForm,OrderRequest,SignUpForm}`, `com.jhg.hgpage.wms.web.form.PurchaseOrderForm`. 내부 타입(`CheckOutForm.ProductDto`, `OrderRequest.OrderItem`) 경로도 따라 바뀜.

- [ ] **Step 1: 폼 파일 이동**

```bash
git mv src/main/java/com/jhg/hgpage/controller/form/CheckOutForm.java      src/main/java/com/jhg/hgpage/oms/web/form/CheckOutForm.java
git mv src/main/java/com/jhg/hgpage/controller/form/OrderRequest.java      src/main/java/com/jhg/hgpage/oms/web/form/OrderRequest.java
git mv src/main/java/com/jhg/hgpage/controller/form/SignUpForm.java        src/main/java/com/jhg/hgpage/oms/web/form/SignUpForm.java
git mv src/main/java/com/jhg/hgpage/controller/form/PurchaseOrderForm.java src/main/java/com/jhg/hgpage/wms/web/form/PurchaseOrderForm.java
```

- [ ] **Step 2: package 선언 변경**

- `CheckOutForm.java`/`OrderRequest.java`/`SignUpForm.java`: `package com.jhg.hgpage.controller.form;` → `package com.jhg.hgpage.oms.web.form;`
- `PurchaseOrderForm.java`: `package com.jhg.hgpage.controller.form;` → `package com.jhg.hgpage.wms.web.form;`

- [ ] **Step 3: main import 갱신**

- `controller/auth/AuthController.java`: `import com.jhg.hgpage.controller.form.SignUpForm;` → `import com.jhg.hgpage.oms.web.form.SignUpForm;`
- `controller/order/OrderController.java`: `CheckOutForm`, `OrderRequest` 2줄 → `com.jhg.hgpage.oms.web.form.*`
- `controller/admin/AdminController.java`: `import com.jhg.hgpage.controller.form.PurchaseOrderForm;` → `import com.jhg.hgpage.wms.web.form.PurchaseOrderForm;`

- [ ] **Step 4: test import 갱신**

- `controller/order/OrderControllerTest.java`: `import com.jhg.hgpage.controller.form.CheckOutForm;` → `import com.jhg.hgpage.oms.web.form.CheckOutForm;`

- [ ] **Step 5: 빌드 green 확인**

Run: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`. (`controller/form/` 디렉터리 비게 됨)

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "refactor: 폼을 oms/web/form·wms/web/form으로 이동 (Phase 2 web 분리 2/5)

CheckOutForm/OrderRequest/SignUpForm → oms.web.form, PurchaseOrderForm → wms.web.form. HTTP 입력 바인딩 전용이라 web 계층 소속. 순수 이동.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: OMS 컨트롤러·api → `oms/web/{controller,api}`

`AuthController`/`CartController`/`OrderController`를 `oms/web/controller`로, `CartApiController`/`OrderApiController`를 `oms/web/api`로 이동. 컨트롤러는 컴포넌트 스캔이라 main 측 import 참조는 없고, 테스트(`@WebMvcTest(X.class)`·단위 테스트의 `new X(...)`)에 import만 추가한다.

**Files:**
- Move: `controller/auth/AuthController.java`, `controller/cart/CartController.java`, `controller/order/OrderController.java` → `oms/web/controller/`; `api/CartApiController.java`, `api/OrderApiController.java` → `oms/web/api/`
- Modify (test import 추가): `controller/auth/AuthControllerMvcTest.java`, `controller/order/OrderControllerMvcTest.java`, `controller/order/OrderControllerTest.java`, `api/CartApiControllerMvcTest.java`, `api/OrderApiControllerMvcTest.java`

**Interfaces:**
- Produces: `com.jhg.hgpage.oms.web.controller.{AuthController,CartController,OrderController}`, `com.jhg.hgpage.oms.web.api.{CartApiController,OrderApiController}`. 매핑 URL·메서드 시그니처 불변.

- [ ] **Step 1: 컨트롤러·api 파일 이동**

```bash
git mv src/main/java/com/jhg/hgpage/controller/auth/AuthController.java   src/main/java/com/jhg/hgpage/oms/web/controller/AuthController.java
git mv src/main/java/com/jhg/hgpage/controller/cart/CartController.java   src/main/java/com/jhg/hgpage/oms/web/controller/CartController.java
git mv src/main/java/com/jhg/hgpage/controller/order/OrderController.java src/main/java/com/jhg/hgpage/oms/web/controller/OrderController.java
git mv src/main/java/com/jhg/hgpage/api/CartApiController.java            src/main/java/com/jhg/hgpage/oms/web/api/CartApiController.java
git mv src/main/java/com/jhg/hgpage/api/OrderApiController.java           src/main/java/com/jhg/hgpage/oms/web/api/OrderApiController.java
```

- [ ] **Step 2: package 선언 변경**

- `AuthController.java`: `package com.jhg.hgpage.controller.auth;` → `package com.jhg.hgpage.oms.web.controller;`
- `CartController.java`: `package com.jhg.hgpage.controller.cart;` → `package com.jhg.hgpage.oms.web.controller;`
- `OrderController.java`: `package com.jhg.hgpage.controller.order;` → `package com.jhg.hgpage.oms.web.controller;`
- `CartApiController.java`: `package com.jhg.hgpage.api;` → `package com.jhg.hgpage.oms.web.api;`
- `OrderApiController.java`: `package com.jhg.hgpage.api;` → `package com.jhg.hgpage.oms.web.api;`

- [ ] **Step 3: 테스트에 이동한 컨트롤러 import 추가**

테스트는 기존 패키지에 그대로 두고, 이전엔 same-package라 생략됐던 컨트롤러 참조에 import를 추가한다:
- `controller/auth/AuthControllerMvcTest.java`: `import com.jhg.hgpage.oms.web.controller.AuthController;` 추가
- `controller/order/OrderControllerMvcTest.java`: `import com.jhg.hgpage.oms.web.controller.OrderController;` 추가
- `controller/order/OrderControllerTest.java`: `import com.jhg.hgpage.oms.web.controller.OrderController;` 추가 (단위 테스트가 `new OrderController(...)` 생성)
- `api/CartApiControllerMvcTest.java`: `import com.jhg.hgpage.oms.web.api.CartApiController;` 추가
- `api/OrderApiControllerMvcTest.java`: `import com.jhg.hgpage.oms.web.api.OrderApiController;` 추가

- [ ] **Step 4: 빌드 green 확인**

Run: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`. 컴파일 에러로 누락 import가 드러나면 해당 테스트에 `import com.jhg.hgpage.oms.web.controller.OrderController;` 형태로 보강.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "refactor: OMS 컨트롤러·api를 oms/web으로 이동 (Phase 2 web 분리 3/5)

Auth/Cart/OrderController → oms.web.controller, Cart/OrderApiController → oms.web.api. 테스트는 기존 패키지 유지(선례), import만 보강. 순수 이동.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `AdminController` 분할 → `OrderAdminController`(oms) + `InventoryAdminController`(wms)

혼재된 `AdminController`를 OMS(배송)·WMS(재고·발주) 두 컨트롤러로 분할하고, 슬라이스 테스트도 둘로 나눈다.

**Files:**
- Delete: `controller/admin/AdminController.java`
- Create: `oms/web/controller/OrderAdminController.java`, `wms/web/controller/InventoryAdminController.java`
- Delete: `controller/admin/AdminControllerMvcTest.java`
- Create: `controller/admin/OrderAdminControllerMvcTest.java`, `controller/admin/InventoryAdminControllerMvcTest.java` (테스트 패키지는 선례대로 유지)

**Interfaces:**
- Consumes: `OrderService`(Task 기존), `InventoryAdjustmentService`/`PurchaseOrderService`/`ProductService`, `com.jhg.hgpage.wms.web.form.PurchaseOrderForm`(Task 2).
- Produces: `com.jhg.hgpage.oms.web.controller.OrderAdminController`, `com.jhg.hgpage.wms.web.controller.InventoryAdminController`. 매핑 URL 전부 불변(`/admin/orders*`는 OrderAdmin, `/admin/inventory*`·`/admin/purchase-orders*`는 InventoryAdmin).

- [ ] **Step 1: `OrderAdminController` 생성**

`src/main/java/com/jhg/hgpage/oms/web/controller/OrderAdminController.java`:

```java
package com.jhg.hgpage.oms.web.controller;

import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderService orderService;

    @GetMapping("/admin/orders")
    public String orders(Model model) {
        model.addAttribute("orders", orderService.findAllForAdmin());
        return "admin/orders";
    }

    // HTML 폼 제약 때문에 path variable 대신 orderId 파라미터를 받는다 (발주 입고와 동일 패턴)
    @PostMapping("/admin/orders/complete-delivery")
    public String completeDelivery(@RequestParam Long orderId,
                                   RedirectAttributes redirectAttributes) {
        try {
            orderService.completeDelivery(orderId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "배송완료 처리되었습니다. (주문 #" + orderId + ")");
        } catch (IllegalStateException | EntityNotFoundException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/orders";
    }
}
```

- [ ] **Step 2: `InventoryAdminController` 생성**

`src/main/java/com/jhg/hgpage/wms/web/controller/InventoryAdminController.java`:

```java
package com.jhg.hgpage.wms.web.controller;

import com.jhg.hgpage.catalog.ProductService;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.service.InventoryAdjustmentService;
import com.jhg.hgpage.wms.service.PurchaseOrderService;
import com.jhg.hgpage.wms.service.PurchaseOrderService.PurchaseOrderLine;
import com.jhg.hgpage.wms.web.form.PurchaseOrderForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class InventoryAdminController {

    private final InventoryAdjustmentService inventoryAdjustmentService;
    private final ProductService productService;
    private final PurchaseOrderService purchaseOrderService;

    @GetMapping("/admin/inventory")
    public String inventory(Model model) {
        model.addAttribute("products", productService.findAllWithInventory());
        model.addAttribute("purchaseOrders", purchaseOrderService.findAllWithItems());
        return "admin/inventory";
    }

    @PostMapping("/admin/inventory/adjust")
    public String adjustInventory(@RequestParam Long productId,
                                  @RequestParam int delta,
                                  @RequestParam(defaultValue = "") String reason,
                                  RedirectAttributes redirectAttributes) {
        try {
            int adjusted = inventoryAdjustmentService.adjust(productId, delta, reason);
            redirectAttributes.addFlashAttribute("successMessage",
                    "재고가 조정되었습니다. (현재 " + adjusted + "개)");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inventory";
    }

    @PostMapping("/admin/purchase-orders")
    public String createPurchaseOrder(@ModelAttribute PurchaseOrderForm form,
                                      RedirectAttributes redirectAttributes) {
        List<PurchaseOrderLine> lines = form.getItems().stream()
                .map(item -> new PurchaseOrderLine(item.getProductId(), item.getQuantity()))
                .toList();
        try {
            Long poId = purchaseOrderService.create(lines, form.getMemo());
            redirectAttributes.addFlashAttribute("successMessage",
                    "발주가 생성되었습니다. (발주 #" + poId + ")");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inventory";
    }

    // HTML 폼은 입력값을 path에 넣을 수 없으므로 path variable 대신 poId 파라미터를 받는다
    @PostMapping("/admin/purchase-orders/receive")
    public String receivePurchaseOrder(@RequestParam Long poId,
                                       RedirectAttributes redirectAttributes) {
        try {
            Long receivedId = purchaseOrderService.receive(poId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "입고 처리되었습니다. (발주 #" + receivedId + ")");
        } catch (IllegalStateException | EntityNotFoundException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inventory";
    }
}
```

- [ ] **Step 3: 옛 `AdminController` 삭제**

```bash
git rm src/main/java/com/jhg/hgpage/controller/admin/AdminController.java
```

- [ ] **Step 4: `OrderAdminControllerMvcTest` 생성 (주문 관련 4개 테스트)**

`src/test/java/com/jhg/hgpage/controller/admin/OrderAdminControllerMvcTest.java` — 기존 `AdminControllerMvcTest`의 주문 관련 테스트(`관리자는_주문목록을_조회한다`, `일반사용자는_주문목록에_접근할_수_없다`, `배송완료_처리하면...`, `배송완료_불가...`)와 헬퍼(`admin()`, `normalUser()`, `sampleProduct()`, `adminOrderDto()`)를 옮긴다. `@WebMvcTest(OrderAdminController.class)`, `@MockBean OrderService` 만 둔다.

```java
package com.jhg.hgpage.controller.admin;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.oms.dto.AdminOrderDto;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.web.controller.OrderAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(OrderAdminController.class)
@Import(SecurityConfig.class)
class OrderAdminControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean OrderService orderService;

    private UserPrincipal admin() {
        return new UserPrincipal(2L, "admin@admin.com", "관리자", "010-1111-2222", "password", Role.ADMIN);
    }

    private UserPrincipal normalUser() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }

    private Product sampleProduct() {
        Product product = new Product();
        product.setId(1L);
        product.setName("상품1");
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(15);
        product.setInventory(inventory);
        return product;
    }

    private AdminOrderDto adminOrderDto() {
        com.jhg.hgpage.oms.domain.Member member = com.jhg.hgpage.oms.domain.Member.createUser(
                "주문자A", "010-0000-0000", new com.jhg.hgpage.oms.domain.Address("서울", "관악구", "500"));
        com.jhg.hgpage.oms.domain.Delivery delivery = new com.jhg.hgpage.oms.domain.Delivery();
        delivery.setAddress(new com.jhg.hgpage.oms.domain.Address("서울", "관악구", "500"));
        com.jhg.hgpage.oms.domain.Order order = com.jhg.hgpage.oms.domain.Order.createOrder(member, delivery,
                com.jhg.hgpage.oms.domain.OrderItem.createOrderItem(sampleProduct(), 10000, 2));
        return AdminOrderDto.from(order);
    }

    @Test
    void 관리자는_주문목록을_조회한다() throws Exception {
        when(orderService.findAllForAdmin()).thenReturn(List.of(adminOrderDto()));

        mockMvc.perform(get("/admin/orders").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/orders"))
                .andExpect(model().attributeExists("orders"))
                .andExpect(content().string(containsString("주문자A")))
                .andExpect(content().string(containsString("배송완료")));
    }

    @Test
    void 일반사용자는_주문목록에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/orders").with(user(normalUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 배송완료_처리하면_주문목록으로_리다이렉트하고_성공메시지를_담는다() throws Exception {
        mockMvc.perform(post("/admin/orders/complete-delivery")
                        .with(user(admin()))
                        .with(csrf())
                        .param("orderId", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(orderService).completeDelivery(10L);
    }

    @Test
    void 배송완료_불가_주문이면_에러메시지와_함께_목록으로_돌아간다() throws Exception {
        doThrow(new IllegalStateException("이미 배송완료된 주문입니다."))
                .when(orderService).completeDelivery(10L);

        mockMvc.perform(post("/admin/orders/complete-delivery")
                        .with(user(admin()))
                        .with(csrf())
                        .param("orderId", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/orders"))
                .andExpect(flash().attributeExists("errorMessage"));
    }
}
```

- [ ] **Step 5: `InventoryAdminControllerMvcTest` 생성 (재고·발주 9개 테스트)**

`src/test/java/com/jhg/hgpage/controller/admin/InventoryAdminControllerMvcTest.java` — 기존 `AdminControllerMvcTest`의 재고/발주 테스트(`관리자는_재고목록과_발주현황을_조회한다`, `일반사용자는_재고목록에_접근할_수_없다`, `재고를_조정하면...`, `재고가_음수가_되는_조정은...`, `일반사용자는_재고를_조정할_수_없다`, `발주를_생성하면...`, `잘못된_발주는...`, `입고하면...`, `이미_입고된_발주는...`, `없는_발주를_입고하면...`)와 헬퍼(`admin()`, `normalUser()`, `sampleProduct()`)를 옮긴다. `@WebMvcTest(InventoryAdminController.class)`, `@MockBean`은 `InventoryAdjustmentService`/`ProductService`/`PurchaseOrderService`.

```java
package com.jhg.hgpage.controller.admin;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.wms.service.InventoryAdjustmentService;
import com.jhg.hgpage.catalog.ProductService;
import com.jhg.hgpage.wms.service.PurchaseOrderService;
import com.jhg.hgpage.wms.web.controller.InventoryAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(InventoryAdminController.class)
@Import(SecurityConfig.class)
class InventoryAdminControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean InventoryAdjustmentService inventoryAdjustmentService;
    @MockBean ProductService productService;
    @MockBean PurchaseOrderService purchaseOrderService;

    private UserPrincipal admin() {
        return new UserPrincipal(2L, "admin@admin.com", "관리자", "010-1111-2222", "password", Role.ADMIN);
    }

    private UserPrincipal normalUser() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }

    private Product sampleProduct() {
        Product product = new Product();
        product.setId(1L);
        product.setName("상품1");
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(15);
        product.setInventory(inventory);
        return product;
    }

    @Test
    void 관리자는_재고목록과_발주현황을_조회한다() throws Exception {
        when(productService.findAllWithInventory()).thenReturn(List.of(sampleProduct()));
        when(purchaseOrderService.findAllWithItems()).thenReturn(
                List.of(PurchaseOrder.create("긴급 발주", PurchaseOrderItem.create(sampleProduct(), 5))));

        mockMvc.perform(get("/admin/inventory").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(model().attributeExists("products", "purchaseOrders"));
    }

    @Test
    void 일반사용자는_재고목록에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/inventory").with(user(normalUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 재고를_조정하면_조회페이지로_리다이렉트하고_성공메시지를_담는다() throws Exception {
        when(inventoryAdjustmentService.adjust(1L, 5, "정기조사")).thenReturn(20);

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(admin()))
                        .with(csrf())
                        .param("productId", "1")
                        .param("delta", "5")
                        .param("reason", "정기조사"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(inventoryAdjustmentService).adjust(1L, 5, "정기조사");
    }

    @Test
    void 재고가_음수가_되는_조정은_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(inventoryAdjustmentService.adjust(1L, -99, "조정"))
                .thenThrow(new IllegalArgumentException("재고는 0 미만이 될 수 없습니다."));

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(admin()))
                        .with(csrf())
                        .param("productId", "1")
                        .param("delta", "-99")
                        .param("reason", "조정"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 일반사용자는_재고를_조정할_수_없다() throws Exception {
        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(normalUser()))
                        .with(csrf())
                        .param("productId", "1")
                        .param("delta", "5")
                        .param("reason", "조정"))
                .andExpect(status().isForbidden());

        verify(inventoryAdjustmentService, never()).adjust(anyLong(), anyInt(), anyString());
    }

    @Test
    void 발주를_생성하면_성공메시지와_함께_재고페이지로_리다이렉트한다() throws Exception {
        when(purchaseOrderService.create(anyList(), eq("긴급 발주"))).thenReturn(7L);

        mockMvc.perform(post("/admin/purchase-orders")
                        .with(user(admin()))
                        .with(csrf())
                        .param("items[0].productId", "1")
                        .param("items[0].quantity", "10")
                        .param("memo", "긴급 발주"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(purchaseOrderService).create(anyList(), eq("긴급 발주"));
    }

    @Test
    void 잘못된_발주는_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(purchaseOrderService.create(anyList(), anyString()))
                .thenThrow(new IllegalArgumentException("발주 수량은 1개 이상이어야 합니다."));

        mockMvc.perform(post("/admin/purchase-orders")
                        .with(user(admin()))
                        .with(csrf())
                        .param("items[0].productId", "1")
                        .param("items[0].quantity", "0")
                        .param("memo", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 입고하면_성공메시지와_함께_재고페이지로_리다이렉트한다() throws Exception {
        when(purchaseOrderService.receive(7L)).thenReturn(7L);

        mockMvc.perform(post("/admin/purchase-orders/receive")
                        .with(user(admin()))
                        .with(csrf())
                        .param("poId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(purchaseOrderService).receive(7L);
    }

    @Test
    void 이미_입고된_발주는_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(purchaseOrderService.receive(7L))
                .thenThrow(new IllegalStateException("이미 입고 처리된 발주입니다."));

        mockMvc.perform(post("/admin/purchase-orders/receive")
                        .with(user(admin()))
                        .with(csrf())
                        .param("poId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 없는_발주를_입고하면_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(purchaseOrderService.receive(99L))
                .thenThrow(new com.jhg.hgpage.exception.EntityNotFoundException("PurchaseOrder", 99L));

        mockMvc.perform(post("/admin/purchase-orders/receive")
                        .with(user(admin()))
                        .with(csrf())
                        .param("poId", "99"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("errorMessage"));
    }
}
```

- [ ] **Step 6: 옛 `AdminControllerMvcTest` 삭제**

```bash
git rm src/test/java/com/jhg/hgpage/controller/admin/AdminControllerMvcTest.java
```

- [ ] **Step 7: 빌드 green 확인**

Run: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`. (분할 전후 테스트 메서드 수 동일: 주문 4 + 재고/발주 9 = 13)

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "refactor: AdminController를 OrderAdmin(oms)+InventoryAdmin(wms)으로 분할 (Phase 2 web 분리 4/5)

배송(OMS)·재고/발주(WMS) 혼재 컨트롤러를 컨텍스트별 두 컨트롤러로 분리. URL 불변. 슬라이스 테스트도 둘로 분할(주문 4 + 재고/발주 9건). 순수 이동.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Home·Main → 공용 `web/`

교차/중립 컨트롤러 `HomeController`·`MainController`를 공용 `web/` 패키지로 모은다.

**Files:**
- Move: `controller/HomeController.java` → `web/HomeController.java`; `controller/main/MainController.java` → `web/MainController.java`
- Modify (test import 추가): `controller/main/MainControllerMvcTest.java`

**Interfaces:**
- Produces: `com.jhg.hgpage.web.HomeController`, `com.jhg.hgpage.web.MainController`. 매핑(`/`, `/main`) 불변.

- [ ] **Step 1: 파일 이동**

```bash
git mv src/main/java/com/jhg/hgpage/controller/HomeController.java      src/main/java/com/jhg/hgpage/web/HomeController.java
git mv src/main/java/com/jhg/hgpage/controller/main/MainController.java src/main/java/com/jhg/hgpage/web/MainController.java
```

- [ ] **Step 2: package 선언 변경**

- `HomeController.java`: `package com.jhg.hgpage.controller;` → `package com.jhg.hgpage.web;`
- `MainController.java`: `package com.jhg.hgpage.controller.main;` → `package com.jhg.hgpage.web;`

- [ ] **Step 3: 테스트에 import 추가**

- `controller/main/MainControllerMvcTest.java`: `import com.jhg.hgpage.web.MainController;` 추가 (`@WebMvcTest(MainController.class)`가 same-package 참조였음)

- [ ] **Step 4: 빌드 green 확인**

Run: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`. (옛 `controller/`·`api/` 최상위 패키지는 비게 됨)

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "refactor: Home·Main 컨트롤러를 공용 web 패키지로 이동 (Phase 2 web 분리 5/5)

교차/중립(BFF 성격) 컨트롤러를 공용 web/에 집결. 옛 controller/·api/ 최상위 패키지 정리. 순수 이동.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 최종 검증

- [ ] 전체 빌드: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat build` → `BUILD SUCCESSFUL`, 테스트 수 회귀 없음.
- [ ] `git grep -n "com.jhg.hgpage.domain.dto.view"` → 결과 0(완전 이동 확인).
- [ ] `git grep -n "com.jhg.hgpage.controller.form"` → 결과 0.
- [ ] 앱 기동 후 메인/장바구니/주문/관리자 화면 정상(선택적 수동 확인 — 순수 이동이라 회귀 위험 낮음).
- [ ] `CLAUDE.md`의 패키지 구조 섹션 갱신(web 계층 분리 반영) — 별도 문서 커밋.
