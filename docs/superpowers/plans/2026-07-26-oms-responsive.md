# OMS Responsive UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every OMS customer and administrator screen usable from a 375px mobile viewport through desktop without changing business behavior or the established visual theme.

**Architecture:** Keep shared responsive primitives in `static/css/app.css` and retain the existing page-local CSS for layouts unique to cart, checkout, order detail, and administrator tables. Use the existing 720px and 980px breakpoints, CSS-only layout changes, and table-local scrolling where hiding data would remove necessary work context.

**Tech Stack:** Thymeleaf HTML, CSS Grid/Flexbox, JUnit 5, AssertJ, Spring Boot 3.5, Chrome viewport verification

## Global Constraints

- Cover customer and OMS administrator screens.
- Support 375×812, 768×1024, and 1280×800 or wider viewports.
- Do not add JavaScript behavior, CSS frameworks, or dependencies.
- Preserve existing semantic HTML, labels, keyboard behavior, dark mode, and desktop layout.
- Hide only administrator table columns that are nonessential on mobile.
- Page-level horizontal overflow is not allowed; wide tables may scroll inside their own wrapper.

---

### Task 1: Responsive contract test

**Files:**
- Create: `src/test/java/com/jhg/hgpage/template/ResponsiveTemplateContractTest.java`
- Test: `src/test/java/com/jhg/hgpage/template/ResponsiveTemplateContractTest.java`

**Interfaces:**
- Consumes: CSS and HTML files under `src/main/resources`
- Produces: a regression contract for shared breakpoints, cart mobile rows, order-detail mobile layout, and administrator table wrappers

- [ ] **Step 1: Write the failing test**

```java
package com.jhg.hgpage.template;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResponsiveTemplateContractTest {

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void sharedLayoutHasMobileOverflowAndTouchRules() throws Exception {
        String css = read("src/main/resources/static/css/app.css");

        assertThat(css).contains("@media(max-width:720px)");
        assertThat(css).contains("body{padding:16px}");
        assertThat(css).contains(".site-nav{max-width:100%;overflow-x:auto");
        assertThat(css).contains(".app-card{padding:18px}");
        assertThat(css).contains(".app-btn{min-height:44px}");
    }

    @Test
    void cartRowsBecomeMobileCards() throws Exception {
        String html = read("src/main/resources/templates/cart.html");

        assertThat(html).contains("@media (max-width: 720px)");
        assertThat(html).contains(".grid.head{display:none}");
        assertThat(html).contains(".grid.row{grid-template-columns:28px minmax(0,1fr)");
        assertThat(html).contains(".footer{align-items:stretch;flex-direction:column}");
    }

    @Test
    void orderDetailStacksMetadataAndActions() throws Exception {
        String html = read("src/main/resources/templates/orderview.html");

        assertThat(html).contains("@media (max-width: 720px)");
        assertThat(html).contains(".meta{grid-template-columns:1fr}");
        assertThat(html).contains(".actions{align-items:stretch;flex-direction:column}");
    }

    @Test
    void administratorTablesUseLocalOverflow() throws Exception {
        String inventory = read("src/main/resources/templates/admin/inventory.html");
        String replenishment = read("src/main/resources/templates/admin/replenishment-requests.html");
        String shipping = read("src/main/resources/templates/admin/orders.html");

        assertThat(inventory).contains(".table-wrap{overflow-x:auto}");
        assertThat(inventory).contains(".inventory-table{min-width:620px}");
        assertThat(replenishment).contains(".table-wrap{overflow-x:auto}");
        assertThat(shipping).contains(".table-wrap{overflow-x:auto}");
        assertThat(shipping).contains(".bulk-actions{align-items:stretch;width:100%}");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  bash gradlew test --tests "com.jhg.hgpage.template.ResponsiveTemplateContractTest" --rerun-tasks
```

Expected: FAIL because the shared 720px contract and mobile cart/order-detail/administrator rules are absent.

---

### Task 2: Shared customer and administrator layout

