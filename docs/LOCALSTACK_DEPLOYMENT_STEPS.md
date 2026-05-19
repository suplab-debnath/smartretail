# LocalStack Deployment Steps

Local development using Docker Compose + LocalStack. No AWS account required.
All AWS services (Kinesis, EventBridge, SQS, DynamoDB, S3, SSM) run inside LocalStack on port 4566.

---

## Prerequisites

| Tool           | Version | Install                                        |
| -------------- | ------- | ---------------------------------------------- |
| Java           | 21      | `sdk install java 21.0.3-tem`                  |
| Maven          | 3.9.x   | `sdk install maven`                            |
| Docker Desktop | 4.x     | https://www.docker.com/products/docker-desktop |
| Node.js        | 20.x    | `nvm install 20`                               |
| Python         | 3.11+   | For smoke-test and publish scripts             |
| psql           | any     | PostgreSQL client                              |

---

## Step 1 — Start infrastructure

```bash
make local-up
```

This starts two containers and waits for both to be ready:

- `smartretail-postgres` — PostgreSQL 15 on `:5432`
- `smartretail-localstack` — LocalStack 3.x on `:4566`

`scripts/localstack-init.sh` runs automatically on LocalStack startup and creates:

| Resource          | Name                                                  |
| ----------------- | ----------------------------------------------------- |
| Kinesis stream    | `smartretail-events-local`                            |
| EventBridge bus   | `smartretail-events-local`                            |
| EventBridge rules | `sales-to-ims`, `alert-to-re`, `all-to-ars`           |
| SQS queue         | `smartretail-ims-sales-local` + DLQ                   |
| SQS FIFO queue    | `smartretail-re-alert-local.fifo` + DLQ               |
| SQS queue         | `smartretail-ars-updates-local`                       |
| DynamoDB table    | `smartretail-idempotency-keys-local`                  |
| S3 bucket         | `smartretail-events-local`                            |
| SSM parameters    | All `/smartretail/local/*` params read by Spring Boot |

---

## Step 2 — Run schema migrations and seed data

```bash
make local-migrate   # Flyway V1–V6: creates all 6 schemas
make local-seed      # V7: loads reference and test data
```

Verify migrations applied:

```bash
docker exec smartretail-postgres psql -U smartretail_admin -d smartretail \
  -c "SELECT schema_name FROM information_schema.schemata \
      WHERE schema_name IN ('sales','inventory','replenishment','forecasting','supplier','promotions') \
      ORDER BY 1;"
# Expected: 6 rows
```

---

## Step 3 — Build all artifacts

```bash
make build-all
```

This runs:
- `mvn clean package -DskipTests` for all 6 services and the Lambda
- `npm run build` for all 4 MFEs

---

## Step 4 — Free up ports (if needed)

If ports are already in use from a previous run:

```bash
make local-free-ports
```

Clears ports: `8080–8085`, `5173–5176`, `3099`.

---

## Step 5 — Start backend services

Open 6 terminals, or run all in the background:

```bash
# :8080 — Sales Ingestion Service
# :8081 — Inventory Management Service
# :8082 — Replenishment Engine
# :8083 — Analytics & Reporting Service
# :8084 — Demand Forecasting Service
# :8085 — Supplier Service
make local-sis &   
make local-ims &   
make local-re  &   
make local-ars &   
make local-dfs &   
make local-sup &   
```

Verify all are healthy:

```bash
for port in 8080 8081 8082 8083 8084 8085; do
  echo -n "Port $port: "
  curl -s http://localhost:$port/actuator/health | python3 -m json.tool
done
# All should return: {"status":"UP"}
```

---

## Step 6 — Start MFEs (optional, for UI testing)

```bash
# :5173 — Store Manager Dashboard
# :5174 — SC Planner Console
# :5175 — Executive Insights Dashboard
make local-mfe-sm   &   
make local-mfe-scp  &   
make local-mfe-exec &   
```

All MFEs run in mock-auth mode locally — no Cognito required.

---

## Step 7 — Run smoke tests

Tests must be run in order — each flow depends on the previous one having left state in the database.

```bash
make test-flow1   # Expected: ✅ 5 passed  ❌ 0 failed
make test-flow2   # Expected: ✅ 2 passed  ❌ 0 failed  (requires Flow 1 to have run)
make test-flow3   # Expected: ✅ 4 passed  ❌ 0 failed  (requires Flow 2 to have run)
make test-flow4   # Expected: ✅ 3 passed  ❌ 0 failed  (requires Flows 1–3 to have run)
make test-flow8   # Expected: ✅ 11 passed ❌ 0 failed  (requires seed data only)
make test-flow9   # Expected: ✅ 11 passed ❌ 0 failed  (requires seed data only)
```

Or run all flows in one command:

```bash
make test-all
```

---

## Port Reference

