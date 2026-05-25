# Seed Data Specification
 
File: `backend/migrations/src/main/resources/db/migration/V7__seed_data.sql`
 
This file populates all schemas with realistic prototype data.
Run after V1–V6 schema creation scripts.
 
The seed data is designed to ensure:
- Flow 1 triggers a stock alert (ATP will fall below reorder_point after the test event)
- Flow 2 Scenario 2a has an auto-approve rule
- Flow 2 Scenario 2b has a manual approval rule
- Flows 8 and 9 have enough historical data to render meaningful charts
 
---
 
## Reference Data
 
### Distribution Centres (3 DCs)
 
| dc_id | name | region |
|-------|------|--------|
| DC-LONDON | London DC | UK South |
| DC-MANCHESTER | Manchester DC | UK North |
| DC-BIRMINGHAM | Birmingham DC | UK Midlands |
 
### SKUs (20 SKUs across 4 categories)
 
| sku_id | name | category |
|--------|------|----------|
| SKU-BEV-001 | Sparkling Water 500ml 24-pack | Beverages |
| SKU-BEV-002 | Orange Juice 1L 12-pack | Beverages |
| SKU-BEV-003 | Cola 330ml 24-pack | Beverages |
| SKU-BEV-004 | Energy Drink 250ml 24-pack | Beverages |
| SKU-BEV-005 | Still Water 1.5L 6-pack | Beverages |
| SKU-SNK-001 | Crisps Mixed 48-pack | Snacks |
| SKU-SNK-002 | Chocolate Bar 50g 48-pack | Snacks |
| SKU-SNK-003 | Nuts Mixed 200g 24-pack | Snacks |
| SKU-SNK-004 | Rice Cakes 100g 12-pack | Snacks |
| SKU-SNK-005 | Popcorn 80g 24-pack | Snacks |
| SKU-DRY-001 | Pasta 500g 12-pack | Dry Goods |
| SKU-DRY-002 | Rice 1kg 10-pack | Dry Goods |
| SKU-DRY-003 | Flour 1.5kg 8-pack | Dry Goods |
| SKU-DRY-004 | Sugar 1kg 10-pack | Dry Goods |
| SKU-DRY-005 | Cereal 500g 12-pack | Dry Goods |
| SKU-CHL-001 | Milk 2L 6-pack | Chilled |
| SKU-CHL-002 | Butter 250g 12-pack | Chilled |
| SKU-CHL-003 | Yogurt 500g 8-pack | Chilled |
| SKU-CHL-004 | Cheese 400g 6-pack | Chilled |
| SKU-CHL-005 | Eggs 12-pack | Chilled |
 
### Suppliers (5 suppliers)
 
```sql
INSERT INTO supplier.supplier_records (supplier_id, supplier_name, status) VALUES
  ('11111111-0000-0000-0000-000000000001', 'Acme Beverages Ltd',      'ACTIVE'),
  ('11111111-0000-0000-0000-000000000002', 'Premier Snacks Co',       'ACTIVE'),
  ('11111111-0000-0000-0000-000000000003', 'Dry Goods Wholesale Ltd', 'ACTIVE'),
  ('11111111-0000-0000-0000-000000000004', 'Chill Chain Logistics',   'ACTIVE'),
  ('11111111-0000-0000-0000-000000000005', 'Metro Food Distributors', 'ACTIVE');
```
 
---
 
## inventory.inventory_positions Seed Data
 
Populate all 20 SKUs × 3 DCs = 60 rows.
 
Key rows designed for prototype flows:
 
```sql
-- SKU-BEV-001 at DC-LONDON: on_hand = 120, reorder_point = 100
-- After Flow 1 publishes a transaction of quantity=30, on_hand drops to 90
-- ATP = 90 - 0 (reserved) = 90 < 100 (reorder_point) → triggers MEDIUM stock alert
INSERT INTO inventory.inventory_positions
  (position_id, sku_id, dc_id, on_hand, in_transit, reserved, reorder_point, safety_stock)
VALUES
  ('22222222-0000-0000-0001-000000000001', 'SKU-BEV-001', 'DC-LONDON',     120, 0, 0, 100, 30),
  ('22222222-0000-0000-0001-000000000002', 'SKU-BEV-001', 'DC-MANCHESTER',  80, 0, 0, 100, 30),
  ('22222222-0000-0000-0001-000000000003', 'SKU-BEV-001', 'DC-BIRMINGHAM', 200, 0, 0, 100, 30),
 
-- SKU-BEV-003 at DC-LONDON: on_hand = 5 → CRITICAL alert already in place
  ('22222222-0000-0000-0003-000000000001', 'SKU-BEV-003', 'DC-LONDON',       5, 50, 0, 100, 25),
  ('22222222-0000-0000-0003-000000000002', 'SKU-BEV-003', 'DC-MANCHESTER', 150,  0, 0, 100, 25),
 
-- Remaining 55 rows with healthy stock levels
  -- (generate with realistic values: on_hand between 150-500, reorder_point 50-150)
  ...;
```
 
