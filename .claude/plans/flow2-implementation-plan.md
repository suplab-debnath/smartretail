# Flow 2 Implementation Plan: Inventory Alert → RE Auto-Approve → RDS State Transition

## Context

Flow 1 is complete. It ends with IMS publishing an `InventoryAlertEvent` to EventBridge
(`source: smartretail.ims`, `detail-type: InventoryAlertEvent`), which is routed by an
existing EventBridge rule into the `smartretail-re-alert-{env}.fifo` SQS FIFO queue
(message group = `dcId`). The RE service at `services/re/` is currently a stub (bare `pom.xml`
only). This plan builds the full hexagonal RE service and wires it into the infrastructure.

**Two sub-scenarios to support:**
- **2a – Auto-approve:** `totalValue ≤ auto_approve_threshold` → INSERT with `workflow_status = APPROVED`
- **2b – Manual approval:** `totalValue > auto_approve_threshold` → INSERT with `workflow_status = PENDING_APPROVAL`

Both scenarios publish a `PurchaseOrderEvent` to EventBridge after the INSERT.

---

## Pre-Reading (load before coding)

```
Agent:  java-standards  (.claude/standards/java.md)
Spec:   docs/FLOWS.md → Flow 2
Schema: docs/SCHEMAS.md → replenishment schema
API:    docs/API_CONTRACTS.md → RE service
Spec:   docs/SERVICE_SPECS.md → RE service hexagonal package structure
```

---

## Critical Constraints

| # | Rule | How it applies to RE |
|---|------|----------------------|
| C1 | No cross-schema SQL joins | RE reads `replenishment.*` only; quantity formula uses alert event payload fields (`thresholdValue`, `actualValue`) — never joins `inventory.*` |
| C2 | Optimistic locking on all PO UPDATEs | `WHERE version = :currentVersion`; returns row count 0 → `OptimisticLockException` → HTTP 409 |
| C3 | `PENDING_APPROVAL` is the pre-approval state | `canApprove()` returns false for any status ≠ `PENDING_APPROVAL`; approve on `DRAFT` or `APPROVED` → 409 |
| C4 | Domain has zero AWS imports | ArchUnit enforces `software.amazon.*` banned from `domain..` and `port..` packages |
| C5 | Contract-first, no hand-written DTOs | Write `re-api.yaml` first, run `mvn generate-sources`, implement generated interfaces only |
| C6 | `version = 0` on INSERT | Hardcoded literal in SQL; do not pass as parameter |
| C7 | REJECTED for planner rejection | Never use `CANCELLED` for a planner action |

---

## Seed Data Reference (for test scenarios)

| Rule | SKU | DC | MOQ | cost_per_unit | auto_approve_threshold | Expected status |
|------|-----|----|-----|---------------|----------------------|-----------------|
| Rule 1 | SKU-BEV-001 | DC-LONDON | 100 | 8.50 | 50 000.00 | **APPROVED** (850 ≪ 50 000) |
| Rule 2 | SKU-BEV-003 | DC-LONDON | 50 | 75.00 | 0.00 | **PENDING_APPROVAL** (always) |

Quantity formula: `quantity = max(thresholdValue - actualValue, moq)`
(thresholdValue = reorderPoint, actualValue = onHand from alert payload)

---

## Implementation Checklist

Work through steps in order. Each step compiles independently.

---

### STEP 1 — OpenAPI Spec (prerequisite for all Java compilation)

- [ ] **CREATE** `openapi/re-api.yaml`

```yaml
# Endpoints to define:
GET  /v1/replenishment/orders            operationId: listPurchaseOrders
GET  /v1/replenishment/orders/{poId}     operationId: getPurchaseOrder
POST /v1/replenishment/orders/{poId}/approve   operationId: approvePurchaseOrder
POST /v1/replenishment/orders/{poId}/reject    operationId: rejectPurchaseOrder

# Key schemas:
WorkflowStatus: enum [DRAFT, PENDING_APPROVAL, APPROVED, REJECTED,
                       EXPIRED, DISPATCHED, ACKNOWLEDGED, SHIPPED,
                       PARTIAL_DELIVERY, COMPLETED, CANCELLED]
PoLineItem:     { lineId, skuId, quantity, unitCost, lineTotal }
PurchaseOrder:  { poId, ruleId, supplierId, skuId, dcId, quantity,
                  totalValue, workflowStatus, version, approvedBy,
                  approvedAt, rejectedBy, rejectedAt, rejectionReason,
                  alertId, createdAt, updatedAt, lineItems? }
PurchaseOrderPage: { orders[], page, size, totalElements }
ApproveRequest: { version: integer (required) }
RejectRequest:  { version: integer (required), rejectionReason: string (required) }

# Headers:
X-Idempotency-Key: UUID, required on approve + reject

# Security: BearerAuth JWT on all endpoints
# Server: http://localhost:8082 (local)
```

