# API Contracts
 
All REST endpoints are served by ECS services via API Gateway VPC Link.
Base URL: `https://{api-gateway-id}.execute-api.us-east-1.amazonaws.com/{stage}`
 
Stages:
- `internal` — for Store Manager, SC Planner, Executive MFEs
- `supplier` — for Supplier Collaboration Portal MFE (not in prototype scope)
 
All endpoints require `Authorization: Bearer {cognito-jwt}` header except
`GET /health` on each service.
 
Error envelope (all error responses):
```json
{
  "errorCode": "VALIDATION_ERROR|NOT_FOUND|CONFLICT|UNAUTHORIZED|INTERNAL_ERROR",
  "message": "Human readable description",
  "traceId": "W3C trace ID",
  "timestamp": "ISO-8601"
}
```
 
---
 
## SIS — Sales Ingestion Service
 
Base path: `/v1/ingest`
 
### POST /v1/ingest/events
 
Ingest a single sales transaction event.
Called by the Kinesis Consumer Lambda (not directly by MFEs).
 
**Request body:**
```json
{
  "transactionId": "uuid-string",
  "storeId": "STORE-001",
  "skuId": "SKU-4423",
  "dcId": "DC-LONDON",
  "quantity": 5,
  "unitPrice": 12.99,
  "channel": "POS",
  "eventTimestamp": "2026-05-15T14:23:00Z"
}
```
 
**Validation rules:**
- `transactionId`: required, UUID format
- `storeId`: required, non-blank, max 50 chars
- `skuId`: required, non-blank, max 50 chars
- `dcId`: required, non-blank, max 50 chars
- `quantity`: required, integer > 0
- `unitPrice`: required, decimal >= 0
- `channel`: required, enum [POS, ECOMMERCE]
- `eventTimestamp`: required, ISO-8601, not in future by more than 5 minutes
 
**Responses:**
- `202 Accepted` — event accepted and queued for processing
  ```json
  { "transactionId": "uuid", "status": "ACCEPTED" }
  ```
- `409 Conflict` — duplicate (idempotency check matched)
  ```json
  { "errorCode": "DUPLICATE_EVENT", "transactionId": "uuid" }
  ```
- `400 Bad Request` — validation failure
 
**Processing steps (asynchronous after 202):**
1. Dedup check: SHA-256(transactionId) → DynamoDB GetItem
2. If not duplicate: write to RDS sales.sales_events
3. Write to S3 raw archive
4. Write dedup key to DynamoDB (TTL 48h)
5. Publish sales transaction event to EventBridge
 
**EventBridge event published:**
```json
{
  "source": "smartretail.sis",
  "detail-type": "SalesTransactionEvent",
  "detail": {
    "transactionId": "uuid",
    "skuId": "SKU-4423",
    "dcId": "DC-LONDON",
    "storeId": "STORE-001",
    "quantity": 5,
    "unitPrice": 12.99,
    "channel": "POS",
    "eventTimestamp": "2026-05-15T14:23:00Z"
  }
}
```
 
---
 
## IMS — Inventory Management Service
 
Base path: `/v1/inventory`
 
### GET /v1/inventory/positions
 
Get inventory positions. Accessible by STORE_MANAGER, SC_PLANNER, ADMIN roles.
 
**Query parameters:**
- `dcId` (required for STORE_MANAGER — enforced at service layer)
- `skuId` (optional)
- `page` (default 0)
- `size` (default 20, max 100)
 
**Response 200:**
```json
{
  "positions": [
    {
      "positionId": "uuid",
      "skuId": "SKU-4423",
      "dcId": "DC-LONDON",
      "onHand": 150,
      "inTransit": 50,
      "reserved": 20,
      "reorderPoint": 100,
      "safetyStock": 30,
      "lastUpdatedAt": "2026-05-15T14:23:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1
}
```
 
### GET /v1/inventory/alerts
 
Get active stock alerts.
 
**Query parameters:**
- `dcId` (optional)
- `severity` (optional — CRITICAL, HIGH, MEDIUM)
- `status` (default ACTIVE)
- `page`, `size`
 
**Response 200:**
```json
{
  "alerts": [
    {
      "alertId": "uuid",
      "positionId": "uuid",
      "skuId": "SKU-4423",
      "dcId": "DC-LONDON",
      "alertType": "LOW_STOCK",
      "severity": "HIGH",
      "thresholdValue": 100,
      "actualValue": 45,
      "raisedAt": "2026-05-15T14:23:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1
}
```
 
**IMS event processing (SQS consumer):**
 
