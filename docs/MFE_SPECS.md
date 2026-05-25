# MFE Implementation Specifications
 
Three MFEs are in prototype scope: Store Manager Dashboard, SC Planner Console,
Executive Insights Dashboard.
 
All MFEs use: React 18 · TypeScript · Tailwind CSS · Recharts · @aws-amplify/auth 6.x
 
---
 
## Shared: mfe/shared/auth/
 
This is a local TypeScript package used by all three MFEs.
Not published to npm — referenced via relative path in each MFE's package.json.
 
### useAuth hook
 
```typescript
// mfe/shared/auth/src/useAuth.ts
 
interface User {
  sub: string;
  email: string;
  groups: string[];  // Cognito groups: STORE_MANAGER | SC_PLANNER | EXECUTIVE | ADMIN
}
 
interface AuthState {
  user: User | null;
  token: string | null;  // stored in memory only — NEVER localStorage
  isLoading: boolean;
  isAuthenticated: boolean;
  signIn: () => Promise<void>;   // redirects to Cognito hosted UI
  signOut: () => Promise<void>;
  hasRole: (role: string) => boolean;
}
 
export function useAuth(): AuthState;
```
 
### AuthProvider
 
```typescript
// Wraps the entire app
// Handles:
// 1. Cognito PKCE flow (authorization_code grant)
// 2. Token storage in memory (React state only)
// 3. Auto-refresh: schedule refresh 5 minutes before expiry
// 4. Redirect to /callback after Cognito login
// 5. Redirect to /logout after sign out
 
export function AuthProvider({ children }: { children: React.ReactNode }): JSX.Element;
```
 
### API Client factory
 
```typescript
// mfe/shared/auth/src/apiClient.ts
 
export function createApiClient(baseUrl: string, getToken: () => string | null) {
  const instance = axios.create({ baseURL: baseUrl });
 
  instance.interceptors.request.use(config => {
    const token = getToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });
 
  instance.interceptors.response.use(
    response => response,
    async error => {
      if (error.response?.status === 401) {
        // Attempt token refresh once, then redirect to login
        try {
          await refreshToken();
          return instance.request(error.config);
        } catch {
          window.location.href = '/login';
        }
      }
      return Promise.reject(error);
    }
  );
 
  return instance;
}
```
 
---
 
## MFE 1: Store Manager Dashboard
 
Location: `mfe/store-manager/`
Cognito pool: Internal
Required role: STORE_MANAGER (or SC_PLANNER/ADMIN can also access)
 
### Pages / Routes
 
```
/               → redirect to /dashboard
/dashboard      → main dashboard (requires dcId selection)
/callback       → Cognito PKCE callback handler
/logout         → sign out page
```
 
### Dashboard Page Components
 
```typescript
// Pages/DashboardPage.tsx
// Layout:
// - DcSelector (top) — dropdown of available DCs, drives all data
// - KpiRow (below selector) — 4 KPI cards
// - AlertList (left column) — active stock alerts
// - ForecastSummary (right column) — latest p50 forecast for top SKUs
// - DataFreshnessIndicator (footer) — "Data as of HH:MM"
```
 
#### DcSelector Component
 
```typescript
interface DcSelectorProps {
  selectedDc: string;
  onDcChange: (dcId: string) => void;
}
 
// Available DCs: DC-LONDON | DC-MANCHESTER | DC-BIRMINGHAM
// Hardcoded for prototype. In production: fetched from API.
```
 
#### KpiRow Component (4 cards)
 
```typescript
// Card 1: Low Stock Alerts
//   - Total count (CRITICAL + HIGH + MEDIUM)
//   - Breakdown by severity (red/amber/yellow chips)
 
// Card 2: On-Hand Units
//   - Sum of on_hand across all SKUs for selected DC
 
// Card 3: Pending Replenishment Orders
//   - Count of POs in PENDING_APPROVAL or APPROVED status for DC
 
// Card 4: Forecast Coverage
//   - Percentage of SKUs that have a forecast for today
```
 
#### AlertList Component
 
```typescript
interface Alert {
  alertId: string;
  skuId: string;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM';
  onHand: number;
  reorderPoint: number;
  raisedAt: string;
}
 
// Renders a table of active alerts, sorted by severity then raisedAt
// CRITICAL rows: red background
// HIGH rows: amber background
// MEDIUM rows: yellow background
// Paginated: 10 per page
```
 
#### API Call
 
