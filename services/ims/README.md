# IMS — Inventory Management Service

Consumes `SalesTransactionProcessed` events from EventBridge, updates ATP (Available-to-Promise) in the `inventory` schema, and raises stock alerts when ATP drops below the reorder point.

**Port (local):** `8081`  
**Schema owned:** `inventory`  
**OpenAPI spec:** `openapi/ims-api.yaml`

## Responsibilities

- Listen for `SalesTransactionProcessed` events from EventBridge (inbound adapter: SQS → Lambda or direct SQS listener).
- Decrement `inventory_positions.on_hand` and recalculate ATP with an optimistic-lock update (`version` column).
- Evaluate `isLowStock()` and `computeSeverity()` on the updated position.
- Persist a `StockAlert` to `inventory.stock_alerts` when low-stock is detected.
- Publish a `StockAlertRaised` event to EventBridge (`AlertPublisherPort`).
- Expose REST endpoints for the Store Manager Dashboard (`/v1/inventory/{skuId}/{dcId}`, `/v1/alerts`).

## Flow position

```
EventBridge: SalesTransactionProcessed
    │
InventoryUpdateUseCase
    ├── InventoryRepositoryPort   → inventory.inventory_positions (optimistic lock)
    └── AlertPublisherPort        → EventBridge: StockAlertRaised
```

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/inventory/{skuId}/{dcId}` | Current inventory position |
| `GET` | `/v1/alerts` | Active stock alerts (filterable by severity, dcId) |

## Domain model

**`InventoryPosition`** — core methods:
- `getAvailableToPromise()` → `onHand - reserved`
- `isLowStock()` → `atp < reorderPoint`
- `computeSeverity()` → `CRITICAL` (atp ≤ 0) / `HIGH` (atp < reorderPoint × 0.5) / `MEDIUM` (otherwise)

**`StockAlert`** — created via `StockAlert.create(position, type, severity)` or rehydrated from DB via `StockAlert.fromDb(...)`.

## Package structure

```
com.smartretail.ims/
├── adapter/
│   ├── inbound/rest/
│   │   ├── InventoryController.java
│   │   └── InventoryResponseMapper.java   @Mapper — domain → generated API types
│   └── outbound/
│       ├── persistence/
│       └── messaging/
├── config/
├── domain/
│   ├── model/
│   │   ├── InventoryPosition.java
│   │   ├── StockAlert.java
│   │   ├── AlertType.java                 LOW_STOCK | OVERSTOCK
│   │   ├── AlertSeverity.java             CRITICAL | HIGH | MEDIUM
│   │   └── exception/
│   │       ├── InventoryPositionNotFoundException.java
│   │       └── OptimisticLockException.java
│   └── usecase/InventoryUpdateUseCase.java
└── port/
    ├── inbound/InventoryUpdatePort.java
    └── outbound/
        ├── AlertPublisherPort.java
        └── InventoryRepositoryPort.java
```

## Build and run

```bash
JAVA_HOME=<java-21-home> mvn clean verify -pl services/ims
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -pl services/ims
# or
make local-ims
```

## Tests

| Class | Tests | Covers |
|-------|-------|--------|
| `InventoryUpdateUseCaseTest` | 4 | ATP update, alert creation, optimistic lock conflict |
| `InventoryPositionTest` | 6 | ATP calc, isLowStock, computeSeverity (all severity branches) |
| `StockAlertTest` | 2 | `create()` factory, `fromDb()` rehydration |
| `ArchitectureTest` | 4 | Hexagonal boundary rules |

JaCoCo minimum: **80 %** on `com.smartretail.ims.domain.**`
