# Replenishment Request Rollout Design

## Goal

Deploy the WMS replenishment-request workflow without interrupting the OMS administrator's existing inventory-adjustment, purchase-order, or receiving actions during the transition.

## Release candidates

| Stage | Repository | Commit | Compatibility |
|---|---|---|---|
| WMS expand | `jhg-wms-project` | `84de725` | Adds replenishment-request APIs and UI while retaining legacy write REST endpoints. |
| OMS switch | `jhg-commerce-project` | `89f8d80` | Uses replenishment requests and removes OMS calls to legacy WMS write endpoints. |
| WMS contract | `jhg-wms-project` | `0278583` | Removes legacy WMS write REST endpoints after the OMS switch is verified. |

The expand candidate is the parent of WMS commit `a71213b`, which removes the legacy REST surface. The contract candidate includes that removal and accompanying documentation.

## Rollout sequence

1. Run the WMS expansion candidate's complete test suite. Create and push a dedicated `expand` branch from `84de725`; do not rewrite the existing implementation branch.
2. Start the WMS implementation and OMS switch candidates locally. Verify request submission, identical `requestKey` retry convergence, WMS approval and rejection, purchase-order receipt, request fulfillment, and OMS backorder promotion.
3. Deploy WMS expand to Railway. Verify the existing deployed OMS can still use its legacy administrator flows, then verify the new WMS request API is healthy.
4. Merge and deploy the OMS switch branch. Verify the OMS request UI and WMS review UI end to end in Railway.
5. Deploy WMS contract only after the OMS switch smoke test proves there are no OMS callers of `/api/inventory/adjust` or `/api/purchase-orders`.

## Compatibility and failure handling

- Never deploy the WMS contract candidate before the OMS switch has passed its Railway smoke test.
- If WMS expand fails, keep the currently deployed WMS version; do not deploy OMS switch.
- If OMS switch fails after WMS expand, keep WMS expand deployed because it remains compatible with the existing OMS.
- If contract deployment fails, restore the WMS expand candidate; the already-switched OMS does not depend on the removed endpoints.
- No force-push, history rewrite, or destructive database action is part of this rollout.

## Verification

Each candidate must report a successful complete Gradle test run. The Railway smoke test must demonstrate:

1. OMS can read WMS inventory and submit a replenishment request.
2. Retrying the same `requestKey` does not create a duplicate request.
3. WMS can approve or reject the request, and approval creates a linked purchase order.
4. Receiving the linked purchase order marks the request fulfilled, increases inventory, and promotes an eligible OMS `BACKORDERED` order.
5. A direct WMS purchase order still receives successfully.
