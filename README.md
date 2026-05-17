# SmartRetail — Demand Forecasting & Supply Chain Platform

A prototype of an event-driven retail supply chain platform built on AWS.
It demonstrates a complete POS-to-inventory pipeline using Kinesis, Lambda, ECS Fargate, EventBridge, SQS, RDS PostgreSQL, and DynamoDB.

> **Implementation status:** All Flows (1, 2, 3, 4, 8, and 9) are fully implemented, integrated, and verified.

---

## What Flow 1 Does

```
POS terminal
    │  publish-pos-event.py
    ▼
Kinesis Data Stream  (smartretail-events-{env})
    │
    ▼  Lambda trigger
Kinesis Consumer Lambda          ← infrastructure adapter only, no domain logic
    │  POST /v1/ingest/events
    ▼
SIS — Sales Ingestion Service    (ECS Fargate, port 8080)
    ├── Idempotency check         DynamoDB (SHA-256 of transactionId, TTL 48h)
    ├── Persist sale              RDS → sales.sales_events
    ├── Archive raw event         S3 → smartretail-events-{env}/
    └── Publish domain event      EventBridge → SalesTransactionEvent
                                      │
                        EventBridge rule: sales-to-ims
                                      │
                                      ▼  SQS
                             smartretail-ims-sales-{env}
                                      │
                                      ▼  SQS consumer
                    IMS — Inventory Management Service   (ECS Fargate, port 8081)
                        ├── Decrement on_hand            RDS → inventory.inventory_positions
                        ├── Create stock alert           RDS → inventory.stock_alerts  (if ATP < reorder_point)
                        └── Publish alert event          EventBridge → InventoryAlertEvent
                                                              │
                                              EventBridge rule: alert-to-re
                                                              │
                                                              ▼  SQS FIFO
                                                    smartretail-re-alert-{env}.fifo
                                                    (Flow 2 picks up from here)
```

**Observable evidence (all 10 checks must pass):**

| Check | What to verify                                                    |
| ----- | ----------------------------------------------------------------- |
| 1.1   | Kinesis record ingested                                           |
| 1.2   | Lambda invoked (CloudWatch Logs)                                  |
| 1.3   | DynamoDB idempotency key written                                  |
| 1.4   | SIS processes event (`"SalesTransactionEvent processed"` in logs) |
| 1.5   | `sales.sales_events` row created in RDS                           |
| 1.6   | Raw event archived to S3                                          |
| 1.7   | EventBridge event received by IMS SQS queue                       |
| 1.8   | `inventory.inventory_positions.on_hand` decremented               |
| 1.9   | `inventory.stock_alerts` row created (if ATP < reorder_point)     |
| 1.10  | `InventoryAlertEvent` published to EventBridge                    |

**Idempotency test:** Re-send the same `transactionId` → SIS returns `409 Conflict`. No new `sales_events` row. DynamoDB key already exists.

---

## What Flow 2 Does

**Pre-condition:** Flow 1 must have completed and published an `InventoryAlertEvent` to the RE FIFO queue.

```
smartretail-re-alert-{env}.fifo          ← receives InventoryAlertEvent from Flow 1
    │  FIFO SQS consumer (message group = dcId#skuId — ordering per DC+SKU)
    ▼
RE — Replenishment Engine    (ECS Fargate, port 8082)
    ├── Look up replenishment_rules      RDS → replenishment.replenishment_rules (by skuId + dcId)
    ├── Compute quantity                 max(reorderPoint − onHand, moq)
    ├── Compute totalValue               quantity × costPerUnit
    ├── Decision: auto-approve?
    │       if totalValue ≤ autoApproveThreshold  →  workflow_status = APPROVED
    │       if totalValue > autoApproveThreshold  →  workflow_status = PENDING_APPROVAL
    ├── INSERT purchase_orders           RDS → replenishment.purchase_orders  (version = 0)
    ├── INSERT po_line_items             RDS → replenishment.po_line_items
    └── Publish domain event             EventBridge → PurchaseOrderEvent
```