**Files:**
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/com/jhg/hgpage/template/ResponsiveTemplateContractTest.java`

**Interfaces:**
- Consumes: existing `.site-nav`, `.app-shell`, `.app-card`, `.app-btn`, `.product-detail`, and `.order-card` classes
- Produces: shared width containment, touch targets, navigation scrolling, and mobile card stacking used by all OMS templates

- [ ] **Step 1: Replace the existing 700px media rule with the shared 720px contract**

```css
@media(max-width:720px){
  body{padding:16px}
  .site-nav{max-width:100%;gap:12px;overflow-x:auto;overscroll-behavior-inline:contain;white-space:nowrap}
  .site-brand{margin-right:4px}
  .app-shell{min-width:0}
  .app-card{padding:18px}
  .app-title{font-size:24px}
  .app-btn{min-height:44px}
  .product-detail{grid-template-columns:1fr}
  .product-art{min-height:200px}
  .purchase-actions{align-items:stretch;flex-direction:column}
  .purchase-actions input,.purchase-actions .app-btn{box-sizing:border-box;width:100%}
  .order-card{grid-template-columns:1fr}
  .timeline-step{font-size:12px}
  .inline-message{top:16px;left:16px;right:16px;max-width:none}
}
```

- [ ] **Step 2: Add global width containment without changing desktop sizing**

```css
*,*:before,*:after{box-sizing:border-box}
html,body{max-width:100%;overflow-x:hidden}
img,svg,video{max-width:100%}
```

- [ ] **Step 3: Run the shared-layout test**

Run:

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  bash gradlew test --tests "com.jhg.hgpage.template.ResponsiveTemplateContractTest.sharedLayoutHasMobileOverflowAndTouchRules" --rerun-tasks
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/css/app.css \
  src/test/java/com/jhg/hgpage/template/ResponsiveTemplateContractTest.java
git commit -m "feat(oms): add shared responsive layout"
```

---

### Task 3: Customer transaction screens

**Files:**
- Modify: `src/main/resources/templates/cart.html`
- Modify: `src/main/resources/templates/orderdetail.html`
- Modify: `src/main/resources/templates/orderview.html`
- Test: `src/test/java/com/jhg/hgpage/template/ResponsiveTemplateContractTest.java`

**Interfaces:**
- Consumes: shared responsive rules from Task 2
- Produces: mobile cart cards, stacked checkout fields and summary, and readable order details

- [ ] **Step 1: Convert cart rows to mobile cards**

Add to the existing cart 720px media block:

```css
body{padding:16px}
.card header{align-items:stretch;flex-direction:column;gap:14px}
.card header .toolbar{display:grid;grid-template-columns:1fr 1fr}
.card header .toolbar .btn{width:100%}
.grid.head{display:none}
.grid.row{grid-template-columns:28px minmax(0,1fr);gap:12px;padding:16px}
.grid.row>div:nth-child(n+3){grid-column:2}
.grid.row .price,.grid.row .line-total{text-align:left}
.footer{align-items:stretch;flex-direction:column;gap:14px}
.summary{align-items:stretch;flex-direction:column;gap:8px}
.footer .toolbar,.footer .toolbar .btn{width:100%}
```

- [ ] **Step 2: Stack checkout and order-detail content**

In `orderdetail.html`, keep the existing 980px single-column layout and ensure the 640px block makes the action button, table wrapper, and summary fit the viewport:

```css
.actions{align-items:stretch;flex-direction:column}
.actions .btn{width:100%}
.table-wrap{overflow-x:auto}
.table{min-width:560px}
```

Wrap the checkout table in `<div class="table-wrap">`.

In `orderview.html`, add:

```css
@media (max-width: 720px){
  body{padding:16px}
  .wrap{margin:0 auto;padding:0}
  .panel{padding:20px 18px}
  .meta{grid-template-columns:1fr}
  .meta dt{margin-top:8px}
  .actions{align-items:stretch;flex-direction:column;gap:10px}
  .actions form,.actions .btn{box-sizing:border-box;width:100%}
  .block{overflow-x:auto}
  table{min-width:520px}
}
```

