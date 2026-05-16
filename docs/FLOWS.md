# Flow Specifications
 
Each flow is independently buildable and testable.
Build in order: Flow 1 → 2 → 3 → 4 → 8 → 9.
 
---
 
## Flow 1: POS Event → SIS → RDS → IMS → Stock Alert → EventBridge
 
**What this proves:**
- Kinesis inbound ingestion
- Lambda-as-adapter pattern (no domain logic in Lambda)
- SIS ECS hexagonal architecture
- RDS sales schema write
- EventBridge domain event publish
- IMS SQS consumption
- Inventory position update with optimistic locking
- Stock alert creation
- IMS EventBridge publish
 
**Components involved:**
- Kinesis Data Stream: `smartretail-events-{env}`
- Lambda: Kinesis Consumer (SIS inbound adapter)
- ECS: SIS (writes to sales schema)
- DynamoDB: idempotency-keys table
- S3: smartretail-events bucket
- EventBridge: `smartretail-events-{env}` bus
- SQS: `smartretail-ims-sales-{env}` queue
- ECS: IMS (writes to inventory schema, publishes alert)
- SQS: `smartretail-re-alert-{env}` FIFO queue (receives IMS alert)
 
**Trigger:**
Run `scripts/publish-pos-event.py` with a test transaction payload.
 
**Observable evidence — all must be true for Flow 1 to pass:**
 
| # | Check | How to verify |
|---|-------|--------------|
| 1.1 | Kinesis record appears on stream | CloudWatch Metrics: GetRecords.IteratorAgeMilliseconds |
| 1.2 | Lambda invoked | CloudWatch Logs: /aws/lambda/smartretail-kinesis-consumer |
| 1.3 | DynamoDB idempotency key written | AWS CLI: `aws dynamodb get-item --table-name smartretail-idempotency-keys-dev --key '{"event_id":{"S":"<sha256>"}}'` |
| 1.4 | SIS processes event | CloudWatch Logs: /smartretail/sis/dev — look for "SalesTransactionEvent processed" |
| 1.5 | RDS sales_events row created | Query: `SELECT * FROM sales.sales_events WHERE transaction_id = '<uuid>'` |
| 1.6 | S3 raw event archived | AWS CLI: `aws s3 ls s3://smartretail-events-dev/` |
| 1.7 | EventBridge SalesTransactionEvent published | CloudWatch Logs: IMS service — look for SQS message received |
| 1.8 | IMS inventory_positions updated | Query: `SELECT on_hand FROM inventory.inventory_positions WHERE sku_id = '<skuId>' AND dc_id = '<dcId>'` — should decrease by quantity |
| 1.9 | Stock alert raised (if ATP < reorder_point) | Query: `SELECT * FROM inventory.stock_alerts WHERE status = 'ACTIVE' ORDER BY raised_at DESC LIMIT 5` |
| 1.10 | IMS publishes InventoryAlertEvent | CloudWatch Logs: /smartretail/ims/dev — look for "InventoryAlertEvent published" |
 
**Duplicate test:**
Run `scripts/publish-pos-event.py` again with the SAME transactionId.
SIS should return 409 Conflict. No new sales_events row. DynamoDB key already exists.
 
---
 
## Flow 2: Inventory Alert → RE Auto-Approve → RDS State Transition
 
**What this proves:**
- RE FIFO SQS consumption with dcId+skuId ordering
- Replenishment rules lookup from RDS
- Auto-approve threshold decision (DB-backed state machine)
- Optimistic locking on purchase_orders INSERT
- EventBridge PurchaseOrderEvent publish
 
**Components involved:**
- SQS: `smartretail-re-alert-{env}` FIFO queue
- ECS: RE (reads replenishment schema, writes purchase_orders)
- EventBridge: PurchaseOrderEvent
 
**Pre-condition:**
Flow 1 must have published an InventoryAlertEvent to the RE FIFO queue.
Alternatively: inject a test InventoryAlertEvent directly into the RE SQS queue
using `scripts/publish-pos-event.py --flow2-direct`.
 
**Two sub-scenarios to test:**
 
### Scenario 2a: Auto-approve (totalValue ≤ threshold)
 
Ensure replenishment_rules seed data has `auto_approve_threshold` high enough
that the computed totalValue (quantity × cost_per_unit) falls below it.
 