When IMS receives a `SalesTransactionEvent` from its SQS queue:
1. Find inventory_positions record by (skuId, dcId)
2. Decrement on_hand by quantity using optimistic locking (version column)
3. Recompute ATP = on_hand - reserved
4. If ATP < reorder_point: create stock_alert record
5. Classify severity:
   - CRITICAL if ATP <= 0
   - HIGH if ATP < (reorder_point * 0.5)
   - MEDIUM otherwise
6. Publish inventory alert event to EventBridge
 
**EventBridge event published (on LOW_STOCK):**
```json
{
  "source": "smartretail.ims",
  "detail-type": "InventoryAlertEvent",
  "detail": {
    "alertId": "uuid",
    "positionId": "uuid",
    "skuId": "SKU-4423",
    "dcId": "DC-LONDON",
    "alertType": "LOW_STOCK",
    "severity": "HIGH",
    "atp": 45,
    "reorderPoint": 100
  }
}
```
 
---
 
## RE — Replenishment Engine
 
Base path: `/v1/replenishment`
 
### GET /v1/replenishment/orders
 
Get purchase orders. STORE_MANAGER sees their DC only. SC_PLANNER sees all.
 
**Query parameters:**
- `status` (optional — filter by workflow_status)
- `dcId` (optional)
- `skuId` (optional)
- `page`, `size`
 
**Response 200:**
```json
{
  "orders": [
    {
      "poId": "uuid",
      "supplierId": "uuid",
      "skuId": "SKU-4423",
      "dcId": "DC-LONDON",
      "quantity": 500,
      "totalValue": 6495.00,
      "workflowStatus": "PENDING_APPROVAL",
      "version": 1,
      "createdAt": "2026-05-15T14:23:00Z",
      "updatedAt": "2026-05-15T14:23:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1
}
```
 
### GET /v1/replenishment/orders/{poId}
 
Get single PO by ID.
 
**Response 200:** Single PO object as above, with line items:
```json
{
  "poId": "uuid",
  "workflowStatus": "PENDING_APPROVAL",
  "version": 1,
  "lineItems": [
    {
      "lineId": "uuid",
      "skuId": "SKU-4423",
      "quantity": 500,
      "unitCost": 12.99,
      "lineTotal": 6495.00
    }
  ]
}
```
 
**Response 404:** PO not found.
 
### POST /v1/replenishment/orders/{poId}/approve
 
Approve a PO. Requires SC_PLANNER or ADMIN role (validated at service layer).
 
**Request headers:**
- `X-Idempotency-Key`: UUID (required — prevents double-approve)
 
**Request body:** (optional)
```json
{ "notes": "Approved — urgent restock needed" }
```
 
**Pre-conditions validated by service:**
1. PO exists
2. workflow_status = PENDING_APPROVAL (not DRAFT, not already APPROVED)
3. JWT contains SC_PLANNER or ADMIN group claim
4. No prior approve with same X-Idempotency-Key
 
**Processing:**
1. SELECT purchase_orders WHERE po_id = ? AND version = current_version
2. UPDATE SET workflow_status = 'APPROVED', approved_by = sub from JWT,
   approved_at = NOW(), version = version + 1
   WHERE po_id = ? AND version = :currentVersion (optimistic lock)
3. If 0 rows updated → concurrent modification → return 409
4. Publish purchase order domain event to EventBridge
5. Insert audit log entry
 
**Response 200:**
```json
{
  "poId": "uuid",
  "workflowStatus": "APPROVED",
  "approvedBy": "planner@smartretail.com",
  "approvedAt": "2026-05-15T14:30:00Z",
  "version": 2
}
```
 
**Response 409 Conflict:**
```json
{
  "errorCode": "INVALID_STATUS_TRANSITION",
  "message": "PO cannot be approved from status DRAFT. Status must be PENDING_APPROVAL.",
  "currentStatus": "DRAFT"
}
```
 
### POST /v1/replenishment/orders/{poId}/reject
 
Reject a PO. Requires SC_PLANNER or ADMIN role.
 
**Request body:**
```json
{ "reason": "Supplier pricing above quarterly budget" }
```
 
**Pre-conditions:** workflow_status = PENDING_APPROVAL
 
**Processing:** Same optimistic lock pattern as approve.
Sets workflow_status = REJECTED, rejected_by, rejected_at, rejection_reason.
 
**Response 200:**
```json
{
  "poId": "uuid",
  "workflowStatus": "REJECTED",
  "rejectedBy": "planner@smartretail.com",
  "rejectedAt": "2026-05-15T14:31:00Z",
  "rejectionReason": "Supplier pricing above quarterly budget"
}
```
 
