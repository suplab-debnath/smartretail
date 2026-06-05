# SmartRetail — Project Agent Guide

SmartRetail is a demand forecasting and supply chain platform built on AWS. This file provides
project context for AI agents — Claude Code, GitHub Copilot CLI, and other third-party tools.
Read this first before reading any other file in the repository.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21 · Spring Boot 3.3 · Hexagonal Architecture · Spring Data JDBC |
| API | OpenAPI 3.1 · Contract-first (openapi-generator-maven-plugin) |
| Infrastructure | AWS CDK TypeScript v2 · ECS Fargate · RDS PostgreSQL 15 (via RDS Proxy) |
| Messaging | AWS SQS (Standard + FIFO) · EventBridge · Kinesis Firehose |
| Frontend | React 18 · TypeScript 5 · Tailwind CSS 3 · Vite · Module Federation |
| Database | PostgreSQL 15 · Flyway 10 — 6 schemas, one per service |
| ML | AWS SageMaker (DeepAR) · Lambda (trigger + batch post-processor) |
| Auth | AWS Cognito · JWT validated at API Gateway AND service layer |
| Local dev | Docker Compose · LocalStack 3 · GNU Make |

---

## Business Flows

```
Flow 1: POS Event   → SIS → IMS → stock_alert (CRITICAL/HIGH/MEDIUM)
Flow 2: Low stock   → RE  → auto-approve PO   (value < auto_approve_threshold)
Flow 3: High-value  → RE  → PENDING_APPROVAL  → SC Planner approves/rejects in MFE
Flow 4: Store Mgr   → ARS → dashboard         (own DC data only — dcId scoped)
Flow 8: Executive   → ARS → MAPE / fulfilment / OTD / stockout SKUs
Flow 9: SC Planner  → RE + DFS + SUP → exception queue + forecast bands + supplier scorecard
```

---

## Service Map

| Service | Port | Schema | Primary responsibility |
|---|---|---|---|
| SIS | 8080 | `sales` | POS event ingestion; idempotency via `idempotency_keys` |
| IMS | 8081 | `inventory` | Stock management; publishes low-stock alerts |
| RE  | 8082 | `replenishment` | PO lifecycle state machine; approve/reject |
| ARS | 8083 | — (reads all) | Analytics aggregation; read-only; no writes |
| DFS | 8084 | `forecasting` | Demand forecast storage and retrieval |
| SUP | 8085 | `supplier` | Supplier performance and order tracking |
| PPS | 8086 | `promotions` | Promotion planning and activation |

---

## Repository Layout

```
smartretail/
├── backend/
│   ├── services/          # 7 Spring Boot services (hexagonal ports-and-adapters)
│   ├── migrations/        # Flyway V1–V7 SQL migrations (immutable once applied)
│   └── adapters/          # Lambda handlers: ml-trigger, batch-post-processor
├── mfe/                   # 4 React MFEs: store-manager, sc-planner, executive, supplier
├── environments/          # CDK TypeScript stacks: demo (Min-*), dev (Dev-*), prod (Prod-*)
├── docs/                  # Architecture docs, service specs, schemas, flow specs
├── scripts/               # Shell scripts: LocalStack init, deploy helpers, hook scripts
│   └── hooks/             # Hook scripts called by .github/hooks/*.json
├── infra/                 # Shared CDK constructs
└── .github/
    ├── copilot-instructions.md   # Global Copilot instructions (always-on)
    ├── agents/                   # 17 custom Copilot agents (persona specialists)
    ├── instructions/             # 7 path-scoped instruction files (*.instructions.md)
    ├── prompts/                  # 10 reusable task/workflow prompt templates
    └── hooks/                    # Lifecycle hook definitions (*.json)
```

---

## Critical Non-Negotiables

These rules are enforced by ArchUnit — CI fails on any violation.

1. **No AWS imports in domain** — `software.amazon.*` is forbidden in `domain/` and `port/` packages
2. **No cross-schema SQL JOINs** — ARS merges data in Java via separate queries, never in SQL
3. **No cross-service schema writes** — each schema has exactly one owning service
4. **RDS via Proxy only** — JDBC URL must use `RDS_PROXY_ENDPOINT` in `aws` profile
5. **Contract-first APIs** — edit the OpenAPI YAML first, then `mvn generate-sources`; never hand-write DTOs
6. **Optimistic locking** — every `UPDATE purchase_orders` must check `AND version = :expectedVersion`
7. **State machine in RDS** — PO workflow status lives in the database; no Step Functions
8. **JWT at both layers** — API Gateway JWT authorizer + Spring Security filter chain (aws profile)

---

## PO Workflow Status Transitions

```
DRAFT → PENDING_APPROVAL → APPROVED → DISPATCHED → ACKNOWLEDGED → SHIPPED
                        ↘ REJECTED   (human planner decision — mandatory reason field)
                        ↘ EXPIRED    (system-level)
                        ↘ CANCELLED  (system-level only — NEVER for planner rejection)
```

Approving from any state other than `PENDING_APPROVAL` returns HTTP 409 (`INVALID_STATUS_TRANSITION`).

---

## Local Development Quick Start

```bash
make local-up          # Start postgres:15 + localstack:3 containers
make local-migrate     # Apply Flyway V1–V6 (schema DDL)
make local-seed        # Apply V7 (reference + seed data)
make local-sis         # Start SIS on local profile (port 8080)
make local-ims         # Start IMS on local profile (port 8081)
make test-flow1        # Smoke test: POS event → inventory decrement → alert
make test-flow2        # Smoke test: alert → auto-approved PO
make test-flow3        # Smoke test: high-value PO → PENDING_APPROVAL
make build-all         # Maven build all services + MFE npm builds
make coverage          # JaCoCo aggregate + Vitest coverage merge
```

