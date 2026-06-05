# Check Architecture Rules
Run ArchUnit tests to verify no hexagonal architecture rules are violated.

**Service (optional):** $ARGUMENTS

If a service name is provided (`sis`, `ims`, `re`, `ars`, `dfs`, `sup`, `pps`):

```bash
mvn test -pl backend/services/$ARGUMENTS -Dtest="*ArchTest" --no-transfer-progress
```

If no argument, run for all services:

```bash
mvn test \
-pl backend/services/sis,backend/services/ims,backend/services/re,\
backend/services/ars,backend/services/dfs,backend/services/sup \
-Dtest="*ArchTest" --no-transfer-progress
```

Report ✅ or ❌ per service. For failures, quote the exact ArchUnit violation message
and suggest the fix (e.g. "Move AWS call to adapter/outbound/ and use a port interface").

Reference: `docs/ARCHITECTURE.md` § Architecture Non-Negotiables
