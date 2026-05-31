---
applyTo: "**/db/migration/**.sql"
---

# SQL / Flyway Instructions — SmartRetail

## Schema qualification — always required
```sql
-- CORRECT
INSERT INTO inventory.stock_levels (sku_id, dc_id, quantity_on_hand) ...
SELECT * FROM replenishment.purchase_orders WHERE ...

-- WRONG — unqualified table name
INSERT INTO stock_levels ...
```

## Cross-schema JOINs are forbidden
Do NOT write SQL that JOINs tables from different schemas.
Merge data from multiple schemas in Java (ARS aggregation pattern).

## Flyway migration immutability
- Once a versioned migration `V{N}__*.sql` has been applied anywhere, it MUST NOT be modified.
- For corrections or additions, create `V{N+1}__*.sql`.

## Standard columns for all new tables
```sql
id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

## Optimistic locking — version column
Tables that support concurrent updates must include:
```sql
version     INTEGER NOT NULL DEFAULT 0
```
All UPDATE statements on such tables must check: `WHERE id = :id AND version = :v`
and increment: `SET version = version + 1`.

## Idempotent DDL
Prefer `IF NOT EXISTS` / `IF EXISTS` for safety in migration files:
```sql
ALTER TABLE inventory.stock_levels
    ADD COLUMN IF NOT EXISTS reserved_quantity INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_po_status ON replenishment.purchase_orders (workflow_status);
```

## Naming conventions
| Object | Convention | Example |
|---|---|---|
| Tables | snake_case plural | `purchase_orders`, `stock_levels` |
| Columns | snake_case | `sku_id`, `created_at`, `workflow_status` |
| Indexes | `idx_{table}_{columns}` | `idx_po_sku_dc` |
| Unique constraints | `uq_{table}_{columns}` | `uq_stock_sku_dc` |
| Foreign keys | `fk_{table}_{ref_table}` | `fk_po_supplier` |
| Check constraints | `ck_{table}_{rule}` | `ck_stock_qty_positive` |
