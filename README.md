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

| Check | What to verify                                                                 |
| ----- | ------------------------------------------------------------------------------ |
| 3.1   | SC Planner MFE displays PENDING_APPROVAL PO in queue                           |
| 3.2   | Approve request reaches RE (`POST /v1/replenishment/orders/{poId}/approve`)    |
| 3.3   | JWT SC_PLANNER role is validated by the system                                 |
| 3.4   | Optimistic lock update succeeds (`version` incremented from 0 to 1)            |
| 3.5   | `replenishment.purchase_orders` row updated (`workflow_status = APPROVED`)     |
| 3.6   | `PurchaseOrderEvent` published to EventBridge with `workflowStatus = APPROVED` |
| 3.7   | MFE reflects approved status (PO removed from pending queue)                   |
| 3.8   | Reject workflow updates database to `REJECTED` and records rejection reason    |
| 3.9   | Wrong role rejection returns `403 Forbidden` for non-planner users             |
| 3.10  | Invalid status transition (e.g. re-approving) returns `409 Conflict`           |

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

| Check | What to verify                                                           |
| ----- | ------------------------------------------------------------------------ |
| 4.1   | Store Manager MFE renders dashboard without error                        |
| 4.2   | ARS receives request `GET /v1/dashboard/store-manager?dcId=DC-LONDON`    |
| 4.3   | Scope enforcement restricts `STORE_MANAGER` to their designated DC       |
| 4.4   | ARS executes parallel, non-blocking queries across database schemas      |
| 4.5   | No cross-schema SQL JOIN is executed (queries are merged in Java memory) |
| 4.6   | Inventory KPI cards display accurate on-hand and in-transit counts       |
| 4.7   | Alert count matches active `inventory.stock_alerts` in RDS               |
| 4.8   | `dataFreshness` timestamp is computed and rendered on the MFE            |

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
docker exec smartretail-postgres psql -U smartretail_admin -d smartretail \
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

| Service                       | Port     |
| ----------------------------- | -------- |
| SIS — Sales Ingestion         | 8080     |
| IMS — Inventory Management    | 8081     |
| RE — Replenishment Engine     | 8082     |
| ARS — Analytics & Reporting   | 8083     |
| DFS — Demand Forecasting      | 8084     |
| SUP — Supplier Service        | 8085     |
| PostgreSQL                    | 5432     |
| LocalStack (all AWS services) | 4566     |
| Store Manager MFE             | 5173     |
| SC Planner MFE                | 5174     |
| Executive MFE                 | 5175     |
| **Demo Control Center MFE**   | **5176** |
| **Demo Control Server**       | **3099** |

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
├── infra/cdk-min/              ← demo/dev CDK stacks (SQS, default VPC) — run this
├── infra/cdk-prod/             ← production CDK stacks (Kinesis, dedicated VPC)
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
│   ├── executive/              ← Executive Insights Dashboard MFE (:5175)
│   └── demo/                   ← Demo Control Center MFE (:5176)
├── demo-server/                ← Demo control server (:3099) — triggers scripts, streams SSE
└── scripts/
    ├── localstack-init.sh      ← creates all LocalStack resources on startup
    ├── publish-pos-event.py    ← Flow 1 trigger / test harness
    ├── smoke-test.sh           ← automated flow verification
    ├── deploy-cdk.sh           ← bootstrap + deploy all CDK stacks
    ├── deploy-services.sh      ← Maven → Docker → ECR push → ECS force-deploy
    ├── deploy-mfes.sh          ← npm build → S3 sync → CloudFront invalidation
    ├── destroy-infra.sh        ← full AWS resource teardown
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

## AWS Deployment

### CDK stacks

The demo/dev stack is in `infra/cdk-min/` (SQS-only, reuses existing default VPC, `Min-*` stack names). Stacks must be deployed in dependency order.

