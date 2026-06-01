---
applyTo: "**/*.java"
---

# Java Instructions — SmartRetail

## Hexagonal architecture package placement
- Domain model, ports, and use-case implementations live under `domain/`
- REST controllers live under `adapter/inbound/rest/`
- Repositories, SQS listeners, and AWS SDK calls live under `adapter/outbound/`
- AWS SDK imports (`software.amazon.*`, `com.amazonaws.*`) are **forbidden** in `domain/` packages

## DI: constructor injection only
```java
// CORRECT
@Service
public class MyUseCase implements MyPort {
    private final DependencyPort dep;
    public MyUseCase(DependencyPort dep) { this.dep = dep; }
}

// WRONG — never use @Autowired on a field
@Autowired private DependencyPort dep;
```

## Value objects: Java records with compact constructors
```java
public record PurchaseOrder(UUID poId, String skuId, int quantity) {
    public PurchaseOrder {
        Objects.requireNonNull(poId, "poId");
        Objects.requireNonNull(skuId, "skuId");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
    }
}
```

## Result types: sealed interfaces
```java
public sealed interface ReplenishmentResult
    permits ReplenishmentResult.AutoApproved, ReplenishmentResult.PendingApproval {
    record AutoApproved(UUID poId) implements ReplenishmentResult {}
    record PendingApproval(UUID poId) implements ReplenishmentResult {}
}
```

## SQL: text blocks, schema-qualified names
```java
private static final String INSERT_PO_SQL = """
    INSERT INTO replenishment.purchase_orders
        (po_id, sku_id, dc_id, quantity, workflow_status, version)
    VALUES
        (:poId, :skuId, :dcId, :quantity, :workflowStatus, 0)
    """;
```

## Optimistic locking — always include version check
```java
// CORRECT
private static final String UPDATE_PO_STATUS_SQL = """
    UPDATE replenishment.purchase_orders
       SET workflow_status = :newStatus, version = version + 1, updated_at = NOW()
     WHERE po_id = :poId AND version = :expectedVersion
    """;

// WRONG — missing version check
UPDATE replenishment.purchase_orders SET workflow_status = :status WHERE po_id = :poId
```

## Never return null from public methods — use Optional
```java
// CORRECT
Optional<PurchaseOrder> findById(UUID poId);

// WRONG
PurchaseOrder findById(UUID poId); // may return null
```

## Exception handling
Domain exceptions extend `RuntimeException`. Define in `domain/model/exception/`.
Map to HTTP status codes in a `@RestControllerAdvice` class — never in controllers.

## Generated DTOs
Never write request/response DTO classes manually. Controller method signatures
implement the generated OpenAPI server interfaces from `target/generated-sources/openapi/`.
