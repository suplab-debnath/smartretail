# Schema Evolution Log
Tracks the rationale for significant migration decisions.
Migrations are immutable — this log explains the WHY behind them.

| Migration | Key decision |
|---|---|
| V1 | sales schema + idempotency_keys (dedup gate for POS events) |
| V2 | forecasting schema (DFS) — separate from inventory to allow independent scaling |
| V3 | inventory schema; stock_alerts as a separate table (not a view) for event history |
| V4 | replenishment schema; `version` column on purchase_orders for optimistic locking |
| V5 | supplier schema — separate from replenishment to allow SUPPLIER_ADMIN role scoping |
| V6 | promotions schema stub (PPS not yet implemented) |
| V7 | Seed data only; separated from DDL per immutability rule |
| V8 | (document here when applied) |
| V9 | Added sku_id, dc_id, quantity to supplier.supplier_pos for Flow 9 write path |

## Index Decisions

| Table | Index | Rationale |
|---|---|---|
| purchase_orders | idx_po_status_created | Most queries filter by workflow_status ORDER BY created_at |
| inventory_positions | idx_inv_sku_dc | Primary lookup key for stock level checks |
| demand_forecasts | idx_df_sku_dc_date | Forecast reads always filter sku + dc + date range |
| sales_events | idx_se_idempotency | Fast duplicate check on idempotency_key |