**Observable evidence:**
| # | Check | How to verify |
|---|-------|--------------|
| 2a.1 | RE consumes alert | CloudWatch Logs: /smartretail/re/dev — "InventoryAlertEvent received" |
| 2a.2 | Replenishment rule found | CloudWatch Logs: "Rule found for SKU/DC: lead_time=X, threshold=Y" |
| 2a.3 | Auto-approve decision made | CloudWatch Logs: "totalValue <= autoApproveThreshold — auto-approving" |
| 2a.4 | purchase_orders row inserted with status APPROVED | Query: `SELECT po_id, workflow_status, version FROM replenishment.purchase_orders WHERE sku_id = '<skuId>' ORDER BY created_at DESC LIMIT 1` — should show APPROVED, version=0 |
| 2a.5 | po_line_items row inserted | Query: `SELECT * FROM replenishment.po_line_items WHERE po_id = '<poId>'` |
| 2a.6 | PurchaseOrderEvent published to EventBridge | CloudWatch Logs: /smartretail/re/dev — "PurchaseOrderEvent published, status=APPROVED" |
 
### Scenario 2b: Manual approval required (totalValue > threshold)
 
Ensure replenishment_rules seed data has `auto_approve_threshold` set to 0
for at least one SKU/DC combination so totalValue always exceeds it.
 
**Observable evidence:**
| # | Check | How to verify |
|---|-------|--------------|
| 2b.1 | purchase_orders row inserted with status PENDING_APPROVAL | Query: workflow_status = 'PENDING_APPROVAL', version = 0 |
| 2b.2 | PurchaseOrderEvent published with PENDING_APPROVAL | CloudWatch Logs — "PurchaseOrderEvent published, status=PENDING_APPROVAL" |
| 2b.3 | SC Planner Console MFE shows PO in approval queue | Open MFE — PO should appear in "Pending Approval" list |
 
---
 
## Flow 3: SC Planner MFE → RE Approve/Reject → RDS → EventBridge
 
**What this proves:**
- MFE → API Gateway → VPC Link → ECS request path
- Cognito JWT authentication from MFE
- SC_PLANNER role enforcement at service layer
- Optimistic locking on purchase_orders UPDATE
- State machine transition: PENDING_APPROVAL → APPROVED or REJECTED
- EventBridge domain event publish
 
**Pre-condition:** Flow 2 Scenario 2b must have a PO in PENDING_APPROVAL status.
 
**Components involved:**
- Browser → SC Planner Console MFE
- API Gateway (internal stage, JWT authoriser)
- ECS: RE
 
**Scenario 3a: Approve**
 
Steps:
1. Open SC Planner Console MFE in browser
2. Sign in with a Cognito user in SC_PLANNER group
3. Navigate to "Pending Approval" tab
4. Find the PO from Flow 2 Scenario 2b
5. Click "Approve"
 
**Observable evidence:**
| # | Check | How to verify |
|---|-------|--------------|
| 3a.1 | MFE displays PENDING_APPROVAL PO | Visual inspection |
| 3a.2 | Approve request reaches RE | CloudWatch Logs: "POST /v1/replenishment/orders/{poId}/approve received" |
| 3a.3 | JWT SC_PLANNER role validated | CloudWatch Logs: "Role validation passed: SC_PLANNER" |
| 3a.4 | Optimistic lock update succeeds | CloudWatch Logs: "PO updated to APPROVED, version=1" |
| 3a.5 | RDS row updated | Query: `SELECT workflow_status, version, approved_by FROM replenishment.purchase_orders WHERE po_id = '<poId>'` — APPROVED, version=1, approved_by set |
| 3a.6 | PurchaseOrderEvent published | CloudWatch Logs: "PurchaseOrderEvent published status=APPROVED" |
| 3a.7 | MFE reflects APPROVED status | Refresh MFE — PO no longer in pending queue or shows APPROVED |
 
**Scenario 3b: Reject**
 
Same as above but click "Reject" instead.
 
**Observable evidence:**
| # | Check | How to verify |
|---|-------|--------------|
| 3b.1 | workflow_status = REJECTED | Query: `SELECT workflow_status, rejected_by, rejection_reason FROM replenishment.purchase_orders WHERE po_id = '<poId>'` |
| 3b.2 | MFE reflects REJECTED status | Visual inspection |
 
**Scenario 3c: Wrong role rejection**
 
Sign in with a Cognito user in STORE_MANAGER group.
Attempt to call POST /v1/replenishment/orders/{poId}/approve directly.
 
**Expected:** 403 Forbidden
```json
{ "errorCode": "UNAUTHORIZED", "message": "SC_PLANNER or ADMIN role required" }
```
 
**Scenario 3d: Wrong status rejection**
 
Attempt to approve a PO that is already APPROVED (not PENDING_APPROVAL).
 
**Expected:** 409 Conflict
```json
{
  "errorCode": "INVALID_STATUS_TRANSITION",
  "message": "PO cannot be approved from status APPROVED. Status must be PENDING_APPROVAL.",
  "currentStatus": "APPROVED"
}
```
 
---
 
