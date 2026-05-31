# Start Local Development Environment

Start the full SmartRetail local stack (LocalStack + PostgreSQL + all services).

---

$ARGUMENTS

---

Start the local development environment and verify each component is healthy.

Execute these steps in order:

1. **Infrastructure**: Run `make local-up` to start Docker Compose (PostgreSQL :5432, LocalStack :4566).
   - Wait for health checks: poll `docker-compose ps` until both containers show "healthy".

2. **Migrations**: Run `make local-migrate` to apply all Flyway migrations via the migrations module.
   - Confirm all migrations applied successfully (no errors in output).

3. **Seed data**: Run `make local-seed` to populate V7__seed_data.sql reference data.

4. **Start services** (in parallel background processes):
   ```
   make local-sis &   # :8080
   make local-ims &   # :8081
   make local-re  &   # :8082
   make local-ars &   # :8083
   make local-dfs &   # :8084
   make local-sup &   # :8085
   make local-pps &   # :8086
   ```

5. **Health check** each service: curl `http://localhost:{port}/actuator/health` for each.
   Report which services are UP and which (if any) failed to start.

6. **Smoke test**: Run `make test-flow1` to verify end-to-end connectivity.

Report: which services are healthy, which are not, and any error messages from service startup logs.

Refer to `docs/LOCAL_DEV.md` for troubleshooting tips if any service fails to start.