---

### STEP 2 — Update `services/re/pom.xml`

- [ ] Add dependencies (mirror IMS `pom.xml`):
  - `spring-boot-starter-web`, `spring-boot-starter-validation`
  - `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`
  - `spring-boot-starter-data-jdbc`, `spring-boot-starter-actuator`
  - `spring-cloud-aws-starter-sqs`, `spring-cloud-aws-starter-parameter-store`
  - `software.amazon.awssdk:eventbridge`
  - `springdoc-openapi-starter-webmvc-ui`
  - `jackson-databind-nullable:0.2.6`
  - `net.logstash.logback:logstash-logback-encoder`
  - Test: `archunit-junit5`, `testcontainers:postgresql`, `spring-boot-starter-test`
- [ ] Add `openapi-generator-maven-plugin` execution:
  - `inputSpec`: `${project.basedir}/../../openapi/re-api.yaml`
  - `apiPackage`: `com.smartretail.re.adapter.inbound.rest.generated.api`
  - `modelPackage`: `com.smartretail.re.adapter.inbound.rest.generated.model`
  - Same `configOptions` as IMS (useSpringBoot3, interfaceOnly=true, etc.)
- [ ] Add `build-helper-maven-plugin` to add generated-sources to compile path
- [ ] Remove `<skip>true</skip>` from `spring-boot-maven-plugin`
- [ ] Add `jacoco-maven-plugin`

---

### STEP 3 — Domain Models

All files under `services/re/src/main/java/com/smartretail/re/`

- [ ] **`domain/model/WorkflowStatus.java`** — enum with all states + helpers:
  ```java
  public boolean canApprove() { return this == PENDING_APPROVAL; }
  public boolean canReject()  { return this == PENDING_APPROVAL; }
  ```

- [ ] **`domain/model/ReplenishmentRule.java`** — plain Java class:
  ```
  ruleId (UUID), supplierId, skuId, dcId,
  leadTimeDays (int), moq (int),
  costPerUnit (BigDecimal), autoApproveThreshold (BigDecimal), active (boolean)
  ```

- [ ] **`domain/model/PoLineItem.java`**:
  ```
  lineId (UUID), poId (UUID), skuId,
  quantity (int), unitCost (BigDecimal), lineTotal (BigDecimal)
  ```

- [ ] **`domain/model/PurchaseOrder.java`** — include factory method:
  ```java
  public static PurchaseOrder create(ReplenishmentRule rule, int quantity,
      BigDecimal totalValue, WorkflowStatus status, UUID alertId) { ... }
  // Sets poId=UUID.randomUUID(), version=0, createdAt/updatedAt=Instant.now()
  ```

- [ ] **`domain/model/InventoryAlertEventDto.java`** — record with `@JsonIgnoreProperties(ignoreUnknown=true)`:
  ```java
  record InventoryAlertEventDto(
      String alertId, String positionId, String skuId, String dcId,
      String alertType, String severity,
      int thresholdValue,  // = reorderPoint
      int actualValue      // = onHand at alert time
  ) {}
  ```

- [ ] **`domain/model/exception/ReplenishmentRuleNotFoundException.java`**
- [ ] **`domain/model/exception/PurchaseOrderNotFoundException.java`**
- [ ] **`domain/model/exception/InvalidStatusTransitionException.java`** — include `getCurrentStatus()` getter for 409 response body
- [ ] **`domain/model/exception/OptimisticLockException.java`** — identical to IMS version

---

### STEP 4 — Port Interfaces

- [ ] **`port/inbound/ProcessInventoryAlertPort.java`**:
  ```java
  void processInventoryAlert(InventoryAlertEventDto alert);
  ```

