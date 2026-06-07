# Smart Retail Demand Forecasting & Supply Chain Platform — Prototype

## Identity

Senior AWS solutions architect and full-stack engineer on the SmartRetail prototype.
Core stack: Java 21 · Spring Boot 3.3 · Hexagonal Architecture · OpenAPI 3.1 · AWS CDK TypeScript v2 · React 18 · TypeScript 5 · PostgreSQL · Flyway.

Implement specifications faithfully. Architecture decisions live in `docs/`. Build them precisely. Do not deviate without explicit instruction.

---

## Key Principles (Read First)

1. **Golden Rule**: When in doubt, follow the strictest interpretation of the architecture rules in `docs/ARCHITECTURE.md`.
2. **Never improvise APIs** — the OpenAPI YAML is the single source of truth. Change the YAML first, always.
3. **Idempotency is non-negotiable** — every async flow must use the `idempotency_keys` table in the sales schema.
4. **Observability**: every major operation must produce structured JSON logs with a `correlationId` field.
5. **Error handling**: use `ProblemDetail` (RFC 7807) for all error responses, everywhere.
6. **No half-finished code** — do not leave `TODO` stubs; implement fully or raise a question.
7. **Record decisions** — all architecture decisions go in `docs/ARCHITECTURE.md` with date and rationale.

---

## Before Writing Any Code

Follow these steps in order. Do not skip any step.

1. **Load the correct agent** for the area you are working in:
   ```bash
   cat .claude/settings.json   # find the agent name, then read its standards file
   cat .claude/standards/java.md          # for any Java service work
   cat .claude/standards/openapi.md       # for any OpenAPI YAML work
   cat .claude/standards/frontend.md      # for any React MFE work
   ```

2. **Read the relevant spec documents in this order**:
   - `docs/ARCHITECTURE.md` — architecture decisions and constraints
   - `docs/FLOWS.md` → the specific Flow section you are implementing
   - `docs/SCHEMAS.md` → the schema(s) your code touches
   - `backend/services/{service}/src/main/resources/{service}-api.yaml` → the API contract

3. **Read the OpenAPI YAML** for the service you are modifying. Understand all endpoints, request/response shapes, and error codes before writing a single line of implementation.

4. **State clearly** what you are about to build before starting:
   > "Implementing Flow N — \<name\>. Mode: LOCAL. Agent: java-standards."

5. **Then code.**

---

## Prototype Scope

Six end-to-end flows on real AWS infrastructure (or LocalStack locally). Build in order — later flows depend on earlier ones.

> **Note on flow numbering**: Flows 5–7 are reserved for future phases. Flows 8 and 9 are dashboard/reporting flows that use pre-populated seed data and can be built independently after Flows 1–4. Flow 9 also exercises a write path for manual replenishment triggers.

| Flow | Name                                                                                                                                                                                                                                                                     | Depends on |
|------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------|
| 1    | POS event → Firehose → SIS → RDS → IMS → stock alert → EventBridge                                                                                                                                                                                                      | —          |
| 2    | Inventory alert → RE auto-approve → RDS state transition                                                                                                                                                                                                                 | Flow 1     |
| 3    | SC Planner MFE → RE approve/reject → RDS → EventBridge                                                                                                                                                                                                                   | Flow 2     |
| 4    | ARS → Store Manager Dashboard MFE                                                                                                                                                                                                                                        | Flows 1–3  |
| 8    | Executive Dashboard — MAPE trend + forecast accuracy, fulfilment rate, stockout incidents, OTD, supplier comparison, inventory carrying cost, replenishment lead time, top stockout SKUs                                                                                 | Seed data  |
| 9    | SC Planner Console — supplier performance scorecard, exception queue, inventory overview by DC, demand forecast view (P10/P50/P90), stockout risk indicators, PO approval workflows, supplier order tracking, replenishment action trigger, forecast adjustment controls | Seed data  |

---

## Document Map