| Stack            | What it provisions                                                                              |
| ---------------- | ----------------------------------------------------------------------------------------------- |
| `NetworkStack`   | VPC, subnets, NAT gateways, security groups                                                     |
| `DataStack`      | RDS PostgreSQL (+ RDS Proxy), DynamoDB idempotency table, S3 events bucket, S3 MFE buckets (×4) |
| `MessagingStack` | Kinesis stream, EventBridge bus + rules, SQS queues + DLQs                                      |
| `IdentityStack`  | Cognito User Pool, app clients, user groups (STORE\_MANAGER / SC\_PLANNER / EXECUTIVE)          |
| `ComputeStack`   | ECS cluster, Fargate services (sis/ims/re/ars/dfs/sup), ECR repos, Kinesis consumer Lambda      |
| `ApiStack`       | HTTP API Gateway, VPC Link, JWT authoriser, routes for all six services                         |
| `HostingStack`   | CloudFront distributions (×4 MFEs) with S3 OAC, SSM outputs for distribution IDs and URLs       |

### Prerequisites

```bash
# AWS profile must have access to us-east-1
aws configure --profile smartretail-dev

export AWS_PROFILE=smartretail-dev
export SMARTRETAIL_ENV=dev        # or prod
export CDK_DEFAULT_REGION=us-east-1
```

### First-time full deployment (≈45 minutes)

`make aws-full-deploy` runs every step in the correct order:

```bash
make aws-full-deploy ENV=dev PROFILE=smartretail-dev
```

It performs these five steps in sequence:

```
Step 1  scripts/deploy-cdk.sh          → bootstrap + deploy all 7 CDK stacks
Step 2  scripts/deploy-services.sh     → Maven build → Docker build → ECR push
                                          → force ECS redeployment for all 6 services
                                          → update Lambda function code
Step 3  scripts/deploy-mfes.sh         → npm build → S3 sync → CloudFront invalidation
                                          for all 4 MFEs (store-manager, sc-planner,
                                          executive, demo)
Step 4  scripts/run-flyway-aws.sh      → run Flyway migrations against RDS
Step 5  scripts/create-cognito-users.sh → create test Cognito users
```

### Deploying changes (after first-time setup)

**Service code change** — rebuild JARs, push images, force ECS rolling deploy:

```bash
# All 6 services + Lambda
make aws-deploy-services ENV=dev

# Single service (e.g. only re and ims)
./scripts/deploy-services.sh --env dev --services re,ims

# With wait — blocks until ECS reaches steady state
make aws-deploy-services-wait ENV=dev
./scripts/deploy-services.sh --env dev --wait
```

**MFE change** — rebuild, sync to S3, invalidate CloudFront:

```bash
# All 4 MFEs
make aws-deploy-mfes ENV=dev

# One MFE (e.g. only the demo MFE)
./scripts/deploy-mfes.sh --env dev --mfes demo

# Skip npm build (use existing dist/)
./scripts/deploy-mfes.sh --env dev --mfes executive --skip-build
```

**Infrastructure (CDK) change** — deploy the affected stack:

```bash
make aws-deploy-hosting    # HostingStack only (CloudFront changes)
make aws-deploy-compute    # ComputeStack only (ECS task changes)
make aws-deploy-api        # ApiStack only (route changes)
make aws-deploy-all        # all stacks (safest when unsure)
```

**DB migration only:**

```bash
make aws-migrate ENV=dev
```

### Script reference

#### `scripts/deploy-services.sh`

Builds all Java JARs, builds Docker images, pushes to ECR, forces ECS redeployment, and updates the Lambda container image.

```
Options:
  --env       dev|prod               Environment (default: $SMARTRETAIL_ENV or dev)
  --profile   aws-profile            AWS CLI profile (default: smartretail-dev)
  --region    region                 AWS region (default: us-east-1)
  --services  sis,ims,re,ars,dfs,sup Comma-separated subset to deploy (default: all six)
  --no-lambda                        Skip Lambda build and update
  --skip-build                       Reuse existing JARs in target/ — skip Maven
  --skip-push                        Build Docker images locally but skip ECR push + ECS update
  --wait                             Block until every targeted ECS service reaches steady state
```

Examples:

