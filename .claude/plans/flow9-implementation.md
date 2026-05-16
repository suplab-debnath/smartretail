# Flow 9: SC Planner Console — Implementation Plan & Checklist

## Context

Flow 9 proves that the SC Planner persona can access all eight console surfaces (exception queue, inventory overview, demand forecast, stockout risk, approval workflows, supplier order tracking, replenishment trigger, and forecast adjustment controls) using pre-populated seed data (V7__seed_data.sql). It extends Flow 8's ARS backend and adds a brand-new SC Planner MFE (currently a skeleton with only `package.json` — zero source files). The reference implementation to mirror is `mfe/executive/`.

---

## Critical Files

| File | Status | Action |
|------|--------|--------|
| `openapi/ars-api.yaml` | Partial (sc-planner returns 501) | Extend with full schemas |
| `services/ars/src/main/java/com/smartretail/ars/` | Executive done; SC Planner missing | Add use case, ports, repositories, controller methods |
| `mfe/sc-planner/` | Skeleton (package.json only) | Build from scratch |
| `mfe/executive/` | Complete | Reference implementation — reuse patterns |
| `mfe/shared/auth/` | Complete | Reuse `useAuth`, `createApiClient`, `AuthCallback` |
| `migrations/flyway/.../V7__seed_data.sql` | Complete | No changes — already seeded |

---

## Phase 1 — OpenAPI Contract (ars-api.yaml)

> Rule: YAML first → generate stubs → implement. Never hand-write DTOs.

- [ ] **1.1** Add `ScPlannerDashboard` response schema to `openapi/ars-api.yaml`
  - Fields: `pendingApprovalCount`, `activeAlertCount`, `forecastAccuracy` (latestMape, mapeThreshold, lastRunAt, status), `dataFreshness`
  - File: `openapi/ars-api.yaml`

- [ ] **1.2** Add `SupplierPerformanceResponse` schema
  - Fields: `suppliers[]` (supplierId, supplierName, onTimeDeliveryRate, poAcknowledgementSlaCompliance, openExceptions, avgLeadTimeVarianceDays, totalPoCount, totalPoValue), `dataFreshness`
  - File: `openapi/ars-api.yaml`

- [ ] **1.3** Wire `GET /v1/dashboard/sc-planner` to return 200 `ScPlannerDashboard` (remove 501)
  - Required roles: `SC_PLANNER`, `ADMIN`
  - File: `openapi/ars-api.yaml`

- [ ] **1.4** Wire `GET /v1/dashboard/supplier-performance` to return 200 `SupplierPerformanceResponse` (remove 501)
  - Required roles: `SC_PLANNER`, `ADMIN`
  - File: `openapi/ars-api.yaml`

- [ ] **1.5** Add `StockAlert` schema and `GET /v1/inventory/alerts` endpoint
  - Query params: `status` (ACTIVE|RESOLVED), `dcId` (optional)
  - Response: `{ alerts: StockAlert[], dataFreshness }`
  - Fields: alertId, skuId, dcId, alertType (LOW_STOCK|OVERSTOCK), severity (CRITICAL|HIGH|MEDIUM), onHand, reorderPoint, raisedAt
  - File: `openapi/ars-api.yaml`

- [ ] **1.6** Add `InventoryPosition` schema and `GET /v1/inventory/positions` endpoint
  - Query params: `dcId` (optional)
  - Response: `{ positions: InventoryPosition[], dataFreshness }`
  - Fields: positionId, skuId, dcId, onHand, inTransit, reserved, atp, reorderPoint, safetyStock, updatedAt
  - File: `openapi/ars-api.yaml`

- [ ] **1.7** Add `ForecastBand` schema and `GET /v1/forecast/{skuId}/{dcId}` endpoint
  - Query params: `horizonDays` (7|14|30, default 30)
  - Response: `{ skuId, dcId, bands: ForecastBand[], latestMape, dataFreshness }`
  - Fields per band: forecastDate, p10, p50, p90, actualUnits (nullable)
  - File: `openapi/ars-api.yaml`

- [ ] **1.8** Add `PurchaseOrderSummary` schema and `GET /v1/replenishment/orders` endpoint (read-only, ARS)
  - Query params: `status` (filter), `dcId` (optional)
  - Response: `{ orders: PurchaseOrderSummary[], dataFreshness }`
  - Fields: poId, supplierId, skuId, dcId, quantity, totalValue, workflowStatus, version, createdAt
  - File: `openapi/ars-api.yaml`

