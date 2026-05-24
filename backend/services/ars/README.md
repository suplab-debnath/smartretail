# ARS — Aggregated Reporting Service

Read-only reporting service that fans out queries to the other services' schemas and merges the results in Java. Feeds the Store Manager Dashboard, SC Planner Console, and Executive Dashboard MFEs.

**Port (local):** `8083`  
**Schema owned:** none — read-only queries across all schemas  
**OpenAPI spec:** `src/main/resources/ars-api.yaml`

## Responsibilities

- Serve four independent dashboard payloads from a single service to avoid each MFE calling six backend services directly.
- Enforce the no-cross-schema-join rule by issuing separate queries per schema and merging in Java with `CompletableFuture`.
- Validate JWT (Cognito) in AWS mode — the only service with Spring Security enabled.
- Return pre-shaped payloads that the MFEs can render without further transformation.

## Architecture rule

> ARS may **read** from any schema but must **never** issue SQL JOINs that cross schema boundaries.  
> All merging happens in use-case code using parallel `CompletableFuture` reads.  
> ArchUnit test `ArsArchitectureTest` enforces this.

## API

| Method | Path | Consumer MFE |
|--------|------|-------------|
| `GET` | `/v1/reporting/store-manager` | Store Manager |
| `GET` | `/v1/reporting/executive` | Executive Dashboard |
| `GET` | `/v1/reporting/sc-planner` | SC Planner Console |
| `GET` | `/v1/reporting/supplier-performance` | SC Planner — Supplier Scorecard tab |

## Package structure

```
com.smartretail.ars/
├── adapter/
│   ├── inbound/rest/
│   │   ├── DashboardController.java
│   │   ├── DashboardResponseMapper.java   @Mapper — executive / sc-planner / supplier payloads
│   │   └── StoreManagerResponseMapper.java
│   └── outbound/persistence/             Read-only Spring Data JDBC repos per schema
├── config/                               Security config (JWT in AWS mode, mock bypass locally)
├── domain/
│   ├── model/                            Read-model POJOs (no setters — immutable projections)
│   └── usecase/
│       ├── ExecutiveDashboardUseCase.java
│       ├── ScPlannerDashboardUseCase.java
│       ├── StoreManagerDashboardUseCase.java
│       └── SupplierPerformanceUseCase.java
└── port/
    ├── inbound/
    │   ├── ExecutiveDashboardPort.java
    │   ├── ScPlannerDashboardPort.java
    │   ├── StoreManagerDashboardPort.java
    │   └── SupplierPerformancePort.java
    └── outbound/
        ├── ForecastReadPort.java
        ├── InventoryReadPort.java
        ├── ReplenishmentReadPort.java
        └── SupplierReadPort.java
```

## Key business logic

**MAPE trend** (`ExecutiveDashboardUseCase`) — computes a 7-day rolling average of MAPE values; trend is `IMPROVING` when the most-recent 7-day average is lower than the prior 7-day average.

**OTD rate** — `(earlyCount + onTimeCount) / totalShipments`; supplier list sorted descending by OTD for the ranking table.

**Supplier performance** (`SupplierPerformanceUseCase`) — sorted ascending by OTD (worst-first) for the exception-focused SC Planner view.

**Forecast coverage** — `(skusWithForecast / totalSkus) * 100` rounded to one decimal.

## Spring Profiles

| `SPRING_PROFILES_ACTIVE` | Config loaded | Security | Use case |
|---|---|---|---|
| `local` | `application-local.yml` | Permit-all, no CORS | Local dev — Docker Compose + LocalStack `:4566` |
| `demo` | `application-aws.yml` + `application-demo.yml` | Permit-all + CORS; OAuth2 auto-config disabled | cdk-demo on AWS — role set via `X-Dev-Role` header, no Cognito JWT |
| `dev` | `application-aws.yml` | CORS + Cognito JWT required | cdk-dev / cdk-prod on AWS |

Profile group resolution (`application.yml`): `dev → [aws]`, `demo → [aws]`.  
The `demo` overlay (`application-demo.yml`) excludes `OAuth2ResourceServerAutoConfiguration` so Spring does not contact the Cognito OIDC endpoint at startup.

**AWS CLI profile:** default `smartretail-dev` (`~/.aws/config`). Override: `AWS_PROFILE=my-profile`.

## Build and run

```bash
JAVA_HOME=<java-21-home> mvn clean verify -pl services/ars
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -pl services/ars
# or
make local-ars
```

## Tests

| Class | Tests | Covers |
|-------|-------|--------|
| `ExecutiveDashboardUseCaseTest` | 7 | MAPE trend logic, stockout direction, OTD rate calc, supplier sort |
| `ScPlannerDashboardUseCaseTest` | 4 | MAPE threshold comparison, forecast coverage % |
| `StoreManagerDashboardUseCaseTest` | 5 | KPI aggregation, alert pagination |
| `SupplierPerformanceUseCaseTest` | 5 | Worst-first sort, OTD colour thresholds |
| `ArchitectureTest` | 4 | Hexagonal + no-cross-schema-join rules |

JaCoCo minimum: **80 %** on `com.smartretail.ars.domain.**`
