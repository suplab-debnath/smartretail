# GitHub Copilot Instructions — SmartRetail Platform

## Reusable Prompts (Copilot Chat)

Open Copilot Chat and type `/` to use any of these prompt files from `.github/prompts/`:

### Persona agents (embody a specific role)
| Prompt | Role |
|---|---|
| `/agent-aws-architect` | Senior AWS Solutions Architect -- CDK, IAM, ECS/RDS/SQS/Firehose review |
| `/agent-java-developer` | Senior Java 21 Developer -- hexagonal, contract-first, Spring Boot 3.3 |
| `/agent-tester` | QA Engineer -- JUnit 5/Mockito/Testcontainers/ArchUnit/Vitest |
| `/agent-code-reviewer` | Code Reviewer -- 8 architecture rules, security, test coverage |
| `/agent-ops-engineer` | Platform Engineer -- Makefile, Flyway, LocalStack, CDK deploy |
| `/agent-enterprise-architect` | Enterprise Architect -- bounded contexts, event contracts, schema governance |
| `/agent-react-developer` | React Developer -- MFEs, Tailwind, data-freshness, accessibility |
| `/agent-ml-engineer` | ML Engineer -- SageMaker pipeline, DFS, MAPE, forecast accuracy |
| `/agent-retail-sme` | Supply Chain SME -- domain expert, KPIs, replenishment rules |
| `/agent-aws-finops-analyst` | AWS FinOps Analyst -- cost evaluation for poc/dev/stg/prod, right-sizing, Savings Plans |

### Task prompts (specific coding tasks)
| Prompt | What it does |
|---|---|
| `/task-new-service` | Scaffold a new Spring Boot service with full hexagonal structure |
| `/task-new-migration` | Create the next Flyway versioned migration correctly |
| `/task-new-endpoint` | Add a REST endpoint contract-first (YAML -> generate -> implement) |
| `/task-new-mfe-component` | Create a typed React component with tests and accessibility |
| `/task-generate-tests` | Generate comprehensive unit, IT, ArchUnit, and MFE tests |
| `/task-debug-flow` | Diagnose a failing flow assertion via logs and DB queries |

### Workflow prompts (multi-step processes)
| Prompt | What it does |
|---|---|
| `/workflow-implement-flow` | End-to-end flow implementation: schema -> services -> MFE -> smoke test |
| `/workflow-review-pr` | Structured PR review with architecture + security + test checklist |
| `/workflow-deploy-demo` | Full demo AWS deployment: CDK -> migrations -> services -> MFEs -> smoke |
| `/workflow-onboard-developer` | Guided onboarding walkthrough for new team members |

---

## Scoped Instruction Files (`.github/instructions/`)

These apply automatically when editing matching file types:
- `java.instructions.md` -- `**/*.java` -- hexagonal rules, constructor injection, records
- `typescript.instructions.md` -- `**/*.{ts,tsx}` -- functional components, typed props, hooks
- `sql.instructions.md` -- `**/db/migration/**.sql` -- schema qualification, immutability
- `openapi.instructions.md` -- `**/*-api.yaml` -- contract-first rules, response codes
- `cdk.instructions.md` -- `environments/**/*.ts` -- CDK conventions, IAM, naming
- `maven.instructions.md` -- `**/pom.xml` -- module layout, plugin config, versions
- `testing.instructions.md` -- `**/*Test.java, **/*IT.java, **/*.test.ts` -- test patterns

---

## Project Overview

SmartRetail is a demand forecasting and supply chain platform built on AWS. The codebase has six
end-to-end flows: POS event ingestion → inventory management → replenishment engine → analytics →
executive and SC-planner dashboards.

**Stack:** Java 21 · Spring Boot 3.3 · Hexagonal Architecture · OpenAPI 3.1 · AWS CDK TypeScript v2
· React 18 · TypeScript 5 · PostgreSQL 15 · Flyway 10

---

## Critical Architecture Rules

These rules are enforced by ArchUnit and will fail the CI build if violated.

1. **Hexagonal architecture** — domain code has zero AWS imports. Place AWS SDK calls only in
   `adapter.outbound.*` packages.
2. **No cross-schema SQL joins** — each service owns one PostgreSQL schema. ARS merges data from
   multiple schemas in Java, not in SQL.
3. **No service writes to another service's schema** — each schema is exclusively owned by one service.
4. **Contract-first APIs** — every REST endpoint starts with the OpenAPI YAML. Never hand-write
   request/response DTO classes that duplicate what openapi-generator produces.
5. **Optimistic locking** — every `UPDATE purchase_orders SET ... WHERE id = :id AND version = :v`.
   Increment version on every write.
6. **State machine is in RDS** — no Step Functions. Workflow status lives in the database.
7. **Approve only from PENDING_APPROVAL** — approving a PO in any other state returns HTTP 409.
8. **JWT validation at both API Gateway AND service layer.**

---

## Package Structure (per Java service)

Every Spring Boot service under `backend/services/{svc}/` follows this hexagonal layout:

```
com.smartretail.{svc}/
├── domain/
│   ├── model/           ← Java records, value objects, sealed interfaces, enums
│   ├── port/
│   │   ├── inbound/     ← use-case interfaces (e.g. SalesEventPort)
│   │   └── outbound/    ← repository/publisher interfaces (e.g. EventStorePort)
│   └── service/         ← use-case implementations — depend on ports only
├── adapter/
│   ├── inbound/
│   │   └── rest/        ← @RestController — implements generated OpenAPI interfaces
│   └── outbound/
│       ├── persistence/ ← Spring Data JDBC repositories
│       ├── messaging/   ← SQS @SqsListener + EventBridge publishers
│       └── aws/         ← S3, Secrets Manager, other AWS SDK adapters
└── config/              ← @Configuration, @Bean, security filter chain
```

