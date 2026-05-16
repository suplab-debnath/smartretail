# Service Implementation Specifications
 
One section per service. Each section specifies the exact package structure,
domain model, port interfaces, and adapter implementations required.
 
All services share this Maven parent POM structure:
- Group ID: `com.smartretail`
- Java 21
- Spring Boot 3.3.x
- See ARCHITECTURE.md for full dependency list
 
---
 
## Kinesis Consumer Lambda (SIS Inbound Adapter)
 
Location: `lambdas/kinesis-consumer/`
Handler: `com.smartretail.lambda.kinesis.KinesisConsumerHandler`
 
### Responsibilities
 
1. Receive a batch of Kinesis records
2. For each record:
   a. Deserialize the POS event JSON
   b. Compute SHA-256(transactionId) → check DynamoDB (GetItem)
   c. If duplicate: skip silently, log at DEBUG
   d. If not duplicate: validate JSON schema
   e. If invalid: route to SQS DLQ with error envelope
   f. If valid: POST to SIS /v1/ingest/events endpoint
   g. On SIS 202: write idempotency key to DynamoDB (TTL = now + 48h)
   h. On SIS 409: duplicate detected by SIS (race condition) — skip
   i. On SIS 4xx/5xx: retry with exponential backoff, DLQ after 3 attempts
 
### Key implementation notes
 
- Uses `bisect-on-error: true` in Kinesis event source mapping
- SIS endpoint URL read from environment variable SIS_ENDPOINT
- DynamoDB table name read from environment variable IDEMPOTENCY_TABLE_NAME
- Uses AWS SDK v2 async clients
- No business logic here — pure infrastructure adapter
 
### POS event JSON format (Kinesis record payload):
```json
{
  "transactionId": "uuid",
  "storeId": "STORE-001",
  "skuId": "SKU-BEV-001",
  "dcId": "DC-LONDON",
  "quantity": 30,
  "unitPrice": 8.50,
  "channel": "POS",
  "eventTimestamp": "2026-05-15T14:23:00Z"
}
```
 
---
 
## SIS — Sales Ingestion Service
 
Location: `services/sis/`
Main class: `com.smartretail.sis.SisApplication`
 
### Package Structure
 
```
com.smartretail.sis/
├── SisApplication.java
├── domain/
│   ├── model/
│   │   ├── SalesTransaction.java          ← aggregate root
│   │   └── IdempotencyRecord.java         ← value object
│   └── usecase/
│       └── SalesIngestionUseCase.java     ← application service
├── port/
│   ├── inbound/
│   │   └── SalesEventPort.java            ← interface
│   └── outbound/
│       ├── EventStorePort.java            ← interface
│       ├── EventPublisherPort.java        ← interface
│       ├── RawArchivePort.java            ← interface
│       └── IdempotencyPort.java           ← interface
└── adapter/
    ├── inbound/
    │   └── rest/
    │       └── SalesIngestionController.java
    └── outbound/
        ├── persistence/
        │   └── SalesEventRepository.java  ← implements EventStorePort
        ├── event/
        │   └── EventBridgePublisher.java  ← implements EventPublisherPort
        ├── archive/
        │   └── S3RawArchiveAdapter.java   ← implements RawArchivePort
        └── idempotency/
            └── DynamoDbIdempotencyAdapter.java ← implements IdempotencyPort
```
 
### Domain Model
 
```java
// SalesTransaction.java — NO AWS IMPORTS
public class SalesTransaction {
    private final UUID transactionId;
    private final String storeId;
    private final String skuId;
    private final String dcId;
    private final int quantity;
    private final BigDecimal unitPrice;
    private final Channel channel;
    private final Instant eventTimestamp;
 
    public enum Channel { POS, ECOMMERCE }
 
    // Constructor validates all fields
    // No setters — immutable
    // Factory method: SalesTransaction.of(...)
}
```
 
### Inbound Port
 
```java
// SalesEventPort.java
public interface SalesEventPort {
    IngestionResult ingest(SalesTransaction transaction);
}
 
public record IngestionResult(UUID transactionId, IngestionStatus status) {
    public enum IngestionStatus { ACCEPTED, DUPLICATE }
}
```
 
### Use Case
 
