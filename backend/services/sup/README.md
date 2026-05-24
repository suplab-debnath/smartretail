# SUP — Supplier Service

Query service for supplier order tracking and shipment metrics. Feeds the SC Planner's Supplier Order Tracking tab and the supplier performance data consumed by ARS.

**Port (local):** `8085`  
**Schema owned:** `supplier`  
**OpenAPI spec:** `src/main/resources/sup-api.yaml`

## Responsibilities

- Serve supplier order lists with optional filtering by supplier, status, and DC.
- Expose shipment metrics (OTD count, fill rate, early/late counts) consumed by ARS's `SupplierPerformanceUseCase`.
- All data is pre-populated via Flyway seed migrations (V5, V9) — SUP is query-only in the prototype.

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/supplier/orders` | List supplier orders (filter by `supplierId`, `status`, `dcId`) |
| `GET` | `/v1/supplier/orders/{id}` | Single order detail |
| `GET` | `/v1/supplier/metrics` | Shipment metrics per supplier (consumed by ARS) |

## Package structure

```
com.smartretail.sup/
├── adapter/
│   ├── inbound/rest/
│   │   ├── SupplierOrderController.java
│   │   └── SupplierOrderResponseMapper.java   @Mapper — domain list → generated API response
│   └── outbound/persistence/                  Spring Data JDBC (supplier schema)
├── config/
├── domain/
│   ├── model/
│   │   ├── SupplierOrder.java
│   │   └── ShipmentMetrics.java
│   └── usecase/SupplierOrderQueryUseCase.java
└── port/
    ├── inbound/SupplierOrderQueryPort.java
    └── outbound/SupplierOrderReadPort.java
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
JAVA_HOME=<java-21-home> mvn clean verify -pl services/sup
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -pl services/sup
# or
make local-sup
```

## Tests

| Class | Tests | Covers |
|-------|-------|--------|
| `SupplierOrderQueryUseCaseTest` | 5 | Filter by status, filter by supplier, empty result, metrics aggregation |
| `ArchitectureTest` | 4 | Hexagonal boundary rules |

JaCoCo minimum: **80 %** on `com.smartretail.sup.domain.**`

## Note on prototype scope

Supplier onboarding, EDI integration, and invoice reconciliation are out of scope. The `supplier` schema is populated by seed data in V5 and V9 representing six suppliers across three DCs with realistic OTD rates and fill rates.