**EventBridge event published (on APPROVED or REJECTED):**
```json
{
  "source": "smartretail.re",
  "detail-type": "PurchaseOrderEvent",
  "detail": {
    "poId": "uuid",
    "supplierId": "uuid",
    "skuId": "SKU-4423",
    "dcId": "DC-LONDON",
    "workflowStatus": "APPROVED",
    "quantity": 500,
    "totalValue": 6495.00
  }
}
```
 
**RE SQS consumer (inventory alert → PO generation):**
 
When RE receives an `InventoryAlertEvent` from its FIFO SQS queue:
1. Look up replenishment_rules by (skuId, dcId)
2. If no rule found: log warning, acknowledge message, stop
3. Compute quantity = max(reorderPoint - onHand, moq)
4. Compute totalValue = quantity * costPerUnit
5. Insert purchase_orders with workflow_status:
   - APPROVED if totalValue <= autoApproveThreshold
   - PENDING_APPROVAL if totalValue > autoApproveThreshold
6. Insert po_line_items
7. Publish PurchaseOrderEvent to EventBridge
 
---
 
## ARS — Analytics & Reporting Service
 
Base path: `/v1/dashboard`
 
### GET /v1/dashboard/store-manager
 
Dashboard KPIs for Store Manager persona.
Requires STORE_MANAGER, SC_PLANNER, or ADMIN role.
STORE_MANAGER role: dcId parameter is REQUIRED and enforced.
 
**Query parameters:**
- `dcId` (required for STORE_MANAGER)
 
**Processing (parallel RDS reads — no cross-schema joins):**
```
Query 1: SELECT from inventory.inventory_positions WHERE dc_id = ?
Query 2: SELECT from inventory.stock_alerts WHERE status = 'ACTIVE' AND position.dc_id = ?
Query 3: SELECT from forecasting.demand_forecasts WHERE dc_id = ? AND forecast_date = CURRENT_DATE
Query 4: SELECT count(*) from replenishment.purchase_orders WHERE dc_id = ? AND workflow_status IN ('PENDING_APPROVAL', 'APPROVED')
```
 
**Response 200:**
```json
{
  "dcId": "DC-LONDON",
  "summary": {
    "totalSkus": 150,
    "lowStockAlerts": {
      "critical": 3,
      "high": 12,
      "medium": 28
    },
    "pendingReplenishmentOrders": 5,
    "forecastCoverage": {
      "skusWithForecast": 148,
      "forecastDate": "2026-05-15"
    }
  },
  "topAlerts": [
    {
      "skuId": "SKU-4423",
      "dcId": "DC-LONDON",
      "severity": "CRITICAL",
      "onHand": 0,
      "reorderPoint": 100
    }
  ],
  "dataFreshness": "2026-05-15T14:23:00Z"
}
```
 
**dataFreshness** = MIN(last updated_at across all queried tables)
 
### GET /v1/dashboard/sc-planner
 
Dashboard for SC Planner persona.
Requires SC_PLANNER or ADMIN role.
 
**Response 200:**
```json
{
  "pendingApprovals": [
    {
      "poId": "uuid",
      "skuId": "SKU-4423",
      "dcId": "DC-LONDON",
      "supplierId": "uuid",
      "quantity": 500,
      "totalValue": 6495.00,
      "workflowStatus": "PENDING_APPROVAL",
      "createdAt": "2026-05-15T14:00:00Z"
    }
  ],
  "pendingApprovalCount": 1,
  "activeAlertCount": 43,
  "forecastAccuracy": {
    "latestMape": 0.0823,
    "mapeThreshold": 0.15,
    "lastRunAt": "2026-05-15T02:00:00Z",
    "status": "WITHIN_THRESHOLD"
  },
  "dataFreshness": "2026-05-15T14:23:00Z"
}
```
 
### GET /v1/dashboard/executive
 
Dashboard for Executive persona.
Requires EXECUTIVE, SC_PLANNER, or ADMIN role.
Read-only — no filters required.
 
**Response 200:**
```json
{
  "kpis": {
    "forecastAccuracy": {
      "latestMape": 0.0823,
      "trend": "IMPROVING",
      "history": [
        { "runDate": "2026-05-15", "mape": 0.0823 },
        { "runDate": "2026-05-14", "mape": 0.0891 },
        { "runDate": "2026-05-13", "mape": 0.0956 }
      ]
    },
    "stockoutFrequency": {
      "last30Days": 12,
      "trend": "DECREASING"
    },
    "replenishmentCycleTime": {
      "averageDays": 3.2,
      "trend": "STABLE"
    }
  },
  "dataFreshness": "2026-05-15T14:23:00Z"
}
```
 