```java
// SalesIngestionUseCase.java — NO AWS IMPORTS
@Component
public class SalesIngestionUseCase implements SalesEventPort {
 
    private final EventStorePort eventStore;
    private final EventPublisherPort eventPublisher;
    private final RawArchivePort rawArchive;
    private final IdempotencyPort idempotency;
 
    @Override
    @Transactional  // Spring transaction — NOT AWS
    public IngestionResult ingest(SalesTransaction transaction) {
        String eventId = sha256(transaction.getTransactionId().toString());
 
        if (idempotency.isDuplicate(eventId)) {
            return new IngestionResult(transaction.getTransactionId(), DUPLICATE);
        }
 
        eventStore.save(transaction);
        rawArchive.archive(transaction);
        idempotency.markProcessed(eventId);
        eventPublisher.publish(new SalesTransactionEvent(transaction));
 
        return new IngestionResult(transaction.getTransactionId(), ACCEPTED);
    }
}
```
 
### REST Controller
 
```java
// SalesIngestionController.java
@RestController
@RequestMapping("/v1/ingest")
public class SalesIngestionController {
 
    private final SalesEventPort salesEventPort;  // injected by interface
 
    @PostMapping("/events")
    public ResponseEntity<?> ingestEvent(@Valid @RequestBody SalesEventRequest request) {
        SalesTransaction transaction = SalesEventMapper.toDomain(request);
        IngestionResult result = salesEventPort.ingest(transaction);
 
        return switch (result.status()) {
            case ACCEPTED -> ResponseEntity.accepted()
                .body(Map.of("transactionId", result.transactionId(), "status", "ACCEPTED"));
            case DUPLICATE -> ResponseEntity.status(409)
                .body(Map.of("errorCode", "DUPLICATE_EVENT", "transactionId", result.transactionId()));
        };
    }
}
```
 
### Database Schema (Spring Data JDBC)
 
```java
// SalesEventRepository.java — implements EventStorePort
@Repository
public class SalesEventRepository implements EventStorePort {
 
    private final JdbcTemplate jdbcTemplate;
 
    @Override
    public void save(SalesTransaction transaction) {
        jdbcTemplate.update("""
            INSERT INTO sales.sales_events
              (transaction_id, event_date, store_id, sku_id, dc_id,
               quantity, unit_price, channel, event_timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            transaction.getTransactionId(),
            transaction.getEventTimestamp().atZone(ZoneOffset.UTC).toLocalDate(),
            transaction.getStoreId(),
            transaction.getSkuId(),
            transaction.getDcId(),
            transaction.getQuantity(),
            transaction.getUnitPrice(),
            transaction.getChannel().name(),
            Timestamp.from(transaction.getEventTimestamp())
        );
    }
}
```
 
---
 
## IMS — Inventory Management Service
 
Location: `services/ims/`
 
### Package Structure
 
```
com.smartretail.ims/
├── ImsApplication.java
├── domain/
│   ├── model/
│   │   ├── InventoryPosition.java         ← aggregate root
│   │   └── StockAlert.java                ← entity
│   └── usecase/
│       └── InventoryUpdateUseCase.java
├── port/
│   ├── inbound/
│   │   └── InventoryUpdatePort.java       ← interface (called by SQS adapter)
│   └── outbound/
│       ├── InventoryRepositoryPort.java
│       └── AlertPublisherPort.java
└── adapter/
    ├── inbound/
    │   ├── sqs/
    │   │   └── SalesSqsListener.java      ← @SqsListener
    │   └── rest/
    │       └── InventoryController.java
    └── outbound/
        ├── persistence/
        │   └── InventoryRepository.java
        └── event/
            └── EventBridgeAlertPublisher.java
```
 
### Domain Model
 
```java
// InventoryPosition.java — NO AWS IMPORTS
public class InventoryPosition {
    private UUID positionId;
    private String skuId;
    private String dcId;
    private int onHand;
    private int inTransit;
    private int reserved;
    private int reorderPoint;
    private int safetyStock;
    private int version;  // optimistic locking
 
    public int getAvailableToPromise() {
        return onHand - reserved;
    }
 
    public boolean isLowStock() {
        return getAvailableToPromise() < reorderPoint;
    }
 
    public AlertSeverity computeSeverity() {
        int atp = getAvailableToPromise();
        if (atp <= 0) return AlertSeverity.CRITICAL;
        if (atp < (reorderPoint * 0.5)) return AlertSeverity.HIGH;
        return AlertSeverity.MEDIUM;
    }
 
    public enum AlertSeverity { CRITICAL, HIGH, MEDIUM }
}
```
 
