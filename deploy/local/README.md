# Local Environment — Setup & Run Guide

Runs the full SmartRetail stack on your machine using Docker Compose (PostgreSQL + LocalStack) and local JVM processes. No AWS account needed.

> For Spring profiles, LocalStack config deep-dive, and debugging tips see `docs/LOCAL_DEV.md`.

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 21 | `sdk install java 21` (SDKMAN) |
| Maven | 3.9+ | bundled with most IDEs or `brew install maven` |
| Docker Desktop | latest | docker.com |
| Node.js | 20+ | `nvm install 20` |
| Python | 3.10+ | `brew install python` / system Python |
| psql client | any | `brew install libpq` |

**Windows users:** run all commands in Git Bash. Set `PYTHON_CMD=python` in your shell profile.

---

## Infrastructure startup

```bash
# Start PostgreSQL + LocalStack (runs in background)
make local-up
```

LocalStack automatically provisions all required AWS resources on startup via `scripts/local/localstack-init.sh`:

| Resource | Name |
|----------|------|
| Kinesis stream | `smartretail-events-local` |
| EventBridge bus + rules | `smartretail-events-local` |
| SQS queues (+ DLQs) | `ims-sales-local`, `re-alert-local.fifo`, `ars-updates-local` |
| DynamoDB table | `smartretail-idempotency-keys-local` |
| S3 bucket | `smartretail-events-local` |
| SSM parameters | read by Spring Boot at startup |

Wait for LocalStack to be ready before starting services:
```bash
until curl -s http://localhost:4566/_localstack/health | grep '"kinesis": "running"'; do sleep 3; done
```

---

## First-time setup

```bash
# 1. Run schema migrations (V1–V6)
make local-migrate

# 2. Load seed data (V7)
make local-seed

# 3. Verify DB is populated
psql postgresql://smartretail_admin:local_dev_password@localhost:5432/smartretail \
  -c "SELECT COUNT(*) FROM inventory.inventory_positions;"
# Expected: 60
```

---

## Starting backend services

Each service runs as a local JVM process in its own terminal (or use `&`):

```bash
make local-sis   # Sales Ingestion Service   :8080
make local-ims   # Inventory Mgmt Service    :8081
make local-re    # Replenishment Engine       :8082
make local-ars   # Analytics & Reporting      :8083
make local-dfs   # Demand Forecasting Service :8084
make local-sup   # Supplier Service           :8085
```

Health-check all services:
```bash
for port in 8080 8081 8082 8083 8084 8085; do
  curl -s http://localhost:$port/actuator/health | grep -o '"status":"[^"]*"'
done
```

---

## Starting MFEs (optional)

```bash
make local-mfe-sm       # Store Manager Dashboard  :5173
make local-mfe-scp      # SC Planner Console       :5174
make local-mfe-exec     # Executive Dashboard      :5175
make local-mfe-supplier # Supplier Portal          :5177
```

No Cognito in local mode — auth is bypassed via `X-Dev-Role` header (set by the mock auth library).

---

## Running smoke tests

```bash
make test-flow1   # POS event → SIS → IMS → EventBridge
make test-flow2   # Inventory alert → RE auto-approve
make test-flow3   # SC Planner approve/reject
make test-flow4   # ARS dashboard
make test-all     # All flows in order (Flows 1–4, 8–9)
```

Trigger Flow 1 manually:
```bash
python3 scripts/shared/publish-pos-event.py \
  --transaction-id $(python3 -c "import uuid; print(uuid.uuid4())") \
  --direct-api http://localhost:8080
```

---

## Demo Control Center (optional)

Presenter UI that triggers flows and streams live SSE log lines:

```bash
make local-demo-server   # Demo control server :3099
make local-mfe-demo      # Demo Control Center UI :5176
```

---

## Daily workflow

```bash
# Morning: start everything
make local-up
make local-sis & make local-ims & make local-re & make local-ars &

# Evening: stop everything
make local-down    # stops Docker containers, leaves data
make local-clean   # stops containers + wipes all volumes (fresh start next time)
```

---

## Port reference

| Service | Port |
|---------|------|
| SIS | 8080 |
| IMS | 8081 |
| RE | 8082 |
| ARS | 8083 |
| DFS | 8084 |
| SUP | 8085 |
| PostgreSQL | 5432 |
| LocalStack | 4566 |
| Store Manager MFE | 5173 |
| SC Planner MFE | 5174 |
| Executive MFE | 5175 |
| Demo Control Center | 5176 |
| Supplier MFE | 5177 |
| Demo Control Server | 3099 |