Two sub-scenarios are tested:

### Scenario 2a — Auto-approve (`totalValue ≤ autoApproveThreshold`)

Seed data: `SKU-BEV-001 / DC-LONDON` has `auto_approve_threshold = 50000`. Expected `totalValue ≈ 850`.

| Check | What to verify                                                                                |
| ----- | --------------------------------------------------------------------------------------------- |
| 2a.1  | RE consumes alert (`"InventoryAlertEvent received"` in logs)                                  |
| 2a.2  | Replenishment rule found (`"Rule found for SKU/DC: lead_time=X, threshold=Y"`)                |
| 2a.3  | Auto-approve decision logged (`"totalValue <= autoApproveThreshold — auto-approving"`)        |
| 2a.4  | `replenishment.purchase_orders` row inserted with `workflow_status = APPROVED`, `version = 0` |
| 2a.5  | `replenishment.po_line_items` row inserted                                                    |
| 2a.6  | `PurchaseOrderEvent` published to EventBridge with `workflowStatus = APPROVED`                |

### Scenario 2b — Manual approval required (`totalValue > autoApproveThreshold`)

Seed data: `SKU-BEV-003 / DC-LONDON` has `auto_approve_threshold = 0` — always requires manual approval.

| Check | What to verify                                                                                        |
| ----- | ----------------------------------------------------------------------------------------------------- |
| 2b.1  | `replenishment.purchase_orders` row inserted with `workflow_status = PENDING_APPROVAL`, `version = 0` |
| 2b.2  | `PurchaseOrderEvent` published to EventBridge with `workflowStatus = PENDING_APPROVAL`                |

**EventBridge event published by RE:**
```json
{
  "source": "smartretail.re",
  "detail-type": "PurchaseOrderEvent",
  "detail": {
    "poId": "uuid",
    "supplierId": "uuid",
    "skuId": "SKU-BEV-001",
    "dcId": "DC-LONDON",
    "workflowStatus": "APPROVED",
    "quantity": 100,
    "totalValue": 850.00
  }
}
```

---

## What Flow 3 Does

**Pre-condition:** Flow 2 Scenario 2b must have run and produced a purchase order with `workflow_status = 'PENDING_APPROVAL'`.

```
SC Planner Console MFE   (localhost:5174 / CloudFront)
    │  POST /v1/replenishment/orders/{poId}/approve
    ▼  (includes Cognito JWT token)
API Gateway              (JWT Authorizer: validates SC_PLANNER role)
    │  VPC Link
    ▼
RE — Replenishment Engine    (ECS Fargate, port 8082)
    ├── Validate role            SC_PLANNER or ADMIN
    ├── Update status            RDS → replenishment.purchase_orders
    │                            (PENDING_APPROVAL → APPROVED or REJECTED)
    └── Publish PO event         EventBridge → PurchaseOrderEvent
                                     │
                       EventBridge rule: po-events
```

**Observable evidence (all 10 checks must pass):**

| Check | What to verify                                                                                |
| ----- | --------------------------------------------------------------------------------------------- |
| 3.1   | SC Planner MFE displays PENDING_APPROVAL PO in queue                                          |
| 3.2   | Approve request reaches RE (`POST /v1/replenishment/orders/{poId}/approve`)                   |
| 3.3   | JWT SC_PLANNER role is validated by the system                                                |
| 3.4   | Optimistic lock update succeeds (`version` incremented from 0 to 1)                           |
| 3.5   | `replenishment.purchase_orders` row updated (`workflow_status = APPROVED`)                     |
| 3.6   | `PurchaseOrderEvent` published to EventBridge with `workflowStatus = APPROVED`                |
| 3.7   | MFE reflects approved status (PO removed from pending queue)                                  |
| 3.8   | Reject workflow updates database to `REJECTED` and records rejection reason                   |
| 3.9   | Wrong role rejection returns `403 Forbidden` for non-planner users                            |
| 3.10  | Invalid status transition (e.g. re-approving) returns `409 Conflict`                           |