### Inventory Update Use Case
 
```java
// InventoryUpdateUseCase.java — NO AWS IMPORTS
@Component
public class InventoryUpdateUseCase implements InventoryUpdatePort {
 
    @Override
    @Transactional
    public void processSalesEvent(SalesTransactionEvent event) {
        InventoryPosition position = inventoryRepo
            .findBySkuAndDc(event.skuId(), event.dcId())
            .orElseThrow(() -> new PositionNotFoundException(event.skuId(), event.dcId()));
 
        // Decrement with optimistic lock
        int updated = inventoryRepo.decrementOnHand(
            position.getPositionId(),
            event.quantity(),
            position.getVersion()
        );
 
        if (updated == 0) {
            throw new OptimisticLockException("Concurrent modification on position " + position.getPositionId());
        }
 
        // Reload after update
        position = inventoryRepo.findById(position.getPositionId()).orElseThrow();
 
        if (position.isLowStock()) {
            StockAlert alert = StockAlert.create(
                position,
                AlertType.LOW_STOCK,
                position.computeSeverity()
            );
            inventoryRepo.saveAlert(alert);
            alertPublisher.publish(new InventoryAlertEvent(alert, position));
        }
    }
}
```
 
### Optimistic Lock SQL
 
```java
// InventoryRepository.java
public int decrementOnHand(UUID positionId, int quantity, int currentVersion) {
    return jdbcTemplate.update("""
        UPDATE inventory.inventory_positions
        SET on_hand = on_hand - ?,
            last_updated_at = NOW(),
            version = version + 1
        WHERE position_id = ?
          AND version = ?
          AND on_hand >= ?
        """,
        quantity, positionId, currentVersion, quantity
    );
}
```
 
### SQS Listener
 
```java
// SalesSqsListener.java
@Component
public class SalesSqsListener {
 
    private final InventoryUpdatePort inventoryUpdatePort;
 
    @SqsListener("${smartretail.sqs.ims-sales-queue-url}")
    public void onSalesEvent(SalesTransactionEvent event) {
        try {
            inventoryUpdatePort.processSalesEvent(event);
        } catch (OptimisticLockException e) {
            // Retry — SQS visibility timeout will re-deliver
            throw e;
        } catch (Exception e) {
            log.error("Failed to process sales event: {}", event.transactionId(), e);
            throw e;  // SQS will retry, then DLQ
        }
    }
}
```
 
---
 
## RE — Replenishment Engine
 
Location: `services/re/`
 
### Package Structure
 
```
com.smartretail.re/
├── ReApplication.java
├── domain/
│   ├── model/
│   │   ├── PurchaseOrder.java             ← aggregate root
│   │   ├── PurchaseOrderLineItem.java     ← entity
│   │   ├── ReplenishmentRule.java         ← entity
│   │   └── WorkflowStatus.java            ← enum (ALL STATES)
│   └── usecase/
│       ├── GeneratePurchaseOrderUseCase.java
│       └── ApprovalWorkflowUseCase.java
├── port/
│   ├── inbound/
│   │   ├── AlertProcessingPort.java       ← SQS consumer calls this
│   │   └── ApprovalPort.java              ← REST controller calls this
│   └── outbound/
│       ├── ReplenishmentRepositoryPort.java
│       └── PurchaseOrderEventPort.java
└── adapter/
    ├── inbound/
    │   ├── sqs/
    │   │   └── InventoryAlertFifoListener.java
    │   └── rest/
    │       └── ReplenishmentController.java
    └── outbound/
        ├── persistence/
        │   └── ReplenishmentRepository.java
        └── event/
            └── EventBridgePurchaseOrderPublisher.java
```
 
### WorkflowStatus Enum
 
```java
public enum WorkflowStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    EXPIRED,
    DISPATCHED,
    ACKNOWLEDGED,
    SHIPPED,
    PARTIAL_DELIVERY,
    COMPLETED,
    CANCELLED;
 
    public boolean canTransitionTo(WorkflowStatus target) {
        return switch (this) {
            case DRAFT -> target == APPROVED || target == PENDING_APPROVAL;
            case PENDING_APPROVAL -> target == APPROVED || target == REJECTED || target == EXPIRED;
            case APPROVED -> target == DISPATCHED;
            case DISPATCHED -> target == ACKNOWLEDGED || target == OVERDUE;
            // ... etc
            default -> false;
        };
    }
}
```
 
