# Store Manager Dashboard MFE

React micro-frontend for store-level inventory monitoring. Shows real-time KPIs, ATP levels, and active stock alerts for a selected distribution centre.

**Dev port:** `5173`  
**Backend:** ARS (`GET /v1/reporting/store-manager`)  
**Flow:** Flow 4 — ARS → Store Manager Dashboard

## Features

- Distribution centre selector — persists the selected DC across page refreshes.
- KPI row — ATP, fill rate, active alerts, and forecast coverage for the selected DC.
- Active stock alerts table — severity-badged, sorted by severity (CRITICAL first).
- Data freshness indicator — shows time since last poll with a manual refresh button (5-second auto-poll interval).

## Components

| Component | Description |
|-----------|-------------|
| `StoreDashboard.tsx` | Root page component — orchestrates data fetch and layout |
| `KpiCard.tsx` | Single KPI tile with label, value, and trend indicator |
| `KpiRow.tsx` | Horizontal row of `KpiCard` instances |
| `AlertList.tsx` | Paginated table of `StockAlert` objects |
| `SeverityBadge.tsx` | Coloured pill — `CRITICAL` (red), `HIGH` (amber), `MEDIUM` (yellow) |
| `DcSelector.tsx` | `<select>` for choosing the active DC |
| `DataFreshnessIndicator.tsx` | "Last updated Xs ago" label + Refresh button |

## Running

```bash
npm install
npm run dev        # → http://localhost:5173
npm run build      # production build to dist/
npm run preview    # serve the production build locally
```

## Testing

```bash
npm test                 # run once (Vitest)
npm run test:watch       # watch mode
npm run test:coverage    # coverage report — 80 % line threshold
```

Test files live in `src/test/components/`. Coverage is measured on `src/components/**` only.

## Auth

Wraps the shared `@smartretail/auth` library (`mfe/shared/auth/`). In local mode auth is bypassed — no Cognito required. In AWS mode a Cognito PKCE flow authenticates the user and the JWT is injected into every API call.

## Environment / proxy

`vite.config.ts` proxies `/api` → `http://localhost:8083` in local mode. In AWS mode `VITE_API_BASE_URL` is set at build time by the CDK stack.

## Dependencies

| Package | Version | Purpose |
|---------|---------|---------|
| `react` | 18.3.1 | UI framework |
| `react-router-dom` | 6.26.1 | Client-side routing |
| `@smartretail/auth` | local | Cognito auth + API client |
| `vitest` | 2.1.9 | Test runner |
| `@testing-library/react` | 16.3.0 | Component testing |