**Wrong role / Wrong status test:**
- Sign in as a `STORE_MANAGER` and attempt to approve a pending PO → returns `403 Forbidden`.
- Attempt to approve an already approved PO → returns `409 Conflict` (status must be `PENDING_APPROVAL`).

---

## What Flow 4 Does

**Pre-condition:** Flows 1–3 must have completed, and seed data must be applied to have realistic inventory, sales, and replenishment data in the PostgreSQL database.

```
Store Manager Dashboard MFE   (localhost:5173 / CloudFront)
    │  GET /v1/dashboard/store-manager?dcId=DC-LONDON
    ▼  (includes Cognito JWT token)
API Gateway                   (JWT Authorizer: validates STORE_MANAGER role)
    │  VPC Link
    ▼
ARS — Analytics & Reporting   (ECS Fargate, port 8083)
    ├── Role & DC scope check     Enforce dcId scope for STORE_MANAGER
    ├── Parallel RDS queries      Separate queries to sales, inventory, and replenishment
    └── Aggregate in Java         Merge datasets in memory (no cross-schema JOINs)
            │
            ▼  JSON response
Store Manager Dashboard MFE   (Renders inventory positions, alerts, and data freshness)
```

**Observable evidence (all 8 checks must pass):**

| Check | What to verify                                                                |
| ----- | ----------------------------------------------------------------------------- |
| 4.1   | Store Manager MFE renders dashboard without error                             |
| 4.2   | ARS receives request `GET /v1/dashboard/store-manager?dcId=DC-LONDON`          |
| 4.3   | Scope enforcement restricts `STORE_MANAGER` to their designated DC            |
| 4.4   | ARS executes parallel, non-blocking queries across database schemas           |
| 4.5   | No cross-schema SQL JOIN is executed (queries are merged in Java memory)      |
| 4.6   | Inventory KPI cards display accurate on-hand and in-transit counts            |
| 4.7   | Alert count matches active `inventory.stock_alerts` in RDS                    |
| 4.8   | `dataFreshness` timestamp is computed and rendered on the MFE                 |

**No cross-schema JOIN:** ARS fetches data in parallel across `sales`, `inventory`, and `replenishment` schemas and performs in-memory aggregation in Java to enforce complete database decoupling.

---

## What Flow 8 Does

**Pre-condition:** Seed data (`make local-seed`) must be applied. Flow 8 is read-only — no write path is exercised.

```
Seed data (V7__seed_data.sql)
    ├── forecasting.forecast_runs        30 daily MAPE values (0.1187 → 0.0823, improving)
    ├── forecasting.demand_forecasts     180 rows: 20 SKUs × 3 DCs × 3 horizons (P10/P50/P90)
    ├── inventory.stock_alerts           21 pre-seeded alerts (1 CRITICAL, 5 HIGH, 15 MEDIUM)
    ├── replenishment.purchase_orders    50 completed POs across 5 suppliers (last 90 days)
    └── supplier.supplier_pos +          shipment records with early / on-time / late delivery
        supplier.shipment_updates
            │
            ▼  GET /v1/dashboard/executive
ARS — Analytics & Reporting Service   (ECS Fargate, port 8083)
    ├── Read forecasting.forecast_runs          → Forecast Accuracy (MAPE) trend
    ├── Read inventory.stock_alerts             → Stockout Incidents by DC + category
    ├── Read replenishment.purchase_orders      → Fulfilment Rate + Replenishment Lead Time
    ├── Read supplier.supplier_pos +            → On-Time Delivery %, Supplier Comparison,
    │        supplier.shipment_updates             Delivery Histogram, Top Stockout SKUs
    └── Compute inventory carrying cost         → on_hand × cost_per_unit per replenishment_rules
    (all schema reads are separate queries — no cross-schema SQL joins)
            │
            ▼  JSON response
Executive Insights Dashboard MFE   (localhost:5175 / CloudFront)
    ├── Section 1: KPI Scorecard row        Fulfilment Rate | OTD % | MAPE | Lead Time
    ├── Section 2: Stockout Incidents       BarChart by DC and category (30-day trend)
    ├── Section 3: MAPE Trend               LineChart, 30 points, 0.15 threshold line
    ├── Section 4: Supplier Comparison      Ranked table + Delivery Histogram (Early/On-Time/Late)
    └── Section 5: Secondary KPIs          Stockout Frequency | Carrying Cost Trend | Top Stockout SKUs
```