### Generate PO Use Case
 
```java
// GeneratePurchaseOrderUseCase.java — NO AWS IMPORTS
@Component
public class GeneratePurchaseOrderUseCase implements AlertProcessingPort {
 
    @Override
    @Transactional
    public void processAlert(InventoryAlertEvent alert) {
        ReplenishmentRule rule = replenishmentRepo
            .findRule(alert.skuId(), alert.dcId())
            .orElse(null);
 
        if (rule == null) {
            log.warn("No replenishment rule for SKU={} DC={}", alert.skuId(), alert.dcId());
            return;
        }
 
        int quantity = Math.max(
            rule.getReorderPoint() - alert.currentOnHand(),
            rule.getMoq()
        );
        BigDecimal totalValue = rule.getCostPerUnit().multiply(BigDecimal.valueOf(quantity));
 
        WorkflowStatus initialStatus = totalValue.compareTo(rule.getAutoApproveThreshold()) <= 0
            ? WorkflowStatus.APPROVED
            : WorkflowStatus.PENDING_APPROVAL;
 
        PurchaseOrder po = PurchaseOrder.create(rule, quantity, totalValue, initialStatus, alert.alertId());
        replenishmentRepo.save(po);
        poEventPublisher.publish(new PurchaseOrderEvent(po));
    }
}
```
 
### Approval Use Case
 
```java
// ApprovalWorkflowUseCase.java — NO AWS IMPORTS
@Component
public class ApprovalWorkflowUseCase implements ApprovalPort {
 
    @Override
    @Transactional
    public ApprovalResult approve(UUID poId, String approvedBy, String idempotencyKey) {
        PurchaseOrder po = replenishmentRepo.findById(poId)
            .orElseThrow(() -> new PoNotFoundException(poId));
 
        if (po.getWorkflowStatus() != WorkflowStatus.PENDING_APPROVAL) {
            throw new InvalidStatusTransitionException(
                "PO cannot be approved from status " + po.getWorkflowStatus() +
                ". Status must be PENDING_APPROVAL.",
                po.getWorkflowStatus()
            );
        }
 
        int updated = replenishmentRepo.updateStatus(
            poId,
            WorkflowStatus.APPROVED,
            approvedBy,
            Instant.now(),
            po.getVersion()  // optimistic lock
        );
 
        if (updated == 0) {
            throw new OptimisticLockException("Concurrent modification on PO " + poId);
        }
 
        PurchaseOrder updatedPo = replenishmentRepo.findById(poId).orElseThrow();
        poEventPublisher.publish(new PurchaseOrderEvent(updatedPo));
 
        return new ApprovalResult(poId, WorkflowStatus.APPROVED, updatedPo.getVersion());
    }
}
```
 
### Optimistic Lock SQL for PO Update
 
```java
public int updateStatus(UUID poId, WorkflowStatus newStatus,
                         String actor, Instant actionAt, int currentVersion) {
    return jdbcTemplate.update("""
        UPDATE replenishment.purchase_orders
        SET workflow_status = ?,
            approved_by = CASE WHEN ? = 'APPROVED' THEN ? ELSE approved_by END,
            approved_at = CASE WHEN ? = 'APPROVED' THEN ? ELSE approved_at END,
            rejected_by = CASE WHEN ? = 'REJECTED' THEN ? ELSE rejected_by END,
            rejected_at = CASE WHEN ? = 'REJECTED' THEN ? ELSE rejected_at END,
            version = version + 1,
            updated_at = NOW()
        WHERE po_id = ?
          AND version = ?
        """,
        newStatus.name(),
        newStatus.name(), actor,
        newStatus.name(), Timestamp.from(actionAt),
        newStatus.name(), actor,
        newStatus.name(), Timestamp.from(actionAt),
        poId, currentVersion
    );
}
```
 
### REST Controller
 
