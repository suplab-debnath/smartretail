# SC Planner Console MFE

React micro-frontend for supply chain planners. Provides a tabbed console covering the full replenishment workflow: supplier scorecards, exception queue, inventory overview, demand forecast, stockout risk, PO approval, supplier order tracking, and manual replenishment triggers.

**Dev port:** `5174`  
**Backends:** ARS (`GET /v1/reporting/sc-planner`, `/v1/reporting/supplier-performance`), RE (`POST /v1/replenishment/orders/{id}/approve|reject`, `POST /v1/replenishment/trigger`)  
**Flow:** Flow 9 — SC Planner Console

## Tabs

| Tab component | Description |
|---------------|-------------|
| `SupplierScorecardTab.tsx` | OTD rate, fill rate, and exception count per supplier |
| `ExceptionQueueTab.tsx` | Pending alerts and overdue orders requiring planner action |
| `InventoryOverviewTab.tsx` | ATP and stock levels across DCs |
| `DemandForecastTab.tsx` | P10/P50/P90 bands rendered with Recharts |
| `StockoutRiskTab.tsx` | SKUs below reorder point ranked by severity |
| `ApprovalWorkflowsTab.tsx` | PENDING_APPROVAL purchase orders — approve / reject actions |
| `SupplierOrderTrackingTab.tsx` | Open POs by supplier with shipment status |
| `ForecastAdjustmentTab.tsx` | Manual forecast override controls (prototype: display only) |

## Other components

| Component | Description |
|-----------|-------------|
| `ScPlannerConsole.tsx` | Root page — tab navigation and data orchestration |
| `ReplenishmentTriggerModal.tsx` | Modal form — SKU + DC (read-only) + quantity input; `POST /v1/replenishment/trigger`; handles 201, 409 (duplicate), 5xx |
| `SeverityBadge.tsx` | Coloured severity pill (shared visual pattern with Store Manager) |

## Running

```bash
npm install
npm run dev        # → http://localhost:5174
npm run build
npm run preview
```

## Testing

```bash
npm test
npm run test:watch
npm run test:coverage    # 80 % line / 70 % branch threshold
```

Key tested behaviours:
- `ReplenishmentTriggerModal` — 201 success calls `onSuccess(poId)` + `onClose`; 409 shows "PENDING order already exists"; 500 shows "HTTP 500"; network failure shows the error message.
- `SeverityBadge` — correct Tailwind color class per severity level.

## Auth

Same `@smartretail/auth` pattern as Store Manager. The approve/reject API calls include the Bearer token; 401 redirects to Cognito login.

## Charts

Recharts `ComposedChart` renders the P10/P50/P90 forecast bands in `DemandForecastTab`. No chart library is used for tabular views.

## Dependencies

| Package | Version | Purpose |
|---------|---------|---------|
| `react` | 18.3.1 | UI framework |
| `react-router-dom` | 6.26.1 | Client-side routing |
| `recharts` | 2.12.7 | Forecast band charts |
| `@smartretail/auth` | local | Auth + API client |
| `vitest` | 2.1.9 | Test runner |
| `@testing-library/react` | 16.3.0 | Component testing |