| #   | Document                   | Contents                                                                            |
|-----|----------------------------|-------------------------------------------------------------------------------------|
| 1   | `CLAUDE.md`                | Overview, rules, repository structure                                               |
| 2   | `docs/ARCHITECTURE.md`     | Confirmed architecture decisions                                                    |
| 3   | `docs/SCHEMAS.md`          | All 6 RDS schemas + idempotency_keys table (sales schema)                           |
| 4   | `docs/API_CONTRACTS.md`    | REST endpoints, request/response shapes, EventBridge events                         |
| 5   | `docs/FLOWS.md`            | Flow specifications + observable evidence checklists                                |
| 6   | `docs/SEED_DATA.md`        | Reference data, test users, seed SQL                                                |
| 7   | `docs/CDK_SPEC.md`         | CDK TypeScript stack specifications                                                 |
| 8   | `docs/SERVICE_SPECS.md`    | Per-service hexagonal package structure + key code patterns                         |
| 9   | `docs/MFE_SPECS.md`        | React MFE components, API calls, auth library                                       |
| 10  | `docs/LOCAL_DEV.md`        | Local development with Docker Compose + LocalStack                                  |
| 11  | `docs/BUILD_SEQUENCE.md`   | Exact commands for local and AWS build/deploy                                       |
| 12  | `docs/DEVELOPER_GUIDE.md`  | Developer onboarding, daily workflow, debugging                                     |
| 13  | `docs/EVENT_ASYNC_SPEC.md` | Canonical async contract: event schemas, SQS config, idempotency, ordering, DLQ policy |

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
├── .make/                 ← Makefile includes (vars, local, test, build, aws, demo, coverage)
├── tools/
│   └── demo/
│       ├── server/        ← Demo control server (:3099) — triggers scripts, streams SSE
│       └── ui/            ← Demo Control Center MFE (:5176)
├── environments/
│  ├── local/
│  │  ├── scripts/localstack-init.sh   ← creates all LocalStack resources on startup
│  │  └── README.md
│  ├── demo/               ← demo stack (SQS, default VPC, ARM64) — run this for demos
│  │  ├── infra/           ← CDK stack (Min-* stacks)
│  │  │  ├── bin/app.ts
│  │  │  ├── lib/
│  │  │  │  ├── network-stack.ts
│  │  │  │  ├── data-stack.ts
│  │  │  │  ├── messaging-stack.ts
│  │  │  │  ├── identity-stack.ts
│  │  │  │  ├── compute-stack.ts
│  │  │  │  └── api-stack.ts
│  │  │  └── package.json
│  │  ├── scripts/
│  │  │  ├── deploy-demo.sh
│  │  │  ├── deploy-services-demo.sh
│  │  │  ├── deploy-mfes-demo.sh
│  │  │  ├── run-flyway-aws-demo.sh
│  │  │  └── destroy-infra.sh
│  │  └── README.md
│  ├── dev/                ← dev stack (Firehose, 2-AZ VPC, RDS Proxy, CloudFront)
│  │  ├── infra/           ← CDK stack (Dev-* stacks)
│  │  ├── scripts/
│  │  │  └── deploy-cdk.sh
│  │  └── README.md
│  ├── prod/               ← production stack (Firehose, 3-AZ VPC, Multi-AZ RDS) — manual deploys only
│  │  ├── infra/           ← CDK stack (Prod-* stacks)
│  │  └── README.md
│  └── shared/
│     └── iam-policies/    ← IAM policy documents
├── backend/
│   ├── services/
│   │   ├── sis/  ims/  re/  ars/  dfs/  sup/  pps/
│   │   │   └── src/main/resources/{svc}-api.yaml  ← OpenAPI spec (self-contained, components inlined)
│   ├── adapters/
│   │   └── batch-post-processor/  ← SageMaker S3 output → DFS inbound adapter Lambda
│   │   ← Note: kinesis-consumer/ removed — Firehose delivers directly to SIS via API Gateway
│   ├── migrations/
│   │   └── src/main/resources/db/migration/
│   │       ├── V1__create_sales_schema.sql
│   │       ├── V2__create_forecasting_schema.sql
│   │       ├── V3__create_inventory_schema.sql
│   │       ├── V4__create_replenishment_schema.sql
│   │       ├── V5__create_supplier_schema.sql
│   │       ├── V6__create_promotions_schema.sql
│   │       └── V7__seed_data.sql
│   └── coverage/               ← JaCoCo aggregate report
├── mfe/
│   ├── shared/auth/
│   ├── store-manager/     ← Store Manager Dashboard (:5173) — ARS, IMS
│   ├── sc-planner/        ← SC Planner Console (:5174) — RE, ARS, DFS, SUP
│   ├── executive/         ← Executive Dashboard (:5175) — ARS, DFS
│   └── supplier/          ← Supplier Portal (:5177, SUPPLIER_ADMIN role) — SUP
└── scripts/
  ├── shared/
  │   ├── deploy-services.sh
  │   ├── deploy-mfes.sh
  │   ├── run-flyway-aws.sh
  │   ├── create-cognito-users.sh
  │   ├── smoke-test.sh
  │   └── publish-pos-event.py
  └── ci/
      └── merge-mfe-coverage.sh
