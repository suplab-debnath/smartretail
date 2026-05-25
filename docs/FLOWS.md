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
Run `scripts/shared/publish-pos-event.py` with a test transaction payload.
 
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
Run `scripts/shared/publish-pos-event.py` again with the SAME transactionId.
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
using `scripts/shared/publish-pos-event.py --flow2-direct`.
 
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
 
**Pre-condition:** Flow 2 Scenario 2b must have a PO in PENDING_APPROVAL status.
 
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
 
**Expected:** 403 Forbidden
```json
{ "errorCode": "UNAUTHORIZED", "message": "SC_PLANNER or ADMIN role required" }
```
 
**Scenario 3d: Wrong status rejection**
 
Attempt to approve a PO that is already APPROVED (not PENDING_APPROVAL).
 
**Expected:** 409 Conflict
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
 
**Pre-condition:** Flows 1–3 must have produced data in sales, inventory,
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
 
## Flow 8: Executive Insights Dashboard
 
**What this proves:**
- ARS executive dashboard endpoint serving multi-metric KPI data
- Pre-populated seed data renders correctly across all nine KPI surfaces
- Recharts rendering (LineChart, BarChart, AreaChart) with real aggregated data
- EXECUTIVE role access; SC_PLANNER / ADMIN can also access
- ARS cross-schema aggregation: no SQL joins — separate queries merged in Java
 
**Nine KPI surfaces:**
 
| # | Surface | Data source |
|---|---------|-------------|
| 8.1 | Fulfilment Rate | `replenishment.purchase_orders` COMPLETED / total ratio |
| 8.2 | Stockout Incidents | `inventory.stock_alerts` CRITICAL count by DC and category, trended 30 days |
| 8.3 | Forecast Accuracy (MAPE) | `forecasting.forecast_runs` last 30 days — LineChart with 0.15 threshold line |
| 8.4 | On-Time Delivery % | `supplier.shipment_updates` vs `replenishment_rules.lead_time_days` |
| 8.5 | Supplier Performance Comparison | Ranked table (OTD %, fill rate, avg lead time) across 5 seed suppliers |
| 8.6 | Delivery Performance Histogram | Early / On-Time / Delayed distribution — Recharts BarChart |
| 8.7 | Inventory Carrying Cost Trend | Directional view: current period cost vs prior period |
| 8.8 | Replenishment Lead Time | Average days from `stock_alerts.raised_at` to `supplier_pos.confirmed_at` |
| 8.9 | Top Stockout SKUs | Highest-impact stockout items (by lost-sales proxy) in the period |
 
**Pre-condition:** Seed data V7__seed_data.sql must be applied.
This populates `forecasting.forecast_runs`, `forecasting.demand_forecasts`,
`inventory.stock_alerts`, `replenishment.purchase_orders`, `supplier.supplier_pos`,
and `supplier.shipment_updates` with 30–90 days of realistic data.
 
**Components involved:**
- Browser → Executive Insights Dashboard MFE
- API Gateway (internal stage)
- ECS: ARS (reads from `forecasting`, `inventory`, `replenishment`, `supplier` schemas)
 
Steps:
1. Open Executive Insights Dashboard MFE
2. Sign in with Cognito user in EXECUTIVE group
3. Dashboard loads — all nine KPI surfaces render
 
**Observable evidence — all must be true for Flow 8 to pass:**
 
| # | Check | How to verify |
|---|-------|--------------|
| 8.1 | Fulfilment Rate KPI card renders with platform-wide fill rate % | Visual: matches seed PO completion ratio |
| 8.2 | Stockout Incidents card renders; trend chart shows 30-day history by DC and category | Visual: counts match `inventory.stock_alerts` CRITICAL seed rows |
| 8.3 | MAPE Trend LineChart renders ≥30 data points; 0.15 reference line visible | Visual: values trend 0.1187 → 0.0823 per seed data |
| 8.4 | On-Time Delivery % KPI card renders aggregate OTD % | Visual: matches supplier seed data weighted average |
| 8.5 | Supplier Performance Comparison table renders 5 suppliers ranked by OTD | Visual: Metro Food last (71%, red); Chill Chain first (95%, green) |
| 8.6 | Delivery Performance Histogram renders Early / On-Time / Delayed bars | Visual: Recharts BarChart with 3 series across 5 suppliers |
| 8.7 | Inventory Carrying Cost Trend card shows directional indicator | Visual: current vs prior period comparison; trend arrow visible |
| 8.8 | Replenishment Lead Time KPI card renders average PO cycle days | Visual: matches avg(confirmed_at − raised_at) from seed data |
| 8.9 | Top Stockout SKUs table renders ≥5 highest-impact items | Visual: SKU, DC, category, and stockout count columns visible |
| 8.10 | EXECUTIVE cannot access SC Planner API | Browser: GET /v1/dashboard/sc-planner returns 403 |
| 8.11 | `dataFreshness` timestamp displayed on all sections | Visual: "Data as of HH:MM" visible in dashboard footer |
 
