# Services

Seven Spring Boot microservices that together implement the SmartRetail demand-forecasting and supply-chain prototype. Each service owns exactly one PostgreSQL schema and communicates with others only through EventBridge events or HTTP (never via cross-schema SQL joins).

## Services at a glance

| Directory | Name | Port (local) | Schema owned |
|-----------|------|-------------|--------------|
| `sis/` | Sales Ingestion Service | 8080 | `sales` |
| `ims/` | Inventory Management Service | 8081 | `inventory` |
| `re/` | Replenishment Engine | 8082 | `replenishment` |
| `ars/` | Aggregated Reporting Service | 8083 | reads all schemas |
| `dfs/` | Demand Forecasting Service | 8084 | `forecasting` |
| `sup/` | Supplier Service | 8085 | `supplier` |
| `pps/` | Pricing & Promotions Service | 8086 | `promotions` |

## Technology

- **Java 21** — records, sealed interfaces, pattern matching
- **Spring Boot 3.3.x** — Web, Data JDBC, Actuator, Security (AWS mode)
- **Hexagonal architecture** — enforced by ArchUnit tests; domain core has zero AWS/Spring imports
- **OpenAPI-first** — every service generates its request/response types from `openapi/{service}-api.yaml` via `openapi-generator`; hand-written DTOs are forbidden
- **MapStruct 1.6.2** — compile-time mappers between domain models and generated API types
- **JaCoCo** — 80 % line coverage gate on `domain.**`; build fails if not met

## Build

```bash
# From the repository root — builds all services and the lambda
JAVA_HOME=<java-21-home> mvn clean verify -pl services/sis,services/ims,services/re,services/ars,services/dfs,services/sup,services/pps

# Single service
JAVA_HOME=<java-21-home> mvn clean verify -pl services/sis
```

## Run (local mode)

```bash
# All services via Make
make local-sis   # SPRING_PROFILES_ACTIVE=local, port 8080
make local-ims   # port 8081
make local-re    # port 8082
make local-ars   # port 8083
make local-dfs   # port 8084
make local-sup   # port 8085
make local-pps   # port 8086
```

Requires `make local-up` (Postgres + LocalStack) and `make local-migrate` to be run first.

## Architecture rules (enforced by ArchUnit)

1. No `software.amazon.*` imports inside any `domain.**` package.
2. No SQL joins across schema boundaries — ARS merges data in Java using `CompletableFuture`.
3. All `purchase_orders` updates must include `WHERE version = :v` (optimistic lock).
4. `PENDING_APPROVAL` is the only status that can transition to `APPROVED` or `REJECTED`.

## Package layout (same in every service)

```
com.smartretail.<service>/
├── adapter/
│   ├── inbound/
│   │   └── rest/          REST controllers + MapStruct mappers
│   └── outbound/
│       ├── persistence/   Spring Data JDBC repositories
│       └── messaging/     EventBridge / SNS publishers
├── config/                Spring @Configuration classes
├── domain/
│   ├── model/             Pure domain objects — no framework annotations
│   └── usecase/           @Service use-case classes
└── port/
    ├── inbound/           Interfaces implemented by use cases
    └── outbound/          Interfaces implemented by adapters
```

## Code generation

Generated sources live in `target/` and are never committed.

```bash
mvn generate-sources   # regenerates from openapi/{service}-api.yaml
```

See each service's `README.md` for service-specific details.