```java
@RestController
@RequestMapping("/v1/replenishment")
public class ReplenishmentController {
 
    @PostMapping("/orders/{poId}/approve")
    public ResponseEntity<?> approve(
            @PathVariable UUID poId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("Authorization") String bearerToken,
            @RequestBody(required = false) ApproveRequest request) {
 
        // Extract JWT claims and validate SC_PLANNER or ADMIN role
        JwtClaims claims = jwtValidator.validate(bearerToken);
        if (!claims.hasRole("SC_PLANNER") && !claims.hasRole("ADMIN")) {
            return ResponseEntity.status(403)
                .body(errorResponse("UNAUTHORIZED", "SC_PLANNER or ADMIN role required"));
        }
 
        try {
            ApprovalResult result = approvalPort.approve(poId, claims.sub(), idempotencyKey);
            return ResponseEntity.ok(Map.of(
                "poId", poId,
                "workflowStatus", result.status(),
                "approvedBy", claims.sub(),
                "approvedAt", Instant.now(),
                "version", result.version()
            ));
        } catch (InvalidStatusTransitionException e) {
            return ResponseEntity.status(409).body(errorResponse(
                "INVALID_STATUS_TRANSITION", e.getMessage(),
                Map.of("currentStatus", e.getCurrentStatus())
            ));
        }
    }
}
```
 
---
 
## ARS — Analytics & Reporting Service
 
Location: `services/ars/`
 
### Package Structure
 
```
com.smartretail.ars/
├── ArsApplication.java
├── domain/
│   ├── model/
│   │   ├── DashboardPayload.java           ← value object (derived, not stored)
│   │   ├── StoreManagerDashboard.java
│   │   ├── ScPlannerDashboard.java
│   │   ├── ExecutiveDashboard.java
│   │   └── SupplierPerformanceDashboard.java
│   └── usecase/
│       ├── StoreManagerDashboardUseCase.java
│       ├── ScPlannerDashboardUseCase.java
│       ├── ExecutiveDashboardUseCase.java
│       └── SupplierPerformanceUseCase.java
├── port/
│   ├── inbound/
│   │   └── DashboardPort.java
│   └── outbound/
│       ├── SalesReadPort.java
│       ├── ForecastReadPort.java
│       ├── InventoryReadPort.java
│       ├── ReplenishmentReadPort.java
│       ├── SupplierReadPort.java
│       └── PromotionsReadPort.java
└── adapter/
    ├── inbound/
    │   └── rest/
    │       └── DashboardController.java
    └── outbound/
        └── persistence/
            ├── SalesReadRepository.java    ← implements SalesReadPort
            ├── ForecastReadRepository.java ← reads forecasting schema (no schema ownership)
            ├── InventoryReadRepository.java
            ├── ReplenishmentReadRepository.java
            └── SupplierReadRepository.java ← reads supplier schema (no schema ownership)
```
 
### Key Implementation Rule — No Cross-Schema JOINs
 
```java
// StoreManagerDashboardUseCase.java — CORRECT (separate queries)
@Component
public class StoreManagerDashboardUseCase {
 
    public StoreManagerDashboard build(String dcId) {
        // Execute in parallel using CompletableFuture
        CompletableFuture<InventorySummary> inventoryFuture =
            CompletableFuture.supplyAsync(() -> inventoryRepo.getSummaryByDc(dcId));
 
        CompletableFuture<List<StockAlert>> alertsFuture =
            CompletableFuture.supplyAsync(() -> inventoryRepo.getActiveAlerts(dcId));
 
        CompletableFuture<ForecastSummary> forecastFuture =
            CompletableFuture.supplyAsync(() -> forecastRepo.getLatestByDc(dcId));
 
        CompletableFuture<Integer> pendingPosFuture =
            CompletableFuture.supplyAsync(() -> replenishmentRepo.countPendingByDc(dcId));
 
        // Wait for all
        CompletableFuture.allOf(inventoryFuture, alertsFuture, forecastFuture, pendingPosFuture).join();
 
        Instant dataFreshness = Stream.of(
            inventoryFuture.join().lastUpdated(),
            alertsFuture.join().stream().map(StockAlert::raisedAt).min(Instant::compareTo).orElse(Instant.now()),
            forecastFuture.join().createdAt()
        ).min(Instant::compareTo).orElse(Instant.now());
 
        return StoreManagerDashboard.builder()
            .dcId(dcId)
            .inventory(inventoryFuture.join())
            .alerts(alertsFuture.join())
            .forecastSummary(forecastFuture.join())
            .pendingReplenishmentOrders(pendingPosFuture.join())
            .dataFreshness(dataFreshness)
            .build();
    }
}
```
 