**Observable evidence (all 11 checks must pass):**

| Check | What to verify                                                                                                             |
| ----- | -------------------------------------------------------------------------------------------------------------------------- |
| 8.1   | Fulfilment Rate KPI card renders with platform-wide fill rate %                                                            |
| 8.2   | Stockout Incidents card renders; 30-day trend chart by DC and category visible                                             |
| 8.3   | MAPE Trend LineChart renders ≥30 data points; 0.15 threshold reference line visible                                        |
| 8.4   | On-Time Delivery % KPI card renders aggregate OTD %                                                                        |
| 8.5   | Supplier Performance Comparison table renders 5 suppliers ranked by OTD (Metro Food last at 71%, Chill Chain first at 95%) |
| 8.6   | Delivery Performance Histogram renders Early / On-Time / Delayed grouped bars                                              |
| 8.7   | Inventory Carrying Cost Trend card shows % change vs prior period                                                          |
| 8.8   | Replenishment Lead Time KPI card renders average PO cycle days                                                             |
| 8.9   | Top Stockout SKUs table renders ≥5 items with DC, category, and count                                                      |
| 8.10  | EXECUTIVE role cannot call SC Planner API (`GET /v1/dashboard/sc-planner` → 403)                                           |
| 8.11  | `dataFreshness` timestamp displayed on all dashboard sections                                                              |

**No write path.** The Executive Dashboard is entirely read-only. All data comes from pre-seeded RDS rows via ARS cross-schema aggregation. ARS merges results in Java — no SQL joins across schemas.

---

## What Flow 9 Does

**Pre-condition:** Seed data (`make local-seed`) must be applied. Flow 9 exercises both read paths (ARS) and write paths (RE).

```
SC Planner Console MFE   (localhost:5174 / CloudFront)
    ├── Tab 1: Exception Queue        inventory.stock_alerts (ACTIVE) sorted by severity
    ├── Tab 2: Inventory Overview     inventory.inventory_positions by DC
    ├── Tab 3: Demand Forecast View   forecasting.demand_forecasts (P10/P50/P90)
    ├── Tab 4: Stockout Risk          Derived ATP vs reorder_point flags
    ├── Tab 5: Approval Workflows     replenishment.purchase_orders (PENDING_APPROVAL)
    ├── Tab 6: Supplier Tracking      supplier.supplier_pos + shipment progress
    ├── Tab 7: Replenishment Trigger  POST /v1/replenishment/orders (DRAFT creation)
    └── Tab 8: Forecast Adjustment    PPS-driven promotional uplift signals
            │
            ▼  GET /v1/dashboard/planner
ARS — Analytics & Reporting Service   (ECS Fargate, port 8083)
    ├── Aggregates data from 4 schemas (inventory, forecasting, replenishment, supplier)
    └── Returns unified dashboard summary + tab-specific detail blocks
            │
            ▼  POST /v1/replenishment/orders
 RE — Replenishment Engine             (ECS Fargate, port 8082)
    └── Creates DRAFT Purchase Order in replenishment schema
```

**Observable evidence (all 11 checks must pass):**