### GET /v1/dashboard/supplier-performance
 
Supplier performance scorecard for SC Planner.
Requires SC_PLANNER or ADMIN role.
 
**Response 200:**
```json
{
  "suppliers": [
    {
      "supplierId": "uuid",
      "supplierName": "Acme Beverages Ltd",
      "onTimeDeliveryRate": 0.87,
      "poAcknowledgementSlaCompliance": 0.92,
      "openExceptions": 2,
      "avgLeadTimeVarianceDays": 1.3,
      "totalPoCount": 45,
      "totalPoValue": 234500.00
    }
  ],
  "dataFreshness": "2026-05-15T14:23:00Z"
}
```
 
**Queries (separate — no joins across schemas):**
```sql
-- supplier names from supplier schema
SELECT supplier_id, supplier_name FROM supplier.supplier_records WHERE status = 'ACTIVE';
 
-- PO metrics from replenishment schema
SELECT supplier_id, COUNT(*) as total_pos,
       SUM(total_value) as total_value,
       COUNT(*) FILTER (WHERE workflow_status = 'COMPLETED') as completed_pos
FROM replenishment.purchase_orders
WHERE created_at >= NOW() - INTERVAL '90 days'
GROUP BY supplier_id;
 
-- shipment metrics from supplier schema
SELECT s.supplier_id,
       COUNT(*) FILTER (WHERE su.update_type = 'SHIPPED' AND sp.dispatched_at IS NOT NULL
                         AND su.created_at <= sp.dispatched_at + (rr.lead_time_days * INTERVAL '1 day'))
           AS on_time_count,
       COUNT(*) FILTER (WHERE su.update_type = 'SHIPPED') AS total_shipped
FROM supplier.supplier_pos sp
JOIN supplier.shipment_updates su ON su.supplier_po_id = sp.supplier_po_id
JOIN supplier.supplier_records s ON s.supplier_id = sp.supplier_id
GROUP BY s.supplier_id;
```
 
Note: supplierId is used to join the results in application code, not SQL.
 
---
 
## Health Endpoints (all services)
 
```
GET /actuator/health    → 200 { "status": "UP" }
GET /actuator/info      → 200 { "service": "...", "version": "..." }
```
 
No auth required on health endpoints.
These are the ECS health check targets.

---

## DFS — Demand Forecasting Service

Base URL (local): `http://localhost:8084`
OpenAPI spec: `openapi/dfs-api.yaml`
Roles allowed: `SC_PLANNER`, `ADMIN`

### GET /v1/forecast/{skuId}/{dcId}

Returns P10/P50/P90 demand forecast bands from the latest COMPLETED forecast run.

**Path parameters:**
- `skuId` — stock-keeping unit identifier (max 50 chars)
- `dcId` — distribution centre identifier (max 50 chars)

**Query parameters:**
- `horizonDays` — 7, 14, or 30 (default: 30)

**Response 200:**
```json
{
  "skuId": "SKU-BEV-001",
  "dcId": "DC-LONDON",
  "horizonDays": 30,
  "latestMape": 0.0823,
  "bands": [
    { "forecastDate": "2026-05-22", "p10": 85, "p50": 110, "p90": 140, "actualUnits": null }
  ],
  "dataFreshness": "2026-05-15T02:00:00Z"
}
```

---

## SUP — Supplier Service

Base URL (local): `http://localhost:8085`
OpenAPI spec: `openapi/sup-api.yaml`
Roles allowed: `SC_PLANNER`, `ADMIN`

### GET /v1/supplier/orders

Returns supplier POs with shipment progress. EXCEPTION rows first, then sorted by ETA ascending.

**Query parameters:**
- `status` — optional filter: `PENDING`, `CONFIRMED`, `DISPATCHED`, `DELIVERED`, `EXCEPTION`

**Response 200:**
```json
{
  "orders": [
    {
      "supplierPoId": "d1b2c3d4-0000-0000-0000-000000000001",
      "poId": "c1b2c3d4-0000-0000-0000-000000000001",
      "supplierId": "11111111-0000-0000-0000-000000000001",
      "supplierName": "Acme Beverages Ltd",
      "skuId": "SKU-BEV-001",
      "dcId": "DC-LONDON",
      "quantity": 500,
      "shipmentStatus": "DISPATCHED",
      "confirmedAt": "2026-04-16T10:00:00Z",
      "dispatchedAt": "2026-04-17T08:00:00Z",
      "eta": "2026-04-19",
      "lastUpdateAt": "2026-04-18T08:00:00Z"
    }
  ],
  "dataFreshness": "2026-05-15T14:23:00Z"
}
```
 
 