- [ ] **1.9** Add `SupplierOrder` schema and `GET /v1/supplier/orders` endpoint
  - Response: `{ orders: SupplierOrder[], dataFreshness }`
  - Fields: poId, supplierId, supplierName, skuId, dcId, quantity, workflowStatus, confirmedAt, dispatchedAt, eta, shipmentStatus
  - File: `openapi/ars-api.yaml`

- [ ] **1.10** Run `mvn generate-sources` in `services/ars/` to regenerate Java stubs
  - Verify: `services/ars/target/generated-sources/openapi/` contains new interfaces
  - Run: `npm run generate-api` in `mfe/sc-planner/` to regenerate TypeScript client

---

## Phase 2 — ARS Backend: SC Planner Dashboard Endpoint

> Follow exact hexagonal pattern from `ExecutiveDashboard` + `ExecutiveDashboardUseCase`.

- [ ] **2.1** Create domain model `ScPlannerDashboard` record
  - File: `services/ars/src/main/java/com/smartretail/ars/domain/model/ScPlannerDashboard.java`
  - Fields: `pendingApprovalCount`, `activeAlertCount`, `forecastAccuracy` (record), `dataFreshness`

- [ ] **2.2** Create inbound port `ScPlannerDashboardPort` interface
  - File: `services/ars/src/main/java/com/smartretail/ars/port/inbound/ScPlannerDashboardPort.java`
  - Method: `ScPlannerDashboard getDashboard()`

- [ ] **2.3** Create use case `ScPlannerDashboardUseCase`
  - File: `services/ars/src/main/java/com/smartretail/ars/domain/usecase/ScPlannerDashboardUseCase.java`
  - Orchestrates 2 parallel `CompletableFuture` queries (reuse existing outbound ports):
    - `InventoryReadPort.countActiveAlerts()` → activeAlertCount
    - `ReplenishmentReadPort.countPendingApprovals()` → pendingApprovalCount
    - `ForecastReadPort.getLatestMape()` → forecastAccuracy
  - `dataFreshness` = `MIN(updated_at)` across queried tables

- [ ] **2.4** Add `countPendingApprovals()` method to `ReplenishmentReadPort` + `ReplenishmentReadRepository`
  - SQL: `SELECT COUNT(*) FROM replenishment.purchase_orders WHERE workflow_status = 'PENDING_APPROVAL'`
  - Files: `port/outbound/ReplenishmentReadPort.java`, `adapter/outbound/persistence/ReplenishmentReadRepository.java`

- [ ] **2.5** Add `countActiveAlerts()` method to `InventoryReadPort` + `InventoryReadRepository`
  - SQL: `SELECT COUNT(*) FROM inventory.stock_alerts WHERE status = 'ACTIVE'`
  - Files: `port/outbound/InventoryReadPort.java`, `adapter/outbound/persistence/InventoryReadRepository.java`

- [ ] **2.6** Add `GET /v1/dashboard/sc-planner` handler in `DashboardController`
  - File: `services/ars/src/main/java/com/smartretail/ars/adapter/inbound/rest/DashboardController.java`
  - Role check: `SC_PLANNER` or `ADMIN` (same JWT extraction pattern as executive endpoint)
  - Implement generated interface method; call `ScPlannerDashboardPort`

---

## Phase 3 — ARS Backend: Supplier Performance Endpoint

> Key rule: NO cross-schema SQL JOINs. Three separate queries merged in Java by `supplierId`.

- [ ] **3.1** Create domain model `SupplierPerformance` + `SupplierPerformanceDashboard` records
  - File: `services/ars/src/main/java/com/smartretail/ars/domain/model/SupplierPerformanceDashboard.java`

- [ ] **3.2** Create inbound port `SupplierPerformancePort` interface
  - File: `services/ars/src/main/java/com/smartretail/ars/port/inbound/SupplierPerformancePort.java`

- [ ] **3.3** Create use case `SupplierPerformanceUseCase`
  - File: `services/ars/src/main/java/com/smartretail/ars/domain/usecase/SupplierPerformanceUseCase.java`
  - Three parallel queries (CompletableFuture.allOf):
    1. `SupplierReadPort.getActiveSuppliers()` → name map keyed by supplierId
    2. `ReplenishmentReadPort.getPoMetricsBySupplierId()` → totalPoCount, totalPoValue per supplier
    3. `SupplierReadPort.getShipmentMetricsBySupplierId()` → onTimeCount, totalShipped per supplier
  - Merge in Java: compute `onTimeDeliveryRate = onTimeCount / totalShipped`

