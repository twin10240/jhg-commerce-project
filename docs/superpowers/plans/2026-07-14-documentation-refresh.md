# OMS Documentation Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the five living OMS documents with the physically separated, authenticated WMS now deployed from `jhg-wms-project`.

**Architecture:** Give each document one job: README for onboarding, CLAUDE for detailed engineering history, risk.md for open risks, the product brief for vision, and the OMS README for runtime boundaries. Preserve historical plans/specs unchanged and make no application-code changes.

**Tech Stack:** Markdown, Spring Boot 3.5.5, OMS Java 17, WMS Java 21, Railway, PostgreSQL, HTTP Basic

---

## File Map

- Modify `README.md`: current public project overview, architecture, execution, deployment, and test summary.
- Modify `CLAUDE.md`: authoritative engineering state and retained change history.
- Modify `risk.md`: open operational risks first; resolved items compressed.
- Modify `docs/기획서.md`: completed Phase 3 roadmap and current system responsibilities.
- Modify `src/main/java/com/jhg/hgpage/oms/README.md`: current OMS/WMS runtime boundary and recovery flow.
- Preserve `docs/superpowers/plans/**` and older `docs/superpowers/specs/**` as historical records.

### Task 1: Refresh the public README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Replace stale roadmap and architecture statements**

Set Phase 3 to complete and Phase 4 to optional. Replace the in-process WMS domain tree with the actual OMS-side `wms/adapter`, `wms/dto`, and `wms/web` packages, and state that inventory, reservation, and purchase-order persistence live in the separate WMS app.

- [ ] **Step 2: Update local and Railway operation guidance**

Document that both apps must run locally, OMS uses `WMS_BASE_URL`, and both apps must share `WMS_BASIC_USER`/`WMS_BASIC_PASSWORD`. State that the public WMS URL requires Basic authentication while OMS-to-WMS traffic keeps using Railway private networking.

- [ ] **Step 3: Correct the test description**

Remove references to deleted in-process WMS tests and record the fresh OMS result: 163 tests, zero failures/errors/skips on 2026-07-14.

### Task 2: Refresh the authoritative engineering guide

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Mark the current Phase 3 outcome clearly**

Keep the existing S0-S4 history, but make the Phase 3 heading completed and add the 2026-07-10/13 WMS hardening outcome: HTTP Basic, public WMS domain, reservation quantity SSOT, unique `productId`, and optimistic locking.

- [ ] **Step 2: Correct current architecture and domain sections**

Describe OMS `wms/` as adapters/DTO/web proxy only. Remove current-tense `Product ↔ Inventory` and in-process WMS domain descriptions. Explain that WMS `Reservation.qtyByProductId` drives ship/release and that WMS is the inventory SSOT.

- [ ] **Step 3: Update deployment and operating constraints**

Record the public WMS URL, private `WMS_BASE_URL`, shared Basic variables, and safe rollout order: deploy credential-sending OMS before authentication-enforcing WMS when credentials change.

- [ ] **Step 4: Align current risks and priorities**

Keep history intact while making the open items match `risk.md`: callback private-network dependency, adjust false-timeout, migration coverage, and Basic credential mismatch. Remove already-completed Phase 2 cleanup from current priorities.

### Task 3: Make risk.md an open-risk ledger

**Files:**
- Modify: `risk.md`

- [ ] **Step 1: Put open risks first**

Document four open risks with mitigation and trigger for further work: unauthenticated OMS replenishment callback protected by private networking, synchronous adjust false-timeout and unsafe retry, Flyway V2-V4 lacking automated PostgreSQL coverage, and shared Basic credentials causing total OMS-to-WMS 401 failure when mismatched.

- [ ] **Step 2: Compress resolved risks**

Keep the removed Product reverse mapping and purchase-order 4xx mapping as a short dated resolved section without repeating obsolete fix instructions.

### Task 4: Update the product brief

**Files:**
- Modify: `docs/기획서.md`

- [ ] **Step 1: Update the roadmap**

Mark Phase 3 complete and Phase 4 optional. Describe the delivered result rather than the extraction plan.

- [ ] **Step 2: Correct system boundaries and communication**

State that OMS owns customers, carts, orders, and backorder policy; WMS owns inventory, reservation ledgers, purchase orders, receipts, and shipments. Describe the three channels: availability query, idempotent inventory writes by `orderId`, and replenishment callback with periodic sweep recovery.

### Task 5: Update the OMS boundary README

**Files:**
- Modify: `src/main/java/com/jhg/hgpage/oms/README.md`

- [ ] **Step 1: Replace future-tense separation text**

Describe the current physically separate WMS and the OMS adapters implementing the contract ports. Remove the claim that OMS and WMS still share one JVM.

- [ ] **Step 2: Document the current order and recovery flow**

Explain Basic-authenticated reserve/ship/release calls, reserve retry and BACKORDERED fallback, callback-driven FIFO promotion, and the scheduled sweeper that recovers missed callbacks.

- [ ] **Step 3: Clarify ownership**

State that OMS order status and WMS reservation status are independent state machines joined by `orderId`; WMS reservation quantities are the source used for ship/release.

### Task 6: Cross-document verification

**Files:**
- Verify: `README.md`
- Verify: `CLAUDE.md`
- Verify: `risk.md`
- Verify: `docs/기획서.md`
- Verify: `src/main/java/com/jhg/hgpage/oms/README.md`

- [ ] **Step 1: Search for stale present-tense claims**

Run:

```powershell
Select-String -Path README.md,CLAUDE.md,risk.md,docs\기획서.md,src\main\java\com\jhg\hgpage\oms\README.md -Encoding UTF8 -Pattern 'Phase 3.*다음','현재는 단일 모놀리스','Phase 3에서.*진화','Product ↔ Inventory \(1:1\)','InventoryServiceTest','PurchaseOrderServiceTest'
```

Expected: no stale current-state matches. Historical passages in the retained CLAUDE change log may match test class names and are acceptable after manual review.

- [ ] **Step 2: Confirm required current facts**

Run:

```powershell
Select-String -Path README.md,CLAUDE.md,risk.md,docs\기획서.md,src\main\java\com\jhg\hgpage\oms\README.md -Encoding UTF8 -Pattern 'WMS_BASIC_USER','private networking','163','Phase 3.*완료','예약.*SSOT|qtyByProductId'
```

Expected: onboarding/engineering documents contain the relevant facts without forcing every document to duplicate all facts.

- [ ] **Step 3: Validate paths and Markdown diff**

Run:

```powershell
Test-Path README.md
Test-Path CLAUDE.md
Test-Path risk.md
Test-Path docs\기획서.md
Test-Path src\main\java\com\jhg\hgpage\oms\README.md
git -c safe.directory=C:/study/jhg-commerce-project diff --check
git -c safe.directory=C:/study/jhg-commerce-project status --short
```

Expected: all paths are `True`, `diff --check` exits 0, and only the five approved documents plus this plan are changed.

- [ ] **Step 4: Review the final diff against the spec**

Run:

```powershell
git -c safe.directory=C:/study/jhg-commerce-project diff -- README.md CLAUDE.md risk.md docs/기획서.md src/main/java/com/jhg/hgpage/oms/README.md
```

Expected: no code/config changes, no historical plan/spec rewrites, and no contradictions about WMS authentication or network routing.