| Service                       | Port |
| ----------------------------- | ---- |
| SIS — Sales Ingestion         | 8080 |
| IMS — Inventory Management    | 8081 |
| RE — Replenishment Engine     | 8082 |
| ARS — Analytics & Reporting   | 8083 |
| DFS — Demand Forecasting      | 8084 |
| SUP — Supplier Service        | 8085 |
| PostgreSQL                    | 5432 |
| LocalStack (all AWS services) | 4566 |
| Store Manager MFE             | 5173 |
| SC Planner MFE                | 5174 |
| Executive MFE                 | 5175 |
| Demo Control Center MFE       | 5176 |
| Demo Control Server           | 3099 |

---

## Manual Flow Triggers

### Trigger Flow 1 manually (publish a POS event)

```bash
python3 scripts/publish-pos-event.py \
  --transaction-id $(python3 -c "import uuid; print(uuid.uuid4())") \
  --direct-api http://localhost:8080
```

Test idempotency — re-send the same `transactionId`, expect `409 Conflict`:

```bash
TX_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
python3 scripts/publish-pos-event.py --transaction-id $TX_ID --direct-api http://localhost:8080
python3 scripts/publish-pos-event.py --transaction-id $TX_ID --direct-api http://localhost:8080
# Second call → 409 Conflict
```

### Verify Flow 2 RDS state

```bash
# Auto-approve PO (SKU-BEV-001 / DC-LONDON — threshold = 50000, totalValue ≈ 850)
docker exec smartretail-postgres psql -U smartretail_admin -d smartretail \
  -c "SELECT po_id, workflow_status, version FROM replenishment.purchase_orders \
      WHERE sku_id = 'SKU-BEV-001' AND dc_id = 'DC-LONDON' \
      ORDER BY created_at DESC LIMIT 1;"
# Expected: workflow_status = APPROVED, version = 0

# Manual approval PO (SKU-BEV-003 / DC-LONDON — threshold = 0, always manual)
docker exec smartretail-postgres psql -U smartretail_admin -d smartretail \
  -c "SELECT po_id, workflow_status, version FROM replenishment.purchase_orders \
      WHERE sku_id = 'SKU-BEV-003' AND dc_id = 'DC-LONDON' \
      ORDER BY created_at DESC LIMIT 1;"
# Expected: workflow_status = PENDING_APPROVAL, version = 0
```

### Verify Flow 8 via ARS directly

```bash
curl -s http://localhost:8083/v1/dashboard/executive | python3 -m json.tool
# Expected: kpis block with fulfilmentRate, onTimeDelivery, forecastAccuracy (9 surfaces)
```

---

## Key API Endpoints (local)

### SIS — ingest a POS event

```bash
curl -X POST http://localhost:8080/v1/ingest/events \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "'"$(python3 -c "import uuid; print(uuid.uuid4())")"'",
    "storeId": "STORE-001",
    "skuId": "SKU-4423",
    "dcId": "DC-LONDON",
    "quantity": 5,
    "unitPrice": 12.99,
    "channel": "POS",
    "eventTimestamp": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"
  }'
# 202 Accepted → {"transactionId":"...","status":"ACCEPTED"}
# 409 Conflict → duplicate transactionId
```

### IMS — query inventory

```bash
curl "http://localhost:8081/v1/inventory/positions?dcId=DC-LONDON"
curl "http://localhost:8081/v1/inventory/alerts?status=ACTIVE"
```

### RE — query purchase orders

```bash
curl "http://localhost:8082/v1/replenishment/orders"
curl "http://localhost:8082/v1/replenishment/orders?status=PENDING_APPROVAL"
curl "http://localhost:8082/v1/replenishment/orders/{poId}"
```

### ARS — dashboard endpoints

```bash
curl "http://localhost:8083/v1/dashboard/executive"
curl "http://localhost:8083/v1/dashboard/store-manager?dcId=DC-LONDON"
curl "http://localhost:8083/v1/dashboard/planner"
```

---

## Demo Control Center (optional)

The Demo Control Center is a browser-based presenter UI that orchestrates all six flows from a single window — no terminal required during a demo.

```bash
# Both processes in one command
make local-demo
```

Or separately:

```bash
make local-demo-server   # :3099 — backend that spawns scripts and streams SSE
make local-mfe-demo      # :5176 — Demo Control Center MFE
```

Open **http://localhost:5176**. All six service health dots must be green before starting.

**Full startup sequence for a demo:**

```bash
# Infrastructure
make local-up
make local-migrate
make local-seed

# Build
make build-all

# Free ports
make local-free-ports

# Backend services
make local-sis & make local-ims & make local-re & make local-ars & make local-dfs & make local-sup &

# Operational MFEs (used as iframes in chapters 3, 4, 5, 6)
make local-mfe-sm & make local-mfe-scp & make local-mfe-exec &

# Demo experience
make local-demo
```

---

## Stop and Clean Up

```bash
make local-down    # stop containers, keep data volumes
make local-clean   # stop containers, destroy volumes (clean slate for next run)
```

Both targets automatically free all ports before stopping containers.