```typescript
// GET /v1/dashboard/store-manager?dcId=DC-LONDON
// Called on mount and on dcId change
// Poll every 60 seconds (setInterval)
// Show loading skeleton during fetch
// Show error state with "Last known data" if fetch fails
```
 
---
 
## MFE 2: SC Planner Console
 
Location: `mfe/sc-planner/`
Cognito pool: Internal
Required role: SC_PLANNER (or ADMIN)
 
### Pages / Routes
 
```
/                           → redirect to /dashboard
/dashboard                  → main dashboard with tabs
/dashboard/exceptions       → exception queue tab (Flow 9.1)
/dashboard/inventory        → inventory overview by DC tab (Flow 9.2)
/dashboard/forecast         → demand forecast view tab (Flow 9.3)
/dashboard/approvals        → PO approval workflows tab (Flow 9.5)
/dashboard/supplier-orders  → supplier order tracking tab (Flow 9.6)
/dashboard/performance      → supplier performance scorecard tab (Flow 9)
/callback, /logout
```
 
### Tabs and Surfaces
 
#### Tab 1: Exception Queue (Flow 9.1)
 
Prioritised list of active LOW_STOCK and OVERSTOCK alerts awaiting planner action.
 
```typescript
// GET /v1/inventory/alerts?status=ACTIVE
// Sorted by severity: CRITICAL → HIGH → MEDIUM
// Severity classification badge on each row
 
interface Alert {
  alertId: string;
  skuId: string;
  dcId: string;
  alertType: 'LOW_STOCK' | 'OVERSTOCK';
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM';
  onHand: number;
  reorderPoint: number;
  raisedAt: string;
}
 
// Components:
// - ExceptionQueueTable: sortable by severity, DC, SKU, raised time
// - SeverityBadge: CRITICAL=red, HIGH=amber, MEDIUM=yellow
// - AlertTypeTag: LOW_STOCK / OVERSTOCK
// - ActionButton: links to Replenishment Action Trigger (Tab 6)
```
 
#### Tab 2: Inventory Overview by DC (Flow 9.2)
 
On-hand, in-transit, and reorder status per Distribution Centre.
 
```typescript
// GET /v1/inventory/positions?dcId={selectedDc}
// GET /v1/inventory/alerts?status=ACTIVE&dcId={selectedDc}
 
// Components:
// - DcSelector: dropdown DC-LONDON | DC-MANCHESTER | DC-BIRMINGHAM
// - InventoryGrid: cards per SKU showing on_hand / in_transit / reserved
// - ReorderStatusChip: OK / REORDER_SOON / CRITICAL per SKU
//   CRITICAL: ATP <= 0
//   REORDER_SOON: ATP < reorder_point
//   OK: ATP >= reorder_point
```
 
#### Tab 3: Demand Forecast View (Flow 9.3)
 
SKU × DC level forecast with P10/P50/P90 probability bands, units sold vs forecast, and accuracy indicator.
 
```typescript
// GET /v1/dashboard/sc-planner (forecastAccuracy block)
// GET /v1/inventory/positions (units sold proxy via on_hand change)
// GET /v1/forecast/{skuId}/{dcId} (forecast bands per horizon)
 
interface ForecastBand {
  forecastDate: string;
  p10: number;
  p50: number;
  p90: number;
  actualUnits?: number;   // null for future dates
}
 
// Components:
// - SkuDcSelector: choose SKU × DC combination
// - ForecastAreaChart: Recharts AreaChart with 3 band areas (P10/P50/P90)
//   + ActualUnits line overlay
// - ForecastAccuracyIndicator: MAPE % badge — green < 10%, amber 10–20%, red > 20%
// - HorizonSelector: 7 / 14 / 30 day horizon toggle
```
 
#### Tab 4: Stockout Risk Indicators (Flow 9.4)
 
Derived risk flags per SKU based on ATP vs reorder_point. Used to prioritise planner attention.
 
```typescript
// Data derived from GET /v1/inventory/positions (no separate endpoint)
// Risk classification:
//   CRITICAL: ATP <= 0
//   HIGH:     ATP < reorder_point × 0.5
//   MODERATE: ATP < reorder_point
 
// Components:
// - StockoutRiskTable: sortable by risk level, SKU, DC
//   Columns: SKU | DC | Category | On-Hand | ATP | Reorder Point | Risk
// - RiskFlag: MODERATE=yellow chip | HIGH=orange chip | CRITICAL=red chip
// - BulkTriggerButton: select multiple HIGH/CRITICAL SKUs → initiate replenishment
```
 
