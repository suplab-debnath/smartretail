# Migration Status
Show the current Flyway migration state for the local database.

```bash
mvn flyway:info -pl backend/migrations --no-transfer-progress
```

Report all migrations with version, description, installed date, and state:
- Success: applied cleanly
- Pending: needs make local-migrate to apply
- Failed: NEVER edit the migration file — run `mvn flyway:repair` in dev/local only

Remind: migrations are immutable once applied. New changes require a new `V{N}__` file.

Reference: `docs/LOCAL_DEV.md` § Running Migrations