- [ ] **3.4** Add `getPoMetricsBySupplierId()` to `ReplenishmentReadPort` + repository
  - SQL (no joins to supplier schema):
    ```sql
    SELECT supplier_id, COUNT(*) as total_pos, SUM(total_value) as total_value,
           COUNT(*) FILTER (WHERE workflow_status = 'COMPLETED') as completed_pos
    FROM replenishment.purchase_orders
    WHERE created_at >= NOW() - INTERVAL '90 days'
    GROUP BY supplier_id
    ```
  - Files: `port/outbound/ReplenishmentReadPort.java`, `adapter/outbound/persistence/ReplenishmentReadRepository.java`

- [ ] **3.5** Add `getShipmentMetricsBySupplierId()` to `SupplierReadPort` + repository
  - SQL (within supplier schema only):
    ```sql
    SELECT sp.supplier_id,
           COUNT(*) FILTER (WHERE su.update_type = 'SHIPPED' AND sp.dispatched_at IS NOT NULL
             AND su.created_at <= sp.dispatched_at + (sr.lead_time_days * INTERVAL '1 day')) AS on_time_count,
           COUNT(*) FILTER (WHERE su.update_type = 'SHIPPED') AS total_shipped
    FROM supplier.supplier_pos sp
    JOIN supplier.shipment_updates su ON su.supplier_po_id = sp.supplier_po_id
    JOIN supplier.supplier_records sr ON sr.supplier_id = sp.supplier_id
    GROUP BY sp.supplier_id
    ```
  - Files: `port/outbound/SupplierReadPort.java`, `adapter/outbound/persistence/SupplierReadRepository.java`

- [ ] **3.6** Add `GET /v1/dashboard/supplier-performance` handler in `DashboardController`
  - Role check: `SC_PLANNER` or `ADMIN`

---

## Phase 4 — ARS Backend: Read Endpoints for MFE Tabs

- [ ] **4.1** `GET /v1/inventory/alerts` — alerts with optional status + dcId filter
  - New port method: `InventoryReadPort.getAlerts(String status, String dcId)`
  - SQL: `SELECT * FROM inventory.stock_alerts WHERE status = :status [AND dc_id = :dcId] ORDER BY CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 ELSE 3 END`
  - Add controller method in `DashboardController` (or new `InventoryController`)
  - Roles: `SC_PLANNER`, `STORE_MANAGER`, `ADMIN`

- [ ] **4.2** `GET /v1/inventory/positions` — positions with optional dcId filter
  - New port method: `InventoryReadPort.getPositions(String dcId)`
  - SQL: `SELECT * FROM inventory.inventory_positions [WHERE dc_id = :dcId] ORDER BY sku_id`
  - Roles: `SC_PLANNER`, `STORE_MANAGER`, `ADMIN`

- [ ] **4.3** `GET /v1/forecast/{skuId}/{dcId}` — forecast bands for a SKU × DC
  - New port method: `ForecastReadPort.getForecastBands(String skuId, String dcId, int horizonDays)`
  - SQL: `SELECT forecast_date, p10, p50, p90 FROM forecasting.demand_forecasts WHERE sku_id = :skuId AND dc_id = :dcId AND horizon_days = :horizonDays ORDER BY forecast_date`
  - Roles: `SC_PLANNER`, `ADMIN`

- [ ] **4.4** `GET /v1/replenishment/orders` — PO list with status filter (read-only ARS view)
  - New port method: `ReplenishmentReadPort.getOrders(String status, String dcId)`
  - SQL: `SELECT * FROM replenishment.purchase_orders WHERE workflow_status = :status [AND dc_id = :dcId] ORDER BY created_at DESC`
  - Roles: `SC_PLANNER`, `ADMIN`

- [ ] **4.5** `GET /v1/supplier/orders` — supplier POs joined with shipment updates (within supplier schema only)
  - SQL: `SELECT sp.*, su.update_type AS shipment_status, su.created_at AS last_update, sr.supplier_name FROM supplier.supplier_pos sp JOIN supplier.shipment_updates su ON su.supplier_po_id = sp.supplier_po_id JOIN supplier.supplier_records sr ON sr.supplier_id = sp.supplier_id`
  - Roles: `SC_PLANNER`, `ADMIN`