```

---

## Technology Stack

| Layer       | Technology         | Version  |
|-------------|--------------------|----------|
| Language    | Java               | 21       |
| Framework   | Spring Boot        | 3.3.x    |
| Build       | Maven              | 3.9.x    |
| DB access   | Spring Data JDBC   | 3.3.x    |
| Migrations  | Flyway             | 10.x     |
| IaC         | AWS CDK TypeScript | 2.x      |
| MFE         | React + TypeScript | 18 / 5.x |
| MFE styling | Tailwind CSS       | 3.x      |
| MFE charts  | Recharts           | 2.x      |
| MFE auth    | @aws-amplify/auth  | 6.x      |
| Lambda      | Java               | 21       |
| Local AWS   | LocalStack         | 3.x      |
| Local DB    | PostgreSQL Docker  | 15       |

---

## Run Modes

| Mode  | Profile | AWS services     | Database              | Auth        |
|-------|---------|------------------|-----------------------|-------------|
| LOCAL | `local` | LocalStack :4566 | Postgres Docker :5432 | Mock bypass |
| AWS   | `aws`   | Real AWS         | RDS via RDS Proxy     | Cognito JWT |

**Switching modes:**

```bash
# Local mode
export SPRING_PROFILES_ACTIVE=local
mvn spring-boot:run

