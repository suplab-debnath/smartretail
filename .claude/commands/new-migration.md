# New Flyway Migration

Scaffold a new Flyway versioned migration script.

---

**Migration description:** $ARGUMENTS

---

Create the next versioned Flyway migration file for this change: "$ARGUMENTS"

Steps:
1. Determine the next version number by listing `backend/migrations/src/main/resources/db/migration/V*.sql` and incrementing by 1.
2. Create `backend/migrations/src/main/resources/db/migration/V{N}__$ARGUMENTS.sql` following the SQL standards in `.claude/standards/sql.md`.

SQL standards to enforce:
- Use schema-qualified table names (e.g., `inventory.stock_levels` not `stock_levels`)
- All tables need `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()` and `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- Primary keys use UUID: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- Foreign keys must reference within the same schema only — no cross-schema FKs
- Every `ALTER TABLE` must be idempotent where possible (use `IF NOT EXISTS`)
- Add comments on columns where the purpose is not obvious
- End the file with a blank line

Remind the user: Flyway migrations are **immutable once applied**. If a column or constraint needs changing after the migration has run anywhere, create a new migration (V{N+1}) instead of editing this one.

After writing the file, show the full SQL content for review before considering the task done.