---

## Phase 5 — SC Planner MFE: Scaffolding

> Mirror `mfe/executive/` exactly. Reuse all config patterns verbatim, changing port to 5174.

- [ ] **5.1** Create `mfe/sc-planner/index.html` — copy from executive, update title to "SC Planner Console"

- [ ] **5.2** Create `mfe/sc-planner/vite.config.ts`
  - Dev server port: **5174**
  - Proxy: `/v1` → `http://localhost:8083` (ARS), `/v1/replenishment` → `http://localhost:8082` (RE)

- [ ] **5.3** Create `mfe/sc-planner/tsconfig.json` — copy from executive verbatim

- [ ] **5.4** Create `mfe/sc-planner/tailwind.config.ts` — copy from executive verbatim

- [ ] **5.5** Create `mfe/sc-planner/postcss.config.js` — copy from executive verbatim

- [ ] **5.6** Create `mfe/sc-planner/src/index.css` — copy from executive (Tailwind directives)

- [ ] **5.7** Create `mfe/sc-planner/src/main.tsx` — standard React 18 entry, mount App to `#root`

---

## Phase 6 — SC Planner MFE: Types & Routing

- [ ] **6.1** Create `mfe/sc-planner/src/types.ts` — TypeScript interfaces matching OpenAPI schemas:
  - `ScPlannerDashboard`, `StockAlert`, `InventoryPosition`, `ForecastBand`, `ForecastData`
  - `PurchaseOrder`, `SupplierOrder`, `SupplierPerformanceEntry`
  - `PromotionalUplift`, `StockoutRisk` (derived type: `CRITICAL|HIGH|MODERATE`)
  - `DataFreshness` (ISO 8601 string)

- [ ] **6.2** Create `mfe/sc-planner/src/App.tsx` — BrowserRouter + AuthProvider + routes:
  - `/` → redirect to `/dashboard`
  - `/dashboard` → `<ScPlannerDashboard />`
  - `/dashboard/exceptions` → tab 1
  - `/dashboard/inventory` → tab 2
  - `/dashboard/forecast` → tab 3
  - `/dashboard/risk` → tab 4
  - `/dashboard/approvals` → tab 5
  - `/dashboard/supplier-orders` → tab 6
  - `/dashboard/replenishment` → tab 7
  - `/dashboard/performance` → supplier scorecard
  - `/callback` → `<AuthCallback />`
  - `/logout` → logout page
  - Role guard: requires `SC_PLANNER` or `ADMIN`

---

## Phase 7 — SC Planner MFE: Data Hooks

- [ ] **7.1** Create `src/hooks/useScPlannerDashboard.ts`
  - `GET /v1/dashboard/sc-planner` on mount; poll every 2 minutes
  - Returns: `{ data, loading, error, refresh }`
  - Header: `X-Dev-Role: SC_PLANNER` for local dev

- [ ] **7.2** Create `src/hooks/useExceptionQueue.ts`
  - `GET /v1/inventory/alerts?status=ACTIVE` (with optional dcId)
  - Lazy load (called when tab first activated)

- [ ] **7.3** Create `src/hooks/useInventoryPositions.ts`
  - `GET /v1/inventory/positions?dcId={selectedDc}` — refetch when DC changes

- [ ] **7.4** Create `src/hooks/useForecast.ts`
  - `GET /v1/forecast/{skuId}/{dcId}?horizonDays={horizon}` — refetch on SKU/DC/horizon change

- [ ] **7.5** Create `src/hooks/usePendingApprovals.ts`
  - `GET /v1/replenishment/orders?status=PENDING_APPROVAL`
  - Optimistic update helpers (approve removes from list locally)

- [ ] **7.6** Create `src/hooks/useSupplierOrders.ts`
  - `GET /v1/supplier/orders`

- [ ] **7.7** Create `src/hooks/useSupplierPerformance.ts`
  - `GET /v1/dashboard/supplier-performance`

---

## Phase 8 — SC Planner MFE: Components (8 Tabs + Shell)

- [ ] **8.1** `src/components/ScPlannerDashboard.tsx` — tab shell
  - Tab bar: 8 tabs + supplier scorecard
  - Lazy-load each tab panel on first activation
  - Badge on Exception Queue tab (activeAlertCount) and Approvals tab (pendingApprovalCount)
  - Footer: `dataFreshness` as "Data as of HH:MM" on **every** tab