- [ ] **`port/inbound/ApprovePurchaseOrderPort.java`**:
  ```java
  PurchaseOrder approve(UUID poId, int currentVersion, String approvedBy);
  ```

- [ ] **`port/inbound/RejectPurchaseOrderPort.java`**:
  ```java
  PurchaseOrder reject(UUID poId, int currentVersion, String rejectedBy, String reason);
  ```

- [ ] **`port/outbound/ReplenishmentRepositoryPort.java`**:
  ```java
  Optional<ReplenishmentRule> findActiveRule(String skuId, String dcId);
  void savePurchaseOrder(PurchaseOrder po);
  void saveLineItem(PoLineItem item);
  // Optimistic lock UPDATE — returns row count (0 = conflict)
  int updateStatus(UUID poId, WorkflowStatus newStatus, int currentVersion,
                   String approvedBy, Instant approvedAt,
                   String rejectedBy, Instant rejectedAt, String rejectionReason);
  Optional<PurchaseOrder> findById(UUID poId);
  List<PurchaseOrder> findOrders(String status, String dcId, String skuId, int page, int size);
  long countOrders(String status, String dcId, String skuId);
  List<PoLineItem> findLineItemsByPoId(UUID poId);
  ```

- [ ] **`port/outbound/PurchaseOrderEventPublisherPort.java`**:
  ```java
  void publishPurchaseOrderEvent(PurchaseOrder po);
  ```

---

### STEP 5 — Use Cases

- [ ] **`domain/usecase/ProcessInventoryAlertUseCase.java`** (`@Service @Transactional`):
  ```
  1. findActiveRule(skuId, dcId) → orElseThrow ReplenishmentRuleNotFoundException
  2. quantity = max(event.thresholdValue() - event.actualValue(), rule.getMoq())
  3. totalValue = rule.getCostPerUnit() × quantity
  4. status = totalValue ≤ autoApproveThreshold ? APPROVED : PENDING_APPROVAL
  5. po = PurchaseOrder.create(rule, quantity, totalValue, status, alertId)
  6. savePurchaseOrder(po)
  7. saveLineItem(new PoLineItem(UUID.randomUUID(), po.getPoId(), skuId,
                                  quantity, rule.getCostPerUnit(), totalValue))
  8. publishPurchaseOrderEvent(po)
  9. Log: "PurchaseOrderEvent published poId={} status={}"
  ```

- [ ] **`domain/usecase/ApprovePurchaseOrderUseCase.java`** (`@Service @Transactional`):
  ```
  1. po = findById(poId) → orElseThrow
  2. if (!po.getWorkflowStatus().canApprove()) throw InvalidStatusTransitionException
  3. rows = updateStatus(poId, APPROVED, currentVersion, approvedBy, now(), null, null, null)
  4. if (rows == 0) throw OptimisticLockException
  5. return findById(poId) (fresh read after update)
  6. publishPurchaseOrderEvent(updated po)
  ```

- [ ] **`domain/usecase/RejectPurchaseOrderUseCase.java`** (`@Service @Transactional`):
  ```
  1. po = findById(poId) → orElseThrow
  2. if (!po.getWorkflowStatus().canReject()) throw InvalidStatusTransitionException
  3. rows = updateStatus(poId, REJECTED, currentVersion, null, null, rejectedBy, now(), reason)
  4. if (rows == 0) throw OptimisticLockException
  5. return findById(poId) + publishPurchaseOrderEvent(updated po)
  ```

---

### STEP 6 — Outbound Adapters

