# SQL and Flyway Standards
 
---
 
## Migration File Rules
 
- Name: `V{n}__{snake_case_description}.sql` (double underscore)
- Never modify an applied migration
- Always idempotent: `CREATE TABLE IF NOT EXISTS`, `INSERT ... ON CONFLICT DO NOTHING`
- Always schema-qualify: `sales.sales_events` not `sales_events`
- Include `COMMENT ON` for architecturally significant columns
## Cross-Schema Rule
 
```sql
-- FORBIDDEN in any migration or application SQL
ALTER TABLE supplier.supplier_pos
ADD CONSTRAINT fk_po
FOREIGN KEY (po_id) REFERENCES replenishment.purchase_orders(po_id);
 
-- CORRECT: logical reference with comment
COMMENT ON COLUMN supplier.supplier_pos.po_id IS
'Logical reference to replenishment.purchase_orders.po_id.
NOT a FK. Cross-schema FKs are forbidden. Resolved via domain events.';
```
 
## Optimistic Locking
 
```sql
-- REQUIRED on every purchase_orders UPDATE
UPDATE replenishment.purchase_orders
SET workflow_status = :newStatus,
approved_by = :approvedBy,
approved_at = NOW(),
version = version + 1,
updated_at = NOW()
WHERE po_id = :poId
AND version = :currentVersion;
-- Check: if rowsUpdated == 0 → throw OptimisticLockException
```
 
## Index Rules
 
```sql
-- Always name indexes explicitly
CREATE INDEX IF NOT EXISTS idx_po_workflow_status
ON replenishment.purchase_orders (workflow_status, created_at DESC);
 
-- Partial index for active alerts (common query pattern)
CREATE INDEX IF NOT EXISTS idx_active_alerts
ON inventory.stock_alerts (raised_at DESC)
WHERE status = 'ACTIVE';
```
 