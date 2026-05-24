# DFS — Demand Forecasting Service

Serves pre-computed demand forecasts from the `forecasting` schema. Exposes P10/P50/P90 probability bands for the SC Planner's forecast view and provides the MAPE history consumed by ARS.

**Port (local):** `8084`  
**Schema owned:** `forecasting`  
**OpenAPI spec:** `src/main/resources/dfs-api.yaml`

## Responsibilities

- Return probabilistic forecast bands (P10 / P50 / P90) for a given SKU and DC over a configurable horizon.
- Expose MAPE history records consumed by ARS for the Executive Dashboard trend chart.
- All data is pre-populated via Flyway seed migrations (V2, V8) — DFS is query-only in the prototype.

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/forecast/{skuId}/{dcId}` | Forecast bands for SKU/DC; `?horizonDays=` param |
| `GET` | `/v1/forecast/mape-history` | MAPE history records (consumed by ARS) |

**Response shape** (`ForecastResponse`):

```json
{
  "skuId": "SKU-BEV-001",
  "dcId":  "DC-LONDON",
  "bands": [
    { "date": "2024-05-18", "p10": 240, "p50": 310, "p90": 390 },
    ...
  ],
  "generatedAt": "2024-05-17T00:00:00Z"
}
```

## Package structure

```
com.smartretail.dfs/
├── adapter/
│   ├── inbound/rest/
│   │   ├── ForecastController.java
│   │   └── ForecastResponseMapper.java   @Mapper — ForecastData → ForecastResponse
│   └── outbound/persistence/             Spring Data JDBC (forecasting schema)
├── config/
├── domain/
│   ├── model/ForecastData.java
│   └── usecase/ForecastQueryUseCase.java
└── port/
    ├── inbound/ForecastQueryPort.java
    └── outbound/ForecastReadPort.java
```

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
JAVA_HOME=<java-21-home> mvn clean verify -pl services/dfs
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -pl services/dfs
# or
make local-dfs
```

## Tests

| Class | Tests | Covers |
|-------|-------|--------|
| `ForecastQueryUseCaseTest` | 4 | SKU found, SKU not found, horizon filtering, empty bands |
| `ArchitectureTest` | 4 | Hexagonal boundary rules |

JaCoCo minimum: **80 %** on `com.smartretail.dfs.domain.**`

## Note on prototype scope

Forecasting model execution is out of scope for this prototype. The `forecasting` schema is populated by Flyway seed data (V2 + V8) representing realistic MAPE trends and P10/P50/P90 bands across 14 SKUs and 3 DCs.