#### Tab 5: Approval Workflows (Flow 9.5 / Flow 3)
 
PO drafts pending planner sign-off before supplier dispatch. Full test covered in Flow 3.
 
```typescript
interface PurchaseOrder {
  poId: string;
  supplierId: string;
  skuId: string;
  dcId: string;
  quantity: number;
  totalValue: number;
  workflowStatus: string;
  version: number;
  createdAt: string;
}
 
// Components:
// - PendingApprovalTable: list of PENDING_APPROVAL POs
// - PurchaseOrderDetail: expandable with line items
// - ApproveButton: POST /v1/replenishment/orders/{poId}/approve
//   + X-Idempotency-Key (crypto.randomUUID(), stored per poId)
// - RejectButton + RejectModal: POST /v1/replenishment/orders/{poId}/reject
// - OptimisticUpdate: immediate UI response before API confirmation
 
// Approve flow:
// 1. Disable button immediately (prevent double-click)
// 2. POST approve with X-Idempotency-Key
// 3. 200 → remove PO from list, show success toast
// 4. 409 INVALID_STATUS_TRANSITION → show error toast "PO status changed — refresh"
// 5. 4xx/5xx → error toast, re-enable button
```
 
#### Tab 6: Supplier Order Tracking (Flow 9.6)
 
PO list with supplier, ETA, fulfilment status, and shipment progress.
 
```typescript
// GET /v1/dashboard/supplier-orders          → ARS (supplier.supplier_pos + supplier_records, intra-schema join)
// Optional query param: ?status=DISPATCHED   → filter by shipment status
// Supplier name is embedded in each order row — no separate SUP call needed
 
interface SupplierOrder {
  poId: string;
  supplierId: string;
  supplierName: string;
  skuId: string;
  dcId: string;
  quantity: number;
  workflowStatus: string;
  confirmedAt?: string;
  dispatchedAt?: string;
  eta?: string;
  shipmentStatus: 'PENDING' | 'CONFIRMED' | 'DISPATCHED' | 'DELIVERED' | 'EXCEPTION';
}
 
// Components:
// - SupplierOrderTable: sortable by supplier, ETA, status
//   Columns: Supplier | SKU | DC | Qty | Status | ETA | Shipment Progress
// - ShipmentProgressBar: visual step indicator (CONFIRMED → DISPATCHED → DELIVERED)
// - ExceptionBadge: red flag on exception rows
// - StatusFilter: dropdown PENDING / CONFIRMED / DISPATCHED / DELIVERED / EXCEPTION
```
 
#### Tab 7: Replenishment Action Trigger (Flow 9.7)
 
Manual override — initiate replenishment for flagged items.
 
```typescript
// POST /v1/replenishment/orders
// Creates a DRAFT PO that RE will process (bypasses automatic FIFO trigger)
 
interface TriggerReplenishmentRequest {
  skuId: string;
  dcId: string;
  quantity: number;   // planner-specified override quantity
  notes?: string;
}
 
// Components:
// - ReplenishmentTriggerModal: opens from ExceptionQueue or StockoutRisk tables
//   Fields: SKU (pre-filled), DC (pre-filled), Quantity (editable), Notes
// - QuantitySuggestion: displays recommended quantity from replenishment_rules
// - ConfirmButton: POST /v1/replenishment/orders
//   201 → success toast "Replenishment order DRAFT-{poId} created"
//   409 → "A PENDING order already exists for this SKU/DC"
```
 
#### Tab 8: Forecast Adjustment Controls (Flow 9.8)
 
Incorporate promotional uplift signals (PPS-driven) into the demand forecast view.
 
```typescript
// Client-side computation — no separate API endpoint
// Applies promotional uplift % to P50 forecast values
// Uplift signal comes from Pricing & Promotions Service (PPS) events
 
interface PromotionalUplift {
  skuId: string;
  dcId: string;
  upliftPercent: number;   // e.g. 25 = 25% uplift on P50
  promotionStartDate: string;
  promotionEndDate: string;
  source: 'MANUAL' | 'PPS_EVENT';
}
 
// Components:
// - UpliftInputPanel: numeric input for uplift % + date range picker
// - AdjustedForecastOverlay: dashed line on ForecastAreaChart showing P50 × (1 + uplift/100)
// - UpliftBadge: "Promo uplift applied: +25%" displayed on chart header
// - ResetButton: clears uplift and returns to raw P50
```
 