---

## Available Copilot Agents (`.github/agents/`)

Select from the Copilot agent picker in your IDE, on GitHub, or via Copilot CLI.

### Core Development

| Agent name | File | Purpose |
|---|---|---|
| Java Developer | `java-developer.md` | Spring Boot 3.3 · hexagonal · contract-first · ArchUnit |
| React Developer | `react-developer.md` | React 18 · TypeScript 5 · Tailwind · MFEs · Amplify v6 |
| Tester | `tester.md` | JUnit 5 · Mockito · Testcontainers · ArchUnit · Vitest + RTL |
| Database Administrator | `db-admin.md` | Flyway migrations · schema design · PostgreSQL index strategy |
| ML Engineer | `ml-engineer.md` | DFS service · SageMaker DeepAR · Lambda · forecast pipeline |

### Architecture & Governance

| Agent name | File | Purpose |
|---|---|---|
| Enterprise Architect | `enterprise-architect.md` | Bounded contexts · event contracts · ADRs · versioning policy |
| AWS Architect | `aws-architect.md` | CDK stacks · ECS · IAM · VPC topology · RDS Proxy |
| API Contract Guardian | `api-contract-guardian.md` | Breaking change detection · OpenAPI versioning · idempotency |
| Security Auditor | `security-auditor.md` | JWT · OWASP Top 10 · IAM audit · PII masking · CORS |
| Code Reviewer | `code-reviewer.md` | 8 architecture rules · 13 forbidden patterns · BLOCK/SUGGEST/NIT |

### Operations & Delivery

| Agent name | File | Purpose |
|---|---|---|
| Ops Engineer | `ops-engineer.md` | Docker Compose · LocalStack · Makefile · troubleshooting |
| AWS Deployer | `aws-deployer.md` | Guarded CDK deploys — always shows plan and waits for YES |
| CI Engineer | `ci-engineer.md` | CodePipeline status · CodeBuild log diagnosis · retry guidance |
| Performance Engineer | `performance-engineer.md` | Micrometer metrics · HikariCP · JVM flags · CloudWatch alarms |
| AWS FinOps Analyst | `aws-finops-analyst.md` | Cost estimates · right-sizing · Savings Plans · tagging |

### Domain & Tracking

| Agent name | File | Purpose |
|---|---|---|
| Retail SME | `retail-sme.md` | Supply chain domain rules · KPI thresholds · seed data realism |
| Project Tracker | `project-tracker.md` | Flow status · doc freshness · coverage gaps · .github consistency |

---

## Available Prompt Files (`.github/prompts/`)

Invoke in Copilot Chat with `/` followed by the prompt name.

| Prompt | Purpose |
|---|---|
| `/task-new-service` | Scaffold a Spring Boot service with full hexagonal structure |
| `/task-new-endpoint` | Add a REST endpoint: YAML → `mvn generate-sources` → implement |
| `/task-new-migration` | Author the next correct Flyway versioned migration |
| `/task-new-mfe-component` | Create a typed React component with tests and accessibility |
| `/task-generate-tests` | Generate unit, IT, ArchUnit, and MFE tests comprehensively |
| `/task-debug-flow` | Diagnose a failing flow via logs and DB state queries |
| `/workflow-implement-flow` | End-to-end: schema → services → MFE → smoke test |
| `/workflow-review-pr` | PR review with architecture + security + test coverage checklist |
| `/workflow-deploy-demo` | Full AWS demo deployment: CDK → migrations → services → MFEs |
| `/workflow-onboard-developer` | Guided onboarding walkthrough for new team members |

---

## Key Documentation

| File | Purpose |
|---|---|
| `docs/ARCHITECTURE.md` | Architecture decisions and confirmed constraints |
| `docs/SERVICE_SPECS.md` | Hexagonal package structure per service |
| `docs/SCHEMAS.md` | All 6 PostgreSQL schemas with column definitions |
| `docs/FLOWS.md` | 6 business flow specifications and acceptance criteria |
| `docs/EVENT_ASYNC_SPEC.md` | Event envelope schemas and EventBridge routing rules |
| `docs/API_CONTRACTS.md` | REST endpoint inventory across all services |
| `docs/CDK_SPEC.md` | CDK stack specifications per environment |
| `docs/LOCAL_DEV.md` | Local development setup and troubleshooting |
| `docs/SEED_DATA.md` | Reference data guide and realistic value ranges |
| `docs/BUILD_SEQUENCE.md` | AWS build and deploy sequence |
| `docs/MFE_SPECS.md` | Component specifications per MFE |

---

## Choosing the Right Agent

| Situation | Agent |
|---|---|
| Writing or modifying Java code | Java Developer |
| Designing DB tables / authoring Flyway | Database Administrator |
| AWS infrastructure design or review | AWS Architect |
| Deploying to AWS (any environment) | AWS Deployer |
| Reviewing a pull request | Code Reviewer |
| Security concern before deploy | Security Auditor |
| Changing an OpenAPI YAML file | API Contract Guardian |
| Performance / latency investigation | Performance Engineer |
| AWS CodePipeline / CI failure | CI Engineer |
| Domain rule or KPI question | Retail SME |
| AWS cost or right-sizing question | AWS FinOps Analyst |
| "What's done?" / project health check | Project Tracker |
| Bounded context or event schema design | Enterprise Architect |
| Local environment not starting | Ops Engineer |
| ML forecast pipeline question | ML Engineer |
| React component or MFE work | React Developer |
| Writing or fixing tests | Tester |