```bash
# Deploy everything, wait for stable
./scripts/deploy-services.sh --env dev --wait

# Deploy only SIS after a hotfix (skip full Maven build)
./scripts/deploy-services.sh --env dev --services sis --skip-build

# Dry-run: build images locally without touching AWS
./scripts/deploy-services.sh --env dev --skip-push
```

#### `scripts/deploy-mfes.sh`

Builds each React MFE, syncs `dist/` to the S3 bucket, and creates a CloudFront invalidation. The CloudFront distribution ID is read automatically from SSM (`/smartretail/{env}/cloudfront/{mfe}-distribution-id`). The live URL is printed at the end.

```
Options:
  --env       dev|prod                              Environment (default: $SMARTRETAIL_ENV or dev)
  --profile   aws-profile                           AWS CLI profile (default: smartretail-dev)
  --mfes      store-manager,sc-planner,executive,demo  Subset to deploy (default: all four)
  --skip-build                                      Reuse existing dist/ — skip npm build
```

Examples:

```bash
# Deploy all four MFEs
./scripts/deploy-mfes.sh --env dev

# Redeploy only the demo MFE after a presentation fix
./scripts/deploy-mfes.sh --env dev --mfes demo

# Push pre-built artifacts (e.g. from CI)
./scripts/deploy-mfes.sh --env dev --skip-build
```

#### `scripts/deploy-cdk.sh`

Installs CDK dependencies, synthesises, and deploys all 7 stacks with `--require-approval never`.

```bash
SMARTRETAIL_ENV=dev ./scripts/deploy-cdk.sh
```

### Teardown

```bash
# Destroy all CDK stacks only — ECR images and S3 objects are preserved
make aws-undeploy ENV=dev PROFILE=smartretail-dev

# Full teardown — CDK stacks + S3 buckets + ECR repos + CloudFront + SSM + CloudWatch logs
make aws-destroy ENV=dev PROFILE=smartretail-dev
# Equivalent: SMARTRETAIL_ENV=dev ./scripts/destroy-infra.sh
```

`destroy-infra.sh` destroys stacks in reverse dependency order (HostingStack first, NetworkStack last), then cleans up any orphaned CloudFront distributions, S3 buckets, ECR repos, CloudWatch log groups, SSM parameters, ENIs, security groups, Secrets Manager secrets, and Cognito pools.

---

## Makefile Reference

All Makefile targets accept `ENV` (default: `local`) and `PROFILE` (default: `smartretail-dev`).

```bash
make <target> ENV=dev PROFILE=smartretail-dev
```

### Local development

| Target              | Description                                                                   |
| ------------------- | ----------------------------------------------------------------------------- |
| `local-up`          | Start Postgres + LocalStack via Docker Compose; wait for readiness            |
| `local-migrate`     | Run Flyway migrations V1–V6 against local Postgres                            |
| `local-seed`        | Apply seed data (V7)                                                          |
| `local-sis`         | Start SIS on :8080 with `SPRING_PROFILES_ACTIVE=local`                        |
| `local-ims`         | Start IMS on :8081                                                            |
| `local-re`          | Start RE on :8082                                                             |
| `local-ars`         | Start ARS on :8083                                                            |
| `local-dfs`         | Start DFS on :8084                                                            |
| `local-sup`         | Start SUP on :8085                                                            |
| `local-mfe-sm`      | Start Store Manager MFE on :5173                                              |
| `local-mfe-scp`     | Start SC Planner MFE on :5174                                                 |
| `local-mfe-exec`    | Start Executive MFE on :5175                                                  |
| `local-demo-server` | Start Demo Control Server on :3099                                            |
| `local-mfe-demo`    | Start Demo Control Center MFE on :5176                                        |
| `local-demo`        | Start both demo-server and demo MFE in parallel                               |
| `local-free-ports`  | Find and terminate host processes holding ports 8080-8085 and 5173-5176       |
| `local-down`        | Stop containers, preserve data volumes (calls local-free-ports automatically) |
| `local-clean`       | Stop containers, destroy volumes (calls local-free-ports automatically)       |