### SC Planner Dashboard Summary API Call
 
```typescript
// GET /v1/dashboard/sc-planner
// Called on mount — drives tab badges
// Returns:
//   pendingApprovalCount  → badge on Approval Workflows tab
//   activeAlertCount      → badge on Exception Queue tab
//   forecastAccuracy      → MAPE % shown in Forecast View tab header
//   dataFreshness         → footer timestamp
 
// Tab-specific data fetched when each tab is first activated (lazy load)
// Poll interval: 2 minutes (planners act on near-real-time data)
```
 
---
 
## MFE 3: Executive Insights Dashboard
 
Location: `mfe/executive/`
Cognito pool: Internal
Required role: EXECUTIVE (or SC_PLANNER/ADMIN)
 
### Pages / Routes
 
```
/               → redirect to /dashboard
/dashboard      → executive KPI dashboard
/callback, /logout
```
 
### Dashboard Layout
 
Single page, scrollable. Four sections:
 
#### Section 1: KPI Scorecard Row (top — 4 cards)
 
```typescript
// Card 1: Fulfilment Rate
//   - Platform-wide order fill rate %
//   - Trend arrow vs prior period
//   - Source: replenishment.purchase_orders COMPLETED / total
 
// Card 2: On-Time Delivery %
//   - Aggregate supplier OTD across all 5 suppliers
//   - Colour: green ≥ 90%, amber 75–90%, red < 75%
//   - Source: supplier.shipment_updates vs lead_time_days
 
// Card 3: Forecast Accuracy (MAPE)
//   - Latest MAPE formatted as accuracy %: (1 − MAPE) × 100
//   - Trend badge: IMPROVING / STABLE / DEGRADING
//   - Source: forecasting.forecast_runs latest row
 
// Card 4: Replenishment Lead Time
//   - Average days from stock alert raised to supplier PO confirmed
//   - Trend: STABLE / IMPROVING / DEGRADING
//   - Source: avg(supplier_pos.confirmed_at − stock_alerts.raised_at)
```
 
#### Section 2: Stockout Incidents (Flow 8.2)
 
```typescript
// Recharts BarChart — CRITICAL stock alert count by DC and product category
// X-axis: last 30 days (grouped by week)
// Y-axis: stockout incident count
// Series: one bar series per DC (DC-LONDON, DC-MANCHESTER, DC-BIRMINGHAM)
// Stacked by product category: Beverages / Snacks / Dry Goods / Chilled
// Tooltip: date range + DC + category + count
// Source: GET /v1/dashboard/executive → kpis.stockoutIncidents
```
 
#### Section 3: Forecast Accuracy MAPE Trend (Flow 8.3)
 
```typescript
// Recharts LineChart
// X-axis: date (last 30 days)
// Y-axis: MAPE value (0.0 to 0.20)
// Reference line at MAPE = 0.15 (threshold — red dashed)
// Data: one point per forecast_run from seed data (30 points)
// Tooltip: date + MAPE value + "Within threshold / Threshold breached"
// Source: GET /v1/dashboard/executive → kpis.forecastAccuracy.history
```
 
#### Section 4: Supplier Performance Comparison + Delivery Histogram (Flow 8.5 / 8.6)
 
```typescript
// Left panel: SupplierRankingTable
//   Columns: Rank | Supplier | OTD % | Fill Rate | Avg Lead Time | Open Exceptions
//   Colour coding:
//     OTD ≥ 90%: green
//     OTD 75–90%: amber
//     OTD < 75%: red
//   Sortable by all columns
//   Source: GET /v1/dashboard/executive → kpis.supplierPerformance
 
// Right panel: Recharts BarChart — Delivery Performance Histogram
//   X-axis: 5 suppliers
//   Y-axis: shipment count
//   3 grouped bars per supplier: Early | On-Time | Late
//   Colour: Early=blue, On-Time=green, Late=red
//   Source: same supplier performance data
```
 
#### Section 5: Secondary KPI Row (bottom — 3 cards)
 
```typescript
// Card 5: Stockout Frequency
//   - Count of CRITICAL stock alerts in last 30 days
//   - Trend arrow vs previous 30 days
 
// Card 6: Inventory Carrying Cost Trend (Flow 8.7)
//   - Directional view: current period total value-at-risk vs prior period
//   - Source: sum(inventory_positions.on_hand × cost_per_unit from replenishment_rules)
//   - Displayed as % change: "+3.2% vs prior period"
 
// Card 7: Top Stockout SKUs (Flow 8.9)
//   - Mini-table: top 5 highest-impact stockout items in the period
//   - Columns: SKU | Category | DC | Stockout Days | Estimated Impact
//   - Estimated impact = stockout_days × p50_forecast × cost_per_unit
```
 