---
 
## inventory.stock_alerts Seed Data
 
Pre-populate some active alerts to make the dashboard interesting:
 
```sql
INSERT INTO inventory.stock_alerts
  (alert_id, position_id, alert_type, severity, threshold_value, actual_value, status)
VALUES
  -- CRITICAL: SKU-BEV-003 at DC-LONDON (ATP = 5)
  ('33333333-0001-0000-0000-000000000001',
   '22222222-0000-0000-0003-000000000001',
   'LOW_STOCK', 'CRITICAL', 100, 5, 'ACTIVE'),
 
  -- HIGH: 5 more HIGH alerts across different SKUs and DCs
  ('33333333-0002-0000-0000-000000000001', ..., 'LOW_STOCK', 'HIGH', ...),
  ('33333333-0003-0000-0000-000000000001', ..., 'LOW_STOCK', 'HIGH', ...),
  ('33333333-0004-0000-0000-000000000001', ..., 'LOW_STOCK', 'HIGH', ...),
  ('33333333-0005-0000-0000-000000000001', ..., 'LOW_STOCK', 'HIGH', ...),
  ('33333333-0006-0000-0000-000000000001', ..., 'LOW_STOCK', 'HIGH', ...),
 
  -- MEDIUM: 15 more MEDIUM alerts
  ...;
```
 
---
 
## replenishment.replenishment_rules Seed Data
 
Two critical rules for prototype flows:
 
```sql
-- Rule 1: AUTO-APPROVE (threshold = 50000 — most POs will be below this)
-- For SKU-BEV-001 at DC-LONDON from Supplier 1
INSERT INTO replenishment.replenishment_rules
  (rule_id, supplier_id, sku_id, dc_id,
   lead_time_days, moq, cost_per_unit, auto_approve_threshold)
VALUES
  ('44444444-0001-0000-0000-000000000001',
   '11111111-0000-0000-0000-000000000001',
   'SKU-BEV-001', 'DC-LONDON',
   5, 100, 8.50, 50000.00),  -- 100 units × 8.50 = 850 << 50000 → AUTO APPROVE
 
-- Rule 2: MANUAL APPROVAL (threshold = 0 → always manual)
-- For SKU-BEV-003 at DC-LONDON from Supplier 1
  ('44444444-0002-0000-0000-000000000001',
   '11111111-0000-0000-0000-000000000001',
   'SKU-BEV-003', 'DC-LONDON',
   7, 200, 9.25, 0.00),  -- any totalValue > 0 → MANUAL APPROVAL
 
-- Rules for remaining SKU/DC/Supplier combinations
-- Mix of auto-approve (threshold 5000-50000) and manual (threshold 0-1000)
  ...;
```
 
---
 
## forecasting.forecast_runs + demand_forecasts Seed Data
 
30 days of forecast runs to populate Flow 8 charts.
MAPE values trend from 0.12 down to 0.08 (improving accuracy).
 