### Testing

| Target       | Description                                             |
| ------------ | ------------------------------------------------------- |
| `test-unit`  | Run unit tests for all 6 services via Maven             |
| `test-flow1` | Smoke test Flow 1 (POS → SIS → IMS → stock alert)       |
| `test-flow2` | Smoke test Flow 2 (RE auto-approve + PENDING\_APPROVAL) |
| `test-flow3` | Smoke test Flow 3 (SC Planner approve/reject)           |
| `test-flow4` | Smoke test Flow 4 (ARS → Store Manager dashboard)       |
| `test-flow8` | Smoke test Flow 8 (Executive Dashboard)                 |
| `test-flow9` | Smoke test Flow 9 (SC Planner Console)                  |
| `test-all`   | Run smoke tests for all flows                           |

### Build

| Target                | Description                                                                 |
| --------------------- | --------------------------------------------------------------------------- |
| `build-services`      | `mvn clean package -DskipTests` for all 6 services                          |
| `build-lambda`        | `mvn clean package -DskipTests` for kinesis-consumer Lambda                 |
| `build-mfes`          | `npm run build` for all 4 MFEs (store-manager, sc-planner, executive, demo) |
| `build-all`           | All of the above                                                            |
| `docker-build-sis`    | Build SIS Docker image locally                                              |
| `docker-build-all`    | Build Docker images for all 6 services locally                              |
| `docker-build-lambda` | Build Kinesis consumer Lambda Docker image locally                          |

### AWS infrastructure

| Target                 | Description                                       |
| ---------------------- | ------------------------------------------------- |
| `aws-bootstrap`        | CDK bootstrap (one-time per account/region)       |
| `aws-deploy-network`   | Deploy NetworkStack                               |
| `aws-deploy-data`      | Deploy DataStack (RDS, DynamoDB, S3 buckets)      |
| `aws-deploy-messaging` | Deploy MessagingStack                             |
| `aws-deploy-identity`  | Deploy IdentityStack (Cognito)                    |
| `aws-deploy-compute`   | Deploy ComputeStack (ECS, Lambda)                 |
| `aws-deploy-api`       | Deploy ApiStack (API Gateway)                     |
| `aws-deploy-hosting`   | Deploy HostingStack (CloudFront distributions)    |
| `aws-deploy-all`       | Deploy all stacks with `--require-approval never` |

### AWS artifacts

| Target                     | Description                                                    |
| -------------------------- | -------------------------------------------------------------- |
| `aws-ecr-login`            | Authenticate Docker to ECR                                     |
| `aws-push-<svc>`           | Build + push a single service image (e.g. `aws-push-sis`)      |
| `aws-push-all`             | Build + push all 6 service images                              |
| `aws-push-lambda`          | Build + push Lambda container image                            |
| `aws-deploy-mfe-<name>`    | Build + deploy a single MFE (e.g. `aws-deploy-mfe-demo`)       |
| `aws-deploy-mfes`          | Build + deploy all 4 MFEs                                      |
| `aws-deploy-services`      | Full service pipeline: Maven → Docker → ECR → ECS force-deploy |
| `aws-deploy-services-wait` | Same as above, waits for ECS steady state before returning     |

### AWS operations

| Target             | Description                                                             |
| ------------------ | ----------------------------------------------------------------------- |
| `aws-migrate`      | Run Flyway migrations against RDS via `run-flyway-aws.sh`               |
| `aws-create-users` | Create test Cognito users via `create-cognito-users.sh`                 |
| `aws-smoke-test`   | Run all smoke tests against AWS endpoints                               |
| `aws-full-deploy`  | **First-time end-to-end deploy**: CDK → images → MFEs → migrate → users |
| `aws-demo`         | Start demo-server in AWS mode + demo MFE                                |
| `aws-undeploy`     | Destroy all CDK stacks (`cdk destroy --all`); S3/ECR untouched          |
| `aws-destroy`      | Full teardown via `destroy-infra.sh`; wipes all AWS resources           |

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

---

## Demo Control Center