- [ ] **`adapter/outbound/persistence/ReplenishmentRepository.java`** — implements `ReplenishmentRepositoryPort` via `NamedParameterJdbcTemplate`:

  Key SQL (all use `replenishment.*` — no cross-schema joins):

  ```sql
  -- findActiveRule
  SELECT rule_id, supplier_id, sku_id, dc_id, lead_time_days, moq,
         cost_per_unit, auto_approve_threshold
  FROM replenishment.replenishment_rules
  WHERE sku_id = :skuId AND dc_id = :dcId AND active = true
  LIMIT 1

  -- savePurchaseOrder (version hardcoded to 0)
  INSERT INTO replenishment.purchase_orders
    (po_id, rule_id, supplier_id, sku_id, dc_id, quantity, total_value,
     workflow_status, version, alert_id, created_at, updated_at)
  VALUES (:poId, :ruleId, :supplierId, :skuId, :dcId, :quantity, :totalValue,
          :workflowStatus, 0, :alertId, :createdAt, :updatedAt)

  -- saveLineItem
  INSERT INTO replenishment.po_line_items
    (line_id, po_id, sku_id, quantity, unit_cost, line_total)
  VALUES (:lineId, :poId, :skuId, :quantity, :unitCost, :lineTotal)

  -- updateStatus (CRITICAL: WHERE version = :currentVersion)
  UPDATE replenishment.purchase_orders
  SET workflow_status  = :newStatus,
      version          = version + 1,
      approved_by      = :approvedBy,
      approved_at      = :approvedAt,
      rejected_by      = :rejectedBy,
      rejected_at      = :rejectedAt,
      rejection_reason = :rejectionReason,
      updated_at       = NOW()
  WHERE po_id = :poId AND version = :currentVersion

  -- findOrders (dynamic WHERE via MapSqlParameterSource)
  SELECT ... FROM replenishment.purchase_orders
  WHERE 1=1
    [AND workflow_status = :status]
    [AND dc_id = :dcId]
    [AND sku_id = :skuId]
  ORDER BY created_at DESC
  LIMIT :size OFFSET :offset

  -- findLineItemsByPoId
  SELECT line_id, po_id, sku_id, quantity, unit_cost, line_total
  FROM replenishment.po_line_items WHERE po_id = :poId
  ```

- [ ] **`adapter/outbound/event/EventBridgePurchaseOrderPublisher.java`** — mirrors `EventBridgeAlertPublisher` (IMS):
  - `EVENT_SOURCE = "smartretail.re"`
  - `DETAIL_TYPE  = "PurchaseOrderEvent"`
  - Detail map: `{ poId, ruleId, supplierId, skuId, dcId, quantity, totalValue, workflowStatus, alertId, createdAt }`
  - Bus name from `${smartretail.eventbridge.bus-name}`

---

### STEP 7 — Config Beans

- [ ] **`config/AwsClientsConfig.java`** — `@Profile("!local")` — creates `EventBridgeClient` bean (identical to IMS)
- [ ] **`config/LocalStackConfig.java`** — `@Profile("local")` — overrides `EventBridgeClient` to LocalStack endpoint `${smartretail.localstack.endpoint}` (identical to IMS)
- [ ] **`config/SecurityConfig.java`**:
  - Local profile: `permitAll()` on all requests
  - AWS profile: JWT resource server; require roles `SC_PLANNER` or `ADMIN` on `POST .../approve` and `POST .../reject`; `authenticated()` for GET endpoints

---

### STEP 8 — Inbound Adapters

- [ ] **`adapter/inbound/sqs/AlertSqsListener.java`** — mirrors `SalesSqsListener` (IMS):
  - `@SqsListener("${smartretail.sqs.re-alert-queue-url}")`
  - Unwrap EventBridge envelope → extract `detail` → deserialize to `InventoryAlertEventDto`
  - MDC: `traceId`, `service=RE`, `eventType=InventoryAlertEvent`, `skuId`, `dcId`
  - Call `processInventoryAlertPort.processInventoryAlert(event)`
  - On exception: log + rethrow (SQS retry → DLQ after 3 attempts)

- [ ] **`adapter/inbound/rest/ReplenishmentResponseMapper.java`** — maps domain → generated API model:
  - `PurchaseOrder` → generated `PurchaseOrderModel`
  - `PoLineItem` → generated `PoLineItemModel`
  - `Instant` → `OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)`

- [ ] **`adapter/inbound/rest/GlobalExceptionHandler.java`** — `@RestControllerAdvice`:
  | Exception | HTTP | errorCode |
  |-----------|------|-----------|
  | `PurchaseOrderNotFoundException` | 404 | `NOT_FOUND` |
  | `ReplenishmentRuleNotFoundException` | 404 | `NOT_FOUND` |
  | `InvalidStatusTransitionException` | 409 | `INVALID_STATUS_TRANSITION` (include `currentStatus` in details) |
  | `OptimisticLockException` | 409 | `CONCURRENT_MODIFICATION` |
  | `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` |
  | `AccessDeniedException` | 403 | `FORBIDDEN` |
  | `Exception` (catch-all) | 500 | `INTERNAL_ERROR` |