### API Call
 
```typescript
// GET /v1/dashboard/executive
// No parameters required
// Poll every 5 minutes (executive data changes slowly)
// Show "Last updated: HH:MM" in footer (dataFreshness from response)
```
 
---
 
## Config Injection (all MFEs)
 
Each MFE reads API Gateway endpoint from `window.SMARTRETAIL_CONFIG`:
 
```typescript
// mfe/shared/config.ts
export interface SmartRetailConfig {
  apiGatewayEndpoint: string;
  cognitoPoolId: string;
  cognitoClientId: string;
  cognitoDomain: string;
  env: string;
}
 
export function getConfig(): SmartRetailConfig {
  return (window as any).SMARTRETAIL_CONFIG;
}
```
 
This config object is injected by a `config.js` file in the S3 bucket root,
loaded before the React app bundle via a `<script>` tag in `index.html`:
 
```html
<script src="/config.js"></script>
```
 
`config.js` is generated by CDK after resource creation:
```javascript
window.SMARTRETAIL_CONFIG = {
  apiGatewayEndpoint: "https://{id}.execute-api.us-east-1.amazonaws.com/internal",
  cognitoPoolId: "us-east-1_XXXXXXXXX",
  cognitoClientId: "XXXXXXXXXXXXXXXX",
  cognitoDomain: "smartretail-{env}.auth.us-east-1.amazoncognito.com",
  env: "dev"
};
```
 
---
 
## MFE Build and Deploy
 
Each MFE is built independently:
 
```bash
cd mfe/store-manager
npm install
npm run build       # Vite build → dist/
aws s3 sync dist/ s3://smartretail-mfe-dev-store-manager-{account}/ --delete
aws cloudfront create-invalidation --distribution-id {CF_ID} --paths "/*"
```
 
CDK creates a CloudFront distribution with OAC for each MFE bucket.
The `config.js` file must be uploaded to S3 before the app bundle.

---

## Supplier Portal MFE

**Location:** `mfe/supplier/`
**Port (local dev):** 5177
**Auth:** Supplier Cognito pool (separate from internal pool)
**Allowed role:** `SUPPLIER_ADMIN`
**Data source:** `GET /v1/supplier/orders` on the SUP service (port 8085 locally, ALB `/v1/supplier/*` in AWS)

### Purpose

External-facing portal for supplier users to view their purchase orders and
shipment status in real time. Authenticates against the supplier Cognito user
pool, keeping supplier access completely separate from internal staff.

### Components

| Component                | File                                        | Description                                        |
| ------------------------ | ------------------------------------------- | -------------------------------------------------- |
| `SupplierPortal`         | `src/components/SupplierPortal.tsx`         | Root layout, auth gate, summary cards, order table |
| `OrderListTab`           | `src/components/OrderListTab.tsx`           | Paginated PO table sortable by ETA/status/SKU      |
| `ShipmentStatusBadge`    | `src/components/ShipmentStatusBadge.tsx`    | Colour-coded status chip                           |
| `DataFreshnessIndicator` | `src/components/DataFreshnessIndicator.tsx` | "Last updated N ago" footer                        |

### Hook

`useSupplierOrders` — polls `GET /v1/supplier/orders` every 60 s.
In local mode, sends `X-Dev-Role: SUPPLIER_ADMIN` header (no real token).

### Routes

| Path        | Component          |
| ----------- | ------------------ |
| `/portal`   | `SupplierPortal`   |
| `/callback` | `AuthCallback`     |
| `/logout`   | Signed-out message |

### Config Injection

Same `window.SMARTRETAIL_CONFIG` pattern as internal MFEs.
In AWS, `cognitoPoolId` / `cognitoClientId` point to the supplier pool
(SSM params: `/smartretail/{env}/cognito/supplier-pool-id`, `supplier-client-id`).

### Build / Deploy

```bash
cd mfe/supplier
npm install
npm run dev      # local dev server on http://localhost:5177
npm run build    # Vite build → dist/
aws s3 sync dist/ s3://smartretail-mfe-{env}-supplier-{account}/ --delete
aws cloudfront create-invalidation --distribution-id {CF_ID} --paths "/*"
```
