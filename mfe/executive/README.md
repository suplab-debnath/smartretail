# Executive Dashboard MFE

React micro-frontend for executive-level supply chain visibility. Presents KPI cards with trend indicators, historical charts, supplier ranking, and stockout analysis — all driven by pre-aggregated data from ARS.

**Dev port:** `5175`  
**Backend:** ARS (`GET /v1/reporting/executive`)  
**Flow:** Flow 8 — Executive Dashboard

## KPIs displayed

| Metric | Trend type | Description |
|--------|-----------|-------------|
| Forecast Accuracy | IMPROVING / DEGRADING / STABLE | 100 − MAPE % |
| MAPE | IMPROVING / DEGRADING / STABLE | Mean Absolute Percentage Error (7-day rolling) |
| OTD Rate | IMPROVING / DEGRADING / STABLE | On-time delivery across all suppliers |
| Stockout Incidents | INCREASING / DECREASING / STABLE | Count of CRITICAL alerts in the period |
| Inventory Carrying Cost | INCREASING / DECREASING / STABLE | Aggregated across all DCs |
| Replenishment Lead Time | IMPROVING / DEGRADING / STABLE | Average days from PO creation to delivery |

## Components

| Component | Description |
|-----------|-------------|
| `ExecutiveDashboard.tsx` | Root page — data fetch, layout, drill-down state |
| `KpiCard.tsx` | KPI tile with value, trend arrow, optional subtitle; clickable for drill-down; renders as `<button>` when `onClick` provided |
| `MapeTrendChart.tsx` | Line chart — MAPE over the last 30 days (Recharts) |
| `StockoutChart.tsx` | Bar chart — daily stockout incident count |
| `DeliveryHistogram.tsx` | Histogram of delivery time distribution |
| `CycleTimeChart.tsx` | Line chart — replenishment cycle time trend |
| `SupplierRankingTable.tsx` | Ranked supplier table — OTD badge coloured green (≥ 90 %), amber (75–89 %), red (< 75 %); exception count badge |
| `ForecastHistoryTable.tsx` | MAPE history by SKU |
| `StockoutHistoryTable.tsx` | Stockout incidents by SKU and DC |
| `CycleTimeHistoryTable.tsx` | Cycle time history by supplier |

## Running

```bash
npm install
npm run dev        # → http://localhost:5175
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
- `KpiCard` — all five trend values render correct arrow and label; `onClick` renders as `<button>`; `isExpanded` prop toggles "Click to collapse" / "Click to explore" hint.
- `SupplierRankingTable` — OTD color thresholds; exception badge vs dash; rank starts at 1; empty state renders no rows.

## Auth

Same `@smartretail/auth` pattern. Executive Dashboard is read-only; no write API calls.

## Dependencies

| Package | Version | Purpose |
|---------|---------|---------|
| `react` | 18.3.1 | UI framework |
| `react-router-dom` | 6.26.1 | Client-side routing |
| `recharts` | 2.12.7 | All dashboard charts |
| `@smartretail/auth` | local | Auth + API client |
| `vitest` | 2.1.9 | Test runner |
| `@testing-library/react` | 16.3.0 | Component testing |