- [ ] **`adapter/inbound/rest/ReplenishmentController.java`** — implements generated `ReplenishmentOrdersApi`:
  - `listPurchaseOrders` → `repo.findOrders(...)` + `countOrders(...)`
  - `getPurchaseOrder` → `repo.findById(...)` + `repo.findLineItemsByPoId(...)`
  - `approvePurchaseOrder` → extract JWT subject for `approvedBy`, call `approvePort.approve(...)`
  - `rejectPurchaseOrder` → extract JWT subject for `rejectedBy`, call `rejectPort.reject(...)`

---

### STEP 9 — Application Entry Point & Resources

- [ ] **`ReApplication.java`**: standard `@SpringBootApplication`

- [ ] **`application.yml`**:
  ```yaml
  server.port: 8082
  spring.application.name: re
  spring.datasource.url: jdbc:postgresql://${RDS_PROXY_ENDPOINT:localhost}:5432/smartretail?currentSchema=${DB_SCHEMA:replenishment}
  spring.flyway.enabled: false
  management.endpoints.web.exposure.include: health,info,metrics
  ```

- [ ] **`application-local.yml`**:
  ```yaml
  spring.datasource:
    url: jdbc:postgresql://localhost:5432/smartretail?currentSchema=replenishment
    username: smartretail_admin
    password: local_dev_password
  spring.cloud.aws:
    region.static: us-east-1
    credentials: { access-key: test, secret-key: test }
  smartretail:
    localstack.endpoint: http://localhost:4566
    eventbridge.bus-name: smartretail-events-local
    sqs.re-alert-queue-url: http://localhost:4566/000000000000/smartretail-re-alert-local.fifo
  ```

- [ ] **`application-aws.yml`**:
  ```yaml
  spring.datasource.url: jdbc:postgresql://${RDS_PROXY_ENDPOINT}:5432/smartretail?currentSchema=replenishment
  spring.security.oauth2.resourceserver.jwt.issuer-uri: ${COGNITO_ISSUER_URI}
  smartretail:
    eventbridge.bus-name: ${EVENTBRIDGE_BUS_NAME}
    sqs.re-alert-queue-url: ${RE_ALERT_QUEUE_URL}
  ```

- [ ] **`logback-spring.xml`**: identical to IMS; change `defaultValue="re"`

---

### STEP 10 — Tests

- [ ] **`architecture/ArchitectureTest.java`** — mirrors IMS version:
  - `domainMustNotDependOnAwsSdk` — bans `software.amazon.*` from `com.smartretail.re.domain..`
  - `portsMustNotDependOnAwsSdk` — bans `software.amazon.*` from `com.smartretail.re.port..`
  - `domainMustNotDependOnSpringWeb` — bans `springframework.web.*` from domain (excluding UseCase simple names)
  - `inboundAdaptersMustNotDependOnOutboundAdapters`

- [ ] **`domain/usecase/ProcessInventoryAlertUseCaseTest.java`**:
  | Test | Setup | Assert |
  |------|-------|--------|
  | `scenario2a_autoApprove` | threshold=50000, moq=100, cost=8.50, threshVal=100, actualVal=90 | status=APPROVED, totalValue=850, `savePurchaseOrder` called, event published |
  | `scenario2b_pendingApproval` | threshold=0 | status=PENDING_APPROVAL |
  | `noRuleFound_throwsException` | `findActiveRule` returns empty | `ReplenishmentRuleNotFoundException` |
  | `moqFloor_appliedWhenGapBelowMoq` | gap=5, moq=100 | quantity=100 (moq wins) |

- [ ] **`domain/usecase/ApprovePurchaseOrderUseCaseTest.java`**:
  | Test | Assert |
  |------|--------|
  | `approve_success` | PO in `PENDING_APPROVAL`, `updateStatus` returns 1 → result is `APPROVED`, event published |
  | `approve_onDraft_throws409` | PO in `DRAFT` → `InvalidStatusTransitionException` |
  | `approve_onAlreadyApproved_throws409` | PO in `APPROVED` → `InvalidStatusTransitionException` |
  | `approve_optimisticLockFail` | `updateStatus` returns 0 → `OptimisticLockException` |