The Demo Control Center is a single-browser experience for presenting all six flows to a technical audience (architects, engineers). The presenter never touches a terminal — every trigger, verification, and MFE reveal is orchestrated from one window.

```
┌──────────────┬─────────────────────────────────────────┬──────────────────┐
│  FLOW RAIL   │           CENTER CANVAS                 │   EVENT LOG      │
│              │  ┌─────────────────────────────────┐   │                  │
│ 01 ● Flow 1  │  │  Chapter hero + narrative prose  │   │  ✓ SIS saved     │
│ 02 ○ Flow 2  │  │  Animated SVG architecture diagram│  │  ✓ IMS alert     │
│ 03 ○ Flow 3  │  │  Before / After DB state panels  │   │  ✓ RE auto-PO    │
│ 04 ○ Flow 4  │  │  Evidence checklist (live ticks) │   │  · Waiting…      │
│ 05 ○ Flow 8  │  │  [ Fire POS Transaction ▶ ]      │   │                  │
│ 06 ○ Flow 9  │  │  ┌── Store Manager MFE iframe ──┐│   │                  │
│              │  │  │  (slides in at right moment)  ││   │                  │
└──────────────┴──┴──┴───────────────────────────────┴┴───┴──────────────────┘
```

### How it works

Two new processes start alongside the existing services:

| Process                      | Port | Role                                                                                                                                  |
| ---------------------------- | ---- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `demo-server` (Node/Express) | 3099 | Spawns `publish-pos-event.py` / `smoke-test.sh`, streams stdout to browser via SSE, queries Postgres for before/after DB state panels |
| `mfe/demo` (Vite + React)    | 5176 | Mission Control UI — flow rail, animated SVG diagram, narrative heroes, evidence checklists, MFE iframes                              |

The architecture diagram shows every service node (Kinesis → Lambda → SIS → EventBridge → IMS / RE → RDS → ARS → MFEs). Nodes pulse and animated dots travel along edges in real time as SSE log lines arrive. The live evidence checklist auto-checks each item when a matching string appears in the log stream.

### Demo narrative arc

| Chapter | Flow   | Title                             | Key moment                                                                          |
| ------- | ------ | --------------------------------- | ----------------------------------------------------------------------------------- |
| 1       | Flow 1 | A Customer Buys Something         | Fire POS event — watch Kinesis → SIS → IMS animate; stock alert row appears         |
| 2       | Flow 2 | The System Responds Automatically | RE evaluates the alert; split reveal shows auto-approved PO vs PENDING\_APPROVAL PO |
| 3       | Flow 3 | The Planner Decides               | SC Planner MFE slides in — presenter approves the live PO; EventBridge fires        |
| 4       | Flow 4 | The Store Manager Reacts          | Store Manager MFE slides in — DC-LONDON KPIs show the active alert from Chapter 1   |
| 5       | Flow 8 | Leadership Reviews Performance    | Executive Dashboard — MAPE trend improving 0.1187 → 0.0823 across 30 seed days      |
| 6       | Flow 9 | The Planner Optimizes             | All 8 SC Planner Console tabs; manual replenishment trigger creates DRAFT PO live   |

---

### Running the demo locally

