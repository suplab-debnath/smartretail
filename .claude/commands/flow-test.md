# Run Flow Tests

Run the end-to-end observable-evidence checks for a specific flow.

---

**Flow number:** $ARGUMENTS

---

Run the smoke tests for Flow $ARGUMENTS against the LOCAL environment (LocalStack + Docker Compose PostgreSQL).

Steps:
1. Verify the local stack is up by running `docker-compose ps` and checking that postgres and localstack containers are healthy.
2. Run `make test-flow$ARGUMENTS` and capture the output.
3. Parse the results — count passed/failed assertions.
4. If any assertions fail, read the relevant service logs from `docker-compose logs <service>` to diagnose the root cause.
5. Report: ✅ N passed / ❌ M failed, with a brief description of any failures and their probable cause.

Flow dependency map:
- Flow 1: No dependencies (POS event → Firehose → SIS → IMS → stock alert)
- Flow 2: Requires Flow 1 (inventory alert → RE auto-approve)
- Flow 3: Requires Flow 2 (SC Planner MFE → RE approve/reject)
- Flow 4: Requires Flows 1–3 (ARS → Store Manager Dashboard)
- Flow 8: Requires seed data only (Executive Dashboard)
- Flow 9: Requires seed data only (SC Planner Console)

Refer to `docs/FLOWS.md` for the full observable-evidence checklist for each flow.
