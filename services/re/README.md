# RE — Replenishment Engine

Processes inventory alerts to create purchase orders and manages the PO lifecycle (PENDING_APPROVAL → APPROVED / REJECTED). Also accepts manual replenishment triggers from the SC Planner MFE.

**Port (local):** `8082`  
**Schema owned:** `replenishment`  
**OpenAPI spec:** `src/main/resources/re-api.yaml`

## Responsibilities

- Consume `StockAlertRaised` EventBridge events and create a `PurchaseOrder` with status `PENDING_APPROVAL`.
- Expose PO approval and rejection endpoints for the SC Planner MFE.
- Accept manual replenishment triggers (`POST /v1/replenishment/trigger`) — always creates a `PENDING_APPROVAL` order.
- Enforce optimistic locking on every PO status transition (`WHERE version = :v`).
- Publish `PurchaseOrderStatusChanged` events to EventBridge after each transition.

## Flow position

```
EventBridge: StockAlertRaised
    │
ProcessInventoryAlertUseCase  →  replenishment.purchase_orders (PENDING_APPROVAL)
                                 replenishment.po_line_items

SC Planner MFE
    │  POST /v1/replenishment/orders/{id}/approve
    ▼
ApprovePurchaseOrderUseCase   →  updateStatus(APPROVED, version=N)
    │
    └── EventBridge: PurchaseOrderStatusChanged
```

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/replenishment/orders` | List purchase orders (filter by status, dcId, skuId) |
| `GET` | `/v1/replenishment/orders/{id}` | Get single PO with line items |
| `POST` | `/v1/replenishment/orders/{id}/approve` | Approve — requires `PENDING_APPROVAL` status |
| `POST` | `/v1/replenishment/orders/{id}/reject` | Reject — requires `PENDING_APPROVAL` status |
| `POST` | `/v1/replenishment/trigger` | Manually trigger replenishment for a SKU/DC |

## WorkflowStatus state machine

```
DRAFT ──► PENDING_APPROVAL ──► APPROVED ──► DISPATCHED ──► SHIPPED ──► COMPLETED
                          └──► REJECTED
                                                                ├──► PARTIAL_DELIVERY
EXPIRED  CANCELLED  ACKNOWLEDGED  (terminal / system states)
```

Only `PENDING_APPROVAL` can transition to `APPROVED` or `REJECTED`. Attempting to approve a `DRAFT` or already-`APPROVED` PO returns 409.

## Package structure

```
com.smartretail.re/
├── adapter/
│   ├── inbound/rest/
│   │   ├── ReplenishmentController.java
│   │   └── ReplenishmentResponseMapper.java   @Mapper — domain PO → generated API types
│   └── outbound/
│       ├── persistence/
│       └── messaging/
├── config/
├── domain/
│   ├── model/
│   │   ├── PurchaseOrder.java                 Factory: PurchaseOrder.create(rule, qty, value, status, alertId)
│   │   ├── PoLineItem.java
│   │   ├── ReplenishmentRule.java
│   │   ├── WorkflowStatus.java                canApprove() / canReject() guards
│   │   ├── InventoryAlertEventDto.java        Record — EventBridge payload deserialisation
│   │   └── exception/
│   │       ├── InvalidStatusTransitionException.java
│   │       ├── OptimisticLockException.java
│   │       ├── PurchaseOrderNotFoundException.java
│   │       └── ReplenishmentRuleNotFoundException.java
│   └── usecase/
│       ├── ProcessInventoryAlertUseCase.java
│       ├── ApprovePurchaseOrderUseCase.java
│       ├── RejectPurchaseOrderUseCase.java
│       └── TriggerManualReplenishmentUseCase.java
└── port/
    ├── inbound/
    │   ├── ApprovePurchaseOrderPort.java
    │   ├── RejectPurchaseOrderPort.java
    │   ├── ProcessInventoryAlertPort.java
    │   └── TriggerManualReplenishmentPort.java
    └── outbound/
        ├── ReplenishmentRepositoryPort.java
        └── PurchaseOrderEventPublisherPort.java
```

## Spring Profiles

| `SPRING_PROFILES_ACTIVE` | Config loaded | Security | Use case |
|---|---|---|---|
| `local` | `application-local.yml` | Permit-all, no CORS | Local dev — Docker Compose + LocalStack `:4566` |
| `demo` | `application-aws.yml` + `application-demo.yml` | Permit-all + CORS; OAuth2 auto-config disabled | cdk-demo on AWS — role set via `X-Dev-Role` header, no Cognito JWT |
| `dev` | `application-aws.yml` | CORS + Cognito JWT required; approve/reject also require `SC_PLANNER` or `ADMIN` role | cdk-dev / cdk-prod on AWS |

Profile group resolution (`application.yml`): `dev → [aws]`, `demo → [aws]`.  
The `demo` overlay (`application-demo.yml`) excludes `OAuth2ResourceServerAutoConfiguration` so Spring does not contact the Cognito OIDC endpoint at startup.

**AWS CLI profile:** default `smartretail-dev` (`~/.aws/config`). Override: `AWS_PROFILE=my-profile`.

## Build and run

```bash
JAVA_HOME=<java-21-home> mvn clean verify -pl services/re
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -pl services/re
# or
make local-re
```

## Tests

| Class | Tests | Covers |
|-------|-------|--------|
| `ProcessInventoryAlertUseCaseTest` | 4 | Alert → PO creation, MOQ enforcement |
| `ApprovePurchaseOrderUseCaseTest` | 5 | Approve happy path, invalid status, optimistic lock, not-found |
| `RejectPurchaseOrderUseCaseTest` | 4 | Reject happy path, invalid status, optimistic lock, not-found |
| `TriggerManualReplenishmentUseCaseTest` | 5 | MOQ enforcement, totalValue calc, missing rule |
| `WorkflowStatusTest` | 2 | `canApprove()` / `canReject()` for all statuses |
| `PurchaseOrderTest` | 2 | `create()` factory, setter round-trip (DB rehydration) |
| `InventoryAlertEventDtoTest` | 1 | Record accessor coverage |
| `DomainExceptionsTest` | 4 | All four exception constructors |
| `ArchitectureTest` | 4 | Hexagonal boundary rules |

JaCoCo minimum: **80 %** on `com.smartretail.re.domain.**`
