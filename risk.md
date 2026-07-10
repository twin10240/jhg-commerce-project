# OMS Risk Notes

## 1. `catalog.Product` still imports OMS domain

- Location: `src/main/java/com/jhg/hgpage/catalog/Product.java`
- Risk: `Product` keeps an unused reverse reference to `oms.domain.CartItem`, so `catalog` is not fully independent from OMS.
- Evidence: `Product.cartItems` is only declared; no production code reads `getCartItems`.
- Fix: delete `Product.cartItems` and the `CartItem` import. Keep the owning `CartItem.product` side.
- Resolved (2026-07-10): removed the dead `Product.cartItems` mapping and `CartItem` import; `CartItem.product` remains the owning reference.

## 2. Purchase-order create errors can bypass admin flash

- Location: `src/main/java/com/jhg/hgpage/wms/adapter/WmsPurchaseOrderAdapter.java`
- Risk: `receive()` maps WMS 404/409 into user-facing exceptions, but `create()` lets WMS 4xx bubble as `HttpClientErrorException`.
- Impact: invalid purchase-order input can render an error page instead of returning to `/admin/purchase-orders` with flash.
- Fix: catch `HttpClientErrorException` in `create()` and throw `IllegalArgumentException` with a short message. Add one adapter test.
- Resolved (2026-07-10): `create()` now maps WMS 4xx `HttpClientErrorException` to `IllegalArgumentException`, covered by a `withBadRequest()` adapter test.

## 3. WMS callback endpoint is public inside the app

- Location: `src/main/java/com/jhg/hgpage/config/SecurityConfig.java`
- Risk: `/api/replenishments` is `permitAll` and CSRF-exempt.
- Current mitigation: Railway private networking keeps WMS API and callback off the public internet.
- Fix before public exposure: add authentication/signature validation or keep the endpoint reachable only from private network.

## 4. Flyway test coverage stops at V1

- Location: `src/test/java/com/jhg/hgpage/FlywayMigrationTest.java`
- Risk: V2/V3 are not automatically exercised in the H2 Flyway test.
- Current mitigation: V3 was verified through Railway deploy logs and production smoke tests.
- Fix: add a PostgreSQL-backed migration check only when schema migration churn justifies the extra test cost.
