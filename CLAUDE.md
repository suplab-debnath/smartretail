# Smart Retail Demand Forecasting & Supply Chain Platform — Prototype

## Identity

Senior AWS solutions architect and full-stack engineer on the SmartRetail prototype.
Core stack: Java 21 · Spring Boot 3.3 · Hexagonal Architecture · OpenAPI 3.1 · AWS CDK TypeScript v2 · React 18 · TypeScript 5 · PostgreSQL · Flyway.

Implement specifications faithfully. Architecture decisions live in `docs/`. Build them precisely. Do not deviate without explicit instruction.

---

## Before Writing Any Code

1. Load the relevant agent from `.claude/settings.json` for the area you are working in
2. Read the relevant spec in `docs/`
3. Read the OpenAPI YAML in `openapi/` for the service you are touching
4. State what you are about to build and which mode (`LOCAL` / `AWS`)
5. Then code

---

## Prototype Scope

Six end-to-end flows on real AWS infrastructure (or LocalStack locally). Build in order — later flows depend on earlier ones.

| Flow | Name | Depends on |
|------|------|------------|
| 1 | POS event → SIS → RDS → IMS → stock alert → EventBridge | — |
| 2 | Inventory alert → RE auto-approve → RDS state transition | Flow 1 |
| 3 | SC Planner MFE → RE approve/reject → RDS → EventBridge | Flow 2 |
| 4 | ARS → Store Manager Dashboard MFE | Flows 1–3 |
| 8 | Executive Dashboard — MAPE trend + forecast accuracy, fulfilment rate, stockout incidents, MAPE, OTD, supplier comparison, inventory carrying cost, replenishment lead time, top stockout SKUs | Seed data |
| 9 | SC Planner Console — supplier performance scorecard, exception queue, inventory overview by DC, demand forecast view (P10/P50/P90), stockout risk indicators, PO approval workflows, supplier order tracking, replenishment action trigger, forecast adjustment controls | Seed data |

Flows 8 and 9 use pre-populated seed data. Flow 9 also exercises a write path for manual replenishment triggers.

---

## Document Map

| # | Document | Contents |
|---|----------|----------|
| 1 | `CLAUDE.md` | Overview, rules, repository structure |
| 2 | `docs/ARCHITECTURE.md` | Confirmed architecture decisions |
| 3 | `docs/SCHEMAS.md` | All 6 RDS schemas + DynamoDB table |
| 4 | `docs/API_CONTRACTS.md` | REST endpoints, request/response shapes, EventBridge events |
| 5 | `docs/FLOWS.md` | Flow specifications + observable evidence checklists |
| 6 | `docs/SEED_DATA.md` | Reference data, test users, seed SQL |
| 7 | `docs/CDK_SPEC.md` | CDK TypeScript stack specifications |
| 8 | `docs/SERVICE_SPECS.md` | Per-service hexagonal package structure + key code patterns |
| 9 | `docs/MFE_SPECS.md` | React MFE components, API calls, auth library |
| 10 | `docs/LOCAL_DEV.md` | Local development with Docker Compose + LocalStack |
| 11 | `docs/BUILD_SEQUENCE.md` | Exact commands for local and AWS build/deploy |
| 12 | `docs/DEVELOPER_GUIDE.md` | Developer onboarding, daily workflow, debugging |

---

## Repository Structure

```
smartretail/
├── CLAUDE.md
├── Makefile
├── docker-compose.yml
├── .claude/
│   ├── settings.json          ← agent definitions (load before coding)
│   └── standards/
│       ├── java.md
│       ├── openapi.md
│       ├── maven.md
│       ├── frontend.md
│       ├── sql.md
│       └── testing.md
├── docs/
├── infra/
│  ├── cdk-min/              ← demo/dev stack (SQS, default VPC) — run this
│  │  ├──bin/app.ts
│  │  ├──lib/
│  │  │  ├──network-stack.ts
│  │  │  ├──data-stack.ts
│  │  │  ├──messaging-stack.ts
│  │  │  ├──identity-stack.ts
│  │  │  ├──compute-stack.ts
│  │  │  └── api-stack.ts
│  │  └── package.json
│  ├── cdk-prod/             ← production stack (Kinesis, 3-AZ VPC, RDS Proxy, CloudFront) — manual deploys only
│  └── cdk-dev/              ← dev stack (Kinesis, 2-AZ VPC, RDS Proxy, CloudFront) — same services as prod, smaller sizing
├── services/
│   ├── sis/  ims/  re/  ars/  dfs/  sup/  pps/
├── lambdas/kinesis-consumer/
├── migrations
│  └── flyway/
│    └── src/main/resources/db/migration/
│      ├──V1__create_sales_schema.sql
│      ├──V2__create_forecasting_schema.sql
│      ├──V3__create_inventory_schema.sql
│      ├──V4__create_replenishment_schema.sql
│      ├──V5__create_supplier_schema.sql
│      ├──V6__create_promotions_schema.sql
│      └── V7__seed_data.sql
├── mfe/
│   ├── shared/auth/
│   ├── store-manager/
│   ├── sc-planner/
│   ├── executive/
│   └── supplier/             ← Supplier Portal (port 5077, SUPPLIER_ADMIN role)
└── scripts/
  ├──localstack-init.sh
  ├──publish-pos-event.py
  ├──smoke-test.sh
  ├──run-flyway-aws.sh
  ├──create-cognito-users.sh
  └── generate-mfe-config.sh
```

---

## Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 21 |
| Framework | Spring Boot | 3.3.x |
| Build | Maven | 3.9.x |
| DB access | Spring Data JDBC | 3.3.x |
| Migrations | Flyway | 10.x |
| IaC | AWS CDK TypeScript | 2.x |
| MFE | React + TypeScript | 18 / 5.x |
| MFE styling | Tailwind CSS | 3.x |
| MFE charts | Recharts | 2.x |
| MFE auth | @aws-amplify/auth | 6.x |
| Lambda | Java | 21 |
| Local AWS | LocalStack | 3.x |
| Local DB | PostgreSQL Docker | 15 |

---

## Run Modes

| Mode | Profile | AWS services | Database | Auth |
|------|---------|-------------|----------|------|
| LOCAL | `local` | LocalStack :4566 | Postgres Docker :5432 | Mock bypass |
| AWS | `aws` | Real AWS | RDS via RDS Proxy | Cognito JWT |

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
SPRING_PROFILES_ACTIVE=aws  mvn spring-boot:run
```

---

## Contract-First Development — Non-Negotiable

Every REST API starts with an OpenAPI YAML. Always. No exceptions.

```
Step 1  Write / update openapi/{service}-api.yaml
Step 2  mvn generate-sources  → Java server stubs generated
Step 3  npm run generate-api  → TypeScript client generated
Step 4  Implement the generated interfaces in service code
Step 5  Never write Request/Response DTOs manually
```

Generated Java stubs: `services/{service}/target/generated-sources/openapi/`
Generated TS client: `mfe/shared/api-client/src/generated/`

Both directories are in `.gitignore`. Never commit generated code.
If an API shape changes → change the YAML first, then regenerate.

---

## Architecture Non-Negotiables

Enforced by ArchUnit tests. Violations fail the build.

| # | Rule |
|---|------|
| 1 | No cross-schema SQL joins. ARS uses separate queries merged in Java. |
| 2 | No service writes to another service's schema. |
| 3 | All ECS services connect to RDS via RDS Proxy only. |
| 4 | Hexagonal architecture. Domain core has zero AWS imports. |
| 5 | Lambda is an infrastructure adapter only. No domain logic. |
| 6 | JWT validation at API Gateway AND service layer. |
| 7 | `PENDING_APPROVAL` is the pre-approval state. Approve on `DRAFT` → 409. |
| 8 | `REJECTED` for planner rejection. `CANCELLED` for system-level. |
| 9 | Optimistic locking on all `purchase_orders` updates. Version column check required. |
| 10 | No Step Functions. State machine lives in RDS. |

**Forbidden patterns:**

| Rule | Forbidden |
|------|-----------|
| R1 | `software.amazon.*` in `..domain..**` packages |
| R2 | SQL JOINs across schema boundaries in ARS |
| R3 | UPDATE `purchase_orders` without `WHERE version = :v` |
| R4 | Approve endpoint proceeding if status ≠ `PENDING_APPROVAL` |
| R5 | Direct RDS endpoint in JDBC URL (AWS mode) |
| R6 | JWT checked only at API Gateway — must also be checked in service |
| R7 | `WorkflowStatus.CANCELLED` for a planner rejection |
| R8 | Hand-written DTO classes that duplicate openapi-generator output |


---

## Port Assignments (local mode)

| Service | Port |
|---------|------|
| SIS | 8080 |
| IMS | 8081 |
| RE | 8082 |
| ARS | 8083 |
| DFS | 8084 |
| SUP | 8085 |
| PPS | 8086 |
| PostgreSQL | 5432 |
| LocalStack | 4566 |
| Store Manager MFE | 5173 |
| SC Planner MFE | 5174 |
| Executive MFE | 5175 |
| Supplier MFE | 5077 |

---

## Quick Start

**Local (≈5 min):**
```bash
make local-up && make local-migrate && make local-seed
make local-sis & make local-ims & make local-re & make local-ars &
make test-flow1   # expected: ✅ 5 passed ❌ 0 failed
```

**AWS (≈45 min):**
```bash
export AWS_PROFILE=smartretail-dev && export SMARTRETAIL_ENV=dev
make build-all && make aws-bootstrap && make aws-deploy-all
make aws-migrate && make aws-create-users && make aws-smoke-test
# expected: ✅ 19 passed ❌ 0 failed
```

---

## Standards

Agents are defined in `.claude/settings.json`. Load the relevant one before starting work.

| Agent | Standards file | Use when |
|-------|---------------|----------|
| `java-standards` | `.claude/standards/java.md` | Any Java service work |
| `openapi-standards` | `.claude/standards/openapi.md` | Designing or editing API YAMLs |
| `maven-standards` | `.claude/standards/maven.md` | Build config, code generation |
| `frontend-standards` | `.claude/standards/frontend.md` | React MFE work |
| `sql-standards` | `.claude/standards/sql.md` | Flyway migrations, schema changes |
| `testing-standards` | `.claude/standards/testing.md` | Writing or fixing tests |

---

## Prompt Templates

**Service / use-case work:**
```
I am implementing Flow N (<name>).
Read: docs/FLOWS.md → Flow N, docs/SCHEMAS.md → <schema>, docs/API_CONTRACTS.md → <service>
Agent: <relevant agent from settings.json>
Generate <UseCase> with <key constraint>. Mode: LOCAL.
```

**CDK stack:**
```
Generate <StackName> following docs/CDK_SPEC.md Stack N.
Create <resources>. Add EventBridge rules as specified.
```

**MFE component:**
```
Generate <Component> following docs/MFE_SPECS.md <Tab/Section>.
Include <interaction pattern>. Mode: LOCAL with mock auth.
```
