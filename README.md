# SmartRetail — Demand Forecasting & Supply Chain Platform

An event-driven retail supply chain prototype built on AWS. Covers a complete
POS-to-replenishment pipeline: sales ingestion, inventory management, automated
replenishment, analytics dashboards, and demand forecasting — all on ECS Fargate
with EventBridge, SQS, RDS PostgreSQL, and CloudFront.

> **Status:** Flows 1, 2, 3, 4, 8, and 9 fully implemented and verified.

---

## Architecture

Seven Spring Boot services in hexagonal architecture, each owning one RDS schema.
No cross-schema SQL joins — ARS aggregates across schemas in Java.

| Service | Role | Schema |
|---------|------|--------|
| SIS | Sales Ingestion — receives POS events via Firehose HTTP endpoint | `sales` |
| IMS | Inventory Management — tracks stock, raises alerts | `inventory` |
| RE  | Replenishment Engine — auto-approves or queues POs for planner review | `replenishment` |
| ARS | Analytics & Reporting — aggregates KPIs across schemas for dashboards | — |
| DFS | Demand Forecasting — consumes SageMaker P10/P50/P90 output | `forecasting` |
| SUP | Supplier Service — tracks supplier POs and shipment updates | `supplier` |
| PPS | Pricing & Promotions — promotional uplift signals (stub) | `promotions` |

Four React MFEs: Store Manager Dashboard, SC Planner Console, Executive Dashboard, Supplier Portal.

---

## Technology Stack

| Layer       | Technology              | Version  |
|-------------|-------------------------|----------|
| Services    | Java + Spring Boot      | 21 / 3.3 |
| Build       | Maven                   | 3.9.x    |
| DB access   | Spring Data JDBC        | 3.3.x    |
| Migrations  | Flyway                  | 10.x     |
| IaC         | AWS CDK TypeScript      | 2.x      |
| MFE         | React + TypeScript      | 18 / 5.x |
| MFE styling | Tailwind CSS            | 3.x      |
| MFE charts  | Recharts                | 2.x      |
| MFE auth    | @aws-amplify/auth       | 6.x      |
| Local AWS   | LocalStack              | 3.x      |
| Database    | PostgreSQL              | 15       |

---

## Quick Start (local, ≈5 minutes)

```bash
make local-up && make local-migrate && make local-seed
make local-sis & make local-ims & make local-re & make local-ars &
make test-flow1   # ✅ 5 passed  ❌ 0 failed
```

For a full setup walkthrough, port assignments, API examples, and AWS deployment
see **[docs/BUILD_SEQUENCE.md](docs/BUILD_SEQUENCE.md)**.

---

## Flows

| Flow | Description | Depends on |
|------|-------------|------------|
| 1 | POS event → Firehose → SIS → RDS → IMS → stock alert → EventBridge | — |
| 2 | Inventory alert → RE auto-approve → RDS state transition | Flow 1 |
| 3 | SC Planner MFE → RE approve/reject → RDS → EventBridge | Flow 2 |
| 4 | ARS → Store Manager Dashboard MFE | Flows 1–3 |
| 8 | Executive Dashboard — MAPE, fulfilment rate, OTD, supplier scorecard, carrying cost | Seed data |
| 9 | SC Planner Console — exception queue, forecast view, PO approvals, replenishment trigger | Seed data |

Flow specifications and observable evidence checklists: **[docs/FLOWS.md](docs/FLOWS.md)**.

---

## Documentation

| Document | Contents |
|----------|----------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Architecture decisions and constraints |
| [docs/BUILD_SEQUENCE.md](docs/BUILD_SEQUENCE.md) | Prerequisites, local setup, AWS deployment, Makefile reference |
| [docs/FLOWS.md](docs/FLOWS.md) | Flow specifications + observable evidence checklists |
| [docs/API_CONTRACTS.md](docs/API_CONTRACTS.md) | REST endpoints, request/response shapes, EventBridge events |
| [docs/SCHEMAS.md](docs/SCHEMAS.md) | All 6 RDS schemas |
| [docs/SERVICE_SPECS.md](docs/SERVICE_SPECS.md) | Hexagonal package structure + key code patterns |
| [docs/LOCAL_DEV.md](docs/LOCAL_DEV.md) | Local development in depth |
| [docs/CDK_SPEC.md](docs/CDK_SPEC.md) | CDK TypeScript stack specifications |
| [docs/EVENT_ASYNC_SPEC.md](docs/EVENT_ASYNC_SPEC.md) | Async event contracts, SQS config, idempotency, DLQ policy |
| [docs/MFE_SPECS.md](docs/MFE_SPECS.md) | React MFE components, API calls, auth |
| [docs/SEED_DATA.md](docs/SEED_DATA.md) | Reference data and seed migrations (V7–V9) |
| [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md) | Onboarding, daily workflow, debugging |
| [tools/demo/README.md](tools/demo/README.md) | Demo Control Center — running and presenting the demo |
| [CLAUDE.md](CLAUDE.md) | AI coding assistant instructions and project rules |