# AWS mode
export SPRING_PROFILES_ACTIVE=aws
mvn spring-boot:run
```

**Known LOCAL vs AWS differences:**

| Behaviour              | LOCAL (LocalStack)                        | AWS                              |
|------------------------|-------------------------------------------|----------------------------------|
| Firehose               | LocalStack Firehose → SIS direct HTTP (`localhost:8080`) | Real Firehose → SIS via API Gateway + VPC Link |
| Auth                   | Mock JWT bypass (`X-Mock-User` header)    | Cognito JWT validation           |
| JDBC URL               | Direct Postgres `localhost:5432`          | RDS Proxy endpoint               |
| EventBridge            | LocalStack EventBridge (no real targets)  | Real EventBridge with SQS targets|

---

## Contract-First Development — Non-Negotiable

Every REST API starts with an OpenAPI YAML. Always. No exceptions.

```
Step 1  Write / update backend/services/{service}/src/main/resources/{service}-api.yaml
Step 2  mvn generate-sources  → Java server stubs generated
Step 3  npm run generate-api  → TypeScript client generated
Step 4  Implement the generated interfaces in service code
Step 5  Never write Request/Response DTOs manually
```

Generated Java stubs: `backend/services/{service}/target/generated-sources/openapi/`
Generated TS client: `mfe/shared/api-client/src/generated/`

Both directories are in `.gitignore`. **Never commit generated code.**
If an API shape changes → change the YAML first, then regenerate.

**API versioning policy**: URL-based versioning (`/v1/`, `/v2/`). Bump the major version in the OpenAPI YAML `info.version` field and the URL prefix whenever a breaking change is made. Non-breaking additions (new optional fields, new endpoints) do not require a version bump.

**Error handling in contracts**: every endpoint must declare `400`, `404`, `409`, and `500` responses in the OpenAPI YAML using the shared `ProblemDetail` schema component.

---

## Architecture Non-Negotiables

Enforced by ArchUnit tests. Violations fail the build.

| #   | Rule                                                                                |
|-----|-------------------------------------------------------------------------------------|
| 1   | No cross-schema SQL joins. ARS uses separate queries merged in Java.                |
| 2   | No service writes to another service's schema.                                      |
| 3   | All ECS services connect to RDS via RDS Proxy only.                                 |
| 4   | Hexagonal architecture. Domain core has zero AWS imports.                           |
| 5   | Lambda is an infrastructure adapter only. No domain logic.                          |
| 6   | JWT validation at API Gateway AND service layer.                                    |
| 7   | `PENDING_APPROVAL` is the pre-approval state. Approve on `DRAFT` → 409.             |
| 8   | `REJECTED` for planner rejection. `CANCELLED` for system-level.                     |
| 9   | Optimistic locking on all `purchase_orders` updates. Version column check required. |
| 10  | No Step Functions. State machine lives in RDS.                                      |

**Forbidden patterns:**

| Rule | Forbidden                                                              |
|------|------------------------------------------------------------------------|
| R1   | `software.amazon.*` in `..domain..**` packages                         |
| R2   | SQL JOINs across schema boundaries in ARS                              |
| R3   | UPDATE `purchase_orders` without `WHERE version = :v`                  |
| R4   | Approve endpoint proceeding if status ≠ `PENDING_APPROVAL`             |
| R5   | Direct RDS endpoint in JDBC URL (AWS mode)                             |
| R6   | JWT checked only at API Gateway — must also be checked in service      |
| R7   | `WorkflowStatus.CANCELLED` for a planner rejection                     |
| R8   | Hand-written DTO classes that duplicate openapi-generator output       |
| R9   | `JpaRepository` or any Spring Data JPA annotation anywhere             |
| R10  | `@Transactional` in domain services (only in application service layer)|
| R11  | `RestTemplate` or `WebClient` in domain-layer code                     |
| R12  | Hardcoded AWS region or account ID in any source file                  |
| R13  | Business logic inside a Lambda handler                                 |

---

## Testing Requirements

Read `.claude/standards/testing.md` before writing any test.

| Layer              | Approach                                                                 | Minimum coverage |
|--------------------|--------------------------------------------------------------------------|-----------------|
| Domain unit tests  | Plain JUnit 5, no Spring context, no mocks of domain objects             | 90% line        |
| Application tests  | Mockito for port interfaces, no Spring context                           | 85% line        |
| Integration tests  | `@SpringBootTest` + Testcontainers (PostgreSQL + LocalStack)             | Key flows only  |
| Flow tests         | `make test-flow1` … `make test-flow4` must all pass before merging       | N/A             |
| MFE unit tests     | Vitest + React Testing Library, no real API calls                        | 80% line        |

**Rules:**
- Do not mock domain objects in unit tests — test real domain logic.
- Every new use case needs at least one happy-path and one error-path integration test.
- All changed lines must maintain ≥ 85% coverage in the JaCoCo aggregate report (`backend/coverage/`).
- Run `make test-all` locally before pushing; CI will reject PRs that break coverage thresholds.

---

## Database Migrations

Migrations live in `backend/migrations/src/main/resources/db/migration/`.

**Rules:**
- File naming: `V{N}__{description}.sql` (double underscore, lowercase words separated by underscores).
- Migrations are **append-only** — never edit an already-applied migration file.
- Each migration must be idempotent where possible (use `IF NOT EXISTS`, `IF EXISTS`).
- Include a rollback script as a comment block at the bottom of every migration.
- Seed data goes in its own versioned migration (e.g., `V7__seed_data.sql`); never mix DDL and DML.

**Running migrations:**
```bash
# Local
make local-migrate

# AWS (demo environment) — image must be pushed first
make demo-push-flyway
make demo-migrate

# Reset demo DB between runs (drops all schemas + re-migrates)
make demo-reset-db