## Flow 4: ARS → Store Manager Dashboard MFE
 
**What this proves:**
- ARS read-only aggregation across multiple RDS schemas
- No cross-schema SQL joins
- dataFreshness derivation
- MFE rendering real data from RDS
 
**Pre-condition:** Flows 1–3 must have produced data in sales, inventory,
and replenishment schemas. Seed data provides forecast data.
 
**Components involved:**
- Browser → Store Manager Dashboard MFE
- API Gateway (internal stage)
- ECS: ARS (reads across sales, inventory, forecasting, replenishment schemas)
 
Steps:
1. Open Store Manager Dashboard MFE in browser
2. Sign in with Cognito user in STORE_MANAGER group
3. Select DC from dropdown (e.g. DC-LONDON)
4. Dashboard loads
 
**Observable evidence:**
| # | Check | How to verify |
|---|-------|--------------|
| 4.1 | MFE renders without error | Visual inspection — no error state shown |
| 4.2 | ARS receives request | CloudWatch Logs: "GET /v1/dashboard/store-manager?dcId=DC-LONDON" |
| 4.3 | dcId enforcement for STORE_MANAGER | CloudWatch Logs: "STORE_MANAGER role — enforcing dcId scope: DC-LONDON" |
| 4.4 | Parallel RDS queries execute | CloudWatch Logs: "Executing parallel dashboard queries" — 4 queries logged |
| 4.5 | No cross-schema JOIN in SQL | Code review: confirm ARS repository classes use separate queries, not JOINs |
| 4.6 | Inventory KPI cards show real data | Visual: on_hand counts match RDS inventory_positions for DC-LONDON |
| 4.7 | Alert count matches RDS | Visual: alert count matches `SELECT COUNT(*) FROM inventory.stock_alerts WHERE status = 'ACTIVE'` |
| 4.8 | dataFreshness timestamp displayed | Visual: "Data as of HH:MM" visible on dashboard |
 
---
 
## Flow 8: Executive Dashboard — MAPE Trend + Forecast Accuracy
 
**What this proves:**
- ARS Executive dashboard endpoint
- Pre-populated forecast data displays correctly
- Recharts rendering with real data
- EXECUTIVE role access
 
**Pre-condition:** Seed data V7__seed_data.sql must be applied.
This populates forecasting.forecast_runs and forecasting.demand_forecasts
with 30 days of simulated MAPE values and forecast data.
 
**Components involved:**
- Browser → Executive Insights Dashboard MFE
- API Gateway (internal stage)
- ECS: ARS (reads from forecasting, inventory schemas)
 
Steps:
1. Open Executive Insights Dashboard MFE
2. Sign in with Cognito user in EXECUTIVE group
3. Dashboard loads
 
**Observable evidence:**
| # | Check | How to verify |
|---|-------|--------------|
| 8.1 | MAPE trend chart renders | Visual: line chart showing MAPE values for last 30 days |
| 8.2 | MAPE values match seed data | Visual: values should trend downward per seed data design |
| 8.3 | Forecast accuracy status shown | Visual: "Within threshold" badge visible |
| 8.4 | Stockout frequency KPI renders | Visual: count from seed data matches |
| 8.5 | EXECUTIVE cannot access SC Planner data | Browser: GET /v1/dashboard/sc-planner returns 403 |
 
---
 
## Flow 9: SC Planner — Supplier Performance Scorecard
 
**What this proves:**
- ARS supplier-performance endpoint
- Cross-schema aggregation in application code (not SQL JOINs)
- Pre-populated supplier and shipment data displays correctly
- SC_PLANNER role access
 
**Pre-condition:** Seed data V7__seed_data.sql must be applied.
This populates supplier.supplier_records, supplier.supplier_pos,
supplier.shipment_updates, and replenishment.purchase_orders with
realistic performance data for 5 test suppliers.
 
**Components involved:**
- Browser → SC Planner Console MFE (supplier performance tab)
- API Gateway (internal stage)
- ECS: ARS (reads from supplier, replenishment schemas)
 
Steps:
1. Open SC Planner Console MFE
2. Sign in with Cognito user in SC_PLANNER group
3. Navigate to "Supplier Performance" tab
 
**Observable evidence:**
| # | Check | How to verify |
|---|-------|--------------|
| 9.1 | Supplier scorecard table renders | Visual: 5 suppliers from seed data visible |
| 9.2 | On-time delivery rates show | Visual: percentages match seed data calculations |
| 9.3 | Open exception count per supplier | Visual: counts match seed data |
| 9.4 | No cross-schema JOIN used | Code review: ARS fetches supplier data and replenishment data in separate queries, merges in Java |
| 9.5 | dataFreshness displayed | Visual: "Data as of HH:MM" visible |
 
 