```sql
-- Insert 30 forecast_runs, one per day for last 30 days
-- MAPE values: day -30 = 0.1187, day -1 = 0.0823
-- All status = 'COMPLETED'
 
INSERT INTO forecasting.forecast_runs (run_id, triggered_by, status, mape, started_at, completed_at)
VALUES
  ('55555555-0001-0000-0000-000000000001', 'SCHEDULED', 'COMPLETED', 0.1187,
   NOW() - INTERVAL '30 days', NOW() - INTERVAL '30 days' + INTERVAL '90 minutes'),
  ('55555555-0002-0000-0000-000000000001', 'SCHEDULED', 'COMPLETED', 0.1143,
   NOW() - INTERVAL '29 days', NOW() - INTERVAL '29 days' + INTERVAL '88 minutes'),
  -- ... continuing to today
  ('55555555-0030-0000-0000-000000000001', 'SCHEDULED', 'COMPLETED', 0.0823,
   NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day' + INTERVAL '82 minutes');
 
-- Insert demand_forecasts for the most recent run (run_id = 55555555-0030-...)
-- 20 SKUs × 3 DCs × 3 horizon_days (7, 14, 30) = 180 rows
INSERT INTO forecasting.demand_forecasts
  (forecast_id, run_id, sku_id, dc_id, forecast_date, horizon_days, p10, p50, p90)
VALUES
  -- Example row
  (gen_random_uuid(),
   '55555555-0030-0000-0000-000000000001',
   'SKU-BEV-001', 'DC-LONDON', CURRENT_DATE, 7,
   80, 120, 165),
  -- 179 more rows...
  ...;
```
 
---
 
## supplier performance seed data
 
For Flows 9 — supplier PO history for last 90 days.
 
Each of the 5 suppliers should have:
- 8–12 completed POs with varying on-time delivery rates
- 1–3 active exceptions per supplier
- Varying lead time variance
 
On-time delivery rates by supplier (for scorecard):
- Acme Beverages: 92% on-time
- Premier Snacks: 78% on-time (below threshold — should stand out)
- Dry Goods Wholesale: 88% on-time
- Chill Chain Logistics: 95% on-time
- Metro Food Distributors: 71% on-time (worst performer)
 
```sql
-- Purchase orders (in replenishment schema)
-- Status = COMPLETED, created over last 90 days
INSERT INTO replenishment.purchase_orders
  (po_id, rule_id, supplier_id, sku_id, dc_id, quantity, total_value,
   workflow_status, version, approved_by, approved_at)
VALUES
  -- Acme: 10 completed POs
  ('66666666-0001-0001-0000-000000000001',
   '44444444-0001-0000-0000-000000000001',
   '11111111-0000-0000-0000-000000000001',
   'SKU-BEV-001', 'DC-LONDON', 500, 4250.00,
   'COMPLETED', 3, 'planner@smartretail.com', NOW() - INTERVAL '85 days'),
  -- ... 9 more for Acme, then 10 each for other suppliers
 
-- Supplier POs and shipment updates (in supplier schema)
-- shipment_updates with update_type = 'SHIPPED'
-- For on-time: created_at <= dispatched_at + lead_time_days
-- For late: created_at > dispatched_at + lead_time_days
INSERT INTO supplier.supplier_pos (supplier_po_id, supplier_id, po_id, po_status, confirmed_at, dispatched_at, eta) VALUES ...;
INSERT INTO supplier.shipment_updates (update_id, supplier_po_id, update_type, actual_qty_shipped, created_at) VALUES ...;
```
 
---
 
## Cognito Test Users
 
Create these users in the respective Cognito pools after CDK deploy.
Use the AWS CLI or Cognito console.
 
### Internal User Pool
 
| Username | Email | Group | Purpose |
|----------|-------|-------|---------|
| store-manager-1 | sm1@test.com | STORE_MANAGER | Flow 4 — Store Manager Dashboard |
| sc-planner-1 | scp1@test.com | SC_PLANNER | Flows 3, 4, 9 — approve POs, view scorecard |
| executive-1 | exec1@test.com | EXECUTIVE | Flow 8 — Executive Dashboard |
 
### Supplier User Pool
 
| Username | Email | Group | Custom attribute | Purpose |
|----------|-------|-------|-----------------|---------|
| supplier-acme-1 | acme@test.com | SUPPLIER | supplierId=11111111-...-001 | Supplier portal testing |
 
CLI commands to create users:
```bash
# Internal pool
aws cognito-idp admin-create-user \
  --user-pool-id {INTERNAL_POOL_ID} \
  --username sc-planner-1 \
  --user-attributes Name=email,Value=scp1@test.com \
  --temporary-password Temp123! \
  --message-action SUPPRESS
 
aws cognito-idp admin-add-user-to-group \
  --user-pool-id {INTERNAL_POOL_ID} \
  --username sc-planner-1 \
  --group-name SC_PLANNER
 
# Set permanent password
aws cognito-idp admin-set-user-password \
  --user-pool-id {INTERNAL_POOL_ID} \
  --username sc-planner-1 \
  --password Test@12345! \
  --permanent
```