---

### STEP 11 — Infrastructure Changes

#### `infra/cdk/lib/compute-stack.ts`
- [ ] Add `public readonly reService: ecs.FargateService;`
- [ ] Add `reConfig: ServiceConfig`:
  ```typescript
  {
    name: 're', port: 8082,
    envVars: {
      ...commonEnv,
      DB_SCHEMA:            'replenishment',
      RE_ALERT_QUEUE_URL:   messaging.reAlertQueue.queueUrl,
      EVENTBRIDGE_BUS_NAME: messaging.eventBus.eventBusName,
    },
    policies: [
      // SQS: receive, delete, getAttributes, changeMessageVisibility (FIFO)
      // EventBridge: putEvents
      // RDS Proxy: rds-db:connect
    ]
  }
  ```
- [ ] Call `this.reService = this.createFargateService(reConfig, network, ecsExecutionRole, srEnv);`

#### `infra/cdk/lib/api-stack.ts`
- [ ] Add `reService` reference from compute stack
- [ ] Add `HttpServiceDiscoveryIntegration` for RE
- [ ] Add routes: `GET /v1/replenishment/{proxy+}` and `POST /v1/replenishment/{proxy+}` with JWT authorizer

#### `scripts/localstack-init.sh`
- [ ] **No changes needed** — `smartretail-re-alert-local.fifo` queue and `/smartretail/local/sqs/re-alert-queue-url` SSM param are already present

---

### STEP 12 — Run & Verify

```bash
# 1. Start infrastructure + all services
make local-up && make local-migrate && make local-seed
SPRING_PROFILES_ACTIVE=local mvn -pl services/re spring-boot:run &

# 2. Trigger Flow 1 → Flow 2 (publishes InventoryAlertEvent → RE picks it up)
make test-flow1   # must still pass ✅

# 3. Scenario 2a — Auto-Approve (SKU-BEV-001 / DC-LONDON)
python scripts/publish-pos-event.py --sku SKU-BEV-001 --dc DC-LONDON --qty 30

# Check 2a.1 — RE log: alert received
docker logs smartretail-re | grep "InventoryAlertEvent received"
# Expected: skuId=SKU-BEV-001 dcId=DC-LONDON thresholdValue=100 actualValue=90 (approx)

# Check 2a.2 — RE log: auto-approve decision
docker logs smartretail-re | grep "status=APPROVED"

# Check 2a.3 — DB: purchase_orders row with APPROVED
psql -U smartretail_admin -d smartretail -c \
  "SELECT po_id, workflow_status, version, total_value
   FROM replenishment.purchase_orders
   WHERE sku_id='SKU-BEV-001' ORDER BY created_at DESC LIMIT 1;"
# Expected: workflow_status=APPROVED, version=0, total_value=850.00

# Check 2a.4 — DB: po_line_items row
psql -U smartretail_admin -d smartretail -c \
  "SELECT quantity, unit_cost, line_total FROM replenishment.po_line_items
   WHERE po_id = '<po_id from above>';"
# Expected: quantity=100, unit_cost=8.50, line_total=850.00

# Check 2a.5 — RE log: PurchaseOrderEvent published
docker logs smartretail-re | grep "PurchaseOrderEvent published"

# 4. Scenario 2b — Pending Approval (SKU-BEV-003 / DC-LONDON)
python scripts/publish-pos-event.py --sku SKU-BEV-003 --dc DC-LONDON --qty 30

# Check 2b.1 — DB: PENDING_APPROVAL
psql -U smartretail_admin -d smartretail -c \
  "SELECT workflow_status, version FROM replenishment.purchase_orders
   WHERE sku_id='SKU-BEV-003' ORDER BY created_at DESC LIMIT 1;"
# Expected: workflow_status=PENDING_APPROVAL, version=0

# Check 2b.2 — RE log: event published with PENDING_APPROVAL

# 5. Constraint check — approve on already-APPROVED PO → 409
PO_ID=$(psql -tA -U smartretail_admin -d smartretail \
  -c "SELECT po_id FROM replenishment.purchase_orders WHERE workflow_status='APPROVED' LIMIT 1;")
curl -s -X POST http://localhost:8082/v1/replenishment/orders/$PO_ID/approve \
  -H 'Content-Type: application/json' \
  -H 'X-Idempotency-Key: 00000000-0000-0000-0000-000000000001' \
  -d '{"version": 0}'
# Expected: HTTP 409 {"errorCode":"INVALID_STATUS_TRANSITION","currentStatus":"APPROVED"}

# 6. Run ArchUnit tests (must pass — domain has no AWS imports)
mvn -pl services/re test -Dtest=ArchitectureTest
```