- [ ] **8.2** `src/components/SeverityBadge.tsx` — CRITICAL=red, HIGH=amber, MEDIUM=yellow chip

- [ ] **8.3** `src/components/ExceptionQueueTab.tsx` (Surface 9.1)
  - Uses `useExceptionQueue` hook
  - Sortable table: SKU | DC | Alert Type | Severity | On-Hand | Reorder Point | Raised At | Action
  - `<SeverityBadge>` per row; AlertTypeTag (LOW_STOCK / OVERSTOCK)
  - ActionButton links to Replenishment Action Trigger modal

- [ ] **8.4** `src/components/InventoryOverviewTab.tsx` (Surface 9.2)
  - Uses `useInventoryPositions` hook
  - `<DcSelector>` dropdown: DC-LONDON | DC-MANCHESTER | DC-BIRMINGHAM
  - Grid of cards per SKU: On-Hand / In-Transit / Reserved
  - `<ReorderStatusChip>`: CRITICAL (ATP ≤ 0, red) | REORDER_SOON (ATP < reorderPoint, amber) | OK (green)

- [ ] **8.5** `src/components/DemandForecastTab.tsx` (Surface 9.3)
  - Uses `useForecast` hook
  - `<SkuDcSelector>` — SKU × DC combination picker
  - `<HorizonSelector>` — 7 / 14 / 30 day toggle
  - Recharts `AreaChart` with 3 band areas (P10/P50/P90, semi-transparent fills) + ActualUnits line overlay
  - `<ForecastAccuracyIndicator>` — MAPE % badge (green < 10%, amber 10–20%, red > 20%)
  - Uplift overlay rendered here when `PromotionalUplift` state is set (Phase 8.10)

- [ ] **8.6** `src/components/StockoutRiskTab.tsx` (Surface 9.4)
  - Derived from `useInventoryPositions` — no additional API call
  - Compute risk client-side: CRITICAL (ATP ≤ 0), HIGH (ATP < reorderPoint × 0.5), MODERATE (ATP < reorderPoint)
  - `<RiskFlag>` chip: MODERATE=yellow, HIGH=orange, CRITICAL=red
  - `<BulkTriggerButton>` — select multiple HIGH/CRITICAL rows → open replenishment modal

- [ ] **8.7** `src/components/ApprovalWorkflowsTab.tsx` (Surface 9.5)
  - Uses `usePendingApprovals` hook
  - Table: PO ID | Supplier | SKU | DC | Qty | Total Value | Created At | Actions
  - `<ApproveButton>`: POST to RE `/v1/replenishment/orders/{poId}/approve` with `X-Idempotency-Key: crypto.randomUUID()`
    - Disable on click; 200 → remove from list + success toast; 409 → "PO status changed — refresh"
  - `<RejectModal>`: reason input → POST `/v1/replenishment/orders/{poId}/reject`
  - Optimistic update: remove row immediately before API confirms

- [ ] **8.8** `src/components/SupplierOrderTrackingTab.tsx` (Surface 9.6)
  - Uses `useSupplierOrders` hook
  - Sortable table: Supplier | SKU | DC | Qty | Status | ETA | Shipment Progress
  - `<ShipmentProgressBar>`: step indicator CONFIRMED → DISPATCHED → DELIVERED
  - `<ExceptionBadge>`: red flag on EXCEPTION rows
  - `<StatusFilter>` dropdown: PENDING / CONFIRMED / DISPATCHED / DELIVERED / EXCEPTION

- [ ] **8.9** `src/components/ReplenishmentTriggerModal.tsx` (Surface 9.7)
  - Triggered from ExceptionQueue or StockoutRisk tables
  - Fields: SKU (pre-filled), DC (pre-filled), Quantity (editable), Notes (optional)
  - POST to RE `/v1/replenishment/orders` (port 8082)
  - 201 → success toast "Replenishment order DRAFT-{poId} created"; 409 → "A PENDING order already exists for this SKU/DC"
  - Proxied via vite dev server `/v1/replenishment` → `http://localhost:8082`

- [ ] **8.10** `src/components/ForecastAdjustmentTab.tsx` (Surface 9.8)
  - `<UpliftInputPanel>`: numeric % input + date range picker (promotionStartDate / promotionEndDate)
  - State lifted to parent; passed as prop into `<DemandForecastTab>` to render dashed adjusted-P50 line
  - `<UpliftBadge>`: "Promo uplift applied: +{n}%" on chart header
  - `<ResetButton>`: clears uplift