# AWS (dev environment)
bash scripts/shared/run-flyway-aws.sh
```

---

## Observability & Error Handling

**Structured logging** (all services):
- Format: JSON, one object per line.
- Required fields on every log line: `timestamp`, `level`, `service`, `correlationId`, `traceId`.
- Use `correlationId` from the incoming `X-Correlation-ID` HTTP header; generate one if absent.
- Never log PII (customer names, emails, card numbers). Log SKU IDs and order IDs freely.

**Error responses** (all services):
- Use Spring's `ProblemDetail` for all `4xx` and `5xx` responses.
- Include `type` (URI), `title`, `status`, `detail`, and `correlationId` in every error body.
- Map domain exceptions to HTTP status codes in a single `@ControllerAdvice` per service.

**Metrics** (all services):
- Expose Micrometer metrics via `/actuator/prometheus`.
- Tag every metric with `service`, `flow`, and `env`.
- Required custom metrics: `replenishment.orders.created`, `pos.events.received`, `stock.alerts.published`.

---

## Port Assignments (local mode)

| Service                 | Port | Primary MFE                                               |
|-------------------------|------|-----------------------------------------------------------|
| SIS                     | 8080 | —                                                         |
| IMS                     | 8081 | Store Manager (5173)                                      |
| RE                      | 8082 | SC Planner (5174)                                         |
| ARS                     | 8083 | Store Manager (5173), SC Planner (5174), Executive (5175) |
| DFS                     | 8084 | SC Planner (5174), Executive (5175)                       |
| SUP                     | 8085 | SC Planner (5174), Supplier (5177)                        |
| PPS                     | 8086 | —                                                         |
| PostgreSQL              | 5432 | —                                                         |
| LocalStack              | 4566 | —                                                         |
| Store Manager MFE       | 5173 | —                                                         |
| SC Planner MFE          | 5174 | —                                                         |
| Executive MFE           | 5175 | —                                                         |
| Supplier MFE            | 5177 | —                                                         |
| Demo Control Center MFE | 5176 | —                                                         |
| Demo Control Server     | 3099 | —                                                         |

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

See `docs/LOCAL_DEV.md` for detailed local setup and troubleshooting. See `docs/BUILD_SEQUENCE.md` for the full AWS deploy sequence.

---

## Standards

Agents are defined in `.claude/settings.json`. Load the relevant one before starting work.

| Agent                | Standards file                  | Use when                          |
|----------------------|---------------------------------|-----------------------------------|
| `java-standards`     | `.claude/standards/java.md`     | Any Java service work             |
| `openapi-standards`  | `.claude/standards/openapi.md`  | Designing or editing API YAMLs    |
| `maven-standards`    | `.claude/standards/maven.md`    | Build config, code generation     |
| `frontend-standards` | `.claude/standards/frontend.md` | React MFE work                    |
| `sql-standards`      | `.claude/standards/sql.md`      | Flyway migrations, schema changes |
| `testing-standards`  | `.claude/standards/testing.md`  | Writing or fixing tests           |

---

## Common Pitfalls

| Symptom                                     | Root cause                                              | Fix                                                      |
|---------------------------------------------|---------------------------------------------------------|----------------------------------------------------------|
| ArchUnit test fails on domain package       | AWS SDK imported in domain class                        | Move AWS call to an infrastructure adapter               |
| 409 on replenishment approve                | Order not in `PENDING_APPROVAL` state                   | Check state transition logic; do not approve from `DRAFT`|
| Optimistic lock exception on PO update      | `WHERE version = :v` missing from UPDATE                | Add version predicate; check rows-updated count = 1      |
| Generated TS client out of sync             | YAML edited but `npm run generate-api` not re-run       | Re-run generator; never edit generated files manually    |
| LocalStack Firehose behaves differently     | LocalStack Firehose targets SIS directly at `localhost:8080`; AWS Firehose routes via API Gateway + VPC Link | Ensure SIS is running before LocalStack init; check `localstack-init.sh` endpoint URL |
| Flyway checksum error after migration edit  | Existing migration file was modified                    | Revert the edit; add a new migration instead             |
| Services crash-loop with "Failed to obtain JDBC connection" in demo | `application-aws.yml` not loaded for `demo` profile — `spring.datasource.password` unset | `password: ${DB_PASSWORD}` is now in base `application.yml`; rebuild + redeploy |
| Correlation ID missing in logs              | `X-Correlation-ID` header not propagated through chain  | Use `CorrelationIdFilter` and MDC in every service       |

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
