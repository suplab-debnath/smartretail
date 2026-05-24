# Supplier Portal MFE

React micro-frontend for external supplier users. Provides a read-only view of purchase orders, shipment statuses, and summary counts — authenticated against the dedicated supplier Cognito pool.

**Dev port:** `5177`  
**Backend:** SUP service — `GET /v1/supplier/orders` (port 8085 locally, ALB `/v1/supplier/*` on AWS)  
**Auth pool:** `smartretail-supplier-{env}` (Cognito), group `SUPPLIER_ADMIN`

## Components

| Component                    | Description                                                                                      |
| ---------------------------- | ------------------------------------------------------------------------------------------------ |
| `SupplierPortal.tsx`         | Root layout — auth gate, header with user email + exception badge, summary cards, tab shell      |
| `OrderListTab.tsx`           | Paginated (10/page) sortable table — PO Reference, SKU, DC, Qty, Status, ETA, Last Update        |
| `ShipmentStatusBadge.tsx`    | Color-coded chip: PENDING=grey, CONFIRMED=blue, DISPATCHED=amber, DELIVERED=green, EXCEPTION=red |
| `DataFreshnessIndicator.tsx` | "Data as of HH:MM" footer driven by `dataFreshness` timestamp from the API                       |

## Hook

`useSupplierOrders.ts` — polls `GET /v1/supplier/orders` every 60 s. Returns `{ orders, dataFreshness, isLoading, error, refresh }`. In local mode sends `X-Dev-Role: SUPPLIER_ADMIN` header instead of a Cognito Bearer token.

## Routes

| Path        | Component                         |
| ----------- | --------------------------------- |
| `/portal`   | `SupplierPortal` (main view)      |
| `/callback` | `AuthCallback` (Cognito redirect) |
| `/logout`   | Signed-out confirmation           |

## Running

```bash
# Prerequisite: build the shared auth package first
cd mfe/shared/auth && npm install && npm run build

cd mfe/supplier
npm install
npm run dev     # → http://localhost:5177
npm run build   # produces dist/
npm run preview
```

Or via Make from the repo root:

```bash
make local-mfe-supplier
```

In local mode the auth library returns a mock `SUPPLIER_ADMIN` user automatically. The Vite proxy forwards `/v1/supplier` → `http://localhost:8085`, so the SUP service must be running.

## Testing

```bash
npm test
npm run test:watch
npm run test:coverage    # 80 % line / 70 % branch threshold
```

## Auth

Uses `@smartretail/auth` (shared package at `mfe/shared/auth/`). In AWS mode the app reads `window.SMARTRETAIL_CONFIG.cognitoPoolId` and `cognitoClientId` injected by `scripts/generate-mfe-config.sh` at deploy time. The supplier Cognito pool is separate from the internal pool — supplier users cannot access internal MFEs and vice versa.

## Dependencies

| Package                  | Version | Purpose             |
| ------------------------ | ------- | ------------------- |
| `react`                  | 18.3.1  | UI framework        |
| `react-router-dom`       | 6.x     | Client-side routing |
| `@smartretail/auth`      | local   | Auth + API client   |
| `vitest`                 | 2.x     | Test runner         |
| `@testing-library/react` | 16.x    | Component testing   |