| #    | Check                                          | How to verify                                                                                                                                        |
| ---- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| 9.1  | Exception Queue renders prioritised alert list | Visual: alerts sorted CRITICAL → HIGH → MEDIUM; severity badges visible                                                                              |
| 9.2  | Inventory Overview by DC renders data per DC   | Visual: DC dropdown selector drives data; all 3 DCs selectable                                                                                       |
| 9.3  | Demand Forecast View renders P10/P50/P90 bands | Visual: Recharts AreaChart per SKU × DC; accuracy % visible                                                                                          |
| 9.4  | Stockout Risk Indicators render risk flags     | Visual: colour-coded badges (MODERATE/HIGH/CRITICAL)                                                                                                 |
| 9.5  | Approval Workflows tab shows pending POs       | Visual: PO list renders; approve/reject buttons functional                                                                                           |
| 9.6  | Supplier Order Tracking table renders PO list  | Visual: 5 suppliers; ETA and shipment progress columns visible                                                                                       |
| 9.7  | Replenishment Action Trigger creates DRAFT PO  | API: POST /v1/replenishment/orders returns 201; `SELECT workflow_status FROM replenishment.purchase_orders ORDER BY created_at DESC LIMIT 1` = DRAFT |
| 9.8  | Forecast Adjustment Controls apply uplift      | Visual: uplift % input updates P50 line in Demand Forecast chart                                                                                     |
| 9.9  | No cross-schema JOIN used                      | Code review: ARS merges separate schema queries in Java                                                                                              |
| 9.10 | Supplier scorecard renders correct OTD rates   | Visual: Metro Food 71% (red), Chill Chain 95% (green)                                                                                                |
| 9.11 | `dataFreshness` displayed on all surfaces      | Visual: "Data as of HH:MM" visible on each tab                                                                                                       |


---

## Prerequisites


| Tool           | Version | Install                                                             |
| -------------- | ------- | ------------------------------------------------------------------- |
| Java           | 21      | `sdk install java 21.0.3-tem`                                       |
| Maven          | 3.9.x   | `sdk install maven`                                                 |
| Docker Desktop | 4.x     | https://www.docker.com/products/docker-desktop                      |
| Node.js        | 20.x    | `nvm install 20`                                                    |
| AWS CLI        | v2      | https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html |
| Python         | 3.11+   | For test scripts                                                    |
| psql           | any     | PostgreSQL client                                                   |

---

## Local Quick Start (≈5 minutes)

### 1. Start infrastructure

```bash
docker-compose up -d

# Wait for Postgres
until docker exec smartretail-postgres pg_isready -U smartretail_admin; do sleep 2; done

# Wait for LocalStack
until curl -s http://localhost:4566/_localstack/health | grep '"kinesis": "running"'; do sleep 3; done
```

LocalStack automatically creates all resources on startup via `scripts/localstack-init.sh`:
- Kinesis stream `smartretail-events-local`
- EventBridge bus `smartretail-events-local` with 3 routing rules
- SQS queues: `ims-sales-local`, `re-alert-local.fifo`, `ars-updates-local` (each with DLQ)
- DynamoDB table `smartretail-idempotency-keys-local`
- S3 bucket `smartretail-events-local`
- SSM parameters read by Spring Boot at startup

### 2. Run schema migrations and seed data

```bash
make local-migrate   # Flyway V1–V6: creates all 6 RDS schemas
make local-seed      # Flyway V7: loads reference/test data
```

Verify:
```bash
PGPASSWORD=local_dev_password psql -h localhost -U smartretail_admin -d smartretail \
  -c "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('sales','inventory','replenishment','forecasting','supplier','promotions') ORDER BY 1;"
# Expected: 6 rows
```

### 3. Start services

Open 4 terminals (or use `&` to background):

```bash
make local-sis   # :8080 — Sales Ingestion Service
make local-ims   # :8081 — Inventory Management Service
make local-re    # :8082 — Replenishment Engine
make local-ars   # :8083 — Analytics & Reporting Service
make local-dfs   # :8084 — Demand Forecasting Service
make local-sup   # :8085 — Supplier Service
```

