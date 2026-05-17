# SIS — Sales Ingestion Service

Receives POS transaction events, writes them to the `sales` schema, and publishes domain events to EventBridge for downstream consumers (IMS, DFS).

**Port (local):** `8080`  
**Schema owned:** `sales`  
**OpenAPI spec:** `openapi/sis-api.yaml`

## Responsibilities

- Accept POS events via `POST /v1/ingest/events` (called by the Kinesis Consumer Lambda).
- Deduplicate using the `idempotency` table (`IdempotencyPort`) — identical `transactionId` returns 200 without re-processing.
- Persist the raw event to `sales.raw_events` for audit (`RawArchivePort`).
- Persist the cleaned `SalesTransaction` to `sales.sales_transactions` (`EventStorePort`).
- Publish a `SalesTransactionProcessed` event to EventBridge (`EventPublisherPort`).

## Flow position

```
Kinesis Consumer Lambda
    │  POST /v1/ingest/events
    ▼
SalesIngestionController
    │
SalesIngestionUseCase
    ├── IdempotencyPort      → idempotency table check/write
    ├── RawArchivePort       → sales.raw_events insert
    ├── EventStorePort       → sales.sales_transactions insert
    └── EventPublisherPort   → EventBridge: SalesTransactionProcessed
```

## API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v1/ingest/events` | Ingest a single POS event |

**Request body** (`SalesEventRequest`):

```json
{
  "transactionId": "uuid",
  "storeId":       "STORE-001",
  "skuId":         "SKU-BEV-001",
  "dcId":          "DC-LONDON",
  "quantity":      30,
  "unitPrice":     8.50,
  "channel":       "POS",
  "eventTimestamp": "2024-05-17T10:23:45Z"
}
```

**Responses:** `202 Accepted` (new), `200 OK` (duplicate), `400` (validation error).

## Package structure

```
com.smartretail.sis/
├── adapter/
│   ├── inbound/rest/
│   │   ├── SalesIngestionController.java
│   │   └── SalesEventMapper.java          @Mapper — SalesEventRequest → SalesTransaction
│   └── outbound/
│       ├── persistence/                   Spring Data JDBC repos (sales schema)
│       └── messaging/                     EventBridge publisher
├── config/                                DataSource, ObjectMapper, Security config
├── domain/
│   ├── model/
│   │   ├── SalesTransaction.java
│   │   └── exception/DuplicateEventException.java
│   └── usecase/SalesIngestionUseCase.java
└── port/
    ├── inbound/SalesEventPort.java
    └── outbound/
        ├── EventPublisherPort.java
        ├── IdempotencyPort.java
        ├── RawArchivePort.java
        └── EventStorePort.java
```

## Build and run

```bash
# Build + test + JaCoCo check
JAVA_HOME=<java-21-home> mvn clean verify -pl services/sis

# Run locally
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -pl services/sis
# or
make local-sis
```

## Tests

| Class | Tests | Covers |
|-------|-------|--------|
| `SalesIngestionUseCaseTest` | 3 | Happy path, duplicate suppression, validation |
| `ArchitectureTest` | 4 | Hexagonal boundary rules |
| `DuplicateEventExceptionTest` | 2 | Exception constructor and message |

JaCoCo minimum: **80 %** on `com.smartretail.sis.domain.**`
