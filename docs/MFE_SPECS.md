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
/               → redirect to /dashboard
/dashboard      → main dashboard with tabs
/dashboard/approvals    → PO approval queue tab
/dashboard/inventory    → inventory overview tab
/dashboard/performance  → supplier performance tab (Flow 9)
/callback, /logout
```
 
### Tabs
 
#### Tab 1: Pending Approvals (Flow 3)
 
Components:
- PendingApprovalTable — list of PENDING_APPROVAL POs
- PurchaseOrderDetail — expandable detail with line items
- ApproveButton — triggers POST /v1/replenishment/orders/{poId}/approve
- RejectButton + RejectModal — triggers POST /v1/replenishment/orders/{poId}/reject
- OptimisticUpdate — immediately marks PO as processing in UI, then confirms/reverts on API response
 
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
 
// X-Idempotency-Key: generated client-side (crypto.randomUUID())
//   stored per poId in component state to prevent double-click double-approve
```
 
**Approve flow:**
1. User clicks "Approve"
2. Disable button immediately (prevent double-click)
3. Generate idempotency key (crypto.randomUUID())
4. POST /v1/replenishment/orders/{poId}/approve with X-Idempotency-Key header
5. On 200: remove PO from pending list, show success toast
6. On 409 (INVALID_STATUS_TRANSITION): show error toast "PO status changed — refresh list"
7. On 4xx/5xx: show error toast, re-enable button
 
**Reject flow:**
1. User clicks "Reject" → modal opens asking for rejection reason (required)
2. User submits → POST /v1/replenishment/orders/{poId}/reject with reason in body
3. On 200: remove PO from list, show success toast "PO rejected"
 
#### Tab 2: Inventory Overview
 
```typescript
// GET /v1/inventory/positions (no dcId filter — planner sees all)
// GET /v1/inventory/alerts?status=ACTIVE
 
// Components:
// - DcSummaryGrid: cards per DC showing alert counts
// - SkuAlertTable: sortable table by severity, skuId, dcId
```
 
#### Tab 3: Supplier Performance (Flow 9)
 
```typescript
// GET /v1/dashboard/supplier-performance
 
interface SupplierPerformance {
  supplierId: string;
  supplierName: string;
  onTimeDeliveryRate: number;   // 0.0 to 1.0
  poAcknowledgementSlaCompliance: number;
  openExceptions: number;
  avgLeadTimeVarianceDays: number;
  totalPoCount: number;
  totalPoValue: number;
}
 
// Components:
// - SupplierScorecardTable
//   Columns: Supplier Name | On-Time % | SLA Compliance | Open Exceptions | Avg Lead Time Variance | Total PO Value
//   Color coding:
//     on-time >= 90%: green
//     on-time 75-90%: amber
//     on-time < 75%: red
//   Sortable by all columns
//   Recharts BarChart below table showing on-time rates per supplier
 
// Poll: 5 minutes (supplier data changes less frequently)
```
 
### SC Planner Dashboard API Call
 
```typescript
// GET /v1/dashboard/sc-planner
// Shows:
// - pendingApprovalCount (badge on Approvals tab)
// - activeAlertCount (badge on Inventory tab)
// - forecastAccuracy.latestMape + status
// - dataFreshness
 
// Called on mount. Tab-specific data fetched when tab is activated.
```
 
---
 
## MFE 3: Executive Insights Dashboard
 
Location: `mfe/executive/`
Cognito pool: Internal
Required role: EXECUTIVE (or SC_PLANNER/ADMIN)
 
### Pages / Routes
 
```
/               → redirect to /dashboard
/dashboard      → executive KPI dashboard
/callback, /logout
```
 
### Dashboard Layout
 
Single page, no tabs. Three sections:
 
#### Section 1: KPI Scorecards (top row — 3 cards)
 
```typescript
// Card 1: Forecast Accuracy
//   - Latest MAPE value formatted as percentage: (1 - MAPE) × 100
//   - Trend badge: IMPROVING / STABLE / DEGRADING
//   - Colour: green if < 10% error, amber if 10-20%, red if > 20%
 
// Card 2: Stockout Frequency
//   - Count of CRITICAL stock alerts in last 30 days
//   - Trend arrow: up or down vs previous 30 days
 
// Card 3: Replenishment Cycle Time
//   - Average days from DRAFT to DISPATCHED
//   - Trend: STABLE / IMPROVING / DEGRADING
```
 
#### Section 2: MAPE Trend Chart (Flow 8)
 
```typescript
// Recharts LineChart
// X-axis: date (last 30 days)
// Y-axis: MAPE value (0.0 to 0.20)
// Reference line at MAPE = 0.15 (threshold — red dashed)
// Data points: one per forecast_run from seed data
// Tooltip: date + MAPE value + "Within threshold / Threshold breached"
 
// Data source: GET /v1/dashboard/executive
// Uses kpis.forecastAccuracy.history array
```
 
#### Section 3: Forecast Accuracy History Table
 
```typescript
// Simple table below the chart
// Columns: Date | MAPE | Status | Run Duration
// Last 10 rows
// Status: green "Within threshold" or red "Threshold breached"
```
 
### API Call
 
```typescript
// GET /v1/dashboard/executive
// No parameters required
// Poll every 5 minutes (executive data changes slowly)
// Show "Last updated: HH:MM" in footer
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
 
 