Health-check all services:
```bash
for port in 8080 8081 8082 8083 8084 8085; do
  curl -s http://localhost:$port/actuator/health | python3 -m json.tool
done
# All should return: {"status":"UP"}
```

### 4. Run the smoke tests

```bash
make test-flow1
# Expected: ✅ 5 passed  ❌ 0 failed

make test-flow2
# Expected: ✅ 2 passed  ❌ 0 failed
# Pre-condition: Flow 1 must have run first (RE queue must have an InventoryAlertEvent)

make test-flow3
# Expected: ✅ 4 passed  ❌ 0 failed
# Pre-condition: Flow 2 must have run first (having a PO in PENDING_APPROVAL)

make test-flow4
# Expected: ✅ 3 passed  ❌ 0 failed
# Pre-condition: Flows 1-3 must have run first (having data in schemas)

make test-flow8
# Expected: ✅ 11 passed  ❌ 0 failed
# Pre-condition: make local-seed must have run (seed data required)

make test-flow9
# Expected: ✅ 11 passed  ❌ 0 failed
# Pre-condition: make local-seed must have run (seed data required)
```

Trigger Flow 1 manually:
```bash
python3 scripts/publish-pos-event.py \
  --transaction-id $(python3 -c "import uuid; print(uuid.uuid4())") \
  --direct-api http://localhost:8080
```

Verify Flow 2 RDS state directly:
```bash
# Auto-approve PO (SKU-BEV-001)
docker exec smartretail-postgres psql -U smartretail_admin -d smartretail \
  -c "SELECT po_id, workflow_status, version FROM replenishment.purchase_orders WHERE sku_id = 'SKU-BEV-001' AND dc_id = 'DC-LONDON' ORDER BY created_at DESC LIMIT 1;"

# Manual approval PO (SKU-BEV-003)
docker exec smartretail-postgres psql -U smartretail_admin -d smartretail \
  -c "SELECT po_id, workflow_status, version FROM replenishment.purchase_orders WHERE sku_id = 'SKU-BEV-003' AND dc_id = 'DC-LONDON' ORDER BY created_at DESC LIMIT 1;"
```

Verify Flow 8 via the Executive Dashboard MFE at http://localhost:5175 (sign in as `exec1@test.com`):
```bash
# Or directly via ARS
curl -s http://localhost:8083/v1/dashboard/executive | python3 -m json.tool
# Expected: kpis block with fulfilmentRate, onTimeDelivery, forecastAccuracy (9 surfaces)
```

### 5. Stop

```bash
make local-down    # stop containers, keep data volumes
make local-clean   # stop containers, destroy volumes (clean slate)
```

---

## Port Assignments (local mode)

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

---

## Key API Endpoints

### SIS — POST /v1/ingest/events

Called by the Kinesis Consumer Lambda. Accepts a POS sales event, deduplicates, persists, and fans out.

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

### IMS — GET /v1/inventory/positions

```bash
curl "http://localhost:8081/v1/inventory/positions?dcId=DC-LONDON"
```

### IMS — GET /v1/inventory/alerts

```bash
curl "http://localhost:8081/v1/inventory/alerts?status=ACTIVE"
```

### RE — GET /v1/replenishment/orders

```bash
# All purchase orders
curl "http://localhost:8082/v1/replenishment/orders"

# Filter by status
curl "http://localhost:8082/v1/replenishment/orders?status=PENDING_APPROVAL"
curl "http://localhost:8082/v1/replenishment/orders?status=APPROVED"

# Single PO with line items
curl "http://localhost:8082/v1/replenishment/orders/{poId}"
```

Flow 2 is entirely event-driven (SQS consumer). The RE service creates purchase orders autonomously — no REST call required to trigger it.

### ARS — GET /v1/dashboard/executive

Returns all nine Executive Dashboard KPI surfaces in one response. Requires seed data (`make local-seed`).

