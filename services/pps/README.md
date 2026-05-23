# PPS — Pricing & Promotions Service

Read-only query service that exposes active promotion schedules from the `promotions` schema. Feeds the SC Planner's Forecast Adjustment tab and future pricing surfaces.

**Port (local):** `8086`  
**Schema owned:** `promotions`  
**OpenAPI spec:** `openapi/pps-api.yaml`

## Responsibilities

- Serve active promotion schedules with optional filtering by status (`ACTIVE` / `EXPIRED` / `CANCELLED`).
- Expose `discountPct`, `upliftFactor`, and `elasticityCoeff` so downstream consumers (ARS, SC Planner MFE) can apply promotional lift to demand forecasts.
- All data is pre-populated via Flyway seed migration (V6) — PPS is query-only in the prototype.

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/promotions/schedules` | List promotion schedules (optional `?status=ACTIVE\|EXPIRED\|CANCELLED`) |

## Package structure

```
com.smartretail.pps/
├── adapter/
│   ├── inbound/rest/
│   │   ├── PromotionController.java          implements PromotionSchedulesApi (generated)
│   │   ├── PromotionResponseMapper.java      @Mapper — domain list → generated API response
│   │   └── GlobalExceptionHandler.java
│   └── outbound/persistence/
│       └── PromotionRepository.java          queries promotions.promotion_schedules
├── config/
│   └── SecurityConfig.java
├── domain/
│   ├── model/PromotionList.java              record: List<PromotionSchedule> + dataFreshness
│   └── usecase/PromotionQueryUseCase.java
└── port/
    ├── inbound/PromotionQueryPort.java
    └── outbound/PromotionReadPort.java
```

## Build and run

```bash
JAVA_HOME=<java-21-home> mvn clean verify -pl services/pps
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -pl services/pps
# or
make local-pps
```

Requires `make local-up` (Postgres + LocalStack) and `make local-migrate` to be run first.

## Tests

| Class | Tests | Covers |
|-------|-------|--------|
| `ArchitectureTest` | 4 | Hexagonal boundary rules (domain ← no AWS/Spring; adapters ← no cross-dependencies) |

JaCoCo minimum: **80 %** on `com.smartretail.pps.domain.**`

## Note on prototype scope

Promotion ingestion (write path) is out of scope. In a full implementation PPS would consume CMS events from EventBridge and write to `promotions.promotion_schedules`. The schema is pre-populated by V6 seed data representing several active and expired promotions.