- [ ] **Step 3: Run customer responsive tests**

Run:

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  bash gradlew test \
  --tests "com.jhg.hgpage.template.ResponsiveTemplateContractTest.cartRowsBecomeMobileCards" \
  --tests "com.jhg.hgpage.template.ResponsiveTemplateContractTest.orderDetailStacksMetadataAndActions" \
  --tests "com.jhg.hgpage.template.CartTemplateThemeTest" \
  --rerun-tasks
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates/cart.html \
  src/main/resources/templates/orderdetail.html \
  src/main/resources/templates/orderview.html
git commit -m "feat(oms): make customer flows responsive"
```

---

### Task 4: Product and administrator screens

**Files:**
- Modify: `src/main/resources/templates/main.html`
- Modify: `src/main/resources/templates/admin/inventory.html`
- Modify: `src/main/resources/templates/admin/replenishment-requests.html`
- Modify: `src/main/resources/templates/admin/orders.html`
- Test: `src/test/java/com/jhg/hgpage/template/ResponsiveTemplateContractTest.java`

**Interfaces:**
- Consumes: shared responsive rules from Task 2
- Produces: one-column mobile catalog, stacked administrator forms and statistics, and table-local horizontal scrolling

- [ ] **Step 1: Finish catalog mobile containment**

In the existing `main.html` 640px media block:

```css
body{padding:16px}
.container{min-width:0}
.product{min-width:0}
.row .btn{width:100%}
.paging{flex-wrap:wrap}
```

- [ ] **Step 2: Make inventory and replenishment layouts mobile-safe**

Keep the existing mobile-hidden inventory columns and add:

```css
.table-wrap{max-width:100%;overflow-x:auto}
.inventory-table{min-width:620px}
```

For replenishment requests, retain the one-column form and add `max-width:100%` to `.table-wrap`.

- [ ] **Step 3: Keep shipping actions accessible**

In `admin/orders.html`, extend the existing 720px block:

```css
.bulk-actions{align-items:stretch;width:100%}
.bulk-actions .btn{width:100%}
.table-wrap{max-width:100%;overflow-x:auto}
```

Keep the shipping table minimum width because order selection, status, and action columns must remain available.

- [ ] **Step 4: Run the administrator contract test**

Run:

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  bash gradlew test --tests "com.jhg.hgpage.template.ResponsiveTemplateContractTest.administratorTablesUseLocalOverflow" --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/main.html \
  src/main/resources/templates/admin/inventory.html \
  src/main/resources/templates/admin/replenishment-requests.html \
  src/main/resources/templates/admin/orders.html
git commit -m "feat(oms): make administrator screens responsive"
```

---

### Task 5: Visual and regression verification

**Files:**
- Test: all OMS tests

**Interfaces:**
- Consumes: completed responsive CSS and templates
- Produces: verified mobile, tablet, and desktop OMS screens with no page-level horizontal overflow

- [ ] **Step 1: Start OMS and WMS without resetting databases**

Run each project with:

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home bash gradlew bootRun
```

- [ ] **Step 2: Verify customer screens**

At 375×812, 768×1024, and 1280×800 inspect:

- `/login`
- `/main`
- `/cart`
- `/orders`
- one `/orders/{id}`

At each size confirm there is no page-level horizontal overflow, all primary actions remain visible, and text does not overlap.

- [ ] **Step 3: Verify administrator screens**

At the same sizes inspect:

- `/admin/inventory`
- `/admin/replenishment-requests`
- `/admin/orders`

Confirm tables scroll only inside `.table-wrap`, mobile-hidden columns do not remove required actions, and bulk shipment controls remain usable.

- [ ] **Step 4: Run the full test suite without cache reuse**

Run:

```bash
JAVA_HOME=/Users/jo/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home \
  bash gradlew test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL` with all test tasks executed.

- [ ] **Step 5: Check the final diff**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and only the responsive implementation plus the previously acknowledged session-cookie changes remain uncommitted.