```bash
curl -s http://localhost:8083/v1/dashboard/executive | python3 -m json.tool
# Returns:
# {
#   "kpis": {
#     "fulfilmentRate": { "value": 0.94, "trend": "STABLE" },
#     "onTimeDelivery": { "value": 0.86, "trend": "IMPROVING" },
#     "forecastAccuracy": { "latestMape": 0.0823, "status": "WITHIN_THRESHOLD", "history": [...30 points] },
#     "replenishmentLeadTime": { "avgDays": 6.2, "trend": "STABLE" },
#     "stockoutIncidents": { "last30Days": 12, "byDc": {...}, "byCategory": {...} },
#     "supplierPerformance": [ ...5 suppliers ranked by OTD ],
#     "deliveryHistogram": { "early": {...}, "onTime": {...}, "late": {...} },
#     "inventoryCarryingCost": { "currentPeriod": 128450.00, "priorPeriod": 124230.00, "changePercent": 3.4 },
#     "topStockoutSkus": [ ...top 5 SKUs ]
#   },
#   "dataFreshness": "2026-05-16T11:00:00Z"
# }
```
---

## Technology Stack

| Layer             | Technology         | Version    |
| ----------------- | ------------------ | ---------- |
| Services          | Java + Spring Boot | 21 / 3.3.x |
| Build             | Maven              | 3.9.x      |
| DB access         | Spring Data JDBC   | 3.3.x      |
| Schema migrations | Flyway             | 10.x       |
| IaC               | AWS CDK TypeScript | 2.x        |
| MFE               | React + TypeScript | 18 / 5.x   |
| MFE styling       | Tailwind CSS       | 3.x        |
| MFE charts        | Recharts           | 2.x        |
| MFE auth          | @aws-amplify/auth  | 6.x        |
| Lambda            | Java               | 21         |
| Local AWS         | LocalStack         | 3.x        |
| Local DB          | PostgreSQL Docker  | 15         |

---

## Architecture

All services follow **hexagonal architecture** (Ports & Adapters). The domain core has zero AWS imports — all AWS SDK usage is confined to `adapter/` packages. Enforced by ArchUnit tests.

```
com.smartretail.{service}/
├── domain/
│   ├── model/       ← aggregates, entities, value objects
│   └── usecase/     ← application services
├── port/
│   ├── inbound/     ← port interfaces (called by adapters)
│   └── outbound/    ← port interfaces (implemented by adapters)
└── adapter/
    ├── inbound/
    │   ├── rest/    ← Spring MVC controllers
    │   └── sqs/     ← SQS message listeners
    └── outbound/
        ├── persistence/ ← Spring Data JDBC repositories
        ├── event/       ← EventBridge publisher
        └── messaging/   ← SQS sender
```

**RDS schemas (one per bounded context):**

| Schema          | Owner      |
| --------------- | ---------- |
| `sales`         | SIS        |
| `inventory`     | IMS        |
| `replenishment` | RE         |
| `forecasting`   | DFS        |
| `supplier`      | SUP        |
| `promotions`    | PPS (stub) |

No cross-schema SQL joins anywhere. ARS reads multiple schemas via separate queries merged in Java.

---

## Repository Structure

```
smartretail/
├── CLAUDE.md                   ← AI coding assistant instructions
├── Makefile                    ← all common operations
├── docker-compose.yml          ← Postgres + LocalStack
├── .claude/
│   ├── settings.json           ← AI agent definitions
│   └── standards/              ← coding standards (java, openapi, maven, frontend, sql, testing)
├── docs/                       ← architecture, API contracts, flow specs, schemas
├── openapi/                    ← OpenAPI 3.1 YAML (source of truth for all REST APIs)
├── infra/cdk/                  ← AWS CDK TypeScript stacks
├── services/
│   ├── sis/                    ← Sales Ingestion Service
│   ├── ims/                    ← Inventory Management Service
│   ├── re/                     ← Replenishment Engine
│   ├── ars/                    ← Analytics & Reporting Service
│   ├── dfs/                    ← Demand Forecasting Service
│   └── sup/                    ← Supplier Service
├── lambdas/kinesis-consumer/   ← Kinesis → SIS adapter Lambda
├── migrations/flyway/          ← Flyway SQL migrations (V1–V7)
├── mfe/
│   ├── shared/auth/            ← shared Cognito auth library
│   ├── store-manager/          ← Store Manager Dashboard MFE (:5173)
│   ├── sc-planner/             ← SC Planner Console MFE (:5174)
│   └── executive/              ← Executive Insights Dashboard MFE (:5175)
└── scripts/
    ├── localstack-init.sh      ← creates all LocalStack resources on startup
    ├── publish-pos-event.py    ← Flow 1 trigger / test harness
    ├── smoke-test.sh           ← automated flow verification
    ├── run-flyway-aws.sh       ← runs Flyway against real RDS
    ├── create-cognito-users.sh ← creates test users in Cognito
    └── generate-mfe-config.sh  ← injects API endpoint into MFE config
```