**Prerequisites:** all six backend services and all three operational MFEs must be running (see [Local Quick Start](#local-quick-start-5-minutes) above).

```bash
# Terminal 1 — demo control server (reads Postgres, spawns scripts)
make local-demo-server

# Terminal 2 — Demo Control Center MFE
make local-mfe-demo

# Or both in one command:
make local-demo
```

Open **[http://localhost:5176](http://localhost:5176)**.

The service health bar at the top shows a green dot for each service once all six are up. Red dots mean the service is not reachable — fix those before starting the demo.

**Full local startup sequence (copy-paste):**

```bash
# Step 1 — infrastructure
make local-up
make local-migrate
make local-seed

# Step 2 - Build all
make build-all

# Step 3 - Free up ports
make local-free-ports

# Step 4 - backend services (run each in its own terminal or background with &)
make local-sis &
make local-ims &
make local-re  &
make local-ars &
make local-dfs &
make local-sup &

# Step 5 — operational MFEs (needed for iframe reveals in chapters 3, 4, 5, 6)
make local-mfe-sm   &
make local-mfe-scp  &
make local-mfe-exec &

# Step 6 — demo experience
make local-demo
```

Open **[http://localhost:5176](http://localhost:5176)** — all health dots should be green.

**Demo flow:**
1. Click a chapter in the left rail to jump to it.
2. Use the step progress bar to move through each chapter's steps.
3. Click trigger buttons (e.g. **Fire POS Transaction**) — the architecture diagram animates and the event log fills in real time.
4. When a step requires the live MFE, an iframe slides in within the canvas — interact with it directly (approve a PO, browse tabs, trigger replenishment).
5. Run **Verify FlowN** at the end of each chapter to execute the smoke test and auto-check the evidence checklist.

---

### Running the demo on AWS

The demo-server switches to AWS mode via `SMARTRETAIL_ENV=aws`. In this mode it routes trigger calls to real API Gateway endpoints, reads MFE URLs from environment variables, and falls back to the ARS REST API for before/after DB state (no direct RDS access from a demo laptop).

**Step 1 — deploy the platform to AWS first:**

```bash
export AWS_PROFILE=smartretail-dev
export SMARTRETAIL_ENV=dev

make aws-full-deploy ENV=dev PROFILE=smartretail-dev
# Runs: CDK stacks → ECR image push → MFE deploy → DB migrate → Cognito users
```

See the [AWS Deployment](#aws-deployment) section for the full breakdown of what this does.

**Step 2 — resolve service and MFE URLs from SSM:**

```bash
export SIS_URL=$(aws ssm get-parameter \
  --name /smartretail/dev/api-gateway/endpoint --query Parameter.Value --output text)
export RE_URL=$SIS_URL    # all services are behind the same API Gateway
export ARS_URL=$SIS_URL

export MFE_SM_URL=$(aws ssm get-parameter \
  --name /smartretail/dev/mfe/store-manager-url --query Parameter.Value --output text)
export MFE_SCP_URL=$(aws ssm get-parameter \
  --name /smartretail/dev/mfe/sc-planner-url --query Parameter.Value --output text)
export MFE_EXEC_URL=$(aws ssm get-parameter \
  --name /smartretail/dev/mfe/executive-url --query Parameter.Value --output text)
```

**Step 3 — start the demo:**

```bash
make aws-demo
```

Open **[http://localhost:5176](http://localhost:5176)**. The architecture diagram gains additional edge labels (ALB → VPC Link → ECS → RDS Proxy → RDS) and node tooltips show the live AWS resource IDs pulled from SSM.

**AWS mode differences:**
- Trigger buttons call real API Gateway endpoints (with `X-Dev-Role` headers for local auth bypass or real Cognito tokens if Cognito is wired)
- `smoke-test.sh` runs against `SMARTRETAIL_ENV=dev` — reads RDS Proxy endpoint from SSM
- Before/After DB panels use ARS REST responses instead of direct Postgres queries
- MFE iframes load from CloudFront URLs instead of localhost ports

---

### Demo troubleshooting

| Symptom                                                   | Fix                                                                                                                              |
| --------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| All health dots red                                       | `make local-demo-server` must be running; confirm all 6 services are up with `curl localhost:8080/actuator/health`               |
| "Flow X is already running" on trigger                    | A previous smoke test is still running (smoke tests have a `sleep 15`); wait for it to complete or refresh the page              |
| Chapter 3 — no PENDING\_APPROVAL PO in the approval queue | Click **Create Test PENDING\_APPROVAL PO** in step 1 of Chapter 3 to inject one                                                  |
| MFE iframe shows login page instead of dashboard          | Auth mock is active — the MFE should auto-login in LOCAL mode; check that `SPRING_PROFILES_ACTIVE=local` is set for all services |
| Event log empty after trigger                             | demo-server SSE connection dropped; reload the page — the `EventSource` auto-reconnects                                          |
