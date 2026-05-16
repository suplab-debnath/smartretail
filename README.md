# SmartRetail — Demand Forecasting & Supply Chain Platform

A prototype of an event-driven retail supply chain platform built on AWS.
It demonstrates a complete POS-to-inventory pipeline using Kinesis, Lambda, ECS Fargate, EventBridge, SQS, RDS PostgreSQL, and DynamoDB.

> **Implementation status:** Flow 1 is implemented and tested. Flows 2–9 are specified but not yet built.

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

| Check | What to verify |
|-------|---------------|
| 1.1 | Kinesis record ingested |
| 1.2 | Lambda invoked (CloudWatch Logs) |
| 1.3 | DynamoDB idempotency key written |
| 1.4 | SIS processes event (`"SalesTransactionEvent processed"` in logs) |
| 1.5 | `sales.sales_events` row created in RDS |
| 1.6 | Raw event archived to S3 |
| 1.7 | EventBridge event received by IMS SQS queue |
| 1.8 | `inventory.inventory_positions.on_hand` decremented |
| 1.9 | `inventory.stock_alerts` row created (if ATP < reorder_point) |
| 1.10 | `InventoryAlertEvent` published to EventBridge |

**Idempotency test:** Re-send the same `transactionId` → SIS returns `409 Conflict`. No new `sales_events` row. DynamoDB key already exists.

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 21 | `sdk install java 21.0.3-tem` |
| Maven | 3.9.x | `sdk install maven` |
| Docker Desktop | 4.x | https://www.docker.com/products/docker-desktop |
| Node.js | 20.x | `nvm install 20` |
| AWS CLI | v2 | https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html |
| Python | 3.11+ | For test scripts |
| psql | any | PostgreSQL client |

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
```

Health-check all services:
```bash
for port in 8080 8081 8082 8083; do
  curl -s http://localhost:$port/actuator/health | python3 -m json.tool
done
# All should return: {"status":"UP"}
```

### 4. Run the Flow 1 smoke test

```bash
make test-flow1
# Expected: ✅ 5 passed  ❌ 0 failed
```

Or trigger manually:
```bash
python3 scripts/publish-pos-event.py \
  --transaction-id $(python3 -c "import uuid; print(uuid.uuid4())") \
  --direct-api http://localhost:8080
```

### 5. Stop

```bash
make local-down    # stop containers, keep data volumes
make local-clean   # stop containers, destroy volumes (clean slate)
```

---

## Port Assignments (local mode)

| Service | Port |
|---------|------|
| SIS — Sales Ingestion | 8080 |
| IMS — Inventory Management | 8081 |
| RE — Replenishment Engine | 8082 |
| ARS — Analytics & Reporting | 8083 |
| PostgreSQL | 5432 |
| LocalStack (all AWS services) | 4566 |
| Store Manager MFE | 5173 |
| SC Planner MFE | 5174 |
| Executive MFE | 5175 |

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

---

## Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Services | Java + Spring Boot | 21 / 3.3.x |
| Build | Maven | 3.9.x |
| DB access | Spring Data JDBC | 3.3.x |
| Schema migrations | Flyway | 10.x |
| IaC | AWS CDK TypeScript | 2.x |
| MFE | React + TypeScript | 18 / 5.x |
| MFE styling | Tailwind CSS | 3.x |
| MFE charts | Recharts | 2.x |
| MFE auth | @aws-amplify/auth | 6.x |
| Lambda | Java | 21 |
| Local AWS | LocalStack | 3.x |
| Local DB | PostgreSQL Docker | 15 |

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

| Schema | Owner |
|--------|-------|
| `sales` | SIS |
| `inventory` | IMS |
| `replenishment` | RE |
| `forecasting` | DFS (stub) |
| `supplier` | SUP (stub) |
| `promotions` | PPS (stub) |

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
│   └── ars/                    ← Analytics & Reporting Service
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

| Mode | Profile | AWS services | Database | Auth |
|------|---------|-------------|----------|------|
| `local` | `local` | LocalStack :4566 | Postgres Docker :5432 | Mock bypass |
| `aws` | `aws` | Real AWS (us-east-1) | RDS via RDS Proxy | Cognito JWT |

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run   # local mode
SPRING_PROFILES_ACTIVE=aws  mvn spring-boot:run    # aws mode
```

---

## Documentation

| Document | Contents |
|----------|----------|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | All confirmed architecture decisions |
| [`docs/FLOWS.md`](docs/FLOWS.md) | Flow specifications + observable evidence checklists |
| [`docs/API_CONTRACTS.md`](docs/API_CONTRACTS.md) | REST endpoints, request/response shapes, EventBridge events |
| [`docs/SCHEMAS.md`](docs/SCHEMAS.md) | All 6 RDS schemas + DynamoDB table |
| [`docs/SERVICE_SPECS.md`](docs/SERVICE_SPECS.md) | Per-service hexagonal package structure + code patterns |
| [`docs/CDK_SPEC.md`](docs/CDK_SPEC.md) | CDK TypeScript stack specifications |
| [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md) | Local development guide (full detail) |
| [`docs/BUILD_SEQUENCE.md`](docs/BUILD_SEQUENCE.md) | Exact commands for local and AWS build/deploy |
| [`docs/SEED_DATA.md`](docs/SEED_DATA.md) | Reference data, test users, seed SQL |
| [`docs/MFE_SPECS.md`](docs/MFE_SPECS.md) | React MFE components, API calls, auth library |
| [`docs/DEVELOPER_GUIDE.md`](docs/DEVELOPER_GUIDE.md) | Developer onboarding, daily workflow, debugging |

---

## Flows Roadmap

| Flow | Description | Status |
|------|-------------|--------|
| **1** | POS event → SIS → RDS → IMS → stock alert → EventBridge | ✅ Implemented |
| 2 | Inventory alert → RE auto-approve → RDS state transition | 🔲 Specified |
| 3 | SC Planner MFE → RE approve/reject → RDS → EventBridge | 🔲 Specified |
| 4 | ARS → Store Manager Dashboard MFE | 🔲 Specified |
| 8 | Executive Dashboard — MAPE trend + forecast accuracy | 🔲 Specified |
| 9 | SC Planner — supplier performance scorecard | 🔲 Specified |