**Never place AWS SDK imports inside `domain/` packages.**

---

## Java Coding Rules

- **Constructor injection only.** No `@Autowired` on fields or setters.
- **Records for value objects** — use compact constructors for validation.
- **Sealed interfaces** for result types (e.g. `IngestionResult`).
- **Text blocks** for all multi-line SQL strings.
- **Optional\<T\>** from all repository find methods — never return null.
- **Unchecked exceptions only** in the domain layer.
- Max method length: 30 lines. Max class length: 200 lines (repos may be longer).
- All public port interface methods must have Javadoc.

### Naming

| Element | Convention | Example |
|---|---|---|
| Domain records | PascalCase noun | `PurchaseOrder` |
| Use cases | PascalCase + UseCase | `GeneratePurchaseOrderUseCase` |
| Inbound/outbound ports | PascalCase + Port | `SalesEventPort`, `EventStorePort` |
| Adapters | PascalCase + tech + Adapter | `EventBridgePurchaseOrderPublisher` |
| Test classes | ClassUnderTest + Test | `ApprovalWorkflowUseCaseTest` |
| Integration tests | ClassUnderTest + IT | `ReplenishmentRepositoryIT` |

---

## OpenAPI / Contract-First Rules

1. **Modify the YAML first** (`backend/services/{svc}/src/main/resources/{svc}-api.yaml`), then
   regenerate stubs with `mvn generate-sources`.
2. Generated Java stubs live in `target/generated-sources/openapi/` — **never edit these**.
3. Generated TypeScript client lives in `mfe/shared/api-client/src/generated/` — **never edit these**.
4. `additionalProperties: false` on all request body schemas.
5. Every property needs a `description` and an `example`.
6. Use `format: uuid` / `format: date-time` / `format: double` on all typed fields.

---

## SQL / Flyway Rules

- Schema-qualify every table name: `inventory.stock_levels`, not `stock_levels`.
- No cross-schema JOINs in SQL — merge in Java.
- Flyway migrations are **immutable** once applied. Never edit `V{N}__*.sql` that already exists;
  create `V{N+1}__*.sql` instead.
- All tables: `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()` and
  `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`.
- Primary keys: `UUID PRIMARY KEY DEFAULT gen_random_uuid()`.
- No direct RDS endpoint in JDBC URL in AWS mode — use the RDS Proxy endpoint.

---

## Frontend (React / TypeScript) Rules

- **Functional components only** — no class components.
- **Typed props** — no `any`. No implicit `{}` types.
- **Custom hooks** for all data fetching — no fetch/axios calls inside components.
- **TypeScript strict mode** — `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes` enabled.
- **Tailwind CSS** for all styles — use the semantic colour palette from `.claude/standards/frontend.md`.
- Do not use arbitrary colour values. Use the named `text-red-700 bg-red-50` pattern for status.
- All interactive elements must be keyboard-navigable (WCAG 2.1 AA).
- Components over 150 lines must be split.
- `role="alert"` on error banners, `aria-label="Loading..."` on loading states.

### MFE Port Map (local mode)
| MFE | Port |
|---|---|
| store-manager | 5173 |
| sc-planner | 5174 |
| executive | 5175 |
| supplier | 5177 |

---

## Service Boundaries

| Service | Schema | Primary responsibility |
|---|---|---|
| SIS (:8080) | `sales` | Ingest POS events; idempotency via `idempotency_keys` |
| IMS (:8081) | `inventory` | Maintain stock levels; publish low-stock alerts |
| RE (:8082) | `replenishment` | PO lifecycle state machine; approve/reject |
| ARS (:8083) | `— (reads all)` | Analytics aggregation; no writes |
| DFS (:8084) | `forecasting` | Demand forecast storage and retrieval |
| SUP (:8085) | `supplier` | Supplier performance and order tracking |
| PPS (:8086) | `promotions` | Promotion planning and activation |

---

## Workflow Status Transitions (purchase_orders)

```
DRAFT → PENDING_APPROVAL → APPROVED → DISPATCHED → ACKNOWLEDGED → SHIPPED
                        ↘ REJECTED
                        ↘ EXPIRED
                        ↘ CANCELLED  (system-level only — NOT for planner rejection)
```

- `CANCELLED` is for system-level events only. A planner rejection uses `REJECTED`.
- Approving from any status other than `PENDING_APPROVAL` returns HTTP 409
  with `errorCode: INVALID_STATUS_TRANSITION`.

---

## Testing

- `@ExtendWith(MockitoExtension.class)` — no Spring context for unit tests.
- `@Testcontainers` with `PostgreSQLContainer` for repository integration tests.
- Test method names: `should{Outcome}When{Condition}`.
- Test both happy path and all exception paths.
- Never assert on log output — test behaviour only.

---

## Run Modes

```
SPRING_PROFILES_ACTIVE=local  → LocalStack :4566 + Docker Postgres :5432
SPRING_PROFILES_ACTIVE=aws    → Real AWS + RDS Proxy
```

In `local` mode, JWT validation is bypassed by a mock security filter.
In `aws` mode, Cognito JWT is validated at both API Gateway and the service filter chain.