---
 
## Flow 9: SC Planner Console — Full Feature Suite
 
**What this proves:**
- ARS supplier-performance endpoint (cross-schema aggregation in Java, not SQL)
- Eight distinct planner console surfaces rendering from seed data
- SC_PLANNER role access; ADMIN can also access
- RE manual PO trigger (surface 9.7) writes a DRAFT purchase order
 
**Eight console surfaces:**
 
| # | Surface | Data source |
|---|---------|-------------|
| 9.1 | Exception Queue | `inventory.stock_alerts` ACTIVE, prioritised by severity; LOW_STOCK / OVERSTOCK types |
| 9.2 | Inventory Overview by DC | `inventory.inventory_positions` on-hand / in-transit / reorder-status per DC (dropdown selector) |
| 9.3 | Demand Forecast View | `forecasting.demand_forecasts` P10/P50/P90 bands; units sold vs forecast; accuracy indicator |
| 9.4 | Stockout Risk Indicators | Derived from ATP vs reorder_point: MODERATE / HIGH / CRITICAL flags per SKU |
| 9.5 | Approval Workflows | `replenishment.purchase_orders` PENDING_APPROVAL — approve / reject with optimistic locking |
| 9.6 | Supplier Order Tracking | `supplier.supplier_pos` + `supplier.shipment_updates` — PO list with ETA and fulfilment status |
| 9.7 | Replenishment Action Trigger | Manual override: POST /v1/replenishment/orders creates a DRAFT PO for planner submission |
| 9.8 | Forecast Adjustment Controls | Promotional uplift signal input — percentage applied to P50 forecast (PPS-driven) |
 
**Pre-condition:** Seed data V7__seed_data.sql must be applied.
This populates `supplier.supplier_records`, `supplier.supplier_pos`, `supplier.shipment_updates`,
`replenishment.purchase_orders`, `inventory.stock_alerts`, and `forecasting.demand_forecasts`
with realistic performance data for 5 test suppliers.
 
**Components involved:**
- Browser → SC Planner Console MFE
- API Gateway (internal stage)
- ECS: ARS (reads `supplier`, `replenishment`, `inventory`, `forecasting` schemas)
- ECS: RE (POST /v1/replenishment/orders for surface 9.7)
 
Steps:
1. Open SC Planner Console MFE
2. Sign in with Cognito user in SC_PLANNER group
3. Navigate through each of the 8 console surfaces
 
**Observable evidence — all must be true for Flow 9 to pass:**
 
| # | Check | How to verify |
|---|-------|--------------|
| 9.1 | Exception Queue renders prioritised alert list | Visual: alerts sorted CRITICAL → HIGH → MEDIUM; severity badges and alert type visible |
| 9.2 | Inventory Overview by DC renders per-DC on-hand / in-transit / reorder status | Visual: DC dropdown selector drives data; all 3 DCs selectable |
| 9.3 | Demand Forecast View renders P10/P50/P90 bands and units sold vs forecast | Visual: Recharts AreaChart per SKU × DC; accuracy indicator percentage visible |
| 9.4 | Stockout Risk Indicators render MODERATE / HIGH / CRITICAL per-SKU flags | Visual: colour-coded badges; counts match seed alert data |
| 9.5 | Approval Workflows tab shows PENDING_APPROVAL POs | Visual: PO list renders; approve / reject buttons functional (full test in Flow 3) |
| 9.6 | Supplier Order Tracking table renders PO list with ETA and shipment progress | Visual: 5 suppliers; ETA, fulfilment status, shipment progress columns visible |
| 9.7 | Replenishment Action Trigger creates a DRAFT PO | API: POST /v1/replenishment/orders returns 201; `SELECT workflow_status FROM replenishment.purchase_orders ORDER BY created_at DESC LIMIT 1` = DRAFT |
| 9.8 | Forecast Adjustment Controls apply promotional uplift | Visual: uplift % input visible; adjusted P50 line updates in Demand Forecast chart |
| 9.9 | No cross-schema JOIN used | Code review: ARS fetches supplier and replenishment data in separate queries, merges in Java |
| 9.10 | Supplier scorecard renders 5 rows with correct OTD rates | Visual: Metro Food 71% (red), Chill Chain 95% (green) per seed data |
| 9.11 | `dataFreshness` displayed on all surfaces | Visual: "Data as of HH:MM" visible on each tab/surface |