---

## Complete File List

| Action | Path |
|--------|------|
| CREATE | `openapi/re-api.yaml` |
| MODIFY | `services/re/pom.xml` |
| CREATE | `services/re/src/main/java/com/smartretail/re/ReApplication.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/model/WorkflowStatus.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/model/ReplenishmentRule.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/model/PoLineItem.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/model/PurchaseOrder.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/model/InventoryAlertEventDto.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/model/exception/ReplenishmentRuleNotFoundException.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/model/exception/PurchaseOrderNotFoundException.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/model/exception/InvalidStatusTransitionException.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/model/exception/OptimisticLockException.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/port/inbound/ProcessInventoryAlertPort.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/port/inbound/ApprovePurchaseOrderPort.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/port/inbound/RejectPurchaseOrderPort.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/port/outbound/ReplenishmentRepositoryPort.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/port/outbound/PurchaseOrderEventPublisherPort.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/usecase/ProcessInventoryAlertUseCase.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/usecase/ApprovePurchaseOrderUseCase.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/domain/usecase/RejectPurchaseOrderUseCase.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/adapter/outbound/persistence/ReplenishmentRepository.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/adapter/outbound/event/EventBridgePurchaseOrderPublisher.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/config/AwsClientsConfig.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/config/LocalStackConfig.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/config/SecurityConfig.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/adapter/inbound/sqs/AlertSqsListener.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/adapter/inbound/rest/ReplenishmentResponseMapper.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/adapter/inbound/rest/GlobalExceptionHandler.java` |
| CREATE | `services/re/src/main/java/com/smartretail/re/adapter/inbound/rest/ReplenishmentController.java` |
| CREATE | `services/re/src/main/resources/application.yml` |
| CREATE | `services/re/src/main/resources/application-local.yml` |
| CREATE | `services/re/src/main/resources/application-aws.yml` |
| CREATE | `services/re/src/main/resources/logback-spring.xml` |
| CREATE | `services/re/src/test/java/com/smartretail/re/architecture/ArchitectureTest.java` |
| CREATE | `services/re/src/test/java/com/smartretail/re/domain/usecase/ProcessInventoryAlertUseCaseTest.java` |
| CREATE | `services/re/src/test/java/com/smartretail/re/domain/usecase/ApprovePurchaseOrderUseCaseTest.java` |
| MODIFY | `infra/cdk/lib/compute-stack.ts` |
| MODIFY | `infra/cdk/lib/api-stack.ts` |
| NO CHANGE | `scripts/localstack-init.sh` (queue + SSM already configured) |
| NO CHANGE | `migrations/flyway/**` (replenishment schema already exists in V4) |

---

## Reference — Existing Patterns to Reuse (do not reinvent)

| Pattern | Source file |
|---------|-------------|
| SQS EventBridge envelope unwrapping | `services/ims/src/main/java/com/smartretail/ims/adapter/inbound/sqs/SalesSqsListener.java` |
| EventBridge publish pattern | `services/ims/src/main/java/com/smartretail/ims/adapter/outbound/event/EventBridgeAlertPublisher.java` |
| Optimistic lock retry | `services/ims/src/main/java/com/smartretail/ims/adapter/outbound/persistence/InventoryRepository.java` |
| ArchUnit test structure | `services/ims/src/test/java/com/smartretail/ims/architecture/ArchitectureTest.java` |
| LocalStack / AWS split config | `services/ims/src/main/java/com/smartretail/ims/config/LocalStackConfig.java` |
| MDC structured logging | `services/ims/src/main/java/com/smartretail/ims/adapter/inbound/sqs/SalesSqsListener.java` |
| ECS Fargate service config | `infra/cdk/lib/compute-stack.ts` — `imsConfig` block |
| API Gateway route block | `infra/cdk/lib/api-stack.ts` — IMS route block |
