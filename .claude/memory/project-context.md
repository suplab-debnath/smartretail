# SmartRetail — Persistent Project Context

## What This Project Is

SmartRetail is a **demand forecasting and supply chain platform** prototype. It processes POS events
from retail stores, manages inventory levels, generates purchase orders via a rules-based replenishment
engine, and presents analytics to store managers, SC planners, and executives via React MFEs.

Primary region: **AWS us-east-1**. Deployment: **ECS Fargate**. DB: **Amazon RDS PostgreSQL 15**.

---

## Service Inventory

| Service | Port (local) | Schema owned | Status |
|---|---|---|---|
| SIS — Sales Ingestion Service | 8080 | `sales` | Implemented |
| IMS — Inventory Management Service | 8081 | `inventory` | Implemented |
| RE — Replenishment Engine | 8082 | `replenishment` | Implemented |
| ARS — Analytics & Reporting Service | 8083 | none (read-only) | Implemented |
| DFS — Demand Forecasting Service | 8084 | `forecasting` | Implemented |
| SUP — Supplier Integration Service | 8085 | `supplier` | Implemented |
| PPS — Pricing & Promotions Service | 8086 | `promotions` | Stub only |

## MFE Inventory

| MFE | Port (local) | Primary services | Users |
|---|---|---|---|
| store-manager | 5173 | ARS, IMS | STORE_MANAGER |
| sc-planner | 5174 | RE, ARS, DFS, SUP | SC_PLANNER |
| executive | 5175 | ARS, DFS | ADMIN |
| supplier | 5177 | SUP | SUPPLIER_ADMIN |
| demo-control (tools/demo) | 5176 / 3099 | all | internal demo use |

---

## Flow Implementation Status

| Flow | Description | Status |
|---|---|---|
| 1 | POS event → Firehose → SIS → IMS → stock alert → EventBridge | Implemented |
| 2 | Inventory alert → RE auto-approve → RDS state transition | Implemented |
| 3 | SC Planner MFE → RE approve/reject → RDS → EventBridge | Implemented |
| 4 | ARS → Store Manager Dashboard MFE | Implemented |
| 8 | Executive Dashboard (seed data) | Implemented |
| 9 | SC Planner Console (seed data + write path) | Implemented |

---

## Environments

| Env | CDK Stack Prefix | AWS Services | DB |
|---|---|---|---|
| local | n/a | LocalStack :4566 | Docker Postgres :5432 |
| demo | `Min-*` | SQS (no Firehose), default VPC, ARM64 | RDS single-AZ t4g.medium |
| dev | `Dev-*` | Firehose, 2-AZ VPC, RDS Proxy, CloudFront | RDS single-AZ t4g.small |
| prod | `Prod-*` | Firehose, 3-AZ VPC, RDS Proxy, CloudFront | RDS Multi-AZ r6g.large |

AWS resource naming pattern: `smartretail-{resource}-{env}` (e.g., `smartretail-ims-sales-dev`)

---

## Authentication Roles (Cognito)

| Role | MFE access | Key permissions |
|---|---|---|
| STORE_MANAGER | store-manager (5173) | Read inventory alerts, view dashboard for own DC |
| SC_PLANNER | sc-planner (5174) | Approve/reject POs, view forecasts, view exception queue |
| SUPPLIER_ADMIN | supplier (5177) | View own orders, update delivery status |
| ADMIN | all MFEs | All permissions |

In **local mode**, JWT validation is bypassed by a mock security filter. In **aws mode**, Cognito JWT
is validated at both API Gateway and the Spring Boot security filter chain.

---

## Key AWS Resource Names (per environment — substitute `{env}`)

| Resource | Name pattern |
|---|---|
| EventBridge bus | `smartretail-events-{env}` |
| SQS — IMS sales queue | `smartretail-ims-sales-{env}` |
| SQS — RE alert queue (FIFO) | `smartretail-re-alert-{env}.fifo` |
| SQS — ARS updates queue | `smartretail-ars-updates-{env}` |
| S3 — raw POS event archive | `smartretail-events-{env}` |
| S3 — SageMaker artefacts | `smartretail-sagemaker-{env}` |
| Firehose stream | `smartretail-ingest-{env}` |
| ECS cluster | `smartretail-{env}` |
| RDS instance | `smartretail-{env}` |
| Secrets Manager — DB creds | `/smartretail/{env}/db/credentials` |
| Secrets Manager — Firehose key | `/smartretail/{env}/firehose/ingest-access-key` |

---

## DB Schema Ownership (no cross-schema SQL joins)

| Schema | Owner service | Key tables |
|---|---|---|
| `sales` | SIS | `sales_events`, `idempotency_keys` |
| `inventory` | IMS | `inventory_positions`, `stock_alerts` |
| `replenishment` | RE | `purchase_orders`, `replenishment_rules` |
| `forecasting` | DFS | `forecast_runs`, `demand_forecasts` |
| `supplier` | SUP | `suppliers`, `supplier_orders`, `supplier_performance` |
| `promotions` | PPS | `promotions`, `promotion_skus` |