---

## Run Modes

No code changes required to switch between modes.

| Mode    | Profile | AWS services         | Database              | Auth        |
| ------- | ------- | -------------------- | --------------------- | ----------- |
| `local` | `local` | LocalStack :4566     | Postgres Docker :5432 | Mock bypass |
| `aws`   | `aws`   | Real AWS (us-east-1) | RDS via RDS Proxy     | Cognito JWT |

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run   # local mode
SPRING_PROFILES_ACTIVE=aws  mvn spring-boot:run    # aws mode
```

---

## Documentation

| Document                                             | Contents                                                    |
| ---------------------------------------------------- | ----------------------------------------------------------- |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)       | All confirmed architecture decisions                        |
| [`docs/FLOWS.md`](docs/FLOWS.md)                     | Flow specifications + observable evidence checklists        |
| [`docs/API_CONTRACTS.md`](docs/API_CONTRACTS.md)     | REST endpoints, request/response shapes, EventBridge events |
| [`docs/SCHEMAS.md`](docs/SCHEMAS.md)                 | All 6 RDS schemas + DynamoDB table                          |
| [`docs/SERVICE_SPECS.md`](docs/SERVICE_SPECS.md)     | Per-service hexagonal package structure + code patterns     |
| [`docs/CDK_SPEC.md`](docs/CDK_SPEC.md)               | CDK TypeScript stack specifications                         |
| [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md)             | Local development guide (full detail)                       |
| [`docs/BUILD_SEQUENCE.md`](docs/BUILD_SEQUENCE.md)   | Exact commands for local and AWS build/deploy               |
| [`docs/SEED_DATA.md`](docs/SEED_DATA.md)             | Reference data, test users, seed SQL                        |
| [`docs/MFE_SPECS.md`](docs/MFE_SPECS.md)             | React MFE components, API calls, auth library               |
| [`docs/DEVELOPER_GUIDE.md`](docs/DEVELOPER_GUIDE.md) | Developer onboarding, daily workflow, debugging             |

---

## Flows Roadmap

| Flow  | Description                                                                                                                                                                                          | Status        |
| ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| **1** | POS event → SIS → RDS → IMS → stock alert → EventBridge                                                                                                                                              | ✅ Implemented |
| **2** | Inventory alert → RE auto-approve → RDS state transition                                                                                                                                             | ✅ Implemented |
| **3** | SC Planner MFE → RE approve/reject → RDS → EventBridge                                                                                                                                               | ✅ Implemented |
| **4** | ARS → Store Manager Dashboard MFE                                                                                                                                                                    | ✅ Implemented |
| **8** | Executive Dashboard — fulfilment rate, stockout incidents, MAPE, OTD, supplier comparison, delivery histogram, inventory carrying cost, replenishment lead time, top stockout SKUs                   | ✅ Implemented |
| **9** | SC Planner Console — exception queue, inventory overview, demand forecast (P10/P50/P90), stockout risk indicators, PO approvals, supplier order tracking, replenishment trigger, forecast adjustment | ✅ Implemented |