- [ ] **8.11** `src/components/SupplierScorecardTab.tsx` (Surface 9.10 check)
  - Uses `useSupplierPerformance` hook
  - Table: Supplier | OTD Rate | PO Acknowledgement SLA | Open Exceptions | Avg Lead Time Variance | Total POs | Total Value
  - OTD Rate colour: ≥ 90% green, 75–89% amber, < 75% red
  - Must show: Chill Chain 95% (green), Metro Food 71% (red)

---

## Phase 9 — Wiring & Local Dev Verification

- [ ] **9.1** Confirm `mfe/sc-planner/` dev server starts: `npm run dev` → http://localhost:5174

- [ ] **9.2** Confirm ARS endpoints respond (services running via `make local-up`):
  - `curl http://localhost:8083/v1/dashboard/sc-planner -H "X-Dev-Role: SC_PLANNER"` → 200
  - `curl http://localhost:8083/v1/dashboard/supplier-performance -H "X-Dev-Role: SC_PLANNER"` → 200
  - `curl http://localhost:8083/v1/inventory/alerts?status=ACTIVE -H "X-Dev-Role: SC_PLANNER"` → 200
  - `curl http://localhost:8083/v1/inventory/positions -H "X-Dev-Role: SC_PLANNER"` → 200

- [ ] **9.3** Verify observable evidence checks from FLOWS.md:
  - 9.1 ✅ Alerts sorted CRITICAL→HIGH→MEDIUM; severity badges visible
  - 9.2 ✅ DC dropdown drives data; all 3 DCs selectable
  - 9.3 ✅ Recharts AreaChart P10/P50/P90 + ActualUnits line; MAPE % badge
  - 9.4 ✅ MODERATE/HIGH/CRITICAL flags per SKU (colour-coded)
  - 9.5 ✅ PENDING_APPROVAL PO list; approve/reject buttons render
  - 9.6 ✅ 5 suppliers, ETA, fulfilment status, shipment progress bar
  - 9.7 ✅ POST returns 201; DB query confirms DRAFT status
  - 9.8 ✅ Uplift % input visible; dashed adjusted P50 line appears
  - 9.9 ✅ Code review: no cross-schema JOINs in ARS repositories
  - 9.10 ✅ Scorecard shows Metro Food 71% (red), Chill Chain 95% (green)
  - 9.11 ✅ "Data as of HH:MM" footer on every tab

- [ ] **9.4** DB verification for 9.7 (replenishment trigger):
  ```sql
  SELECT workflow_status FROM replenishment.purchase_orders ORDER BY created_at DESC LIMIT 1;
  -- Expected: DRAFT
  ```

---

## Build Order

```
1. Update openapi/ars-api.yaml
2. mvn generate-sources (services/ars/)
3. Implement ARS domain + ports + use cases + repositories (Phases 2–4)
4. Scaffold SC Planner MFE (Phase 5)
5. Add types + routing (Phase 6)
6. Add hooks (Phase 7)
7. Add components top-down: shell → tab panels → sub-components (Phase 8)
8. make local-up && verify all 11 observable checks (Phase 9)
```

---

## Reuse Checklist (from Executive MFE)

| Pattern | Reuse from |
|---------|-----------|
| App.tsx structure (BrowserRouter + AuthProvider) | `mfe/executive/src/App.tsx` |
| `vite.config.ts` skeleton | `mfe/executive/vite.config.ts` (change port to 5174) |
| `tsconfig.json` | `mfe/executive/tsconfig.json` (verbatim) |
| `tailwind.config.ts` | `mfe/executive/tailwind.config.ts` (verbatim) |
| `useAuth`, `createApiClient`, `AuthCallback` | `mfe/shared/auth/src/` |
| `KpiCard` styling patterns | `mfe/executive/src/components/KpiCard.tsx` |
| Recharts AreaChart / LineChart patterns | `mfe/executive/src/components/MapeTrendChart.tsx` |
| Hook data-fetch pattern (polling, error, loading) | `mfe/executive/src/hooks/useExecutiveDashboard.ts` |
| CompletableFuture parallel query pattern | `services/ars/.../ExecutiveDashboardUseCase.java` |
| Role-based JWT extraction in controller | `services/ars/.../DashboardController.java` |