### ARS Outbound Repositories — Cross-Schema Read Note

`ForecastReadRepository` reads the `forecasting` schema and `SupplierReadRepository`
reads the `supplier` schema directly — this is by design. ARS is a read-only aggregator
with no schema ownership that reads across schemas via separate queries merged in Java.

### Supplier Performance — Application-Level Join


 
```java
// SupplierPerformanceUseCase.java
public List<SupplierPerformance> buildScorecard() {
    // Step 1: Get supplier names from supplier schema
    Map<UUID, String> supplierNames = supplierRepo.getAllActiveSuppliers();
 
    // Step 2: Get PO metrics from replenishment schema
    Map<UUID, PoMetrics> poMetrics = replenishmentRepo.getPoMetricsBySupplierId();
 
    // Step 3: Get shipment metrics from supplier schema
    Map<UUID, ShipmentMetrics> shipmentMetrics = supplierRepo.getShipmentMetricsBySupplierId();
 
    // Step 4: Merge in Java — NOT SQL
    return supplierNames.keySet().stream()
        .map(supplierId -> SupplierPerformance.builder()
            .supplierId(supplierId)
            .supplierName(supplierNames.get(supplierId))
            .poMetrics(poMetrics.getOrDefault(supplierId, PoMetrics.empty()))
            .shipmentMetrics(shipmentMetrics.getOrDefault(supplierId, ShipmentMetrics.empty()))
            .build()
        )
        .sorted(Comparator.comparing(sp -> sp.getOnTimeDeliveryRate(), Comparator.reverseOrder()))
        .collect(Collectors.toList());
}
```

---

## DFS — Demand Forecasting Service

Location: `services/dfs/`
Main class: `com.smartretail.dfs.DfsApplication`
Port: 8084
Schema: `forecasting` (read-only)

### Responsibilities

Read-only service exposing demand forecast probability bands (P10/P50/P90) per SKU x DC
from the latest COMPLETED forecast run.

### Package Structure

```
com.smartretail.dfs/
├── DfsApplication.java
├── config/
│   └── SecurityConfig.java
├── domain/
│   ├── model/
│   │   └── ForecastData.java
│   └── usecase/
│       └── ForecastQueryUseCase.java
├── port/
│   ├── inbound/
│   │   └── ForecastQueryPort.java
│   └── outbound/
│       └── ForecastReadPort.java
└── adapter/
    ├── inbound/
    │   └── rest/
    │       ├── ForecastController.java
    │       └── GlobalExceptionHandler.java
    └── outbound/
        └── persistence/
            └── ForecastRepository.java   ← queries forecasting schema only
```

### Key Rule

All SQL runs within the `forecasting` schema only. No joins to other schemas.
The `ForecastRepository` queries `forecasting.demand_forecasts` joined with
`forecasting.forecast_runs` — both within the same schema.

---

## SUP — Supplier Service

Location: `services/sup/`
Main class: `com.smartretail.sup.SupApplication`
Port: 8085
Schema: `supplier` (read-only)

### Responsibilities

Read-only service exposing supplier PO tracking with shipment progress.
All queries run within the `supplier` schema — no cross-schema SQL joins.

### Package Structure

```
com.smartretail.sup/
├── SupApplication.java
├── config/
│   └── SecurityConfig.java
├── domain/
│   ├── model/
│   │   └── SupplierOrderList.java
│   └── usecase/
│       └── SupplierOrderQueryUseCase.java
├── port/
│   ├── inbound/
│   │   └── SupplierOrderQueryPort.java
│   └── outbound/
│       └── SupplierOrderReadPort.java
└── adapter/
    ├── inbound/
    │   └── rest/
    │       ├── SupplierOrderController.java
    │       └── GlobalExceptionHandler.java
    └── outbound/
        └── persistence/
            └── SupplierOrderRepository.java   ← queries supplier schema only
```

### Key Rule

All SQL runs within the `supplier` schema only. The `SupplierOrderRepository`
joins `supplier.supplier_pos`, `supplier.supplier_records`, and
`supplier.shipment_updates` — all within the same schema. EXCEPTION rows are
returned first, then sorted by `eta` ascending.
