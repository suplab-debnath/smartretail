---
mode: 'agent'
description: 'Task: Create the next Flyway versioned migration script with correct schema, naming, and idempotency'
tools: ['codebase', 'new', 'usages', 'workspaceDetails']
---

Create a new Flyway versioned migration for SmartRetail.

## Migration details
- **Change description:** ${input:description}
  (used in the filename, e.g. `add_reserved_quantity_to_stock_levels`)
- **Target schema:** ${input:schema}
  (one of: `sales`, `inventory`, `replenishment`, `forecasting`, `supplier`, `promotions`)

## Steps

1. List `backend/migrations/src/main/resources/db/migration/V*.sql` to find the highest version number N
2. Create `backend/migrations/src/main/resources/db/migration/V{N+1}__{description}.sql`

## SQL standards to follow
- Schema-qualify every table: `{schema}.table_name` (never unqualified)
- New tables must include:
  ```sql
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
  ```
- Tables with concurrent updates need: `version INTEGER NOT NULL DEFAULT 0`
- Use `IF NOT EXISTS` / `IF EXISTS` for idempotency on ALTER and CREATE INDEX
- No cross-schema FK constraints -- FKs are intra-schema only
- Add `COMMENT ON COLUMN` for non-obvious columns

## Naming conventions
| Object | Pattern | Example |
|---|---|---|
| Tables | snake_case plural | `stock_alerts` |
| Indexes | `idx_{table}_{columns}` | `idx_stock_alerts_sku_dc` |
| Unique constraints | `uq_{table}_{columns}` | `uq_stock_sku_dc` |
| Check constraints | `ck_{table}_{rule}` | `ck_qty_positive` |

## Flyway immutability reminder
This migration is permanent once applied. If you need to correct it after it runs anywhere, create a new migration -- never edit this file.

Show the full SQL content for review before considering the task complete.
